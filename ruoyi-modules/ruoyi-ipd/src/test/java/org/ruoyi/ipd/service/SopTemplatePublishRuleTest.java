package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.SopTemplate;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.SopTemplateInstanceMapper;
import org.ruoyi.ipd.mapper.SopTemplateMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-3.3 / AC-IPD-20 发布规则：生物特征动作（bioFeature）SOP 正文必须同时含
 * 「算法公平性」与「偏见测试」，缺一即 400 且零写入；发布时同 actionCode 旧 PUBLISHED
 * 逐条条件归档（effectiveTo=now + status=ARCHIVED），DRAFT→PUBLISHED 条件 UPDATE 守卫并发。
 *
 * <p>与 {@link SopTemplateServiceTest}（版本链）/ {@link SopTemplateInstanceServiceTest}（实例）
 * 互补：本类只钉「发布」这一动作的规则面与并发面。
 */
@Tag("dev")
class SopTemplatePublishRuleTest {

    private SopTemplateMapper templateMapper;
    private SopTemplateInstanceMapper instanceMapper;
    private IAuditLogService auditLogService;
    private SopTemplateService service;

    private static final IpdActor ADMIN = new IpdActor(1L, "admin", "SUPER_ADMIN", 100L);
    private static final IpdActor MARKET_PM = new IpdActor(2L, "market", "MARKET_PM", 100L);
    private static final long ID = 100L;

