package org.ruoyi.workflow.workflow;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.chat.service.workFlow.IWorkFlowStarterService;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.workflow.entity.*;
import org.ruoyi.workflow.helper.SSEEmitterHelper;
import org.ruoyi.workflow.service.*;
import org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.ruoyi.common.chat.enums.ErrorEnum.*;

@Slf4j
@Service
public class WorkflowStarter implements IWorkFlowStarterService {

    @Lazy
    @Resource
    private WorkflowStarter self;

    @Resource
    private WorkflowService workflowService;

    @Resource
    private WorkflowNodeService workflowNodeService;

    @Resource
    private WorkflowEdgeService workflowEdgeService;

    @Resource
    private WorkflowComponentService workflowComponentService;

    @Resource
    private WorkflowRuntimeService workflowRuntimeService;

    @Resource
    private WorkflowRuntimeNodeService workflowRuntimeNodeService;

    @Resource
    private SSEEmitterHelper sseEmitterHelper;

    @Resource
    private JdbcCheckpointSaver jdbcCheckpointSaver;

    @Resource
    private SseEmitterManager sseEmitterManager;

    public SseEmitter streaming(User user, String workflowUuid, List<ObjectNode> userInputs, Long sessionId) {
        // 获取用户ID
        Long userId = LoginHelper.getUserId();
        // 获取登录Token（仅透传给 WfState，工作流 SSE 通过 emitter 直发，不串台）
        String tokenValue = StpUtil.getTokenValue();
        // 获取当前租户ID（@Async 线程不继承请求线程的租户上下文，需显式透传）
        String tenantId = TenantHelper.getTenantId();
        // 根据会话ID连接SSE对象（每会话一个连接，避免同用户多会话串台）
        SseEmitter sseEmitter = sseEmitterManager.connect(String.valueOf(sessionId));
        if (!sseEmitterHelper.checkOrComplete(user, sseEmitter)) {
            return sseEmitter;
        }
        Workflow workflow = workflowService.getByUuid(workflowUuid);
        if (null == workflow) {
            sseEmitterHelper.sendErrorAndComplete(user.getId(), sseEmitter, A_WF_NOT_FOUND.getInfo());
            return sseEmitter;
        } else if (Boolean.FALSE.equals(workflow.getIsEnable())) {
            sseEmitterHelper.sendErrorAndComplete(user.getId(), sseEmitter, A_WF_DISABLED.getInfo());
            return sseEmitter;
        }
        self.asyncRun(user, workflow, userInputs, sseEmitter, userId, tokenValue, sessionId, tenantId);
        return sseEmitter;
    }

    @Async
    public void asyncRun(User user, Workflow workflow, List<ObjectNode> userInputs, SseEmitter sseEmitter, Long userId, String tokenValue, Long sessionId, String tenantId) {
        // @Async 线程不继承请求线程的租户上下文, 显式设置, 避免租户缓存/隔离逻辑异常
        if (tenantId != null) {
            TenantHelper.setDynamic(tenantId);
        }
        try {
            log.info("WorkflowEngine run,userId:{},workflowUuid:{},userInputs:{}", user.getId(), workflow.getUuid(), userInputs);
            List<WorkflowComponent> components = workflowComponentService.getAllEnable();
            List<WorkflowNode> nodes = workflowNodeService.lambdaQuery()
                    .eq(WorkflowNode::getWorkflowId, workflow.getId())
                    .eq(WorkflowNode::getIsDeleted, false)
                    .list();
            List<WorkflowEdge> edges = workflowEdgeService.lambdaQuery()
                    .eq(WorkflowEdge::getWorkflowId, workflow.getId())
                    .eq(WorkflowEdge::getIsDeleted, false)
                    .list();
            WorkflowEngine workflowEngine = new WorkflowEngine(workflow,
                    sseEmitterHelper, components, nodes, edges,
                    workflowRuntimeService, workflowRuntimeNodeService, jdbcCheckpointSaver);
            workflowEngine.run(user, userInputs, sseEmitter, userId, tokenValue, sessionId);
        } finally {
            TenantHelper.clearDynamic();
        }
    }

    /**
     * 僵尸实例断点续跑入口（补遗 §5-5 D1）：给定 runtime 实例 uuid，从该 thread 最新 checkpoint 恢复执行。
     * <p>
     * 与 {@link #streaming} 不同：不新建实例、不校验用户输入（输入沿用原实例落库 input）。
     * sseEmitter 可传 null（进程重启后无 SSE 连接）：跳过实时推送，结果照常落库。
     * 配套处置入口见 {@code WorkflowRuntimeService#failZombieDoingRuntimes}。
     */
    public void resumeRuntime(String runtimeUuid, User user, SseEmitter sseEmitter, Long userId, String tokenValue,
                              Long sessionId, String tenantId) {
        // @Async/运维线程无租户上下文时显式设置（与 asyncRun 同因）
        if (tenantId != null) {
            TenantHelper.setDynamic(tenantId);
        }
        try {
            WorkflowRuntime runtime = workflowRuntimeService.getByUuidForResume(runtimeUuid);
            if (null == runtime) {
                log.error("工作流实例不存在,runtimeUuid:{}", runtimeUuid);
                return;
            }
            Workflow workflow = workflowService.getById(runtime.getWorkflowId());
            if (null == workflow) {
                log.error("工作流定义不存在,workflowId:{}", runtime.getWorkflowId());
                return;
            }
            List<WorkflowComponent> components = workflowComponentService.getAllEnable();
            List<WorkflowNode> nodes = workflowNodeService.lambdaQuery()
                    .eq(WorkflowNode::getWorkflowId, workflow.getId())
                    .eq(WorkflowNode::getIsDeleted, false)
                    .list();
            List<WorkflowEdge> edges = workflowEdgeService.lambdaQuery()
                    .eq(WorkflowEdge::getWorkflowId, workflow.getId())
                    .eq(WorkflowEdge::getIsDeleted, false)
                    .list();
            WorkflowEngine workflowEngine = new WorkflowEngine(workflow,
                    sseEmitterHelper, components, nodes, edges,
                    workflowRuntimeService, workflowRuntimeNodeService, jdbcCheckpointSaver);
            workflowEngine.resume(user, runtime, sseEmitter, userId, tokenValue, sessionId);
        } catch (Exception e) {
            log.error("resumeRuntime error,runtimeUuid:{}", runtimeUuid, e);
        } finally {
            TenantHelper.clearDynamic();
        }
    }
}
