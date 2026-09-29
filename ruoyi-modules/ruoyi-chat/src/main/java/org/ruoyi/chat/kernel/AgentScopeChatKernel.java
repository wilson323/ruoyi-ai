package org.ruoyi.chat.kernel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.LocalSessionTurnGate;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.gateway.TurnLease;
import io.agentscope.harness.agent.tools.ToolsConfig;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * W1 对话内核委托桥（2026-09-28，ADR-0075 W1：矩阵 #1 #2 #4 #5 #6 #7）+ W2 模型层
 * （矩阵 #8「替换」+ #9「包装」）。
 *
 * <p>成熟方案优先：会话状态用 AgentScope 原生 {@link MysqlAgentStateStore}（矩阵 #5「替换」）、
 * 事件流用 {@code HarnessAgent.streamEvents}（矩阵 #7「包装」），同键串行使用原生
 * {@link LocalSessionTurnGate}（JVM 级静态共享，覆盖同进程内全部内核实例与注入点；
 * 跨进程多副本串行须分布式锁，另行验收）。
 * 四维隔离键只经 {@link KernelScopeKey} 唯一收口（矩阵 #1「包装」，业务代码禁止手拼）。
 *
 * <p>身份三态（矩阵 #4「包装」，userId 严禁来自请求参数）：
 * <ul>
 *   <li>已登录 → 四维键 p&#123;project&#125;:u&#123;user&#125;；</li>
 *   <li>空 userId → 正式桥拒绝；PoC 匿名键只供隔离实验；</li>
 *   <li>非法段（':'/'..'）→ fail-closed 拒绝（{@code SCOPE_REJECTED} 错误帧，不触引擎）。</li>
 * </ul>
 *
 * <p>模型层（W2，唯一装配/选型入口 {@link KernelModelSelector}）：装配只经 AgentScope
 * 原生 {@code ModelRegistry.resolve}（provider SPI，凭据走 {@code ModelCreationContext}，
 * 矩阵 #8「替换」）；请求 {@code model} 字段经 {@link KernelModelRequest} 薄归一进同一
 * 解析面（矩阵 #9「包装」，禁第二套路由），{@code chat.kernel.agentscope.model-id}
 * 仅在入口未提供模型时作为默认值；显式选型失败不得借 {@code fallbackModel} 静默改用其他模型。
 * PoC 旧 SSE/WS 已传入选定模型；默认关闭的路由开关、正式运行与 IPD
 * {@code ai_model_configs} 接线仍须分别验收（W2 出条件待验）。
 *
 * <p>W1 工具清单强制为空。{@link KernelEventFrames} 的权限状态映射不是执行拦截；
 * W3 必须把既有 {@link ToolPolicyEngine} 接入真正执行前边界后再开放工具。
 *
 * <p>回滚点（ADR-0075 W1/W2）：{@code chat.kernel.agentscope.enabled} 默认 {@code false}，
 * Bean 缺席即回既有 langchain4j 路径（{@code ChatServiceFacade}/{@code MpChatWebSocketHandler}
 * 内核委托分支随之失效），保留 {@code AbstractChatService} 现实现一个版本周期。
 * 状态存储表 DDL 预建（{@code createIfNotExist=false}）。
 */
