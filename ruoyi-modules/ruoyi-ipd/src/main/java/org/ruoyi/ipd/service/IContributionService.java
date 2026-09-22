package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.ContributionVersion;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.dto.ContributionSaveReq;
import org.ruoyi.ipd.dto.ContributionVersionView;
import org.ruoyi.ipd.dto.ContributionView;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.ContributionVersionMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
