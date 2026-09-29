package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.AiAgentTaskQueryService;
import org.ruoyi.ipd.vo.AiAgentTaskView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * R221 ai_agent_tasks 只读查询 API /api/v1/ai-agent-tasks（R232 P2-04 任务卡数据通道）。
 *
 * <p>最小面两端点（前后端同批齐落防孤儿棘轮红）：
 * <ul>
 *   <li>{@code GET /{taskId}}：按任务单查——待办直达解析（NotificationService 载荷
 *       sourceType=ai_agent_task + sourceId=taskId）与跨设备恢复用；</li>
 *   <li>{@code GET ?projectId=}:按项目列表——任务卡时间线卡片组。</li>
 * </ul>
 *
 * <p>只读零写入（无 POST/PUT/DELETE）；状态呈现到 result_summary 粒度，
 * prompt/fillPayload 原文永不出 {@link AiAgentTaskView}（审计规约 L0-5）。
 * 权限码沿用 AI 读族 {@code ipd:ai-document:list}（READ_SET 四角色全员可读，
 * 与 versions/history/diff 同口径）；receiver/actor 恒从会话推导（SEC-API-01）。
 *
 * <p><b>数据范围</b>：角色级权限码不限定项目，故 actor 由 {@code requireInternal()} 捕获后
 * 透传 service，由 {@code AiAgentTaskQueryService} 经 IpdIdorGuard 守卫 3 做项目在职成员/租户校验
 * （跨项目 taskId/projectId 一律 FORBIDDEN，与 SubStage/PostLaunchReview/Handover 同口径）。
 */
@RestController
@RequestMapping("/api/v1/ai-agent-tasks")
@RequiredArgsConstructor
public class AiAgentTaskController {

    private final AiAgentTaskQueryService aiAgentTaskQueryService;
    private final IpdPermission ipdPermission;

    /**
     * 按 taskId 单查（不存在 50001 NOT_FOUND，IpdResources 在 service 层统一收口）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_DOCUMENT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/{taskId}")
    public ApiV1Response<AiAgentTaskView> get(@PathVariable Long taskId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(aiAgentTaskQueryService.getByTaskId(taskId, actor));
    }

    /**
     * 按项目列任务（create_time DESC；空项目返回空列表）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_DOCUMENT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<List<AiAgentTaskView>> listByProject(@RequestParam Long projectId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(aiAgentTaskQueryService.listByProject(projectId, actor));
    }
}
