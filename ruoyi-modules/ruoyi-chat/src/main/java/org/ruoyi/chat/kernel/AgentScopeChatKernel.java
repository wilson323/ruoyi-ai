package org.ruoyi.chat.kernel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.AgentState;
import org.redisson.api.RedissonClient;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnLease;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;

/** 原生 AgentScope 聊天内核，保持身份隔离、会话持久状态与同键整轮串行。 */
@Component
public class AgentScopeChatKernel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeChatKernel.class);

    /** 身份收口拒绝（非法复合键段，fail-closed，不触引擎）。 */
    static final String ERR_SCOPE_REJECTED = "SCOPE_REJECTED";
    /** 装配/委托同步异常。 */
    static final String ERR_KERNEL_ERROR = "KERNEL_ERROR";
    /** 内核流式中断。 */
    static final String ERR_STREAM_ERROR = "KERNEL_STREAM_ERROR";
    static final String SAFE_ERROR_MESSAGE = "对话处理失败，请稍后重试";

    /** 未分桶降级段（W1 兼容面 {@code agent(String, String)} 专用，不承载真实项目/用户语义）。 */
    static final String UNSCOPED_SEGMENT = "__unscoped__";

    /** 官方沙箱默认镜像（与 IPD 内核一致；可用 chat.kernel.agentscope.sandbox-image 覆盖）。 */
    static final String DEFAULT_SANDBOX_IMAGE = "python:3.13-alpine";

    private io.agentscope.core.hook.Hook auditHook;
    /** 跨副本沙箱执行互斥（复用共享 Redisson；无 Redisson 的直接构造保持 SDK noop 默认）。 */
    private SandboxExecutionGuard sandboxExecutionGuard;
    private final KernelModelSelector modelSelector;
    private final Supplier<? extends AgentStateStore> stateStoreSupplier;
    private final Path workspaceRoot;
    private final KernelEventFrames frames;
    private final ConcurrentMap<AgentConfiguration, HarnessAgent> agents = new ConcurrentHashMap<>();
    /** JVM 级共享整轮锁：同进程内全部内核实例/注入点同 slot 整轮互斥（C1 双副本实证教训：
     * 实例级 gate 遇双实例同 slot 并发保存会进 CAS 竞态，SDK 版本化保存反序列化失败）。 */
    private static final SessionTurnGate TURN_GATE = new LocalSessionTurnGate();

    /** 缓存身份含配置摘要与工作区分桶维度；不得在键或日志中保留明文凭据。 */
    private record AgentConfiguration(String projectId, String userId, String agentId, String systemPrompt,
                                      String modelKey, String configurationIdentity) {}

    private volatile AgentStateStore stateStore;

    /** 官方沙箱镜像；缺镜像时沙箱执行明确失败，不自动下载、不回退主机执行。 */
    private String sandboxImage = DEFAULT_SANDBOX_IMAGE;

    /** 正式状态只借用本项目现有 Redisson，原生 CAS 持久化，不创建表或新 Redis 客户端。 */
    @Autowired
    public AgentScopeChatKernel(RedissonClient redisson,
            @Value("${chat.kernel.agentscope.model-id:minimax:MiniMax-M3}") String modelId,
            @Value("${chat.kernel.agentscope.workspace-root:${java.io.tmpdir}/agentscope-workspace}") Path workspaceRoot,
            @Value("${chat.kernel.agentscope.sandbox-image:python:3.13-alpine}") String sandboxImage,
            @org.springframework.beans.factory.annotation.Qualifier("agentScopeAuditHook") io.agentscope.core.hook.Hook auditHook) {
        this(redisson, modelId, workspaceRoot);
        this.auditHook = auditHook;
        if (redisson != null) {
            this.sandboxExecutionGuard = new AgentScopeRedisSandboxGuard(redisson,
                    "ruoyi:agentscope:chat:guard:");
        }
        this.sandboxImage = sandboxImage == null || sandboxImage.isBlank()
                ? DEFAULT_SANDBOX_IMAGE : sandboxImage;
    }

    /** 兼容直接构造；生产使用带共享诊断Hook的构造器。 */
    public AgentScopeChatKernel(
            RedissonClient redisson,
            @Value("${chat.kernel.agentscope.model-id:minimax:MiniMax-M3}") String modelId,
            @Value("${chat.kernel.agentscope.workspace-root:${java.io.tmpdir}/agentscope-workspace}") Path workspaceRoot) {
        this(new KernelModelSelector(modelId), true,
            () -> new FailClosedAgentStateStore(AgentScopeRedisStateStores.create(redisson, "ruoyi:agentscope:chat:")), workspaceRoot);
    }

    /** 旧 MySQL 测试夹具；生产注入不使用它。 */
    AgentScopeChatKernel(DataSource dataSource, String modelId, String stateDatabase,
                         String stateTable, Path workspaceRoot) {
        this(new KernelModelSelector(modelId), true,
            checkedStateStoreSupplier(dataSource, stateDatabase, stateTable), workspaceRoot);
    }

    private static Supplier<MysqlAgentStateStore> checkedStateStoreSupplier(
            DataSource dataSource, String stateDatabase, String stateTable) {
        Objects.requireNonNull(dataSource, "dataSource");
        if (stateDatabase == null || stateDatabase.isBlank()) {
            throw new IllegalArgumentException("chat.kernel.agentscope.state-database must be explicit");
        }
        if (stateTable == null || stateTable.isBlank()) {
            throw new IllegalArgumentException("chat.kernel.agentscope.state-table must be explicit");
        }
        return () -> new MysqlAgentStateStore(dataSource, stateDatabase, stateTable, false);
    }

    /** 测试装配：显式 Model + 状态存储供应器（stub 模型只 mock 输出，存储可全真实）。 */
    AgentScopeChatKernel(
            Model fixedModel,
            String modelId,
            Supplier<? extends AgentStateStore> stateStoreSupplier,
            Path workspaceRoot) {
        this(fixedModel == null
                        ? new KernelModelSelector(modelId)
                        : new KernelModelSelector(modelId, (registryKey, context) -> fixedModel),
                true,
                stateStoreSupplier,
                workspaceRoot);
    }

    /** 兼容既有测试装配签名；明确选定模型始终生效。 */
    AgentScopeChatKernel(
            KernelModelSelector modelSelector,
            boolean modelRoutingEnabled,
            Supplier<? extends AgentStateStore> stateStoreSupplier,
            Path workspaceRoot) {
        this.modelSelector = Objects.requireNonNull(modelSelector, "modelSelector");
        this.stateStoreSupplier = Objects.requireNonNull(stateStoreSupplier, "stateStoreSupplier");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.frames = new KernelEventFrames(
                new ToolPolicyEngine(List.of()), HarnessPermissionMode.READ_ONLY);
    }

    /** 测试装配：可观测装配缝（模型路由断言用；生产恒原生 {@code ModelRegistry.resolve}）。 */
    AgentScopeChatKernel(
            KernelModelSelector modelSelector,
            Supplier<? extends AgentStateStore> stateStoreSupplier,
            Path workspaceRoot) {
        this(modelSelector, true, stateStoreSupplier, workspaceRoot);
    }

    /**
     * W1 兼容面：默认模型委托（等价 {@code model=null} → {@code chat.kernel.agentscope.model-id}）。
     */
    public Disposable stream(
            String projectId,
            String userId,
            String agentId,
            String sessionId,
            String userText,
            String systemPrompt,
            KernelChatSink sink) {
        return stream(projectId, userId, agentId, sessionId, userText, systemPrompt, null, sink);
    }

    /**
     * 内核流式委托（W2 模型路由）。身份三态在入口收口；模型经 {@link KernelModelSelector}
     * 选型装配（矩阵 #9，Agent 缓存键含模型注册键）；任何装配/流异常都转 {@code onError} 帧（不裸抛）。
     *
     * @param projectId  项目/租户维度（调用方从登录态/握手属性取，禁来自请求参数）
     * @param userId     用户维度（必填；来源 LoginHelper / WS 握手 attributes）
     * @param agentId    数字员工维度（普通对话由调用方给定固定段）
     * @param sessionId  会话维度
     * @param userText   用户输入
     * @param systemPrompt 系统提示词（可空）
     * @param model      请求模型（可空 = 默认配置；显式选型不可装配时返回安全错误帧）
     * @param sink       事件出口缝
     */
    public Disposable stream(
            String projectId,
            String userId,
            String agentId,
            String sessionId,
            String userText,
            String systemPrompt,
            KernelModelRequest model,
            KernelChatSink sink) {
        KernelScopeKey.Scope scope;
        try {
            // 正式桥只接受认证身份；同时 agentId 是工作区目录名，必须是单段标识。
            if (userId == null || userId.isBlank()
                    || agentId != null && (agentId.equals(".")
                            || agentId.indexOf('/') >= 0 || agentId.indexOf('\\') >= 0)) {
                throw new IllegalArgumentException("authenticated user and single-segment agentId required");
            }
            scope = KernelScopeKey.of(projectId, userId, agentId, sessionId);
        } catch (IllegalArgumentException rejected) {
            log.warn("kernel_chat operation=SCOPE status=REJECTED errorType={}", rejected.getClass().getName());
            sink.onError(ERR_SCOPE_REJECTED, SAFE_ERROR_MESSAGE);
            return () -> { };
        }
        try {
            Msg msg = Msg.builder().role(MsgRole.USER).textContent(userText).build();
            KernelModelRequest effectiveModel = model;
            HarnessAgent selectedAgent = agent(projectId, userId, agentId, systemPrompt,
                    modelSelector.plan(effectiveModel, userId, sessionId));
            AgentEventSinkBridge bridge = new AgentEventSinkBridge(sink, framesFor(selectedAgent));
            return Flux.using(() -> TURN_GATE.acquire(scope.slotId()),
                    lease -> selectedAgent.streamEvents(msg, scope.toRuntimeContext()),
                    TurnLease::close)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(bridge::dispatch, bridge::error, bridge::complete);
        } catch (Exception e) {
            log.error("kernel_chat operation=STREAM status=FAILED errorType={}", e.getClass().getName());
            sink.onError(ERR_KERNEL_ERROR, SAFE_ERROR_MESSAGE);
            return () -> { };
        }
    }

    /** 本次工具集与客户端仅供本次运行，终态/失败/取消均关闭；会话状态仍在原生状态库。 */
    public Disposable stream(String projectId, String userId, String agentId, String sessionId,
                             String userText, String systemPrompt, KernelModelRequest model,
                             Toolkit toolkit, AutoCloseable resources, KernelChatSink sink) {
        return stream(projectId, userId, agentId, sessionId, userText, systemPrompt, model,
            toolkit, resources, List::of, sink);
    }

    public Disposable stream(String projectId, String userId, String agentId, String sessionId,
                             String userText, String systemPrompt, KernelModelRequest model,
                             Toolkit toolkit, AutoCloseable resources,
                             Supplier<List<Msg>> initialHistory, KernelChatSink sink) {
        KernelScopeKey.Scope scope;
        try {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("authenticated user required");
            }
            scope = KernelScopeKey.of(projectId, userId, agentId, sessionId);
        } catch (IllegalArgumentException rejected) {
            closeResources(resources);
            sink.onError(ERR_SCOPE_REJECTED, SAFE_ERROR_MESSAGE);
            return () -> { };
        }
        try {
            KernelModelSelector.ModelPlan plan = modelSelector.plan(model, userId, sessionId);
            var descriptors = toolkit.getToolNames().stream().map(name -> {
                boolean readOnly = toolkit.getTool(name).isReadOnly();
                return new org.ruoyi.service.coding.harness.tool.ToolDescriptor(name,
                    java.util.EnumSet.of(readOnly
                        ? org.ruoyi.service.coding.harness.tool.ToolCapability.READ
                        : org.ruoyi.service.coding.harness.tool.ToolCapability.WRITE),
                    readOnly, 30_000L, 4096L, 16384L, false, "配置选定的市场工具");
            }).toList();
            var policy = new ToolPolicyEngine(descriptors);
            var governance = new org.ruoyi.chat.kernel.tool.KernelToolGovernance(policy,
                HarnessPermissionMode.FULL_ACCESS,
                org.ruoyi.service.coding.harness.model.HarnessApprovalPolicy.NEVER,
                new org.ruoyi.chat.kernel.tool.InMemoryKernelToolEffectLedger(),
                new org.ruoyi.chat.kernel.tool.KernelToolCallTrace());
            for (String name : List.copyOf(toolkit.getToolNames())) {
                var delegate = toolkit.getTool(name);
                toolkit.removeTool(name);
                toolkit.registerAgentTool(org.ruoyi.chat.kernel.tool.KernelGovernedTool.wrap(delegate, governance));
            }
            HarnessAgent selectedAgent = buildAgent(projectId, userId, agentId, systemPrompt, plan, toolkit);
            AgentEventSinkBridge bridge = new AgentEventSinkBridge(sink, framesFor(selectedAgent));
            Msg msg = Msg.builder().role(MsgRole.USER).textContent(userText).build();
            return Flux.using(() -> TURN_GATE.acquire(scope.slotId()),
                lease -> {
                    initializeHistoryIfAbsent(scope, initialHistory);
                    return selectedAgent.streamEvents(msg, scope.toRuntimeContext());
                }, TurnLease::close)
                .doFinally(signal -> {
                    try { selectedAgent.close(); }
                    finally { closeResources(resources); }
                }).subscribeOn(Schedulers.boundedElastic())
                .subscribe(bridge::dispatch, bridge::error, bridge::complete);
        } catch (Exception failure) {
            closeResources(resources);
            sink.onError(ERR_KERNEL_ERROR, SAFE_ERROR_MESSAGE);
            return () -> { };
        }
    }

    private static void closeResources(AutoCloseable resources) {
        if (resources != null) {
            try { resources.close(); }
            catch (Exception failure) {
                log.warn("kernel_chat operation=CLOSE_TOOLS status=FAILED errorType={}", failure.getClass().getName());
            }
        }
    }

    /** W1 兼容面：默认模型装配（AgentScopeKernelBoundaryTest 反射锁签名）。 */
    private HarnessAgent agent(String agentId, String systemPrompt) {
        return agent(UNSCOPED_SEGMENT, UNSCOPED_SEGMENT, agentId, systemPrompt, modelSelector.plan(null));
    }

    /** 按完整配置比较缓存身份；同名模型端点或凭据改变不得复用旧 Agent。 */
    private HarnessAgent agent(String projectId, String userId, String agentId,
                               String systemPrompt, KernelModelSelector.ModelPlan plan) {
        AgentConfiguration cacheKey = new AgentConfiguration(projectId, userId, agentId, systemPrompt,
                plan.registryKey(), plan.configurationIdentity());
        return agents.computeIfAbsent(cacheKey,
                ignored -> buildAgent(projectId, userId, agentId, systemPrompt, plan));
    }

    private HarnessAgent buildAgent(String projectId, String userId, String agentId,
                                    String systemPrompt, KernelModelSelector.ModelPlan plan) {
        return buildAgent(projectId, userId, agentId, systemPrompt, plan, new Toolkit());
    }

    private HarnessAgent buildAgent(String projectId, String userId, String agentId,
                                    String systemPrompt, KernelModelSelector.ModelPlan plan, Toolkit toolkit) {
        try {
            Path workspace = createWorkspace(workspaceRoot, projectId, userId, agentId);
            Path agentsMd = workspace.resolve("AGENTS.md");
            if (!Files.exists(agentsMd, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.writeString(agentsMd, "# " + agentId + "\n\nChat kernel employee.\n",
                            StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException concurrentCreate) {
                    // Another configuration may have created the same employee workspace.
                }
            }
            if (!Files.isRegularFile(agentsMd, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("workspace AGENTS.md must be a regular file");
            }
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(agentId)
                    .sysPrompt(systemPrompt == null || systemPrompt.isBlank()
                            ? "You are a helpful assistant. Answer concisely in the user's language."
                            : systemPrompt)
                    .model(plan.model())
                    .middleware(new OfficialToolGovernanceMiddleware())
                    .hook(auditHook);
            var capabilities = ChatOfficialCapabilities.configure(builder, workspace,
                KernelScopeKey.of(projectId, userId, agentId, "assembly").userId(),
                sandboxImage, plan.knownSecrets(), sandboxExecutionGuard);
            HarnessAgent built = builder
                    .toolkit(toolkit)
                    // 模型/工具调用超时与重试套官方默认（模型5min+3次尝试，工具5min单次）。
                    // [AgentScope 2.0.3 陷阱 · 勿单独设置 .maxRetries(n)] 它不生效且无任何告警。
// 机制：ModelConfig.maxRetries 不是独立重试源——ReActAgent.buildGenerateOptions() 把它降级为
// ExecutionConfig.maxAttempts 并注入 GenerateOptions 的【fallback 位】，而本行 modelExecutionConfig
// 落在【primary 位】；mergeOptions/mergeConfigs 的语义是「primary 非 null 就取 primary」，
// 而 ExecutionConfig.MODEL_DEFAULTS 的 6 个字段全非 null（含 maxAttempts=3），故 maxRetries 永远取不到。
// 要改重试次数：改本行的 ExecutionConfig（builder().maxAttempts(n).build()），不要加 .maxRetries。
                    .modelExecutionConfig(ExecutionConfig.MODEL_DEFAULTS)
                    .toolExecutionConfig(ExecutionConfig.TOOL_DEFAULTS)
                    // 长对话压缩：官方 Builder 默认即装配全默认配置；此处显式声明固化意图防默认漂移。
                    .compaction(CompactionConfig.builder().build())
                    .workspace(workspace)
                    .stateStore(stateStore())
                    .build();
            capabilities.bind(built);
            // SDK build 注册的官方默认工具统一过治理包装（幂等）；acting 前还会再绑定后注册的工具。
            governOfficialTools(built.getToolkit());
            return built;
        } catch (Exception e) {
            throw new IllegalStateException("build HarnessAgent failed: " + agentId, e);
        }
    }

    /**
     * SDK build 注册的官方默认工具统一过治理包装：能力注册面保持完整（不删工具、不设 deny），
     * 只读裁决与出站闸门收敛在治理层；已包装工具幂等跳过。官方 web 工具按 NETWORK 能力登记，
     * 聊天内核现行 FULL_ACCESS + NEVER：官方能力直接放行并留痕（不再只读拒绝）。
     */
    static void governOfficialTools(Toolkit toolkit) {
        var policy = officialPolicy(toolkit);
        var governance = new org.ruoyi.chat.kernel.tool.KernelToolGovernance(policy,
            HarnessPermissionMode.FULL_ACCESS,
            org.ruoyi.service.coding.harness.model.HarnessApprovalPolicy.NEVER,
            new org.ruoyi.chat.kernel.tool.InMemoryKernelToolEffectLedger(),
            new org.ruoyi.chat.kernel.tool.KernelToolCallTrace());
        for (String name : List.copyOf(toolkit.getToolNames())) {
            AgentTool delegate = toolkit.getTool(name);
            if (delegate instanceof org.ruoyi.chat.kernel.tool.KernelGovernedTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(org.ruoyi.chat.kernel.tool.KernelGovernedTool.wrap(delegate, governance));
        }
    }

    /** 事件帧与执行面同口径（FULL_ACCESS + NEVER），策略按该 Agent 当时的工具箱现算。 */
    static KernelEventFrames framesFor(HarnessAgent agent) {
        return new KernelEventFrames((java.util.function.Supplier<ToolPolicyEngine>) () -> officialPolicy(agent.getToolkit()),
            HarnessPermissionMode.FULL_ACCESS,
            org.ruoyi.service.coding.harness.model.HarnessApprovalPolicy.NEVER);
    }

    /**
     * 按工具箱当前全部工具（含官方默认工具）登记策略表；执行面治理与事件帧共用，
     * 避免官方工具在帧裁决里被当作未知工具而误报 denied。
     */
    static ToolPolicyEngine officialPolicy(Toolkit toolkit) {
        List<org.ruoyi.service.coding.harness.tool.ToolDescriptor> descriptors = new ArrayList<>();
        for (String name : toolkit.getToolNames()) {
            AgentTool tool = toolkit.getTool(name);
            if (tool == null) {
                continue;
            }
            if ("web_fetch".equals(name) || "web_search".equals(name)) {
                descriptors.add(officialDescriptor(name,
                    EnumSet.of(org.ruoyi.service.coding.harness.tool.ToolCapability.NETWORK),
                    false, "出站网络访问；聊天内核放行（不再只读拒绝）"));
            } else {
                boolean readOnly = tool.isReadOnly();
                descriptors.add(officialDescriptor(name,
                    EnumSet.of(readOnly
                        ? org.ruoyi.service.coding.harness.tool.ToolCapability.READ
                        : org.ruoyi.service.coding.harness.tool.ToolCapability.WRITE),
                    readOnly, "官方默认工具"));
            }
        }
        return new ToolPolicyEngine(descriptors);
    }

    private static org.ruoyi.service.coding.harness.tool.ToolDescriptor officialDescriptor(
            String name, EnumSet<org.ruoyi.service.coding.harness.tool.ToolCapability> capabilities,
            boolean readOnly, String summary) {
        return new org.ruoyi.service.coding.harness.tool.ToolDescriptor(name, capabilities,
            readOnly, 30_000L, 4_096L, 16_384L, false, summary);
    }

    /** 官方工具治理绑定：build 后与每次 acting 前执行，SDK 运行期后注册的工具同样过闸门。 */
    static final class OfficialToolGovernanceMiddleware implements MiddlewareBase {
        @Override
        public int order() {
            return Integer.MAX_VALUE;
        }

        @Override
        public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> {
                governOfficialTools(agent.getToolkit());
                return next.apply(input);
            });
        }
    }

    /**
     * 工作区分桶与 {@link KernelScopeKey} 同源补维（C1 切片，2026-09-29）：文件面按
     * project × user × agent 三维隔离；会话维由 stateStore 四维键硬隔离，同一用户同一员工的
     * 多会话共用员工 persona 目录；官方 Docker SESSION 隔离实际文件、Shell、记忆与计划。
     *
     * <p>任一段为空、为 {@code .}、含 {@code :}、{@code /}、反斜杠或 {@code ..} 即
     * fail-closed 拒绝，防工作区路径穿越。
     */
    static Path workspaceFor(Path root, String projectId, String userId, String agentId) {
        return root.resolve(workspaceSegment("projectId", projectId))
                .resolve(workspaceSegment("userId", userId))
                .resolve(workspaceSegment("agentId", agentId));
    }

    private static Path createWorkspace(Path root, String projectId, String userId, String agentId)
            throws java.io.IOException {
        root = root.toAbsolutePath().normalize();
        Path ancestor = root.getRoot();
        for (Path segment : root) {
            ancestor = ancestor.resolve(segment);
            if (Files.isSymbolicLink(ancestor)) {
                Path expected = ancestor.equals(Path.of("/var")) ? Path.of("/private/var")
                    : ancestor.equals(Path.of("/tmp")) ? Path.of("/private/tmp") : null;
                if (ancestor.equals(root) || expected == null || !ancestor.toRealPath().equals(expected)) {
                    throw new IllegalStateException("workspace root symbolic link rejected");
                }
            }
        }
        Path workspace = workspaceFor(root, projectId, userId, agentId);
        Files.createDirectories(root);
        Path current = root;
        for (Path segment : root.relativize(workspace)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("workspace symbolic link rejected");
            }
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException existing) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("workspace segment must be a directory", existing);
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("workspace symbolic link rejected");
            }
        }
        return workspace;
    }

    private static String workspaceSegment(String name, String value) {
        if (value == null || value.isBlank() || value.equals(".")
                || value.indexOf(':') >= 0 || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0 || value.contains("..")) {
            throw new IllegalArgumentException(
                    "[AgentScopeChatKernel] workspace " + name + " segment rejected (path safety)");
        }
        return value;
    }

    /** 在同键 turn gate 内仅创建缺失状态，已有原生状态是唯一权威。 */
    private void initializeHistoryIfAbsent(KernelScopeKey.Scope scope, Supplier<List<Msg>> initialHistory) {
        AgentStateStore store = stateStore();
        if (store == null || store.exists(scope.userId(), scope.sessionId())) { return; }
        List<Msg> history = initialHistory.get();
        if (history == null || history.isEmpty()) { return; }
        AgentState seed = AgentState.builder().userId(scope.userId()).sessionId(scope.sessionId())
            .context(history).build();
        long version = store.saveIfVersion(scope.userId(), scope.sessionId(), "agent_state", seed, 0L);
        if (store.supportsVersioning() && version == AgentStateStore.UNVERSIONED) {
            throw new IllegalStateException("chat state initialized concurrently");
        }
    }

    private AgentStateStore stateStore() {
        AgentStateStore store = stateStore;
        if (store == null) {
            synchronized (this) {
                store = stateStore;
                if (store == null) {
                    store = stateStoreSupplier.get();
                    stateStore = store;
                }
            }
        }
        return store;
    }

    @Override
    public void close() {
        for (HarnessAgent agent : agents.values()) {
            try {
                agent.close();
            } catch (Exception e) {
                log.warn("kernel_chat operation=CLOSE_AGENT status=FAILED errorType={}", e.getClass().getName());
            }
        }
        agents.clear();
        AgentStateStore store = stateStore;
        if (store instanceof MysqlAgentStateStore mysql) {
            try {
                mysql.close();
            } catch (Exception e) {
                log.warn("kernel_chat operation=CLOSE_STORE status=FAILED errorType={}", e.getClass().getName());
            }
        }
    }

    /** 流事件 → sink 回调适配（错误/完成只走一次语义由 sink 契约约束）。 */
    private final class AgentEventSinkBridge {

        private final KernelChatSink sink;
        private final KernelEventFrames turnFrames;

        private AgentEventSinkBridge(KernelChatSink sink) { this(sink, frames); }
        private AgentEventSinkBridge(KernelChatSink sink, KernelEventFrames turnFrames) {
            this.sink = sink;
            this.turnFrames = turnFrames;
        }

        private void dispatch(AgentEvent event) {
            turnFrames.dispatch(event, sink);
        }

        private void error(Throwable err) {
            log.error("kernel_chat operation=STREAM status=FAILED errorType={}", err.getClass().getName());
            sink.onError(ERR_STREAM_ERROR, SAFE_ERROR_MESSAGE);
        }

        private void complete() {
            sink.onComplete();
        }
    }
}
