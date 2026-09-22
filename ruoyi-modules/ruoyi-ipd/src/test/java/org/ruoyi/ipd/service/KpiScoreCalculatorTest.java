package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KPI 得分纯函数计算器单测（{@link KpiScoreCalculator}）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>P1：comprehensive / deviationDays / windowHitRate 既有方法契约</li>
 *   <li>P2 七项功能指标：requirementAccuracy / scenarioCompetitiveness /
 *       competitorIntelligence / launchOnTimeRate / qualityDefectRate /
 *       techInnovation / firstPassYield — 验证接口契约绿（null/empty/越界校验）
 *       + R167 §三 业务规则公式实装（PPM 目标 / 5 维评分 / 严重度 3/2/1 / 重工扣分 等）</li>
 * </ul>
 *
 * <p>R167 默认值参考：
 * <ul>
 *   <li>REQ_ACCURACY_PPM_TARGET = 85</li>
 *   <li>SCENARIO_COMPETE_DIMENSION_COUNT = 5（每维 0~20）</li>
 *   <li>COMPETITOR_INTEL_DIMENSION_COUNT = 5（每维 0~20）</li>
 *   <li>LAUNCH_ON_TIME_TOLERANCE_DAYS = 30（每超 1 天扣 1 分）</li>
 *   <li>DEFECT_SEVERITY = 严重×3 + 一般×2 + 轻微×1</li>
 *   <li>TECH_INNOVATION_SCORE_CAP = 100</li>
 *   <li>FPY_REWORK_PENALTY = 5 分/次</li>
 * </ul>
 *
 * <p>Surefire groups 过滤要求必打 {@code @Tag("dev")}，否则被静默跳过。
 */
@Tag("dev")
class KpiScoreCalculatorTest {

    /* ====================== P1 既有方法契约（AC-KPI-04 / AC-KPI-15）====================== */

    @Test
    @DisplayName("[P1] comprehensive：f=80 s=70 w=0.6 → 80×0.6 + 70×0.4 = 76.00")
    void comprehensive_basicCase() {
        BigDecimal result = KpiScoreCalculator.comprehensive(
            new BigDecimal("80"), new BigDecimal("70"), new BigDecimal("0.60"));
        assertThat(result).isEqualByComparingTo(new BigDecimal("76.00"));
    }

    @Test
    @DisplayName("[P1] comprehensive：w=null 使用默认 0.6")
    void comprehensive_defaultWeight() {
        BigDecimal result = KpiScoreCalculator.comprehensive(
            new BigDecimal("90"), new BigDecimal("80"), null);
        assertThat(result).isEqualByComparingTo(new BigDecimal("86.00"));
    }

    @Test
    @DisplayName("[P1] deviationDays：落在窗口内 → 0")
    void deviationDays_insideWindow() {
        int days = KpiScoreCalculator.deviationDays(
            1_700_000_000_000L, 1_600_000_000_000L, 1_800_000_000_000L);
        assertThat(days).isZero();
    }

