package org.ruoyi.chat.kernel.tool;

import java.util.UUID;
import org.ruoyi.service.coding.harness.tool.PolicyDecision;

/**
 * PERMISSION_DECISION 裁决事件（ADR-0075 §5.2 行为判据：同一工具调用 trace 恰一个裁决事件）。
 *
 * <p>裁决唯一源是自研 {@link ToolPolicyEngine}（红线 D1：禁止第二套裁决机制）；本记录只是
 * 裁决事件的不可变留痕，供「恰一裁决事件」计数断言与审计回读，不承载任何裁决逻辑。
 *
 * <p>身份语义对齐 {@code HarnessToolEffect}：{@code argumentsSha256} 为调用入参的 SHA-256
 * （工具输出文本永不作身份）；{@code callId} 为治理层内生序号（原生 tool_call id 在执行
 * 结算段回绑账本 {@code resultMessageId}）。
 */
public record KernelToolCallDecision(
        String decisionId,
        String callId,
        String toolName,
        String argumentsSha256,
        PolicyDecision decision,
        String code,
        String reason,
        long decidedAt) {

    /** 裁决事件种类（trace 中的唯一事件类型名）。 */
    public static final String KIND = "PERMISSION_DECISION";

    public KernelToolCallDecision {
        if (decisionId == null || decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId is required");
        }
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId is required");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        if (argumentsSha256 == null || argumentsSha256.isBlank()) {
            throw new IllegalArgumentException("argumentsSha256 is required");
        }
        if (decision == null || code == null || code.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("decision, code and reason are required");
        }
    }

    /** 便捷工厂（decisionId 默认 UUID）。 */
    public static KernelToolCallDecision of(String callId, String toolName, String argumentsSha256,
                                            PolicyDecision decision, String code, String reason,
                                            long decidedAt) {
        return new KernelToolCallDecision(UUID.randomUUID().toString(), callId, toolName,
                argumentsSha256, decision, code, reason, decidedAt);
    }
}
