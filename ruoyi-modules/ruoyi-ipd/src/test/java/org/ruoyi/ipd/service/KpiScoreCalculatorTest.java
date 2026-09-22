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
 *       techInnovation / firstPassYield — 验证接口契约绿（null/empty/越界校验 +
 *       默认占位返回 BigDecimal.ZERO）</li>
 * </ul>
 *
 * <p>P2 业务规则（公式/阈值/默认分）由业务 owner 拍板后由 R166 子任务替换默认实现；
 * 本测试**仅校验接口契约**，不验证业务公式（避免把未拍板的业务规则固化成假绿）。
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

    /* ====================== P2 七项功能指标（接口契约，仅校验 null/empty/越界 + 默认占位）====================== */

    /* ---------- MKT_REQUIREMENT_ACCURACY ---------- */

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
    @DisplayName("[P2-REQ] requirementAccuracy：合法输入返回默认占位 0（P2 阶段待 owner 拍板业务规则）")
    void requirementAccuracy_placeholderReturnsZero() {
        BigDecimal result = KpiScoreCalculator.requirementAccuracy(8L, 10L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("[P2-REQ] requirementAccuracy：分子=null 视为 0，返回默认占位")
    void requirementAccuracy_nullNumeratorAllowed() {
        BigDecimal result = KpiScoreCalculator.requirementAccuracy(null, 10L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ---------- MKT_SCENARIO_COMPETITIVENESS ---------- */

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness：分母=0 抛异常")
    void scenarioCompetitiveness_zeroTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.scenarioCompetitiveness(3L, 0L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("分母必须 > 0");
    }

    @Test
    @DisplayName("[P2-SCENE] scenarioCompetitiveness：合法输入返回默认占位 0")
    void scenarioCompetitiveness_placeholderReturnsZero() {
        BigDecimal result = KpiScoreCalculator.scenarioCompetitiveness(3L, 5L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ---------- MKT_COMPETITOR_INTELLIGENCE ---------- */

    @Test
    @DisplayName("[P2-COMPETITOR] competitorIntelligence：分母=null 抛异常")
    void competitorIntelligence_nullTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.competitorIntelligence(2L, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("分母必须 > 0");
    }

    @Test
    @DisplayName("[P2-COMPETITOR] competitorIntelligence：合法输入返回默认占位 0")
    void competitorIntelligence_placeholderReturnsZero() {
        BigDecimal result = KpiScoreCalculator.competitorIntelligence(2L, 5L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ---------- RD_LAUNCH_ON_TIME_RATE ---------- */

    @Test
    @DisplayName("[P2-LAUNCH] launchOnTimeRate：分子 > 分母 抛异常")
    void launchOnTimeRate_numeratorOverTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.launchOnTimeRate(6L, 5L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("[0, totalLaunches]");
    }

    @Test
    @DisplayName("[P2-LAUNCH] launchOnTimeRate：合法输入返回默认占位 0")
    void launchOnTimeRate_placeholderReturnsZero() {
        BigDecimal result = KpiScoreCalculator.launchOnTimeRate(4L, 5L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ---------- RD_QUALITY_DEFECT_RATE ---------- */

    @Test
    @DisplayName("[P2-DEFECT] qualityDefectRate：缺陷数=负 抛异常")
    void qualityDefectRate_negativeDefectThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.qualityDefectRate(-1L, 100L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("[0, totalUnits]");
    }

    @Test
    @DisplayName("[P2-DEFECT] qualityDefectRate：合法输入返回默认占位 0（反向语义：缺陷率越低分越高）")
    void qualityDefectRate_placeholderReturnsZero() {
        BigDecimal result = KpiScoreCalculator.qualityDefectRate(5L, 100L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ---------- RD_TECH_INNOVATION ---------- */

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
    @DisplayName("[P2-INNOVATION] techInnovation：合法输入返回默认占位 0")
    void techInnovation_placeholderReturnsZero() {
        BigDecimal result = KpiScoreCalculator.techInnovation(80L, 100L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /* ---------- RD_FIRST_PASS_YIELD ---------- */

    @Test
    @DisplayName("[P2-FPY] firstPassYield：总周期数=null 抛异常")
    void firstPassYield_nullTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.firstPassYield(8L, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("分母必须 > 0");
    }

    @Test
    @DisplayName("[P2-FPY] firstPassYield：分子 > 分母 抛异常")
    void firstPassYield_numeratorOverTotalThrows() {
        assertThatThrownBy(() -> KpiScoreCalculator.firstPassYield(11L, 10L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("[0, totalCycles]");
    }

    @Test
    @DisplayName("[P2-FPY] firstPassYield：合法输入返回默认占位 0")
    void firstPassYield_placeholderReturnsZero() {
        BigDecimal result = KpiScoreCalculator.firstPassYield(8L, 10L);
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }
}