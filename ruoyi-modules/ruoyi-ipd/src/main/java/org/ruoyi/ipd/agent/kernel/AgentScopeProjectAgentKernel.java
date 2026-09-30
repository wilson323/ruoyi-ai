package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.chat.kernel.tool.InMemoryKernelToolEffectLedger;
import org.ruoyi.chat.kernel.tool.KernelGovernedTool;
import org.ruoyi.chat.kernel.tool.KernelToolCallTrace;
import org.ruoyi.chat.kernel.tool.KernelToolGovernance;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 项目智能体 AgentScope 2.0.3 内核适配（独立于 {@code AgentScopeChatKernel}，不改其原文）。
 *
 * <p>装配要点（API 均经本机 2.0.3 JAR javap 核实）：
 * <ul>
 *   <li>身份：{@link KernelScopeKey#of} 四维收口 (projectId, personId, ipd_project_agent, runId)，
 *       userId 来自已授权运行行，不来自请求参数；非法段 fail-closed → SCOPE_REJECTED；</li>
 *   <li>模型：按运行选定的 ai_model_configs 装配，失败 → MODEL_UNAVAILABLE，不回落默认模型；</li>
 *   <li>Skill：正文已在服务端经 ClasspathSkillRepository 读取并校验 sha256，注入系统提示；
 *       原生动态 Skill/工作区 Skill 关闭，避免第二条未审计的加载路径；</li>
 *   <li>工具：仅注册选定的只读工具，外包 {@link KernelGovernedTool}，执行前经
 *       {@link KernelToolGovernance}（裁决唯一源 {@link ToolPolicyEngine}，READ_ONLY）；
 *       构建后校验 Toolkit 工具集 ⊆ 选定集，多出即拒绝运行；</li>
 *   <li>状态：每次运行新 session（sessionId=runId），不接 MysqlAgentStateStore；
 *       业务状态/事件由 IPD 持久层承担，AgentState 不作任务完成依据。</li>
 * </ul>
 */
public class AgentScopeProjectAgentKernel implements ProjectAgentKernel {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeProjectAgentKernel.class);

    static final String ERR_SCOPE_REJECTED = "SCOPE_REJECTED";
    static final String ERR_MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    static final String ERR_KERNEL_ERROR = "KERNEL_ERROR";
    static final String ERR_STREAM_ERROR = "STREAM_ERROR";
    static final String ERR_RUN_TIMEOUT = "RUN_TIMEOUT";

    private final ProjectAgentModelAssembler modelAssembler;
    private final ProjectKnowledgeSearchTool.KnowledgeRetriever retriever;
    private final Path workspaceRoot;
    private final int maxIters;

    /**
     * @param modelAssembler 模型装配
     * @param retriever 项目资料检索端口
     * @param workspaceRoot 工作区根
     * @param maxIters ReAct 最大迭代数（工具调用轮次上限）
     */
    public AgentScopeProjectAgentKernel(ProjectAgentModelAssembler modelAssembler,
                                        ProjectKnowledgeSearchTool.KnowledgeRetriever retriever,
                                        Path workspaceRoot, int maxIters) {
        this.modelAssembler = Objects.requireNonNull(modelAssembler, "modelAssembler");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.maxIters = Math.max(1, maxIters);
    }

    /** {@inheritDoc} */
    @Override
    public Disposable execute(ProjectAgentRunSpec spec, ProjectAgentEventSink sink) {
        KernelScopeKey.Scope scope;
        try {
            scope = KernelScopeKey.of(String.valueOf(spec.projectId()), String.valueOf(spec.personId()),
                ProjectAgentConstants.AGENT_ID, String.valueOf(spec.runId()));
        } catch (IllegalArgumentException rejected) {
            log.warn("project_agent operation=SCOPE status=REJECTED runId={}", spec.runId());
            sink.onError(ERR_SCOPE_REJECTED);
            return () -> { };
        }
        Model model;
        try {
            model = modelAssembler.assemble(spec.model());
        } catch (RuntimeException e) {
            log.warn("project_agent operation=MODEL status=REJECTED runId={} errorType={}",
                spec.runId(), e.getClass().getName());
            sink.onError(ERR_MODEL_UNAVAILABLE);
            return () -> { };
        }
        HarnessAgent agent;
        try {
            agent = buildAgent(spec, model, sink);
        } catch (Exception e) {
            log.error("project_agent operation=BUILD status=FAILED runId={} errorType={}",
                spec.runId(), e.getClass().getName());
            sink.onError(ERR_KERNEL_ERROR);
            return () -> { };
        }
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(spec.message()).build();
        EventBridge bridge = new EventBridge(sink, spec.runId());
        return Flux.using(() -> agent,
                a -> a.streamEvents(msg, scope.toRuntimeContext()),
                HarnessAgent::close)
            .timeout(spec.timeout())
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(bridge::dispatch, bridge::error, bridge::complete);
    }

    /**
     * 按运行装配 HarnessAgent（包级可见供装配面测试：不触发模型调用）。
     *
     * @param spec 运行输入
     * @param model 已装配模型
     * @param sink 事件出口（工具真实检索结果上报 SOURCE）
     * @return 已构建智能体
     * @throws Exception 装配失败或工具集越界
     */
    HarnessAgent buildAgent(ProjectAgentRunSpec spec, Model model, ProjectAgentEventSink sink) throws Exception {
        Path workspace = ProjectAgentWorkspace.prepare(workspaceRoot, String.valueOf(spec.projectId()),
            String.valueOf(spec.personId()), ProjectAgentConstants.AGENT_ID);
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (String toolId : spec.toolIds()) {
            ToolDescriptor descriptor = ProjectAgentToolCatalog.descriptor(toolId);
            if (descriptor == null) {
                throw new IllegalStateException("tool not implemented: " + toolId);
            }
            descriptors.add(descriptor);
        }
        KernelToolGovernance governance = new KernelToolGovernance(new ToolPolicyEngine(descriptors),
            HarnessPermissionMode.READ_ONLY, new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
        Toolkit toolkit = new Toolkit();
        if (spec.toolIds().contains(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)) {
            toolkit.registerAgentTool(KernelGovernedTool.wrap(
                new ProjectKnowledgeSearchTool(spec.projectId(), retriever, sink::onSource), governance));
        }
        ToolsConfig toolsConfig = new ToolsConfig();
        toolsConfig.setDeny(List.of("web_fetch", "web_search", "wait_async_results"));
        HarnessAgent built = HarnessAgent.builder()
            .name(ProjectAgentConstants.AGENT_ID)
            .sysPrompt(ProjectAgentPrompt.build(spec))
            .model(model)
            .toolkit(toolkit)
            .maxIters(maxIters)
            .disableFilesystemTools()
            .disableShellTool()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableTranscript()
            .disableSessionPersistence()
            .enableAgentTracingLog(false)
            .disableSubagents()
            .disableDynamicSubagents()
            .disableDynamicSkills()
            .disableDefaultWorkspaceSkills()
            .skillsEnabled(false)
            .toolsConfig(toolsConfig)
            .workspace(workspace)
            .build();
        Set<String> exposed = new HashSet<>(built.getToolkit().getToolNames());
        if (!new HashSet<>(spec.toolIds()).containsAll(exposed)) {
            built.close();
            throw new IllegalStateException("project agent exposes unselected tools");
        }
        return built;
    }

    /** 原生事件 → IPD 事件出口（只映射合同事件；推理原文不外发不落库）。 */
    private static final class EventBridge {

        private final ProjectAgentEventSink sink;
        private final Long runId;

        private EventBridge(ProjectAgentEventSink sink, Long runId) {
            this.sink = sink;
            this.runId = runId;
        }

        private void dispatch(AgentEvent event) {
            if (event instanceof TextBlockDeltaEvent text) {
                sink.onText(text.getDelta());
            } else if (event instanceof ToolCallStartEvent call) {
                sink.onToolCall(call.getToolCallId(), call.getToolCallName());
            } else if (event instanceof ToolResultEndEvent result) {
                sink.onToolResult(result.getToolCallId(), result.getToolCallName(),
                    result.getState() == null ? null : result.getState().name());
            } else if (event instanceof ModelCallStartEvent) {
                sink.onStep("MODEL_CALL", Map.of());
            } else if (event instanceof ModelCallEndEvent end) {
                sink.onStep("MODEL_CALL", modelCallDetail(end.getUsage()));
            } else if (event instanceof ExceedMaxItersEvent) {
                sink.onStep("EXCEED_MAX_ITERS", Map.of());
            }
        }

        private void error(Throwable err) {
            boolean timeout = err instanceof TimeoutException;
            log.error("project_agent operation=STREAM status=FAILED runId={} errorType={}",
                runId, err.getClass().getName());
            sink.onError(timeout ? ERR_RUN_TIMEOUT : ERR_STREAM_ERROR);
        }

        private void complete() {
            sink.onComplete();
        }
    }

    /**
     * 把模型结束事件上的用量写成步骤明细。没有 usage 时不编造 token。
     * {@code ChatUsage.getTime()} 的单位未经本仓实证，不写入耗时。
     *
     * @param usage 原生用量，可为 null
     * @return 含 inputTokens / outputTokens 的明细；无用量时为空 map
     */
    static Map<String, Object> modelCallDetail(ChatUsage usage) {
        if (usage == null) {
            return Map.of();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("inputTokens", usage.getInputTokens());
        detail.put("outputTokens", usage.getOutputTokens());
        return detail;
    }
}
