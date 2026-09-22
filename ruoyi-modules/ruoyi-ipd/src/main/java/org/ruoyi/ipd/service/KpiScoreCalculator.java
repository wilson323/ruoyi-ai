package org.ruoyi.ipd.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;


import org.ruoyi.ipd.common.IpdBusinessException;
/**
 * P3-1.1 KPI 得分纯函数计算器（不依赖 mapper / DB，便于单测）
 *
 * <p>核心算法：
 * <ul>
 *   <li>综合得分 = functional × w + shared × (1 − w)，w 默认 0.6（功能 60%）</li>
 *   <li>共担权重 (1 − w) 不得低于 0.3，低于时拒绝保存</li>
 *   <li>市场 PM 四项功能 KPI：需求准确率 / 窗口命中率 / 场景竞争力 / 竞品情报，各 15%</li>
 *   <li>研发 PM 四项功能 KPI：上市准时率 / 质量缺陷率 / 技术创新度 / 一次性实现率，各 15%</li>
 *   <li>四项 KPI 总权重 = 60%（与 w 对应），各项 15%</li>
 * </ul>
 *
 * <p>AC：AC-KPI-01/02/03/04/15；BR：BR-KPI-01/02/03/06。
 * <p>不读写数据库；服务层 KpiRecordService 调用本类完成字段校验与算法计算。
 */
public final class KpiScoreCalculator {

    /** 功能 KPI 总权重（默认 60%） */
    public static final BigDecimal DEFAULT_FUNCTIONAL_WEIGHT = new BigDecimal("0.60");

    /** 共担 KPI 权重下限（30%），低于时拒绝保存 */
    public static final BigDecimal MIN_SHARED_WEIGHT = new BigDecimal("0.30");

    /** 市场 PM 四项功能 KPI 名称（用于录入校验） */
    public static final List<String> MARKET_PM_FUNCTIONAL_FIELDS = List.of(
        "requirementAccuracy",   // 需求准确率
        "windowHitRate",         // 窗口命中率
        "scenarioCompetitiveness",// 场景竞争力
        "competitorIntelligence" // 竞品情报
    );

    /** 研发 PM 四项功能 KPI 名称 */
    public static final List<String> RD_PM_FUNCTIONAL_FIELDS = List.of(
        "launchOnTimeRate",      // 上市准时率
        "qualityDefectRate",     // 质量缺陷率
        "techInnovation",        // 技术创新度
        "firstPassYield"         // 一次性实现率
    );

    /** 单项功能 KPI 默认权重 15% */
    public static final BigDecimal SINGLE_FIELD_WEIGHT = new BigDecimal("0.15");

    /** 窗口命中率偏差容忍（天）：偏差 ≤ 30 天线性衰减 */
    public static final int WINDOW_HIT_TOLERANCE_DAYS = 30;

    /** 每超过 1 天扣 1 分（窗口命中率 0~100） */
    public static final BigDecimal WINDOW_HIT_PENALTY_PER_DAY = BigDecimal.ONE;

    private KpiScoreCalculator() {}

