package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Date;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.LaunchDateChangeRequest;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ILaunchDateChangeService 接口（paiban-05 接口化，实现见 {@link LaunchDateChangeService}）。
 */
public interface ILaunchDateChangeService {

    /** LaunchDateChange 实体类型（与 DefaultStateMachineGuard.registerRule 约定一致） */
    void setStateMachineGuard(StateMachineGuard stateMachineGuard);

    /** R24 接线：注册 postCommit 副作用（事务提交后触发）。 */
    /** * 提议变更上市日期（第一签）。 */
    /** * <p>R11 / A1 修复（WB-17-1 收口后死路）:提议时预落 confirmer_id/confirmer_role/confirmer_group_id， */
    /** * 使 StrategicChangeAggregator 能正常投递 LD-卡（真活恒空死路）。与 Gate 仲裁方案 1 同构—— */
    /** * propose 时刻已知第二签人（提议人选择/系统互补角色推导），即落库而非 confirm 时回填。 */
    /** * */
    /** * @param projectId         项目 */
    /** * @param proposedDate      新上市日 */
    /** * @param reason            理由 */
    /** * @param proposerId        提议人 */
    /** * @param proposerRole      角色 */
    /** * @param proposerGroupId   提议人所属产品组（横向越权防护用） */
    /** * @param confirmerId       第二签确认人（提议时由前端选定，必传） */
    /** * @param confirmerRole     第二签角色 */
    /** * @param confirmerGroupId  第二签人所属产品组（必传，与项目主组同） */
    /** * @return PENDING_SECOND 申请（confirmer 字段已落库） */
    LaunchDateChangeRequest propose(
        Long projectId,
        Date proposedDate,
        String reason,
        Long proposerId,
        String proposerRole,
        Long proposerGroupId,
        Long confirmerId,
        String confirmerRole,
        Long confirmerGroupId
    );

    /** * 第二签确认或驳回；确认后写回项目上市日期。 */
    /** * */
    /** * @param requestId       申请 */
    /** * @param confirmerId     确认人（须异于提议人） */
    /** * @param confirmerRole   确认人角色 */
    /** * @param confirmerGroupId 确认人所属产品组（横向越权防护用） */
    /** * @param approve         是否通过 */
    /** * @param opinion         意见 */
    /** * @return 终态申请 */
    LaunchDateChangeRequest secondDecision(
        Long requestId,
        Long confirmerId,
        String confirmerRole,
        Long confirmerGroupId,
        boolean approve,
        String opinion
    );

    /** * P1 / §5.1：L08 上市日期初次录入（独立端点）—— 状态仅 DRAFT|CONFIRMED 可调； */
    /** * 写 INITIAL_LAUNCH_DATE 审计。launch_date 已有值（曾录入过）则拒绝（走双签修改流程）。 */
    /** * */
    /** * @param projectId 项目 */
    /** * @param date      录入的上市日期 */
    /** * @param reason    理由 */
    /** * @param operatorId 操作人 */
    Project initialRecord(Long projectId, Date date, String reason, Long operatorId);

    /** * P1-2：按项目 ID 列上市日期变更单（{@code projectId=null} 返回全库，按创建时间倒序）。 */
    /** * 只读事务；{@code currentPersonId} 入参预留审计追踪位。 */
    /** * */
    /** * @param projectId      可选项目 ID 过滤 */
    /** * @param currentPersonId 当前会话人 ID（审计追踪位） */
    /** * @return 上市日期变更单列表 */
    List<LaunchDateChangeRequest> listByProject(Long projectId, String currentPersonId);

    /** * P1-2：按 ID 取上市日期变更单详情；id 缺失或不存在直接 fail-fast。 */
    /** * */
    /** * @param id             申请 ID */
    /** * @param currentPersonId 当前会话人 ID（审计追踪位） */
    /** * @return 单条上市日期变更单 */
    LaunchDateChangeRequest getByIdForReview(Long id, String currentPersonId);

}
