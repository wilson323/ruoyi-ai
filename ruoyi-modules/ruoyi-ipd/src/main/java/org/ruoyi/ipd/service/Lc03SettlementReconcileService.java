package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R232-LC03 上市后 6 个月终算对账（奖金池 finalPool + 回款达成率，智能体节点执行者样板 #2）。
 *
 * <p>确定性交叉复核（只读），六类对账项全部可被同岗真人逐项复算（ZK-IPD §三.2.1/§三.2.5 + AC-INC-16b/16d/17h）：
 * <ol>
 *   <li>窗口净回款：存储回款基数（{@code bonus_pools.target_sales} 字段孤岛，ZK 路径写入 actualReceipts）
 *       vs {@link ReceiptLedgerService#windowNet} 窗口内 RECEIPT 净额（DIFF）；</li>
 *   <li>奖金池比例：存储 poolRate vs 当前配置 {@code bonus.poolRate}（DRIFT，配置已变更提示人工确认）；</li>
 *   <li>项目 S/A/B 系数：存储 coefficient vs {@code projects.levelCoefficient} 源表现值（DRIFT）；</li>
 *   <li>回款达成率：存储 achievementRate vs {@link ReceiptLedgerService#calculateAchievementRate} 同公式复算（DIFF）；</li>
 *   <li>阶梯系数：存储 tierCoefficient vs {@link BonusPoolService#tierCoefficientOf} 六档复算（DIFF）；</li>
 *   <li>最终奖金池：存储 finalPool vs {@link BonusPoolService#calculateBonusPoolByZkFormulaWithModifiers}
 *       （§三.2.5 完整公式）以存储因子复算（DIFF）。</li>
 * </ol>
 *
 * <p>铁律（与 {@link KpiSharedReconcileService} 同严）：缺值如实标 {@code PENDING_DATA}（W14-02「缺证/无效样本转待补，
 * 不得按 0 直接扣分」语义），不伪造数据、不改写任何业务表；差异处置权留真人组长（台账只报事实，不做裁决）。
 * 公式一律复用 {@link BonusPoolService}/{@link ReceiptLedgerService}（同源复用，防第二套口径漂移，GatePrep M2 教训）。
 *
 * <p><b>personalCoefficient 诚实处理</b>：个人绩效系数不落 {@code bonus_pools} 表（仅入 distributions JSON /
 * 由 {@link BonusPoolService#resolvePersonalCoefficient} 从 kpi_records 推导），stored finalPool 含它而复算无法
 * 单路还原——故 finalPool 项双路复算（中性 1.0 / 推导同源），命中任一即 MATCH 并注明命中路；均不符标 DIFF 并
 * 如实披露「差异可能源于计算时个人绩效系数」，不伪造确定性、不假绿。
 */
@Service
@RequiredArgsConstructor
public class Lc03SettlementReconcileService {

    /** 对账判定：存储值与确定性复算/源表一致 */
    public static final String VERDICT_MATCH = "MATCH";
    /** 对账判定：存储值与公式复算不符（真实差异，需人查） */
    public static final String VERDICT_DIFF = "DIFF";
    /** 对账判定：配置/源表现值与计算时不同（漂移，建议人工确认后重算） */
    public static final String VERDICT_DRIFT = "DRIFT";
    /** 对账判定：缺值待补（不按 0 伪判，不污染下游） */
    public static final String VERDICT_PENDING = "PENDING_DATA";

    /** 对账项 code：6 自然月窗口净回款 */
    public static final String ITEM_RECEIPTS_NET = "RECEIPTS_NET";
    /** 对账项 code：奖金池比例 */
    public static final String ITEM_POOL_RATE = "POOL_RATE";
    /** 对账项 code：项目 S/A/B 差异化系数 */
    public static final String ITEM_LEVEL_COEFFICIENT = "LEVEL_COEFFICIENT";
    /** 对账项 code：回款达成率 */
    public static final String ITEM_ACHIEVEMENT_RATE = "ACHIEVEMENT_RATE";
    /** 对账项 code：达成率阶梯系数 */
    public static final String ITEM_TIER_COEFFICIENT = "TIER_COEFFICIENT";
    /** 对账项 code：最终奖金池 */
    public static final String ITEM_FINAL_POOL = "FINAL_POOL";

    private final BonusPoolMapper bonusPoolMapper;
    private final ProjectMapper projectMapper;
    /** 公式与实时配置的唯一复用入口（readPoolRateZk/tierCoefficientOf/calculateBonusPoolByZkFormulaWithModifiers/resolvePersonalCoefficient 同源） */
    private final BonusPoolService bonusPoolService;
    /** 窗口净额与达成率的唯一复用入口（windowNet/calculateAchievementRate 同源） */
    private final ReceiptLedgerService receiptLedgerService;
    /** 对账结果接收人解析复用（leadersOf 同口径，禁止第三份拷贝） */
    private final KpiSharedReconcileService kpiReconcileService;

    /**
     * 对账主入口：项目 + 既有奖金池（任意状态，每项目仅一池）。无池 → 全项待补台账（noData，仍是有效产出）。
     * 只读；异常语义交给引擎（项目不存在抛 IpdBusinessException → FAILED 退避，不伪造台账）。
     */
    public ReconcileReport reconcile(Long projectId, Clock clock) {
        Project project = projectId == null ? null : projectMapper.selectById(projectId);
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "终算对账失败：项目不存在 " + projectId);
        }
        BonusPool pool = bonusPoolMapper.selectByProjectIdAnyStatus(projectId);
        if (pool == null) {
            return ReconcileReport.awaitingSettlement(projectId,
                YearMonth.from(LocalDate.now(clock)).toString(), bonusPoolService.readWindowMonths());
        }
        String period = periodOf(pool, clock);
        ReceiptLedgerService.WindowNet window = receiptLedgerService.windowNet(projectId);

        List<Item> items = new ArrayList<>();
        items.add(reconcileReceipts(pool, window));
        items.add(reconcilePoolRate(pool));
        items.add(reconcileLevelCoefficient(pool, project));
        BigDecimal recomputedAchievement = recomputedAchievement(projectId, project, window);
        items.add(reconcileAchievement(pool, project, window, recomputedAchievement));
        items.add(reconcileTier(pool, recomputedAchievement));
        FinalCheck finalCheck = reconcileFinalPool(pool, projectId, period);
        items.add(finalCheck.item());

        return new ReconcileReport(projectId, period, false, items,
            bonusPoolService.readWindowMonths(), str(pool.getFinalPool()),
            finalCheck.recomputed(), finalCheck.verdict(), finalCheck.personalNote());
    }

    /**
     * 对账结果接收人（产品组长）：与 W14 共担 KPI 对账同口径——成员→person.groupId→productGroup.leaderPersonId
     * + 主产品组 leader。复用 {@link KpiSharedReconcileService#leadersOf}（public 同源），不自建第三份拷贝。
     */
    public Set<Long> leadersOf(Long projectId) {
        return kpiReconcileService.leadersOf(projectId);
    }

    /** ① 窗口净回款：存储回款基数 vs 窗口内 RECEIPT 净额。 */
    private Item reconcileReceipts(BonusPool pool, ReceiptLedgerService.WindowNet window) {
        String note = "复算=6 自然月窗口内 RECEIPT 净额 SUM(回款-退款)（AC-INC-16b/16d）；"
            + "存储基数在 target_sales 字段（ZK §三.2.1 路径写入 actualReceipts，字段孤岛见 BonusPoolService.buildPoolFromProject）";
        if (window.rowCount() == 0) {
            return new Item(ITEM_RECEIPTS_NET, "窗口净回款", str(pool.getTargetSales()), null,
                VERDICT_PENDING, "窗口内无回款台账行，不按 0 伪判（W14-02），转待补；" + note);
        }
        if (pool.getTargetSales() == null) {
            return new Item(ITEM_RECEIPTS_NET, "窗口净回款", null, str(window.netAmount()),
                VERDICT_PENDING, "存储回款基数缺值，转待补；" + note);
        }
        boolean match = eq(pool.getTargetSales(), window.netAmount());
        return new Item(ITEM_RECEIPTS_NET, "窗口净回款", str(pool.getTargetSales()),
            str(window.netAmount()), match ? VERDICT_MATCH : VERDICT_DIFF,
            match ? "" : "存储基数与窗口净额不符（含窗口外/退款冲减/跨期口径可能），请人工核对回款台账；" + note);
    }

    /** ② 奖金池比例：存储 poolRate vs 当前配置（漂移=配置已变更，历史池保留原值属正常）。 */
    private Item reconcilePoolRate(BonusPool pool) {
        BigDecimal current = bonusPoolService.readPoolRateZk();
        if (pool.getPoolRate() == null) {
            return new Item(ITEM_POOL_RATE, "奖金池比例", null, str(current),
                VERDICT_PENDING, "存储 poolRate 缺值，转待补");
        }
        boolean match = eq(pool.getPoolRate(), current);
        return new Item(ITEM_POOL_RATE, "奖金池比例", str(pool.getPoolRate()), str(current),
            match ? VERDICT_MATCH : VERDICT_DRIFT,
            match ? "" : "当前配置 bonus.poolRate 与本池计算时不同（配置变更后历史池保留原值属正常），是否重算由人决定");
    }

    /** ③ 项目 S/A/B 系数：存储 coefficient vs projects.levelCoefficient 源表现值。 */
    private Item reconcileLevelCoefficient(BonusPool pool, Project project) {
        BigDecimal current = project.getLevelCoefficient();
        if (pool.getCoefficient() == null) {
            return new Item(ITEM_LEVEL_COEFFICIENT, "项目 S/A/B 系数", null, str(current),
                VERDICT_PENDING, "存储 coefficient 缺值，转待补");
        }
        if (current == null) {
            return new Item(ITEM_LEVEL_COEFFICIENT, "项目 S/A/B 系数", str(pool.getCoefficient()), null,
                VERDICT_PENDING, "projects.levelCoefficient 未配置（G1 双签缺失），源表侧待补");
        }
        boolean match = eq(pool.getCoefficient(), current);
        return new Item(ITEM_LEVEL_COEFFICIENT, "项目 S/A/B 系数", str(pool.getCoefficient()), str(current),
            match ? VERDICT_MATCH : VERDICT_DRIFT,
            match ? "" : "projects.levelCoefficient 已变更（G1 双签后改），建议人工确认后重算奖金池");
    }

    /** ④⑤ 复算输入：窗口达成率（同 ReceiptLedgerService 公式）；不可复算返回 null（调用方标待补）。 */
    private BigDecimal recomputedAchievement(Long projectId, Project project,
                                             ReceiptLedgerService.WindowNet window) {
        BigDecimal target = project.getTargetSalesAmount();
        boolean denomOk = target != null && target.signum() > 0;
        if (window.rowCount() == 0 || !denomOk) {
            return null;
        }
        return receiptLedgerService.calculateAchievementRate(projectId, target);
    }

    /** ④ 回款达成率：存储 achievementRate vs 窗口复算（分母=projects.target_sales_amount）。 */
    private Item reconcileAchievement(BonusPool pool, Project project,
                                      ReceiptLedgerService.WindowNet window, BigDecimal recomputed) {
        String note = "口径：达成率 = 窗口净回款 / projects.target_sales_amount（AC-INC-16b/16d，"
            + "ReceiptLedgerService.calculateAchievementRate 同源）";
        if (window.rowCount() == 0) {
            return new Item(ITEM_ACHIEVEMENT_RATE, "回款达成率", str(pool.getAchievementRate()), null,
                VERDICT_PENDING, "窗口内无回款台账行，不按 0 伪判（W14-02），转待补；" + note);
        }
        BigDecimal target = project.getTargetSalesAmount();
        if (target == null || target.signum() <= 0) {
            return new Item(ITEM_ACHIEVEMENT_RATE, "回款达成率", str(pool.getAchievementRate()), null,
                VERDICT_PENDING, "分母缺失：projects.target_sales_amount 未配置，达成率不可复算；" + note);
        }
        if (pool.getAchievementRate() == null) {
            return new Item(ITEM_ACHIEVEMENT_RATE, "回款达成率", null, str(recomputed),
                VERDICT_PENDING, "存储达成率缺值（无达成率路径计算的池不落此字段），转待补；" + note);
        }
        boolean match = eq(pool.getAchievementRate(), recomputed);
        return new Item(ITEM_ACHIEVEMENT_RATE, "回款达成率", str(pool.getAchievementRate()), str(recomputed),
            match ? VERDICT_MATCH : VERDICT_DIFF,
            match ? "" : "存储达成率与窗口复算不符，请人工核对目标口径与回款归集；" + note);
    }

    /** ⑤ 阶梯系数：存储 tierCoefficient vs 六档复算（AC-INC-17h）。 */
    private Item reconcileTier(BonusPool pool, BigDecimal recomputedAchievement) {
        String note = "口径：AC-INC-17h 六档阶梯（BonusPoolService.tierCoefficientOf 同源）";
        if (pool.getTierCoefficient() == null) {
            return new Item(ITEM_TIER_COEFFICIENT, "达成率阶梯系数", null, null,
                VERDICT_PENDING, "存储 tierCoefficient 缺值（无达成率路径不落此字段），转待补；" + note);
        }
        if (recomputedAchievement == null) {
            return new Item(ITEM_TIER_COEFFICIENT, "达成率阶梯系数", str(pool.getTierCoefficient()), null,
                VERDICT_PENDING, "达成率待补，阶梯系数不可复算；" + note);
        }
        BigDecimal recomputed = bonusPoolService.tierCoefficientOf(recomputedAchievement);
        boolean match = eq(pool.getTierCoefficient(), recomputed);
        return new Item(ITEM_TIER_COEFFICIENT, "达成率阶梯系数", str(pool.getTierCoefficient()), str(recomputed),
            match ? VERDICT_MATCH : VERDICT_DIFF,
            match ? "" : "存储阶梯与按复算达成率推档不符，请人工核对计算时达成率；" + note);
    }

    /**
     * ⑥ 最终奖金池（§三.2.5 完整公式，存储因子复算 + personal 双路诚实判定）：
     * finalPool = 回款基数 × poolRate × levelCoefficient × tierCoefficient × personalCoefficient。
     */
    private FinalCheck reconcileFinalPool(BonusPool pool, Long projectId, String period) {
        if (pool.getFinalPool() == null) {
            return new FinalCheck(new Item(ITEM_FINAL_POOL, "最终奖金池", null, null,
                VERDICT_PENDING, "存储 finalPool 缺值，转待补"), null, VERDICT_PENDING,
                "存储 finalPool 缺值");
        }
        BigDecimal base = pool.getTargetSales();
        BigDecimal level = pool.getCoefficient();
        if (base == null || level == null || base.signum() < 0) {
            return new FinalCheck(new Item(ITEM_FINAL_POOL, "最终奖金池", str(pool.getFinalPool()), null,
                VERDICT_PENDING, "公式输入缺值（回款基数/项目系数），不按 0 伪判，转待补"),
                null, VERDICT_PENDING, "公式输入缺值");
        }
        // 存储因子（tier=计算时入参）代入 §三.2.5；personal 双路：中性 1.0 / kpi_records 推导同源
        BigDecimal neutral = bonusPoolService.calculateBonusPoolByZkFormulaWithModifiers(
            base, level, pool.getTierCoefficient(), null);
        BigDecimal derivedPersonal = bonusPoolService.resolvePersonalCoefficient(projectId, period);
        BigDecimal derived = bonusPoolService.calculateBonusPoolByZkFormulaWithModifiers(
            base, level, pool.getTierCoefficient(), derivedPersonal);
        String label = "最终奖金池";
        if (eq(pool.getFinalPool(), neutral) && eq(neutral, derived)) {
            return new FinalCheck(new Item(ITEM_FINAL_POOL, label, str(pool.getFinalPool()), str(neutral),
                VERDICT_MATCH, "§三.2.5 完整公式以存储因子复算一致"),
                str(neutral), VERDICT_MATCH, "两路复算同值（个人绩效系数=中性 1.0）");
        }
        if (eq(pool.getFinalPool(), neutral)) {
            return new FinalCheck(new Item(ITEM_FINAL_POOL, label, str(pool.getFinalPool()), str(neutral),
                VERDICT_MATCH, "§三.2.5 完整公式以存储因子复算一致"),
                str(neutral), VERDICT_MATCH, "个人绩效系数按中性 1.0 复算一致");
        }
        if (eq(pool.getFinalPool(), derived) && !eq(derived, neutral)) {
            return new FinalCheck(new Item(ITEM_FINAL_POOL, label, str(pool.getFinalPool()), str(derived),
                VERDICT_MATCH, "§三.2.5 完整公式以存储因子复算一致"),
                str(derived), VERDICT_MATCH,
                "个人绩效系数按 kpi_records 推导同源复算一致（推导系数=" + str(derivedPersonal) + "）");
        }
        String both = "中性=" + str(neutral) + "；推导=" + str(derived);
        return new FinalCheck(new Item(ITEM_FINAL_POOL, label, str(pool.getFinalPool()), both,
                VERDICT_DIFF,
                "§三.2.5 双路复算均不符存储值；个人绩效系数不落 bonus_pools（仅入 distributions JSON），"
                    + "差异可能源于计算时个人绩效系数或输入改动，请人工核对 distributions 与计算参数"),
            both, VERDICT_DIFF,
            "个人绩效系数不落 bonus_pools（仅入 distributions JSON）；两路复算（中性=" + str(neutral)
                + " / 推导=" + str(derivedPersonal) + "）均不符，差异待人工核对，不假绿");
    }

    /** 终算期口径：奖金池 calculatedAt 所在月（YYYY-MM）；缺 calculatedAt 回退当前月。 */
    private static String periodOf(BonusPool pool, Clock clock) {
        if (pool.getCalculatedAt() == null) {
            return YearMonth.from(LocalDate.now(clock)).toString();
        }
        return YearMonth.from(pool.getCalculatedAt().toInstant().atZone(clock.getZone())).toString();
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    /** 台账展示口径：同形可比（stripTrailingZeros），null 显示占位由渲染层处理。 */
    private static String str(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    /* ---------- 报告契约（executor 渲染 markdown 的唯一数据源） ---------- */

    public record ReconcileReport(
        Long projectId, String period, boolean noData, List<Item> items, int windowMonths,
        String storedFinalPool, String recomputedFinalPool, String finalPoolVerdict,
        String personalCoefficientNote) {

        static ReconcileReport awaitingSettlement(Long projectId, String period, int windowMonths) {
            List<Item> pending = new ArrayList<>();
            for (String code : List.of(ITEM_RECEIPTS_NET, ITEM_POOL_RATE, ITEM_LEVEL_COEFFICIENT,
                ITEM_ACHIEVEMENT_RATE, ITEM_TIER_COEFFICIENT, ITEM_FINAL_POOL)) {
                pending.add(new Item(code, null, null, null,
                    VERDICT_PENDING, "尚无奖金池计算记录，台账全项待补（不按 0 伪判）"));
            }
            return new ReconcileReport(projectId, period, true, pending, windowMonths,
                null, null, VERDICT_PENDING, "无奖金池行");
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

    /** ⑥ 最终奖金池复算结果：台账行 + 报告级字段（recomputed/verdict/personalNote）。 */
    private record FinalCheck(Item item, String recomputed, String verdict, String personalNote) {
    }
}
