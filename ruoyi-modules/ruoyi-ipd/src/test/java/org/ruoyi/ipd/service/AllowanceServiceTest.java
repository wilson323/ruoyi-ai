package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.NegativeFeedback;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ④刀「津贴按原需求」第 1 批契约测试（2026-10-07）。
 *
 * <p>覆盖四项改造：① 无产出腿翻案为「仅提醒」；② 封顶倍数从死配置变成真削减；
 * ③ 负反馈（BR-INC-10）入金额；④ 金额变更留审计。
 *
 * <p>口径来源：全部断言对齐 {@code AllowanceService} 现码与真库写入路径
 * （project_members 在职行 / negative_feedbacks status=EXECUTED / allowance_ledgers decimal(10,2)），
 * 不引用任何审计报告的估算数字。
 */
@Tag("dev")
class AllowanceServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "allowance-svc");
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, AllowanceLedger.class);
        TableInfoHelper.initTableInfo(assistant, KpiRecord.class);
        TableInfoHelper.initTableInfo(assistant, NegativeFeedback.class);
        TableInfoHelper.initTableInfo(assistant, StageAction.class);
        TableInfoHelper.initTableInfo(assistant, Deliverable.class);
        TableInfoHelper.initTableInfo(assistant, GateReview.class);
    }

    private AllowanceLedgerMapper ledgerMapper;
    private ProjectMemberMapper memberMapper;
    private KpiRecordMapper kpiMapper;
    private NegativeFeedbackMapper negativeFeedbackMapper;
    private ISystemConfigService configService;
    private INotificationService notificationService;
    private IAuditLogService auditLogService;
    private AllowanceService svc;

    @BeforeEach
    void setUp() {
        ledgerMapper = mock(AllowanceLedgerMapper.class);
        memberMapper = mock(ProjectMemberMapper.class);
        kpiMapper = mock(KpiRecordMapper.class);
        negativeFeedbackMapper = mock(NegativeFeedbackMapper.class);
        configService = mock(ISystemConfigService.class);
        notificationService = mock(INotificationService.class);
        auditLogService = mock(IAuditLogService.class);

        svc = new AllowanceService(ledgerMapper, memberMapper);
        svc.setClock(java.time.Clock.fixed(
            java.time.Instant.parse("2026-10-07T00:00:00Z"), java.time.ZoneOffset.UTC));
        svc.setKpiRecordMapper(kpiMapper);
        svc.setActivityMappers(mock(StageActionMapper.class), mock(DeliverableMapper.class),
            mock(GateReviewMapper.class));
        svc.setNegativeFeedbackMapper(negativeFeedbackMapper);
        svc.setSystemConfigService(configService);
        svc.setNotificationService(notificationService);
        svc.setAuditLogService(auditLogService);

        when(ledgerMapper.selectCount(any())).thenReturn(0L);
        when(negativeFeedbackMapper.selectList(any())).thenReturn(List.of());
        when(kpiMapper.selectList(any())).thenReturn(List.of());
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ==================== ② 封顶倍数：死配置 → 真削减 ====================

    @Test
    @DisplayName("resolveCapMultiplier: 未装配配置服务 ⇒ 回落默认 2")
    void capMultiplierDefaultWhenNoConfig() {
        AllowanceService bare = new AllowanceService(ledgerMapper, memberMapper);
        assertThat(bare.resolveCapMultiplier()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("resolveCapMultiplier: 读 allowance.capMultiplier 配置值")
    void capMultiplierFromConfig() {
        when(configService.getValue(eq("allowance.capMultiplier"), anyString())).thenReturn("3");
        assertThat(svc.resolveCapMultiplier()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("resolveCapMultiplier: 键缺失 / 非法值 / 非正数 一律回落 2（不因脏配置多封钱）")
    void capMultiplierFallsBackOnBadValue() {
        when(configService.getValue(eq("allowance.capMultiplier"), anyString()))
            .thenReturn(null).thenReturn("abc").thenReturn("0").thenReturn("  ");
        assertThat(svc.resolveCapMultiplier()).isEqualByComparingTo("2");
        assertThat(svc.resolveCapMultiplier()).isEqualByComparingTo("2");
        assertThat(svc.resolveCapMultiplier()).isEqualByComparingTo("2");
        assertThat(svc.resolveCapMultiplier()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("calculateMonthlyAllowance 走配置封顶：4×2000=8000，cap=3 ⇒ 实发 6000（原硬编码 2 会给 4000）")
    void monthlyAllowanceHonorsConfiguredCap() {
        when(configService.getValue(eq("allowance.capMultiplier"), anyString())).thenReturn("3");
        when(memberMapper.selectList(any())).thenReturn(Arrays.asList(
            bound(10L, 1L, "2000"), bound(10L, 2L, "2000"),
            bound(10L, 3L, "2000"), bound(10L, 4L, "2000")));
        assertThat(svc.calculateMonthlyAllowance(1L, "2026-10")).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("A4-27/29 真削减：四项目叠加 8000 > 封顶线 4000 ⇒ 每行 finalAmount 由 2000 削到 1000")
    void generateTrimsFinalAmountOnCap() {
        when(memberMapper.selectList(any())).thenReturn(Arrays.asList(
            bound(1L, 10L, "2000"), bound(1L, 11L, "2000"),
            bound(1L, 12L, "2000"), bound(1L, 13L, "2000")));

        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(4);

        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper, times(4)).insert(captor.capture());
        List<AllowanceLedger> rows = captor.getAllValues();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getFinalAmount()).as("封顶真实削减到金额").isEqualByComparingTo("1000.00");
            assertThat(r.getCapApplied()).isEqualTo("1");
            assertThat(r.getBaseAmount()).as("baseAmount 仍是项目锁定额，不被削减").isEqualByComparingTo("2000");
        });
        BigDecimal total = rows.stream().map(AllowanceLedger::getFinalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).as("人当月合计 = 封顶线 4000").isEqualByComparingTo("4000.00");
    }

    @Test
    @DisplayName("未超封顶时金额逐行不变（回归：比例系数为 1）")
    void generateKeepsAmountWhenUnderCap() {
        when(memberMapper.selectList(any())).thenReturn(Arrays.asList(
            bound(1L, 10L, "2000"), bound(1L, 11L, "2000")));
        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(2);
        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.getFinalAmount()).isEqualByComparingTo("2000.00");
            assertThat(r.getCapApplied()).isEqualTo("0");
        });
    }

    @Test
    @DisplayName("非整数比例封顶：3×2000=6000，cap=2 ⇒ 每行削到 1333.33（HALF_UP 到分）")
    void generateRoundsTrimmedAmountToCents() {
        when(memberMapper.selectList(any())).thenReturn(Arrays.asList(
            bound(1L, 10L, "2000"), bound(1L, 11L, "2000"), bound(1L, 12L, "2000")));
        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(3);
        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r ->
            assertThat(r.getFinalAmount()).isEqualByComparingTo("1333.33"));
    }

    // ==================== ③ A4-43 负反馈入金额 ====================

    @Test
    @DisplayName("A4-43 主责 STOP_ALLOWANCE ⇒ 负反馈入金额，实发 ×0（不写停发标记：停发语义只归低分腿）")
    void negativeFeedbackStopZeroesAmount() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        when(negativeFeedbackMapper.selectList(any())).thenReturn(List.of(
            NegativeFeedback.builder()
                .projectId(10L).mainPersonId(1L).mainExecution("STOP_ALLOWANCE")
                .triggerType("QUALITY_ACCIDENT").status("EXECUTED").triggerMonth("2026-10")
                .build()));

        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(1);

        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("0.00");
        assertThat(captor.getValue().getStopReason())
            .as("负反馈折减不写停发标记，避免与低分停发混为一谈").isNull();
    }

    @Test
    @DisplayName("A4-43 连带 HALVE_ALLOWANCE ⇒ 实发 ×0.5")
    void negativeFeedbackHalveCutsHalf() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        when(negativeFeedbackMapper.selectList(any())).thenReturn(List.of(
            NegativeFeedback.builder()
                .projectId(10L).mainPersonId(2L).relatedPersonId(1L)
                .relatedExecution("HALVE_ALLOWANCE")
                .triggerType("QUALITY_ACCIDENT").status("EXECUTED").triggerMonth("2026-10")
                .build()));

        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(1);

        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("负反馈只影响本项目本人生效，旁人不受影响")
    void negativeFeedbackScopedToPersonAndProject() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(9L, 10L, "2000")));
        when(negativeFeedbackMapper.selectList(any())).thenReturn(List.of(
            NegativeFeedback.builder()
                .projectId(10L).mainPersonId(1L).mainExecution("STOP_ALLOWANCE")
                .status("EXECUTED").triggerMonth("2026-10").build()));
        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(1);
        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("负反馈 triggerMonth 不等于当前账期 ⇒ 不折减（负反馈不跨月无限期扣钱）")
    void negativeFeedbackMonthScoped() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        when(negativeFeedbackMapper.selectList(any())).thenReturn(List.of(
            NegativeFeedback.builder()
                .projectId(10L).mainPersonId(1L).mainExecution("STOP_ALLOWANCE")
                .status("EXECUTED").triggerMonth("2026-01").build()));
        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(1);
        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("负反馈 mapper 未装配 ⇒ 不折减（数据源缺失时不动钱）")
    void negativeFeedbackDegradesWithoutMapper() {
        svc.setNegativeFeedbackMapper(null);
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(1);
        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("2000.00");
    }

    // ==================== ① 无产出翻案：仅提醒 ====================

    @Test
    @DisplayName("① 翻案：附加项目无产出 ⇒ 照常发钱、不写 stop_reason / stop_start_date，并发提醒通知")
    void noOutputBecomesReminderOnly() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000", "ADDITIONAL")));
        // 四类活动 mapper selectOne 默认返回 null ⇒ 从未活动 ⇒ 触发提醒

        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(1);

        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getFinalAmount()).as("无产出照常发放").isEqualByComparingTo("2000.00");
        assertThat(captor.getValue().getStopReason()).as("不得污染台账停发语义").isNull();
        assertThat(captor.getValue().getStopStartDate()).isNull();

        verify(notificationService).publish(eq(1L), eq(AllowanceService.EVENT_NO_OUTPUT_REMINDER),
            eq(NotificationService.KIND_ACTION), eq("allowance_ledgers"), any(),
            anyString(), anyString(), any());
    }

    @Test
    @DisplayName("① 低分腿不受翻案影响：仍然停发（final=0 + 停发标记），且不发无产出提醒（低分优先）")
    void lowScoreStillStops() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000", "ADDITIONAL")));
        when(kpiMapper.selectList(any())).thenReturn(List.of(
            KpiRecord.builder().personId(1L).period("2026-10").status("FINALIZED")
                .comprehensiveScore(new BigDecimal("58")).build()));

        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(1);

        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("0.00");
        assertThat(captor.getValue().getStopReason()).isEqualTo("STOP_SCORE_BELOW_60");
        assertThat(captor.getValue().getStopStartDate()).isNotNull();
        verify(notificationService, never()).publish(any(), anyString(), anyString(), anyString(),
            any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("① 活动类 mapper 全缺失 ⇒ 既不提醒也不停发（少算不误判）")
    void noReminderWithoutActivitySource() {
        svc.setActivityMappers(null, null, null);
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000", "ADDITIONAL")));
        assertThat(svc.generateMonthlyLedgers("2026-10")).isEqualTo(1);
        ArgumentCaptor<AllowanceLedger> captor = ArgumentCaptor.forClass(AllowanceLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getFinalAmount()).isEqualByComparingTo("2000.00");
        verify(notificationService, never()).publish(any(), anyString(), anyString(), anyString(),
            any(), anyString(), anyString(), any());
    }

    // ==================== ④ A4-56 / A4-61 金额变更审计 ====================

    /**
     * 每行落库本就会写一条 ALLOWANCE_LEDGER_INSERT 审计，本批新增的是
     * ALLOWANCE_FINAL_AMOUNT_ADJUSTED（金额被谁改了、为什么）——断言必须按动作过滤，
     * 否则会把两条审计混在一起数。
     */
    private List<AuditLog> adjustmentAudits() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        List<AuditLog> all = new ArrayList<>(captor.getAllValues());
        List<AuditLog> adjustments = new ArrayList<>();
        for (AuditLog l : all) {
            if ("ALLOWANCE_FINAL_AMOUNT_ADJUSTED".equals(l.getAction())) {
                adjustments.add(l);
            }
        }
        return adjustments;
    }

    @Test
    @DisplayName("④ 封顶削减写审计：动作 ALLOWANCE_FINAL_AMOUNT_ADJUSTED，reason 记 CAP_TRIM")
    void capTrimWritesAudit() {
        when(memberMapper.selectList(any())).thenReturn(Arrays.asList(
            bound(1L, 10L, "2000"), bound(1L, 11L, "2000"),
            bound(1L, 12L, "2000"), bound(1L, 13L, "2000")));
        svc.generateMonthlyLedgers("2026-10");

        assertThat(adjustmentAudits()).hasSize(4)
            .allSatisfy(l -> assertThat(l.getReason()).contains("CAP_TRIM"));
    }

    @Test
    @DisplayName("④ 负反馈折减写审计：reason 记 NEGATIVE_FEEDBACK 与折减系数")
    void negativeFeedbackWritesAudit() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        when(negativeFeedbackMapper.selectList(any())).thenReturn(List.of(
            NegativeFeedback.builder()
                .projectId(10L).relatedPersonId(1L).relatedExecution("HALVE_ALLOWANCE")
                .status("EXECUTED").triggerMonth("2026-10").build()));
        svc.generateMonthlyLedgers("2026-10");

        assertThat(adjustmentAudits()).hasSize(1);
        assertThat(adjustmentAudits().get(0).getReason())
            .contains("NEGATIVE_FEEDBACK").contains("0.5");
    }

    @Test
    @DisplayName("④ 低分停发写审计：reason 记 LOW_SCORE_STOP")
    void lowScoreStopWritesAudit() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        when(kpiMapper.selectList(any())).thenReturn(List.of(
            KpiRecord.builder().personId(1L).period("2026-10").status("FINALIZED")
                .comprehensiveScore(new BigDecimal("10")).build()));
        svc.generateMonthlyLedgers("2026-10");

        assertThat(adjustmentAudits()).hasSize(1);
        assertThat(adjustmentAudits().get(0).getReason()).contains("LOW_SCORE_STOP");
    }

    @Test
    @DisplayName("④ 金额未偏离时不写金额变更审计（只留行写入审计，不刷噪声）")
    void noAdjustmentNoAudit() {
        when(memberMapper.selectList(any())).thenReturn(List.of(bound(1L, 10L, "2000")));
        svc.generateMonthlyLedgers("2026-10");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(l ->
            assertThat(l.getAction()).isEqualTo("ALLOWANCE_LEDGER_INSERT"));
    }

    // ==================== helpers ====================

    private static ProjectMember bound(Long personId, Long projectId, String amount) {
        return bound(personId, projectId, amount, null);
    }

    private static ProjectMember bound(Long personId, Long projectId, String amount, String memberType) {
        return ProjectMember.builder()
            .projectId(projectId).personId(personId).role("RD_PM")
            .lockedLevel("L3").lockedAmount(new BigDecimal(amount))
            .joinDate(new Date(0L)).exitDate(null).memberType(memberType).build();
    }
}