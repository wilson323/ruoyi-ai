package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.HandoverRecord;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.ContributionVersionMapper;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.support.NoopTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-6 归档后只读边界补齐：Handover / Contribution / KPI / AiDocument 四域专项。
 *
 * <p><b>需求口径</b>：动作清单 LC09「项目归档（资料归档，转只读）」、README.md:136
 * 「G-01 子项 · 归档后只读」——归档后**全部业务域**转只读，不只是项目/动作域。
 * 测绘（{@code docs/ipd-系统说明/系统画像-业务规则权威图.md} §C-6）发现原实现只覆盖
 * Project / StageAction 两域。
 *
 * <p><b>本类逐条核实结论</b>（改动前）：
 * <ul>
 *   <li>{@code HandoverService} —— 缺项目级 ARCHIVED 判据。原有 ARCHIVED 只作用于移交记录
 *       自身的 {@code archived_at} 写，<b>建单/接单/撤销三条写路径全无守卫</b>。→ 本类新增守卫。</li>
 *   <li>{@code ContributionService} —— <b>已有守卫</b>：{@code requireG5Stage} 对 ARCHIVED 直接拒，
 *       且 {@code saveSelf} / {@code adjustMarketShare} / {@code confirm} 三个写入口全部经过它。
 *       → <b>不改生产代码</b>，本类只做回归锁定（防止后人误删该判据）。</li>
 *   <li>{@code KpiRecordService} —— 缺。原有 {@code ST_ARCHIVED} 是 KPI 记录自身状态机的终态，
 *       与项目归档无关。→ 本类新增守卫。</li>
 *   <li>{@code AiDocumentService} —— 缺。原有 {@code STATUS_ARCHIVED} 是文档版本自身状态机终态，
 *       与项目归档无关。→ 本类新增守卫（{@code rebuildIndexAuthorized} 除外，见下）。</li>
 * </ul>
 *
 * <p><b>有意豁免</b>：{@code AiDocumentService.rebuildIndexAuthorized} 只重建检索缓存、
 * 不改任何业务行，属读侧补偿；拦它会造成「归档即失忆」（归档项目的历史文档检索不到）。
 *
 * <p><b>守卫范式</b>：照抄 {@code ProjectService.updateBaselines}（读项目行 → 命中 ARCHIVED
 * 即抛），未自创。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("C-6 归档后只读：Handover / Contribution / KPI / AiDocument 四域守卫")
class ArchivedProjectReadOnlyGuardTest {

    private static final Long PROJECT_ID = 1L;
    private static final String ARCHIVE_HINT = "已归档";

    @Mock private ProjectMemberMapper memberMapper;
    @Mock private PersonMapper personMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private HandoverMapper handoverMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private IProjectMemberService projectMemberService;
    @Mock private NotificationService notificationService;

    @Mock private KpiRecordMapper kpiRecordMapper;
    @Mock private AiDocumentMapper aiDocumentMapper;

    @Mock private ContributionMapper contributionMapper;
    @Mock private ContributionVersionMapper contributionVersionMapper;
    @Mock private ProductGroupMapper productGroupMapper;
    @Mock private IpdPermission ipdPermission;

