package org.ruoyi.chat.kernel.tool;

import io.agentscope.core.message.ToolResultBlock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.model.HarnessToolEffect;
import org.ruoyi.service.coding.harness.tool.PolicyDecision;
import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolInvocation;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEvaluation;

/**
 * 工具调用治理边界（W3）：**真正执行前边界**的单点裁决 + 账本写。
 *
 * <p>单轨纪律（红线 D1 / W3 出条件 ⑥）：裁决唯一源 = 既有 {@link ToolPolicyEngine}
 * （fail-closed：unknown tool → DENY）；本类不新增任何策略语义，只做：
 * <ol>
 *   <li>恰一次裁决：{@link #govern} 每次工具调用恰评估一次并追加一个
 *       {@link KernelToolCallDecision}（PERMISSION_DECISION 事件）；</li>
 *   <li>恰一账本写：同点追加一个写前意图 {@link HarnessToolEffect}（PENDING 标记）；</li>
 *   <li>原生映射：{@code PolicyDecision} → {@code PermissionDecision}（包装，不自造裁决器）。</li>
 * </ol>
 *
 * <p>执行段 {@link #claimForExecution} 只放行已裁决 ALLOW 的调用（执行面无旁路）；
 * 未裁决调用拒执行并补记拒绝事件（fail-closed）。结算 {@link #settleSuccess}/{@link #settleFailure}
 * 为同一账本条目就地状态转移（非新账本写）：成功 commit+settle 回绑原生 tool_call id；
 * 失败仅 replay-safe 条目可 abandon，不确定副作用保持 PENDING 待仲裁（禁止机械重试）。
 */
public final class KernelToolGovernance {

    /** 未获授权执行的拒绝文案（对外安全文案，不带内部细节）。 */
    static final String REFUSAL_MESSAGE = "tool call was not authorized for execution";

    private final ToolPolicyEngine policy;
    private final HarnessPermissionMode permissionMode;
    private final org.ruoyi.service.coding.harness.model.HarnessApprovalPolicy approvalPolicy;
    private final KernelToolEffectLedger ledger;
    private final KernelToolCallTrace trace;
    private final AtomicLong sequence = new AtomicLong();
    /** 已裁决 ALLOW 待执行的写前意图（按 工具名+入参 SHA FIFO 关联执行段）。 */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<KernelToolCallGovernance>> pendingExecutions =
            new ConcurrentHashMap<>();

    public KernelToolGovernance(ToolPolicyEngine policy,
                                HarnessPermissionMode permissionMode,
                                KernelToolEffectLedger ledger,
                                KernelToolCallTrace trace) {
        this(policy, permissionMode,
                org.ruoyi.service.coding.harness.model.HarnessApprovalPolicy.ON_REQUEST, ledger, trace);
    }

    public KernelToolGovernance(ToolPolicyEngine policy,
                                HarnessPermissionMode permissionMode,
                                org.ruoyi.service.coding.harness.model.HarnessApprovalPolicy approvalPolicy,
                                KernelToolEffectLedger ledger,
                                KernelToolCallTrace trace) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.permissionMode = Objects.requireNonNull(permissionMode, "permissionMode");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    public KernelToolEffectLedger ledger() {
        return ledger;
    }

    public KernelToolCallTrace trace() {
        return trace;
    }

    /**
     * 单点裁决（每次工具调用恰一次）：裁决事件 + 写前账本写 + 原生映射。
     */
    public KernelToolCallGovernance govern(String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String callId = "kgov-" + sequence.incrementAndGet();
        String argsSha = argumentsSha256(args);
        ToolPolicyEvaluation evaluation =
                policy.evaluate(ToolInvocation.of(callId, toolName, args), permissionMode, approvalPolicy, null);
        if (evaluation.decision() == PolicyDecision.ALLOW && "web_fetch".equals(toolName)) {
            // 官方 web_fetch 无目标检查；放行不等于可访问内网/元数据地址（公网仍直接放行）。
            String blocked = PublicDestinationGuard.blockReason(args.get("url"));
            if (blocked != null) {
                evaluation = new ToolPolicyEvaluation(PolicyDecision.DENY, "web_destination_blocked", blocked);
            }
        }
        KernelToolCallDecision decision = KernelToolCallDecision.of(
                callId, toolName, argsSha,
                evaluation.decision(), evaluation.code(), evaluation.reason(), System.currentTimeMillis());
        // 恰一裁决事件
        trace.append(decision);
        // 恰一账本写（写前意图）
        HarnessToolEffect intent = newIntent(callId, toolName, argsSha, evaluation.decision());
        ledger.append(intent);
        KernelToolCallGovernance call = new KernelToolCallGovernance(
                intent.effectId(), decision, evaluation, evaluation.decision() == PolicyDecision.ALLOW);
        if (call.executable()) {
            pendingExecutions.computeIfAbsent(claimKey(toolName, argsSha),
                    k -> new ConcurrentLinkedQueue<>()).add(call);
        }
        return call;
    }

