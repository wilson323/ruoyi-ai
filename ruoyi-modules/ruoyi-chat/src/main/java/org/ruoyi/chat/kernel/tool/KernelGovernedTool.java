package org.ruoyi.chat.kernel.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 受治理的原生工具（W3：Tool 执行协议「替换」+ Permission 三态「包装」的接缝）。
 *
 * <p>执行协议 = AgentScope 原生：本类是 {@link ToolBase}（原生 PermissionGate 仅对
 * {@code ToolBase} 走拦截点），工具体由原生 {@link AgentTool}/{@code @Tool} 协议执行
 * （矩阵 #11「替换」：不自研执行面）。
 *
 * <p>权限三态 = 包装：{@link #checkPermissions} 是原生 permission-system 拦截点，
 * 裁决语义唯一源 = 自研 {@code ToolPolicyEngine}（经 {@link KernelToolGovernance} 单点
 * 产生恰一裁决事件 + 恰一账本写），映射回原生 {@code PermissionDecision}（矩阵 #12）。
 *
 * <p>DENY/未批准 ASK 零副作用由结构保证：原生 PermissionGate 对 deny→auto-deny、
 * ask→挂起，{@link #callAsync} 不可达；即便被旁路调用，执行段认领（只放行已裁决
 * ALLOW）fail-closed 拒绝，工具体不触达。
 */
public final class KernelGovernedTool extends ToolBase {

    private static final String SAFE_ERROR_MESSAGE = "工具执行失败，请稍后重试";

    private final AgentTool delegate;
    private final KernelToolGovernance governance;

    private KernelGovernedTool(ToolBase.Builder builder, AgentTool delegate,
                               KernelToolGovernance governance) {
        super(builder);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.governance = Objects.requireNonNull(governance, "governance");
    }

    /** 用原生工具体包装为受治理工具（名称/描述/参数 schema 沿用原生工具体）。 */
    public static KernelGovernedTool wrap(AgentTool delegate, KernelToolGovernance governance) {
        Objects.requireNonNull(delegate, "delegate");
        return new KernelGovernedTool(
                ToolBase.builder()
                        .name(delegate.getName())
                        .description(delegate.getDescription())
                        .inputSchema(delegate.getParameters())
                        .readOnly(delegate.isReadOnly())
                        .concurrencySafe(true),
                delegate, governance);
    }

    /**
     * 原生 permission-system 拦截点（ReActAgent PermissionGate 每次工具调用恰调用一次）。
     * 单点裁决 + 恰一事件/账本写 + 原生三态映射。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                                                     PermissionContextState permissionContext) {
        return Mono.fromCallable(() -> governance.govern(getName(), input).toPermissionDecision());
    }

    /**
     * 原生执行段（仅已裁决 ALLOW 可达）：工具体执行前后包账本（写前意图已在裁决段
     * 追加，此处就地结算）。
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getToolUseBlock().getInput();
        KernelToolCallGovernance call = governance.claimForExecution(getName(), input);
        if (call == null) {
            return Mono.fromCallable(() -> governance.refuseExecution(getName(), input));
        }
        String nativeToolCallId = param.getToolUseBlock().getId();
        return delegate.callAsync(param)
                .flatMap(result -> Mono.defer(() -> {
                    governance.settleSuccess(call, nativeToolCallId, describe(result));
                    return Mono.just(result);
                }))
                .onErrorResume(err -> Mono.defer(() -> {
                    governance.settleFailure(call, err.getClass().getName());
                    return Mono.just(ToolResultBlock.error(getName(), SAFE_ERROR_MESSAGE));
                }));
    }

    private static String describe(ToolResultBlock result) {
        if (result == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder();
        if (result.getOutput() != null) {
            for (var block : result.getOutput()) {
                sb.append(block.getClass().getSimpleName()).append(';');
            }
        }
        sb.append("state=").append(result.getState());
        return sb.toString();
    }
}
