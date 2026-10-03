package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.event.AgentEvent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.net.URI;
import java.net.InetAddress;
import org.ruoyi.ipd.service.ai.EndpointUrlValidator;
import java.util.function.Function;
import org.ruoyi.chat.kernel.tool.KernelGovernedTool;
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

    public ProjectAgentOfficialToolGovernance(ProjectAgentEventSink sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    @Override public int order() { return Integer.MAX_VALUE; }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            sink.requireActiveOwnership();
            var state = RuntimeContext.resolveAgentState(context, agent);
            if (state != null) {
                state.setPermissionContext(ProjectAgentOfficialPermissions.extend(state.getPermissionContext()));
            }
            bind(agent.getToolkit());
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
                toolkit.registerAgentTool(new OwnedTool(original, sink));
            }
        }
    }

    static final class OwnedTool extends ToolBase {
        private final AgentTool delegate;
        private final ProjectAgentEventSink sink;

        OwnedTool(AgentTool delegate, ProjectAgentEventSink sink) {
            super(metadata(delegate));
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
                PermissionContextState permission = state != null
                    ? state.getPermissionContext()
                    : PermissionContextState.builder().build();
                // 既有业务工具由 claimForExecution 消费真实裁决，不能二次 govern 产生第二账本。
                boolean businessGuarded = delegate instanceof KernelGovernedTool
                    || delegate instanceof BusinessExecutionGuarded guarded && guarded.hasExecutionClaimGuard();
                if (businessGuarded) return execute(param);
                // Official confirmation promotes this exact pending call to ALLOWED in its session state.
                // Consume that decision without asking again; direct callers cannot forge a matching receipt.
                if (hasOfficialAuthorization(param, state)) return execute(param);
                // 官方 SDK 自检没有业务账本副作用；按本次调用的官方权限上下文直接复查。
                return new PermissionEngine(permission).checkPermission(this, param.getInput())
                    .flatMap(decision -> {
                        if (decision.getBehavior() != PermissionBehavior.ALLOW) {
                            return Mono.error(new IllegalStateException(
                                "Official tool permission requires resolution: " + getName()
                                    + " / " + decision.getBehavior().name()));
                        }
                        return execute(param);
                    });
            });
        }

        private Mono<ToolResultBlock> execute(ToolCallParam param) {
            sink.requireActiveOwnership();
            sink.onStep("TOOL_EXECUTION", Map.of("toolName", getName(), "state", "STARTED"));
            return delegate.callAsync(param)
                    .doOnSuccess(result -> sink.onStep("TOOL_EXECUTION", Map.of(
                        "toolName", getName(), "state", "RETURNED")))
                    .doOnError(error -> sink.onStep("TOOL_EXECUTION", Map.of(
                        "toolName", getName(), "state", "FAILED")));
        }

        private boolean hasOfficialAuthorization(ToolCallParam param,
                io.agentscope.core.state.AgentState state) {
            RuntimeContext context = param.getRuntimeContext();
            var call = param.getToolUseBlock();
            if (state == null || context == null || call == null || call.getId() == null
                || context.getUserId() == null || context.getSessionId() == null
                || !Objects.equals(state.getUserId(), context.getUserId())
                || !Objects.equals(state.getSessionId(), context.getSessionId())
                || !Objects.equals(param.getInput(), call.getInput())
                || call.getState() != io.agentscope.core.message.ToolCallState.ALLOWED) {
                return false;
            }
            for (var message : state.getContext()) {
                for (var block : message.getContent()) {
                    if (block instanceof ToolResultBlock result && Objects.equals(result.getId(), call.getId())) {
                        return false; // The official result already consumed this exact call.
                    }
                }
            }
            for (int index = state.getContext().size() - 1; index >= 0; index--) {
                var message = state.getContext().get(index);
                if (message.getRole() != io.agentscope.core.message.MsgRole.ASSISTANT) continue;
                return message.getContent().stream().anyMatch(block ->
                    block instanceof io.agentscope.core.message.ToolUseBlock approved
                        && approved.getState() == io.agentscope.core.message.ToolCallState.ALLOWED
                        && Objects.equals(approved.getId(), call.getId())
                        && Objects.equals(approved.getName(), getName())
                        && Objects.equals(approved.getName(), call.getName())
                        && Objects.equals(approved.getInput(), call.getInput())
                        && Objects.equals(approved.getContent(), call.getContent()));
            }
            return false;
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
