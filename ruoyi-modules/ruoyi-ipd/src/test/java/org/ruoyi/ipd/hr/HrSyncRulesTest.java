package org.ruoyi.ipd.hr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.hr.HrResponse.BasicInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HrSyncRules 单测（2026-09-29 EHR 对接口径，{@code @Tag("dev")}）。
 *
 * <p>覆盖需求表两条口径：
 * <ol>
 *   <li>员工类型过滤（「各系统接口对接人及往返数据流」IPD 行）：正式工 = 试用/转正/退休返聘
 *       （EMPCATEGORY ∈ {Regular, Probation, Relationship}），劳务/实习/外包等剔除；</li>
 *   <li>X 标识口径（「传参X标识会议记录」IPD 行：DEL_FLAG=×、LEAVE_FLAG=√）：
 *       离职只认 LEAVE_FLAG=X / STAT2=0，DEL_FLAG 不消费。</li>
 * </ol>
 */
@Tag("dev")
@DisplayName("EHR-口径 HrSyncRules: 员工类型过滤 + LEAVE_FLAG 离职判定")
class HrSyncRulesTest {

    private static BasicInfo info(String empcategory, String stat2, String leaveFlag, String delFlag) {
        BasicInfo i = new BasicInfo();
        i.empcategory = empcategory;
        i.stat2 = stat2;
        i.leaveFlag = leaveFlag;
        i.delFlag = delFlag;
        return i;
    }

    // ===== 员工类型过滤 =====

    @Test
    @DisplayName("正式工三类合格：试用 Probation / 转正 Regular / 退休返聘 Relationship")
    void eligibleCategories() {
        assertThat(HrSyncRules.isEligible(info("Regular", "3", "", ""))).isTrue();
        assertThat(HrSyncRules.isEligible(info("Probation", "3", "", ""))).isTrue();
        assertThat(HrSyncRules.isEligible(info("Relationship", "3", "", ""))).isTrue();
    }

    @Test
    @DisplayName("劳务/实习/外包等非正式工剔除（不入任何表）")
    void ineligibleCategories_filteredOut() {
        assertThat(HrSyncRules.isEligible(info("Labor", "3", "", ""))).isFalse();
        assertThat(HrSyncRules.isEligible(info("Intern", "3", "", ""))).isFalse();
        assertThat(HrSyncRules.isEligible(info("Outsource", "3", "", ""))).isFalse();
    }

    @Test
    @DisplayName("空值/ null 视为不合格（范围合规优先），BasicInfo 缺失也不合格")
    void nullAndBlankNotEligible() {
        assertThat(HrSyncRules.isEligible(info("", "3", "", ""))).isFalse();
        assertThat(HrSyncRules.isEligible(info(null, "3", "", ""))).isFalse();
        assertThat(HrSyncRules.isEligible(null)).isFalse();
    }

    @Test
    @DisplayName("容忍首尾空白：\" Regular \" 合格")
    void trimsWhitespace() {
        assertThat(HrSyncRules.isEligible(info(" Regular ", "3", "", ""))).isTrue();
    }

    // ===== LEAVE_FLAG / STAT2 离职口径 =====

    @Test
    @DisplayName("LEAVE_FLAG=X（大小写不敏感）⇒ 离职")
    void leaveFlagX_resigned() {
        assertThat(HrSyncRules.isResigned(info("Regular", "3", "X", ""))).isTrue();
        assertThat(HrSyncRules.isResigned(info("Regular", "", "x", ""))).isTrue();
    }

    @Test
    @DisplayName("STAT2=0 ⇒ 离职")
    void stat2Zero_resigned() {
        assertThat(HrSyncRules.isResigned(info("Regular", "0", "", ""))).isTrue();
    }

    @Test
    @DisplayName("在职：STAT2=3 且 LEAVE_FLAG 空 ⇒ 不离职")
    void activeNotResigned() {
        assertThat(HrSyncRules.isResigned(info("Regular", "3", "", ""))).isFalse();
        assertThat(HrSyncRules.isResigned(info("Regular", "3", null, ""))).isFalse();
    }

    @Test
    @DisplayName("DEL_FLAG=X 不影响状态（IPD 口径 ×：不消费删除标识）")
    void delFlagNotConsumed() {
        assertThat(HrSyncRules.isResigned(info("Regular", "3", "", "X"))).isFalse();
    }

    @Test
    @DisplayName("BasicInfo 缺失 ⇒ 不离职（fail-safe 不误杀）")
    void nullInfoNotResigned() {
        assertThat(HrSyncRules.isResigned(null)).isFalse();
    }
}
