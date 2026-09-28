package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.approval.ApprovalGuardSupport;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.BusinessConfigKeys;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.CertTemplateMapper;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.util.Workdays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 删除审核引擎（BR-DEL / F29：两级差异化审核；G-02 禁止直接物理删除）
 * 状态机：DRAFT → LEADER_REVIEW → ADMIN_REVIEW → DELETED / REJECTED；终态不可逆。
 * 期限：组长 2 工作日（deletion.leaderDeadlineDays）、超管 2 工作日（deletion.adminDeadlineDays），
 *       组长逾期自动升级超管；撤回时限 deletion.withdrawHours=24。
 * P0-6.2：超管通过必须经 {@link DeleteAuditService#approveAndExecute} 原子软删目标行（AC-DEL-02），
 * 禁止仅改申请态为 DELETED。
 */
@Service
@RequiredArgsConstructor
public class DeletionRequestServiceImpl implements IDeletionRequestService {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_LEADER_REVIEW = "LEADER_REVIEW";
    public static final String ST_ADMIN_REVIEW = "ADMIN_REVIEW";
    public static final String ST_DELETED = "DELETED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_WITHDRAWN = "WITHDRAWN";

    private final DeletionRequestMapper deletionRequestMapper;
    private final ISystemConfigService systemConfigService;
    private final IAuditLogService auditLogService;
    private final DeleteAuditService deleteAuditService;
    /**
     * W5-E-2.2（P0 #2）IDOR 修复：删除目标归属解析所需只读 mapper。
     * submit 按 (entityType, entityId) 解析资源归属（owner / 在职 ProjectMember / 所属组组长），
     * leaderDecision 校验组长与目标所属组匹配；均为只读查询，不参与状态机与审计写入。
     */
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;
    private final GateMapper gateMapper;
    private final ProductMapper productMapper;
    private final PersonMapper personMapper;
    /**
     * R217 拍板C 配套：cert_templates 实体存在性校验所需只读 mapper。
     * 可选注入（setter 模式与 stateMachineGuard 同型）：9 参构造的存量测试未注入时该类型跳过存在性判定，
     * 生产 Spring 装配恒注入。
     */
    @Autowired(required = false)
    private CertTemplateMapper certTemplateMapper;

    public void setCertTemplateMapper(CertTemplateMapper certTemplateMapper) {
        this.certTemplateMapper = certTemplateMapper;
    }
    /**
     * R218 卡1（AC-REQ-09）配套：requirements 实体存在性/归属解析所需只读 mapper。
     * 可选注入（与 certTemplateMapper 同型先例）：9 参构造的存量测试未注入时该类型跳过存在性判定，
     * 生产 Spring 装配恒注入。
     */
    @Autowired(required = false)
    private RequirementMapper requirementMapper;

    public void setRequirementMapper(RequirementMapper requirementMapper) {
        this.requirementMapper = requirementMapper;
    }
    /**
     * R221 通知缺口补线（spec §5.1）：submit/驳回/超期升级三处业务节点发 DEL_* 待办通知。
     * 可选注入（setter 同型先例）：存量 9 参构造测试未注入时整体跳过，生产 Spring 装配恒注入。
     */
    @Autowired(required = false)
    private NotificationService notificationService;

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** R221 通知缺口：按目标组/申请人组解析组长（知会收件人）所需只读 mapper，同上可选注入范式。 */
    @Autowired(required = false)
    private ProductGroupMapper productGroupMapper;

    public void setProductGroupMapper(ProductGroupMapper productGroupMapper) {
        this.productGroupMapper = productGroupMapper;
    }
    /** R33 ChainSpec C3：审批链守卫实体类型（与 DefaultStateMachineGuard 规则表锚点一致）。 */
    private static final String ENTITY_TYPE = "deletion_request";

    /**
     * R33 ChainSpec 一期（分片 C3）：审批链共享守卫骨架组合件——收编 preCheckGuard/registerPostCommit
     * 六连拷贝 + 终态守卫 requireFromState（纯组合；API 冻结见 ApprovalGuardSupport）。
     * 决策 CAS requireCasHit 未收敛：escalate 批量 CAS 的 miss 语义为静默短路，与其 miss→抛相反（见该处注）。
     * final + 构造初始化：不进 @RequiredArgsConstructor 参数表，存量 9 参构造测试零改动。
     */
    private final ApprovalGuardSupport guardSupport = new ApprovalGuardSupport(ENTITY_TYPE);

    /** ROOT-R3-P0-1 修复：Spring 注入 StateMachineGuard（R33 起转发 guardSupport，nullable 兼容旧测试） */
    @Autowired(required = false)
    public void setStateMachineGuard(org.ruoyi.ipd.service.StateMachineGuard stateMachineGuard) {
        this.guardSupport.setStateMachineGuard(stateMachineGuard);
    }
    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定提交时刻消除真实时钟摇摆，生产零影响）。
     *  当前仅 submit 路径接入，其余方法的时钟接入按需扩展。
     *  R33 保真注：本 setter 故意不投递 guardSupport.setClock——守卫 postCommit 的 occurredAt
     *  沿用其默认系统时钟，与迁移前 registerPostCommit 内 {@code new java.util.Date()} 逐字等价（行为零变更）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }

    private Date now() {
        return Date.from(clock.instant());
    }
    /** ROOT-R1 P0-7 字面量迁移：删除申请配置（冷静期/升级超时；B-RULE-05 配套）来源 */
    @Autowired(required = false)
    private IBusinessConfigService businessConfigService;

    /**
     * 提交删除申请：存快照、进组长初审、算期限、写审计。
     * <p>W5-E-2.2（P0 #2）IDOR 修复：入口 actor 校验 + 按资源类型归属校验
     * （资源 owner / 在职 ProjectMember / 目标所属组组长，SUPER_ADMIN 豁免）；
     * requesterId 改为服务端权威取 {@code actor.id()}，状态机/期限/审计业务逻辑零改。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeletionRequest submit(IpdActor actor, String entityType, Long entityId, String snapshot, String reason) {
        // W5-E-2.2 件 1.1/1.2：actor 入口校验 + 资源归属校验（修复前任意人可对任意资源发起删除）
        requireAuthenticated(actor);
        requireSubmitTargetAllowed(actor, entityType, entityId);
        Long requesterId = actor.id(); // 服务端权威身份（原参数取值即 controller 的 actor.id()，语义不变）
        DeletionRequest request = DeletionRequest.builder()
            .entityType(entityType)
            .entityId(entityId)
            .entitySnapshot(snapshot)
            .reason(reason)
            .requesterId(requesterId)
            .status(ST_LEADER_REVIEW)
            .leaderDueAt(Workdays.add(now(), leaderDeadlineDays()))
            .build();
        // ⚠️ @Builder 只覆盖本类字段，BaseEntity 的 createTime 须走 setter
        request.setCreateTime(now());
        // ROOT-R3-P0-1：守卫 preCheck（跨域联动合法性校验）—— DRAFT->LEADER_REVIEW 合法
        guardSupport.preCheck("DRAFT", DeletionRequestServiceImpl.ST_LEADER_REVIEW, "submit");
        deletionRequestMapper.insert(request);
        audit(entityType, entityId, requesterId, "DELETE_REQUEST_SUBMIT", request.getId());
        guardSupport.registerPostCommit("DRAFT", DeletionRequestServiceImpl.ST_LEADER_REVIEW, "submit", requesterId, request.getId());
        // R221 通知缺口补线（spec §5.1，AC-DEL-04 DEL_CROSS_GROUP_CC 死账接线）：建单进初审
        // → 知会目标组组长。submit 为 @Transactional，走 publishAfterCommit 防 W1 毒化；
        // 目标组/组长不可解析或服务未装配（存量 9 参测试）时静默跳过，绝不影响建单主链。
        notifyLeaderPendingReview(request, requesterId);
        return request;
    }

    /**
     * 撤回：仅申请人在 withdrawHours 内且未终态。
     * <p>MED-3（2026-09-09 治理轮 R21）：不存在与非本人统一响应文案——原实现「不存在 抛『删除申请不存在: id』/
     * 非本人抛『仅申请人可撤回』」的差分响应可被攻击者用作存在性侧信道探测（枚举 id 区分
     * 「有但非本人」与「无」）。统一为同一文案后不再泄露存在性。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeletionRequest withdraw(Long requestId, Long requesterId) {
        DeletionRequest request = deletionRequestMapper.selectById(requestId);
        if (request == null || !request.getRequesterId().equals(requesterId)) {
            // MED-3：两分支合并同一文案，不区分 404/403，消除存在性侧信道
            throw new ServiceException("撤回失败：申请不存在或非本人发起");
        }
        if (isTerminal(request.getStatus())) {
            throw new ServiceException("已终态，不可撤回");
        }
        int withdrawHours;
        // ROOT-R1 P0-7：先读 IBusinessConfigService.DELETION_ESCALATE_TIMEOUT_HOURS，回退 SystemConfig
        Integer bv = readBusinessInt(BusinessConfigKeys.DELETION_ESCALATE_TIMEOUT_HOURS);
        if (bv != null) {
            withdrawHours = bv;
        } else {
            withdrawHours = systemConfigService.getIntValue("deletion.withdrawHours", 24);
        }
        Date deadline = new Date(request.getCreateTime().getTime() + withdrawHours * 3600_000L);
        if (now().after(deadline)) {
            throw new ServiceException("已超过 " + withdrawHours + " 小时撤回时限");
        }
        // ROOT-R3-P0-1：守卫 preCheck —— *->WITHDRAWN 通配收敛
        // 2026-09-09 C3 缺陷修复：先留存真实 from——此前 setStatus 污染后再取
        // request.getStatus() 传给 postCommit，from 失真成 WITHDRAWN（审计链数据质量问题）
        String fromStatus = request.getStatus();
        guardSupport.preCheck(fromStatus, DeletionRequestServiceImpl.ST_WITHDRAWN, "withdraw");
        request.setStatus(ST_WITHDRAWN);
        deletionRequestMapper.updateById(request);
        audit(request.getEntityType(), request.getEntityId(), requesterId, "DELETE_REQUEST_WITHDRAW", request.getId());
        guardSupport.registerPostCommit(fromStatus, DeletionRequestServiceImpl.ST_WITHDRAWN, "withdraw", requesterId, request.getId());
        return request;
    }

    /**
     * SEC-MED-3 侧信道防御版撤返：所有失败路径统一抛 {@link ApiV1ErrorCode#NOT_FOUND}，
     * 与 404 不存在资源错误码 + 文案 + HTTP 状态完全一致，防止攻击者基于 403/500/200
     * 响应差异推断删除申请存在性 / 所有权 / 状态。
     *
     * <p>失败归一情形：
     * <ul>
     *   <li>申请不存在 → NOT_FOUND「资源不存在」</li>
     *   <li>非本人申请 → NOT_FOUND「资源不存在」（不暴露所有权）</li>
     *   <li>已终态（DELETED / REJECTED / WITHDRAWN）→ NOT_FOUND「资源不存在」</li>
     *   <li>超过 withdrawHours 时限 → NOT_FOUND「资源不存在」（不暴露时限）</li>
     * </ul>
     *
     * <p>成功路径与原 {@link #withdraw} 行为一致：状态机守卫 → 写 WITHDRAWN → 写审计 → 注册 postCommit。
     * 单代码路径设计保证 4 类失败耗时近似，杜绝 timing 侧信道。
     *
     * @param actor     当前操作人（actor.id() 必须等于 request.requesterId；非本人按"不存在"处理）
     * @param requestId 申请 ID
     * @return 撤回后的 DeletionRequest（status=WITHDRAWN）
     * @throws IpdBusinessException {@code NOT_FOUND} 失败归一异常
     */
    @Transactional(rollbackFor = Exception.class)
    public DeletionRequest withdrawIfExistsOrNotFound(IpdActor actor, Long requestId) {
        requireAuthenticated(actor);
        // 单次 selectById 后，所有失败统一 NOT_FOUND（防侧信道：避免差异响应暴露资源状态）
        DeletionRequest request = deletionRequestMapper.selectById(requestId);
        int withdrawHours;
        // ROOT-R1 P0-7：先读 IBusinessConfigService.DELETION_ESCALATE_TIMEOUT_HOURS，回退 SystemConfig
        Integer bv = readBusinessInt(BusinessConfigKeys.DELETION_ESCALATE_TIMEOUT_HOURS);
        if (bv != null) {
            withdrawHours = bv;
        } else {
            withdrawHours = systemConfigService.getIntValue("deletion.withdrawHours", 24);
        }
        if (request == null
            || !actor.id().equals(request.getRequesterId())
            || isTerminal(request.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "资源不存在");
        }
        Date deadline = new Date(request.getCreateTime().getTime() + withdrawHours * 3600_000L);
        if (now().after(deadline)) {
            // 超时限 → 同样 NOT_FOUND 化（不暴露时限长度 / 当前是否在窗口内）
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "资源不存在");
        }
        // ROOT-R3-P0-1：守卫 preCheck —— *->WITHDRAWN 通配收敛
        guardSupport.preCheck(request.getStatus(), DeletionRequestServiceImpl.ST_WITHDRAWN, "withdraw");
        request.setStatus(ST_WITHDRAWN);
        deletionRequestMapper.updateById(request);
        audit(request.getEntityType(), request.getEntityId(), actor.id(), "DELETE_REQUEST_WITHDRAW", request.getId());
        guardSupport.registerPostCommit(request.getStatus(), DeletionRequestServiceImpl.ST_WITHDRAWN, "withdraw", actor.id(), request.getId());
        return request;
    }

    /**
     * 组长初审：APPROVE → 超管终审；REJECT → 终态。
     * <p>W5-E-2.2（P0 #2）IDOR 修复：仅目标所属组组长（GROUP_LEADER 且 groupId 匹配）或
     * SUPER_ADMIN 可初审（修复前任何人可冒充组长审批）；leaderId 改为服务端权威取
     * {@code actor.id()}，状态机/期限/审计业务逻辑零改。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeletionRequest leaderDecision(IpdActor actor, Long requestId, boolean approve, String opinion) {
        // W5-E-2.2 件 1.1/1.3：actor 入口校验 + 组长角色校验（防冒充组长）
        requireAuthenticated(actor);
        if (!"GROUP_LEADER".equals(actor.role()) && !"SUPER_ADMIN".equals(actor.role())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅组长或超管可初审删除申请");
        }
        Long leaderId = actor.id(); // 服务端权威身份
        DeletionRequest request = getOrThrow(requestId);
        requireStatus(request, ST_LEADER_REVIEW);
        // 组长还须是目标所属组组长（超管豁免；目标组不可解析时 fail-closed 拒绝）
        if (!"SUPER_ADMIN".equals(actor.role())) {
            TargetScope scope = resolveScope(request.getEntityType(), request.getEntityId());
            if (scope.groupId() == null || !scope.groupId().equals(actor.groupId())) {
                throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅目标所属组组长可初审删除申请");
            }
        }
        request.setLeaderId(leaderId);
        request.setLeaderDecision(approve ? "APPROVE" : "REJECT");
        request.setLeaderDecidedAt(now());
        String target = approve ? ST_ADMIN_REVIEW : ST_REJECTED;
        String trigger = approve ? "leaderApprove" : "leaderReject";
        // ROOT-R3-P0-1：守卫 preCheck（LEADER_REVIEW -> ADMIN_REVIEW/REJECTED 合法）
        guardSupport.preCheck(DeletionRequestServiceImpl.ST_LEADER_REVIEW, target, trigger);
        request.setStatus(approve ? ST_ADMIN_REVIEW : ST_REJECTED);
        if (approve) {
            request.setAdminDueAt(Workdays.add(now(), adminDeadlineDays()));
        }
        deletionRequestMapper.updateById(request);
        // ROOT-R3-P0-1：postCommit 跨域副作用
        guardSupport.registerPostCommit(DeletionRequestServiceImpl.ST_LEADER_REVIEW, target, trigger, leaderId, request.getId());
        audit(request.getEntityType(), request.getEntityId(), leaderId, approve ? "DELETE_LEADER_APPROVE" : "DELETE_LEADER_REJECT", request.getId());
        // R221 通知缺口补线（AC-DEL-05 DEL_REJECTED 死账接线）：组长驳回 → 申请人收驳回待办；
        // 通过时初审待办已在 submit 发过，终审队列可见不重复发。
        if (!approve && notificationService != null) {
            notificationService.publishAfterCommit(request.getRequesterId(), NotificationService.Types.DEL_REJECTED,
                NotificationService.KIND_ACTION, "deletion_request", request.getId(),
                "删除申请被组长驳回：" + request.getEntityType() + "/" + request.getEntityId(),
                "组长（" + actor.name() + "）驳回了你的删除申请，可修改后重新发起。",
                "/ipd/deletion/my-requests");
        }
        return request;
    }

    /**
     * 超管终审。
     * <p>APPROVE → 委托 {@link DeleteAuditService#approveAndExecute}：申请态 + 目标软删 + 审计同事务；
     * REJECT → 仅标 REJECTED 并写审计（不触碰目标行）。
     * <p>W5-E-2.2（P0 #2）IDOR 修复：仅 SUPER_ADMIN 可终审（修复前任何人可冒充超管审批触发软删）；
     * adminId 改为服务端权威取 {@code actor.id()}，原子软删/审计业务逻辑零改。
     *
     * @param actor     当前操作人（服务端会话身份，须为 SUPER_ADMIN）
     * @param requestId 申请 ID
     * @param approve   true=通过并软删；false=驳回
     * @param opinion   意见（驳回时写入审计 reason 后缀）
     * @return 终态申请
     */
    @Transactional(rollbackFor = Exception.class)
    public DeletionRequest adminDecision(IpdActor actor, Long requestId, boolean approve, String opinion) {
        // W5-E-2.2 件 1.1/1.4：actor 入口校验 + 超管角色校验（防冒充超管审批触发软删）
        requireAuthenticated(actor);
        if (!"SUPER_ADMIN".equals(actor.role())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅超管可终审删除申请");
        }
        Long adminId = actor.id(); // 服务端权威身份
        DeletionRequest request = getOrThrow(requestId);
        requireStatus(request, ST_ADMIN_REVIEW);
        if (approve) {
            // ROOT-R3-P0-1：守卫 preCheck —— ADMIN_REVIEW -> DELETED 合法（跨域→触发原子软删）
            guardSupport.preCheck(DeletionRequestServiceImpl.ST_ADMIN_REVIEW, DeletionRequestServiceImpl.ST_DELETED, "adminApprove");
            // P0-6.2 / AC-DEL-02：必须走原子软删，禁止只改申请态
            DeletionRequest deleted = deleteAuditService.approveAndExecute(requestId, adminId);
            // ROOT-R3-P0-1：postCommit 跨域副作用（事务提交后触发）
            guardSupport.registerPostCommit(DeletionRequestServiceImpl.ST_ADMIN_REVIEW, DeletionRequestServiceImpl.ST_DELETED, "adminApprove", adminId, requestId);
            return deleted;
        }
        // ROOT-R3-P0-1：守卫 preCheck —— ADMIN_REVIEW -> REJECTED 合法
        guardSupport.preCheck(DeletionRequestServiceImpl.ST_ADMIN_REVIEW, DeletionRequestServiceImpl.ST_REJECTED, "adminReject");
        request.setAdminId(adminId);
        request.setAdminDecision("REJECT");
        request.setAdminDecidedAt(now());
        request.setStatus(ST_REJECTED);
        deletionRequestMapper.updateById(request);
        audit(request.getEntityType(), request.getEntityId(), adminId, "DELETE_ADMIN_REJECT", request.getId());
        // 2026-09-09 C3 缺陷修复：R5 adminReject 标 crossDomain=true（跨域→通知申请人），
        // 但此前驳回分支漏调 registerPostCommit → 申请人收不到驳回通知。与 adminApprove(L230) 对齐
        guardSupport.registerPostCommit(DeletionRequestServiceImpl.ST_ADMIN_REVIEW, DeletionRequestServiceImpl.ST_REJECTED, "adminReject", adminId, requestId);
        return request;
    }

    /**
     * 组长逾期升级：LEADER_REVIEW 且 leaderDueAt 已过 → 转 ADMIN_REVIEW；返回升级条数。
     *
     * <p>PERF-P0-1：原实现走 N+1（selectList + 每行 updateById + 每行 audit append ≈ 500 SQL/百条），
     * 现改为单 SQL 条件批量 UPDATE + 补逐条审计（G-02 语义不变）。命中
     * {@code idx_del_status_leader(status, leader_due_at)}，无逾期时短路零额外 SQL 开销。
     *
     * <p>步骤：① selectList 拿受影响行（含 id + entityType/entityId 供 audit 用）→ ② 单 SQL
     * 条件批量 UPDATE（status='ADMIN_REVIEW' AND leader_due_at < now 谓词）→ ③ affected=0
     * 直接返回 0；否则按预取行逐条写 DELETE_LEADER_OVERDUE_ESCALATE 审计（保持 G-02 一行一审）。
     * 事务在单 SQL UPDATE + 逐条 audit 都成功后整体提交。
     */
    @Transactional(rollbackFor = Exception.class)
    public int escalateOverdueLeaderReview() {
        // 步骤 ①：先用同谓词 selectList 拿受影响行的 id / entityType / entityId（供 audit 用）
        List<DeletionRequest> overdue = deletionRequestMapper.selectList(new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getStatus, ST_LEADER_REVIEW)
            .lt(DeletionRequest::getLeaderDueAt, now()));
        if (overdue.isEmpty()) {
            return 0; // affected=0 短路：零 SQL 额外开销
        }
        Date adminDueAt = Workdays.add(now(), adminDeadlineDays());
        // ROOT-R3-P0-1：守卫 preCheck —— LEADER_REVIEW -> ADMIN_REVIEW 合法（升级路径）
        // 2026-09-09 C3 缺陷修复：preCheck 语义是"迁移前拦截"，此前挂在批量 UPDATE 之后——
        // 虽有 @Transactional 兜底回滚，但守卫应前置拒绝而非事后验证。前移到 UPDATE 前
        guardSupport.preCheck(DeletionRequestServiceImpl.ST_LEADER_REVIEW, DeletionRequestServiceImpl.ST_ADMIN_REVIEW, "escalateOverdue");
        // 步骤 ②：单 SQL 条件批量 UPDATE（PERF-P0-1：消除 N+1 写放大）
        int affected = deletionRequestMapper.update(null, new LambdaUpdateWrapper<DeletionRequest>()
            .set(DeletionRequest::getStatus, ST_ADMIN_REVIEW)
            .set(DeletionRequest::getAdminDueAt, adminDueAt)
            .eq(DeletionRequest::getStatus, ST_LEADER_REVIEW)
            .lt(DeletionRequest::getLeaderDueAt, now()));
        if (affected == 0) {
            // 谓词扫描与 UPDATE 之间发生状态变迁（极少见——并发方抢先处置）：同样短路
            // R33 一期注：此处命中判定故意不收敛 requireCasHit——其 miss 语义为抛冲突异常，
            // 与本处 miss→静默短路相反，收敛即行为变更（违反 escalate 语义零触碰红线，见迁移报告）。
            return 0;
        }
        // 步骤 ③：按预取行补逐条审计（G-02 语义不变：每条升级单独留痕，可被审计范围查询到）
        for (DeletionRequest request : overdue) {
            audit(request.getEntityType(), request.getEntityId(), null, "DELETE_LEADER_OVERDUE_ESCALATE", request.getId());
            // R221 通知缺口补线（AC-DEL-07 DEL_REVIEW_OVERDUE 死账接线）：超期升级 →
            // 申请人知会 + 原初审组长提醒（其组可解析时）。同日重复扫描不重发、次日可再提醒，
            // 幂等由 publishDailyAfterCommit 的 dayStamp dedupKey 保证。
            if (notificationService != null) {
                notificationService.publishDailyAfterCommit(request.getRequesterId(),
                    NotificationService.Types.DEL_REVIEW_OVERDUE, NotificationService.KIND_ACTION,
                    "deletion_request", request.getId(),
                    "删除申请超期已升级超管终审：" + request.getEntityType() + "/" + request.getEntityId(),
                    "你的删除申请因组长超期未审，已自动升级超管终审（超管不会自动通过，仅提醒）。",
                    "/ipd/deletion/my-requests", now());
                // 复审修复 W-1（r221-notification-gap）：初审义务人是**目标组**组长（L280 IDOR
                // 校验同口径），非申请人组组长——跨组删除时按申请人组解析会把提醒发给了
                // 无审核义务的组长、真正逾期者反而收不到。改走与 submit 知会一致的 resolveScope 链。
                TargetScope overdueScope = resolveScope(request.getEntityType(), request.getEntityId());
                Long overdueLeaderId = resolveGroupLeader(overdueScope.groupId());
                if (overdueLeaderId != null) {
                    notificationService.publishDailyAfterCommit(overdueLeaderId,
                        NotificationService.Types.DEL_REVIEW_OVERDUE, NotificationService.KIND_ACTION,
                        "deletion_request", request.getId(),
                        "组内删除申请超期已升级超管：" + request.getEntityType() + "/" + request.getEntityId(),
                        "申请人（ID " + request.getRequesterId() + "）的删除申请超过你的审核期限，已自动升级超管终审。",
                        "/ipd/deletion/review", now());
                }
            }
        }
        return affected;
    }

    /** 超管逾期清单（仅提醒，不自动通过——涉删权限保守处理） */
    public List<DeletionRequest> listOverdueAdminReview() {
        return deletionRequestMapper.selectList(new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getStatus, ST_ADMIN_REVIEW)
            .lt(DeletionRequest::getAdminDueAt, now()));
    }

    /**
     * P1-1（R25 真白屏修复）：「我的申请」列表——按申请人 actor.id 过滤的全状态删除申请。
     * <p>设计：server-side 取 {@code requesterId}（防前端伪造）；排除 MyBatis-Plus
     * {@code @TableLogic} 自动加上的 {@code del_flag='1'} 行；按 createTime DESC 让最新申请在前。
     * 申请人本人可见自己发起的全部申请（含 LEADER_REVIEW / ADMIN_REVIEW / REJECTED / WITHDRAWN / DELETED）。
     *
     * @param applicantId 当前会话人 ID
     * @return 申请人发起的删除申请列表
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<DeletionRequest> listByApplicant(Long applicantId) {
        if (applicantId == null) {
            return List.of();
        }
        return deletionRequestMapper.selectList(new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getRequesterId, applicantId)
            .orderByDesc(DeletionRequest::getCreateTime));
    }

    /**
     * P1-1（R25 真白屏修复）：「待我审核」列表——按当前会话人角色分流。
     * <ul>
     *   <li>{@code SUPER_ADMIN} → {@code ADMIN_REVIEW}（终审待办；超管可兼任初审，
     *       但「待我审核」列表先聚焦终审队列，避免与组长视角的初审重叠）</li>
     *   <li>{@code GROUP_LEADER} → {@code LEADER_REVIEW}（初审待办）</li>
     *   <li>{@code MARKET_PM / RD_PM} → 空集（不持审核权，不应承担审核入口）</li>
     * </ul>
     * 设计：actor 从 controller 透传，service 内做角色→状态映射，避免 controller 出现
     * 「角色→状态」散落硬编码；按 createTime DESC 让最新申请在前。
     *
     * @param actor 当前会话人
     * @return 待当前人审核的删除申请列表
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<DeletionRequest> listForReview(IpdActor actor) {
        if (actor == null || actor.role() == null) {
            return List.of();
        }
        switch (actor.role()) {
            case "SUPER_ADMIN":
                return deletionRequestMapper.selectList(new LambdaQueryWrapper<DeletionRequest>()
                    .eq(DeletionRequest::getStatus, ST_ADMIN_REVIEW)
                    .orderByDesc(DeletionRequest::getCreateTime));
            case "GROUP_LEADER":
                return deletionRequestMapper.selectList(new LambdaQueryWrapper<DeletionRequest>()
                    .eq(DeletionRequest::getStatus, ST_LEADER_REVIEW)
                    .orderByDesc(DeletionRequest::getCreateTime));
            default:
                // MARKET_PM / RD_PM / 其他：不持审核权，返回空集
                return List.of();
        }
    }


    private DeletionRequest getOrThrow(Long id) {
        DeletionRequest request = deletionRequestMapper.selectById(id);
        if (request == null) {
            throw new ServiceException("删除申请不存在: " + id);
        }
        return request;
    }

    /**
     * R221 通知缺口：submit 后知会目标组组长待初审（AC-DEL-04 语义：组长对组内删除事项知会/待办）。
     * 服务/mapper 未装配、目标组不可解析（如 cert_templates 全局参考数据）、组长未配置或
     * 组长即申请人本人 → 均静默跳过，绝不影响建单主链。
     */
    private void notifyLeaderPendingReview(DeletionRequest request, Long requesterId) {
        if (notificationService == null || productGroupMapper == null) {
            return;
        }
        TargetScope scope = resolveScope(request.getEntityType(), request.getEntityId());
        Long leaderPersonId = resolveGroupLeader(scope.groupId());
        if (leaderPersonId == null || leaderPersonId.equals(requesterId)) {
            return;
        }
        notificationService.publishAfterCommit(leaderPersonId, NotificationService.Types.DEL_CROSS_GROUP_CC,
            NotificationService.KIND_ACTION, "deletion_request", request.getId(),
            "删除申请待初审：" + request.getEntityType() + "/" + request.getEntityId(),
            "你所属组收到删除申请（申请人 ID " + requesterId + "），请在审核期限内处置。",
            "/ipd/deletion/review");
    }

    /** 按组 ID 解析组长 personId（mapper 未装配/组未配置组长 → null，调用方自行跳过）。 */
    private Long resolveGroupLeader(Long groupId) {
        if (productGroupMapper == null || groupId == null) {
            return null;
        }
        ProductGroup group = productGroupMapper.selectById(groupId);
        return group == null ? null : group.getLeaderPersonId();
    }

    private void requireStatus(DeletionRequest request, String expect) {
        // R33 一期：命中判定收敛 ApprovalGuardSupport.requireFromState（ServiceException 类型与文案逐字保真）
        guardSupport.requireFromState(request.getStatus(), expect,
            "状态机不匹配：期望 " + expect + "，实际 " + request.getStatus());
    }

    private boolean isTerminal(String status) {
        return ST_DELETED.equals(status) || ST_REJECTED.equals(status) || ST_WITHDRAWN.equals(status);
    }

    // ===== W5-E-2.2（P0 #2）IDOR 修复：actor 入口 + 资源归属 + 审批角色校验 =====

    /** 与 DeleteAuditService 软删执行器注册表一致的 entity_type 白名单（未知类型 fail-closed）。
     *  R218 卡1（看板 e256007b / AC-REQ-09）：补 requirements——需求池删除强制双层审核，
     *  执行器见 RequirementSoftDeleteExecutor（Spring List 注入自动注册，两侧须同步扩表防漂移）。 */
    private static final Set<String> SUPPORTED_ENTITY_TYPES =
        Set.of("projects", "products", "persons", "cert_templates", "gates", "requirements");

    /** actor 入口校验——service 层不信任 controller 必传（防御性兜底）。actor == null 或 id == null → UNAUTHORIZED。 */
    private static void requireAuthenticated(IpdActor actor) {
        if (actor == null || actor.id() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.UNAUTHORIZED, "未登录");
        }
    }

    /** 删除目标归属范围：所属项目（在职成员判定）+ 所属组（组长判定），均可空（null=不可解析，不放行）。 */
    private record TargetScope(Long projectId, Long groupId) { }

    /**
     * W5-E-2.2 件 1.2：submit 资源归属校验——actor 必须是资源 owner（persons 本人）、
     * 该项目在职 ProjectMember、或目标所属组组长三者之一；SUPER_ADMIN 豁免；
     * 缺参/未知 entity_type fail-closed PARAM_INVALID。
     */
    private void requireSubmitTargetAllowed(IpdActor actor, String entityType, Long entityId) {
        if (entityType == null || entityType.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "entityType 不能为空");
        }
        if (entityId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "entityId 不能为空");
        }
        if (!SUPPORTED_ENTITY_TYPES.contains(entityType)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "不支持的 entity_type: " + entityType);
        }
        if ("SUPER_ADMIN".equals(actor.role())) {
            // R217 拍板C 配套（dangling-fk-survey.md §1.7/§3.1 根因修复）：超管豁免资源归属校验，
            // 但实体存在性必查——此前超管可对任意不存在的 entity_id 建删除申请（deletion_requests 5 僵尸行根因）
            if (!entityExists(entityType, entityId)) {
                throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND,
                    "目标实体不存在: " + entityType + "/" + entityId);
            }
            return;
        }
        TargetScope scope = resolveScope(entityType, entityId);
        // ① 资源 owner：persons 允许本人对本人记录发起
        if ("persons".equals(entityType) && actor.id().equals(entityId)) {
            return;
        }
        // ② 在职 ProjectMember（projects 直判；gates/products 经所属 projectId 解析；exitDate 非空视为已退出）
        if (scope.projectId() != null && isActiveProjectMember(actor.id(), scope.projectId())) {
            return;
        }
        // ③ 目标所属组组长（组长对本组项目/产品/成员发起，与 PersonService「GROUP_LEADER 仅可操作本组员工」同口径）
        if (scope.groupId() != null && "GROUP_LEADER".equals(actor.role()) && scope.groupId().equals(actor.groupId())) {
            return;
        }
        throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅资源 owner / 在职项目成员 / 所属组组长可发起删除申请");
    }

    /** 在职项目成员判定（personId + projectId 匹配且 exitDate IS NULL，与 KpiSharedCollectionService 同口径）。 */
    private boolean isActiveProjectMember(Long personId, Long projectId) {
        return projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getPersonId, personId)
            .isNull(ProjectMember::getExitDate)) > 0;
    }

    /**
     * W5-E-2.2：按资源类型解析删除目标归属（projectId/groupId 均可空；目标行缺失时对应维度为 null，调用方 fail-closed）。
     * projects → 本体即项目；gates → 经 projectId 上溯项目主组；products → 直取所属项目/组；
     * persons → 所属组；cert_templates → 组织级全局参考数据，无组/项目归属（仅 SUPER_ADMIN 可发起）；
     * requirements → 经 productId 上溯所属产品的 projectId/groupId（R218 卡1，AC-REQ-09）。
     */
    private TargetScope resolveScope(String entityType, Long entityId) {
        switch (entityType) {
            case "projects": {
                Project project = projectMapper.selectById(entityId);
                return new TargetScope(entityId, project == null ? null : project.getMainGroupId());
            }
            case "gates": {
                Gate gate = gateMapper.selectById(entityId);
                if (gate == null || gate.getProjectId() == null) {
                    return new TargetScope(null, null);
                }
                Project project = projectMapper.selectById(gate.getProjectId());
                return new TargetScope(gate.getProjectId(), project == null ? null : project.getMainGroupId());
            }
            case "products": {
                Product product = productMapper.selectById(entityId);
                if (product == null) {
                    return new TargetScope(null, null);
                }
                return new TargetScope(product.getProjectId(), product.getGroupId());
            }
            case "persons": {
                Person person = personMapper.selectById(entityId);
                return new TargetScope(null, person == null ? null : person.getGroupId());
            }
            case "requirements": {
                // R218 卡1：需求经 productId 上溯所属产品的项目/组归属（与 products 分支同口径）；
                // 「其他/不确定」需求 productId=NULL → scope 不可解析，非超管 fail-closed 拒绝
                if (requirementMapper == null) {
                    return new TargetScope(null, null);
                }
                Requirement requirement = requirementMapper.selectById(entityId);
                if (requirement == null) {
                    return new TargetScope(null, null);
                }
                Product product = requirement.getProductId() == null
                    ? null : productMapper.selectById(requirement.getProductId());
                Long projectId = product != null ? product.getProjectId() : requirement.getProjectId();
                return new TargetScope(projectId, product == null ? null : product.getGroupId());
            }
            default:
                return new TargetScope(null, null);
        }
    }

    /**
     * R217 拍板C 配套：按 entity_type 判定目标实体是否存在（仅复用本文件既有只读 mapper，
     * 与 {@link #resolveScope} 同型 switch 分发；软删实体经 @TableLogic 过滤后 selectById 返回 null，视为不存在）。
     *
     * <p>cert_templates 走可选注入的 {@link #certTemplateMapper}：未注入（旧测试 9 参构造）时放行，
     * 与本文件 stateMachineGuard==null 兼容先例同口径；其余四类为 final 构造注入，必查。
     */
    private boolean entityExists(String entityType, Long entityId) {
        switch (entityType) {
            case "projects":
                return projectMapper.selectById(entityId) != null;
            case "gates":
                return gateMapper.selectById(entityId) != null;
            case "products":
                return productMapper.selectById(entityId) != null;
            case "persons":
                return personMapper.selectById(entityId) != null;
            case "cert_templates":
                return certTemplateMapper == null || certTemplateMapper.selectById(entityId) != null;
            case "requirements":
                // R218 卡1：未注入（旧 9 参构造测试）时放行，与 cert_templates 先例同口径；
                // 软删行经 @TableLogic 过滤后 selectById 返回 null → 视为不存在
                return requirementMapper == null || requirementMapper.selectById(entityId) != null;
            default:
                return false; // 白名单外类型 fail-closed（理论上已被 SUPPORTED_ENTITY_TYPES 拦截）
        }
    }

    private int leaderDeadlineDays() {
        // ROOT-R1 P0-7：先读 IBusinessConfigService.DELETION_COOLDOWN_DAYS，回退 SystemConfig
        Integer v = readBusinessInt(BusinessConfigKeys.DELETION_COOLDOWN_DAYS);
        if (v != null) return v;
        return systemConfigService.getIntValue("deletion.leaderDeadlineDays", 2);
    }

    private int adminDeadlineDays() {
        // ROOT-R1 P0-7：先读 IBusinessConfigService.DELETION_COOLDOWN_DAYS，回退 SystemConfig
        Integer v = readBusinessInt(BusinessConfigKeys.DELETION_COOLDOWN_DAYS);
        if (v != null) return v;
        return systemConfigService.getIntValue("deletion.adminDeadlineDays", 2);
    }

    /**
     * ROOT-R1 P0-7：读 IBusinessConfigService 整数；未注入或抛错返回 null（让调用方走 SystemConfig 回退）。
     */
    private Integer readBusinessInt(String key) {
        if (businessConfigService == null) return null;
        try {
            return businessConfigService.getInt(key);
        } catch (Exception ex) {
            return null;
        }
    }

    private void audit(String entityType, Long entityId, Long operatorId, String action, Long requestId) {
        // R239 消重：委托 append(Long,...) 共享重载；createTime 由注入 Clock 回归真实写入时刻（同 ProjectService 注）。
        auditLogService.append(operatorId, action, entityType, entityId, "deletion_request:" + requestId);
    }

}