    /**
     * 执行段认领（只放行已裁决 ALLOW 的调用）。无对应裁决 → 空（调用方须走
     * {@link #refuseExecution}，执行面零旁路）。
     */
    KernelToolCallGovernance claimForExecution(String toolName, Map<String, Object> arguments) {
        ConcurrentLinkedQueue<KernelToolCallGovernance> queue =
                pendingExecutions.get(claimKey(toolName, argumentsSha256(arguments == null ? Map.of() : arguments)));
        return queue == null ? null : queue.poll();
    }

    /** 未裁决/非 ALLOW 调用的 fail-closed 拒绝：补记拒绝事件 + 账本写，零副作用。 */
    public ToolResultBlock refuseExecution(String toolName, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String callId = "kgov-" + sequence.incrementAndGet();
        String argsSha = argumentsSha256(args);
        ToolPolicyEvaluation evaluation = new ToolPolicyEvaluation(
                PolicyDecision.DENY, "execution_not_authorized",
                "Tool execution refused: no authorized ALLOW decision for this call");
        KernelToolCallDecision decision = KernelToolCallDecision.of(
                callId, toolName, argsSha, PolicyDecision.DENY,
                evaluation.code(), evaluation.reason(), System.currentTimeMillis());
        trace.append(decision);
        HarnessToolEffect intent = newIntent(callId, toolName, argsSha, PolicyDecision.DENY);
        ledger.append(intent);
        return ToolResultBlock.error(toolName, REFUSAL_MESSAGE);
    }

    /** 执行成功结算（同条目就地状态转移：commit+settle，回绑原生 tool_call id）。 */
    public void settleSuccess(KernelToolCallGovernance call, String nativeToolCallId, String resultContent) {
        HarnessToolEffect current = ledger.find(call.effectId())
                .orElseThrow(() -> new IllegalStateException("missing tool effect: " + call.effectId()));
        String receipt = resultContent == null || resultContent.isBlank() ? "(empty)" : resultContent;
        HarnessToolEffect settled = current
                .commit(receipt, System.currentTimeMillis())
                .settle("tool-result:" + (nativeToolCallId == null ? call.decision().callId() : nativeToolCallId),
                        System.currentTimeMillis());
        ledger.settle(settled);
    }

    /**
     * 执行失败结算：replay-safe 条目可 abandon（已知无副作用）；不确定副作用保持
     * PENDING 待仲裁（{@link HarnessToolEffect#requiresOperatorAdjudication}），禁止机械重试。
     *
     * @return 结算后的条目（不确定态返回原 PENDING 条目）
     */
    public HarnessToolEffect settleFailure(KernelToolCallGovernance call, String errorType) {
        HarnessToolEffect current = ledger.find(call.effectId())
                .orElseThrow(() -> new IllegalStateException("missing tool effect: " + call.effectId()));
        if (!current.replaySafe()) {
            return current;
        }
        HarnessToolEffect abandoned = current.abandon(
                "tool execution failed: " + (errorType == null || errorType.isBlank() ? "unknown" : errorType),
                System.currentTimeMillis());
        return ledger.settle(abandoned);
    }

    private HarnessToolEffect newIntent(String callId, String toolName, String argsSha,
                                        PolicyDecision decision) {
        // DENY/ASK：结构性未执行（已知无副作用）→ replay-safe；ALLOW：写前意图，
        // 有副作用能力的工具不可盲重放（replaySafe=false → 不确定态待仲裁）。
        boolean replaySafe = decision != PolicyDecision.ALLOW || replaySafe(toolName);
        HarnessToolEffect intent = HarnessToolEffect.pending(
                callId, toolName, argsSha, replaySafe, System.currentTimeMillis());
        if (decision == PolicyDecision.DENY) {
            return intent.abandon("denied before execution: " + decision, System.currentTimeMillis());
        }
        return intent;
    }

    /** 依据唯一裁决源的工具能力声明判断可重放安全性（READ-only = replay-safe）。 */
    private boolean replaySafe(String toolName) {
        ToolDescriptor descriptor = policy.descriptor(toolName).orElse(null);
        if (descriptor == null) {
            return false;
        }
        return !descriptor.hasCapability(ToolCapability.WRITE)
                && !descriptor.hasCapability(ToolCapability.DESTRUCTIVE)
                && !descriptor.hasCapability(ToolCapability.EXECUTE)
                && !descriptor.hasCapability(ToolCapability.NETWORK);
    }

    private static String claimKey(String toolName, String argsSha) {
        return toolName + "\u0000" + argsSha;
    }

    /** 入参规范化（键排序）后取 SHA-256（对齐 HarnessToolEffect 身份语义：输出文本不作身份）。 */
    static String argumentsSha256(Map<String, Object> arguments) {
        String canonical = new TreeMap<>(arguments == null ? Map.of() : arguments).entrySet().stream()
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                .collect(Collectors.joining("\n"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
