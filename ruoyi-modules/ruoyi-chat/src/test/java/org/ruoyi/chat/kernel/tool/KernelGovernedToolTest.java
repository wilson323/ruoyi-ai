package org.ruoyi.chat.kernel.tool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.model.HarnessToolEffectStatus;
import org.ruoyi.service.coding.harness.tool.PolicyDecision;
import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3 受治理原生工具体单测（矩阵 #11「替换」+ #12「包装」接缝）：
 * 原生 checkPermissions 拦截点三态映射；执行段只放行已裁决 ALLOW；
 * 旁路 callAsync（无裁决）fail-closed 拒绝且工具体零触达（W3 硬项 ③ 零副作用）；
 * 结算为账本就地状态转移（写计数恒 1，硬项 ②）。
 */
@Tag("dev")
@DisplayName("W3 受治理原生工具体：三态映射 + 执行认领 + 零副作用拒绝")
class KernelGovernedToolTest {

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

    private static ToolCallParam param(String id, String name, Map<String, Object> input) {
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock(id, name, input))
                .build();
    }

    /** 输出块 → 文本（TextBlock 取原文，其余 toString 兜底）。 */
    private static String outputText(ToolResultBlock result) {
        StringBuilder sb = new StringBuilder();
        for (var block : result.getOutput()) {
            sb.append(block instanceof TextBlock text ? text.getText() : String.valueOf(block));
        }
        return sb.toString();
    }

    /** stub 原生工具体：计数委托触达，可注入失败。 */
    private static final class StubAgentTool implements AgentTool {

        private final String name;
        private final boolean readOnly;
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean failing;

        private StubAgentTool(String name, boolean readOnly, boolean failing) {
            this.name = name;
            this.readOnly = readOnly;
            this.failing = failing;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "stub tool " + name;
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam toolCall) {
            calls.incrementAndGet();
            return failing
                    ? Mono.error(new IllegalStateException("stub failure"))
                    : Mono.just(ToolResultBlock.text("stub-ok"));
        }
    }

    @Test
    void errorResultIsSanitizedAndConcurrencyDeclarationPreserved() {
        io.agentscope.core.tool.ToolBase delegate = org.mockito.Mockito.mock(io.agentscope.core.tool.ToolBase.class);
        org.mockito.Mockito.when(delegate.getName()).thenReturn("note_read");
        org.mockito.Mockito.when(delegate.getDescription()).thenReturn("note lookup");
        org.mockito.Mockito.when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
        org.mockito.Mockito.when(delegate.isReadOnly()).thenReturn(true);
        org.mockito.Mockito.when(delegate.isConcurrencySafe()).thenReturn(false);
        org.mockito.Mockito.when(delegate.callAsync(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(ToolResultBlock.error("secret-marker")));
        KernelGovernedTool tool = KernelGovernedTool.wrap(delegate,
            governance(HarnessPermissionMode.READ_ONLY, READ_TOOL));
        Map<String, Object> input = Map.of();
        tool.checkPermissions(input, null).block();
        ToolResultBlock result = tool.callAsync(param("safe-1", "note_read", input)).block();
        assertEquals(false, tool.isConcurrencySafe());
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals("safe-1", result.getId());
        assertEquals("[ERROR] 工具执行失败，请稍后重试", outputText(result));
    }

    @Test
    void returnedErrorIsNotSettledAsSuccess() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL);
        AgentTool delegate = org.mockito.Mockito.mock(AgentTool.class);
        org.mockito.Mockito.when(delegate.getName()).thenReturn("note_read");
        org.mockito.Mockito.when(delegate.getDescription()).thenReturn("read");
        org.mockito.Mockito.when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
        org.mockito.Mockito.when(delegate.isReadOnly()).thenReturn(true);
        org.mockito.Mockito.when(delegate.callAsync(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Mono.just(ToolResultBlock.error("unavailable")));
        KernelGovernedTool tool = KernelGovernedTool.wrap(delegate, gov);
        Map<String, Object> input = Map.of("path", "a.md");
        tool.checkPermissions(input, null).block();
        assertEquals(ToolResultState.ERROR, tool.callAsync(param("error-1", "note_read", input)).block().getState());
        assertEquals(HarnessToolEffectStatus.ABANDONED, gov.ledger().entries().get(0).status());
        assertEquals(1, gov.ledger().writeCount());
    }

    @Test
    void emptyResultAndSynchronousThrowAreSettledAsFailures() {
        for (boolean empty : List.of(true, false)) {
            KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL);
            AgentTool delegate = org.mockito.Mockito.mock(AgentTool.class);
            org.mockito.Mockito.when(delegate.getName()).thenReturn("note_read");
            org.mockito.Mockito.when(delegate.getDescription()).thenReturn("read");
            org.mockito.Mockito.when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
            org.mockito.Mockito.when(delegate.isReadOnly()).thenReturn(true);
            org.mockito.Mockito.when(delegate.callAsync(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                if (empty) { return Mono.empty(); }
                throw new IllegalStateException("private error");
            });
            KernelGovernedTool tool = KernelGovernedTool.wrap(delegate, gov);
            Map<String, Object> input = Map.of("path", "a.md");
            tool.checkPermissions(input, null).block();
            ToolResultBlock result = tool.callAsync(param("failure-1", "note_read", input)).block();
            assertEquals(ToolResultState.ERROR, result.getState());
            assertEquals(HarnessToolEffectStatus.ABANDONED, gov.ledger().entries().get(0).status());
            assertTrue(!outputText(result).contains("private error"));
        }
    }

    @Test
    @DisplayName("checkPermissions 原生拦截点：三态映射（ALLOW/ASK/DENY）各恰一事件/一账本写")
    void checkPermissionsMapsThreeStates() {
        KernelToolGovernance readGov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL);
        KernelToolGovernance askGov = governance(HarnessPermissionMode.WORKSPACE_WRITE, EXECUTE_TOOL);
        KernelToolGovernance denyGov = governance(HarnessPermissionMode.READ_ONLY, WRITE_TOOL);
        KernelGovernedTool readTool = KernelGovernedTool.wrap(new StubAgentTool("note_read", true, false), readGov);
        KernelGovernedTool askTool = KernelGovernedTool.wrap(new StubAgentTool("shell_run", false, false), askGov);
        KernelGovernedTool denyTool = KernelGovernedTool.wrap(new StubAgentTool("note_save", false, false), denyGov);

        PermissionDecision allow = readTool.checkPermissions(Map.of("path", "a.md"), null).block();
        PermissionDecision ask = askTool.checkPermissions(Map.of("cmd", "ls"), null).block();
        PermissionDecision deny = denyTool.checkPermissions(Map.of("text", "x"), null).block();

        assertEquals(PermissionBehavior.ALLOW, allow.getBehavior());
        assertEquals(PermissionBehavior.ASK, ask.getBehavior());
        assertEquals(PermissionBehavior.DENY, deny.getBehavior());
        for (KernelToolGovernance gov : List.of(readGov, askGov, denyGov)) {
            assertEquals(1, gov.trace().count(), "每次裁决恰一裁决事件");
            assertEquals(1, gov.ledger().writeCount(), "每次裁决恰一账本写");
        }
    }

    @Test
    @DisplayName("governed ALLOW：执行段认领 → 工具体触达 1 次 → 结算 SETTLED（写计数仍 1）")
    void governedAllowExecutesAndSettles() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL);
        StubAgentTool stub = new StubAgentTool("note_read", true, false);
        KernelGovernedTool tool = KernelGovernedTool.wrap(stub, gov);
        Map<String, Object> input = Map.of("path", "a.md");

        PermissionDecision decision = tool.checkPermissions(input, null).block();
        assertEquals(PermissionBehavior.ALLOW, decision.getBehavior());

        ToolResultBlock result = tool.callAsync(param("t-1", "note_read", input)).block();

        assertNotNull(result);
        assertEquals(1, stub.calls.get(), "工具体恰触达一次");
        assertEquals(HarnessToolEffectStatus.SETTLED,
                gov.ledger().entries().get(0).status(),
                "执行结算 = 账本就地转移 SETTLED");
        assertEquals(1, gov.ledger().writeCount(), "结算不增账本写（硬项 ②）");
        assertEquals(1, gov.trace().count(), "执行段不增裁决事件");
    }

    @Test
    @DisplayName("旁路 callAsync（无裁决）：fail-closed 拒绝，工具体零触达（W3 硬项 ③）")
    void bypassCallAsyncIsRefusedWithoutTouchingDelegate() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL);
        StubAgentTool stub = new StubAgentTool("note_read", true, false);
        KernelGovernedTool tool = KernelGovernedTool.wrap(stub, gov);

        ToolResultBlock refusal = tool.callAsync(param("t-1", "note_read", Map.of("path", "a.md"))).block();

        assertNotNull(refusal);
        assertEquals(ToolResultState.ERROR, refusal.getState());
        assertTrue(outputText(refusal).contains(KernelToolGovernance.REFUSAL_MESSAGE),
                "拒绝文案固定对外安全文案");
        assertEquals(0, stub.calls.get(), "工具体零触达（零副作用）");
        assertEquals(PolicyDecision.DENY, gov.trace().events().get(0).decision());
    }

    @Test
    @DisplayName("DENY 后即便旁路 callAsync 也拒绝：工具体零触达（W3 硬项 ③ 负测）")
    void deniedCallIsRefusedOnBypassToo() {
        KernelToolGovernance gov = governance(HarnessPermissionMode.READ_ONLY, WRITE_TOOL);
        StubAgentTool stub = new StubAgentTool("note_save", false, false);
        KernelGovernedTool tool = KernelGovernedTool.wrap(stub, gov);
        Map<String, Object> input = Map.of("text", "x");

        PermissionDecision decision = tool.checkPermissions(input, null).block();
        assertEquals(PermissionBehavior.DENY, decision.getBehavior());

        ToolResultBlock refusal = tool.callAsync(param("t-1", "note_save", input)).block();

        assertNotNull(refusal);
        assertEquals(ToolResultState.ERROR, refusal.getState());
        assertEquals(0, stub.calls.get(), "DENY 后工具体仍零触达");
    }

    @Test
    @DisplayName("执行失败：replay-safe 条目 abandon（ABANDONED）；不确定副作用保持 PENDING 待仲裁")
    void executionFailureSettlesByReplaySafety() {
        // replay-safe（READ）→ ABANDONED
        KernelToolGovernance readGov = governance(HarnessPermissionMode.READ_ONLY, READ_TOOL);
        KernelGovernedTool readTool = KernelGovernedTool.wrap(
                new StubAgentTool("note_read", true, true), readGov);
        Map<String, Object> readInput = Map.of("path", "a.md");
        readTool.checkPermissions(readInput, null).block();

        ToolResultBlock readResult = readTool.callAsync(param("t-1", "note_read", readInput)).block();

        assertNotNull(readResult);
        assertEquals(ToolResultState.ERROR, readResult.getState());
        assertEquals(HarnessToolEffectStatus.ABANDONED,
                readGov.ledger().entries().get(0).status(), "replay-safe 失败 → abandon 留痕");
        assertEquals(1, readGov.ledger().writeCount(), "结算不增账本写");

        // 非 replay-safe（WRITE）→ 保持 PENDING 待仲裁
        KernelToolGovernance writeGov = governance(HarnessPermissionMode.WORKSPACE_WRITE, WRITE_TOOL);
        KernelGovernedTool writeTool = KernelGovernedTool.wrap(
                new StubAgentTool("note_save", false, true), writeGov);
        Map<String, Object> writeInput = Map.of("text", "x");
        writeTool.checkPermissions(writeInput, null).block();

        ToolResultBlock writeResult = writeTool.callAsync(param("t-2", "note_save", writeInput)).block();

        assertNotNull(writeResult);
        assertEquals(HarnessToolEffectStatus.PENDING,
                writeGov.ledger().entries().get(0).status(), "不确定副作用禁止机械重试，保持 PENDING");
    }
}