    /** AC-IPD-20 关键词（与 SopTemplateService.requireBioFeatureContent 同字面量）。 */
    private static final String FAIRNESS = "算法公平性";
    private static final String BIAS_TEST = "偏见测试";

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SopTemplate.class);

        templateMapper = mock(SopTemplateMapper.class);
        instanceMapper = mock(SopTemplateInstanceMapper.class);
        auditLogService = mock(IAuditLogService.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(templateMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        service = new SopTemplateService(templateMapper, instanceMapper, auditLogService,
            mock(ProjectMemberMapper.class), mock(ProjectMapper.class));
    }

    private static SopTemplate draft(String actionCode, String content) {
        return SopTemplate.builder()
            .id(ID).actionCode(actionCode).title("t").content(content)
            .version(2L).status(SopTemplate.Status.DRAFT).delFlag("0").build();
    }

    private static SopTemplate publishedRow(long id, String actionCode, long version) {
        SopTemplate t = SopTemplate.builder()
            .id(id).actionCode(actionCode).title("t").content("旧版")
            .version(version).status(SopTemplate.Status.PUBLISHED)
            .effectiveFrom(new Date()).effectiveTo(null).delFlag("0").build();
        return t;
    }

    /** 让 publishDraft 的两次 getById 返回「发布前草稿」与「发布后已生效」，并给出旧 PUBLISHED 列表。 */
    private void arrangePublish(SopTemplate before, List<SopTemplate> oldPublished) {
        SopTemplate after = SopTemplate.builder()
            .id(before.getId()).actionCode(before.getActionCode()).title(before.getTitle())
            .content(before.getContent()).version(before.getVersion())
            .status(SopTemplate.Status.PUBLISHED).effectiveFrom(new Date()).effectiveTo(null)
            .delFlag("0").build();
        when(templateMapper.selectById(ID)).thenReturn(before, after);
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(oldPublished);
    }

    private static ApiV1ErrorCode codeOf(Throwable ex) {
        return ((IpdBusinessException) ex).getErrorCode();
    }

    // ========== 规则前提：生物特征动作集合本身 ==========

    @Test
    @DisplayName("AC-IPD-20 前提：ActionCatalog 中 bioFeature=true 的动作恰为 V10/C12/D11")
    void bioFeatureCatalogIsExactlyV10C12D11() {
        Set<String> bioCodes = ActionCatalog.ALL.stream()
            .filter(ActionDef::bioFeature)
            .map(ActionDef::code)
            .collect(Collectors.toSet());

        assertThat(bioCodes).containsExactlyInAnyOrder("V10", "C12", "D11");
    }

    // ========== 生物特征动作：关键词缺一不可（400 零写入） ==========

    @Test
    @DisplayName("V10 正文缺两个关键词 → 400 零写入零审计")
    void publish_bioFeature_noKeywords_rejected() {
        when(templateMapper.selectById(ID)).thenReturn(draft("V10", "普通正文，没有任何合规关键词"));

        assertThatThrownBy(() -> service.publishDraft(ID, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verify(templateMapper, never()).update(any(), any(Wrapper.class));
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("V10 正文只有「偏见测试」缺「算法公平性」→ 400")
    void publish_bioFeature_onlyBiasTest_rejected() {
        when(templateMapper.selectById(ID)).thenReturn(draft("V10", "含" + BIAS_TEST + "要求"));

        assertThatThrownBy(() -> service.publishDraft(ID, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verify(templateMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("V10 正文只有「算法公平性」缺「偏见测试」→ 400（反向也必须拦住）")
    void publish_bioFeature_onlyFairness_rejected() {
        when(templateMapper.selectById(ID)).thenReturn(draft("V10", "含" + FAIRNESS + "要求"));

        assertThatThrownBy(() -> service.publishDraft(ID, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verify(templateMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("V10 正文为 null → 400（空正文不得绕过生物特征校验）")
    void publish_bioFeature_nullContent_rejected() {
        when(templateMapper.selectById(ID)).thenReturn(draft("V10", null));

        assertThatThrownBy(() -> service.publishDraft(ID, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
    }

    @Test
    @DisplayName("V10 正文同时含两关键词 → 发布成功")
    void publish_bioFeature_bothKeywords_passes() {
        SopTemplate before = draft("V10", "正文：" + FAIRNESS + " 与 " + BIAS_TEST + " 均须说明");
        arrangePublish(before, new ArrayList<>());

        assertThat(service.publishDraft(ID, ADMIN).getStatus())
            .isEqualTo(SopTemplate.Status.PUBLISHED);
        verify(auditLogService).append(org.mockito.ArgumentMatchers.argThat(
            (AuditLog a) -> "PUBLISH".equals(a.getAction())));
    }

    @Test
    @DisplayName("C12 / D11 同属生物特征动作，两关键词齐备方可发布")
    void publish_otherBioFeatureCodes_requireBothKeywords() {
        for (String code : List.of("C12", "D11")) {
            // 缺一：拒
            when(templateMapper.selectById(ID)).thenReturn(draft(code, "只有" + FAIRNESS));
            assertThatThrownBy(() -> service.publishDraft(ID, ADMIN))
                .as("%s 缺「偏见测试」必须被拒", code)
                .isInstanceOf(IpdBusinessException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));

            // 齐备：过
            arrangePublish(draft(code, FAIRNESS + " + " + BIAS_TEST), new ArrayList<>());
            assertThat(service.publishDraft(ID, ADMIN).getStatus())
                .as("%s 两关键词齐备必须放行", code)
                .isEqualTo(SopTemplate.Status.PUBLISHED);
        }
    }

    @Test
    @DisplayName("非生物特征动作（C01）不受关键词约束，任意正文可发布")
    void publish_nonBioFeature_skipsKeywordRule() {
        arrangePublish(draft("C01", "完全不含合规关键词的普通流程说明"), new ArrayList<>());

        assertThat(service.publishDraft(ID, ADMIN).getStatus())
            .isEqualTo(SopTemplate.Status.PUBLISHED);
    }

    // ========== 版本归档：旧 PUBLISHED 逐条条件归档 ==========

    @Test
    @DisplayName("同 actionCode 两条旧 PUBLISHED → 逐条归档（2 条 ARCHIVE 审计 + 1 条 PUBLISH）")
    void publish_archivesEveryOldPublished() {
        List<SopTemplate> old = List.of(
            publishedRow(1L, "C01", 1L),
            publishedRow(2L, "C01", 2L));
        arrangePublish(draft("C01", "正文"), old);

        service.publishDraft(ID, ADMIN);

        verify(auditLogService, org.mockito.Mockito.times(2)).append(
            org.mockito.ArgumentMatchers.argThat((AuditLog a) -> "ARCHIVE".equals(a.getAction())));
        verify(auditLogService, org.mockito.Mockito.times(1)).append(
            org.mockito.ArgumentMatchers.argThat((AuditLog a) -> "PUBLISH".equals(a.getAction())));
        // 2 条归档 UPDATE + 1 条发布 UPDATE
        verify(templateMapper, org.mockito.Mockito.times(3)).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("旧 PUBLISHED 条件 UPDATE 影响 0 行（已被并发归档）→ 跳过该条审计，发布仍成功")
    void publish_zeroRowArchiveIsSkipped() {
        List<SopTemplate> old = List.of(publishedRow(1L, "C01", 1L));
        arrangePublish(draft("C01", "正文"), old);
        // 第 1 次 update = 归档（0 行，已被并发处理）；第 2 次 = 发布（1 行）
        when(templateMapper.update(any(), any(Wrapper.class))).thenReturn(0, 1);

        assertThat(service.publishDraft(ID, ADMIN).getStatus())
            .isEqualTo(SopTemplate.Status.PUBLISHED);
        verify(auditLogService, never()).append(
            org.mockito.ArgumentMatchers.argThat((AuditLog a) -> "ARCHIVE".equals(a.getAction())));
        verify(auditLogService).append(
            org.mockito.ArgumentMatchers.argThat((AuditLog a) -> "PUBLISH".equals(a.getAction())));
    }

    @Test
    @DisplayName("发布 UPDATE 的 SET 子句同时写 status/effective_from/effective_to（重开生效窗口）")
    void publish_updateSetsEffectiveWindow() {
        arrangePublish(draft("C01", "正文"), new ArrayList<>());

        service.publishDraft(ID, ADMIN);

        ArgumentCaptor<Wrapper<SopTemplate>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(templateMapper, atLeastOnce()).update(any(), captor.capture());
        List<String> setClauses = captor.getAllValues().stream()
            .map(w -> ((LambdaUpdateWrapper<SopTemplate>) w).getSqlSet())
            .toList();

        assertThat(setClauses).anySatisfy(sql -> {
            assertThat(sql).contains("status");
            assertThat(sql).contains("effective_from");
            // effective_to 必须被显式置回 null，否则旧窗口关闭后新版本不生效
            assertThat(sql).contains("effective_to");
        });
    }

    // ========== 状态机与并发守卫 ==========

    @Test
    @DisplayName("源非 DRAFT → 409 零写入")
    void publish_nonDraftConflict() {
        when(templateMapper.selectById(ID)).thenReturn(publishedRow(ID, "C01", 1L));

        assertThatThrownBy(() -> service.publishDraft(ID, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        verify(templateMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("DRAFT→PUBLISHED 条件 UPDATE 影响 0 行（并发被抢先发布）→ 409")
    void publish_concurrentZeroRowsConflict() {
        when(templateMapper.selectById(ID)).thenReturn(draft("C01", "正文"));
        when(templateMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(templateMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.publishDraft(ID, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
    }

    @Test
    @DisplayName("非超管（MARKET_PM）不得发布 → 403 零写入")
    void publish_nonAdminForbidden() {
        assertThatThrownBy(() -> service.publishDraft(ID, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.FORBIDDEN));
        verify(templateMapper, never()).update(any(), any(Wrapper.class));
        verify(auditLogService, never()).append(any(AuditLog.class));
    }
}
