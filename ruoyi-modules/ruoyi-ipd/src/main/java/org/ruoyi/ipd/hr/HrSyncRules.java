package org.ruoyi.ipd.hr;

import org.ruoyi.ipd.hr.HrResponse.BasicInfo;

import java.util.Set;

/**
 * HR 同步数据口径规则（2026-09-29，依据《IPD系统_熵基EHR项目接口需求-2026.9.22》）。
 *
 * <p>集中承载两条业务口径，供 {@link RealHrSyncAdapter} / {@link HrPersonMirrorIngestService} 共用，
 * 避免规则散落多处形成口径双轨：
 * <ol>
 *   <li><b>员工类型范围</b>（需求表「各系统接口对接人及往返数据流」IPD 行）：
 *       只同步正式工——试用 {@code Probation} / 转正 {@code Regular} / 退休返聘 {@code Relationship}，
 *       其余（劳务、实习、外包等）不同步；空值视为不合格（范围合规优先）。</li>
 *   <li><b>X 标识口径</b>（需求表「传参X标识会议记录」IPD 行：DEL_FLAG=×、LEAVE_FLAG=√）：
 *       离职判定只看 {@code LEAVE_FLAG=X} 或 {@code STAT2=0}；<b>不消费 DEL_FLAG</b>
 *       （员工删除标识不进 IPD 的状态联动）。</li>
 * </ol>
 */
final class HrSyncRules {

    /** IPD 传参员工类型白名单（EMPCATEGORY 字典值）。 */
    static final Set<String> ELIGIBLE_EMP_CATEGORIES = Set.of("Regular", "Probation", "Relationship");

    private HrSyncRules() { }

    /** 是否属于 IPD 同步范围（正式工：试用/转正/退休返聘）。BasicInfo 为空视为不合格。 */
    static boolean isEligible(BasicInfo info) {
        if (info == null || info.empcategory == null) return false;
        return ELIGIBLE_EMP_CATEGORIES.contains(info.empcategory.trim());
    }

    /**
     * 离职判定：LEAVE_FLAG=X 或 STAT2=0 ⇒ 离职；否则在职。
     * DEL_FLAG 明确不参与（IPD 不消费删除标识）。
     */
    static boolean isResigned(BasicInfo info) {
        if (info == null) return false;
        String stat2 = info.stat2;
        String leaveFlag = info.leaveFlag;
        return "0".equals(stat2) || "X".equalsIgnoreCase(leaveFlag);
    }
}
