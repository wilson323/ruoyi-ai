package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.approval.ApprovalGuardSupport;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.CoefficientChangeRequest;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * AC-INC-15c：S/B 级系数定值 = 双PM 联合提议 → 产品组长确认 → 写入项目档案。
 * <p>A 级固定 1.0，禁止走本流程；区间校验复用 {@link ProjectService} 规则文案。
 *
 * <p>PERF-P1-6 框架（2026-09-07）：所有写方法统一 {@code @Transactional(rollbackFor = Exception.class)}
 * 类级默认值；{@link #propose(Long, BigDecimal, String, Long, Long, Long)} 与
 * {@link #leaderDecision(Long, Long, boolean, String)} 在同一事务内完成业务写入（request insert/update +
 * project update）；{@link IAuditLogService#append} 走 {@code REQUIRES_NEW} 保证审计链原子分配（seq/prevHash）
 * 与业务回滚解耦——这是审计完整性 vs 性能的固有 trade-off，4 SQL → 1 批插入目标需要引入
 * {@code AppendAuditBatchUtil}（锚行锁一次性分配 N 个连续 seq + 链式哈希 + 批 INSERT），见
 * docs/ipd-系统说明/治理/ 待办；当前提交只做事务边界与代码同质化收敛。
 */
@Service
@RequiredArgsConstructor
public class CoefficientChangeService implements ICoefficientChangeService {

    public static final String ACTION_PROPOSE = "COEFFICIENT_PROPOSE";
    public static final String ACTION_CONFIRM = "COEFFICIENT_CONFIRM";
    public static final String ACTION_REJECT = "COEFFICIENT_REJECT";

    private final CoefficientChangeRequestMapper requestMapper;
    private final ProjectMapper projectMapper;
    /**
     * R11 / A2 修复：propose 预落 leader_id 需按项目主组解析 product_groups.leader_person_id，
     * 与 ContributionService（A3，L342）同源注入范式（ProductGroupMapper 直查，零 REST 契约变更）。
     */
    private final ProductGroupMapper productGroupMapper;
    private final IAuditLogService auditLogService;

    /* ---------- R33 一期：状态机守卫接线收编至 ApprovalGuardSupport（行为零变更） ---------- */
    /** CoefficientChange 实体类型（与 DefaultStateMachineGuard.registerRule 约定一致） */
    static final String COEF_ENTITY_TYPE = "coefficient_change";
    /**
     * R33 一期：审批链共享守卫骨架（组合替代 preCheckGuard/registerPostCommit 六连拷贝）。
     * fail-closed 抛错 + afterCommit 双路径 + 无事务降级语义与旧拷贝逐字等价。
     */
    private final ApprovalGuardSupport guardSupport = new ApprovalGuardSupport(COEF_ENTITY_TYPE);

    @Autowired(required = false)
    public void setStateMachineGuard(StateMachineGuard stateMachineGuard) {
        this.guardSupport.setStateMachineGuard(stateMachineGuard);
    }

    /**
     * 双PM 联合提议（一次提交同时登记双方 ID）。
     *
     * @param projectId   项目
     * @param coefficient 提议系数
     * @param reason      定值理由（必填）
     * @param marketPmId  市场PM
     * @param rdPmId      研发PM
     * @param proposerId  提交人
     * @return 新建申请（PENDING_LEADER）
     */
    @Transactional(rollbackFor = Exception.class)
    public CoefficientChangeRequest propose(Long projectId, BigDecimal coefficient, String reason,
                                            Long marketPmId, Long rdPmId, Long proposerId, IpdActor actor) {
        if (projectId == null || coefficient == null || marketPmId == null || rdPmId == null || proposerId == null
                || actor == null || actor.id() == null) {
            throw new ServiceException("项目、系数、双PM 与提交人不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new ServiceException("S/B 级系数定值理由必填（写审计）");
        }
        if (marketPmId.equals(rdPmId)) {
            throw new ServiceException("联合提议须由市场PM与研发PM两位不同人员");
        }
        Project project = requireProject(projectId);
        // R-NEW CoefficientChange（依赖 A-2 落地的 IpdIdorGuard.assertSameGroupIpd）：
        // ① 同组归属（SUPER_ADMIN 豁免）；② 提交人必须是双 PM 之一或超管代提。
        IpdIdorGuard.assertSameGroupIpd(actor, project.getMainGroupId());
        if (!actor.id().equals(marketPmId) && !actor.id().equals(rdPmId)
                && !"SUPER_ADMIN".equals(actor.role())) {
            throw new IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN,
                "提交人必须是双 PM 之一或超管代提");
        }
        String level = project.getLevel();
        if ("A".equals(level)) {
            throw new ServiceException("A 级为固定 1.0 不可改");
        }
        if (!"S".equals(level) && !"B".equals(level)) {
            throw new ServiceException("仅 S/B 级可走系数定值流程");
        }
        ProjectService.validateCoefficientRange(level, coefficient);
        Long pending = requestMapper.selectCount(new LambdaQueryWrapper<CoefficientChangeRequest>()
            .eq(CoefficientChangeRequest::getProjectId, projectId)
            .eq(CoefficientChangeRequest::getStatus, CoefficientChangeRequest.ST_PENDING_LEADER));
        // R33 一期：在途单唯一预检收编（文案逐字保留）
        guardSupport.assertNoInFlight(pending, "该项目已有待组长确认的系数定值申请");
        // R11 / A2 修复（防回归重接：136ef385 曾落地、被 2a3799d4 merge 取错侧吞掉，本次找回）：
        // propose 时刻预落 leader_id = 项目主组组长（product_groups.leader_person_id）。
        // StrategicChangeAggregator 的 CC- 卡要求 PENDING_LEADER + leader_id 非 NULL 才投递；
        // leaderDecision 回填即转终态离卡——不预落则真活 CC- 卡永不投递（死路 A2）。
        // 解析范式与 A3（ContributionService.saveSelf [R11 A3] 段）同构；但口径按 A1 更严：
        // 解析不到组长 fail-closed 拒绝提议——防工作台 CC- 卡恒空（宁可在入口暴露配置缺失，
        // 不允许产生投不出卡的在途单）。
        Long preLeaderId = null;
        if (project.getMainGroupId() != null) {
            ProductGroup group = productGroupMapper.selectById(project.getMainGroupId());
            if (group != null) {
                preLeaderId = group.getLeaderPersonId();
            }
        }
        if (preLeaderId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "项目主组（mainGroupId=" + project.getMainGroupId() + "）未配置产品组长，"
                    + "系数定值不可提议——请先配置组长（防工作台 CC-卡恒空）");
        }
        CoefficientChangeRequest req = CoefficientChangeRequest.builder()
            .projectId(projectId)
            .proposedCoefficient(coefficient)
            .reason(reason.trim())
            .marketPmId(marketPmId)
            .rdPmId(rdPmId)
            .proposerId(proposerId)
            .leaderId(preLeaderId)
            .status(CoefficientChangeRequest.ST_PENDING_LEADER)
            .build();
        req.setCreateTime(new Date());
        // R24 接线：状态机守卫 preCheck（fail-closed）——创建迁移 INITIAL→PENDING_LEADER|propose。
        guardSupport.preCheck(null, CoefficientChangeRequest.ST_PENDING_LEADER, "propose");
        requestMapper.insert(req);
        // R24 接线：postCommit（事务后）。本规则 crossDomain=false。
        guardSupport.registerPostCommit(null, CoefficientChangeRequest.ST_PENDING_LEADER, "propose", proposerId, req.getId());
        audit(proposerId, ACTION_PROPOSE, req.getId(),
            "project:" + projectId + " coef:" + coefficient + " " + reason.trim());
        return req;
    }

    /**
     * 产品组长确认或驳回；确认后写回项目档案。
     *
     * @param requestId 申请 ID
     * @param leaderId  组长
     * @param approve   true=确认写入；false=驳回
     * @param opinion   意见
     * @return 终态申请
     */
    @Transactional(rollbackFor = Exception.class)
    public CoefficientChangeRequest leaderDecision(Long requestId, Long leaderId, boolean approve, String opinion,
                                                   IpdActor actor) {
        if (leaderId == null) {
            throw new ServiceException("组长不能为空");
        }
        if (actor == null || actor.id() == null) {
            throw new ServiceException("actor 不能为空");
        }
        // R-NEW CoefficientChange：服务内兜底 + 同组归属——Controller 已有 requireLeaderOrAdmin，
        // service 层补强防注解/Catalog 漂移。
        IpdIdorGuard.requireRoleOrSuperAdmin(actor, "GROUP_LEADER");
        CoefficientChangeRequest req = requestMapper.selectById(requestId);
        if (req == null) {
            throw new ServiceException("系数定值申请不存在: " + requestId);
        }
        // R33 一期：终态守卫前置收编（requireFromState，文案逐字保留）
        guardSupport.requireFromState(req.getStatus(), CoefficientChangeRequest.ST_PENDING_LEADER,
            "状态机不匹配：期望 PENDING_LEADER，实际 " + req.getStatus());
        // R11 / A2 修复：actor 必须 = propose 时刻预落的 leader_id 或超管（防组长 A 的单被组长 B 代签，
        // 与 ContributionService.confirm L451-459 / LaunchDateChangeService.secondDecision L188-189 同构口径）。
        // leader_id 为 NULL 的存量行（A2 修复前的历史在途单）不校验，兼容放行照旧回填。
        if (req.getLeaderId() != null
            && !actor.id().equals(req.getLeaderId())
            && !"SUPER_ADMIN".equals(actor.role())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "系数定值确认必须为预落组长（leaderId=" + req.getLeaderId()
                    + "）或超管，当前 actor=" + actor.id() + "/" + actor.role());
        }
        // approve 路径守卫（加载项目 → 同组归属 → 区间校验）保持在任何写库之前——
        // 守卫抛 FORBIDDEN 时申请状态不被污染。
        Project project = null;
        if (approve) {
            project = requireProject(req.getProjectId());
            IpdIdorGuard.assertSameGroupIpd(actor, project.getMainGroupId());
            ProjectService.validateCoefficientRange(project.getLevel(), req.getProposedCoefficient());
        }
        // 系统性梳理-20260909 新②：决策 CAS 化。此前「查状态→内存改→updateById 全量」
        // 存在 TOCTOU：两人并发决策（如一驳一准）都会通过读侧检查，后写覆盖先写，
        // 可造成「项目系数已定值但申请显示已驳回」的账实分离。
        // 改为条件 UPDATE 原子翻转：仅当行仍处 PENDING_LEADER 才生效，未命中即被并发处理。
        Date decidedAt = new Date();
        String decision = approve ? "APPROVE" : "REJECT";
        String targetStatus = approve ? CoefficientChangeRequest.ST_CONFIRMED : CoefficientChangeRequest.ST_REJECTED;
        // R24 接线（双线合并修正）：preCheck 前移至任何写库前（C3 缺陷修复原则：迁移前拦截）。
        // 2026-09-09 双线合并：原 R24 接线把 preCheck 挂在 CAS UPDATE 之后（时序错误）；
        // CAS 前调时 DB 仍处 PENDING_LEADER，preCheck 语义与内存快照一致。
        guardSupport.preCheck(CoefficientChangeRequest.ST_PENDING_LEADER, targetStatus,
            approve ? "leaderApprove" : "leaderReject");
        int updatedRows = requestMapper.update(null, new LambdaUpdateWrapper<CoefficientChangeRequest>()
            .eq(CoefficientChangeRequest::getId, requestId)
            .eq(CoefficientChangeRequest::getStatus, CoefficientChangeRequest.ST_PENDING_LEADER)
            .set(CoefficientChangeRequest::getStatus, targetStatus)
            .set(CoefficientChangeRequest::getLeaderId, leaderId)
            .set(CoefficientChangeRequest::getLeaderDecision, decision)
            .set(CoefficientChangeRequest::getLeaderDecidedAt, decidedAt)
            .set(CoefficientChangeRequest::getLeaderOpinion, opinion)
            // update(null, wrapper) 不触发 BaseEntity 的 INSERT_UPDATE 元填充，簿记字段显式补齐（蜂群复审 P2）
            .set(CoefficientChangeRequest::getUpdateBy, leaderId)
            .set(CoefficientChangeRequest::getUpdateTime, decidedAt));
        // R33 一期：CAS 命中判定收编（未命中即以原文案抛出，文案逐字保留）
        guardSupport.requireCasHit(updatedRows, "状态机不匹配：申请已被并发处理（期望 PENDING_LEADER）");
        req.setStatus(targetStatus);
        req.setLeaderId(leaderId);
        req.setLeaderDecision(decision);
        req.setLeaderDecidedAt(decidedAt);
        req.setLeaderOpinion(opinion);
        // R24 接线：postCommit（事务后）—— CAS 已原子翻转，不再 updateById 双写。
        guardSupport.registerPostCommit(CoefficientChangeRequest.ST_PENDING_LEADER, targetStatus,
            approve ? "leaderApprove" : "leaderReject", leaderId, req.getId());
        if (!approve) {
            audit(leaderId, ACTION_REJECT, req.getId(), opinion);
            return req;
        }
        project.setLevelCoefficient(req.getProposedCoefficient());
        project.setLevelCoefficientReason(req.getReason());
        projectMapper.updateById(project);
        audit(leaderId, ACTION_CONFIRM, req.getId(),
            "project:" + project.getId() + " coef:" + req.getProposedCoefficient());
        return req;
    }

    private Project requireProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException("项目不存在: " + projectId);
        }
        return project;
    }

    private void audit(Long operatorId, String action, Long entityId, String reason) {
        auditLogService.append(operatorId, action, "coefficient_change_requests", entityId, reason);
    }

    /**
     * P1-2：按项目 ID 列系数变更单（{@code projectId=null} 返回全库，按创建时间倒序）。
     * <p>只读事务；{@code currentPersonId} 入参预留审计追踪位（与 controller 端 actor.id() 对齐），
     * 暂不做 IDOR 过滤（项目级查询码已限制为内部角色；projectId 维度由 controller 决定）。
     * 与 RequirementChangeService.listByProject 同型。
     *
     * @param projectId      可选项目 ID 过滤
     * @param currentPersonId 当前会话人 ID（审计追踪位）
     * @return 系数变更单列表
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<CoefficientChangeRequest> listByProject(Long projectId, String currentPersonId) {
        LambdaQueryWrapper<CoefficientChangeRequest> q = new LambdaQueryWrapper<>();
        if (projectId != null) {
            q.eq(CoefficientChangeRequest::getProjectId, projectId);
        }
        q.orderByDesc(CoefficientChangeRequest::getCreateTime);
        return requestMapper.selectList(q);
    }

    /**
     * P1-2：按 ID 取系数变更单详情；id 缺失或不存在直接 fail-fast（service 层兜底，不依赖 controller）。
     *
     * @param id             申请 ID
     * @param currentPersonId 当前会话人 ID（审计追踪位）
     * @return 单条系数变更单
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CoefficientChangeRequest getByIdForReview(Long id, String currentPersonId) {
        if (id == null) {
            throw new ServiceException("系数定值申请 ID 不能为空");
        }
        CoefficientChangeRequest req = requestMapper.selectById(id);
        if (req == null) {
            throw new ServiceException("系数定值申请不存在: " + id);
        }
        return req;
    }
}
