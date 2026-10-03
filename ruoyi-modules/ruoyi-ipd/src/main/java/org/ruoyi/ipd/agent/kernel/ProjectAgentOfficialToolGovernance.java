package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.event.AgentEvent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.URI;
import java.net.InetAddress;
import org.ruoyi.ipd.service.ai.EndpointUrlValidator;
import java.util.function.Function;
import org.ruoyi.chat.kernel.tool.KernelGovernedTool;
import org.ruoyi.chat.kernel.KernelScopeKey;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 官方工具装配后的运行所有权与审计扩展，不筛掉官方能力，不覆盖官方权限决定。
 * SDK 子智能体继承父 middleware，因此在 acting 前也保护其后来装配的工具。
 */
public final class ProjectAgentOfficialToolGovernance implements MiddlewareBase {
    /** 仅已有业务工具的原生执行账本守卫可声明，普通 SDK 工具不得声明。 */
    public interface BusinessExecutionGuarded {
        boolean hasExecutionClaimGuard();
    }
    private final ProjectAgentEventSink sink;
    private ProjectAgentExecutionClaims executionClaims;
    private ProjectAgentChildLineageRegistry childLineage;
    public ProjectAgentOfficialToolGovernance childLineage(ProjectAgentChildLineageRegistry lineage) {
        this.childLineage=Objects.requireNonNull(lineage);return this;
    }
    private final KernelScopeKey.Scope expectedScope;
    private final java.util.function.BiPredicate<Agent, RuntimeContext> trustedChildScope;

    public ProjectAgentOfficialToolGovernance(ProjectAgentEventSink sink) {
        this(sink, null);
    }
    public ProjectAgentOfficialToolGovernance(ProjectAgentEventSink sink, KernelScopeKey.Scope expectedScope) {
        this(sink, expectedScope, (agent, context) -> false);
    }
    public ProjectAgentOfficialToolGovernance(ProjectAgentEventSink sink, KernelScopeKey.Scope expectedScope,
            java.util.function.BiPredicate<Agent, RuntimeContext> trustedChildScope) {
        this.sink = Objects.requireNonNull(sink);
        this.expectedScope = expectedScope;
        this.trustedChildScope = Objects.requireNonNull(trustedChildScope);
    }

    public ProjectAgentOfficialToolGovernance executionClaims(ProjectAgentExecutionClaims claims) {
        if (executionClaims != null) throw new IllegalStateException("Execution capability registry already bound");
        executionClaims = Objects.requireNonNull(claims);
        return this;
    }