@Component
@ConditionalOnProperty(name = "chat.kernel.agentscope.enabled", havingValue = "true", matchIfMissing = false)
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

    private final KernelModelSelector modelSelector;
    private final boolean modelRoutingEnabled;
    private final Supplier<MysqlAgentStateStore> stateStoreSupplier;
    private final Path workspaceRoot;
    private final KernelEventFrames frames;
    private final ConcurrentMap<AgentConfiguration, HarnessAgent> agents = new ConcurrentHashMap<>();
    /** JVM 级共享整轮锁：同进程内全部内核实例/注入点同 slot 整轮互斥（C1 双副本实证教训：
     * 实例级 gate 遇双实例同 slot 并发保存会进 CAS 竞态，SDK 版本化保存反序列化失败）。 */
    private static final SessionTurnGate TURN_GATE = new LocalSessionTurnGate();

    /** 缓存身份含配置摘要与工作区分桶维度；不得在键或日志中保留明文凭据。 */
    private record AgentConfiguration(String projectId, String userId, String agentId, String systemPrompt,
                                      String modelKey, String configurationIdentity) {}

    private volatile MysqlAgentStateStore stateStore;

    /** 生产装配：应用 DataSource + 可配置模型/库表/工作区（凭据不落代码，C6）。 */
    @Autowired
    public AgentScopeChatKernel(
            DataSource dataSource,
            @Value("${chat.kernel.agentscope.model-id:minimax:MiniMax-M3}") String modelId,
            @Value("${chat.kernel.agentscope.state-database:}") String stateDatabase,
            @Value("${chat.kernel.agentscope.state-table:agentscope_sessions}") String stateTable,
            @Value("${chat.kernel.agentscope.workspace-root:${java.io.tmpdir}/agentscope-workspace}")
                    Path workspaceRoot,
            @Value("${chat.kernel.agentscope.model-routing.enabled:false}") boolean modelRoutingEnabled) {
        this(
                new KernelModelSelector(modelId),
                modelRoutingEnabled,
                checkedStateStoreSupplier(dataSource, stateDatabase, stateTable),
                workspaceRoot);
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
            Supplier<MysqlAgentStateStore> stateStoreSupplier,
            Path workspaceRoot) {
        this(fixedModel == null
                        ? new KernelModelSelector(modelId)
                        : new KernelModelSelector(modelId, (registryKey, context) -> fixedModel),
                true,
                stateStoreSupplier,
                workspaceRoot);
    }

    /** 测试装配：路由开关可显式关闭（W2 回滚点语义验证）。 */
    AgentScopeChatKernel(
            KernelModelSelector modelSelector,
            boolean modelRoutingEnabled,
            Supplier<MysqlAgentStateStore> stateStoreSupplier,
            Path workspaceRoot) {
        this.modelSelector = Objects.requireNonNull(modelSelector, "modelSelector");
        this.modelRoutingEnabled = modelRoutingEnabled;
        this.stateStoreSupplier = Objects.requireNonNull(stateStoreSupplier, "stateStoreSupplier");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.frames = new KernelEventFrames(
                new ToolPolicyEngine(List.of()), HarnessPermissionMode.READ_ONLY);
    }

    /** 测试装配：可观测装配缝（模型路由断言用；生产恒原生 {@code ModelRegistry.resolve}）。 */
    AgentScopeChatKernel(
            KernelModelSelector modelSelector,
            Supplier<MysqlAgentStateStore> stateStoreSupplier,
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
            AgentEventSinkBridge bridge = new AgentEventSinkBridge(sink);
            // W2 回滚点：路由开关关 → 忽略请求 model 字段（W1 静态 model-id 行为）。
            KernelModelRequest effectiveModel = modelRoutingEnabled ? model : null;
            HarnessAgent selectedAgent = agent(projectId, userId, agentId, systemPrompt,
                    modelSelector.plan(effectiveModel));
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
            ToolsConfig toolsConfig = new ToolsConfig();
            toolsConfig.setDeny(List.of("web_fetch", "web_search", "wait_async_results"));
            HarnessAgent.Builder builder = HarnessAgent.builder()
                    .name(agentId)
                    .sysPrompt(systemPrompt == null || systemPrompt.isBlank()
                            ? "You are a helpful assistant. Answer concisely in the user's language."
                            : systemPrompt)
                    .model(plan.model());
            HarnessAgent built = builder
                    .toolkit(new Toolkit())
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
                    .toolsConfig(toolsConfig)
                    .workspace(workspace)
                    .stateStore(stateStore())
                    .build();
            // W1 只开放对话；W3 完成执行前权限/审批/账本验收后再按单一目录开放工具。
            if (!built.getToolkit().getToolNames().isEmpty()) {
                built.close();
                throw new IllegalStateException("W1 chat kernel must not expose tools");
            }
            return built;
        } catch (Exception e) {
            throw new IllegalStateException("build HarnessAgent failed: " + agentId, e);
        }
    }

    /**
     * 工作区分桶与 {@link KernelScopeKey} 同源补维（C1 切片，2026-09-29）：文件面按
     * project × user × agent 三维隔离；会话维由 stateStore 四维键硬隔离，同一用户同一员工的
     * 多会话共用员工工作区。W3 开放工具前仍须裁决会话维文件隔离（当前 W1 文件工具全关）。
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

    private MysqlAgentStateStore stateStore() {
        MysqlAgentStateStore store = stateStore;
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
        MysqlAgentStateStore store = stateStore;
        if (store != null) {
            try {
                store.close();
            } catch (Exception e) {
                log.warn("kernel_chat operation=CLOSE_STORE status=FAILED errorType={}", e.getClass().getName());
            }
        }
    }

    /** 流事件 → sink 回调适配（错误/完成只走一次语义由 sink 契约约束）。 */
    private final class AgentEventSinkBridge {

        private final KernelChatSink sink;

        private AgentEventSinkBridge(KernelChatSink sink) {
            this.sink = sink;
        }

        private void dispatch(AgentEvent event) {
            frames.dispatch(event, sink);
        }

        private void error(Throwable err) {
            log.error("kernel_chat operation=STREAM status=FAILED errorType={} error={}",
                    err.getClass().getName(), err.getMessage(), err);
            sink.onError(ERR_STREAM_ERROR, SAFE_ERROR_MESSAGE);
        }

        private void complete() {
            sink.onComplete();
        }
    }
}
