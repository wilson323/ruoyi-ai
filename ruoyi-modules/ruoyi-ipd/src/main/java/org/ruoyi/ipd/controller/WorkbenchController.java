package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.WorkbenchService;
import org.ruoyi.ipd.workbench.domain.MyInitiatedTask;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 我的工作台聚合（P4-3.1 前置真实现；对齐 ZK-IPD 原型 /api/workflow/tasks + workspace 概览）。
 * 页03：metric 四卡 / 责任任务队列 / 我的当前推进 / 删除审批待办数的唯一数据源。
 */
@RestController
@RequestMapping("/api/v1/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final IpdPermission ipdPermission;
    private final WorkbenchService workbenchService;

    /**
     * 工作台总览（真实聚合，无 mock）。
     *
     * @param projectId 可选当前项目（顶栏切换后续传；空 = 第一个可见项目）
     * @return stats(pending/overdue/unread/completed) + tasks + deletionPending + currentAdvance
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/summary")
    public ApiV1Response<Map<String, Object>> summary(@RequestParam(required = false) Long projectId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(workbenchService.summary(actor, projectId));
    }

    /**
     * 任务队列过滤视图（WB-17-1 S0 切片；spec 页03 §4 tasks?bucket=&type=&limit=）。
     *
     * @param projectId 可选：仅该项目的卡（卡面 projectId 精确匹配）
     * @param bucket    pending（缺省，全部在途）| overdue（dueDate 早于当前，与 summary 同规则）；
     *                  completed/initiated fail-closed 400（数据源契约各在 completedCount / my-initiated）
     * @param type      可选：spec 页03:165 权威 17 类 taskType 之一，非法值 400
     * @param limit     可选：1~200，缺省 50；返回 total=截断前命中数
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/tasks")
    public ApiV1Response<Map<String, Object>> tasks(@RequestParam(required = false) Long projectId,
                                                    @RequestParam(required = false) String bucket,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) Integer limit) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(workbenchService.tasks(actor, projectId, bucket, type, limit));
    }

    /**
     * 我发起的（R27 P0-6）：聚合 {@code deletion_requests} 与
     * {@code launch_date_change_requests} 中 create_by=本人 的单据。
     *
     * <p><b>查询范围只取会话身份</b>（SEC-API-01）：本端点语义是「我的」，不接受调用方
     * 指定 personId——允许传参即等于任何内部用户都能读到别人的单据标题（W5-E 同类越权读口）。
     * 前端固定不传参；即使请求带上 {@code ?personId=} 也会被忽略，仍返回本人数据。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/my-initiated")
    public ApiV1Response<List<MyInitiatedTask>> myInitiated() {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(workbenchService.myInitiated(actor.id()));
    }

    /**
     * 待我审批的（R27 P0-6）：聚合业务单据中处于审批态、且本人确有权办理的记录。
     *
     * <p><b>查询范围只取会话身份</b>（SEC-API-01）：审批归属由 personId 推导角色后判定
     * （SUPER_ADMIN→ADMIN_REVIEW / GROUP_LEADER→LEADER_REVIEW），因此传他人的 id 不只是
     * 读到别人的清单，而是拿到别人的<b>角色视角</b>。故同 {@link #myInitiated()} 不接受 personId。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/my-pending-approvals")
    public ApiV1Response<List<MyInitiatedTask>> myPendingApprovals() {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(workbenchService.myPendingApprovals(actor.id()));
    }
}