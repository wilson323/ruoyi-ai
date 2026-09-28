package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.BonusAllocation;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.BonusAllocationMapper;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R232-LC04 双 PM 贡献度评定对账（智能体节点执行者样板 #3，镜像 {@link Lc03SettlementReconcileService}）。
 *
 * <p>确定性交叉复核（只读），对账项全部可被同岗真人逐项复算，公式一律同源既有生产者/校验器
 * （防第二套口径漂移，GatePrep M2 教训）：
 * <ol>
 *   <li>tierCoefficient：存储值 vs 双 PM 自评五维两路 {@link ContributionService#computeTierCoefficient} 复算（DIFF/MATCH）；</li>
 *   <li>SHARE_CONSTRAINT：marketShare/rdShare vs ZK-IPD §三.2.4 区间与合计约束
 *       （{@link BonusPoolService#calculateDistribution} 同源校验器）；</li>
 *   <li>ALLOCATED_AMOUNT：bonus_allocations 行级 stored vs {@code finalPool × 本方占比}
 *       （{@code BonusPoolService.writeBonusAllocations} 生产者口径，AC-INC-35）；</li>
 *   <li>CONTRIBUTION_RATE：行级 stored vs {@code tierCoefficient × 本方占比}（同上生产者口径）；</li>
 *   <li>PERFORMANCE_COEFFICIENT：行级 stored vs {@code bonus_pools.coefficient}
 *       （生产者沿用项目差异化系数，漂移标 DRIFT）。</li>
 * </ol>
 *
 * <p>铁律（与 {@link KpiSharedReconcileService} / Lc03 同严）：缺值如实标 {@code PENDING_DATA}
 * （W14-02「缺证/无效样本转待补，不得按 0 直接扣分」），不伪造数据、不改写任何业务表；
 * 差异处置权留真人组长（台账只报事实，不做裁决，G5 贡献度确认仍是真人路径）。
 *
 * <p><b>tier 后写覆盖诚实处理</b>：{@code contributions.tierCoefficient} 是单列，双 PM 各自 saveSelf
 * 均覆盖写入（{@code ContributionService.saveSelf}），哪一方后写不可从存储复原——故该项双路复算
 * （market 自评路 / rd 自评路），命中任一即 MATCH 并注明命中路；两路复算不一致而 stored 只代表后写方时
 * 另发「分歧披露……待人工裁决」（MATCH 仅证公式自洽，不证评定口径正确）；两路均不符标 DIFF。
 * 不伪造确定性、不假绿（LC03 branch2 分歧披露同精神）。
 */
@Service
@RequiredArgsConstructor
public class Lc04ContributionReconcileService {

    /** 对账判定：存储值与确定性复算/源表一致 */
    public static final String VERDICT_MATCH = "MATCH";
    /** 对账判定：存储值与公式复算不符（真实差异，需人查） */
    public static final String VERDICT_DIFF = "DIFF";
    /** 对账判定：源表/配置表现值与写入时不同（漂移，建议人工确认后重算） */
    public static final String VERDICT_DRIFT = "DRIFT";
    /** 对账判定：缺值待补（不按 0 伪判，不污染下游） */
    public static final String VERDICT_PENDING = "PENDING_DATA";

    /** 对账项 code：五维加权 tier 修正系数 */
    public static final String ITEM_TIER_COEFFICIENT = "TIER_COEFFICIENT";
    /** 对账项 code：双 PM 分配比例区间与合计约束（§三.2.4） */
    public static final String ITEM_SHARE_CONSTRAINT = "SHARE_CONSTRAINT";
    /** 对账项 code：分配额（bonus_allocations 行级） */
    public static final String ITEM_ALLOCATED_AMOUNT = "ALLOCATED_AMOUNT";
    /** 对账项 code：贡献度率（bonus_allocations 行级衍生字段） */
    public static final String ITEM_CONTRIBUTION_RATE = "CONTRIBUTION_RATE";
    /** 对账项 code：分配行绩效系数（沿用奖金池项目差异化系数） */
    public static final String ITEM_PERFORMANCE_COEFFICIENT = "PERFORMANCE_COEFFICIENT";

    private final ContributionMapper contributionMapper;
    private final BonusPoolMapper bonusPoolMapper;
    private final BonusAllocationMapper bonusAllocationMapper;
    private final ProjectMapper projectMapper;
    /** §三.2.4 分配比例校验器唯一复用入口（calculateDistribution 同源） */
    private final BonusPoolService bonusPoolService;
    /** 对账结果接收人解析复用（leadersOf 同口径，禁止第三份拷贝） */
    private final KpiSharedReconcileService kpiReconcileService;

    /**
     * 对账主入口：项目 + 最新贡献度评定 + 既有奖金池（任意状态）+ 分配台账行。
     * 无评定 → 全项待补台账（noData，仍是有效产出）；只读；异常语义交给引擎
     * （项目不存在抛 IpdBusinessException → FAILED 退避，不伪造台账）。
     */
    public ReconcileReport reconcile(Long projectId, Clock clock) {
        Project project = projectId == null ? null : projectMapper.selectById(projectId);
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "贡献度对账失败：项目不存在 " + projectId);
        }
        Contribution contribution = latestContribution(projectId);
        if (contribution == null) {
            return ReconcileReport.awaitingEvaluation(projectId,
                YearMonth.from(LocalDate.now(clock)).toString());
        }
        String period = periodOf(contribution, clock);
        BonusPool pool = bonusPoolMapper.selectByProjectIdAnyStatus(projectId);

        List<Item> items = new ArrayList<>();
        TierCheck tierCheck = reconcileTier(contribution);
        items.add(tierCheck.item());
        items.add(reconcileShareConstraint(contribution));
        List<BonusAllocation> allocations = pool == null ? List.of() : allocationsOf(pool.getId());
        items.addAll(reconcileAllocationRows(pool, contribution, allocations));

        return new ReconcileReport(projectId, period, false, items, tierCheck.divergenceNote());
    }

    /** 最新未删贡献度评定（与 {@link ContributionService#getByProject} 同选取口径）。 */
    private Contribution latestContribution(Long projectId) {
        return contributionMapper.selectOne(new LambdaQueryWrapper<Contribution>()
            .eq(Contribution::getProjectId, projectId)
            .eq(Contribution::getDelFlag, "0")
            .orderByDesc(Contribution::getId)
            .last("limit 1"));
    }

    private List<BonusAllocation> allocationsOf(Long bonusPoolId) {
        List<BonusAllocation> rows = bonusAllocationMapper.selectList(new LambdaQueryWrapper<BonusAllocation>()
            .eq(BonusAllocation::getBonusPoolId, bonusPoolId));
        return rows == null ? List.of() : rows;
    }

    /** 对账结果接收人（产品组长）：与 W14 / LC03 同口径复用，不自建第三份拷贝。 */
    public Set<Long> leadersOf(Long projectId) {
        return kpiReconcileService.leadersOf(projectId);
    }

    /**
     * ① tierCoefficient 双路复算 + 后写覆盖分歧披露（见类注释）。
     * 五维自评缺一路不影响另一路对账；两路全缺才 PENDING。
     */
    private TierCheck reconcileTier(Contribution c) {
        if (c.getTierCoefficient() == null) {
            return new TierCheck(new Item(ITEM_TIER_COEFFICIENT, "五维加权 tier 修正系数", null, null,
                VERDICT_PENDING, "存储 tierCoefficient 缺值，转待补"), null);
        }
        BigDecimal marketPath = marketSelfTier(c);
        BigDecimal rdPath = rdSelfTier(c);
        if (marketPath == null && rdPath == null) {
            return new TierCheck(new Item(ITEM_TIER_COEFFICIENT, "五维加权 tier 修正系数",
                str(c.getTierCoefficient()), null,
                VERDICT_PENDING, "双 PM 五维自评均缺值，tier 不可复算，转待补（不按 0 伪判）"), null);
        }
        boolean hitMarket = marketPath != null && eq(c.getTierCoefficient(), marketPath);
        boolean hitRd = rdPath != null && eq(c.getTierCoefficient(), rdPath);
        if (hitMarket || hitRd) {
            String hit = hitMarket && hitRd ? "market/rd 双路" : (hitMarket ? "market 自评路" : "rd 自评路");
            String diverge = "";
            if (marketPath != null && rdPath != null && !eq(marketPath, rdPath)) {
                diverge = "；分歧披露：双 PM 自评复算不一致（market 路=" + str(marketPath)
                    + "，rd 路=" + str(rdPath) + "），tierCoefficient 为后写覆盖单列、"
                    + "哪方后写不可从存储复原，存储是否为评定口径正确值待人工裁决";
            }
            return new TierCheck(new Item(ITEM_TIER_COEFFICIENT, "五维加权 tier 修正系数",
                str(c.getTierCoefficient()), str(hitMarket ? marketPath : rdPath),
                VERDICT_MATCH, "§三.2.5 五维加权公式复算一致（命中 " + hit + "）" + diverge),
                diverge.isEmpty() ? null : diverge);
        }
        return new TierCheck(new Item(ITEM_TIER_COEFFICIENT, "五维加权 tier 修正系数",
            str(c.getTierCoefficient()),
            "market 路=" + str(marketPath) + " / rd 路=" + str(rdPath),
            VERDICT_DIFF,
            "与两路自评复算均不符（§三.2.5 五维加权公式），疑落库被绕过或评定后自评被改，转人工核查"),
            null);
    }

    /** market PM 自评五维复算；任一维度缺值返回 null（该路不可复算）。 */
    private BigDecimal marketSelfTier(Contribution c) {
        return tierOf(c.getMarketSelfInitiation(), c.getMarketSelfInnovation(), c.getMarketSelfLaunch(),
            c.getMarketSelfMarketResult(), c.getMarketSelfLeadership());
    }

    /** rd PM 自评五维复算；任一维度缺值返回 null（该路不可复算）。 */
    private BigDecimal rdSelfTier(Contribution c) {
        return tierOf(c.getRdSelfInitiation(), c.getRdSelfInnovation(), c.getRdSelfLaunch(),
            c.getRdSelfMarketResult(), c.getRdSelfLeadership());
    }

    private BigDecimal tierOf(BigDecimal d1, BigDecimal d2, BigDecimal d3, BigDecimal d4, BigDecimal d5) {
        if (d1 == null || d2 == null || d3 == null || d4 == null || d5 == null) {
            return null;
        }
        // 公式同源：ContributionService.computeTierCoefficient（static，不自建第二套权重）
        return ContributionService.computeTierCoefficient(d1, d2, d3, d4, d5);
    }

    /**
     * ② 分配比例区间与合计约束（ZK-IPD §三.2.4）：复用 {@code calculateDistribution}
     * 校验器（越界抛 ServiceException → DIFF 如实记）；合计 ≠1 同判 DIFF。
     */
    private Item reconcileShareConstraint(Contribution c) {
        String label = "双 PM 分配比例（§三.2.4 区间+合计）";
        if (c.getMarketShare() == null || c.getRdShare() == null) {
            return new Item(ITEM_SHARE_CONSTRAINT, label, null, null,
                VERDICT_PENDING, "marketShare/rdShare 缺值，转待补");
        }
        String stored = "market=" + str(c.getMarketShare()) + "，rd=" + str(c.getRdShare());
        try {
            Map<String, BigDecimal> checked =
                bonusPoolService.calculateDistribution(c.getMarketShare(), c.getRdShare());
            BigDecimal sum = checked.get("sum");
            if (sum == null || sum.compareTo(BigDecimal.ONE) != 0) {
                return new Item(ITEM_SHARE_CONSTRAINT, label, stored, "sum=1.0",
                    VERDICT_DIFF, "合计 ≠ 100%（sum=" + str(sum) + "，§三.2.4 双 PM 比例须合计 1），转人工裁决");
            }
            return new Item(ITEM_SHARE_CONSTRAINT, label, stored, "区间合规且合计 1.0",
                VERDICT_MATCH, "§三.2.4 区间校验器（calculateDistribution 同源）通过");
        } catch (RuntimeException ex) {
            return new Item(ITEM_SHARE_CONSTRAINT, label, stored, "§三.2.4 区间校验",
                VERDICT_DIFF, "分配比例越界：" + ex.getMessage());
        }
    }

    /**
     * ③④⑤ 分配台账行级对账（生产者口径 {@code writeBonusAllocations}，AC-INC-35）：
     * allocatedAmount = finalPool × 本方占比；contributionRate = tierCoefficient × 本方占比；
     * performanceCoefficient = bonus_pools.coefficient。缺池/缺行各自 PENDING，不按 0 伪判。
     */
    private List<Item> reconcileAllocationRows(BonusPool pool, Contribution c, List<BonusAllocation> rows) {
        List<Item> items = new ArrayList<>();
        String poolNote = "（池=" + (pool == null ? "无" : "bonusPoolId " + pool.getId()) + "）";
        if (pool == null) {
            items.add(pendingAlloc(ITEM_ALLOCATED_AMOUNT, "分配额（行级）", "尚无奖金池计算记录，分配额不可复算，转待补 " + poolNote));
            items.add(pendingAlloc(ITEM_CONTRIBUTION_RATE, "贡献度率（行级）", "尚无奖金池计算记录，转待补 " + poolNote));
            items.add(pendingAlloc(ITEM_PERFORMANCE_COEFFICIENT, "分配行绩效系数（行级）", "尚无奖金池计算记录，转待补 " + poolNote));
            return items;
        }
        if (rows.isEmpty()) {
            items.add(pendingAlloc(ITEM_ALLOCATED_AMOUNT, "分配额（行级）", "无 bonus_allocations 台账行（distribute 未执行或已清理），转待补 " + poolNote));
            items.add(pendingAlloc(ITEM_CONTRIBUTION_RATE, "贡献度率（行级）", "无 bonus_allocations 台账行，转待补 " + poolNote));
            items.add(pendingAlloc(ITEM_PERFORMANCE_COEFFICIENT, "分配行绩效系数（行级）", "无 bonus_allocations 台账行，转待补 " + poolNote));
            return items;
        }
        for (BonusAllocation row : rows) {
            String role = row.getRoleInProject() == null ? "?" : row.getRoleInProject();
            BigDecimal share = shareOfRole(c, role);
            // ③ allocatedAmount = finalPool × 本方占比（生产者口径）
            if (row.getAllocatedAmount() == null) {
                items.add(pendingAlloc(ITEM_ALLOCATED_AMOUNT, "分配额 " + role, "行 " + row.getId() + " allocatedAmount 缺值，转待补"));
            } else if (share == null || pool.getFinalPool() == null) {
                items.add(new Item(ITEM_ALLOCATED_AMOUNT, "分配额 " + role, str(row.getAllocatedAmount()), null,
                    VERDICT_PENDING, "行 " + row.getId() + " 复算缺入参（本方占比=" + str(share)
                    + "，finalPool=" + str(pool.getFinalPool()) + "），转待补（不按 0 伪判）"));
            } else {
                BigDecimal recomputed = pool.getFinalPool().multiply(share);
                boolean match = eq(row.getAllocatedAmount(), recomputed);
                items.add(new Item(ITEM_ALLOCATED_AMOUNT, "分配额 " + role, str(row.getAllocatedAmount()),
                    str(recomputed), match ? VERDICT_MATCH : VERDICT_DIFF,
                    "行 " + row.getId() + " 按生产者口径 finalPool×本方占比 复算" + (match ? "一致" : "不符，转人工核查")));
            }
            // ④ contributionRate = tierCoefficient × 本方占比（生产者衍生字段口径）
            if (row.getContributionRate() == null) {
                items.add(pendingAlloc(ITEM_CONTRIBUTION_RATE, "贡献度率 " + role, "行 " + row.getId() + " contributionRate 缺值，转待补"));
            } else if (share == null || c.getTierCoefficient() == null) {
                items.add(new Item(ITEM_CONTRIBUTION_RATE, "贡献度率 " + role, str(row.getContributionRate()), null,
                    VERDICT_PENDING, "行 " + row.getId() + " 复算缺入参（tier=" + str(c.getTierCoefficient())
                    + "，本方占比=" + str(share) + "），转待补"));
            } else {
                BigDecimal recomputed = c.getTierCoefficient().multiply(share);
                boolean match = eq(row.getContributionRate(), recomputed);
                items.add(new Item(ITEM_CONTRIBUTION_RATE, "贡献度率 " + role, str(row.getContributionRate()),
                    str(recomputed), match ? VERDICT_MATCH : VERDICT_DIFF,
                    "行 " + row.getId() + " 按生产者口径 tierCoefficient×本方占比 复算" + (match ? "一致" : "不符，转人工核查")));
            }
            // ⑤ performanceCoefficient = bonus_pools.coefficient（生产者沿用项目差异化系数）
            if (row.getPerformanceCoefficient() == null) {
                items.add(pendingAlloc(ITEM_PERFORMANCE_COEFFICIENT, "分配行绩效系数 " + role, "行 " + row.getId() + " performanceCoefficient 缺值，转待补"));
            } else if (pool.getCoefficient() == null) {
                items.add(new Item(ITEM_PERFORMANCE_COEFFICIENT, "分配行绩效系数 " + role,
                    str(row.getPerformanceCoefficient()), null,
                    VERDICT_PENDING, "行 " + row.getId() + " 池 coefficient 缺值，源表侧待补"));
            } else {
                boolean match = eq(row.getPerformanceCoefficient(), pool.getCoefficient());
                items.add(new Item(ITEM_PERFORMANCE_COEFFICIENT, "分配行绩效系数 " + role,
                    str(row.getPerformanceCoefficient()), str(pool.getCoefficient()),
                    match ? VERDICT_MATCH : VERDICT_DRIFT,
                    "行 " + row.getId() + " 沿用池项目差异化系数" + (match ? "一致" : "漂移（池系数已变更，建议人工确认后重算）")));
            }
        }
        return items;
    }

    /** 本方占比：MARKET_PM→marketShare，RD_PM→rdShare（生产者 writeBonusAllocations 同选取）。 */
    private BigDecimal shareOfRole(Contribution c, String role) {
        return Contribution.ROLE_MARKET.equals(role) ? c.getMarketShare()
            : Contribution.ROLE_RD.equals(role) ? c.getRdShare() : null;
    }

    private Item pendingAlloc(String code, String label, String note) {
        return new Item(code, label, null, null, VERDICT_PENDING, note);
    }

    private static String periodOf(Contribution c, Clock clock) {
        Instant at = c.getUpdateTime() != null ? c.getUpdateTime().toInstant()
            : (c.getSubmittedAt() != null ? c.getSubmittedAt().toInstant() : clock.instant());
        return YearMonth.from(LocalDate.ofInstant(at, ZoneId.of("Asia/Shanghai"))).toString();
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static String str(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    /* ---------- 报告契约（executor 渲染 markdown 的唯一数据源） ---------- */

    public record ReconcileReport(Long projectId, String period, boolean noData, List<Item> items,
                                  String tierDivergenceNote) {

        static ReconcileReport awaitingEvaluation(Long projectId, String period) {
            List<Item> pending = new ArrayList<>();
            for (String code : List.of(ITEM_TIER_COEFFICIENT, ITEM_SHARE_CONSTRAINT,
                ITEM_ALLOCATED_AMOUNT, ITEM_CONTRIBUTION_RATE, ITEM_PERFORMANCE_COEFFICIENT)) {
                pending.add(new Item(code, null, null, null,
                    VERDICT_PENDING, "尚无贡献度评定行，台账全项待补（不按 0 伪判）"));
            }
            return new ReconcileReport(projectId, period, true, pending, null);
        }

        /** 台账统计：各判定计数，供 summary 一句话。 */
        public Map<String, Long> verdictCounts() {
            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            items.forEach(i -> counts.merge(i.verdict(), 1L, Long::sum));
            return counts;
        }
    }

    public record Item(String code, String label, String stored, String recomputed,
                       String verdict, String note) {
    }

    /** ① tier 复算结果：台账行 + 报告级分歧披露（executor 台账特写段用）。 */
    private record TierCheck(Item item, String divergenceNote) {
    }
}
