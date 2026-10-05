package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.BidResponse;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.mapper.BidResponseMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 招标单服务（P2-3.1 BR-TEAM-03/05）
 * 状态机：OPEN → SELECTED / EXPIRED → CLOSED
 * 删除走 IDeletionRequestService + DeleteAuditService（P0-6.2 软删除）
 */
@Service
public class BidInvitationService {

    private final BidInvitationMapper bidInvitationMapper;
    private final BidResponseMapper bidResponseMapper;
    private final IAuditLogService auditLogService;
    private final NotificationService notificationService;
    private ProjectMemberMapper projectMemberMapper;

    /** 遴选后把中标研发 PM 写入既有项目成员。测试构造器可不注入。 */
    @Autowired(required = false)
    public void setProjectMemberMapper(ProjectMemberMapper projectMemberMapper) {
        this.projectMemberMapper = projectMemberMapper;
    }

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();
    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }
    private Date now() { return Date.from(clock.instant()); }

    public BidInvitationService(BidInvitationMapper bidInvitationMapper,
                                 BidResponseMapper bidResponseMapper,
                                 IAuditLogService auditLogService) {
        this(bidInvitationMapper, bidResponseMapper, auditLogService, null);
    }

    /**
     * Spring 装配入口：双构造器并存时必须显式标注，否则容器无法透型尝试无参构造
     */
    @Autowired
    public BidInvitationService(BidInvitationMapper bidInvitationMapper,
                                 BidResponseMapper bidResponseMapper,
                                 IAuditLogService auditLogService,
                                 NotificationService notificationService) {
        this.bidInvitationMapper = bidInvitationMapper;
        this.bidResponseMapper = bidResponseMapper;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    /**
     * 创建招标单（市场PM）
     * AC-TEAM-03：市场PM 发起招标 ⇒ 招标单状态 OPEN
     */
    @Transactional(rollbackFor = Exception.class)
    public BidInvitation create(BidInvitation invitation) {
        // D-1 接线：INITIAL->OPEN|create 守卫
        preCheckGuard(BID_INVITATION_ENTITY_TYPE, null, "OPEN", "create");
        invitation.setStatus("OPEN");
        invitation.setCreateTime(now());
        bidInvitationMapper.insert(invitation);
        registerPostCommit(BID_INVITATION_ENTITY_TYPE, null, "OPEN", "create", invitation.getCreateBy(), invitation.getId());
        return invitation;
    }

    /**
     * 发布招标单（通知目标研发PM 或全员）
     */
    @Transactional(rollbackFor = Exception.class)
    public BidInvitation publish(Long id) {
        BidInvitation inv = requireOpen(id);
        // 通知逻辑由 OPS-04/OPS-05 消息服务承接，此处仅状态校验
        return inv;
    }

    /**
     * P1-5.2：生成 6 字符 confirmToken（去歧义字符集：去掉 0/O/1/I/L 等易混字符）。
     */
    private static final char[] CONFIRM_TOKEN_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom CONFIRM_TOKEN_RNG = new SecureRandom();
    private static final long CONFIRM_TOKEN_TTL_MS = 24L * 3600 * 1000;
    public static String newConfirmToken() {
        char[] buf = new char[6];
        for (int i = 0; i < 6; i++) {
            buf[i] = CONFIRM_TOKEN_ALPHABET[CONFIRM_TOKEN_RNG.nextInt(CONFIRM_TOKEN_ALPHABET.length)];
        }
        return new String(buf);
    }

    /**
     * P1-5.2：预演生成 confirmToken（不写库，落库只在真正 selectResponse 时写）。
     * 调 /select 时须带上同 token，前端先展示「将向 N 名应标者发送落选通知」预演。
     *
     * @return token + expiresAt
     */
    public record ConfirmTokenView(String token, java.util.Date expiresAt) {}
    public ConfirmTokenView issueConfirmToken(Long invitationId) {
        BidInvitation inv = requireOpen(invitationId);
        String token = newConfirmToken();
        Date expires = new Date(now().getTime() + CONFIRM_TOKEN_TTL_MS);
        // 落库：confirm_token 与 expires_at 一起写；状态机不变（保持 OPEN）
        inv.setConfirmToken(token);
        inv.setConfirmTokenExpires(expires);
        bidInvitationMapper.updateById(inv);
        return new ConfirmTokenView(token, expires);
    }

    /**
     * 遴选应标（AC-TEAM-05，P2-3.2 原子提交）：
     * 单事务内选定中标行（回填 rd_pm_id 并置 ACCEPTED）、其余 PENDING 行批量置 REJECTED（落选）、招标单置 SELECTED；
     * 遴选结果写审计（entityType=bid_invitation，action=select，afterData 含中标者与落选者清单——
     * OPS-05 通知系统就绪前由审计行承载“落选通知可查”留痕）。
     */
    /**
     * P1-5.2：3 参兼容入口（仅测试 / 老调用方）—— 不校验 confirmToken。
     *
     * <p>2026-10-03 安全收口：本入口不再经由 4 参入口转发。原实现让 4 参入口在收到
     * 字符串哨兵 {@code "__BACKCOMPAT__"} 时跳过校验，而 4 参入口同时就是 HTTP 端点
     * （{@code BidController#selectResponse} 的 {@code @RequestParam String confirmToken}）——
     * 于是<b>校验开关由外部输入决定</b>：任何调用方把该字符串原样塞进请求参数，就能跳过
     * 缺失 / 不匹配 / 过期三道检查。现改为两个入口各自独立：4 参入口<b>永远</b>校验，
     * 3 参入口直接进主体，字符串不再参与任何分支判断。
     */
    public BidInvitation selectResponse(Long invitationId, Long responseId, Long operatorId) {
        return doSelectResponse(requireOpen(invitationId), responseId, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public BidInvitation selectResponse(Long invitationId, Long responseId, String confirmToken, Long operatorId) {
        BidInvitation inv = requireOpen(invitationId);
        assertConfirmToken(inv, confirmToken);
        return doSelectResponse(inv, responseId, operatorId);
    }

    /** P1-5.2：confirmToken 校验（防误点击 / CSRF；24h 过期）。无任何可绕过的旁路。 */
    private void assertConfirmToken(BidInvitation inv, String confirmToken) {
        if (confirmToken == null || confirmToken.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "缺少 confirmToken（前端须先调 /pre-select-token）");
        }
        if (inv.getConfirmToken() == null || !inv.getConfirmToken().equals(confirmToken)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "confirmToken 不匹配，请重新拉取预演");
        }
        if (inv.getConfirmTokenExpires() == null || inv.getConfirmTokenExpires().before(now())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "confirmToken 已过期（24h），请重新拉取预演");
        }
    }

    /**
     * 遴选主体（不含 confirmToken 校验）：两个入口共用。
     *
     * <p>事务语义与收口前一致：4 参入口带 {@code @Transactional}，主体在其事务内执行；
     * 3 参入口原本经自调用（{@code this.} 转发）到达 4 参方法，Spring 代理不介入、
     * 事实上无事务，此处直接调用私有方法，保持同一语义，行为不变。
     */
    private BidInvitation doSelectResponse(BidInvitation inv, Long responseId, Long operatorId) {
        Long invitationId = inv.getId();
        BidResponse resp = bidResponseMapper.selectById(responseId);
        if (resp == null || !resp.getInvitationId().equals(invitationId)) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "应标记录不存在或不属于该招标单");
        }
        if (!"PENDING".equals(resp.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "应标记录状态不允许遴选: " + resp.getStatus());
        }
        // AC-TEAM-05：中标行回填 rd_pm_id（列语义：应标时可为空，遴选后回填）
        if (resp.getRdPmId() == null) {
            // STATE_CONFLICT 而非 ROLE_LOCKED：缺 rd_pm_id 是业务数据不完整（非角色被锁定）；ROLE_LOCKED 专属"市场PM 不可跨研发PM 动作"语义
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "中标应标行缺少研发PM身份，无法绑定");
        }
        // D-1 接线：三条迁移守卫（bid_response 两条 + bid_invitation 一条；落选批量 UPDATE 旁路按迁移为单位 preCheck 一次，DeletionRequestServiceImpl:386 口径）
        preCheckGuard(BID_INVITATION_ENTITY_TYPE, "OPEN", "SELECTED", "select");
        preCheckGuard(BID_RESPONSE_ENTITY_TYPE, "PENDING", "ACCEPTED", "select");
        preCheckGuard(BID_RESPONSE_ENTITY_TYPE, "PENDING", "REJECTED", "select");
        resp.setStatus("ACCEPTED");
        bidResponseMapper.updateById(resp);
        // 落选：同单其余 PENDING 行单 SQL 批量置 REJECTED（避免逐行写放大）
        List<BidResponse> losers = bidResponseMapper.selectList(new LambdaQueryWrapper<BidResponse>()
            .eq(BidResponse::getInvitationId, invitationId)
            .eq(BidResponse::getStatus, "PENDING")
            .ne(BidResponse::getId, responseId));
        String rejectedRdPmIds = losers.stream()
            .map(r -> String.valueOf(r.getRdPmId() == null ? r.getId() : r.getRdPmId()))
            .collect(Collectors.joining(","));
        bidResponseMapper.update(null, new LambdaUpdateWrapper<BidResponse>()
            .set(BidResponse::getStatus, "REJECTED")
            .eq(BidResponse::getInvitationId, invitationId)
            .eq(BidResponse::getStatus, "PENDING")
            .ne(BidResponse::getId, responseId));
        inv.setStatus("SELECTED");
        inv.setSelectedResponseId(responseId);
        bindSelectedRdPm(inv.getProjectId(), resp.getRdPmId());
        inv.setConfirmToken(null);
        inv.setConfirmTokenExpires(null);
        bidInvitationMapper.updateById(inv);
        registerPostCommit(BID_INVITATION_ENTITY_TYPE, "OPEN", "SELECTED", "select", operatorId, invitationId);
        registerPostCommit(BID_RESPONSE_ENTITY_TYPE, "PENDING", "ACCEPTED", "select", operatorId, responseId);
        if (!losers.isEmpty()) {
            // 批量落选：以迁移为单位登记一次 postCommit（实体锚=招标单）
            registerPostCommit(BID_RESPONSE_ENTITY_TYPE, "PENDING", "REJECTED", "select", operatorId, invitationId);
        }
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId).action("select").entityType("bid_invitation").entityId(invitationId)
            .afterData(AuditEventData.json(
                "selectedResponseId", responseId,
                "selectedRdPmId", resp.getRdPmId(),
                "rejectedRdPmIds", rejectedRdPmIds))
            .reason(inv.getTitle())
            .createTime(now()).build());
        // HIGH-1.2 落选通知：镜像 adminAssign 行 332-355 模式
        // 中标者 ⇒ BID_WON；其余落选 PENDING ⇒ BID_LOST（dedupKey 幂等，重复不重发）
        // selectResponse 为 @Transactional，走 publishAfterCommit：宿主回滚（如 CAS/守卫判定失败）时
        // 中标与落选通知均不发出，避免投标人收到与库内状态不符的「你中标了/你落选了」。
        if (notificationService != null) {
            if (resp.getRdPmId() != null) {
                notificationService.publishAfterCommit(resp.getRdPmId(),
                    NotificationService.Types.BID_WON,
                    NotificationService.KIND_ACTION,
                    "bid_invitation", invitationId,
                    "招标已遴选您",
                    "招标单「" + inv.getTitle() + "」(项目 " + invitationId + ") 已遴选您为中标研发PM，请尽快承接。",
                    "/bid-invitations/" + invitationId);
            }
            for (BidResponse loser : losers) {
                if (loser.getRdPmId() == null) continue;
                notificationService.publishAfterCommit(loser.getRdPmId(),
                    NotificationService.Types.BID_LOST,
                    NotificationService.KIND_ACTION,
                    "bid_invitation", invitationId,
                    "招标落选通知",
                    "招标单「" + inv.getTitle() + "」(项目 " + invitationId + ") 已遴选他人，您本次落选。",
                    "/bid-invitations/" + invitationId);
            }
        }
        return inv;
    }

    /**
     * 招标单已挂在既有项目上。遴选不另建项目，把中标研发 PM 写入该项目成员。
     * 已有其他在职研发 PM 时拒绝覆盖。
     */
    private void bindSelectedRdPm(Long projectId, Long rdPmId) {
        if (projectMemberMapper == null || projectId == null || rdPmId == null) {
            return;
        }
        Long same = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getRole, "RD_PM")
            .eq(ProjectMember::getPersonId, rdPmId)
            .isNull(ProjectMember::getExitDate));
        if (same != null && same > 0) {
            return;
        }
        Long others = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getRole, "RD_PM")
            .isNull(ProjectMember::getExitDate));
        if (others != null && others > 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目已有在职研发PM，遴选不能覆盖");
        }
        projectMemberMapper.insert(ProjectMember.builder()
            .projectId(projectId)
            .personId(rdPmId)
            .role("RD_PM")
            .joinDate(now())
            .bonusEligible("1")
            .build());
    }

    /**
     * 过期扫描（定时任务，PERF-P0-2：单 SQL 条件 UPDATE，消除 N+1 selectCount 与恒等三元冗余）
     * AC-TEAM-08：招标到期无人应标 ⇒ 自动过期
     * ZK-IPD §四.1.3：到期后通知市场 PM（createBy）重新发起
     */
    @Transactional(rollbackFor = Exception.class)
    public int expireOverdue() {
        // ZK-IPD §四.1.3：先 selectList 拿受影响行（id + createBy），update 完发通知
        List<BidInvitation> overdue = bidInvitationMapper.selectList(
            new LambdaQueryWrapper<BidInvitation>()
                .eq(BidInvitation::getStatus, "OPEN")
                .lt(BidInvitation::getExpireAt, now()));
        // D-1 接线：批量 UPDATE 旁路以迁移为单位 preCheck 一次（定时任务无操作人 operatorId=null）
        if (!overdue.isEmpty()) {
            preCheckGuard(BID_INVITATION_ENTITY_TYPE, "OPEN", "EXPIRED", "expire");
        }
        int affected = bidInvitationMapper.update(null, new LambdaUpdateWrapper<BidInvitation>()
            .set(BidInvitation::getStatus, "EXPIRED")
            .eq(BidInvitation::getStatus, "OPEN")
            .lt(BidInvitation::getExpireAt, now()));
        if (affected > 0 && notificationService != null) {
            for (BidInvitation inv : overdue) {
                if (inv.getCreateBy() == null) continue;
                // expireOverdue 为 @Transactional：走 publishAfterCommit 延迟到批量置 EXPIRED 提交后发送，
                // 回滚时创建人不会收到「招标已到期」的假提醒。
                notificationService.publishAfterCommit(inv.getCreateBy(),
                    NotificationService.Types.BID_EXPIRED_NO_RESPONSE,
                    NotificationService.KIND_ACTION,
                    "bid_invitation", inv.getId(),
                    "招标已到期",
                    "招标单「" + inv.getTitle() + "」已到期且无应标，请考虑重新发起或调整条件（ZK-IPD §四.1.3）",
                    "/bid-invitations/" + inv.getId());
            }
        }
        if (affected > 0) {
            registerPostCommit(BID_INVITATION_ENTITY_TYPE, "OPEN", "EXPIRED", "expire", null,
                overdue.isEmpty() ? null : overdue.get(0).getId());
        }
        return affected;
    }

    /**
     * 撤回招标单（24h 内可撤回，AC-TEAM-13）
     */
    @Transactional(rollbackFor = Exception.class)
    public BidInvitation withdraw(Long id) {
        BidInvitation inv = requireOpen(id);
        long millisSinceCreate = now().getTime() - inv.getCreateTime().getTime();
        if (millisSinceCreate > 24 * 60 * 60 * 1000L) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "超过24小时不可撤回");
        }
        // D-1 接线：OPEN->CLOSED|withdraw 守卫（requireOpen 已保证 OPEN）
        preCheckGuard(BID_INVITATION_ENTITY_TYPE, "OPEN", "CLOSED", "withdraw");
        inv.setStatus("CLOSED");
        bidInvitationMapper.updateById(inv);
        registerPostCommit(BID_INVITATION_ENTITY_TYPE, "OPEN", "CLOSED", "withdraw", null, id);
        return inv;
    }

    /**
     * 关闭招标单
     */
    @Transactional(rollbackFor = Exception.class)
    public BidInvitation close(Long id) {
        BidInvitation inv = bidInvitationMapper.selectById(id);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "招标单不存在: " + id);
        }
        // D-1 接线：*->CLOSED|close 通配终态收敛守卫（close() 无状态门禁宽进语义保持）
        String guardFrom = inv.getStatus();
        preCheckGuard(BID_INVITATION_ENTITY_TYPE, guardFrom, "CLOSED", "close");
        inv.setStatus("CLOSED");
        bidInvitationMapper.updateById(inv);
        registerPostCommit(BID_INVITATION_ENTITY_TYPE, guardFrom, "CLOSED", "close", null, id);
        return inv;
    }

    /**
     * 分页查询招标单列表
     */
    public IPage<BidInvitation> page(IpdActor actor, int pageNo, int pageSize, Long projectId, String status) {
        Page<BidInvitation> page = new Page<>(pageNo, Math.min(pageSize, 200));
        LambdaQueryWrapper<BidInvitation> qw = new LambdaQueryWrapper<BidInvitation>()
            .eq(projectId != null, BidInvitation::getProjectId, projectId)
            .eq(status != null && !status.isBlank(), BidInvitation::getStatus, status)
            .orderByDesc(BidInvitation::getCreateTime);
        IpdIdorGuard.requireAuthenticated(actor);
        if (!isSuperAdmin(actor)) {
            // 可见性谓词与 visibleTo(...) 同源，两处必须一起改（读详情走 visibleTo，列表走这里）。
            // and(...) 包一层，避免 or 把上面的 projectId/status 过滤短路掉。
            Long actorId = actor.id();
            qw.and(w -> w.eq(BidInvitation::getCreateBy, actorId)
                .or().eq(BidInvitation::getTargetPersonId, actorId)
                .or().eq(BidInvitation::getMode, MODE_PUBLIC));
        }
        return bidInvitationMapper.selectPage(page, qw);
    }

    /**
     * 查询招标单详情
     */
    public BidInvitation getById(Long id) {
        BidInvitation inv = bidInvitationMapper.selectById(id);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "招标单不存在: " + id);
        }
        return inv;
    }

    /**
     * 按可见性取招标单详情（**读端点专用**，不动 {@link #getById}——后者还被 8 个写端点共用）。
     *
     * @throws IpdBusinessException {@code NOT_FOUND} 记录不存在；{@code UNAUTHORIZED} actor 缺失；
     *                              {@code FORBIDDEN} 存在但本人无权看
     */
    public BidInvitation getVisibleTo(Long id, IpdActor actor) {
        IpdIdorGuard.requireAuthenticated(actor);
        BidInvitation inv = getById(id);
        if (!visibleTo(inv, actor)) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权查看该招标单");
        }
        return inv;
    }

    /**
     * 一对一邀标的可见性口径。
     *
     * <p>验收清单 AC-TEAM-01 原文：「市场PM 发起一对一邀标给研发PM A | A 收到通知；
     * <b>其他研发PM 看不到该招标单</b>」。故可见＝发起人 ∪ 受邀人 ∪ 超管；PUBLIC 模式的招标单
     * 本就是公开征集，全员可见。此前两个读端点只做「是不是内部人」，等于对定向邀标不设防。
     */
    private static boolean visibleTo(BidInvitation inv, IpdActor actor) {
        if (isSuperAdmin(actor)) {
            return true;
        }
        if (inv.getCreateBy() != null && inv.getCreateBy().equals(actor.id())) {
            return true;
        }
        if (inv.getTargetPersonId() != null && inv.getTargetPersonId().equals(actor.id())) {
            return true;
        }
        return MODE_PUBLIC.equals(inv.getMode());
    }

    private static boolean isSuperAdmin(IpdActor actor) {
        return actor != null && ROLE_SUPER_ADMIN.equals(actor.role());
    }

    /**
     * 查询招标单下的应标列表（P2-3.2 隐私 + MEDIUM-2.2 公开招标应标者互见）。
     *
     * <ul>
     *   <li>ONE_TO_ONE 模式：非发起人仅见本人应标（不变）</li>
     *   <li>PUBLIC 模式：所有 PENDING/ACCEPTED 记录对全员可见（WITHDRAWN/REJECTED 仅发起人或本人见）</li>
     *   <li>PUBLIC 模式下非发起人视角脱敏：解决方案摘要仅展示前 80 字符（避免互抄）</li>
     * </ul>
     *
     * @param invitationId    招标单 ID
     * @param currentPersonId 会话用户 ID；等于发起人（createBy）时返回全量（含 WITHDRAWN/REJECTED）
     *
     * <p>PERF-P1-2 兼容保留：不带分页，调用方需注意应标量过大时内存压力。
     * 新代码请优先用 {@link #listResponsesPaged(Long, Long, Integer, Integer)}。
     */
    public List<BidResponse> listResponses(Long invitationId, Long currentPersonId) {
        BidInvitation inv = bidInvitationMapper.selectById(invitationId);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "招标单不存在: " + invitationId);
        }
        // MEDIUM-info-disclosure 修复：强制要求已登录 actor，避免 eq(field, null) IS-NULL 语义泄露遗留数据
        if (currentPersonId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "未登录或会话失效");
        }
        LambdaQueryWrapper<BidResponse> qw = new LambdaQueryWrapper<BidResponse>()
            .eq(BidResponse::getInvitationId, invitationId)
            .orderByDesc(BidResponse::getCreateTime);
        boolean isCreator = currentPersonId != null && currentPersonId.equals(inv.getCreateBy());
        boolean isPublic = "PUBLIC".equals(inv.getMode());
        if (isCreator) {
            // 发起人：全量（含 WITHDRAWN/REJECTED），不动 responseNote
        } else if (isPublic) {
            // MEDIUM-2.2：PUBLIC 模式下 WITHDRAWN/REJECTED 仅本人可见，其余应标者互见
            qw.and(w -> w.notIn(BidResponse::getStatus, "WITHDRAWN", "REJECTED")
                .or().eq(BidResponse::getRdPmId, currentPersonId));
        } else {
            // ONE_TO_ONE：仅本人
            qw.eq(BidResponse::getRdPmId, currentPersonId);
        }
        // PERF-P1-2（2026-09-09 契约轮）：招标详情页应标列表曾全量返回不分页，
        // 单招标应标数理论上限受 PM 人数约束，500 硬上限足够安全（真分页待后续需要时随 BidController 端点改造）。
        qw.last("LIMIT 500");
        List<BidResponse> rows = bidResponseMapper.selectList(qw);
        // MEDIUM-2.2：PUBLIC 模式下非发起人视角脱敏解决方案摘要为前 80 字符
        if (isPublic && !isCreator) {
            for (BidResponse r : rows) {
                if (r.getRdPmId() != null && !r.getRdPmId().equals(currentPersonId)
                    && r.getResponseNote() != null) {
                    r.setResponseNote(maskSummary(r.getResponseNote()));
                }
            }
        }
        return rows;
    }

    /**
     * PERF-P1-2：分页查询招标单下的应标列表（隐私过滤不变 + IPage 物理分页）。
     *
     * <ul>
     *   <li>复用既有 {@code idx_br_invitation(invitation_id)} 索引，等值过滤直接走索引</li>
     *   <li>{@code pageSize} 上限 200 防滥用；null → 默认 20；&lt;1 → 1</li>
     *   <li>隐私过滤与脱敏口径同 {@link #listResponses(Long, Long)}：ONE_TO_ONE 仅本人 /
     *       PUBLIC 全员可见 + WITHDRAWN|REJECTED 仅本人 / 发起人全量 / PUBLIC 非发起人脱敏前 80 字符</li>
     *   <li>隐私过滤在 wrapper 层完成，避免 count 查询泄露「有无应标」oracle</li>
     * </ul>
     *
     * @param invitationId    招标单 ID
     * @param currentPersonId 会话用户 ID
     * @param pageNo   页码（从 1 开始；&lt;1 → 1）
     * @param pageSize 每页条数（&lt;1 → 1；&gt;200 → 200；null → 20）
     */
    public IPage<BidResponse> listResponsesPaged(Long invitationId, Long currentPersonId,
                                                 Integer pageNo, Integer pageSize) {
        BidInvitation inv = bidInvitationMapper.selectById(invitationId);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "招标单不存在: " + invitationId);
        }
        // MEDIUM-info-disclosure 修复：强制要求已登录 actor，避免 eq(field, null) IS-NULL 语义泄露遗留数据
        if (currentPersonId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "未登录或会话失效");
        }
        int pNo = (pageNo == null || pageNo < 1) ? 1 : pageNo;
        int pSize;
        if (pageSize == null) {
            pSize = 20;
        } else if (pageSize < 1) {
            pSize = 1;
        } else if (pageSize > BidResponseService.MAX_PAGE_SIZE) {
            pSize = BidResponseService.MAX_PAGE_SIZE;
        } else {
            pSize = pageSize;
        }
        LambdaQueryWrapper<BidResponse> qw = new LambdaQueryWrapper<BidResponse>()
            .eq(BidResponse::getInvitationId, invitationId)
            .orderByDesc(BidResponse::getCreateTime);
        boolean isCreator = currentPersonId.equals(inv.getCreateBy());
        boolean isPublic = "PUBLIC".equals(inv.getMode());
        if (isCreator) {
            // 发起人：全量（含 WITHDRAWN/REJECTED），不动 responseNote
        } else if (isPublic) {
            // MEDIUM-2.2：PUBLIC 模式下 WITHDRAWN/REJECTED 仅本人可见，其余应标者互见
            qw.and(w -> w.notIn(BidResponse::getStatus, "WITHDRAWN", "REJECTED")
                .or().eq(BidResponse::getRdPmId, currentPersonId));
        } else {
            // ONE_TO_ONE：仅本人
            qw.eq(BidResponse::getRdPmId, currentPersonId);
        }
        Page<BidResponse> page = new Page<>(pNo, pSize);
        IPage<BidResponse> result = bidResponseMapper.selectPage(page, qw);
        // MEDIUM-2.2：PUBLIC 模式下非发起人视角脱敏解决方案摘要为前 80 字符
        if (isPublic && !isCreator) {
            List<BidResponse> records = result.getRecords();
            if (records != null) {
                for (BidResponse r : records) {
                    if (r.getRdPmId() != null && !r.getRdPmId().equals(currentPersonId)
                        && r.getResponseNote() != null) {
                        r.setResponseNote(maskSummary(r.getResponseNote()));
                    }
                }
            }
        }
        return result;
    }

    /** MEDIUM-2.2：解决方案摘要脱敏（互见场景下避免互抄完整方案）；超长截断并附省略号 */
    static String maskSummary(String note) {
        if (note == null) {
            return null;
        }
        if (note.length() <= 80) {
            return note;
        }
        return note.substring(0, 80) + "…";
    }


    /**
     * AC-TEAM-13：市场PM（招标单发起人）在有效期内修改招标条件
     * - 仅发起人本人可改（横向越权防御）
     * - 仅 OPEN 状态可改（SELECTED/EXPIRED/CLOSED 状态机封口）
     * - 写审计 action=modify_conditions
     * - 向所有 PENDING 应标者发 BID_CONDITIONS_CHANGED 通知
     */
    @Transactional(rollbackFor = Exception.class)
    public BidInvitation modifyInvitation(Long id, String newTitle, String newContent,
                                         Date newExpireAt, Long operatorId) {
        BidInvitation inv = bidInvitationMapper.selectByIdForUpdate(id);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        if (operatorId == null || !operatorId.equals(inv.getCreateBy())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN);
        }
        if (!"OPEN".equals(inv.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }
        StringBuilder changeLog = new StringBuilder("{");
        changeLog.append("\"before\":{\"title\":\"").append(escape(inv.getTitle()))
            .append("\",\"expireAt\":\"").append(inv.getExpireAt()).append("\"}");
        inv.setTitle(newTitle);
        inv.setContent(newContent);
        inv.setExpireAt(newExpireAt);
        inv.setUpdateTime(now());
        bidInvitationMapper.updateById(inv);
        changeLog.append(",\"after\":{\"title\":\"").append(escape(newTitle))
            .append("\",\"expireAt\":\"").append(newExpireAt).append("\"}");
        changeLog.append("}");
        // 通知所有 PENDING 应标者（必须排在审计落库之前）：
        // 审计 append 走 REQUIRES_NEW 独立事务提交，外层回滚撤不回它。
        // 若先写审计再发通知，外层后续回滚（业务未改）时，
        // 哈希链里却永久留下一条「修改招标条件成功」的假记录。
        // 走 publishAfterCommit：延迟到宿主事务提交后独立事务发送，
        // 发送失败只 WARN，绝不把外层事务打成 rollback-only（通知是副链，不反噬主链）。
        if (notificationService != null) {
            List<BidResponse> responders = bidResponseMapper.selectList(
                new LambdaQueryWrapper<BidResponse>()
                    .eq(BidResponse::getInvitationId, id)
                    .eq(BidResponse::getStatus, "PENDING"));
            for (BidResponse r : responders) {
                if (r.getRdPmId() == null) continue;
                notificationService.publishAfterCommit(r.getRdPmId(),
                    NotificationService.Types.BID_CONDITIONS_CHANGED,
                    NotificationService.KIND_ACTION,
                    "bid_invitation", id,
                    "招标条件已变更",
                    "招标单 " + id + "「" + newTitle + "」条件已变更，请重新评估。",
                    "/bid-invitations/" + id);
            }
        }
        // 通知全部成功后，最后落审计——此时写审计即等价于「业务确实发生了」
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId).action("modify_conditions").entityType("bid_invitation").entityId(id)
            .afterData(changeLog.toString())
            .reason(inv.getTitle())
            .createTime(now()).build());
        return inv;
    }


    /**
     * D-1 批次（补遗 §5-2 二波接线）：状态机守卫，对齐 StageActionService 金样板。
     * 本服务同时驱动两台机器（bid_invitation + bid_response 遴选迁移），helper 按 entityType 参数化。
     * setter 注入不扩构造签名；缺失时迁移 fail-closed。
     */
    private StateMachineGuard stateMachineGuard;

    /** entityType 词表与其他机器一致：小写下划线。 */
    private static final String BID_INVITATION_ENTITY_TYPE = "bid_invitation";
    private static final String BID_RESPONSE_ENTITY_TYPE = "bid_response";

    /** 公开征集模式的招标单：全员可见（与 ONE_TO_ONE 定向邀标相对）。 */
    private static final String MODE_PUBLIC = "PUBLIC";
    /** 超管角色字面量（与 IpdIdorGuard / 四大 P0 修复同口径）。 */
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    @Autowired(required = false)
    public void setStateMachineGuard(StateMachineGuard stateMachineGuard) {
        this.stateMachineGuard = stateMachineGuard;
    }

    /** 守卫 preCheck 包装（fail-closed：守卫 null = 装配缺失，拒绝迁移）。 */
    private void preCheckGuard(String entityType, String fromState, String toState, String trigger) {
        if (stateMachineGuard == null) {
            throw new IpdBusinessException("状态机守卫未装配 entityType=" + entityType
                + " from=" + fromState + " to=" + toState);
        }
        stateMachineGuard.preCheck(entityType, fromState, toState, trigger);
    }

    /** 注册 postCommit 副作用（事务提交后触发；无守卫降级 no-op；无事务上下文直接执行）。 */
    private void registerPostCommit(String entityType, String fromState, String toState, String trigger,
                                    Long operatorId, Long entityId) {
        if (stateMachineGuard == null) {
            return;
        }
        Date occurredAt = new Date();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    stateMachineGuard.postCommit(entityType, fromState, toState, trigger,
                        operatorId, entityId, occurredAt);
                }
            });
        } else {
            stateMachineGuard.postCommit(entityType, fromState, toState, trigger,
                operatorId, entityId, occurredAt);
        }
    }

    /**
     * AC-TEAM-09：超管对挂起超 30 日的招标单直接指派（无需应标行）
     * SEC-REV-BID-01：状态门禁 + 年龄判定 + targetPersonId 必填 + 通知对等。
     *
     * <ul>
     *   <li>仅 EXPIRED 状态可被强制指派（避免在 OPEN/SELECTED 上覆盖正常流程）</li>
     *   <li>挂起需 ≥ 30 日（expireAt 锚点；expireAt 缺失回退到 updateTime）</li>
     *   <li>targetPersonId 必填（中标者）</li>
     *   <li>通知对等：targetPersonId ⇒ BID_WON；其他 PENDING 应标者（若有）⇒ BID_LOST</li>
     * </ul>
     *
     * 调用方需校验调用人为超管（controller 守卫 ipd:bid-invitation:admin-assign 注解 + IpdPermission.requireAdmin() 兜底）
     */
    @Transactional(rollbackFor = Exception.class)
    public BidInvitation adminAssign(Long id, Long targetPersonId, Long adminId) {
        BidInvitation inv = bidInvitationMapper.selectByIdForUpdate(id);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        // Bug#4 高危：状态门禁 —— 防止 admin 在 OPEN/SELECTED 任意时刻强制指派覆盖正常流程
        if (!"EXPIRED".equals(inv.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "admin-assign 仅适用于 EXPIRED 挂起超 30 日的招标单，当前状态: " + inv.getStatus());
        }
        // Bug#4 高危：年龄判定 —— 挂起 ≥ 30 日才有强制指派的业务理由
        long ageMillis = ageOfInvitationMillis(inv);
        if (ageMillis < ADMIN_ASSIGN_MIN_AGE_MILLIS) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "招标单挂起不足 30 日，禁止 admin-assign");
        }
        // Bug#4 高危：targetPersonId 必填 —— 服务端权威，避免 admin 把空指针写成中标人
        if (targetPersonId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "targetPersonId 必填");
        }
        // D-1 接线：EXPIRED->SELECTED|adminAssign 守卫
        preCheckGuard(BID_INVITATION_ENTITY_TYPE, "EXPIRED", "SELECTED", "adminAssign");
        inv.setStatus("SELECTED");
        inv.setUpdateTime(now());
        bindSelectedRdPm(inv.getProjectId(), targetPersonId);
        bidInvitationMapper.updateById(inv);
        registerPostCommit(BID_INVITATION_ENTITY_TYPE, "EXPIRED", "SELECTED", "adminAssign", adminId, id);
        auditLogService.append(AuditLog.builder()
            .operatorId(adminId).action("admin_assign").entityType("bid_invitation").entityId(id)
            .afterData(AuditEventData.json("targetPersonId", targetPersonId))
            .reason(inv.getTitle())
            .createTime(now()).build());
        // Bug#6 中危：兄弟路径门禁对等 —— 中标者 BID_WON；其他 PENDING 应标者 BID_LOST（保持与 selectResponse 一致语义）
        // adminAssign 为 @Transactional，走 publishAfterCommit（同 selectResponse）：宿主回滚时
        // 「管理员指派给您 / 已指派他人」两条通知都不发出。
        if (notificationService != null) {
            notificationService.publishAfterCommit(targetPersonId,
                NotificationService.Types.BID_WON,
                NotificationService.KIND_ACTION,
                "bid_invitation", id,
                "招标已指派给您",
                "招标单「" + inv.getTitle() + "」已被管理员指派给您，请尽快承接。",
                "/bid-invitations/" + id);
            List<BidResponse> losers = bidResponseMapper.selectList(new LambdaQueryWrapper<BidResponse>()
                .eq(BidResponse::getInvitationId, id)
                .eq(BidResponse::getStatus, "PENDING")
                .ne(BidResponse::getRdPmId, targetPersonId));
            for (BidResponse loser : losers) {
                if (loser.getRdPmId() == null) continue;
                notificationService.publishAfterCommit(loser.getRdPmId(),
                    NotificationService.Types.BID_LOST,
                    NotificationService.KIND_ACTION,
                    "bid_invitation", id,
                    "招标已由管理员指派他人",
                    "招标单「" + inv.getTitle() + "」已由管理员强制指派给其他人，本次落选。",
                    "/bid-invitations/" + id);
            }
        }
        return inv;
    }

    /** Bug#4：admin-assign 最小挂起时长（30 天）。 */
    static final long ADMIN_ASSIGN_MIN_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000L;

    /** Bug#4：挂起时长锚点 —— 优先 expireAt，缺失则回退到 updateTime。 */
    private static long ageOfInvitationMillis(BidInvitation inv) {
        // static 工具方法——保留 System.currentTimeMillis()（跨实例，无 clock 注入必要）
        if (inv.getExpireAt() != null) {
            return System.currentTimeMillis() - inv.getExpireAt().getTime();
        }
        if (inv.getUpdateTime() != null) {
            return System.currentTimeMillis() - inv.getUpdateTime().getTime();
        }
        return 0L;
    }

    /**
     * Bug#5 中危：JSON 字符串转义 —— 控制字符全集（\n \r \t \b \f + U+0000..U+001F）。
     * 旧实现仅处理 \\ 与 "，会把换行/制表等字符原样写入审计 afterData，导致 JSON 解析失败。
     */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private BidInvitation requireOpen(Long id) {
        // 锁定读（H-1）：遴选/发布/撤回在招标单行上串行化，防止并发 selectResponse 双中标
        BidInvitation inv = bidInvitationMapper.selectByIdForUpdate(id);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "招标单不存在: " + id);
        }
        if (!"OPEN".equals(inv.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "招标单状态非 OPEN，当前: " + inv.getStatus());
        }
        return inv;
    }
}
