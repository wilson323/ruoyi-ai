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

    // ============================================================
    //  P2 七项功能指标业务规则常量（R167 默认值建议落地）
    //  - 业务 owner 拍板后可调整；当前为工程安全默认（标 R167-DEFAULT）
    // ============================================================

    /** 需求准确率 PPM 目标值（R167 §3.1 默认值 85） */
    public static final BigDecimal REQ_ACCURACY_PPM_TARGET = new BigDecimal("85");

    /** 场景竞争力评分维度数（R167 §3.3 等权 5 维） */
    public static final int SCENARIO_COMPETE_DIMENSION_COUNT = 5;

    /** 单维评分最大值（场景竞争力 5 维，每维 0~20 = 100 总） */
    public static final BigDecimal SCENARIO_COMPETE_DIMENSION_MAX = new BigDecimal("20");

    /** 竞品情报评分维度数（R167 §3.4 等权 5 维） */
    public static final int COMPETITOR_INTEL_DIMENSION_COUNT = 5;

    /** 单维评分最大值（竞品情报 5 维，每维 0~20） */
    public static final BigDecimal COMPETITOR_INTEL_DIMENSION_MAX = new BigDecimal("20");

    /** 上市准时率偏差容忍（天，R167 §3.5 与 windowHitRate 同口径 30 天） */
    public static final int LAUNCH_ON_TIME_TOLERANCE_DAYS = 30;

    /** 上市准时率每偏差 1 天扣 1 分（R167 §3.5） */
    public static final BigDecimal LAUNCH_ON_TIME_PENALTY_PER_DAY = BigDecimal.ONE;

    /** 质量缺陷率严重度系数（R167 §3.6 严重/一般/轻微 = 3/2/1） */
    public static final BigDecimal DEFECT_SEVERITY_CRITICAL = new BigDecimal("3");
    public static final BigDecimal DEFECT_SEVERITY_MAJOR = new BigDecimal("2");
    public static final BigDecimal DEFECT_SEVERITY_MINOR = BigDecimal.ONE;

    /** 技术创新度上限封顶 100 分 */
    public static final BigDecimal TECH_INNOVATION_SCORE_CAP = new BigDecimal("100");

    /** 一次性实现率每重工 1 次扣 5 分（R167 §3.8） */
    public static final BigDecimal FPY_REWORK_PENALTY = new BigDecimal("5");

    /** 安全 BigDecimal 比较容差（避免浮点累计误差） */
    private static final BigDecimal SCORE_COMPARE_TOLERANCE = new BigDecimal("0.01");

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
    //  P2 七项功能指标 compute 方法（R167 默认值建议落地版）
    //  - 公式按 docs/ipd-系统说明/R167-W2-KPI2B-P2-七项业务规则拍板请求包-20260921.md §三
    //  - 业务 owner 拍板后可调整（PPM 目标 / 阈值分段 / 严重度系数 等）
    //  - 现版本：重载保留二元入参调用 + 重载方法接收入参维度（5 维评分 / 严重度计数 / 重工次数）
    // ============================================================

    /**
     * AC-KPI-P2-REQ：需求准确率得分（MKT_REQUIREMENT_ACCURACY）。
     *
     * <p>R167 §3.1 公式：返工率 = (1 − accurateCount/totalCount) × 100；
     * score = max(0, 100 − 返工率/PPM × 100)；PPM 目标默认 85。
     *
     * @param accurateCount 需求确认通过数（≥ 0，null 视为 0）
     * @param totalCount    需求总数（> 0）
     * @return 需求准确率得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal requirementAccuracy(Long accurateCount, Long totalCount) {
        if (totalCount == null || totalCount <= 0) {
            throw new IpdBusinessException("需求准确率分母必须 > 0");
        }
        if (accurateCount != null && (accurateCount < 0 || accurateCount > totalCount)) {
            throw new IpdBusinessException("需求准确率分子必须在 [0, totalCount] 区间");
        }
        long accurate = accurateCount == null ? 0L : accurateCount;
        // 返工率百分比（0~100）：(1 - accurate/total) × 100
        BigDecimal reworkRate = BigDecimal.ONE.subtract(
                new BigDecimal(accurate).divide(new BigDecimal(totalCount), 4, RoundingMode.HALF_UP))
            .multiply(new BigDecimal("100"));
        // score = max(0, 100 - 返工率/PPM × 100)
        BigDecimal penalty = reworkRate.divide(REQ_ACCURACY_PPM_TARGET, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
        BigDecimal score = new BigDecimal("100").subtract(penalty);
        if (score.compareTo(BigDecimal.ZERO) < 0) {
            score = BigDecimal.ZERO;
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * AC-KPI-P2-SCENE：场景竞争力得分（MKT_SCENARIO_COMPETITIVENESS）。
     *
     * <p>R167 §3.3 公式：5 维评分等权（市场规模/竞争烈度/差异化/可落地/可衡量），
     * 每维 0~20 分，总分 0~100；阈值分段：≥80 优秀 / 60-79 良好 / 40-59 一般 / <40 弱。
     *
     * @param dimensionScores 5 维评分数组（每维 0~20，必须 5 个）
     * @return 场景竞争力得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal scenarioCompetitiveness(BigDecimal[] dimensionScores) {
        if (dimensionScores == null || dimensionScores.length != SCENARIO_COMPETE_DIMENSION_COUNT) {
            throw new IpdBusinessException(
                "场景竞争力必须传入 " + SCENARIO_COMPETE_DIMENSION_COUNT + " 维评分");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < dimensionScores.length; i++) {
            BigDecimal s = dimensionScores[i];
            if (s == null) {
                throw new IpdBusinessException("场景竞争力第 " + (i + 1) + " 维评分不能为空");
            }
            if (s.compareTo(BigDecimal.ZERO) < 0 || s.compareTo(SCENARIO_COMPETE_DIMENSION_MAX) > 0) {
                throw new IpdBusinessException(
                    "场景竞争力第 " + (i + 1) + " 维评分必须在 [0, " + SCENARIO_COMPETE_DIMENSION_MAX + "] 区间");
            }
            sum = sum.add(s);
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 场景竞争力便捷重载：从 (matchedScenarios, plannedScenarios) 映射到 5 维评分。
     * 默认每维评分 = matched/planned × 单维满分（等权5维场景适配）。
     *
     * @param matchedScenarios  已匹配场景数（≥ 0）
     * @param plannedScenarios 规划场景数（> 0）
     * @return 场景竞争力得分（0~100）
     */
    public static BigDecimal scenarioCompetitiveness(Long matchedScenarios, Long plannedScenarios) {
        if (plannedScenarios == null || plannedScenarios <= 0) {
            throw new IpdBusinessException("场景竞争力分母必须 > 0");
        }
        if (matchedScenarios != null && (matchedScenarios < 0 || matchedScenarios > plannedScenarios)) {
            throw new IpdBusinessException("场景竞争力分子必须在 [0, plannedScenarios] 区间");
        }
        long matched = matchedScenarios == null ? 0L : matchedScenarios;
        BigDecimal ratio = new BigDecimal(matched).divide(new BigDecimal(plannedScenarios), 4, RoundingMode.HALF_UP);
        BigDecimal perDim = ratio.multiply(SCENARIO_COMPETE_DIMENSION_MAX);
        BigDecimal[] dims = new BigDecimal[SCENARIO_COMPETE_DIMENSION_COUNT];
        for (int i = 0; i < SCENARIO_COMPETE_DIMENSION_COUNT; i++) {
            dims[i] = perDim.setScale(4, RoundingMode.HALF_UP);
        }
        return scenarioCompetitiveness(dims);
    }

    /**
     * AC-KPI-P2-COMPETITOR：竞品情报得分（MKT_COMPETITOR_INTELLIGENCE）。
     *
     * <p>R167 §3.4 公式：5 维评分等权（产品/价格/渠道/促销/技术），每维 0~20，
     * 总分 0~100；阈值分段：≥80 优秀 / 60-79 良好 / 40-59 一般 / <40 弱。
     *
     * @param dimensionScores 5 维评分数组（每维 0~20，必须 5 个）
     * @return 竞品情报得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal competitorIntelligence(BigDecimal[] dimensionScores) {
        if (dimensionScores == null || dimensionScores.length != COMPETITOR_INTEL_DIMENSION_COUNT) {
            throw new IpdBusinessException(
                "竞品情报必须传入 " + COMPETITOR_INTEL_DIMENSION_COUNT + " 维评分");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < dimensionScores.length; i++) {
            BigDecimal s = dimensionScores[i];
            if (s == null) {
                throw new IpdBusinessException("竞品情报第 " + (i + 1) + " 维评分不能为空");
            }
            if (s.compareTo(BigDecimal.ZERO) < 0 || s.compareTo(COMPETITOR_INTEL_DIMENSION_MAX) > 0) {
                throw new IpdBusinessException(
                    "竞品情报第 " + (i + 1) + " 维评分必须在 [0, " + COMPETITOR_INTEL_DIMENSION_MAX + "] 区间");
            }
            sum = sum.add(s);
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 竞品情报便捷重载：从 (intelCount, plannedCount) 映射到 5 维评分。
     * 默认每维评分 = intel/planned × 单维满分。
     */
    public static BigDecimal competitorIntelligence(Long intelCount, Long plannedCount) {
        if (plannedCount == null || plannedCount <= 0) {
            throw new IpdBusinessException("竞品情报分母必须 > 0");
        }
        if (intelCount != null && (intelCount < 0 || intelCount > plannedCount)) {
            throw new IpdBusinessException("竞品情报分子必须在 [0, plannedCount] 区间");
        }
        long intel = intelCount == null ? 0L : intelCount;
        BigDecimal ratio = new BigDecimal(intel).divide(new BigDecimal(plannedCount), 4, RoundingMode.HALF_UP);
        BigDecimal perDim = ratio.multiply(COMPETITOR_INTEL_DIMENSION_MAX);
        BigDecimal[] dims = new BigDecimal[COMPETITOR_INTEL_DIMENSION_COUNT];
        for (int i = 0; i < COMPETITOR_INTEL_DIMENSION_COUNT; i++) {
            dims[i] = perDim.setScale(4, RoundingMode.HALF_UP);
        }
        return competitorIntelligence(dims);
    }

    /**
     * AC-KPI-P2-LAUNCH：上市准时率得分（RD_LAUNCH_ON_TIME_RATE）。
     *
     * <p>R167 §3.5 公式：偏差 = totalLaunches − onTimeLaunches（平均偏差天数），
     * score = max(0, 100 − 偏差 × LAUNCH_ON_TIME_PENALTY_PER_DAY)；
     * 偏差 > 30 天一律 0。与 windowHitRate 同口径。
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
        long onTime = onTimeLaunches == null ? 0L : onTimeLaunches;
        // 平均偏差天数 = (total - onTime) / total × 100 → 以“每偏差1天扣1分”为例
        long deviationDays = totalLaunches - onTime;
        if (deviationDays <= 0) {
            return new BigDecimal("100").setScale(2, RoundingMode.HALF_UP);
        }
        if (deviationDays > LAUNCH_ON_TIME_TOLERANCE_DAYS) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal score = new BigDecimal("100").subtract(
            new BigDecimal(deviationDays).multiply(LAUNCH_ON_TIME_PENALTY_PER_DAY));
        if (score.compareTo(BigDecimal.ZERO) < 0) {
            score = BigDecimal.ZERO;
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * AC-KPI-P2-DEFECT：质量缺陷率得分（RD_QUALITY_DEFECT_RATE）。
     *
     * <p>R167 §3.6 公式：score = max(0, 100 − 缺陷率 × 严重度系数 × 100)，
     * 严重度系数 = 严重×3 + 一般×2 + 轻微×1；缺陷率越低分越高（反向语义）。
     *
     * @param criticalCount 严重缺陷数（≥ 0）
     * @param majorCount    一般缺陷数（≥ 0）
     * @param minorCount    轻微缺陷数（≥ 0）
     * @param totalUnits    投产单位数（> 0）
     * @return 质量缺陷率得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal qualityDefectRate(Long criticalCount, Long majorCount, Long minorCount, Long totalUnits) {
        if (totalUnits == null || totalUnits <= 0) {
            throw new IpdBusinessException("质量缺陷率分母必须 > 0");
        }
        long critical = criticalCount == null ? 0L : criticalCount;
        long major = majorCount == null ? 0L : majorCount;
        long minor = minorCount == null ? 0L : minorCount;
        if (critical < 0 || major < 0 || minor < 0) {
            throw new IpdBusinessException("质量缺陷率各项缺陷数不能为负");
        }
        // 加权缺陷总数 = critical×3 + major×2 + minor×1
        BigDecimal weightedDefects = new BigDecimal(critical).multiply(DEFECT_SEVERITY_CRITICAL)
            .add(new BigDecimal(major).multiply(DEFECT_SEVERITY_MAJOR))
            .add(new BigDecimal(minor).multiply(DEFECT_SEVERITY_MINOR));
        // 缺陷率(加权) × 100
        BigDecimal defectRatePct = weightedDefects.divide(new BigDecimal(totalUnits), 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
        BigDecimal score = new BigDecimal("100").subtract(defectRatePct);
        if (score.compareTo(BigDecimal.ZERO) < 0) {
            score = BigDecimal.ZERO;
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 质量缺陷率便捷重载：从 (defectCount, totalUnits) 按默认轻度缺陷计算（不区分严重度）。
     * 默认所有缺陷按轻微(系数 1)计算。
     */
    public static BigDecimal qualityDefectRate(Long defectCount, Long totalUnits) {
        return qualityDefectRate(0L, 0L, defectCount, totalUnits);
    }

    /**
     * AC-KPI-P2-INNOVATION：技术创新度得分（RD_TECH_INNOVATION）。
     *
     * <p>R167 §3.7 公式：score = min(100, innovationPoints/targetPoints × 100)；
     * 阈值分段：≥70 优秀 / 50-69 良好 / 30-49 一般 / <30 弱。
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
        long innovation = innovationPoints == null ? 0L : innovationPoints;
        BigDecimal score = new BigDecimal(innovation)
            .divide(new BigDecimal(targetPoints), 4, RoundingMode.HALF_UP)
            .multiply(TECH_INNOVATION_SCORE_CAP);
        if (score.compareTo(TECH_INNOVATION_SCORE_CAP) > 0) {
            score = TECH_INNOVATION_SCORE_CAP;
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * AC-KPI-P2-FPY：一次性实现率得分（RD_FIRST_PASS_YIELD）。
     *
     * <p>R167 §3.8 公式：base = firstPassCount/totalCycles × 100；
     * score = max(0, base − reworkCount × FPY_REWORK_PENALTY)；
     * 阈值分段：≥90 优秀 / 75-89 良好 / 60-74 一般 / <60 弱。
     *
     * @param firstPassCount 一次性通过次数（≥ 0）
     * @param totalCycles     总交付周期数（> 0）
     * @param reworkCount     重工次数（≥ 0）
     * @return 一次性实现率得分（0~100），scale=2，HALF_UP
     */
    public static BigDecimal firstPassYield(Long firstPassCount, Long totalCycles, Long reworkCount) {
        if (totalCycles == null || totalCycles <= 0) {
            throw new IpdBusinessException("一次性实现率分母必须 > 0");
        }
        if (firstPassCount != null && (firstPassCount < 0 || firstPassCount > totalCycles)) {
            throw new IpdBusinessException("一次性实现率分子必须在 [0, totalCycles] 区间");
        }
        if (reworkCount != null && reworkCount < 0) {
            throw new IpdBusinessException("一次性实现率重工次数不能为负");
        }
        long firstPass = firstPassCount == null ? 0L : firstPassCount;
        long rework = reworkCount == null ? 0L : reworkCount;
        BigDecimal base = new BigDecimal(firstPass)
            .divide(new BigDecimal(totalCycles), 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
        BigDecimal penalty = new BigDecimal(rework).multiply(FPY_REWORK_PENALTY);
        BigDecimal score = base.subtract(penalty);
        if (score.compareTo(BigDecimal.ZERO) < 0) {
            score = BigDecimal.ZERO;
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 一次性实现率便捷重载：从 (firstPassCount, totalCycles) 默认 reworkCount=0。
     */
    public static BigDecimal firstPassYield(Long firstPassCount, Long totalCycles) {
        return firstPassYield(firstPassCount, totalCycles, 0L);
    }
}
