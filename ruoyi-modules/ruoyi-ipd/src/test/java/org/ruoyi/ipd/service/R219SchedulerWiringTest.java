package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.controller.BidInvitationExpireScanController;
import org.ruoyi.ipd.controller.StageActionOverdueScanController;
import org.ruoyi.ipd.security.IpdPermission;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R219 卡④（ef20c06a）调度器接线批契约测试。
 *
 * <p>缺陷（R219 归因同 DEF-B「实现完成但入口不可达」孤儿盘点族）：
 * ① {@code BidInvitationService.expireOverdue} 零 main 调用方——OPEN 到期邀标永不转 EXPIRED；
 * ② {@code NotificationService.Types.ACTION_OVERDUE} 零发布方——逾期开放动作无人提醒；
 * ③ {@code AllowanceLedgerService.autoScan} 只 selectCount——allowance_ledgers 无定时生产者。
 *
 * <p>契约断言（R218AcProd09WiringTest 同款惯例）：
 * ① 三链各有 @Scheduled 调度器且方法委托 service（cron 错峰锁定 09:45/09:50/每月 1 日 10:00）；
 * ② 链①②有超管手动扫描端点；链③ autoScan 编排为「先生成后计数」；
 * ③ notifyOverdueActions 接收人映射：MARKET_PM/RD_PM/BOTH→在职成员、GROUP_LEADER→主组组长、
 *    可选项缺失优雅降级返回 0；
 * ④ generateMonthlyLedgers：幂等跳过、2×cap 标记、低分腿停发（FINALIZED 且 &lt;60）、
 *    NO_OUTPUT_60_DAYS 腿停发（附加项目四类并集活动空/超 60 天，AC-INC-07；主项目不触发 AC-INC-08）、
 *    kpiRecordMapper 缺失不误停、month 格式守卫。
 *
 * <p>mock 合法性：全部 fixture 字段组合对齐真库写入路径——MARKET_PM 开放动作带 dueDate 是
 * instantiate+transit(IN_PROGRESS)+PATCH dueDate 的正常产物；project_members 在职行（exitDate NULL）
 * 是 bindMember 正常产物；KPI FINALIZED 行是真库 kpi_records 现状（全量 FINALIZED）；
 * ADDITIONAL member 是 bindMember 真库值域（project_members.member_type 实测 64 行）；
 * stage_actions.confirmed_at/by 是 confirm() 正常产物，deliverables.uploaded_at/by 是上传正常产物。
 */
