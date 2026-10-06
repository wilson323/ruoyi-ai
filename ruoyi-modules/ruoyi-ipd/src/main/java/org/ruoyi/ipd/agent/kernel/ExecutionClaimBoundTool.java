package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

/**
 * 受治理工具的执行声明包装：让本地工具（如 render_html_page）在执行期持有与官方
 * {@code deliver_artifact} 同源的 {@link ProjectAgentExecutionClaims} 服务端执行声明，
 * 使其在交付链 {@code ArtifactDeliveryTarget.deliver} 的 Claim 门禁
 * （{@code requireAuthorized}/{@code requireDelivery}）下合法交付产物。
 *
 * <p>结构对齐 {@code ProjectAgentOfficialToolGovernance.OwnedTool#execute} 的
 * {@code Mono.using(openApproved, ...)} 模式：进入执行段先经 {@code openApproved}
 * （身份/纪元校验 + 签发 Claim），工具体在 Claim 的 runtime 副本下执行（交付链按该
 * runtime 命中声明），Reactor 上下文绑定声明防串接，执行结束关闭声明（一次性消费）。
 * 裁决仍在最外层 {@code KernelGovernedTool}（checkPermissions → ToolPolicyEngine），
 * 本类不做授权判断。
 */
final class ExecutionClaimBoundTool implements AgentTool {

    private final AgentTool delegate;
    private final ProjectAgentExecutionClaims claims;

    ExecutionClaimBoundTool(AgentTool delegate, ProjectAgentExecutionClaims claims) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.using(() -> claims.openApproved(param), scope ->
            Mono.deferContextual(context -> {
                claims.requireReactive(context, scope.runtime(), claims.binding());
                return delegate.callAsync(ToolCallParam.builder(param)
                    .runtimeContext(scope.runtime()).build());
            }).contextWrite(scope::contextWrite),
            ProjectAgentExecutionClaims.ExecutionScope::close);
    }
}
