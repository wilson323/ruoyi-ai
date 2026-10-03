package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.dto.AiFeedbackReq;
import org.ruoyi.ipd.agent.service.AiFeedbackService;
import org.ruoyi.ipd.agent.service.ProjectAgentCapabilityService;
import org.ruoyi.ipd.agent.service.ProjectAgentRunService;
import org.ruoyi.ipd.agent.service.ProjectAgentAguiStream;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 项目智能体 HTTP 接线（合同 #1–#6）。只转调已有 service，不掺业务。
 *
 * <p>物理放在 {@code org.ruoyi.ipd.controller}（契约门禁只扫本目录）；服务 Bean 在
 * {@code org.ruoyi.ipd.agent}。雪花 ID 路径段以字符串接收再解析，避免前端精度丢失。
 * 权限码沿用副驾读族 {@link IpdPermissionCode#OPERATION_AI_COPILOT}；对象级可见性由 service
 * 经 {@code IpdCopilotAccess} 裁定。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProjectAgentController {

    private final ProjectAgentCapabilityService capabilityService;
    private final ProjectAgentRunService runService;
    private final AiFeedbackService feedbackService;
    private final IpdPermission ipdPermission;
    private final ProjectAgentAguiStream aguiStream;

    /**
     * 合同 #1：能力目录（开关关闭仍 200+code=0，pack.available=false）。
     *
     * @param projectId 项目 ID（字符串）
     * @return 能力包 + 模型
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/projects/{projectId}/agent-capabilities")
    public ApiV1Response<ProjectAgentViews.Capabilities> capabilities(@PathVariable String projectId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(capabilityService.capabilities(actor, parseId(projectId, "projectId")));
    }

    /**
     * 合同 #2：创建运行（幂等键在 body）。
     *
     * @param projectId 项目 ID（字符串）
     * @param req 创建请求
     * @return runId + status
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/projects/{projectId}/agent-runs")
    public ApiV1Response<ProjectAgentViews.RunStatus> create(@PathVariable String projectId,
                                                             @RequestBody AgentRunCreateReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(runService.create(actor, parseId(projectId, "projectId"), req));
    }

    /** 回答持久中断并继续原运行，不创建另一运行。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/agent-runs/{runId}/resume")
    public ApiV1Response<ProjectAgentViews.RunStatus> resume(@PathVariable String runId,
            @RequestBody org.ruoyi.ipd.agent.dto.AgentRunResumeReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        if (req == null) throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "中断响应不能为空");
        return ApiV1Response.ok(runService.resume(actor, parseId(runId, "runId"),
            req.expectedPauseSeq(), req.aguiInput()));
    }

    /**
     * 本人在该项目下的运行列表，支持 q / status / actionCode / cursor / limit。
     *
     * @param projectId 项目 ID（字符串）
     * @param q 搜索词，可空
     * @param status 运行状态，可空
     * @param actionCode 动作编码，可空
     * @param cursor 上一页最后的 runId，可空
     * @param limit 条数，默 20，最大 50
     * @return 运行摘要
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/projects/{projectId}/agent-runs")
    public ApiV1Response<List<ProjectAgentViews.RunItem>> listRuns(
        @PathVariable String projectId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String actionCode,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit) {
        IpdActor actor = ipdPermission.requireInternal();
        Long beforeId = cursor == null || cursor.isBlank() ? null : parseId(cursor, "cursor");
        return ApiV1Response.ok(runService.list(actor, parseId(projectId, "projectId"),
            q, status, actionCode, beforeId, limit));
    }

    /**
     * 合同 #3：运行详情（仅发起人）。
     *
     * @param runId 运行 ID（字符串）
     * @return 运行视图
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/agent-runs/{runId}")
    public ApiV1Response<ProjectAgentViews.Run> get(@PathVariable String runId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(runService.get(actor, parseId(runId, "runId")));
    }

    /**
     * 合同 #4：事件增量（seq &gt; afterSeq）。
     *
     * @param runId 运行 ID（字符串）
     * @param afterSeq 游标（缺省 0）
     * @return 事件页
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/agent-runs/{runId}/events")
    public ApiV1Response<ProjectAgentViews.Events> events(@PathVariable String runId,
                                                          @RequestParam(required = false) Long afterSeq) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(runService.events(actor, parseId(runId, "runId"), afterSeq));
    }

    /** 原运行的持久事件 AG-UI SSE；连接断开只停止读取，不取消后台。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping(value = "/agent-runs/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String runId,
                                  @RequestParam(required = false) Long afterSeq,
                                  @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        IpdActor actor = ipdPermission.requireInternal();
        return aguiStream.open(actor, parseId(runId, "runId"), afterSeq, lastEventId);
    }

    /**
     * 合同 #5：取消运行。
     *
     * @param runId 运行 ID（字符串）
     * @return runId + 当前状态
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/agent-runs/{runId}/cancel")
    public ApiV1Response<ProjectAgentViews.RunStatus> cancel(@PathVariable String runId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(runService.cancel(actor, parseId(runId, "runId")));
    }

    /**
     * 合同 #6：点赞/点踩（targetType 仅 RUN_MESSAGE | ARTIFACT_VERSION）。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID（字符串）
     * @param req 评级与原因
     * @return 反馈视图
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping("/ai-feedback/{targetType}/{targetId}")
    public ApiV1Response<ProjectAgentViews.Feedback> feedback(@PathVariable String targetType,
                                                              @PathVariable String targetId,
                                                              @RequestBody AiFeedbackReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(feedbackService.put(actor, targetType, targetId, req));
    }

    /**
     * 产物 apply：将 DRAFT 版本落到项目文档并回写 documentId；indexStatus 如实返回。
     *
     * @param runId 运行 ID（字符串）
     * @param artifactId 逻辑产物 ID
     * @return apply 结果
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/agent-runs/{runId}/artifacts/{artifactId}/apply")
    public ApiV1Response<ProjectAgentViews.ArtifactApply> applyArtifact(@PathVariable String runId,
                                                                        @PathVariable String artifactId) {
        IpdActor actor = ipdPermission.requireInternal();
        ProjectAgentViews.ArtifactApply applied =
            runService.applyArtifact(actor, parseId(runId, "runId"), artifactId);
        ApiV1Response<ProjectAgentViews.ArtifactApply> response = ApiV1Response.ok(applied);
        response.setMessage(applied.documentStatusLabel());
        return response;
    }

    /**
     * 路径段字符串 → Long；非法时 PARAM_INVALID（不触达 service）。
     *
     * @param raw 原始路径段
     * @param field 字段名（用于错误文案）
     * @return 解析后的 ID
     */
    static Long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, field + " 格式错误");
        }
    }
}
