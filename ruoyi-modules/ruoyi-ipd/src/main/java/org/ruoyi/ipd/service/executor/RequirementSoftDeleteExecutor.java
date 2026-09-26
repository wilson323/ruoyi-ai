package org.ruoyi.ipd.service.executor;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.service.SoftDeleteExecutor;
import org.springframework.stereotype.Component;

/**
 * Requirement 软删除执行器（entityType=requirements，R218 卡1 / AC-REQ-09 双层审核删除收口）。
 *
 * <p>照 GateSoftDeleteExecutor 同模式实现（del_flag 语义与既有 5 个 Executor 完全一致）：
 * <ul>
 *   <li>幂等：已软删或不存在时不重复写（{@code isDeleted} 先行判定，NOOP 由 DeleteAuditService 记审计）；</li>
 *   <li>实体带 {@code @TableLogic}，updateById 会把逻辑删除字段从 SET 子句剔除致静默失效
 *       （R216 实测回归），故与 Person/Project/Gate 同款显式 LambdaUpdateWrapper UPDATE 保证 del_flag 真实落库；</li>
 *   <li>已删行 {@code selectById} 返回 null，与 NOOP 语义一致。</li>
 * </ul>
 *
 * <p>注册链路：本 bean 由 Spring 注入 {@code List<SoftDeleteExecutor<?>>} 自动进入
 * {@code DeleteAuditService.executorsByType} 注册表，无需改 DeleteAuditService；
 * 与 {@code DeletionRequestServiceImpl.SUPPORTED_ENTITY_TYPES} 的 requirements 分量同步补齐。
 */
@Component
@RequiredArgsConstructor
public class RequirementSoftDeleteExecutor implements SoftDeleteExecutor<Requirement> {

    public static final String ENTITY_TYPE = "requirements";

    private final RequirementMapper requirementMapper;

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public Class<Requirement> entityClass() {
        return Requirement.class;
    }

    /**
     * 软删需求池记录（del_flag='1'）。
     *
     * @param id 需求主键
     */
    @Override
    public void softDelete(Long id) {
        Requirement requirement = requirementMapper.selectById(id);
        if (requirement == null || "1".equals(requirement.getDelFlag())) {
            return;
        }
        int rows = requirementMapper.update(null, new LambdaUpdateWrapper<Requirement>()
            .eq(Requirement::getId, id)
            .eq(Requirement::getDelFlag, "0")
            .set(Requirement::getDelFlag, "1"));
        if (rows != 1) {
            throw new ServiceException("需求池记录软删除未更新唯一记录: id=" + id);
        }
    }

    /**
     * 判断需求是否已软删或不存在。
     *
     * @param id 需求主键
     * @return true 表示应记 DELETE_NOOP
     */
    @Override
    public boolean isDeleted(Long id) {
        Requirement requirement = requirementMapper.selectById(id);
        return requirement == null || "1".equals(requirement.getDelFlag());
    }
}