    @Override public int order() { return Integer.MAX_VALUE; }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            sink.requireActiveOwnership();
            var state = RuntimeContext.resolveAgentState(context, agent);
            if (expectedScope != null && (context == null
                || !Objects.equals(expectedScope.userId(), context.getUserId())
                || !trustedChildScope.test(agent, context)))
                throw new IllegalStateException("Official acting identity is outside the owning run");
            if (state != null) {
                state.setPermissionContext(ProjectAgentOfficialPermissions.extend(state.getPermissionContext()));
            }
            bind(agent.getToolkit());
            if (state != null) {
                for (ToolUseBlock use : input.toolCalls()) {
                    AgentTool tool = agent.getToolkit().getTool(use.getName());
                    if (tool instanceof OwnedTool owned) owned.bindCall(state, context, agent, use);
                }
            }
            return next.apply(input);
        });
    }

    /** 在 build 后及每次 acting 前调用；重复装配幂等且保留所有工具名称。 */
    public void bind(Toolkit toolkit) {
        Objects.requireNonNull(toolkit, "Official toolkit is required");
        synchronized (toolkit) {
            for (String name : List.copyOf(toolkit.getToolNames())) {
                AgentTool original = toolkit.getTool(name);
                if (original instanceof OwnedTool) continue;
                toolkit.removeTool(name);
                toolkit.registerAgentTool(new OwnedTool(original, sink, executionClaims, childLineage));
            }
        }
    }

    static final class OwnedTool extends ToolBase {
        private final ProjectAgentExecutionClaims executionClaims;
        private final ProjectAgentChildLineageRegistry childLineage;
        private final AgentTool delegate;
        private final ProjectAgentEventSink sink;
        private final Map<AgentState, Map<String, BoundCall>> calls = new IdentityHashMap<>();
        private record BoundCall(Agent agent, String userId, String sessionId,
                                 ToolUseBlock call, AtomicBoolean consumed) { }

        OwnedTool(AgentTool delegate, ProjectAgentEventSink sink) { this(delegate, sink, null); }
        OwnedTool(AgentTool delegate, ProjectAgentEventSink sink, ProjectAgentExecutionClaims executionClaims) {
            this(delegate,sink,executionClaims,null);
        }
        OwnedTool(AgentTool delegate, ProjectAgentEventSink sink, ProjectAgentExecutionClaims executionClaims, ProjectAgentChildLineageRegistry childLineage) {
            super(metadata(delegate));
            this.childLineage=childLineage;
            this.executionClaims = executionClaims;
            this.delegate = delegate;
            this.sink = sink;
        }

        private static ToolBase.Builder metadata(AgentTool tool) {
            ToolBase.Builder builder = ToolBase.builder().name(tool.getName())
                .description(tool.getDescription()).inputSchema(tool.getParameters())
                .readOnly(tool.isReadOnly());
            if (tool instanceof ToolBase nativeTool) {
                builder.concurrencySafe(nativeTool.isConcurrencySafe())
                    .externalTool(nativeTool.isExternalTool()).stateInjected(nativeTool.isStateInjected());
                if (nativeTool.isMcp()) builder.mcp(nativeTool.getMcpName());
            } else {
                builder.concurrencySafe(false);
            }
            return builder;
        }

        @Override public Boolean getStrict() { return delegate.getStrict(); }
        @Override public Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
        @Override public boolean matchRule(String rule, Map<String, Object> input) {
            return delegate instanceof ToolBase nativeTool
                ? nativeTool.matchRule(rule, input) : super.matchRule(rule, input);
        }
        @Override public List<PermissionRule> generateSuggestions(Map<String, Object> input) {
            return delegate instanceof ToolBase nativeTool
                ? nativeTool.generateSuggestions(input) : super.generateSuggestions(input);
        }
        @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                PermissionContextState context) {
            return Mono.defer(() -> {
                sink.requireActiveOwnership();
                if ("web_fetch".equals(getName())) {
                    String reason = webBlockReason(input);
                    if (reason != null) return Mono.just(PermissionDecision.deny(reason));
                }
                Mono<PermissionDecision> decision = delegate instanceof ToolBase nativeTool
                    ? nativeTool.checkPermissions(input, context) : super.checkPermissions(input, context);
                return decision.doOnNext(value -> {
                    sink.onStep("TOOL_PERMISSION", Map.of(
                        "toolName", getName(), "behavior", value.getBehavior().name()));
                });
            });
        }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.defer(() -> {
                sink.requireActiveOwnership();
                Objects.requireNonNull(param, "Tool execution context is required");
                // 官方 EXPLORE/ACCEPT_EDITS 对只读工具可能先放行；出站约束仍须在实际调用前再检查。
                if ("web_fetch".equals(getName()) && webBlockReason(param.getInput()) != null)
                    return Mono.error(new IllegalStateException("Web destination is not authorized"));
                var state = RuntimeContext.resolveAgentState(param.getRuntimeContext(), param.getAgent());
                // All tools require the canonical SDK execution binding; business delegates
                // still consume their existing execution ledger, without a second govern call.
                // SDK的ConfirmResult可以仅批准本次工具调用，不添加持久allow rule。
                // 权威是本次AgentState里的原生ALLOWED记录，不能信任param自报的state。
                if (!authorizedPendingCall(state, param))
                    return Mono.error(new IllegalStateException("Official tool call has no active approved execution"));
                return execute(param);
            });
        }

        private boolean authorizedPendingCall(AgentState state, ToolCallParam param) {
            ToolUseBlock requested = param.getToolUseBlock();
            if (state == null || requested == null || requested.getId() == null) return false;
            RuntimeContext runtime = param.getRuntimeContext();
            BoundCall binding;
            synchronized (calls) {
                var bound = calls.get(state);
                binding = bound == null ? null : bound.get(requested.getId());
            }
            if (binding == null || runtime == null || binding.agent() != param.getAgent()
                || !Objects.equals(binding.userId(), runtime.getUserId())
                || !Objects.equals(binding.sessionId(), runtime.getSessionId())
                || !Objects.equals(state.getUserId(), runtime.getUserId())
                || !Objects.equals(state.getSessionId(), runtime.getSessionId())
                || !getName().equals(requested.getName())
                || !Objects.equals(binding.call().getInput(), requested.getInput())) return false;
            var context = state.getContext();
            for (var message : context) {
                if (message.getContentBlocks(ToolResultBlock.class).stream()
                    .anyMatch(result -> requested.getId().equals(result.getId()))) return false;
            }
            for (int index = context.size() - 1; index >= 0; index--) {
                var message = context.get(index);
                if (message.getRole() != MsgRole.ASSISTANT) continue;
                boolean approved = message.getContentBlocks(ToolUseBlock.class).stream().anyMatch(actual ->
                    requested.getId().equals(actual.getId()) && getName().equals(actual.getName())
                    && actual.getState() == ToolCallState.ALLOWED
                    && Objects.equals(actual.getInput(), param.getInput())
                    && Objects.equals(actual.getInput(), requested.getInput())
                    && Objects.equals(actual.getContent(), requested.getContent()));
                return approved && binding.consumed().compareAndSet(false, true);
            }
            return false;
        }

        private void bindCall(AgentState state, RuntimeContext runtime, Agent agent, ToolUseBlock call) {
            if (runtime == null || !Objects.equals(state.getUserId(), runtime.getUserId())
                || !Objects.equals(state.getSessionId(), runtime.getSessionId()))
                throw new IllegalStateException("Official call-scoped identity mismatch");
            synchronized (calls) {
                calls.computeIfAbsent(state, ignored -> new HashMap<>()).putIfAbsent(call.getId(),
                    new BoundCall(agent, runtime.getUserId(), runtime.getSessionId(), call, new AtomicBoolean()));
            }
        }

        private Mono<ToolResultBlock> execute(ToolCallParam param) {
            sink.requireActiveOwnership();
            sink.onStep("TOOL_EXECUTION", Map.of("toolName", getName(), "state", "STARTED"));
            final ToolCallParam executing;
            if(childLineage!=null && "agent_spawn".equals(getName())) {
                var scoped=childLineage.issueParentCall(param.getAgent(),param.getRuntimeContext(),param.getToolUseBlock());
                executing=ToolCallParam.builder(param).runtimeContext(scoped).build();
            } else executing=param;
            Mono<ToolResultBlock> execution;
            if (executionClaims == null) execution = Mono.defer(() -> delegate.callAsync(executing));
            else execution = Mono.using(() -> executionClaims.openApproved(executing), scope ->
                Mono.deferContextual(context -> {
                    executionClaims.requireReactive(context, scope.runtime(), executionClaims.binding());
                    return delegate.callAsync(ToolCallParam.builder(executing).runtimeContext(scope.runtime()).build());
                }).contextWrite(scope::contextWrite), ProjectAgentExecutionClaims.ExecutionScope::close);
            return execution.flatMap(result -> childLineage!=null && "agent_spawn".equals(getName()) && childLineage.parentNeedsPause(executing.getRuntimeContext())
                    ? Mono.error(new io.agentscope.core.tool.ToolSuspendException("Original child approval pending")) : Mono.just(result))
                    .doOnSuccess(result -> sink.onStep("TOOL_EXECUTION", Map.of(
                        "toolName", getName(), "state", "RETURNED")))
                    .doOnError(error -> sink.onStep("TOOL_EXECUTION", Map.of(
                        "toolName", getName(), "state", "FAILED")));
        }

        private static String webBlockReason(Map<String, Object> input) {
            String url = Objects.toString(input.get("url"), "");
            String reason = EndpointUrlValidator.blockReason(url, "");
            if (reason != null) return "Web destination is outside authorized public endpoints";
            try {
                for (InetAddress address : InetAddress.getAllByName(URI.create(url).getHost())) {
                    if (EndpointUrlValidator.isBlockedIp(address.getAddress()))
                        return "Web destination is outside authorized public endpoints";
                }
                return null;
            } catch (Exception unresolved) {
                return "Web destination could not be verified";
            }
        }
    }
}
