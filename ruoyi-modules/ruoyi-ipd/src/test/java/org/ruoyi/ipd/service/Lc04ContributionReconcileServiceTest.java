package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.BonusAllocation;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.BonusAllocationMapper;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.KpiRecordMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R232-LC04 贡献度评定对账服务单测。
 * <p>mock 合法性：Contribution/BonusAllocation 载荷形状与真库生产者完全一致
 * （{@code ContributionService.saveSelf} 落库形状 + {@code BonusPoolService.writeBonusAllocations}
 * 分配台账行形状，AC-INC-35），不复造真库不可能出现的组合。
 * <p>公式复用真实例：BonusPoolService 用 4 参构造（businessConfigService 为 null）走真
 * calculateDistribution 校验器；ContributionService.computeTierCoefficient 为 static 真公式，零 stub。
 */
@Tag("dev")
class Lc04ContributionReconcileServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final long PROJECT_ID = 100L;

    private ContributionMapper contributionMapper;
    private BonusPoolMapper bonusPoolMapper;
    private BonusAllocationMapper bonusAllocationMapper;
    private ProjectMapper projectMapper;
    private KpiSharedReconcileService kpiReconcileService;
    private Lc04ContributionReconcileService service;

    @BeforeEach
    void setUp() {
        contributionMapper = mock(ContributionMapper.class);
        bonusPoolMapper = mock(BonusPoolMapper.class);
        bonusAllocationMapper = mock(BonusAllocationMapper.class);
        projectMapper = mock(ProjectMapper.class);
        KpiRecordMapper kpiRecordMapper = mock(KpiRecordMapper.class);
        ProjectScoreService projectScoreService = mock(ProjectScoreService.class);
        kpiReconcileService = mock(KpiSharedReconcileService.class);
        // 公式真实例（零 stub）：BonusPoolService.calculateDistribution §三.2.4 真校验器
        BonusPoolService formulas = new BonusPoolService(
            bonusPoolMapper, projectMapper, kpiRecordMapper, projectScoreService);
        service = new Lc04ContributionReconcileService(
            contributionMapper, bonusPoolMapper, bonusAllocationMapper, projectMapper,
            formulas, kpiReconcileService);
    }

    /* ---------- fixtures（生产者同形） ---------- */

    private Project project() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        return p;
    }

    /**
     * saveSelf 落库形状：tier=两路自评复算之一、share 为默认 0.55/0.45（saveSelf 初始落库值）。
     * 五维全 80 → tier 0.80；全 60 → 0.60（computeTierCoefficient 真公式）。
     * <p>状态取 CONFIRMED（规约二）：分配行只在 distribute 事务内落库且前置要求最新评定 CONFIRMED
     * （BonusPoolService.distribute → requireConfirmedContribution 同事务翻池 DISTRIBUTED），
     * 含行用例的可达组合只能是 CONFIRMED+DISTRIBUTED；personId/marketContributionRate 为
     * DDL NOT NULL 列，按规约三显式赋值（与被测路径无关也要补）。
     */
    private Contribution contribution(BigDecimal tier, BigDecimal marketShare, BigDecimal rdShare) {
        Contribution c = new Contribution();
        c.setId(1L);
        c.setProjectId(PROJECT_ID);
        c.setPersonId(11L);
        c.setMarketContributionRate(new BigDecimal("0.55"));
        c.setStatus(Contribution.ST_CONFIRMED);
        c.setMarketShare(marketShare);
        c.setRdShare(rdShare);
        c.setMarketSelfInitiation(new BigDecimal("80"));
        c.setMarketSelfInnovation(new BigDecimal("80"));
        c.setMarketSelfLaunch(new BigDecimal("80"));
        c.setMarketSelfMarketResult(new BigDecimal("80"));
        c.setMarketSelfLeadership(new BigDecimal("80"));
        c.setRdSelfInitiation(new BigDecimal("80"));
        c.setRdSelfInnovation(new BigDecimal("80"));
        c.setRdSelfLaunch(new BigDecimal("80"));
        c.setRdSelfMarketResult(new BigDecimal("80"));
        c.setRdSelfLeadership(new BigDecimal("80"));
        c.setTierCoefficient(tier);
        c.setUpdateTime(Date.from(Instant.parse("2026-09-20T00:00:00Z")));
        return c;
    }

    /** writeBonusAllocations 落库形状（AC-INC-35）：allocated=finalPool×本方占比、rate=tier×本方占比、perf=池系数。 */
    private BonusAllocation allocation(long id, String role, BigDecimal allocated,
                                      BigDecimal rate, BigDecimal perf) {
        return BonusAllocation.builder()
            .id(id).bonusPoolId(1L).personId(11L)
            .roleInProject(role).contributionRate(rate)
            .performanceCoefficient(perf).allocatedAmount(allocated)
            .status("DRAFT").build();
    }

    /**
     * 池形状：targetSales/poolRate 为 DDL NOT NULL 列（规约三，取 Lc03 样板同值）。
     * status 显式传入（规约二）：含分配行→DISTRIBUTED（行与翻状态同事务落库）；无行→DRAFT（池建出未分）。
     */
    private BonusPool pool(BigDecimal finalPool, BigDecimal coefficient, String status) {
        return BonusPool.builder()
            .id(1L).projectId(PROJECT_ID).finalPool(finalPool)
            .targetSales(new BigDecimal("1000")).poolRate(new BigDecimal("0.05"))
            .coefficient(coefficient).status(status).build();
    }

    private void stubContribution(Contribution c) {
        when(contributionMapper.selectOne(any())).thenReturn(c);
    }

    private void stubPool(BonusPool p) {
        when(bonusPoolMapper.selectByProjectIdAnyStatus(PROJECT_ID)).thenReturn(p);
    }

    private void stubAllocations(List<BonusAllocation> rows) {
        when(bonusAllocationMapper.selectList(any())).thenReturn(rows);
    }

    /** 全 MATCH 载荷：tier 0.8 双路同值、share 0.55/0.45、finalPool 15、行按生产者口径落。 */
    private void stubAllMatch() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        stubContribution(contribution(new BigDecimal("0.80"), new BigDecimal("0.55"), new BigDecimal("0.45")));
        stubPool(pool(new BigDecimal("15"), new BigDecimal("1.0"), "DISTRIBUTED"));
        stubAllocations(List.of(
            allocation(1L, Contribution.ROLE_MARKET, new BigDecimal("8.25"), new BigDecimal("0.44"), new BigDecimal("1.0")),
            allocation(2L, Contribution.ROLE_RD, new BigDecimal("6.75"), new BigDecimal("0.36"), new BigDecimal("1.0"))));
    }

    /* ---------- ① tierCoefficient ---------- */

    @Test
    void allMatchWhenStoredConsistent() {
        stubAllMatch();
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.noData()).isFalse();
        assertThat(report.items()).allSatisfy(i ->
            assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_MATCH));
        assertThat(report.tierDivergenceNote()).isNull();
    }

    @Test
    void tierDisclosesDivergenceWhenPathsDisagree() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        // market 路 0.8（全 80）/ rd 路 0.6（全 60），stored=0.8 命中 market 路——后写覆盖不可复原须披露
        Contribution c = contribution(new BigDecimal("0.80"), new BigDecimal("0.55"), new BigDecimal("0.45"));
        c.setRdSelfInitiation(new BigDecimal("60"));
        c.setRdSelfInnovation(new BigDecimal("60"));
        c.setRdSelfLaunch(new BigDecimal("60"));
        c.setRdSelfMarketResult(new BigDecimal("60"));
        c.setRdSelfLeadership(new BigDecimal("60"));
        stubContribution(c);
        stubPool(pool(new BigDecimal("15"), new BigDecimal("1.0"), "DISTRIBUTED"));
        stubAllocations(List.of(
            allocation(1L, Contribution.ROLE_MARKET, new BigDecimal("8.25"), new BigDecimal("0.44"), new BigDecimal("1.0")),
            allocation(2L, Contribution.ROLE_RD, new BigDecimal("6.75"), new BigDecimal("0.36"), new BigDecimal("1.0"))));

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i ->
                Lc04ContributionReconcileService.ITEM_TIER_COEFFICIENT.equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_MATCH);
                assertThat(i.note()).contains("0.6").contains("后写覆盖").contains("待人工裁决");
            });
        assertThat(report.tierDivergenceNote()).contains("0.6").contains("待人工裁决");
    }

    @Test
    void tierDiffWhenNeitherPathMatches() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        // stored 0.9 不等于任一路复算（market 0.8 / rd 0.6）
        Contribution c = contribution(new BigDecimal("0.90"), new BigDecimal("0.55"), new BigDecimal("0.45"));
        c.setRdSelfInitiation(new BigDecimal("60"));
        c.setRdSelfInnovation(new BigDecimal("60"));
        c.setRdSelfLaunch(new BigDecimal("60"));
        c.setRdSelfMarketResult(new BigDecimal("60"));
        c.setRdSelfLeadership(new BigDecimal("60"));
        stubContribution(c);
        stubPool(pool(new BigDecimal("15"), new BigDecimal("1.0"), "DRAFT"));
        stubAllocations(List.of());

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i ->
                Lc04ContributionReconcileService.ITEM_TIER_COEFFICIENT.equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_DIFF);
                assertThat(i.recomputed()).contains("0.8").contains("0.6");
            });
    }

    /* ---------- ② 分配比例约束（§三.2.4） ---------- */

    @Test
    void shareConstraintDiffWhenSumNotOne() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        // 0.55 + 0.55 = 1.1：非生产者路径载荷（saveSelf 默认 0.55/0.45，越界写入会被校验器拒），
        // 仅可能来自绕过校验器的直写/历史脏数据——本对账项正是抓它（不变量守卫）。
        // §三.2.4 同源校验器自身即校验 sum=100%（总和违例抛 ServiceException → DIFF）。
        stubContribution(contribution(new BigDecimal("0.80"), new BigDecimal("0.55"), new BigDecimal("0.55")));
        stubPool(pool(new BigDecimal("15"), new BigDecimal("1.0"), "DRAFT"));
        stubAllocations(List.of());

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i ->
                Lc04ContributionReconcileService.ITEM_SHARE_CONSTRAINT.equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_DIFF);
                assertThat(i.note()).contains("总和").contains("100%");
            });
    }

    @Test
    void shareConstraintDiffWhenMarketOutOfRange() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        // market 0.70 越 §三.2.4 上界 0.65：calculateDistribution 同源校验器抛 → DIFF（非生产者路径载荷，同上不变量守卫）
        stubContribution(contribution(new BigDecimal("0.80"), new BigDecimal("0.70"), new BigDecimal("0.30")));
        stubPool(pool(new BigDecimal("15"), new BigDecimal("1.0"), "DRAFT"));
        stubAllocations(List.of());

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i ->
                Lc04ContributionReconcileService.ITEM_SHARE_CONSTRAINT.equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_DIFF);
                assertThat(i.note()).contains("越界");
            });
    }

    /* ---------- ③④⑤ 分配台账行级（AC-INC-35 生产者口径） ---------- */

    @Test
    void allocatedAmountDiffWhenFormulaDiverges() {
        stubAllMatch();
        // 破坏 MARKET 行 allocated（应 8.25）→ DIFF，其余项不受影响
        stubAllocations(List.of(
            allocation(1L, Contribution.ROLE_MARKET, new BigDecimal("999"), new BigDecimal("0.44"), new BigDecimal("1.0")),
            allocation(2L, Contribution.ROLE_RD, new BigDecimal("6.75"), new BigDecimal("0.36"), new BigDecimal("1.0"))));

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i ->
                Lc04ContributionReconcileService.ITEM_ALLOCATED_AMOUNT.equals(i.code())
                    && i.label().contains("MARKET_PM"))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_DIFF);
                assertThat(i.recomputed()).isEqualTo("8.25");
            });
    }

    @Test
    void contributionRateDiffWhenNotTierTimesShare() {
        stubAllMatch();
        stubAllocations(List.of(
            allocation(1L, Contribution.ROLE_MARKET, new BigDecimal("8.25"), new BigDecimal("0.5"), new BigDecimal("1.0")),
            allocation(2L, Contribution.ROLE_RD, new BigDecimal("6.75"), new BigDecimal("0.36"), new BigDecimal("1.0"))));

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i ->
                Lc04ContributionReconcileService.ITEM_CONTRIBUTION_RATE.equals(i.code())
                    && i.label().contains("MARKET_PM"))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_DIFF);
                assertThat(i.recomputed()).isEqualTo("0.44");
            });
    }

    @Test
    void performanceCoefficientDriftWhenPoolCoefficientChanged() {
        stubAllMatch();
        // 行 perf=1.0 写入时旧系数，池系数已变 1.2 → DRIFT（人工确认后重算）
        stubPool(pool(new BigDecimal("15"), new BigDecimal("1.2"), "DISTRIBUTED"));
        stubAllocations(List.of(
            allocation(1L, Contribution.ROLE_MARKET, new BigDecimal("8.25"), new BigDecimal("0.44"), new BigDecimal("1.0")),
            allocation(2L, Contribution.ROLE_RD, new BigDecimal("6.75"), new BigDecimal("0.36"), new BigDecimal("1.0"))));

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i ->
                Lc04ContributionReconcileService.ITEM_PERFORMANCE_COEFFICIENT.equals(i.code()))
            .hasSize(2)
            .allSatisfy(i -> assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_DRIFT));
    }

    /* ---------- PENDING（W14-02 不按 0 伪判） ---------- */

    @Test
    void pendingWhenNoContribution() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        stubContribution(null);

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.noData()).isTrue();
        assertThat(report.items()).hasSize(5).allSatisfy(i ->
            assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_PENDING));
    }

    @Test
    void pendingWhenNoPool() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        stubContribution(contribution(new BigDecimal("0.80"), new BigDecimal("0.55"), new BigDecimal("0.45")));
        stubPool(null);

        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i ->
                List.of(Lc04ContributionReconcileService.ITEM_ALLOCATED_AMOUNT,
                    Lc04ContributionReconcileService.ITEM_CONTRIBUTION_RATE,
                    Lc04ContributionReconcileService.ITEM_PERFORMANCE_COEFFICIENT).contains(i.code()))
            .hasSize(3)
            .allSatisfy(i -> {
                assertThat(i.verdict()).isEqualTo(Lc04ContributionReconcileService.VERDICT_PENDING);
                assertThat(i.note()).contains("尚无奖金池");
            });
    }

    @Test
    void projectNotFoundThrows() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.reconcile(PROJECT_ID, CLOCK))
            .isInstanceOf(IpdBusinessException.class);
    }
}