    @Test
    @DisplayName("[P1] windowHitRate：偏差 0 天 → 100")
    void windowHitRate_zeroDeviation() {
        BigDecimal score = KpiScoreCalculator.windowHitRate(0);
        assertThat(score).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("[P1] windowHitRate：偏差 15 天 → 85")
    void windowHitRate_fifteenDays() {
        BigDecimal score = KpiScoreCalculator.windowHitRate(15);
        assertThat(score).isEqualByComparingTo(new BigDecimal("85"));
    }

    @Test
    @DisplayName("[P1] windowHitRate：偏差 > 30 天 → 0")
    void windowHitRate_overTolerance() {
        BigDecimal score = KpiScoreCalculator.windowHitRate(60);
        assertThat(score).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ====================== P2 七项功能指标（R167 默认值实装公式）====================== */

    /* ---------- MKT_REQUIREMENT_ACCURACY：PPM=85，返工率/PPM × 100 扣分 ---------- */

    @Test
    @DisplayName("[P2-REQ] requirementAccuracy：分母=0 抛 IpdBusinessException")
    void requirementAccuracy_zeroTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.requirementAccuracy(5L, 0L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("分母必须 > 0");
    }

    @Test
    @DisplayName("[P2-REQ] requirementAccuracy：分子 > 分母 抛 IpdBusinessException")
    void requirementAccuracy_numeratorOverTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.requirementAccuracy(11L, 10L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("[0, totalCount]");
    }

    @Test
    @DisplayName("[P2-REQ] requirementAccuracy：准确率 100%（返工率 0%）→ 100 分")
    void requirementAccuracy_perfectScore() {
        BigDecimal result = KpiScoreCalculator.requirementAccuracy(10L, 10L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("[P2-REQ] requirementAccuracy：准确率 90%（返工率 10%）→ 100 − 10/85×100 = 88.24")
    void requirementAccuracy_90PercentAccuracy() {
        BigDecimal result = KpiScoreCalculator.requirementAccuracy(90L, 100L);
        // reworkRate = 10%, penalty = 10/85 × 100 = 11.7647 → score = 88.2352 → HALF_UP → 88.24
        assertThat(result).isEqualByComparingTo(new BigDecimal("88.24"));
    }

    @Test
    @DisplayName("[P2-REQ] requirementAccuracy：分子=null 视为 0（返工率 100% → score 截断到 0）")
    void requirementAccuracy_nullNumeratorReturnsZero() {
        BigDecimal result = KpiScoreCalculator.requirementAccuracy(null, 10L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ---------- MKT_SCENARIO_COMPETITIVENESS：5 维评分等权，每维 0~20 ---------- */

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness(dimensionScores)：分母=0 抛异常（便捷重载）")
    void scenarioCompetitiveness_zeroTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.scenarioCompetitiveness(3L, 0L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("分母必须 > 0");
    }

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness(dimensionScores)：传入 null 抛异常")
    void scenarioCompetitiveness_nullArrayThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.scenarioCompetitiveness((BigDecimal[]) null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("必须传入 5 维评分");
    }

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness(dimensionScores)：传入 4 维抛异常")
    void scenarioCompetitiveness_wrongDimensionCountThrows() {
        BigDecimal[] fourDims = new BigDecimal[4];
        for (int i = 0; i < 4; i++) {
            fourDims[i] = new BigDecimal("15");
        }
        assertThatThrownBy(() -> KpiScoreCalculator.scenarioCompetitiveness(fourDims))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("必须传入 5 维评分");
    }

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness(dimensionScores)：第 3 维超 20 抛异常")
    void scenarioCompetitiveness_dimensionOverMaxThrows() {
        BigDecimal[] dims = { new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("21"),
            new BigDecimal("20"), new BigDecimal("20") };
        assertThatThrownBy(() -> KpiScoreCalculator.scenarioCompetitiveness(dims))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("第 3 维评分必须在 [0, 20]");
    }

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness(dimensionScores)：5 维满分 20×5 → 100")
    void scenarioCompetitiveness_perfectScore() {
        BigDecimal[] dims = { new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("20"),
            new BigDecimal("20"), new BigDecimal("20") };
        BigDecimal result = KpiScoreCalculator.scenarioCompetitiveness(dims);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness(dimensionScores)：5 维各 16 → 80")
    void scenarioCompetitiveness_80Score() {
        BigDecimal[] dims = { new BigDecimal("16"), new BigDecimal("16"), new BigDecimal("16"),
            new BigDecimal("16"), new BigDecimal("16") };
        BigDecimal result = KpiScoreCalculator.scenarioCompetitiveness(dims);
        assertThat(result).isEqualByComparingTo(new BigDecimal("80.00"));
    }

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness(Long, Long)：3/5 → 每维 12 → 60")
    void scenarioCompetitiveness_longOverloadMapping() {
        BigDecimal result = KpiScoreCalculator.scenarioCompetitiveness(3L, 5L);
        // ratio = 3/5 = 0.6; perDim = 0.6 × 20 = 12; 5 维各 12 → 60
        assertThat(result).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    /* ---------- MKT_COMPETITOR_INTELLIGENCE：5 维评分等权，每维 0~20 ---------- */

    @Test
    @DisplayName("[P2-COMPETITOR] competitorIntelligence(Long, Long)：分母=null 抛异常（便捷重载）")
    void competitorIntelligence_nullTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.competitorIntelligence(2L, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("分母必须 > 0");
    }

    @Test
    @DisplayName("[P2-COMPETITOR] competitorIntelligence(dimensionScores)：传入 null 抛异常")
    void competitorIntelligence_nullArrayThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.competitorIntelligence((BigDecimal[]) null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("必须传入 5 维评分");
    }

    @Test
    @DisplayName("[P2-COMPETITOR] competitorIntelligence(dimensionScores)：5 维满分 → 100")
    void competitorIntelligence_perfectScore() {
        BigDecimal[] dims = { new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("20"),
            new BigDecimal("20"), new BigDecimal("20") };
        BigDecimal result = KpiScoreCalculator.competitorIntelligence(dims);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("[P2-COMPETITOR] competitorIntelligence(dimensionScores)：5 维各 12 → 60（良好段）")
    void competitorIntelligence_60Score() {
        BigDecimal[] dims = { new BigDecimal("12"), new BigDecimal("12"), new BigDecimal("12"),
            new BigDecimal("12"), new BigDecimal("12") };
        BigDecimal result = KpiScoreCalculator.competitorIntelligence(dims);
        assertThat(result).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    /* ---------- RD_LAUNCH_ON_TIME_RATE：偏差 (total-onTime) 天，超 30 = 0 ---------- */

    @Test
    @DisplayName("[P2-LAUNCH] launchOnTimeRate：分子 > 分母 抛异常")
    void launchOnTimeRate_numeratorOverTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.launchOnTimeRate(6L, 5L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("[0, totalLaunches]");
    }

    @Test
    @DisplayName("[P2-LAUNCH] launchOnTimeRate：全部准时（onTime==total）→ 100")
    void launchOnTimeRate_allOnTime() {
        BigDecimal result = KpiScoreCalculator.launchOnTimeRate(5L, 5L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("[P2-LAUNCH] launchOnTimeRate：偏差 15 天（onTime=85,total=100）→ 85")
    void launchOnTimeRate_15DaysDeviation() {
        BigDecimal result = KpiScoreCalculator.launchOnTimeRate(85L, 100L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("85.00"));
    }

    @Test
    @DisplayName("[P2-LAUNCH] launchOnTimeRate：偏差 60 天（>30）→ 0")
    void launchOnTimeRate_overTolerance() {
        BigDecimal result = KpiScoreCalculator.launchOnTimeRate(40L, 100L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /* ---------- RD_QUALITY_DEFECT_RATE：反向语义，加权缺陷率×100 扣分 ---------- */

    @Test
    @DisplayName("[P2-DEFECT] qualityDefectRate(critical,major,minor,total)：分母=0 抛异常")
    void qualityDefectRate_zeroTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.qualityDefectRate(1L, 1L, 1L, 0L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("分母必须 > 0");
    }

    @Test
    @DisplayName("[P2-DEFECT] qualityDefectRate(critical,major,minor,total)：负缺陷数抛异常")
    void qualityDefectRate_negativeThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.qualityDefectRate(-1L, 0L, 0L, 100L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不能为负");
    }

    @Test
    @DisplayName("[P2-DEFECT] qualityDefectRate(critical,major,minor,total)：零缺陷 → 100")
    void qualityDefectRate_perfectScore() {
        BigDecimal result = KpiScoreCalculator.qualityDefectRate(0L, 0L, 0L, 100L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("[P2-DEFECT] qualityDefectRate(critical,major,minor,total)：加权缺陷率 5% → 95")
    void qualityDefectRate_5PercentWeighted() {
        // critical=0, major=0, minor=5, total=100 → 加权缺陷率 = 5/100 = 5% → score = 95
        BigDecimal result = KpiScoreCalculator.qualityDefectRate(0L, 0L, 5L, 100L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("95.00"));
    }

    @Test
    @DisplayName("[P2-DEFECT] qualityDefectRate(critical,major,minor,total)：严重 1 缺陷 → 严重加权 3% → 97")
    void qualityDefectRate_criticalSeverity() {
        // critical=1, total=100 → 加权缺陷 = 1×3 = 3, 缺陷率 3% → score = 97
        BigDecimal result = KpiScoreCalculator.qualityDefectRate(1L, 0L, 0L, 100L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("97.00"));
    }

    @Test
    @DisplayName("[P2-DEFECT] qualityDefectRate(critical,major,minor,total)：加权缺陷率 200% → 截断 0（反向语义）")
    void qualityDefectRate_over100PercentClampedToZero() {
        // critical=10, major=10, minor=10, total=10 → 加权 = 30+20+10 = 60, 缺陷率 600% → 截断 0
        BigDecimal result = KpiScoreCalculator.qualityDefectRate(10L, 10L, 10L, 10L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /* ---------- RD_TECH_INNOVATION：score = min(100, innovation/target × 100) ---------- */

    @Test
    @DisplayName("[P2-INNOVATION] techInnovation：目标分=null 抛异常")
    void techInnovation_nullTargetThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.techInnovation(80L, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("目标分必须 > 0");
    }

    @Test
    @DisplayName("[P2-INNOVATION] techInnovation：分子超出 targetPoints × 10 抛异常")
    void techInnovation_numeratorOverReasonableThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.techInnovation(200L, 10L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("超出合理范围");
    }

    @Test
    @DisplayName("[P2-INNOVATION] techInnovation：innovation=80,target=100 → 80")
    void techInnovation_80Score() {
        BigDecimal result = KpiScoreCalculator.techInnovation(80L, 100L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("80.00"));
    }

    @Test
    @DisplayName("[P2-INNOVATION] techInnovation：innovation=target → 100")
    void techInnovation_perfectScore() {
        BigDecimal result = KpiScoreCalculator.techInnovation(100L, 100L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("[P2-INNOVATION] techInnovation：innovation > target → 封顶 100")
    void techInnovation_overflowClampedTo100() {
        // innovation=200, target=100 → 200/100×100=200 → 封顶 100
        BigDecimal result = KpiScoreCalculator.techInnovation(200L, 100L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    /* ---------- RD_FIRST_PASS_YIELD：base − rework × 5 ---------- */

    @Test
    @DisplayName("[P2-FPY] firstPassYield(firstPass,total,rework)：总周期数=null 抛异常")
    void firstPassYield_nullTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.firstPassYield(8L, null, 0L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("分母必须 > 0");
    }

    @Test
    @DisplayName("[P2-FPY] firstPassYield(firstPass,total,rework)：分子 > 分母 抛异常")
    void firstPassYield_numeratorOverTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.firstPassYield(11L, 10L, 0L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("[0, totalCycles]");
    }

    @Test
    @DisplayName("[P2-FPY] firstPassYield(firstPass,total,rework)：负重工次数抛异常")
    void firstPassYield_negativeReworkThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.firstPassYield(8L, 10L, -1L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("重工次数不能为负");
    }

    @Test
    @DisplayName("[P2-FPY] firstPassYield(firstPass,total,rework)：一次性全过 0 重工 → 100")
    void firstPassYield_perfectScore() {
        BigDecimal result = KpiScoreCalculator.firstPassYield(10L, 10L, 0L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("[P2-FPY] firstPassYield(firstPass,total,rework)：80% 一次通过 + 2 次重工 → 80−10 = 70")
    void firstPassYield_80BaseWithRework() {
        // firstPass=8, total=10 → base=80; rework=2 × 5 = 10 → score = 70
        BigDecimal result = KpiScoreCalculator.firstPassYield(8L, 10L, 2L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    @DisplayName("[P2-FPY] firstPassYield(firstPass,total,rework)：重工超扣 → 截断 0")
    void firstPassYield_reworkOverClampedToZero() {
        // base=10, rework=5 × 5 = 25 → score = -15 → 截断 0
        BigDecimal result = KpiScoreCalculator.firstPassYield(1L, 10L, 5L);
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("[P2-FPY] firstPassYield(firstPass,total)：便捷重载 rework=0 → 与三元版一致")
    void firstPassYield_longOverloadDefaultRework() {
        BigDecimal threeArgs = KpiScoreCalculator.firstPassYield(10L, 10L, 0L);
        BigDecimal twoArgs = KpiScoreCalculator.firstPassYield(10L, 10L);
        assertThat(twoArgs).isEqualByComparingTo(threeArgs);
    }
}