    private HandoverService handoverService;
    private KpiRecordService kpiService;
    private AiDocumentService aiDocService;
    private ContributionService contributionService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Person.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, HandoverRecord.class);
        TableInfoHelper.initTableInfo(assistant, KpiRecord.class);
        TableInfoHelper.initTableInfo(assistant, AiDocument.class);
        TableInfoHelper.initTableInfo(assistant, Contribution.class);
    }

    @BeforeEach
    void setUp() {
        handoverService = new HandoverService(memberMapper, personMapper, projectMapper,
            handoverMapper, auditLogService, projectMemberService, NoopTransactionManager.INSTANCE,
            null, notificationService);
        handoverService.setStateMachineGuard(mock(StateMachineGuard.class));

        kpiService = new KpiRecordService(kpiRecordMapper, null, null, null);
        kpiService.setProjectMapper(projectMapper);
        // 状态机守卫必须装配，否则 recordScore 走到 EDITING 迁移时抛「守卫未装配」，
        // 与本测试要验的「归档守卫」无关（这 3 条红就是这么来的）。
        kpiService.setStateMachineGuard(mock(StateMachineGuard.class));

        aiDocService = new AiDocumentService(aiDocumentMapper);
        aiDocService.setProjectMapper(projectMapper);

        contributionService = new ContributionService(contributionMapper, contributionVersionMapper,
            projectMapper, productGroupMapper, auditLogService, ipdPermission);
    }

    // ---------- 夹具 ----------

    private Project project(String status, String currentStage) {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setStatus(status);
        p.setCurrentStage(currentStage);
        p.setDelFlag("0");
        p.setMainGroupId(9L);
        return p;
    }

    private void projectIs(String status, String currentStage) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(status, currentStage));
    }

    private IpdActor pm() {
        return new IpdActor(10L, "P-10", "MARKET_PM", 9L);
    }

    private Person activePerson(long id, String type) {
        return Person.builder().id(id).name("P-" + id).personType(type)
            .accountStatus("ACTIVE").employmentStatus("ACTIVE").build();
    }

    /** 移交建单成功所需的全部 mock（仅供「未归档项目应正常」用例）。 */
    private void handoverHappyPath() {
        when(personMapper.selectById(20L)).thenReturn(activePerson(20L, "MARKET_PM"));
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(handoverMapper.selectCount(any())).thenReturn(0L);
    }

    private HandoverRecord draftRecord() {
        return HandoverRecord.builder().id(100L).projectId(PROJECT_ID)
            .fromPersonId(10L).toPersonId(20L).handoverRole("MARKET_PM")
            .handoverType("PROJECT").status("DRAFT").build();
    }

    private HandoverRecord completedRecord() {
        HandoverRecord rec = draftRecord();
        rec.setStatus("COMPLETED");
        rec.setCompletedAt(new java.util.Date());
        return rec;
    }

    // ================= Handover 域 =================

    @Test
    @DisplayName("H-1 归档项目上发起移交被拒（不落库）")
    void handover_initiate_onArchivedProject_rejected() {
        projectIs("ARCHIVED", "LIFECYCLE");
        handoverHappyPath();

        assertThatThrownBy(() -> handoverService.initiate(PROJECT_ID, "MARKET_PM", 20L, "note", pm()))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(ARCHIVE_HINT);

        verify(handoverMapper, never()).insert(any(HandoverRecord.class));
    }

    @Test
    @DisplayName("H-2 未归档项目上发起移交正常落库")
    void handover_initiate_onActiveProject_succeeds() {
        projectIs("ACTIVE", "LIFECYCLE");
        handoverHappyPath();

        assertThatCode(() -> handoverService.initiate(PROJECT_ID, "MARKET_PM", 20L, "note", pm()))
            .doesNotThrowAnyException();

        verify(handoverMapper).insert(any(HandoverRecord.class));
    }

    @Test
    @DisplayName("H-3 归档项目上确认移交被拒（成员绑定不转移）")
    void handover_doAccept_onArchivedProject_rejected() {
        projectIs("ARCHIVED", "LIFECYCLE");
        when(handoverMapper.selectById(100L)).thenReturn(draftRecord());
        when(personMapper.selectById(20L)).thenReturn(activePerson(20L, "MARKET_PM"));

        assertThatThrownBy(() -> handoverService.accept(100L, null, new IpdActor(20L, "P-20", "MARKET_PM", 9L)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(ARCHIVE_HINT);

        verify(projectMemberService, never()).exitForHandover(anyLong(), anyLong(), anyString());
        verify(projectMemberService, never()).bindMember(anyLong(), anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("H-4 归档项目上撤销移交被拒")
    void handover_rollback_onArchivedProject_rejected() {
        projectIs("ARCHIVED", "LIFECYCLE");
        when(handoverMapper.selectById(100L)).thenReturn(completedRecord());

        assertThatThrownBy(() -> handoverService.rollback(100L, "误操作", pm()))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(ARCHIVE_HINT);

        verify(handoverMapper, never()).updateById(any(HandoverRecord.class));
    }

    // ================= Contribution 域（回归锁定既有守卫） =================

    @Test
    @DisplayName("C-1 归档项目上保存贡献度被拒（既有 requireG5Stage 守卫，本轮未改生产代码）")
    void contribution_saveSelf_onArchivedProject_rejected() {
        projectIs("ARCHIVED", "LIFECYCLE");
        when(ipdPermission.requireInternal()).thenReturn(pm());

        assertThatThrownBy(() -> contributionService.saveSelf(PROJECT_ID, new ContributionSaveReqStub().req()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining(ARCHIVE_HINT)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.CONTRIB_NOT_G5_STAGE);

        verify(contributionMapper, never()).insert(any(Contribution.class));
    }

    @Test
    @DisplayName("C-2 未归档 + LIFECYCLE 时贡献度守卫放行（证明拒绝原因确为归档而非其它）")
    void contribution_saveSelf_onActiveProject_passesArchiveGuard() {
        projectIs("ACTIVE", "LIFECYCLE");
        when(ipdPermission.requireInternal()).thenReturn(pm());

        // 只断言「不是归档拒」：角色/权重等后续校验可能以别的消息拒绝
        assertThat(catchMessage(() -> contributionService.saveSelf(PROJECT_ID, new ContributionSaveReqStub().req())))
            .doesNotContain(ARCHIVE_HINT);
    }

    /** saveSelf 的入参记录：五维度均 80 分（record 规范构造器，无 setter）。 */
    private static final class ContributionSaveReqStub {
        org.ruoyi.ipd.dto.ContributionSaveReq req() {
            java.math.BigDecimal s = new java.math.BigDecimal("80");
            return new org.ruoyi.ipd.dto.ContributionSaveReq("MARKET_PM", s, s, s, s, s, null);
        }
    }

    // ================= KPI 域 =================

    @Test
    @DisplayName("K-1 归档项目上录 KPI 草稿被拒（不 insert）")
    void kpi_recordScore_onArchivedProject_rejected() {
        projectIs("ARCHIVED", "LIFECYCLE");
        KpiRecord draft = new KpiRecord();
        draft.setProjectId(PROJECT_ID);

        assertThatThrownBy(() -> kpiService.recordScore(draft, pm()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining(ARCHIVE_HINT)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);

        verify(kpiRecordMapper, never()).insert(any(KpiRecord.class));
    }

    @Test
    @DisplayName("K-2 归档项目上审批/驳回/归档 KPI 全部被拒（不 update）")
    void kpi_transitions_onArchivedProject_rejected() {
        projectIs("ARCHIVED", "LIFECYCLE");
        KpiRecord record = new KpiRecord();
        record.setId(7L);
        record.setProjectId(PROJECT_ID);
        record.setStatus("EDITING");
        when(kpiRecordMapper.selectById(7L)).thenReturn(record);

        assertThatThrownBy(() -> kpiService.approveKpi(7L, pm()))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining(ARCHIVE_HINT);
        assertThatThrownBy(() -> kpiService.rejectKpi(7L, pm()))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining(ARCHIVE_HINT);
        assertThatThrownBy(() -> kpiService.archiveKpi(7L, pm()))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining(ARCHIVE_HINT);

        verify(kpiRecordMapper, never()).updateById(any(KpiRecord.class));
    }

    @Test
    @DisplayName("K-3 未归档项目上录 KPI 草稿正常落库")
    void kpi_recordScore_onActiveProject_succeeds() {
        projectIs("ACTIVE", "LIFECYCLE");
        KpiRecord draft = new KpiRecord();
        draft.setProjectId(PROJECT_ID);

        assertThatCode(() -> kpiService.recordScore(draft, pm())).doesNotThrowAnyException();

        verify(kpiRecordMapper).insert(draft);
    }

    @Test
    @DisplayName("K-4 个人级 KPI（projectId 为空）不受归档守卫影响")
    void kpi_recordScore_withoutProject_notBlocked() {
        KpiRecord draft = new KpiRecord();

        assertThatCode(() -> kpiService.recordScore(draft, pm())).doesNotThrowAnyException();

        verify(kpiRecordMapper).insert(draft);
    }

    // ================= AiDocument 域 =================

    @Test
    @DisplayName("A-1 归档项目上生成 AI 文档被拒（不 insert）")
    void aiDoc_createGenerated_onArchivedProject_rejected() {
        projectIs("ARCHIVED", "LIFECYCLE");

        assertThatThrownBy(() -> aiDocService.createGenerated(
            PROJECT_ID, "PRD", "标题", "正文", "m1", 10, 20, 10L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining(ARCHIVE_HINT)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);

        verify(aiDocumentMapper, never()).insert(any(AiDocument.class));
    }

    @Test
    @DisplayName("A-2 归档项目上审核/归档/拒绝 AI 文档全部被拒（不 update）")
    void aiDoc_transitions_onArchivedProject_rejected() {
        projectIs("ARCHIVED", "LIFECYCLE");
        when(aiDocumentMapper.selectById(500L)).thenReturn(generatedRow());

        assertThatThrownBy(() -> aiDocService.review(500L, 10L))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining(ARCHIVE_HINT);
        assertThatThrownBy(() -> aiDocService.archive(500L, 10L))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining(ARCHIVE_HINT);
        assertThatThrownBy(() -> aiDocService.reject(500L, 10L, "内容不合规"))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining(ARCHIVE_HINT);

        verify(aiDocumentMapper, never()).update(any(AiDocument.class), any());
    }

    @Test
    @DisplayName("A-3 未归档项目上生成 AI 文档正常落库")
    void aiDoc_createGenerated_onActiveProject_succeeds() {
        projectIs("ACTIVE", "LIFECYCLE");

        assertThatCode(() -> aiDocService.createGenerated(
            PROJECT_ID, "PRD", "标题", "正文", "m1", 10, 20, 10L))
            .doesNotThrowAnyException();

        verify(aiDocumentMapper).insert(any(AiDocument.class));
    }

    @Test
    @DisplayName("A-4 未归档项目上审核 AI 文档正常流转到 REVIEWED")
    void aiDoc_review_onActiveProject_succeeds() {
        projectIs("ACTIVE", "LIFECYCLE");
        when(aiDocumentMapper.selectById(500L)).thenReturn(generatedRow());
        // 生产代码调 mapper.update(null, wrapper)——Mockito 2+ 的 any(Class) 不匹配 null，须用 nullable
        when(aiDocumentMapper.update(nullable(AiDocument.class), any())).thenReturn(1);

        AiDocument out = aiDocService.review(500L, 10L);

        assertThat(out.getStatus()).isEqualTo("REVIEWED");
    }

    private AiDocument generatedRow() {
        AiDocument row = AiDocument.builder()
            .id(500L).projectId(PROJECT_ID).docType("PRD").title("标题")
            .content("正文").status("GENERATED").versionNo(1)
            .contentSha256("x").build();
        return row;
    }

    // ---------- 工具 ----------

    private static String catchMessage(Runnable runnable) {
        try {
            runnable.run();
            return "";
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }
}
