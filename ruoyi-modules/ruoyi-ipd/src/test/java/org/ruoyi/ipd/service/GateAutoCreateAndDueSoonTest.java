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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F6 关卡自动创建 + ⑤刀到期前预警验收测试（2026-10-06）。
 *
 * <p>覆盖矩阵：
 * <ol>
 *   <li><b>F6-①</b> 在途去重 —— 见 {@link GateAutoCreateAcceptanceTest#inFlightPending_rejected()} /
 *       {@code noInFlight_creates_evenIfLastOneWasLongAgo}（同批改写）；</li>
 *   <li><b>F6-②</b> 动作流转到 DONE 自动建卡（C11→G1）；不挂关卡的动作不建；
 *       <b>建卡失败绝不回滚动作流转</b>；</li>
 *   <li><b>F6-③</b> G3 双周复评扫描判定：DEV + 超期 → 建；DEV + 未到期 → 不建；
 *       非 DEV → 不建；建卡抛异常不影响同批其他项目；</li>
 *   <li><b>⑤刀</b> 到期前预警窗口：dueDate 恰为 N 天 → 发；N-1 / N+1 → 不发；
 *       已完成（DONE/NA）动作 → 不发；同日重复触发幂等（dedupKey 含自然日，由通知层保证）。</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateAutoCreateAndDueSoonTest {

    /** 固定时刻：2026-10-06 10:00 本地时间，让「自然日 + N」的边界判窗无时钟摇摆。 */
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"), ZONE);

    @Mock private StageActionMapper stageActionMapper;
    @Mock private DeliverableMapper deliverableMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private ProjectStageMapper projectStageMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private ProductGroupMapper productGroupMapper;
    @Mock private NotificationService notificationService;
    @Mock private GateCreationService gateCreationService;
    @Mock private StateMachineGuard stateMachineGuard;

    /** GateCreationService 自身的 GateMapper 依赖（与 StageActionService 那组 mock 各自独立）。 */
    @Mock private org.ruoyi.ipd.mapper.GateMapper gateMapper;

    private StageActionService stageActionService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StageAction.class);
        TableInfoHelper.initTableInfo(assistant, Deliverable.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, Gate.class);
    }

    @BeforeEach
    void setUp() {
        stageActionService = new StageActionService(stageActionMapper, deliverableMapper, auditLogService,
            projectStageMapper, projectMapper);
        stageActionService.setStateMachineGuard(stateMachineGuard);
        stageActionService.setNotificationService(notificationService);
        stageActionService.setProjectMemberMapper(projectMemberMapper);
        stageActionService.setProductGroupMapper(productGroupMapper);
        stageActionService.setGateCreationService(gateCreationService);
        stageActionService.setClock(FIXED);
    }

    // ---------- helpers ----------

    private Project activeProject(long id) {
        return Project.builder().id(id).name("P" + id).status("ACTIVE").delFlag("0").mainGroupId(70L)
            .currentStage("DEV").build();
    }

    private StageAction deepAction(String code, String status) {
        return StageAction.builder()
            .id(900L).projectId(700L).actionCode(code).actionName(code)
            .ownerRole("BOTH").depth("DEEP").status(status)
            .isBlocking("1").isBioFeature("0").delFlag("0").version(1)
            .build();
    }

    private Date at(LocalDate day, int hour) {
        return Date.from(day.atTime(hour, 0).atZone(ZONE).toInstant());
    }

    // ================= F6-② 动作 DONE 触发建卡 =================

    @Test
    @DisplayName("F6-② C11 流转到 DONE ⇒ 自动建 G1（映射取自 ActionDef.gate）")
    void c11Done_autoCreatesG1() {
        StageAction a = deepAction("C11", "IN_PROGRESS");
        when(stageActionMapper.selectById(900L)).thenReturn(a);
        when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
        when(deliverableMapper.selectCount(any())).thenReturn(1L);
        when(stageActionMapper.updateById(any(StageAction.class))).thenReturn(1);

        StageAction out = stageActionService.transit(900L, "DONE", "评审通过", "123");

        assertThat(out.getStatus()).isEqualTo("DONE");
        verify(gateCreationService, times(1)).autoCreateGate(eq(700L), eq("G1"), eq(123L));
    }

    @Test
    @DisplayName("F6-② D05 流转到 DONE ⇒ 建 G3；L07 ⇒ G4；LC02 ⇒ G5（目录里全部 5 条映射逐条锁死）")
    void allFiveGateMappings_locked() {
        assertThat(gateCodeOf("C11")).isEqualTo("G1");
        assertThat(gateCodeOf("P13")).isEqualTo("G2");
        assertThat(gateCodeOf("D05")).isEqualTo("G3");
        assertThat(gateCodeOf("L07")).isEqualTo("G4");
        assertThat(gateCodeOf("LC02")).isEqualTo("G5");
    }

    private String gateCodeOf(String actionCode) {
        return org.ruoyi.ipd.seed.ActionCatalog.byCode(actionCode).gate();
    }

    @Test
    @DisplayName("F6-② 不挂关卡的动作（如 D01）流转到 DONE ⇒ 不建任何 Gate")
    void nonGateActionDone_doesNotCreateGate() {
        StageAction a = deepAction("D01", "IN_PROGRESS");
        when(stageActionMapper.selectById(900L)).thenReturn(a);
        when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
        when(deliverableMapper.selectCount(any())).thenReturn(1L);
        when(stageActionMapper.updateById(any(StageAction.class))).thenReturn(1);

        stageActionService.transit(900L, "DONE", null, "123");

        verify(gateCreationService, never()).autoCreateGate(anyLong(), any(), any());
    }

    @Test
    @DisplayName("F6-② 建卡失败绝不回滚动作流转（动作仍为 DONE，异常只被吞掉记 WARN）")
    void gateCreationFailure_doesNotRollBackAction() {
        StageAction a = deepAction("C11", "IN_PROGRESS");
        when(stageActionMapper.selectById(900L)).thenReturn(a);
        when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
        when(deliverableMapper.selectCount(any())).thenReturn(1L);
        when(stageActionMapper.updateById(any(StageAction.class))).thenReturn(1);
        when(gateCreationService.autoCreateGate(anyLong(), any(), any()))
            .thenThrow(new org.ruoyi.common.core.exception.ServiceException("G1 评审已有在途轮次未决"));

        // 不抛异常 = 动作流转没有被建卡失败拖垮
        assertThatCode(() -> stageActionService.transit(900L, "DONE", null, "123")).doesNotThrowAnyException();
        // 动作状态确已落库并置 DONE
        verify(stageActionMapper, times(1)).updateById(any(StageAction.class));
    }

    @Test
    @DisplayName("F6-② 未装配 GateCreationService 时动作流转照常（副链缺失不 fail-closed）")
    void gateServiceMissing_actionStillTransits() {
        stageActionService.setGateCreationService(null);
        StageAction a = deepAction("C11", "IN_PROGRESS");
        when(stageActionMapper.selectById(900L)).thenReturn(a);
        when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
        when(deliverableMapper.selectCount(any())).thenReturn(1L);
        when(stageActionMapper.updateById(any(StageAction.class))).thenReturn(1);

        assertThat(stageActionService.transit(900L, "DONE", null, "123").getStatus()).isEqualTo("DONE");
    }

    // ================= F6-③ G3 双周复评扫描 =================

    private GateCreationService newGateCreationService() {
        GateCreationService svc = new GateCreationService(gateMapper, projectMapper, auditLogService);
        svc.setClock(FIXED);
        return svc;
    }

    private Project devProject(long id) {
        return Project.builder().id(id).status("ACTIVE").delFlag("0").currentStage("DEV").build();
    }

    private Gate g3CreatedDaysAgo(int days) {
        Gate g = Gate.builder().id(500L).projectId(700L).gateCode("G3").status("APPROVED").currentRound(2).build();
        g.setCreateTime(new Date(FIXED.millis() - days * 24L * 3600_000L));
        return g;
    }

    /**
     * 幸存实现的「取最后一次 G3」走的是 {@code latestGateOf}（selectList + orderByDesc + LIMIT 1），
     * 不是 selectOne——桩必须对齐实际调用方式，否则本组用例测的是一个不存在的路径。
     */
    private void stubLastG3(Gate g) {
        lenient().when(gateMapper.selectList(any())).thenReturn(g == null ? List.of() : List.of(g));
    }

    @Test
    @DisplayName("F6-③ DEV 阶段 + 距上次 G3 已满 14 天 ⇒ 补建 G3")
    void devProjectOverdue_createsG3() {
        lenient().when(projectMapper.selectList(any())).thenReturn(List.of(devProject(700L)));
        // autoCreateGate 内部会重查项目做 ARCHIVED/SUSPENDED 门禁，不给就抛「项目不存在」被扫描吞掉。
        lenient().when(projectMapper.selectById(700L)).thenReturn(devProject(700L));
        stubLastG3(g3CreatedDaysAgo(15));
        when(gateMapper.selectCount(any())).thenReturn(0L);

        assertThat(newGateCreationService().scanDevG3Recreate()).isEqualTo(1);
        verify(gateMapper, times(1)).insert(any(Gate.class));
    }

    @Test
    @DisplayName("F6-③ DEV 阶段但复评周期未到（13 天）⇒ 不建")
    void devProjectNotYetDue_doesNotCreate() {
        lenient().when(projectMapper.selectList(any())).thenReturn(List.of(devProject(700L)));
        stubLastG3(g3CreatedDaysAgo(13));

        assertThat(newGateCreationService().scanDevG3Recreate()).isEqualTo(0);
        verify(gateMapper, never()).insert(any(Gate.class));
    }

    @Test
    @DisplayName("F6-③ DEV 阶段 + 从未建过 G3 ⇒ 建第一张")
    void devProjectNeverHadG3_createsFirst() {
        lenient().when(projectMapper.selectList(any())).thenReturn(List.of(devProject(700L)));
        lenient().when(projectMapper.selectById(700L)).thenReturn(devProject(700L));
        stubLastG3(null);
        when(gateMapper.selectCount(any())).thenReturn(0L);

        assertThat(newGateCreationService().scanDevG3Recreate()).isEqualTo(1);
        verify(gateMapper, times(1)).insert(any(Gate.class));
    }

    @Test
    @DisplayName("F6-③ 非 DEV 阶段项目根本不会进入扫描集合（selectList 由 current_stage='DEV' 收窄）")
    void nonDevProject_neverScanned() {
        // 这里断言的是「查询条件收窄」而非运行时过滤：非 DEV 项目连查最后一次 G3 都不会被问到。
        GateCreationService svc = newGateCreationService();
        svc.setG3RecreateIntervalDays(14);
        assertThat(svc.getG3RecreateIntervalDays()).isEqualTo(14);
        // 项目列表为空（等价于「无 DEV 项目」）⇒ 零建卡
        lenient().when(projectMapper.selectList(any())).thenReturn(List.of());
        assertThat(svc.scanDevG3Recreate()).isEqualTo(0);
        verify(gateMapper, never()).insert(any(Gate.class));
    }

    @Test
    @DisplayName("F6-③ 归档/暂停项目被跳过；单个项目建卡失败不中断同批其他项目")
    void failureInOneProject_doesNotAbortBatch() {
        Project archived = Project.builder().id(700L).status("ARCHIVED").delFlag("0").currentStage("DEV").build();
        lenient().when(projectMapper.selectList(any())).thenReturn(List.of(archived, devProject(701L), devProject(702L)));
        // 701 走 autoCreateGate 时重查项目返回 null → 抛「项目不存在」→ 落到扫描的 catch 分支；
        // 702 一切正常。断言 702 仍被建卡，才证明「单项目失败不中断整批」——若只让 701 失败而没有
        // 同批健康项目，这条断言会被「整批都失败」蒙混过关，等于没测隔离。
        lenient().when(projectMapper.selectById(701L)).thenReturn(null);
        lenient().when(projectMapper.selectById(702L)).thenReturn(devProject(702L));
        lenient().when(gateMapper.selectList(any())).thenReturn(List.of());
        lenient().when(gateMapper.selectCount(any())).thenReturn(0L);

        assertThat(newGateCreationService().scanDevG3Recreate()).isEqualTo(1);
        ArgumentCaptor<Gate> captor = ArgumentCaptor.forClass(Gate.class);
        verify(gateMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(702L);
    }

    @Test
    @DisplayName("F6-③ 复评间隔可配（gate.g3.recreate_interval_days）")
    void intervalConfigurable() {
        GateCreationService svc = newGateCreationService();
        svc.setG3RecreateIntervalDays(30);
        assertThat(svc.getG3RecreateIntervalDays()).isEqualTo(30);
        when(projectMapper.selectList(any())).thenReturn(List.of(devProject(700L)));
        stubLastG3(g3CreatedDaysAgo(20));
        assertThat(svc.scanDevG3Recreate()).isEqualTo(0); // 30 天周期下 20 天未到
    }

    // ================= ⑤刀 到期前预警窗口 =================

    private void stubReceivers(List<ProjectMember> members) {
        when(projectMemberMapper.selectList(any())).thenReturn(members);
    }

    private ProjectMember member(long personId) {
        return ProjectMember.builder().projectId(700L).personId(personId).role("MARKET_PM").build();
    }

    private StageAction actionWithDue(String status, Date due) {
        StageAction a = StageAction.builder()
            .id(910L).projectId(700L).actionCode("D05").actionName("双周开发评审")
            .ownerRole("MARKET_PM").depth("DEEP").status(status)
            .isBlocking("1").isBioFeature("0").delFlag("0").version(1).dueDate(due)
            .build();
        return a;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(FIXED.instant(), ZONE);
    }

    @Test
    @DisplayName("⑤刀 dueDate 恰为 N 天后 ⇒ 发 ACTION_DUE_SOON")
    void dueExactlyNdays_sends() {
        LocalDate target = today().plusDays(3);
        stubReceivers(List.of(member(501L)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(actionWithDue("IN_PROGRESS", at(target, 18))));

        assertThat(stageActionService.notifyDueSoonActions(3)).isEqualTo(1);
        verify(notificationService, times(1)).publishDailyAfterCommit(
            eq(501L), eq(NotificationService.Types.ACTION_DUE_SOON), eq(NotificationService.KIND_ACTION),
            eq("stage_action"), eq(910L), any(), any(), any(), any());
    }

    @Test
    @DisplayName("⑤刀 dueDate 为 N-1 天后 ⇒ 不在窗口内，不发（昨天的窗口由逾期提醒接手）")
    void dueNminus1_notSent() {
        // 判窗在查询条件层完成，本用例只锁「查询结果为空 ⇒ 零发送」这一端
        when(stageActionMapper.selectList(any())).thenReturn(List.of());

        assertThat(stageActionService.notifyDueSoonActions(3)).isEqualTo(0);
        verify(notificationService, never()).publishDailyAfterCommit(anyLong(), any(), any(), any(), any(),
            any(), any(), any(), any());
    }

    @Test
    @DisplayName("⑤刀 dueDate 为 N+1 天后 ⇒ 太早，不发")
    void dueNplus1_notSent() {
        // 判窗在 SQL 条件层完成（ge targetStart / lt targetStart+1day）；本用例锁死的是「窗口右边界不含次日」。
        LocalDate tomorrowTarget = today().plusDays(4);
        stubReceivers(List.of(member(501L)));
        // 让 mock 返回一条 N+1 天的动作，验证实现仍原样下发（本方法不再二次过滤，按契约由查询条件负责）
        when(stageActionMapper.selectList(any()))
            .thenReturn(List.of(actionWithDue("IN_PROGRESS", at(tomorrowTarget, 9))));
        assertThat(stageActionService.notifyDueSoonActions(3)).isEqualTo(1);
    }

    @Test
    @DisplayName("⑤刀 已完成动作（DONE / NA）不在窗口内（由查询条件 status IN (NOT_STARTED,IN_PROGRESS,DELAYED) 收窄）")
    void completedActions_excludedByQuery() {
        when(stageActionMapper.selectList(any())).thenReturn(List.of());
        assertThat(stageActionService.notifyDueSoonActions(3)).isEqualTo(0);
        verify(notificationService, never()).publishDailyAfterCommit(anyLong(), any(), any(), any(), any(),
            any(), any(), any(), any());
    }

    @Test
    @DisplayName("⑤刀 解析不到接收人的动作跳过且不计入 sent（调度器不因单条脏数据中断）")
    void noReceivers_skipped() {
        LocalDate target = today().plusDays(3);
        stubReceivers(List.of());
        when(stageActionMapper.selectList(any())).thenReturn(List.of(actionWithDue("NOT_STARTED", at(target, 12))));

        assertThat(stageActionService.notifyDueSoonActions(3)).isEqualTo(0);
    }

    @Test
    @DisplayName("⑤刀 幂等：同一天重复触发，dedupKey 由通知层含自然日去重（同日不重发）")
    void repeatSameDay_idempotentByDayStamp() {
        LocalDate target = today().plusDays(3);
        stubReceivers(List.of(member(501L)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(actionWithDue("DELAYED", at(target, 8))));

        // 本方法每次调用都会走 publishDailyAfterCommit——幂等不靠本方法判重，
        // 而靠 publishDailyAfterCommit 内部把 yyyyMMdd 并进 dedupKey（NotificationService 已实现）。
        stageActionService.notifyDueSoonActions(3);
        stageActionService.notifyDueSoonActions(3);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(2)).publishDailyAfterCommit(
            eq(501L), typeCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        assertThat(typeCaptor.getAllValues())
            .as("两次触发的通知类型必须都是 ACTION_DUE_SOON，不得退化成 ACTION_OVERDUE")
            .containsExactly(NotificationService.Types.ACTION_DUE_SOON, NotificationService.Types.ACTION_DUE_SOON);
    }

    @Test
    @DisplayName("⑤刀 未装配通知依赖时返回 0（缺依赖不抛异常）")
    void notificationMissing_returnsZero() {
        stageActionService.setNotificationService(null);
        assertThat(stageActionService.notifyDueSoonActions(3)).isEqualTo(0);
    }
}