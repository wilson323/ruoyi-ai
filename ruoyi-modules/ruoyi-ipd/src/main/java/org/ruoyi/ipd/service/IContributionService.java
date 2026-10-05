package org.ruoyi.ipd.service;

import java.math.BigDecimal;
import java.util.List;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.dto.ContributionSaveReq;
import org.ruoyi.ipd.dto.ContributionVersionView;
import org.ruoyi.ipd.dto.ContributionView;

/**
 * IContributionService 接口（paiban-05 接口化，实现见 {@link ContributionService}）。
 */
public interface IContributionService {

    /** Contribution 实体类型（与 DefaultStateMachineGuard.registerRule 约定一致） */
    void setStateMachineGuard(StateMachineGuard stateMachineGuard);

    /** R24 接线：注册 postCommit 副作用（事务提交后触发）。 */
    /** * 查询项目最新贡献度评定；若不存在则返回 null。 */
    ContributionView getByProject(Long projectId);

    /** * 公式预览：不改库；返回当前五维度下的 tierCoefficient 与联动比例。 */
    ContributionView preview(Long projectId, ContributionSaveReq req);

    /** * 双 PM 自评保存（落地五维度 + tierCoefficient）。 */
    /** * <p>幂等：同 (projectId, role) 已存在则覆盖；状态保持 DRAFT（双方均完成时 → SUBMITTED）。 */
    ContributionView saveSelf(Long projectId, ContributionSaveReq req);

    /** * 调整市场 PM 比例：区间校验通过 → 落 marketShare，rdShare = 1.0 - market（AC-INC-25）。 */
    ContributionView adjustMarketShare(Long projectId, BigDecimal marketShare);

    /** * 产品组长确认贡献度；幂等（已 CONFIRMED 不重复落审计）。 */
    ContributionView confirm(Long projectId, String decision, String opinion);

    /** * 版本历史列表：该项目的历次确认归档快照（versionNo 降序，最新确认在前）。 */
    /** * <p>2026-09-08 前端契约对照轮补交：此前仅单行 contributions，无版本追溯。 */
    List<ContributionVersionView> listVersions(Long projectId);

}
