package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.SopTemplate;
import org.ruoyi.ipd.domain.SopTemplateInstance;
import org.ruoyi.ipd.dto.SopTemplateSaveReq;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.SopTemplateInstanceMapper;
import org.ruoyi.ipd.mapper.SopTemplateMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-3.3 SOP 模板版本管理与实例快照（BR-IPD-07：actionCode 维度）；
 * 版本机 DRAFT --publish--> PUBLISHED --被新版本替代--> ARCHIVED；
 * AC-IPD-20 生物特征动作发布关键词校验；AC-IPD-27 快照解耦。
 */
@Tag("dev")
class SopTemplateServiceTest {

    private SopTemplateMapper templateMapper;
    private SopTemplateInstanceMapper instanceMapper;
    private AuditLogService auditLogService;
    private ProjectMemberMapper projectMemberMapper;
    private ProjectMapper projectMapper;
    private SopTemplateService service;

    private static final IpdActor ADMIN = new IpdActor(1L, "admin", "SUPER_ADMIN", 100L);
    private static final IpdActor MARKET_PM = new IpdActor(2L, "market", "MARKET_PM", 100L);
    private static final IpdActor RD_PM = new IpdActor(3L, "rd", "RD_PM", 100L);
    private static final IpdActor GUEST = new IpdActor(4L, "guest", "GUEST", 100L);

