package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ActingInput;
import java.time.Duration;
import java.util.function.Function;
import io.agentscope.harness.agent.HarnessAgent;
import org.ruoyi.ipd.mapper.IpdAgentMemoryMapper;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.chat.kernel.tool.InMemoryKernelToolEffectLedger;
import org.ruoyi.chat.kernel.tool.KernelGovernedTool;
import org.ruoyi.chat.kernel.tool.KernelToolCallTrace;
import org.ruoyi.chat.kernel.tool.KernelToolGovernance;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.mapper.ProductLineNameMapper;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 项目智能体 AgentScope 2.0.3 内核适配（独立于 {@code AgentScopeChatKernel}，不改其原文）。
 *
 * <p>装配要点（API 均经本机 2.0.3 JAR javap 核实）：
 * <ul>
 *   <li>身份：{@link KernelScopeKey#of} 四维收口 (projectId, personId, ipd_project_agent, runId)，
 *       userId 来自已授权运行行，不来自请求参数；非法段 fail-closed → SCOPE_REJECTED；</li>
 *   <li>模型：按运行选定的 ai_model_configs 装配，失败 → MODEL_UNAVAILABLE，不回落默认模型；</li>
 *   <li>Skill：正文经 ClasspathSkillRepository 与本次冻结 sha/version/body 复核，由官方仓库渐进发现/加载；
 *       动态技能与管理能力启用，发布与可见性经官方 owner 扩展点约束；</li>
 *   <li>工具：选定业务检索外包 {@link KernelGovernedTool}，执行前经
 *       {@link KernelToolGovernance}（裁决唯一源 {@link ToolPolicyEngine}，READ_ONLY）；
 *       官方基础能力完整装配，构建后及子任务 acting 前统一保护所有权、权限与审计；</li>
 *   <li>状态：每次运行新 session（sessionId=runId），生产复用SDK RedissonAgentStateStore；
 *       业务状态/事件由 IPD 持久层承担，AgentState 不作任务完成依据。</li>
 * </ul>
 */
public class AgentScopeProjectAgentKernel implements ProjectAgentKernel {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeProjectAgentKernel.class);

    /**
     * 事件循环专用调度器，不占用全局 {@code boundedElastic}。
     * 工具侧已经两次订在那个池上：AgentScope {@code ToolExecutor.applyScheduling}
     * 和 {@code ProjectKnowledgeSearchTool.callAsync}。
     * 事件循环若再订上去，同一次回复里的两次检索会经 {@code mergeSequential}
     * 把任务排进仍在等待结果的那条工人队列，工具体不会开始，整轮只能空转到超时。
     */
    static final Scheduler AGENT_LOOP = Schedulers.newBoundedElastic(
        4, Integer.MAX_VALUE, "ipd-project-agent-loop", 60, true);

    static final String ERR_SCOPE_REJECTED = "SCOPE_REJECTED";
    static final String ERR_MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    static final String ERR_KERNEL_ERROR = "KERNEL_ERROR";
    static final String ERR_STREAM_ERROR = "STREAM_ERROR";

    /**
     * 运行失败的错误码判定。判定必须走完整异常链，不能只看顶层类型。
     *
     * <p>run 2106378468009717761 实测反证：记忆抽取的 {@link TimeoutException} 被脱敏包装成
     * {@code IllegalStateException}，顶层 {@code instanceof} 判据落进 else，对外报
     * {@code STREAM_ERROR}「模型输出中断」——而正文与结束事件早已推送落库，与事实相反。
     * 包装类的存在正是为了让对外文案脱敏，不能反过来让脱敏把原因也一起掩盖。
     */
    static String classifyStreamFailure(Throwable err) {
        for (Throwable current = err; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException) return ERR_RUN_TIMEOUT;
            if (current.getCause() == current) break;
        }
        return ERR_STREAM_ERROR;
    }

    /** 异常链最深处的类型名，仅用于日志定位；自引用链防御性截断。 */
    static String rootCauseType(Throwable err) {
        Throwable current = err;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? "unknown" : current.getClass().getName();
    }
    static final String ERR_RUN_TIMEOUT = "RUN_TIMEOUT";

    private final ProjectAgentModelAssembler modelAssembler;
    private final ProjectKnowledgeSearchTool.KnowledgeRetriever retriever;
    private final Path workspaceRoot;
    private final int maxIters;
    private final ProductLineNameMapper lineNames;
    private final io.agentscope.core.hook.Hook auditHook;
    private volatile IpdAgentMemoryMapper longTermMemoryMapper;
    private io.agentscope.core.state.AgentStateStore stateStore = new InMemoryAgentStateStore();
    private java.util.function.Consumer<ProjectAgentRunSpec> runtimeAccess = spec -> { };
    private ProjectAgentArtifactProviderFactory artifactProviderFactory;
    private io.agentscope.harness.agent.filesystem.remote.store.BaseStore collaborationStore;
    private AnswerMeHtmlRenderer htmlRenderer;

    public void setCollaborationStore(io.agentscope.harness.agent.filesystem.remote.store.BaseStore store) {
        collaborationStore = Objects.requireNonNull(store, "Official collaboration store is required");
    }

    /**
     * @param modelAssembler 模型装配
     * @param retriever 项目资料检索端口
     * @param workspaceRoot 工作区根
     * @param maxIters ReAct 最大迭代数（工具调用轮次上限）
     */
    public AgentScopeProjectAgentKernel(ProjectAgentModelAssembler modelAssembler,
                                        ProjectKnowledgeSearchTool.KnowledgeRetriever retriever,
                                        Path workspaceRoot, int maxIters) {
        this(modelAssembler, retriever, workspaceRoot, maxIters, null);
    }

    /**
     * @param modelAssembler 模型装配
     * @param retriever 项目资料检索端口
     * @param workspaceRoot 工作区根
     * @param maxIters ReAct 最大迭代数（工具调用轮次上限）
     * @param lineNames 读取产品线已记下的服务标识；展示名不参与选择
     */
    public AgentScopeProjectAgentKernel(ProjectAgentModelAssembler modelAssembler,
                                        ProjectKnowledgeSearchTool.KnowledgeRetriever retriever,
                                        Path workspaceRoot, int maxIters,
                                        ProductLineNameMapper lineNames) {
        this(modelAssembler, retriever, workspaceRoot, maxIters, lineNames, null);
    }

    public AgentScopeProjectAgentKernel(ProjectAgentModelAssembler modelAssembler,
                                        ProjectKnowledgeSearchTool.KnowledgeRetriever retriever,
                                        Path workspaceRoot, int maxIters, ProductLineNameMapper lineNames,
                                        io.agentscope.core.hook.Hook auditHook) {
        this.auditHook = auditHook;
        this.modelAssembler = Objects.requireNonNull(modelAssembler, "modelAssembler");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.maxIters = Math.max(1, maxIters);
        this.lineNames = lineNames;
    }

    /** 生产配置复用SDK Redis状态存储；业务完成仍由原run/事件权威。 */
    public void setStateStore(io.agentscope.core.state.AgentStateStore store) {
        this.stateStore = Objects.requireNonNull(store, "stateStore");
    }

    /**
     * 绑定长期记忆存储；未绑定时本仓不挂官方 LongTermMemory hook（记忆能力缺席，
     * **不是**降级开关——装配层不存在「关掉记忆」这个动作）。
     */
    public void setLongTermMemoryMapper(IpdAgentMemoryMapper mapper) {
        this.longTermMemoryMapper = mapper;
    }

    /** 生产配置绑定当前 Person/租户/项目权限的原业务访问服务；不导入 SDK 权限快照。 */
    public void setRuntimeAccess(java.util.function.Consumer<ProjectAgentRunSpec> access) {
        this.runtimeAccess = Objects.requireNonNull(access, "runtimeAccess");
    }

    public void setArtifactProviderFactory(ProjectAgentArtifactProviderFactory factory) {
        this.artifactProviderFactory = Objects.requireNonNull(factory, "artifactProviderFactory");
    }

    /** 生产配置绑定 HTML 渲染引擎；未绑定时勾选 render_html_page 的运行 fail-loud 拒建。 */
    public void setHtmlRenderer(AnswerMeHtmlRenderer renderer) {
        this.htmlRenderer = Objects.requireNonNull(renderer, "htmlRenderer");
    }

    /** Read-only original-run validation before the business approval consumer performs its CAS. */
    public void preflightChildResume(ProjectAgentRunSpec spec,ProjectAgentChildLineageRegistry.ChildApproval approval) {
        runtimeAccess.accept(spec);
        try {
            Path workspace=ProjectAgentWorkspace.existing(workspaceRoot,String.valueOf(spec.projectId()),String.valueOf(spec.personId()),ProjectAgentConstants.AGENT_ID);
            var selected=new FrozenProjectAgentSkills(spec.skills());
            ProjectAgentChildPreflight.preflight(spec,approval,stateStore,officialFactoryBuilder(spec,workspace,selected),workspace,()->runtimeAccess.accept(spec));
        } catch(java.io.IOException unavailable) {throw new IllegalStateException("Original child workspace is unavailable",unavailable);}
    }
    private HarnessAgent.Builder officialFactoryBuilder(ProjectAgentRunSpec spec,Path workspace,FrozenProjectAgentSkills selectedSkills) {
        return HarnessAgent.builder().name(ProjectAgentConstants.AGENT_ID).sysPrompt(ProjectAgentPrompt.build(spec) + ProjectAgentOutputContract.prompt())
            .skillRepository(selectedSkills).skillFilter(selectedSkills.filter()).workspace(workspace)
            .permissionContext(ProjectAgentOfficialPermissions.workspace()).maxIters(maxIters)
            // [AgentScope 2.0.3 陷阱 · 勿单独设置 .maxRetries(n)] 它不生效且无告警：ModelConfig.maxRetries 被
// ReActAgent.buildGenerateOptions() 降级为 ExecutionConfig.maxAttempts 注入 GenerateOptions 的
// 【fallback 位】，而本行 modelExecutionConfig 在【primary 位】；mergeOptions 语义是
// 「primary 非 null 即取 primary」，MODEL_DEFAULTS 6 字段全非 null（含 maxAttempts=3）故永远取不到。
// 要改重试次数请改本行 ExecutionConfig，不要加 .maxRetries。
            .memory(ProjectAgentNativeProfile.memory())
            .modelExecutionConfig(ExecutionConfig.MODEL_DEFAULTS)
            .toolExecutionConfig(ExecutionConfig.TOOL_DEFAULTS);
    }

    /** {@inheritDoc} */
    @Override
    public Disposable execute(ProjectAgentRunSpec spec, ProjectAgentEventSink originalSink) {
        ProjectAgentEventSink sink = new ProjectAgentRuntimeAccessSink(originalSink, spec, runtimeAccess);
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
            sink.requireActiveOwnership();
            model = modelAssembler.assemble(spec.model(), spec.personId(), spec.runId());
        } catch (RuntimeException e) {
            log.warn("project_agent operation=MODEL status=REJECTED runId={} errorType={}",
                spec.runId(), e.getClass().getName());
            sink.onError(ERR_MODEL_UNAVAILABLE);
            return () -> { };
        }
        HarnessAgent agent;
        ManagedAgent managed;
        DeadlineMiddleware deadline = new DeadlineMiddleware(spec.timeout());
        try {
            sink.requireActiveOwnership();
            managed = buildManagedAgent(spec, model, sink, deadline);
            agent = managed.agent();
        } catch (Exception e) {
            log.error("project_agent operation=BUILD status=FAILED runId={} errorType={}",
                spec.runId(), e.getClass().getName());
            sink.onError(ERR_KERNEL_ERROR);
            return () -> { };
        }
        List<Msg> messages;
        try {
            messages = !spec.serverChildResumes().isEmpty() && spec.serverResumeMessages() == null ? List.of() : spec.serverResumeMessages() != null ? spec.serverResumeMessages()
                : spec.aguiInput() != null && !spec.aguiInput().getMessages().isEmpty() ? ProjectAgentAguiInput.messages(spec.aguiInput(), Map.of())
                : List.of(Msg.builder().role(MsgRole.USER).textContent(spec.message()).build());
            if (messages.isEmpty() && spec.serverChildResumes().isEmpty()) throw new IllegalArgumentException("Agent input messages are required");
        } catch (RuntimeException invalidInput) {
            try { managed.agent().close(); managed.filesystem().verifyReleased(); } finally {
                if (managed.artifactProvider() != null) managed.artifactProvider().claims().close();
            }
            try {
                if (!managed.preserveCheckpoint(spec)) {
                    checkpointOwnership(sink).withActiveOwnership(() -> { managed.state().sealAndDelete(); return null; });
                }
            } catch (org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership.OwnershipLost ignored) {
                // 失去执行租约后不能清理后继 owner 的检查点。
            }
            sink.onError(ERR_SCOPE_REJECTED);
            return () -> { };
        }
        if (managed.requiresCommittedReceipt()) {
            try { sink.registerTerminalSuccessReceipt(() -> managed.terminalCommitted().set(true)); }
            catch (RuntimeException unavailableReceipt) {
                try { agent.close(); managed.filesystem().verifyReleased(); } finally {
                    if (managed.artifactProvider() != null) managed.artifactProvider().claims().close();
                }
                sink.onError(ERR_KERNEL_ERROR);
                return () -> { };
            }
        }
        EventBridge bridge = new EventBridge(sink, spec.runId(), spec.aguiInput(), managed.lineage(), managed.state(), () -> {
            var checkpoint = managed.state().getVersioned(scope.userId(), scope.sessionId(),
                "agent_state", io.agentscope.core.state.AgentState.class);
            if (!checkpoint.isPresent() || checkpoint.version() < 0)
                throw new IllegalStateException("Official interrupted checkpoint has not been persisted");
            return checkpoint.version();
        });
        var runtimeContext = ProjectAgentAguiRuntimeContext.prepare(agent, spec.aguiInput(), scope.toRuntimeContext());
        var deadlineReached = new java.util.concurrent.atomic.AtomicBoolean();
        return usingPreservingFailure(() -> agent,
                a -> spec.serverChildResumes().isEmpty() ? a.streamEvents(messages, runtimeContext)
                    : new ProjectAgentChildResumeDispatcher(managed.subagentScope(),managed.state(),sink::requireChildResumeConsumed,
                        sink::recordChildCompletion,sink::loadChildCompletions)
                        .resume(a,runtimeContext,spec.serverChildResumes().stream().map(item ->
                            new ProjectAgentChildResumeDispatcher.Resume(item.approval(),item.messages())).toList(),messages),
                a -> {
                    deadline.cancel();
                    if (!sink.isPaused() && !bridge.hasPendingPause()) a.interrupt(runtimeContext);
                    // close 失败必须向流传播并保留检查点，不能在 finally 内删掉恢复依据。
                    try {
                        a.close();
                        if (managed.childConsumers() != null) managed.childConsumers().closeChildren();
                        managed.filesystem().verifyReleased();
                    } catch (RuntimeException cleanupFailure) {
                        managed.filesystem().lifecycle().recordFailure(cleanupFailure);
                        throw cleanupFailure;
                    } finally {
                        if (managed.artifactProvider() != null) managed.artifactProvider().claims().close();
                    }
                    try {
                        if (!sink.isPaused() && !bridge.protectChildCheckpoint
                            && !managed.preserveCheckpoint(spec)) checkpointOwnership(sink).withActiveOwnership(() -> {
                            managed.state().sealAndDelete();
                            return null;
                        });
                    } catch (org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership.OwnershipLost ignored) {
                        // 旧owner只释放SDK资源，不得清理新epoch的checkpoint。
                    }
                })
            .concatWith(Mono.defer(() -> {
                // 官方 SESSION release 先于流完成；警告日志不能作为归档成功证据。
                var receipts = managed.filesystem().verifyReleased();
                if (!receipts.isEmpty()) sink.onStep("SANDBOX_ARCHIVED", Map.of("snapshots", receipts));
                return Mono.empty();
            }))
            // takeUntilOther 的伴随流须正常发信号才能取消主订阅；伴随流报错只向下游报错。
            .takeUntilOther(Mono.delay(spec.timeout()).doOnNext(ignored -> deadlineReached.set(true)))
            .concatWith(Mono.defer(() -> deadlineReached.get()
                ? Mono.error(new TimeoutException("project agent deadline exceeded")) : Mono.empty()))
            .subscribeOn(AGENT_LOOP)
            .subscribe(bridge::dispatch, bridge::error, bridge::complete);
    }

    static <T, R> Flux<T> usingPreservingFailure(java.util.concurrent.Callable<R> resource,
        java.util.function.Function<R, org.reactivestreams.Publisher<T>> source,
        java.util.function.Consumer<R> cleanup) {
        return Flux.defer(() -> {
            var upstreamFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            return Flux.using(resource,
                owned -> Flux.defer(() -> Flux.from(source.apply(owned))).doOnError(upstreamFailure::set),
                owned -> {
                    try { cleanup.accept(owned); }
                    catch (RuntimeException cleanupFailure) {
                        Throwable original = upstreamFailure.get();
                        if (original == null) throw cleanupFailure;
                        if (original != cleanupFailure) original.addSuppressed(cleanupFailure);
                        // 原错误仍向下游传播；清理已失败，不能继续执行seal/delete。
                    }
                });
        });
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
        return buildManagedAgent(spec, model, sink, new DeadlineMiddleware(spec.timeout())).agent();
    }

    private record ManagedAgent(HarnessAgent agent, ProjectAgentTemporaryStateStore state,
                                ProjectAgentChildLineageRegistry lineage,
                                ProjectAgentArtifactProviderFactory.Provider artifactProvider,
                                java.util.concurrent.atomic.AtomicBoolean terminalCommitted,
                                boolean requiresCommittedReceipt, ProjectAgentSubagentScopeMiddleware subagentScope,
                                ProjectAgentOfficialSandbox.ManagedFilesystem filesystem,
                                ProjectAgentChildConsumers childConsumers) {
        boolean preserveCheckpoint(ProjectAgentRunSpec spec) {
            return filesystem.lifecycle().failed() || !terminalCommitted.get() && (requiresCommittedReceipt || preserveApprovalCheckpoint(spec, lineage));
        }
    }

    /** 恢复消费者或审批事务失败时保留原检查点，终态清理不得先销毁恢复证据。 */
    private static boolean preserveApprovalCheckpoint(ProjectAgentRunSpec spec,
                                                       ProjectAgentChildLineageRegistry lineage) {
        return spec.serverResumeMessages() != null || !spec.serverChildResumes().isEmpty()
            || lineage.hasPendingChildApprovals();
    }

    private static ProjectAgentEventSink checkpointOwnership(ProjectAgentEventSink sink) {
        return sink instanceof ProjectAgentRuntimeAccessSink guarded ? guarded.checkpointOwnership() : sink;
    }

    private ManagedAgent buildManagedAgent(ProjectAgentRunSpec spec, Model model, ProjectAgentEventSink sink,
                                    DeadlineMiddleware deadline) throws Exception {
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
            HarnessPermissionMode.WORKSPACE_WRITE, new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
        // 同一次回复里的两次检索不要并行订到同一个 boundedElastic。
        Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        if (spec.toolIds().contains(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)) {
            ProjectKnowledgeSearchTool.KnowledgeRetriever boundRetriever = retriever;
            if (retriever instanceof ProjectKnowledgeRetriever concrete) {
                Long personId = spec.personId();
                boundRetriever = (projectId, docType, query) ->
                    concrete.retrieve(personId, projectId, docType, query);
            }
            toolkit.registerAgentTool(KernelGovernedTool.wrap(
                new InlineKnowledgeSearchTool(spec.projectId(), boundRetriever, sink::onTrustedSource), governance));
        }
        sink.requireActiveOwnership();
        ProductLineMcpTool.bind(toolkit, governance, spec, lineNames, sink);
        // 联网搜索：调研与外部事实核查的基础能力。密钥缺失则不注册（不装空壳工具）。
        ProjectAgentMetasoSearch.bind(toolkit, ProjectAgentMetasoSearch.apiKeyFromEnvironment());
        ToolsConfig toolsConfig = new ToolsConfig();
        FrozenProjectAgentSkills selectedSkills = new FrozenProjectAgentSkills(spec.skills());
        ProjectAgentSkillGovernance skillGovernance = new ProjectAgentSkillGovernance(workspace, spec, selectedSkills);
        skillGovernance.bindSink(sink);
        var parentRef = new java.util.concurrent.atomic.AtomicReference<HarnessAgent>();
        var trustedScope = KernelScopeKey.of(String.valueOf(spec.projectId()), String.valueOf(spec.personId()),
            ProjectAgentConstants.AGENT_ID, String.valueOf(spec.runId()));
        var childConsumersRef = new java.util.concurrent.atomic.AtomicReference<ProjectAgentChildConsumers>();
        var subagentScope = new ProjectAgentSubagentScopeMiddleware(
            new ProjectAgentFoundationTools.Scope(String.valueOf(spec.projectId()), String.valueOf(spec.personId()),
                String.valueOf(spec.runId()), workspace), trustedScope.toRuntimeContext(),
            sink::requireActiveOwnership, (leaf, parent, context) ->
                childConsumersRef.get() == null ? leaf : childConsumersRef.get().bind(leaf, parent, context)).bindParent(parentRef::get);
        subagentScope.lineage().bindPolicy(ProjectAgentChildPreflight.policyHash(spec));
        subagentScope.onRegistered((actor, context) -> {
            io.agentscope.core.ReActAgent actual = actor instanceof HarnessAgent child
                ? child.getDelegate() : actor instanceof io.agentscope.core.ReActAgent react ? react : null;
            if (actual == null) throw new IllegalStateException("Official ReAct actor is required");
            var existing = actual.getAgentState(context.getUserId(), context.getSessionId()).getPermissionContext();
            var extended = ProjectAgentOfficialPermissions.extend(existing);
            if (!existing.getAllowRules().equals(extended.getAllowRules())) {
                actual.replacePermissionContext(context.getUserId(), context.getSessionId(), extended);
            }
        });
        ProjectAgentOfficialToolGovernance officialGovernance = new ProjectAgentOfficialToolGovernance(sink,
            trustedScope, (actor, context) -> { subagentScope.lineage().requireKnown(actor, context); return true; }).childLineage(subagentScope.lineage());
        officialGovernance.selectedSkillReads(skill -> {
            var approved = selectedSkills.getSkill(skill.getName());
            return approved != null && Objects.equals(approved.getSkillContent(), skill.getSkillContent())
                && Objects.equals(approved.getMetadataValue("version"), skill.getMetadataValue("version"))
                && Objects.equals(approved.getResources(), skill.getResources());
        });
        var artifactProvider = artifactProviderFactory == null ? null : artifactProviderFactory.create(spec,
            trustedScope.toRuntimeContext(), sink, subagentScope.lineage()::requireKnown);
        if (spec.toolIds().contains(ProjectAgentToolCatalog.HTML_PAGE_RENDER)) {
            // 勾选即要求引擎与产物链全部就绪：fail-loud，不静默降级为不可用。
            if (htmlRenderer == null) {
                throw new IllegalStateException("HTML render engine is not assembled");
            }
            if (artifactProvider == null) {
                throw new IllegalStateException("HTML render requires the artifact delivery chain");
            }
            // 交付链（ArtifactDeliveryTarget.deliver）按服务端执行声明门禁放行：渲染工具
            // 必须在执行期持有与官方 deliver_artifact 同源的 Claim，否则交付被拒。
            toolkit.registerAgentTool(KernelGovernedTool.wrap(
                new ExecutionClaimBoundTool(new HtmlPageRenderTool(htmlRenderer, artifactProvider.target(),
                    step -> sink.onStep("HTML_RENDERED", step)), artifactProvider.claims()), governance));
        }
        for (String name : List.copyOf(toolkit.getToolNames())) {
            AgentTool original = toolkit.getTool(name);
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new OwnershipGuardedTool(original, sink));
        }
        if (artifactProvider != null) officialGovernance.executionClaims(artifactProvider.claims());
        var terminalCommitted = new java.util.concurrent.atomic.AtomicBoolean();
        boolean requiresCommittedReceipt = true;
        ProjectAgentSafeTranscriptStore safeTranscript = new ProjectAgentSafeTranscriptStore(
            spec.model().apiKey() == null ? List.of() : List.of(spec.model().apiKey()));
        ProjectAgentChildConsumers childConsumers = artifactProvider == null ? null :
            new ProjectAgentChildConsumers(parentRef::get, trustedScope.toRuntimeContext(), safeTranscript,
                spec.tenantId(), artifactProvider.target(),
                context -> Mono.fromRunnable(sink::requireActiveOwnership), sink::requireActiveOwnership)
                .failureSink(failure -> sink.onStep("CHILD_EXECUTION_FAILED", Map.of(
                    "childSession", failure.childSession(), "reason", failure.reasonCode(),
                    "category", failure.exceptionCategory())))
                .childLineage(subagentScope.lineage());
        childConsumersRef.set(childConsumers);
        ProjectAgentEventSink checkpointOwnership = checkpointOwnership(sink);
        ProjectAgentTemporaryStateStore temporaryState = new ProjectAgentTemporaryStateStore(stateStore,
            trustedScope, checkpointOwnership, subagentScope.lineage(), String.valueOf(spec.runId()));
        org.ruoyi.chat.kernel.OfficialAgentTraceLogging.install();
        var filesystem = ProjectAgentOfficialSandbox.managedFilesystem(workspace, "python:3.13-alpine", sink);
        sink.registerTemporaryStateCleanup(() -> {
            if (terminalCommitted.get() || !requiresCommittedReceipt
                && !preserveApprovalCheckpoint(spec, subagentScope.lineage())) {
                filesystem.verifyReleased();
                temporaryState.sealAndDelete();
            }
        });
        // 主运行、压缩、子调用和长期记忆抽取复用同一个计量/权限包装。
        var meteredModel = new ProjectAgentMeteredModel(model, sink, checkpointOwnership,
            spec.frozenModels() == null ? null : spec.frozenModels().primaryIdentity());
        var longTermMemory = longTermMemoryMapper == null ? null
            : new ProjectScopedLongTermMemory(spec.projectId(), spec.personId(), spec.runId(),
                  longTermMemoryMapper, meteredModel);
        // 已配置的官方回退模型必须成功装配；失败向原运行错误链传播，不能静默跳过。
        Model fallbackModel = spec.frozenModels() == null
            ? modelAssembler.assembleFallback(spec.model(), spec.personId(), spec.runId())
            : modelAssembler.assembleConfiguredFallback(spec.frozenModels().fallback(), spec.personId(), spec.runId());

        HarnessAgent.Builder builder = officialFactoryBuilder(spec,workspace,selectedSkills)
            .middleware(filesystem.lifecycle())
            .enableSkillManageTool(SkillManageConfig.defaults())
            .enableSkillPromotionGate(skillGovernance, skillGovernance)
            .enableSkillCurator(SkillCuratorConfig.defaults())
            .memory(filesystem.lifecycle().memoryConfig(ProjectAgentNativeProfile.memory(), meteredModel))
            .enablePlanMode()
            .enableTaskList()
            .enableMetaTool(true)
            .enableAgentTracingLog(true)
            .enablePendingToolRecovery(true)
            // 本地嵌入经常超过 30 秒。到点后官方后台卸载会取消正在进行的嵌入请求，
            // 项目资料检索就被记成失败。检索要等嵌入返回，不能在 30 秒被掐断。
            .asyncToolTimeout(Duration.ofSeconds(120))
            .middleware(safeTranscript)
            .transcriptStore(safeTranscript)
            .middleware(subagentScope)
            .middleware(officialGovernance)
            .middleware(new ProjectAgentSkillRuntimeGuard(selectedSkills, spec, sink,
                () -> java.util.Objects.requireNonNull(parentRef.get(), "Official root agent is not bound")
                    .getWorkspaceManager().getFilesystem()))
            .model(meteredModel)
            .permissionContext(ProjectAgentOfficialPermissions.workspace())
            .hook(auditHook)
            .middleware(deadline)
            .middleware(new OwnershipMiddleware(sink))
            // 官方 OTel 追踪（core.tracing）：invoke_agent/chat/execute_tool span；未配 SDK 时 noop 零开销，
            // 构造器自带幂等 Reactor hook 注册，不占 legacy TracerRegistry 路径。
            .middleware(new OtelTracingMiddleware())
            .toolkit(toolkit)
            .maxIters(maxIters)
            // 模型/工具调用超时与重试套官方默认（模型5min+3次尝试，工具5min单次）；不设时SDK不套任何重试。
            // [AgentScope 2.0.3 陷阱 · 勿单独设置 .maxRetries(n)] 它不生效且无告警：ModelConfig.maxRetries 被
