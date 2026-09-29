package org.ruoyi.chat.kernel.tool;

import io.agentscope.core.permission.PermissionBehavior;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.model.HarnessToolEffectStatus;
import org.ruoyi.service.coding.harness.tool.PolicyDecision;
import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3 治理边界单测：恰一裁决事件 + 恰一账本写（每次工具调用）；
 * 三态映射 PolicyDecision → 原生 PermissionDecision（包装，不自造裁决器）；
 * unknown tool fail-closed（裁决唯一源语义）。
 */
@Tag("dev")
@DisplayName("W3 工具治理边界：恰一事件/账本写 + 原生三态映射")
class KernelToolGovernanceTest {

    private static final ToolDescriptor READ_TOOL = new ToolDescriptor(
        "note_read", Set.of(ToolCapability.READ), true, 1_000L, 1_024L, 1_024L, false, "read-only note lookup");
    private static final ToolDescriptor WRITE_TOOL = new ToolDescriptor(
        "note_save", Set.of(ToolCapability.WRITE), false, 1_000L, 1_024L, 1_024L, false, "writes a note");
    private static final ToolDescriptor EXECUTE_TOOL = new ToolDescriptor(
        "shell_run", Set.of(ToolCapability.EXECUTE), false, 1_000L, 1_024L, 1_024L, false, "executes commands");

    private static KernelToolGovernance governance(HarnessPermissionMode mode,
                                                   ToolDescriptor... descriptors) {
        return new KernelToolGovernance(new ToolPolicyEngine(List.of(descriptors)), mode,
                new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
    }

    /** 输出块 → 文本（TextBlock 取原文，其余 String.valueOf 兜底）。 */
    private static String outputText(io.agentscope.core.message.ToolResultBlock result) {
        StringBuilder sb = new StringBuilder();
        for (var block : result.getOutput()) {
            sb.append(block instanceof io.agentscope.core.message.TextBlock text
                    ? text.getText() : String.valueOf(block));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("ALLOW：恰一裁决事件 + 恰一账本写；映射原生 allow")
    void allowProducesExactlyOneDecisionAndOneLedgerWrite() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL);

        KernelToolCallGovernance call = gov.govern("note_read", Map.of("path", "a.md"));

        assertEquals(PolicyDecision.ALLOW, call.verdict());
        assertEquals(PermissionBehavior.ALLOW, call.toPermissionDecision().getBehavior());
        assertTrue(call.executable());
        assertEquals(1, gov.trace().count(), "恰一裁决事件");
        assertEquals(1, gov.ledger().writeCount(), "恰一账本写");
        assertEquals(HarnessToolEffectStatus.PENDING,
                gov.ledger().find(call.effectId()).orElseThrow().status(),
                "写前意图 = PENDING 标记");

        // 执行结算：就地状态转移，不增裁决事件/账本写
        gov.claimForExecution("note_read", Map.of("path", "a.md"));
        gov.settleSuccess(call, "native-1", "ok");
        assertEquals(1, gov.trace().count(), "结算不得新增裁决事件");
        assertEquals(1, gov.ledger().writeCount(), "结算不得新增账本写");
        assertEquals(HarnessToolEffectStatus.SETTLED,
                gov.ledger().find(call.effectId()).orElseThrow().status());
    }

    @Test
    @DisplayName("DENY（READ_ONLY 下 WRITE 工具）：恰一事件 + 一账本写（ABANDONED），映射原生 deny")
    void denyProducesAbandonedLedgerEntry() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, WRITE_TOOL);

        KernelToolCallGovernance call = gov.govern("note_save", Map.of("text", "x"));

        assertEquals(PolicyDecision.DENY, call.verdict());
        assertEquals(PermissionBehavior.DENY, call.toPermissionDecision().getBehavior());
        assertFalse(call.executable());
        assertEquals(1, gov.trace().count());
        assertEquals(1, gov.ledger().writeCount());
        assertEquals(HarnessToolEffectStatus.ABANDONED,
                gov.ledger().find(call.effectId()).orElseThrow().status(),
                "拒绝 = 结构性无副作用，落 ABANDONED 留痕");
        assertNull(gov.claimForExecution("note_save", Map.of("text", "x")),
                "DENY 不得进入执行段认领");
    }

    @Test
    @DisplayName("ASK（WORKSPACE_WRITE 下 EXECUTE 工具）：恰一事件 + 一账本写（PENDING 挂起），映射原生 ask")
    void askProducesPendingLedgerEntry() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.WORKSPACE_WRITE, EXECUTE_TOOL);

        KernelToolCallGovernance call = gov.govern("shell_run", Map.of("cmd", "ls"));

        assertEquals(PolicyDecision.ASK, call.verdict());
        assertEquals(PermissionBehavior.ASK, call.toPermissionDecision().getBehavior());
        assertFalse(call.executable());
        assertEquals(1, gov.trace().count());
        assertEquals(1, gov.ledger().writeCount());
        assertEquals(HarnessToolEffectStatus.PENDING,
                gov.ledger().find(call.effectId()).orElseThrow().status(),
                "未批准 ASK = 挂起意图，不执行");
        assertNull(gov.claimForExecution("shell_run", Map.of("cmd", "ls")),
                "未批准 ASK 不得进入执行段认领");
    }

    @Test
    @DisplayName("unknown tool fail-closed DENY（裁决唯一源 = ToolPolicyEngine，不长第二套白名单）")
    void unknownToolIsDeniedFailClosed() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.FULL_ACCESS, READ_TOOL);

        KernelToolCallGovernance call = gov.govern("rogue_tool", Map.of());

        assertEquals(PolicyDecision.DENY, call.verdict());
        assertEquals("unknown_tool", call.evaluation().code());
        assertEquals(1, gov.trace().count());
        assertEquals(1, gov.ledger().writeCount());
    }

    @Test
    @DisplayName("执行面无旁路：未裁决调用被拒并补记拒绝事件/账本写（fail-closed）")
    void ungovernedExecutionIsRefusedFailClosed() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL);

        assertNull(gov.claimForExecution("note_read", Map.of("path", "a.md")));
        var refusal = gov.refuseExecution("note_read", Map.of("path", "a.md"));

        assertTrue(outputText(refusal).contains(KernelToolGovernance.REFUSAL_MESSAGE),
                "拒绝文案固定对外安全文案");
        assertEquals(1, gov.trace().count(), "拒绝补记恰一裁决事件");
        assertEquals(1, gov.ledger().writeCount(), "拒绝补记恰一账本写");
        assertEquals(PolicyDecision.DENY, gov.trace().events().get(0).decision());
    }

    @Test
    @DisplayName("多次调用各自恰一事件/一账本写（计数按调用数线性）")
    void eachCallGetsExactlyOneEventAndWrite() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL, WRITE_TOOL);

        gov.govern("note_read", Map.of("path", "a.md"));
        gov.govern("note_read", Map.of("path", "b.md"));
        gov.govern("note_save", Map.of("text", "x"));

        assertEquals(3, gov.trace().count(), "三次调用恰三个裁决事件");
        assertEquals(3, gov.ledger().writeCount(), "三次调用恰三个账本写");
        assertEquals(2, gov.trace().countFor("note_read"));
        assertEquals(1, gov.trace().countFor("note_save"));
    }
}
