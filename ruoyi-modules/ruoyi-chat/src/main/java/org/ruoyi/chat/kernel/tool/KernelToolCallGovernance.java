package org.ruoyi.chat.kernel.tool;

import io.agentscope.core.permission.PermissionDecision;
import org.ruoyi.service.coding.harness.tool.PolicyDecision;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEvaluation;

/**
 * 一次工具调用的治理结果：裁决事件 + 写前账本意图 + 原生 {@link PermissionDecision} 映射。
 *
 * <p>三态映射（矩阵 #12「包装」：原生 permission-system 作拦截点，裁决语义唯一源 =
 * 自研 {@code PolicyDecision}）：ALLOW→allow、ASK→ask、DENY→deny。
 * 原生 PermissionGate 语义（2.0.3 实测）：deny→auto-deny 不执行、ask→挂起不执行。
 */
public record KernelToolCallGovernance(
        String effectId,
        KernelToolCallDecision decision,
        ToolPolicyEvaluation evaluation,
        boolean executable) {

    /** PolicyDecision → 原生 PermissionDecision（包装映射，不自造裁决器）。 */
    public PermissionDecision toPermissionDecision() {
        PolicyDecision verdict = decision.decision();
        return switch (verdict) {
            case ALLOW -> PermissionDecision.allow(decision.reason());
            case ASK -> PermissionDecision.ask(decision.reason());
            case DENY -> PermissionDecision.deny(decision.reason());
        };
    }

    public PolicyDecision verdict() {
        return decision.decision();
    }
}