    @BeforeEach
    void setUp() {
        // MP 单元测试必备：lambdaUpdate().set(SopTemplate::xxx) 会立即解析 lambda→列名，
        // 需 TableInfo 缓存（P1-3.3 P133 教训 1）
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), SopTemplate.class);

        templateMapper = mock(SopTemplateMapper.class);
        instanceMapper = mock(SopTemplateInstanceMapper.class);
        auditLogService = mock(AuditLogService.class);
        projectMemberMapper = mock(ProjectMemberMapper.class);
        projectMapper = mock(ProjectMapper.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(templateMapper.insert(any(SopTemplate.class))).thenAnswer(inv -> {
            SopTemplate t = inv.getArgument(0);
            if (t.getId() == null) t.setId(System.nanoTime());
            return 1;
        });
        when(instanceMapper.insert(any(SopTemplateInstance.class))).thenAnswer(inv -> {
            SopTemplateInstance i = inv.getArgument(0);
            if (i.getId() == null) i.setId(System.nanoTime());
            return 1;
        });
        when(instanceMapper.updateById(any(SopTemplateInstance.class))).thenReturn(1);
        when(templateMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(projectMapper.selectById(anyLong())).thenReturn(stubProject());
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        service = new SopTemplateService(
            templateMapper, instanceMapper, auditLogService, projectMemberMapper, projectMapper);
    }

    private static Project stubProject() {
        Project p = new Project();
        p.setId(100L);
        p.setTenantId(null);
        return p;
    }

    private static SopTemplate draft(String actionCode, long version, String content) {
        return SopTemplate.builder()
            .id(100L).actionCode(actionCode).title("t").content(content)
            .version(version).status(SopTemplate.Status.DRAFT).delFlag("0").build();
    }

    private static SopTemplate published(String actionCode, long version, String content) {
        return SopTemplate.builder()
            .id(100L).actionCode(actionCode).title("t").content(content)
            .version(version).status(SopTemplate.Status.PUBLISHED)
            .effectiveFrom(new java.util.Date()).effectiveTo(null)
            .delFlag("0").build();
    }

    private static SopTemplate archived(String actionCode, long version, String content) {
        return SopTemplate.builder()
            .id(101L).actionCode(actionCode).title("t").content(content)
            .version(version).status(SopTemplate.Status.ARCHIVED)
            .effectiveFrom(new java.util.Date(0)).effectiveTo(new java.util.Date())
            .delFlag("0").build();
    }

    // ========== listByActionCode ==========

    @Test
    @DisplayName("listByActionCode：CHAR_LENGTH(content) 计算 content_len；version 倒序")
    void listByActionCodeReturnsLightweight() {
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            SopTemplate.builder().id(2L).actionCode("P03").title("v2").version(2L)
                .status(SopTemplate.Status.PUBLISHED).contentLen(20L).build(),
            SopTemplate.builder().id(1L).actionCode("P03").title("v1").version(1L)
                .status(SopTemplate.Status.ARCHIVED).contentLen(15L).build()));
        var items = service.listByActionCode("P03");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).id()).isEqualTo(2L);
        assertThat(items.get(0).contentLen()).isEqualTo(20L);
        assertThat(items.get(0).actionCode()).isEqualTo("P03");
    }

    @Test
    @DisplayName("listByActionCode：actionCode 空白 → PARAM_INVALID")
    void listByActionCodeBlank() {
        assertThatThrownBy(() -> service.listByActionCode("   "))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    // ========== currentForAction ==========

    @Test
    @DisplayName("currentForAction：无 PUBLISHED → NOT_FOUND")
    void currentForActionNotFound() {
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        assertThatThrownBy(() -> service.currentForAction("UNKNOWN"))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("currentForAction：取 version 最大且 effectiveTo IS NULL 的 PUBLISHED")
    void currentForActionReturnsLatest() {
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            SopTemplate.builder().id(5L).actionCode("V10").version(2L)
                .status(SopTemplate.Status.PUBLISHED).effectiveTo(null).delFlag("0")
                .content("含算法公平性、偏见测试").build()));
        SopTemplate out = service.currentForAction("V10");
        assertThat(out.getId()).isEqualTo(5L);
        assertThat(out.getStatus()).isEqualTo(SopTemplate.Status.PUBLISHED);
        assertThat(out.getContent()).contains("算法公平性");
    }

    // ========== copyToDraft ==========

    @Test
    @DisplayName("copyToDraft：源 PUBLISHED → 新 DRAFT version=max+1，审计 COPY")
    void copyToDraftFromPublished() {
        SopTemplate src = published("P03", 1L, "原版正文");
        when(templateMapper.selectById(100L)).thenReturn(src);
        // duplicateAsDraft 里两次 selectList：existingDrafts 空 → all [src]
        when(templateMapper.selectList(any(Wrapper.class)))
            .thenReturn(new ArrayList<>())
            .thenReturn(List.of(src));
        SopTemplate draft = service.copyToDraft(100L, ADMIN);
        assertThat(draft.getStatus()).isEqualTo(SopTemplate.Status.DRAFT);
        assertThat(draft.getVersion()).isEqualTo(2L);
        assertThat(draft.getActionCode()).isEqualTo("P03");
        assertThat(draft.getContent()).isEqualTo("原版正文");
        verify(auditLogService).append(org.mockito.Mockito.argThat(
            (AuditLog a) -> "COPY".equals(a.getAction()) && "SOP_TEMPLATE".equals(a.getEntityType())));
    }

    @Test
    @DisplayName("copyToDraft：源 DRAFT → 409 零写入")
    void copyToDraftFromDraftFails() {
        when(templateMapper.selectById(100L)).thenReturn(draft("P03", 2L, "x"));
        assertThatThrownBy(() -> service.copyToDraft(100L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
        verify(templateMapper, never()).insert(any(SopTemplate.class));
    }

    @Test
    @DisplayName("copyToDraft：同 actionCode 已有 DRAFT → 409")
    void copyToDraftWhenDraftExists() {
        SopTemplate src = published("P03", 1L, "x");
        when(templateMapper.selectById(100L)).thenReturn(src);
        // duplicateAsDraft existingDrafts 非空
        when(templateMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(draft("P03", 2L, "y")));
        assertThatThrownBy(() -> service.copyToDraft(100L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("copyToDraft：非超管 → FORBIDDEN")
    void copyToDraftForbidden() {
        assertThatThrownBy(() -> service.copyToDraft(100L, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    // ========== updateDraft ==========

    @Test
    @DisplayName("updateDraft：title 去空格 <2 → PARAM_INVALID，零写入")
    void updateDraftTitleTooShort() {
        assertThatThrownBy(() -> service.updateDraft(100L,
            new SopTemplateSaveReq("短", "正常正文内容"), ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
        verify(templateMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("updateDraft：content 空白 → PARAM_INVALID")
    void updateDraftContentBlank() {
        assertThatThrownBy(() -> service.updateDraft(100L,
            new SopTemplateSaveReq("正常标题", "   "), ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("updateDraft：源 PUBLISHED → 409 零写入")
    void updateDraftNonDraftFails() {
        when(templateMapper.selectById(100L)).thenReturn(published("P03", 1L, "x"));
        assertThatThrownBy(() -> service.updateDraft(100L,
            new SopTemplateSaveReq("正常标题", "新正文"), ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
        verify(templateMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("updateDraft：正常保存 → 条件 UPDATE 1 行 + 审计 UPDATE")
    void updateDraftSuccess() {
        SopTemplate d = draft("P03", 2L, "旧正文");
        SopTemplate updated = draft("P03", 2L, "新正文");
        updated.setTitle("新标题");
        when(templateMapper.selectById(100L)).thenReturn(d, updated);
        SopTemplate out = service.updateDraft(100L,
            new SopTemplateSaveReq("新标题", "新正文"), ADMIN);
        assertThat(out.getTitle()).isEqualTo("新标题");
        assertThat(out.getContent()).isEqualTo("新正文");
        verify(auditLogService).append(org.mockito.Mockito.argThat(
            (AuditLog a) -> "UPDATE".equals(a.getAction()) && "SOP_TEMPLATE".equals(a.getEntityType())));
    }

    @Test
    @DisplayName("updateDraft：条件 UPDATE 0 行（并发）→ 409")
    void updateDraftConcurrentZeroRows() {
        when(templateMapper.selectById(100L)).thenReturn(draft("P03", 2L, "x"));
        when(templateMapper.update(any(), any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service.updateDraft(100L,
            new SopTemplateSaveReq("正常标题", "新正文"), ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
    }

    // ========== publishDraft（AC-IPD-20 生物特征校验 + 版本机并发守卫） ==========

    @Test
    @DisplayName("publishDraft：非超管 → FORBIDDEN")
    void publishDraftForbidden() {
        assertThatThrownBy(() -> service.publishDraft(100L, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("publishDraft：源非 DRAFT → 409")
    void publishDraftNonDraftFails() {
        when(templateMapper.selectById(100L)).thenReturn(published("P03", 1L, "x"));
        assertThatThrownBy(() -> service.publishDraft(100L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("publishDraft：AC-IPD-20 反例（V10 缺「算法公平性」）→ 400，零写入零归档")
    void publishDraftBioFeatureMissingKeyword() {
        when(templateMapper.selectById(100L)).thenReturn(draft("V10", 2L, "仅含偏见测试关键词"));
        assertThatThrownBy(() -> service.publishDraft(100L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
        verify(templateMapper, never()).update(any(), any(Wrapper.class));
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("publishDraft：AC-IPD-20 正例（含两关键词）→ PUBLISHED + 旧版归档")
    void publishDraftBioFeaturePasses() {
        SopTemplate d = draft("V10", 2L, "正文含算法公平性、偏见测试关键词");
        // mock 第二次 selectById 模拟真实 DB UPDATE 已持久化：返回 status=PUBLISHED 的新对象
        SopTemplate afterPublish = draft("V10", 2L, "正文含算法公平性、偏见测试关键词");
        afterPublish.setStatus(SopTemplate.Status.PUBLISHED);
        when(templateMapper.selectById(100L)).thenReturn(d, afterPublish);
        when(templateMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(published("V10", 1L, "旧版"))); // 同动作旧 PUBLISHED
        SopTemplate out = service.publishDraft(100L, ADMIN);
        assertThat(out.getStatus()).isEqualTo(SopTemplate.Status.PUBLISHED);
        // 至少 2 条审计：ARCHIVE 旧 + PUBLISH 新
        verify(auditLogService, org.mockito.Mockito.atLeast(2)).append(any(AuditLog.class));
        verify(auditLogService).append(org.mockito.Mockito.argThat(
            (AuditLog a) -> "ARCHIVE".equals(a.getAction())));
        verify(auditLogService).append(org.mockito.Mockito.argThat(
            (AuditLog a) -> "PUBLISH".equals(a.getAction())));
    }

    @Test
    @DisplayName("publishDraft：非生物动作（P03）→ 不走 AC-IPD-20 关键词校验，直接 PUBLISH")
    void publishDraftNonBioSkipsKeywordCheck() {
        SopTemplate d = draft("P03", 2L, "普通正文，不含特殊关键词");
        SopTemplate afterPublish = draft("P03", 2L, "普通正文，不含特殊关键词");
        afterPublish.setStatus(SopTemplate.Status.PUBLISHED);
        when(templateMapper.selectById(100L)).thenReturn(d, afterPublish);
        when(templateMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(published("P03", 1L, "旧版")));
        SopTemplate out = service.publishDraft(100L, ADMIN);
        assertThat(out.getStatus()).isEqualTo(SopTemplate.Status.PUBLISHED);
    }

    @Test
    @DisplayName("publishDraft：DRAFT→PUBLISHED 条件 UPDATE 0 行（并发）→ 409")
    void publishDraftConcurrentZeroRows() {
        SopTemplate d = draft("P03", 2L, "x");
        when(templateMapper.selectById(100L)).thenReturn(d);
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        // 第一次 update（无旧版本场景本就不调用）—— 让 update 第一次返回 1（可能），第二次 0
        // 实际只有一个 update 调用（DRAFT→PUBLISHED），让它返回 0
        when(templateMapper.update(any(), any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service.publishDraft(100L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
    }

    // ========== revertToDraft ==========

    @Test
    @DisplayName("revertToDraft：源 ARCHIVED → 新 DRAFT version=max+1，审计 REVERT")
    void revertToDraftSuccess() {
        SopTemplate src = archived("P03", 1L, "归档版正文");
        when(templateMapper.selectById(101L)).thenReturn(src);
        when(templateMapper.selectList(any(Wrapper.class)))
            .thenReturn(new ArrayList<>())
            .thenReturn(List.of(src));
        SopTemplate out = service.revertToDraft(101L, ADMIN);
        assertThat(out.getStatus()).isEqualTo(SopTemplate.Status.DRAFT);
        assertThat(out.getVersion()).isEqualTo(2L);
        verify(auditLogService).append(org.mockito.Mockito.argThat(
            (AuditLog a) -> "REVERT".equals(a.getAction())));
    }

    @Test
    @DisplayName("revertToDraft：源非 ARCHIVED → 409")
    void revertToDraftNonArchivedFails() {
        when(templateMapper.selectById(100L)).thenReturn(published("P03", 1L, "x"));
        assertThatThrownBy(() -> service.revertToDraft(100L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
    }

    // ========== instantiate（保留既有 P1-3.3 IDOR 测试） ==========

    @Test
    @DisplayName("instantiate snapshotJson 包含 meta/actionList/responsibilityMatrix/phaseDeadlineMap")
    void instantiateSnapshotFields() {
        SopTemplate t = SopTemplate.builder()
            .id(10L).actionCode("C01").title("t").content("c").templateCode("SOP-CONCEPT-DEEP")
            .templateName("深管概念").version(1L).status(SopTemplate.Status.PUBLISHED)
            .category(SopTemplate.Category.DEEP_MGMT).delFlag("0").build();
        when(templateMapper.selectById(10L)).thenReturn(t);
        when(instanceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>());
        SopTemplateInstance out = service.instantiate(10L, 200L, MARKET_PM);
        assertThat(out.getStatus()).isEqualTo(SopTemplateInstance.Status.ACTIVE);
        assertThat(out.getInstanceVersion()).isEqualTo(1L);
        assertThat(out.getSnapshotJson()).contains("\"meta\"");
        assertThat(out.getSnapshotJson()).contains("\"actionList\"");
        assertThat(out.getSnapshotJson()).contains("\"templateCode\":\"SOP-CONCEPT-DEEP\"");
    }

    @Test
    @DisplayName("[IDOR] instantiate 非项目成员 → FORBIDDEN")
    void instantiateNonProjectMemberForbidden() {
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        SopTemplate t = SopTemplate.builder()
            .id(40L).actionCode("X").version(1L).status(SopTemplate.Status.PUBLISHED)
            .category(SopTemplate.Category.MIXED).delFlag("0").build();
        when(templateMapper.selectById(40L)).thenReturn(t);
        assertThatThrownBy(() -> service.instantiate(40L, 600L, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("instantiate GUEST 角色拒绝")
    void instantiateRejectedForGuest() {
        SopTemplate t = SopTemplate.builder()
            .id(32L).actionCode("X").version(1L).status(SopTemplate.Status.PUBLISHED)
            .category(SopTemplate.Category.MIXED).delFlag("0").build();
        when(templateMapper.selectById(32L)).thenReturn(t);
        assertThatThrownBy(() -> service.instantiate(32L, 502L, GUEST))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(ex -> ((IpdBusinessException) ex).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }
}