// ReActAgent.buildGenerateOptions() 降级为 ExecutionConfig.maxAttempts 注入 GenerateOptions 的
// 【fallback 位】，而本行 modelExecutionConfig 在【primary 位】；mergeOptions 语义是
// 「primary 非 null 即取 primary」，MODEL_DEFAULTS 6 字段全非 null（含 maxAttempts=3）故永远取不到。
// 要改重试次数请改本行 ExecutionConfig，不要加 .maxRetries。
            .modelExecutionConfig(ExecutionConfig.MODEL_DEFAULTS)
            .toolExecutionConfig(ExecutionConfig.TOOL_DEFAULTS)
            // 长对话压缩：官方 Builder 默认即装配全默认配置（主模型+官方摘要prompt+动态阈值）。
            // 此处显式声明与默认等价的配置，固化意图防官方默认漂移；溢出硬失败仅在 disableCompaction 时出现。
            .compaction(CompactionConfig.builder().build())
            // 官方 provider 负责文件与命令执行，缺失镜像明确失败，不回退主机执行。
            .filesystem(filesystem.spec())
            // SDK checkpoint与业务run状态分离，session使用可信person/run身份。
            .stateStore(temporaryState)
            // 官方无参 getAgentState() 以 (null, defaultSessionId) 访问 store；设为裸 runId 使其与
            // AG-UI 运行时 (runId, runId) 身份落同一 official 存储槽（storageSession 同走 Base64(runId)
            // 分支），LTM 的 AgentStateMemoryView 读到的就是运行时真实短期记忆，不错槽。
            .defaultSessionId(String.valueOf(spec.runId()))
            .toolsConfig(toolsConfig)
            .workspace(workspace);
        if (fallbackModel != null) {
            // 回退调用同计量：主/回退 token 同账本，不留回退侧计量盲区。
            builder.fallbackModel(new ProjectAgentMeteredModel(fallbackModel, sink, checkpointOwnership,
                spec.frozenModels() == null ? null : spec.frozenModels().fallbackIdentity()));
        }
        if (artifactProvider != null) builder.artifactDeliveryTarget(artifactProvider.target());
        if (childConsumers != null) builder.middleware(childConsumers);
        if (collaborationStore != null) {
            var collaboration = ProjectAgentOfficialCollaboration.create(trustedScope, collaborationStore,
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(workspace, true, 16), sink);
            ProjectAgentOfficialCollaboration.attach(builder, collaboration, trustedScope);
        }
        if (longTermMemory != null) {
            // 官方推荐 MiddlewareBase；2.0.3 父 Hook 亦真实接线，旧“仅子工厂”说明有误。
            // 按运行时 trustedScope 获取活状态，并等待记忆持久化回执后才结束流。
            builder.middleware(new ProjectAgentLongTermMemoryMiddleware(longTermMemory, sink));
        }
        HarnessAgent built;
        try { built = builder.build(); }
        catch (RuntimeException failure) {
            if (artifactProvider != null) artifactProvider.claims().close();
            throw failure;
        }
        try {
            // SDK build 与子任务会追加工具；统一在装配后和每次 acting 前挂官方权限扩展。
            parentRef.set(built);
            subagentScope.lineage().bindRoot(built);
            filesystem.lifecycle().bindWorkspace(built.getWorkspaceManager());
            safeTranscript.bind(built.getWorkspaceManager());
            skillGovernance.bind(built.getWorkspaceManager());
            // AG-UI schemas are already server-bound by the planner; never overwrite a backend tool.
            if (spec.aguiInput() != null && spec.aguiInput().getTools() != null) {
                var frontendSchemas = new io.agentscope.core.agui.converter.AguiToolConverter()
                    .toToolSchemaList(spec.aguiInput().getTools());
                for (var schema : frontendSchemas) {
                    if (built.getToolkit().getTool(schema.getName()) != null) {
                        throw new IllegalArgumentException("Frontend tool conflicts with an existing backend tool");
                    }
                }
                for (var schema : frontendSchemas) {
                    built.getToolkit().registerAgentTool(new io.agentscope.core.tool.SchemaOnlyTool(schema));
                }
            }
            officialGovernance.bind(built.getToolkit());
            return new ManagedAgent(built, temporaryState, subagentScope.lineage(), artifactProvider,
                terminalCommitted, requiresCommittedReceipt, subagentScope, filesystem, childConsumers);
        } catch (RuntimeException failure) {
            try { built.close(); filesystem.verifyReleased(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            finally { if (artifactProvider != null) artifactProvider.claims().close(); }
            throw failure;
        }
    }

    static final class OwnershipMiddleware implements MiddlewareBase {
        private final ProjectAgentEventSink sink;
        OwnershipMiddleware(ProjectAgentEventSink sink) { this.sink = sink; }
        public int order() { return Integer.MAX_VALUE; }
        public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                       Function<AgentInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> { sink.requireActiveOwnership(); return next.apply(input); });
        }
        public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                           Function<ModelCallInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> { sink.requireActiveOwnership(); return next.apply(input); });
        }
        public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                        Function<ActingInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> { sink.requireActiveOwnership(); return next.apply(input); });
        }
    }

    /** 同一acting批次的每个工具订阅也检查，不能只在批次开始检查一次。 */
    static final class OwnershipGuardedTool extends io.agentscope.core.tool.ToolBase
            implements ProjectAgentOfficialToolGovernance.BusinessExecutionGuarded {
        private final io.agentscope.core.tool.ToolBase delegate;
        private final ProjectAgentEventSink sink;
        OwnershipGuardedTool(AgentTool original, ProjectAgentEventSink sink) {
            super(io.agentscope.core.tool.ToolBase.builder().name(original.getName())
                .description(original.getDescription()).inputSchema(original.getParameters())
                .readOnly(original.isReadOnly())
                .concurrencySafe(original instanceof io.agentscope.core.tool.ToolBase nativeTool && nativeTool.isConcurrencySafe()));
            if (!(original instanceof io.agentscope.core.tool.ToolBase nativeTool)) {
                throw new IllegalArgumentException("Ownership guard requires a native governed tool");
            }
            this.delegate = nativeTool;
            this.sink = sink;
        }
        public boolean hasExecutionClaimGuard() { return delegate instanceof KernelGovernedTool; }
        public Boolean getStrict() { return delegate.getStrict(); }
        public Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
        @Override public Mono<io.agentscope.core.permission.PermissionDecision> checkPermissions(
                Map<String, Object> input, io.agentscope.core.permission.PermissionContextState context) {
            return Mono.defer(() -> { sink.requireActiveOwnership(); return delegate.checkPermissions(input, context); });
        }
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.defer(() -> { sink.requireActiveOwnership(); return delegate.callAsync(param); });
        }
    }

    /** 让整轮截止从SDK内部错误路径收口，先清理请求注册，再由事件桥提交失败。 */
    static final class DeadlineMiddleware implements MiddlewareBase {
        private final Duration timeout;
        private final long deadline;
        private final reactor.core.publisher.Sinks.One<Boolean> cancelled = reactor.core.publisher.Sinks.one();

        private void cancel() { cancelled.tryEmitValue(Boolean.TRUE); }

        DeadlineMiddleware(Duration timeout) {
            this.timeout = timeout;
            this.deadline = System.nanoTime() + timeout.toNanos();
        }

        @Override
        public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                        Function<AgentInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> {
                return next.apply(input);
            });
        }

        @Override
        public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            return withinDeadline(() -> next.apply(input));
        }

        @Override
        public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                        Function<ActingInput, Flux<AgentEvent>> next) {
            return withinDeadline(() -> next.apply(input));
        }

        private Flux<AgentEvent> withinDeadline(java.util.function.Supplier<Flux<AgentEvent>> action) {
            return Flux.defer(() -> {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { return Flux.error(new TimeoutException("project agent deadline exceeded")); }
                var stopped = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Mono<?> stop = Mono.firstWithSignal(
                    cancelled.asMono().doOnNext(ignored -> stopped.set(
                        new java.util.concurrent.CancellationException("project agent cancelled"))),
                    Mono.delay(Duration.ofNanos(remaining)).doOnNext(ignored -> stopped.set(
                        new TimeoutException("project agent deadline exceeded"))));
                // 正常停止信号先取消上游，再把原因交给 SDK 的错误收口路径。
                return action.get().takeUntilOther(stop)
                    .concatWith(Flux.defer(() -> stopped.get() == null
                        ? Flux.empty() : Flux.error(stopped.get())));
            });
        }
    }

    /**
     * 与 {@link ProjectKnowledgeSearchTool} 同一参数和结果，但不再 {@code subscribeOn(boundedElastic)}。
     * 工具体那一次调度会把检索任务排进仍在等待结果的工人队列，调用发出后工具体不会进入。
     */
    private static final class InlineKnowledgeSearchTool implements AgentTool {

        private final Long boundProjectId;
        private final ProjectKnowledgeSearchTool.KnowledgeRetriever retriever;
        private final Consumer<Map<String, Object>> sourceListener;

        private InlineKnowledgeSearchTool(Long boundProjectId,
                                          ProjectKnowledgeSearchTool.KnowledgeRetriever retriever,
                                          Consumer<Map<String, Object>> sourceListener) {
            this.boundProjectId = Objects.requireNonNull(boundProjectId, "boundProjectId");
            this.retriever = Objects.requireNonNull(retriever, "retriever");
            this.sourceListener = sourceListener == null ? source -> { } : sourceListener;
        }

        /** {@inheritDoc} */
        @Override
        public String getName() {
            return ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH;
        }

        /** {@inheritDoc} */
        @Override
        public String getDescription() {
            return ProjectKnowledgeSearchTool.DESCRIPTION;
        }

        /** {@inheritDoc} */
        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> query = Map.of("type", "string", "description", "检索主题关键词或问题");
            Map<String, Object> docType = Map.of("type", "string", "description", "可选：文档类型过滤");
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of("query", query, "docType", docType));
            schema.put("required", List.of("query"));
            schema.put("additionalProperties", false);
            return schema;
        }

        /** {@inheritDoc} */
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /**
         * 在当前订阅线程上执行检索。不要再切到 boundedElastic。
         *
         * @param param 原生调用参数
         * @return 检索文本；缺 query 时返回可见错误
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Map<String, Object> input = param == null || param.getInput() == null ? Map.of() : param.getInput();
            String query = stringArg(input, "query");
            String docType = stringArg(input, "docType");
            String toolCallId = param == null || param.getToolUseBlock() == null
                ? null : param.getToolUseBlock().getId();
            if (query == null) {
                return Mono.just(ToolResultBlock.error("query 为必填参数"));
            }
            return Mono.fromCallable(() -> {
                try {
                    RetrievalContext context = retriever.retrieve(boundProjectId, docType, query);
                    int hits = context == null ? 0 : context.hits();
                    String block = context == null || context.block() == null ? "" : context.block();
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("toolCallId", toolCallId);
                    source.put("tool", getName());
                    source.put("projectId", String.valueOf(boundProjectId));
                    source.put("query", query);
                    source.put("hits", hits);
                    source.put("retrievalStatus", ProjectKnowledgeSearchTool.retrievalStatus(context));
                    source.put("chars", context == null ? 0 : context.chars());
                    source.put("preview", block.length() > ProjectKnowledgeSearchTool.PREVIEW_MAX_CHARS
                        ? block.substring(0, ProjectKnowledgeSearchTool.PREVIEW_MAX_CHARS) : block);
                    String citationText = context == null ? "" : context.citationText();
                    source.put("citationText", citationText);
                    source.put("citationStatus", citationText.isBlank() ? "NONE" : "SUCCESS");
                    source.put("sourceEvidence", ProjectKnowledgeSearchTool.sourceEvidence(context));
                    sourceListener.accept(source);
                    return ProjectKnowledgeSearchTool.result(context);
                } catch (Throwable ex) {
                    if (ex instanceof VirtualMachineError) {
                        throw (VirtualMachineError) ex;
                    }
                    String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    return ToolResultBlock.error(message);
                }
            });
        }

        private static String stringArg(Map<String, Object> input, String key) {
            Object value = input.get(key);
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? null : text;
        }
    }

    /** 原生事件 → IPD 事件出口（只映射合同事件；推理原文不外发不落库）。 */
    private static final class EventBridge {

        private final ProjectAgentEventSink sink;
        private final ProjectAgentAguiBridge agui;
        private ProjectAgentChildLineageRegistry childLineage;
        private io.agentscope.core.state.AgentStateStore childState;
        private boolean protectChildCheckpoint;
        private boolean childPauseRequested;
        private java.util.List<ProjectAgentChildLineageRegistry.ChildApproval> pendingChildren = java.util.List.of();
        private final Long runId;
        private final java.util.function.LongSupplier checkpointVersion;
        private final StringBuilder accumulated = new StringBuilder();
        private final java.util.Map<String, StringBuilder> toolResultText = new java.util.HashMap<>();

        private EventBridge(ProjectAgentEventSink sink, Long runId) {
            this(sink, runId, null, () -> { throw new IllegalStateException("Checkpoint version source is required"); });
        }

        private EventBridge(ProjectAgentEventSink sink, Long runId,
                            java.util.function.LongSupplier checkpointVersion) {
            this(sink, runId, null, checkpointVersion);
        }

        private EventBridge(ProjectAgentEventSink sink, Long runId,
                            io.agentscope.core.agui.model.RunAgentInput serverBoundInput,
                            java.util.function.LongSupplier checkpointVersion) {
            this.sink = sink;
            this.runId = runId;
            this.checkpointVersion = checkpointVersion;
            this.agui = new ProjectAgentAguiBridge(sink, runId, serverBoundInput);
        }

        private EventBridge(ProjectAgentEventSink sink, Long runId,
                            io.agentscope.core.agui.model.RunAgentInput serverBoundInput,
                            ProjectAgentChildLineageRegistry childLineage,
                            io.agentscope.core.state.AgentStateStore childState,
                            java.util.function.LongSupplier checkpointVersion) {
            this(sink, runId, serverBoundInput, checkpointVersion);
            this.childLineage = childLineage;
            this.childState = childState;
        }

        private void dispatch(AgentEvent event) {
            if (childLineage != null) childLineage.captureAndStrip(event);
            boolean rootEvent=event.getSource()==null || event.getSource().isBlank();
            if(rootEvent && event instanceof io.agentscope.core.event.AgentResultEvent && childLineage!=null && childLineage.hasPendingChildApprovals()) {
                protectChildCheckpoint=true;
                return; // Original child pause takes priority; publish only after parent checkpoint END.
            }
            agui.accept(event);
            // 子事件完整保留在官方 subagent.* 流，不能拼入父运行的业务正文。
            if (event.getSource() != null && !event.getSource().isBlank()) return;
            if (event instanceof io.agentscope.core.event.AgentEndEvent && childLineage != null && childLineage.hasPendingChildApprovals()) {
                protectChildCheckpoint = true;
                // 只收集暂停；官方释放、归档与最终检查点保存必须仍持有原租约。
                return;
            }
            if (event instanceof io.agentscope.core.event.AgentResultEvent result) {
                var pending = agui.pendingInterrupts();
                if (!pending.isEmpty()) {
                    protectChildCheckpoint = true;
                    return;
                }
                String finalText = org.ruoyi.chat.kernel.KernelFinalResponse.text(result.getResult(), accumulated.toString());
                if (!java.util.Objects.equals(finalText, accumulated.toString())) {
                    sink.onFinalText(finalText);
                    accumulated.setLength(0);
                    accumulated.append(finalText);
                }
            } else if (event instanceof TextBlockDeltaEvent text) {
                accumulated.append(text.getDelta());
                sink.onText(text.getDelta());
            } else if (event instanceof ToolCallStartEvent call) {
                sink.onToolCall(call.getToolCallId(), call.getToolCallName());
            } else if (event instanceof ToolResultTextDeltaEvent delta) {
                String id = delta.getToolCallId() == null ? "" : delta.getToolCallId();
                toolResultText.computeIfAbsent(id, key -> new StringBuilder())
                    .append(delta.getDelta() == null ? "" : delta.getDelta());
            } else if (event instanceof ToolResultEndEvent result) {
                String id = result.getToolCallId() == null ? "" : result.getToolCallId();
                StringBuilder text = toolResultText.remove(id);
                sink.onToolResult(result.getToolCallId(), result.getToolCallName(),
                    result.getState() == null ? null : result.getState().name(),
                    text == null ? "" : text.toString());
            } else if (event instanceof ModelCallStartEvent) {
                sink.onStep("MODEL_CALL", Map.of());
            } else if (event instanceof ModelCallEndEvent end) {
                // The shared Model decorator meters main, memory, compaction and child calls.
                // Keep native stream lifecycle visible without a second token-bearing STEP.
                sink.onStep("MODEL_CALL", Map.of("phase", "STREAM_END"));
            } else if (event instanceof ExceedMaxItersEvent) {
                sink.onStep("EXCEED_MAX_ITERS", Map.of());
            }
        }

        private void error(Throwable err) {
            log.error("project_agent operation=STREAM status=FAILED runId={} errorType={} rootErrorType={}",
                runId, err.getClass().getName(), rootCauseType(err));
            sink.onError(classifyStreamFailure(err));
        }

        private boolean hasPendingPause() {
            return protectChildCheckpoint || !agui.pendingInterrupts().isEmpty()
                || childLineage != null && childLineage.hasPendingChildApprovals();
        }

        private void complete() {
            // Child-only resumption can suspend again without executing or ending the original parent.
            // Publish from its already persisted checkpoint; never manufacture a parent END/result.
            if (childLineage != null && childLineage.hasPendingChildApprovals() && !childPauseRequested) {
                protectChildCheckpoint = true;
                long rootVersion = checkpointVersion.getAsLong();
                pendingChildren = childLineage.checkpointApprovals(childState, rootVersion);
                sink.onChildInterrupt(pendingChildren, rootVersion);
                childPauseRequested = true;
            }
            if (!childPauseRequested && !agui.pendingInterrupts().isEmpty()) {
                sink.onAguiInterrupt(agui.pendingInterrupts(), checkpointVersion.getAsLong());
                return;
            }
            if (protectChildCheckpoint && !childPauseRequested) {
                // protectChildCheckpoint 是「曾经拦截过结果事件」的单向标记，只置真不清零。
                // 子智能体完成 / 批准批完之后条件早已不成立，标记却仍在，
                // 旧实现在此报 STREAM_ERROR（文案「模型输出中断」）——把健康运行误判为失败，
                // 且文案与事实相反。根因是拿历史标记表达当前状态。
                // 这里改为按当前实况判定：条件已不成立即清标记并走正常收尾；
                // 条件仍成立说明确实还在等，保持非终态、不得报成功也不得报失败。
                if (childLineage != null && childLineage.hasPendingChildApprovals()) {
                    return; // 仍在等子智能体批准：维持非终态，等子运行收口后再收口本轮
                }
                protectChildCheckpoint = false;
            }
            if (!sink.isPaused() && !childPauseRequested) sink.onComplete();
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