    /**
     * 计算综合得分。
     *
     * @param functional 功能 KPI 得分（0~100）
     * @param shared     共担 KPI 得分（0~100）
     * @param w          功能权重（0~1），默认 0.6
     * @return 综合得分 = functional × w + shared × (1 − w)，保留 2 位小数，HALF_UP
     */
    public static BigDecimal comprehensive(BigDecimal functional, BigDecimal shared, BigDecimal w) {
        if (functional == null || shared == null) {
            throw new IpdBusinessException("功能 / 共担得分不能为空");
        }
        BigDecimal weight = w == null ? DEFAULT_FUNCTIONAL_WEIGHT : w;
        validateFunctionalWeight(weight);
        return functional.multiply(weight)
            .add(shared.multiply(BigDecimal.ONE.subtract(weight)))
            .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 校验功能权重 w：共担权重 (1 − w) 不得低于 30%。
     *
     * @param w 功能权重
     * @throws IllegalArgumentException 校验失败
     */
    public static void validateFunctionalWeight(BigDecimal w) {
        if (w == null) {
            throw new IpdBusinessException("功能 KPI 权重不能为空");
        }
        if (w.compareTo(BigDecimal.ZERO) <= 0 || w.compareTo(BigDecimal.ONE) > 0) {
            throw new IpdBusinessException("功能 KPI 权重必须在 (0, 1] 区间");
        }
        BigDecimal sharedWeight = BigDecimal.ONE.subtract(w);
        if (sharedWeight.compareTo(MIN_SHARED_WEIGHT) < 0) {
            throw new IpdBusinessException(
                "共担 KPI 权重不得低于 30%（当前共担=" + sharedWeight + "）");
        }
    }

    /**
     * 市场 PM 功能 KPI 录入校验：四项 + 总权重 = 60%。
     *
     * @param weights 四项权重（小数），顺序对应 MARKET_PM_FUNCTIONAL_FIELDS
     */
    public static void validateMarketPmFunctionalWeights(List<BigDecimal> weights) {
        validateFourFieldWeights(weights, MARKET_PM_FUNCTIONAL_FIELDS);
    }

    /**
     * 研发 PM 功能 KPI 录入校验：四项 + 总权重 = 60%。
     */
    public static void validateRdPmFunctionalWeights(List<BigDecimal> weights) {
        validateFourFieldWeights(weights, RD_PM_FUNCTIONAL_FIELDS);
    }

    private static void validateFourFieldWeights(List<BigDecimal> weights, List<String> fields) {
        if (weights == null || weights.size() != 4) {
            throw new IpdBusinessException(
                fields.get(0).substring(0, 1).toUpperCase() + " PM 功能 KPI 必须录入四项（" + fields + "）");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            if (w == null || w.compareTo(BigDecimal.ZERO) < 0 || w.compareTo(BigDecimal.ONE) > 0) {
                throw new IpdBusinessException("单项权重必须在 [0, 1] 区间");
            }
            sum = sum.add(w);
        }
        // 四项总和应等于功能 KPI 总权重（默认 60%）；允许 ±0.01 容差避免浮点累计误差
        BigDecimal diff = sum.subtract(DEFAULT_FUNCTIONAL_WEIGHT).abs();
        if (diff.compareTo(new BigDecimal("0.01")) > 0) {
            throw new IpdBusinessException(
                "四项功能 KPI 总权重必须等于功能 KPI 总权重（" + DEFAULT_FUNCTIONAL_WEIGHT + "），当前=" + sum);
        }
    }

    /**
     * AC-KPI-15：偏差天数计算（实际上市日期 vs 计划窗口）。
     *
     * @param actualLaunchDate   实际上市日期（毫秒）
     * @param plannedWindowStart 计划窗口起始（毫秒）
     * @param plannedWindowEnd   计划窗口截止（毫秒）
     * @return 偏差天数（绝对值）：落在窗口内为 0；早于窗口为窗口起点 − 实际；晚于窗口为实际 − 窗口终点
     */
    public static int deviationDays(long actualLaunchDate, long plannedWindowStart, long plannedWindowEnd) {
        if (plannedWindowStart > plannedWindowEnd) {
            throw new IpdBusinessException("计划窗口起始不能晚于截止");
        }
        if (actualLaunchDate < plannedWindowStart) {
            return (int) ((plannedWindowStart - actualLaunchDate) / 86400_000L);
        }
        if (actualLaunchDate > plannedWindowEnd) {
            return (int) ((actualLaunchDate - plannedWindowEnd) / 86400_000L);
        }
        return 0;
    }

    /**
     * AC-KPI-15：市场窗口命中率得分。
     * 偏差 ≤ 0 天 → 100 分；偏差每超过 1 天扣 1 分；偏差 ≥ 30 天 → 70 分；偏差 > 30 天 → 0 分。
     * <p>规则：偏差 ≤ 30 天线性衰减，>30 天一律 0。
     *
     * @param deviationDays 偏差天数（绝对值，≥ 0）
     * @return 命中率 0~100
     */
    public static BigDecimal windowHitRate(int deviationDays) {
        if (deviationDays < 0) {
            throw new IpdBusinessException("偏差天数不能为负");
        }
        if (deviationDays == 0) {
            return new BigDecimal("100");
        }
        if (deviationDays > WINDOW_HIT_TOLERANCE_DAYS) {
            return BigDecimal.ZERO;
        }
        // 偏差 d 在 (0, 30]：100 − d × 1
        return BigDecimal.valueOf(100L - deviationDays);
    }

    // ============================================================
    //  P2 七项功能指标 compute 方法（A2 R149 §A2 拍板：等 owner 业务规则）
    //  - 现状：方法签名稳定 + 默认占位（返回 0）+ TODO 业务规则拍板点
    //  - 业务规则（公式/阈值/默认分）由业务 owner 拍板后由 R166 子任务替换默认实现
    //  - 接口契约不依赖业务规则：null/empty 输入校验、返回值类型、scale 统一
    //  - 拍板记录：docs/ipd-系统说明/R166-kpi2b-p2-7compute-实装-20260921.md
    // ============================================================

    /** 默认占位分（业务 owner 未拍板前的安全默认值 = 0） */
    private static final BigDecimal P2_PLACEHOLDER_SCORE = BigDecimal.ZERO;

    /**
     * AC-KPI-P2-REQ：需求准确率得分（MKT_REQUIREMENT_ACCURACY）。
     *
     * <p>P2 阶段（待 owner 拍板业务规则）：默认返回 0。
     * 业务规则 TODO @owner：分子/分母取值范围、阈值分段（≥X=100 / ≥Y=80 / ≥Z=60 / <Z=0）、
     * 量表版本配套、PPM 目标值、返工率分母。
     *
     * @param accurateCount 需求确认通过数（≥ 0，null 视为 0）
     * @param totalCount    需求总数（> 0，null/0 抛 IpdBusinessException）
     * @return 需求准确率得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal requirementAccuracy(Long accurateCount, Long totalCount) {
        if (totalCount == null || totalCount <= 0) {
            throw new IpdBusinessException("需求准确率分母必须 > 0");
        }
        if (accurateCount != null && (accurateCount < 0 || accurateCount > totalCount)) {
            throw new IpdBusinessException("需求准确率分子必须在 [0, totalCount] 区间");
        }
        // TODO @owner: P2 业务规则拍板后，替换默认占位为实际公式
        return P2_PLACEHOLDER_SCORE;
    }

    /**
     * AC-KPI-P2-SCENE：场景竞争力得分（MKT_SCENARIO_COMPETITIVENESS）。
     *
     * <p>P2 阶段（待 owner 拍板业务规则）：默认返回 0。
     * 业务规则 TODO @owner：场景评分维度（功能匹配度/差异化优势/市场份额影响）、
     * 加权公式、阈值分段。
     *
     * @param matchedScenarios 已匹配场景数（≥ 0）
     * @param plannedScenarios 规划场景数（> 0）
     * @return 场景竞争力得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal scenarioCompetitiveness(Long matchedScenarios, Long plannedScenarios) {
        if (plannedScenarios == null || plannedScenarios <= 0) {
            throw new IpdBusinessException("场景竞争力分母必须 > 0");
        }
        if (matchedScenarios != null && (matchedScenarios < 0 || matchedScenarios > plannedScenarios)) {
            throw new IpdBusinessException("场景竞争力分子必须在 [0, plannedScenarios] 区间");
        }
        // TODO @owner: P2 业务规则拍板后，替换默认占位为实际公式
        return P2_PLACEHOLDER_SCORE;
    }

    /**
     * AC-KPI-P2-COMPETITOR：竞品情报得分（MKT_COMPETITOR_INTELLIGENCE）。
     *
     * <p>P2 阶段（待 owner 拍板业务规则）：默认返回 0。
     * 业务规则 TODO @owner：情报完整度（覆盖率/及时性/差异化建议数）、
     * 加权公式、阈值分段。
     *
     * @param intelCount 有效情报条数（≥ 0）
     * @param plannedCount 计划情报条数（> 0）
     * @return 竞品情报得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal competitorIntelligence(Long intelCount, Long plannedCount) {
        if (plannedCount == null || plannedCount <= 0) {
            throw new IpdBusinessException("竞品情报分母必须 > 0");
        }
        if (intelCount != null && (intelCount < 0 || intelCount > plannedCount)) {
            throw new IpdBusinessException("竞品情报分子必须在 [0, plannedCount] 区间");
        }
        // TODO @owner: P2 业务规则拍板后，替换默认占位为实际公式
        return P2_PLACEHOLDER_SCORE;
    }

    /**
     * AC-KPI-P2-LAUNCH：上市准时率得分（RD_LAUNCH_ON_TIME_RATE）。
     *
     * <p>P2 阶段（待 owner 拍板业务规则）：默认返回 0。
     * 业务规则 TODO @owner：与 K01-K04 共担窗口对齐（上市后 6 个月窗口）、准时判定标准、
     * 偏差容忍区间、阈值分段。
     *
     * @param onTimeLaunches 准时上市次数（≥ 0）
     * @param totalLaunches  总上市次数（> 0）
     * @return 上市准时率得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal launchOnTimeRate(Long onTimeLaunches, Long totalLaunches) {
        if (totalLaunches == null || totalLaunches <= 0) {
            throw new IpdBusinessException("上市准时率分母必须 > 0");
        }
        if (onTimeLaunches != null && (onTimeLaunches < 0 || onTimeLaunches > totalLaunches)) {
            throw new IpdBusinessException("上市准时率分子必须在 [0, totalLaunches] 区间");
        }
        // TODO @owner: P2 业务规则拍板后，替换默认占位为实际公式
        return P2_PLACEHOLDER_SCORE;
    }

    /**
     * AC-KPI-P2-DEFECT：质量缺陷率得分（RD_QUALITY_DEFECT_RATE）。
     *
     * <p>P2 阶段（待 owner 拍板业务规则）：默认返回 0。
     * 业务规则 TODO @owner：缺陷严重度分级（P0/P1/P2/P3）、缺陷率阈值、
     * 缺陷来源过滤（排除/纳入）、权重公式。
     * <p>语义：缺陷率越低分越高（与"高 = 好"的其他 6 项反向），公式替换时须显式翻转。
     *
     * @param defectCount  缺陷数（≥ 0）
     * @param totalUnits   投产单位数（> 0）
     * @return 质量缺陷率得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal qualityDefectRate(Long defectCount, Long totalUnits) {
        if (totalUnits == null || totalUnits <= 0) {
            throw new IpdBusinessException("质量缺陷率分母必须 > 0");
        }
        if (defectCount != null && (defectCount < 0 || defectCount > totalUnits)) {
            throw new IpdBusinessException("质量缺陷率分子必须在 [0, totalUnits] 区间");
        }
        // TODO @owner: P2 业务规则拍板后，替换默认占位为实际公式（注意反向语义）
        return P2_PLACEHOLDER_SCORE;
    }

    /**
     * AC-KPI-P2-INNOVATION：技术创新度得分（RD_TECH_INNOVATION）。
     *
     * <p>P2 阶段（待 owner 拍板业务规则）：默认返回 0。
     * 业务规则 TODO @owner：创新项类型（专利/标准/技术突破/架构创新）、
     * 加权公式、阈值分段、专家评审维度。
     *
     * @param innovationPoints 创新点加权得分（≥ 0，由评审委员会打分）
     * @param targetPoints     目标创新点得分（> 0）
     * @return 技术创新度得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal techInnovation(Long innovationPoints, Long targetPoints) {
        if (targetPoints == null || targetPoints <= 0) {
            throw new IpdBusinessException("技术创新度目标分必须 > 0");
        }
        if (innovationPoints != null && (innovationPoints < 0 || innovationPoints > targetPoints * 10)) {
            throw new IpdBusinessException("技术创新度分子超出合理范围（允许 ≤ targetPoints × 10）");
        }
        // TODO @owner: P2 业务规则拍板后，替换默认占位为实际公式
        return P2_PLACEHOLDER_SCORE;
    }

    /**
     * AC-KPI-P2-FPY：一次性实现率得分（RD_FIRST_PASS_YIELD）。
     *
     * <p>P2 阶段（待 owner 拍板业务规则）：默认返回 0。
     * 业务规则 TODO @owner：FPY 判定标准（首次通过/首次提交/首次集成）、
     * 重工扣分项、阈值分段。
     *
     * @param firstPassCount 一次性通过次数（≥ 0）
     * @param totalCycles     总交付周期数（> 0）
     * @return 一次性实现率得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal firstPassYield(Long firstPassCount, Long totalCycles) {
        if (totalCycles == null || totalCycles <= 0) {
            throw new IpdBusinessException("一次性实现率分母必须 > 0");
        }
        if (firstPassCount != null && (firstPassCount < 0 || firstPassCount > totalCycles)) {
            throw new IpdBusinessException("一次性实现率分子必须在 [0, totalCycles] 区间");
        }
        // TODO @owner: P2 业务规则拍板后，替换默认占位为实际公式
        return P2_PLACEHOLDER_SCORE;
    }
}
