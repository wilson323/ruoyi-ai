package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.common.IpdResources;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.BidResponse;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.BidInvitationService;
import org.ruoyi.ipd.service.BidResponseService;
import org.springframework.web.bind.annotation.*;


/**
 * 招标组队 API（P2-3.1 / P2-3.2）
 *
 * <p>端点：
 * <ul>
 *   <li>POST   /api/v1/bid-invitations          创建招标单（市场PM）</li>
 *   <li>GET    /api/v1/bid-invitations           分页查询招标单</li>
 *   <li>GET    /api/v1/bid-invitations/{id}      招标单详情</li>
 *   <li>PUT    /api/v1/bid-invitations/{id}/publish   发布</li>
 *   <li>PUT    /api/v1/bid-invitations/{id}/select    遴选应标</li>
 *   <li>PUT    /api/v1/bid-invitations/{id}/withdraw  撤回（24h内）</li>
 *   <li>PUT    /api/v1/bid-invitations/{id}/close     关闭</li>
 *   <li>GET    /api/v1/bid-invitations/{id}/responses 应标列表</li>
 *   <li>POST   /api/v1/bid-responses             提交应标（研发PM）</li>
 *   <li>PUT    /api/v1/bid-responses/{id}/withdraw  撤回应标</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BidController {

    private final BidInvitationService bidInvitationService;
    private final BidResponseService bidResponseService;
    private final IpdPermission ipdPermission;
    private final IpdAuthSession session;
    private final ProjectMapper projectMapper;

    /**
     * 招标单归属守卫（横向越权防护，写库前执行）。
     *
     * <p>三要素：① actor 只来自会话（{@code ipdPermission.requireInternal()} 的返回值，
     * 绝不接受入参传入）；② 归属解析在写库之前完成（id → 招标单 → 项目 → 项目主组）；③
     * 失败即拒且统一「无权操作」文案——招标单不存在与无权操作同一分支，不泄漏存在性。
     *
     * <p>复用 {@link IpdIdorGuard#assertSameGroupIpd}（操作人组 vs 项目主组，与
     * {@code BidP231Validator#assertProjectVisible}、{@code GateReviewService} 同口径），
     * 不另造守卫。无项目挂靠的历史数据退化为「发起人本人或超管」。
     *
     * @param actor         会话身份
     * @param invitationId  招标单 ID
     * @throws IpdBusinessException {@link ApiV1ErrorCode#FORBIDDEN} 招标单不存在或跨组
     */
    private Long resolveInvitationGroup(IpdActor actor, Long invitationId) {
        BidInvitation inv = bidInvitationService.getById(invitationId);
        if (inv == null) {
            // 统一 FORBIDDEN——不区分「不存在」与「无权限」，避免存在性探测
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作");
        }
        if (inv.getProjectId() == null) {
            // 无项目挂靠的历史数据退化为「发起人本人或超管」；命中后回操作人自己的组 ID，
            // 由调用点的 assertSameGroupIpd 统一放行——解析与断言两个职责分开，
            // 端点方法体里能看到真正的归属断言（门禁 GUARD_RE 只扫端点方法体）。
            IpdIdorGuard.requireSelfOrSuperAdmin(actor, inv.getCreateBy());
            return actor.groupId();
        }
        Project project = projectMapper.selectById(inv.getProjectId());
        // 项目不存在时 mainGroupId 传 null：assertSameGroupIpd 对非超管一律 FORBIDDEN
        return project == null ? null : project.getMainGroupId();
    }

    /** 新建招标单：请求体里的 projectId 同样要过归属校验，避免建在别人项目下。 */
    private void requireProjectGroup(IpdActor actor, Long projectId) {
        if (projectId == null) {
            return;
        }
        Project project = projectMapper.selectById(projectId);
        IpdIdorGuard.assertSameGroupIpd(actor, project == null ? null : project.getMainGroupId());
    }

    // ==================== 招标单 ====================

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/bid-invitations")
    public ApiV1Response<BidInvitation> createInvitation(@RequestBody BidInvitation invitation) {
        IpdActor actor = ipdPermission.requireInternal();
        Person person = session.currentPerson();
        // 发起人身份服务端权威：供 listResponses 隐私过滤与审计使用（通用填充器取不到 IPD 独立会话）
        invitation.setCreateBy(person.getId());
        requireProjectGroup(actor, invitation.getProjectId());
        return ApiV1Response.ok(bidInvitationService.create(invitation));
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/bid-invitations")
    public ApiV1Response<IPage<BidInvitation>> listInvitations(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {
        IpdActor actor = ipdPermission.requireInternal();
        // AC-TEAM-01：「其他研发PM 看不到该招标单」——列表按可见性过滤（发起人 ∪ 受邀人 ∪ 超管；PUBLIC 全员）。
        return ApiV1Response.ok(bidInvitationService.page(actor, pageNo, pageSize, projectId, status));
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/bid-invitations/{id}")
    public ApiV1Response<BidInvitation> getInvitation(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        // 同上：详情也按可见性放行，否则「列表看不到、猜 id 却能读全文」。
        return ApiV1Response.ok(IpdResources.requireOrNotFound(
            bidInvitationService.getVisibleTo(id, actor), id, "招标邀请"));
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping("/bid-invitations/{id}/publish")
    public ApiV1Response<BidInvitation> publishInvitation(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        IpdIdorGuard.assertSameGroupIpd(actor, resolveInvitationGroup(actor, id));
        return ApiV1Response.ok(bidInvitationService.publish(id));
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping("/bid-invitations/{id}/select")
    public ApiV1Response<BidInvitation> selectResponse(
            @PathVariable Long id,
            @RequestParam Long responseId,
            @RequestParam String confirmToken) {
        IpdActor actor = ipdPermission.requireInternal();
        // 2026-10-03 P4 存量清零：本端点原登记在 scripts/ownership-gate-exempt.txt
        // （「存量中危待修：能操作本组外数据」），与同文件 publish / withdraw / close /
        // preSelectToken 同口径补归属守卫——SELECTED 决定谁中标，必须限本产品组。
        IpdIdorGuard.assertSameGroupIpd(actor, resolveInvitationGroup(actor, id));
        Person person = session.currentPerson();
        return ApiV1Response.ok(bidInvitationService.selectResponse(id, responseId, confirmToken, person.getId()));
    }

    /**
     * P1-5.2：预演生成 confirmToken（6 字符随机 + 24h 过期）。
     * 前端先调此端点拿 token 与预演信息，再展示「将向 N 名应标者发送落选通知」，
     * 用户在 UI 勾选「已确认」后，把 token 提交到 /select。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/bid-invitations/{id}/pre-select-token")
    public ApiV1Response<BidInvitationService.ConfirmTokenView> preSelectToken(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        IpdIdorGuard.assertSameGroupIpd(actor, resolveInvitationGroup(actor, id));
        return ApiV1Response.ok(bidInvitationService.issueConfirmToken(id));
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping("/bid-invitations/{id}/withdraw")
    public ApiV1Response<BidInvitation> withdrawInvitation(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        IpdIdorGuard.assertSameGroupIpd(actor, resolveInvitationGroup(actor, id));
        return ApiV1Response.ok(bidInvitationService.withdraw(id));
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping("/bid-invitations/{id}/close")
    public ApiV1Response<BidInvitation> closeInvitation(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        IpdIdorGuard.assertSameGroupIpd(actor, resolveInvitationGroup(actor, id));
        return ApiV1Response.ok(bidInvitationService.close(id));
    }

    /**
     * P2-3.3 AC-TEAM-13：市场PM（招标单发起人）在有效期内修改招标条件
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping("/bid-invitations/{id}/modify")
    public ApiV1Response<BidInvitation> modifyInvitation(
            @PathVariable Long id,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") java.util.Date expireAt) {
        IpdActor actor = ipdPermission.requireInternal();
        // 2026-10-03 P4 存量清零：同 selectResponse——原登记豁免，改招标条件属本组写操作。
        IpdIdorGuard.assertSameGroupIpd(actor, resolveInvitationGroup(actor, id));
        Person person = session.currentPerson();
        return ApiV1Response.ok(bidInvitationService.modifyInvitation(id, title, content, expireAt, person.getId()));
    }

    /**
     * P2-3.3 AC-TEAM-09：超管对挂起超 30 日的招标单直接指派
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_BID_INVITATION_ADMIN_ASSIGN, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping("/bid-invitations/{id}/admin-assign")
    public ApiV1Response<BidInvitation> adminAssign(
            @PathVariable Long id,
            @RequestParam Long targetPersonId) {
        ipdPermission.requireAdmin();
        Person person = session.currentPerson();
        return ApiV1Response.ok(bidInvitationService.adminAssign(id, targetPersonId, person.getId()));
    }

    /**
     * PERF-P1-2：分页查询招标单下的应标列表（隐私过滤不变 + 物理分页）。
     * pageSize 上限 200（service 侧硬约束），null → 默认 20。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/bid-invitations/{id}/responses")
    public ApiV1Response<IPage<BidResponse>> listResponses(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        ipdPermission.requireInternal();
        Person person = session.currentPerson();
        return ApiV1Response.ok(bidInvitationService.listResponsesPaged(id, person.getId(), pageNo, pageSize));
    }

    /**
     * PERF-P0-4：分页查询某研发PM的所有应标（IDOR 三分支放行不变 + 物理分页）。
     * 三分支放行同 service.listByRdPm：本人 / SUPER_ADMIN / 关联项目在职 ProjectMember；
     * pageSize 上限 200（service 侧硬约束），null → 默认 20。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/bid-responses/by-rd-pm/{rdPmId}")
    public ApiV1Response<IPage<BidResponse>> listByRdPm(
            @PathVariable Long rdPmId,
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        // W5-E-2.4：捕获 actor 传入 service，service 层再做 UNAUTHORIZED 入口校验 + IDOR 三分支
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(bidResponseService.listByRdPmPaged(actor, rdPmId, pageNo, pageSize));
    }

    // ==================== 应标 ====================

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/bid-responses")
    public ApiV1Response<BidResponse> submitResponse(@RequestBody BidResponse response) {
        // W5-E-2.4：捕获 actor 传入 service，service 层再做 UNAUTHORIZED 入口校验（IDOR 修复）
        IpdActor actor = ipdPermission.requireInternal();
        // decision=reject 不留痕：BR-TEAM-03 以 code=0 + data=null 表达 204 语义
        return ApiV1Response.ok(bidResponseService.submit(actor, response));
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping("/bid-responses/{id}/withdraw")
    public ApiV1Response<BidResponse> withdrawResponse(@PathVariable Long id) {
        // W5-E-2.4：捕获 actor 传入 service（IDOR 修复）
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(bidResponseService.withdraw(actor, id));
    }
}
