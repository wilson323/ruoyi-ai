package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ReceiptLedgerMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R232-LC03 终算对账服务单测。
 * <p>mock 合法性：BonusPool/ReceiptLedger 载荷形状与真库生产者完全一致
 * （{@code BonusPoolService.buildPoolFromProjectWithAchievement} 落库形状 + {@code ReceiptLedgerService.recordReceipt}
 * 窗口行形状），不复造真库不可能出现的组合。
 * <p>公式复用真实例：BonusPoolService 用 4 参构造（businessConfigService 为 null → readPoolRateZk 回退 0.05）、
 * ReceiptLedgerService 用 mapper mock 真公式，tierCoefficientOf/§三.2.5 完整公式/达成率全走真码，零 stub。
 */
@Tag("dev")
class Lc03SettlementReconcileServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final long PROJECT_ID = 100L;

    private BonusPoolMapper bonusPoolMapper;
    private ProjectMapper projectMapper;
    private KpiRecordMapper kpiRecordMapper;
    private ReceiptLedgerMapper receiptLedgerMapper;
    private ProjectScoreService projectScoreService;
    private KpiSharedReconcileService kpiReconcileService;
    private Lc03SettlementReconcileService service;

    @BeforeEach
    void setUp() {
        bonusPoolMapper = mock(BonusPoolMapper.class);
        projectMapper = mock(ProjectMapper.class);
        kpiRecordMapper = mock(KpiRecordMapper.class);
        receiptLedgerMapper = mock(ReceiptLedgerMapper.class);
        projectScoreService = mock(ProjectScoreService.class);
        kpiReconcileService = mock(KpiSharedReconcileService.class);
        // 公式真实例（零 stub）：BonusPoolService §三.2.5/tierCoefficientOf/readPoolRateZk 回退 0.05；
        // ReceiptLedgerService windowNet/calculateAchievementRate 同源
        BonusPoolService formulas = new BonusPoolService(
            bonusPoolMapper, projectMapper, kpiRecordMapper, projectScoreService);
        ReceiptLedgerService receipts = new ReceiptLedgerService(receiptLedgerMapper, projectMapper);
        service = new Lc03SettlementReconcileService(
            bonusPoolMapper, projectMapper, formulas, receipts, kpiReconcileService);
    }

    /* ---------- fixtures ---------- */

    /** 真库生产者同形项目：目标销售额 2000（达成率分母）、A 级系数 1.0。 */
    private Project project() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setTargetSalesAmount(new BigDecimal("2000"));
        p.setLevelCoefficient(new BigDecimal("1.0"));
        return p;
    }

    /**
     * 真实公式自检过的期望池（buildPoolFromProjectWithAchievement 落库形状）：
     * 窗口净额 1000 → 达成率 1000/2000=50.0000 → 阶梯 50 命中 0.3 档；
     * finalPool = 1000 × 0.05 × 1.0 × 0.3 × 1.0 = 15（中性路）。
     */
    private BonusPool pool(BigDecimal finalPool) {
        return BonusPool.builder()
            .id(1L).projectId(PROJECT_ID)
            .targetSales(new BigDecimal("1000"))
            .poolRate(new BigDecimal("0.05"))
            .coefficient(new BigDecimal("1.0"))
            .achievementRate(new BigDecimal("50.0000"))
            .tierCoefficient(new BigDecimal("0.3"))
            .finalPool(finalPool)
            .status("DRAFT")
            .calculatedAt(Date.from(Instant.parse("2026-09-15T00:00:00Z")))
            .build();
    }

    /** 窗口内 RECEIPT 行（recordReceipt 落库形状）：回款 1000、退款 0 → 净额 1000。 */
    private void stubWindowRows(int rows, BigDecimal receipt) {
        when(receiptLedgerMapper.selectList(any())).thenReturn(
            rows == 0 ? List.of()
                : List.of(org.ruoyi.ipd.domain.ReceiptLedger.builder()
                    .projectId(PROJECT_ID).source("RECEIPT")
                    .receiptAmount(receipt).refundAmount(BigDecimal.ZERO).build()));
    }

    private void stubProjectAndPool(BonusPool pool) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(bonusPoolMapper.selectByProjectIdAnyStatus(PROJECT_ID)).thenReturn(pool);
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of());
    }

    /* ---------- 用例 ---------- */

    @Test
    void allMatchWhenStoredEqualsRecompute() {
        stubProjectAndPool(pool(new BigDecimal("15")));
        stubWindowRows(1, new BigDecimal("1000"));
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.noData()).isFalse();
        assertThat(report.period()).isEqualTo("2026-09");
        assertThat(report.items()).hasSize(6)
            .allSatisfy(i -> assertThat(i.verdict())
                .isEqualTo(Lc03SettlementReconcileService.VERDICT_MATCH));
        assertThat(report.items()).filteredOn(i -> "FINAL_POOL".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.stored()).isEqualTo("15");
                assertThat(i.recomputed()).isEqualTo("15");
            });
        assertThat(report.finalPoolVerdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_MATCH);
        assertThat(report.recomputedFinalPool()).isEqualTo("15");
    }

    @Test
    void detectsFormulaDiffWhenFinalPoolTampered() {
        // 存储 finalPool=999 vs 双路复算 15 → DIFF，且如实披露个人绩效系数不落库待人工核
        stubProjectAndPool(pool(new BigDecimal("999")));
        stubWindowRows(1, new BigDecimal("1000"));
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i -> "FINAL_POOL".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_DIFF);
                assertThat(i.recomputed()).contains("中性=15").contains("推导=15");
                assertThat(i.note()).contains("个人绩效系数不落 bonus_pools");
            });
        assertThat(report.finalPoolVerdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_DIFF);
    }

    @Test
    void detectsReceiptsAndDownstreamDiffWhenWindowNetChanged() {
        // 窗口净额 800 ≠ 存储基数 1000 → RECEIPTS_NET DIFF；达成率 40 阶梯 0 传导 DIFF
        stubProjectAndPool(pool(new BigDecimal("15")));
        stubWindowRows(1, new BigDecimal("800"));
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i -> "RECEIPTS_NET".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_DIFF);
                assertThat(i.recomputed()).isEqualTo("800");
            });
        assertThat(report.items()).filteredOn(i -> "ACHIEVEMENT_RATE".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_DIFF);
                assertThat(i.recomputed()).isEqualTo("40");
            });
        assertThat(report.items()).filteredOn(i -> "TIER_COEFFICIENT".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_DIFF);
                assertThat(i.recomputed()).isEqualTo("0");
            });
    }

    @Test
    void poolRateAndLevelCoefficientDriftFlagSourceChanges() {
        // 存储 0.08 vs 当前配置 0.05、存储系数 1.5 vs 源表 1.0 → 两项 DRIFT（配置/源表已变更）
        BonusPool drifted = pool(new BigDecimal("15"));
        drifted.setPoolRate(new BigDecimal("0.08"));
        drifted.setCoefficient(new BigDecimal("1.5"));
        stubProjectAndPool(drifted);
        stubWindowRows(1, new BigDecimal("1000"));
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i -> "POOL_RATE".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_DRIFT);
                assertThat(i.note()).contains("配置变更");
            });
        assertThat(report.items()).filteredOn(i -> "LEVEL_COEFFICIENT".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_DRIFT);
                assertThat(i.note()).contains("G1 双签后改");
            });
    }

    @Test
    void missingWindowRowsMarkPendingWithoutFabricatingZero() {
        // W14-02 缺值语义：窗口无台账行 → 净额/达成率/阶梯待补（不按 0 伪判）；公式自洽项仍可复算
        stubProjectAndPool(pool(new BigDecimal("15")));
        stubWindowRows(0, null);
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i -> "RECEIPTS_NET".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_PENDING);
                assertThat(i.note()).contains("不按 0 伪判");
            });
        assertThat(report.items()).filteredOn(i -> "ACHIEVEMENT_RATE".equals(i.code()))
            .singleElement().satisfies(i ->
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_PENDING));
        assertThat(report.items()).filteredOn(i -> "TIER_COEFFICIENT".equals(i.code()))
            .singleElement().satisfies(i ->
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_PENDING));
    }

    @Test
    void finalPoolMatchesDerivedPersonalCoefficientPath() {
        // kpi_records 推导个人绩效系数 0.6 → derived 路 1000×0.05×1.0×0.3×0.6=9；存储 9 命中推导路 → MATCH 并注明
        stubProjectAndPool(pool(new BigDecimal("9")));
        stubWindowRows(1, new BigDecimal("1000"));
        when(kpiRecordMapper.selectCount(any())).thenReturn(1L);
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of(
            KpiRecord.builder().comprehensiveScore(new BigDecimal("90")).build()));
        when(projectScoreService.projectPerformanceCoefficient(any())).thenReturn(new BigDecimal("0.6"));
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i -> "FINAL_POOL".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_MATCH);
                assertThat(i.recomputed()).isEqualTo("9");
            });
        assertThat(report.personalCoefficientNote()).contains("推导同源").contains("0.6");
    }

    @Test
    void awaitingSettlementLedgerMarksAllPendingWithoutFabricatingScores() {
        // 无奖金池 → 全项 PENDING_DATA，不按 0 伪判，仍是有台账的有效产出
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(bonusPoolMapper.selectByProjectIdAnyStatus(PROJECT_ID)).thenReturn(null);
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.noData()).isTrue();
        assertThat(report.period()).isEqualTo("2026-09");
        assertThat(report.items()).hasSize(6).allSatisfy(i ->
            assertThat(i.verdict()).isEqualTo(Lc03SettlementReconcileService.VERDICT_PENDING));
        assertThat(report.verdictCounts())
            .containsEntry(Lc03SettlementReconcileService.VERDICT_PENDING, 6L);
    }

    @Test
    void missingProjectFailsFastWithoutFakeLedger() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.reconcile(PROJECT_ID, CLOCK))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("项目不存在");
    }

    @Test
    void leadersOfDelegatesToKpiReconcileForSingleSourceOfTruth() {
        // 复用 W14 leadersOf 同口径（禁止第三份拷贝），转发即为契约
        when(kpiReconcileService.leadersOf(PROJECT_ID)).thenReturn(Set.of(9L));
        assertThat(service.leadersOf(PROJECT_ID)).isEqualTo(Set.of(9L));
    }
}
