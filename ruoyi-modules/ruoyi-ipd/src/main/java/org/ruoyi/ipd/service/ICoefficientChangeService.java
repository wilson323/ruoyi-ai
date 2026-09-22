package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.CoefficientChangeRequest;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ICoefficientChangeService 接口（paiban-05 接口化，实现见 {@link CoefficientChangeService}）。
 */
public interface ICoefficientChangeService {

    /** CoefficientChange 实体类型（与 DefaultStateMachineGuard.registerRule 约定一致） */
    void setStateMachineGuard(StateMachineGuard stateMachineGuard);

    /** R24 接线：注册 postCommit 副作用（事务提交后触发）。 */
    /** * 双PM 联合提议（一次提交同时登记双方 ID）。 */
    /** * */
    /** * @param projectId   项目 */
    /** * @param coefficient 提议系数 */
    /** * @param reason      定值理由（必填） */
    /** * @param marketPmId  市场PM */
    /** * @param rdPmId      研发PM */
    /** * @param proposerId  提交人 */
    /** * @return 新建申请（PENDING_LEADER） */
    CoefficientChangeRequest propose(
        Long projectId,
        BigDecimal coefficient,
        String reason,
        Long marketPmId,
        Long rdPmId,
        Long proposerId,
        IpdActor actor
    );

    /** * 产品组长确认或驳回；确认后写回项目档案。 */
    /** * */
    /** * @param requestId 申请 ID */
    /** * @param leaderId  组长 */
    /** * @param approve   true=确认写入；false=驳回 */
    /** * @param opinion   意见 */
    /** * @return 终态申请 */
    CoefficientChangeRequest leaderDecision(
        Long requestId,
        Long leaderId,
        boolean approve,
        String opinion,
        IpdActor actor
    );

    /** * P1-2：按项目 ID 列系数变更单（{@code projectId=null} 返回全库，按创建时间倒序）。 */
    /** * <p>只读事务；{@code currentPersonId} 入参预留审计追踪位（与 controller 端 actor.id() 对齐）， */
    /** * 暂不做 IDOR 过滤（项目级查询码已限制为内部角色；projectId 维度由 controller 决定）。 */
    /** * 与 RequirementChangeService.listByProject 同型。 */
    /** * */
    /** * @param projectId      可选项目 ID 过滤 */
    /** * @param currentPersonId 当前会话人 ID（审计追踪位） */
    /** * @return 系数变更单列表 */
    List<CoefficientChangeRequest> listByProject(Long projectId, String currentPersonId);

    /** * P1-2：按 ID 取系数变更单详情；id 缺失或不存在直接 fail-fast（service 层兜底，不依赖 controller）。 */
    /** * */
    /** * @param id             申请 ID */
    /** * @param currentPersonId 当前会话人 ID（审计追踪位） */
    /** * @return 单条系数变更单 */
    CoefficientChangeRequest getByIdForReview(Long id, String currentPersonId);

}