@Tag("dev")
class R219SchedulerWiringTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "r219-wiring");
        TableInfoHelper.initTableInfo(assistant, StageAction.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, ProductGroup.class);
        TableInfoHelper.initTableInfo(assistant, AllowanceLedger.class);
        TableInfoHelper.initTableInfo(assistant, KpiRecord.class);
        TableInfoHelper.initTableInfo(assistant, Deliverable.class);
        TableInfoHelper.initTableInfo(assistant, GateReview.class);
    }

    // ===== 契约①：三链调度接线（委托 + cron 错峰锁定） =====

    @Test
    @DisplayName("R219-①a BidInvitationExpireScheduler @Scheduled(09:45) 委托 expireOverdue")
    void bidExpireSchedulerDelegates() throws Exception {
        Scheduled cron = scheduledMethodOf(BidInvitationExpireScheduler.class);
        assertThat(cron.cron()).as("错峰时刻锁定（IpdSchedulingConfig 任务表）").isEqualTo("0 45 9 * * ?");
        BidInvitationService svc = mock(BidInvitationService.class);
        Object instance = BidInvitationExpireScheduler.class.getConstructor(BidInvitationService.class).newInstance(svc);
        invokeScheduled(instance, BidInvitationExpireScheduler.class);
        verify(svc).expireOverdue();
    }

    @Test
    @DisplayName("R219-①b StageActionOverdueScheduler @Scheduled(09:50) 委托 notifyOverdueActions")
    void stageActionOverdueSchedulerDelegates() throws Exception {
        Scheduled cron = scheduledMethodOf(StageActionOverdueScheduler.class);
        assertThat(cron.cron()).isEqualTo("0 50 9 * * ?");
        StageActionService svc = mock(StageActionService.class);
        Object instance = StageActionOverdueScheduler.class.getConstructor(StageActionService.class).newInstance(svc);
        invokeScheduled(instance, StageActionOverdueScheduler.class);
        verify(svc).notifyOverdueActions();
    }

    @Test
    @DisplayName("R219-①c AllowanceMonthlyLedgerScheduler @Scheduled(每月1日10:00) 为上一自然月生成台账")
    void allowanceSchedulerDelegates() throws Exception {
        Scheduled cron = scheduledMethodOf(AllowanceMonthlyLedgerScheduler.class);
        assertThat(cron.cron()).isEqualTo("0 0 10 1 * ?");
        AllowanceService svc = mock(AllowanceService.class);
        Object instance = AllowanceMonthlyLedgerScheduler.class.getConstructor(AllowanceService.class).newInstance(svc);
        invokeScheduled(instance, AllowanceMonthlyLedgerScheduler.class);
        verify(svc).generateMonthlyLedgers(YearMonth.now().minusMonths(1).toString());
    }

    private static Scheduled scheduledMethodOf(Class<?> scheduler) {
        for (Method m : scheduler.getDeclaredMethods()) {
            Scheduled s = m.getAnnotation(Scheduled.class);
            if (s != null) {
                return s;
            }
        }
        throw new AssertionError("调度器缺 @Scheduled 方法：" + scheduler.getSimpleName());
    }

    private static void invokeScheduled(Object instance, Class<?> scheduler) throws Exception {
        for (Method m : scheduler.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Scheduled.class)) {
                m.invoke(instance);
                return;
            }
        }
    }

    // ===== 契约②：手动扫描端点（超管兜底） =====

    @Test
    @DisplayName("R219-②a POST /api/v1/bid-invitations/expire-scan 存在且 requireAdmin 后委托 expireOverdue")
    void bidExpireScanEndpointWired() {
        BidInvitationService svc = mock(BidInvitationService.class);
        IpdPermission permission = mock(IpdPermission.class);
        when(svc.expireOverdue()).thenReturn(3);
        var resp = new BidInvitationExpireScanController(svc, permission).expireScan();
        verify(permission).requireAdmin();
        verify(svc).expireOverdue();
        assertThat(resp.getData()).containsEntry("expiredCount", 3);
    }

    @Test
    @DisplayName("R219-②b POST /api/v1/stage-actions/overdue-scan 存在且 requireAdmin 后委托 notifyOverdueActions")
    void stageActionOverdueScanEndpointWired() {
        StageActionService svc = mock(StageActionService.class);
        IpdPermission permission = mock(IpdPermission.class);
        when(svc.notifyOverdueActions()).thenReturn(5);
        var resp = new StageActionOverdueScanController(svc, permission).overdueScan();
        verify(permission).requireAdmin();
        verify(svc).notifyOverdueActions();
        assertThat(resp.getData()).containsEntry("notifiedCount", 5);
    }

    // ===== 契约③：notifyOverdueActions 行为（接收人映射 + 降级） =====

    private StageActionMapper actionMapper;
    private NotificationService notificationService;
    private ProjectMemberMapper memberMapper;
    private ProductGroupMapper groupMapper;
    private ProjectMapper projectMapper;
    private StageActionService stageActionService;

    private StageActionService overdueService() {
        if (stageActionService == null) {
            actionMapper = mock(StageActionMapper.class);
            notificationService = mock(NotificationService.class);
            memberMapper = mock(ProjectMemberMapper.class);
            groupMapper = mock(ProductGroupMapper.class);
            projectMapper = mock(ProjectMapper.class);
            stageActionService = new StageActionService(actionMapper, mock(DeliverableMapper.class),
                mock(IAuditLogService.class), mock(ProjectStageMapper.class), projectMapper);
            stageActionService.setNotificationService(notificationService);
            stageActionService.setProjectMemberMapper(memberMapper);
            stageActionService.setProductGroupMapper(groupMapper);
        }
        return stageActionService;
    }

    private static StageAction overdueAction(Long id, String ownerRole) {
        return overdueAction(id, ownerRole, 100L);
    }

    private static StageAction overdueAction(Long id, String ownerRole, Long projectId) {
        return StageAction.builder()
            .id(id).projectId(projectId).stageId(10L).actionCode("C01").actionName("概念评估")
            .ownerRole(ownerRole).depth("DEEP").status("IN_PROGRESS")
            .dueDate(new Date(System.currentTimeMillis() - 86_400_000L))
            .build();
    }

    private static ProjectMember activeMember(Long personId, String role) {
        return ProjectMember.builder()
            .projectId(100L).personId(personId).role(role).exitDate(null).build();
    }

    @Test
    @DisplayName("R219-③a MARKET_PM 逾期动作 → 发布给该项目在职 MARKET_PM 成员")
    void notifyOverduePublishesToActiveMembers() {
        StageActionService svc = overdueService();
        when(actionMapper.selectList(any())).thenReturn(List.of(overdueAction(1L, "MARKET_PM")));
        when(memberMapper.selectList(any())).thenReturn(List.of(activeMember(101L, "MARKET_PM")));

        assertThat(svc.notifyOverdueActions()).isEqualTo(1);
        verify(notificationService).publishDaily(eq(101L), eq("ACTION_OVERDUE"),
            eq(NotificationService.KIND_ACTION), eq("stage_action"), eq(1L),
            anyString(), anyString(), eq("/projects/100"), any(Date.class));
    }

    @Test
    @DisplayName("R219-③b GROUP_LEADER 逾期动作 → 发布给项目主产品组组长（product_groups.leader_person_id）")
    void notifyOverdueResolvesGroupLeader() {
        StageActionService svc = overdueService();
        when(actionMapper.selectList(any())).thenReturn(List.of(overdueAction(2L, "GROUP_LEADER")));
        when(projectMapper.selectById(100L)).thenReturn(
            Project.builder().id(100L).mainGroupId(9L).status("ACTIVE").delFlag("0").build());
        when(groupMapper.selectById(9L)).thenReturn(
            ProductGroup.builder().id(9L).leaderPersonId(555L).build());

        assertThat(svc.notifyOverdueActions()).isEqualTo(1);
        verify(notificationService).publishDaily(eq(555L), eq("ACTION_OVERDUE"),
            eq(NotificationService.KIND_ACTION), eq("stage_action"), eq(2L),
            anyString(), anyString(), anyString(), any(Date.class));
        verify(memberMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("R219-③c BOTH → MARKET_PM+RD_PM 两类在职成员并集；解析不到接收人的动作跳过不抛错")
    void notifyOverdueBothRolesUnionAndSkipUnresolvable() {
        StageActionService svc = overdueService();
        // 两条动作分属不同项目：避免命中同项目成员缓存，第二条真实走「查无接收人」分支
        when(actionMapper.selectList(any())).thenReturn(List.of(
            overdueAction(3L, "BOTH", 100L), overdueAction(4L, "MARKET_PM", 200L)));
        when(memberMapper.selectList(any()))
            .thenReturn(List.of(activeMember(101L, "MARKET_PM"), activeMember(102L, "RD_PM")))
            .thenReturn(List.of());

        assertThat(svc.notifyOverdueActions()).isEqualTo(2);
        verify(notificationService).publishDaily(eq(101L), eq("ACTION_OVERDUE"), anyString(),
            eq("stage_action"), eq(3L), anyString(), anyString(), anyString(), any(Date.class));
        verify(notificationService).publishDaily(eq(102L), eq("ACTION_OVERDUE"), anyString(),
            eq("stage_action"), eq(3L), anyString(), anyString(), anyString(), any(Date.class));
    }

    @Test
    @DisplayName("R219-③d 可选协作者未装配（裸 5 参构造，存量测试不破）⇒ 返回 0 不 NPE")
    void notifyOverdueDegradesWithoutWiring() {
        StageActionService bare = new StageActionService(mock(StageActionMapper.class),
            mock(DeliverableMapper.class), mock(IAuditLogService.class),
            mock(ProjectStageMapper.class), mock(ProjectMapper.class));
        assertThat(bare.notifyOverdueActions()).isZero();
    }

    // ===== 契约④：generateMonthlyLedgers 行为 =====

    private AllowanceLedgerMapper ledgerMapper;
    private ProjectMemberMapper allowanceMemberMapper;
    private KpiRecordMapper kpiRecordMapper;
    private StageActionMapper stageActionMapper;
    private DeliverableMapper deliverableMapper;
    private GateReviewMapper gateReviewMapper;
    private AllowanceService allowanceService;

    private AllowanceService ledgerService(boolean withKpi) {
        return ledgerService(withKpi, true);
    }

    /** withActivity=false：活动类 mapper 全不装配（四类并集数据源缺失 ⇒ 不判 NO_OUTPUT 腿）。 */
    private AllowanceService ledgerService(boolean withKpi, boolean withActivity) {
        if (allowanceService == null) {
            ledgerMapper = mock(AllowanceLedgerMapper.class);
            allowanceMemberMapper = mock(ProjectMemberMapper.class);
            kpiRecordMapper = mock(KpiRecordMapper.class);
            allowanceService = new AllowanceService(ledgerMapper, allowanceMemberMapper);
        }
        allowanceService.setKpiRecordMapper(withKpi ? kpiRecordMapper : null);
        if (stageActionMapper == null) {
            stageActionMapper = mock(StageActionMapper.class);
            deliverableMapper = mock(DeliverableMapper.class);
            gateReviewMapper = mock(GateReviewMapper.class);
        }
        allowanceService.setActivityMappers(
            withActivity ? stageActionMapper : null,
            withActivity ? deliverableMapper : null,
            withActivity ? gateReviewMapper : null);
        return allowanceService;
    }

    private static ProjectMember bound(Long personId, Long projectId, String amount) {
        return bound(personId, projectId, amount, null);
    }

    private static ProjectMember bound(Long personId, Long projectId, String amount, String memberType) {
        return ProjectMember.builder()
            .projectId(projectId).personId(personId).role("RD_PM")
            .lockedLevel("L3").lockedAmount(new BigDecimal(amount))
            .joinDate(new Date(0L)).exitDate(null).memberType(memberType).build();
    }

    @Test
    @DisplayName("R219-④a 双项目叠加超 2×cap：两行各记 lockedAmount 且 capApplied=1")
    void generateCreatesRowsWithCapFlag() {
        AllowanceService svc = ledgerService(false);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(
            bound(1L, 10L, "2000"), bound(1L, 11L, "2000")));
        when(ledgerMapper.selectCount(any())).thenReturn(0L);

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(2);
        var captor = org.mockito.ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        for (AllowanceLedger row : captor.getAllValues()) {
            assertThat(row.getMonth()).isEqualTo("2026-08");
            assertThat(row.getFinalAmount()).isEqualByComparingTo("2000");
            assertThat(row.getCapApplied()).as("Σ4000 > 2×2000 不超，等号边界不标 cap").isEqualTo("0");
        }
    }

    @Test
    @DisplayName("R219-④b 四项目叠加 8000 > 2×2000：capApplied 标记生效（AC-INC-03 月度维度）")
    void generateMarksCapAppliedWhenOverMultiplier() {
        AllowanceService svc = ledgerService(false);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(
            bound(1L, 10L, "2000"), bound(1L, 11L, "2000"),
            bound(1L, 12L, "2000"), bound(1L, 13L, "2000")));
        when(ledgerMapper.selectCount(any())).thenReturn(0L);

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(4);
        var captor = org.mockito.ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row ->
            assertThat(row.getCapApplied()).isEqualTo("1"));
    }

    @Test
    @DisplayName("R219-④c 幂等：(personId,projectId,month) 已成账跳过不重复 insert（P3-3.3）")
    void generateSkipsExistingKeys() {
        AllowanceService svc = ledgerService(false);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(
            bound(1L, 10L, "2000"), bound(1L, 11L, "2000")));
        when(ledgerMapper.selectCount(any())).thenReturn(1L).thenReturn(0L);

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(1);
        verify(ledgerMapper, org.mockito.Mockito.times(1)).insert(any(AllowanceLedger.class));
    }

    @Test
    @DisplayName("R219-④d 低分腿：当月 FINALIZED KPI <60 → STOP_SCORE_BELOW_60 且实发 0（AC-INC-05）")
    void generateAppliesLowScoreStop() {
        AllowanceService svc = ledgerService(true);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        when(ledgerMapper.selectCount(any())).thenReturn(0L);
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of(
            KpiRecord.builder().personId(1L).period("2026-08").status("FINALIZED")
                .comprehensiveScore(new BigDecimal("55")).build()));

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getStopReason()).isEqualTo("STOP_SCORE_BELOW_60");
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("R219-④e kpiRecordMapper 未装配 ⇒ 不判停发腿（宁可少停不误停）")
    void generateWithoutKpiMapperNeverStops() {
        AllowanceService svc = ledgerService(false);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        when(ledgerMapper.selectCount(any())).thenReturn(0L);

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getStopReason()).isNull();
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("R219-④f month 格式守卫：非 yyyy-MM 直接拒绝")
    void generateRejectsBadMonth() {
        AllowanceService svc = ledgerService(false);
        assertThatThrownBy(() -> svc.generateMonthlyLedgers("2026-8"))
            .isInstanceOf(IpdBusinessException.class);
        assertThatThrownBy(() -> svc.generateMonthlyLedgers(null))
            .isInstanceOf(IpdBusinessException.class);
    }

    @Test
    @DisplayName("R219-④g NO_OUTPUT 腿：ADDITIONAL 成员四类信号全空 → STOP_NO_OUTPUT_60_DAYS 且实发 0（AC-INC-07）")
    void generateAppliesNoOutputStopForAdditionalMember() {
        AllowanceService svc = ledgerService(true);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(
            bound(1L, 10L, "2000", "ADDITIONAL")));
        when(ledgerMapper.selectCount(any())).thenReturn(0L);
        // 四类 mapper 默认 mock：selectOne 均返回 null（该人该项目从未活动）

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getStopReason()).isEqualTo("STOP_NO_OUTPUT_60_DAYS");
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("R219-④h 主项目不触发：PRIMARY 成员同样四类全空 → 不停发（AC-INC-08）")
    void generateNeverStopsNoOutputForPrimaryMember() {
        AllowanceService svc = ledgerService(true);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(
            bound(1L, 10L, "2000", "PRIMARY")));
        when(ledgerMapper.selectCount(any())).thenReturn(0L);

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getStopReason()).isNull();
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("R219-④i 四类并集取最近：61天前动作+30天前交付物 → 30 天不停发；并集全空才停")
    void generateTakesLatestOfFourSignals() {
        AllowanceService svc = ledgerService(true);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(
            bound(1L, 10L, "2000", "ADDITIONAL")));
        when(ledgerMapper.selectCount(any())).thenReturn(0L);
        Date daysAgo61 = new Date(System.currentTimeMillis() - 61L * 24 * 3600 * 1000);
        Date daysAgo30 = new Date(System.currentTimeMillis() - 30L * 24 * 3600 * 1000);
        when(stageActionMapper.selectOne(any())).thenReturn(
            StageAction.builder().projectId(10L).confirmedBy(1L).confirmedAt(daysAgo61).build());
        when(deliverableMapper.selectOne(any())).thenReturn(
            Deliverable.builder().projectId(10L).uploadedBy(1L).uploadedAt(daysAgo30).build());

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getStopReason()).isNull();
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("R219-④j 活动类 mapper 全不装配：ADDITIONAL 成员 → 不判 NO_OUTPUT 腿（少算不误停）")
    void generateWithoutActivityMappersNeverStopsNoOutput() {
        AllowanceService svc = ledgerService(true, false);
        when(allowanceMemberMapper.selectList(any())).thenReturn(List.of(
            bound(1L, 10L, "2000", "ADDITIONAL")));
        when(ledgerMapper.selectCount(any())).thenReturn(0L);

        assertThat(svc.generateMonthlyLedgers("2026-08")).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getStopReason()).isNull();
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("2000");
    }

    // ===== 契约②'：autoScan 编排「先生成后计数」 =====

    @Test
    @DisplayName("R219-②c autoScan 先委托 generateMonthlyLedgers 再计数；未装配时退化旧行为")
    void autoScanOrchestratesGenerateThenCount() {
        AllowanceLedgerMapper scanLedgerMapper = mock(AllowanceLedgerMapper.class);
        AllowanceService mockAllowance = mock(AllowanceService.class);
        AllowanceLedgerService svc = new AllowanceLedgerService(scanLedgerMapper);
        svc.setAllowanceService(mockAllowance);
        when(mockAllowance.generateMonthlyLedgers("2026-08")).thenReturn(7);
        when(scanLedgerMapper.selectCount(any())).thenReturn(42L);

        IpdActor admin = new IpdActor(1L, "超管", "SUPER_ADMIN", null);
        assertThat(svc.autoScan(admin, "2026-08")).isEqualTo(42);
        verify(mockAllowance).generateMonthlyLedgers("2026-08");

        // 裸构造（存量 P331 测试同款）：不装配生成器 → 只计数，行为不破
        AllowanceLedgerService bare = new AllowanceLedgerService(scanLedgerMapper);
        assertThat(bare.autoScan(admin, "2026-08")).isEqualTo(42);
    }
}
