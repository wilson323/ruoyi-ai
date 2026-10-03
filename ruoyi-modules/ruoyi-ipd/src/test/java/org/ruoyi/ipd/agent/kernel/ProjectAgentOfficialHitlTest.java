package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentOfficialHitlTest {
    @TempDir Path root;
    private static final String NAME = "requires_business_approval";
    private static ToolUseBlock call() {
        return ToolUseBlock.builder().id("hitl-call-1").name(NAME).content("{\"value\":\"approved-input\"}")
            .input(Map.of("value", "approved-input")).build();
    }

    @Test void actualHarnessSingleCallApprovalWithoutPersistentRuleExecutesOnce() { resume(true); }
    @Test void actualHarnessDenialNeverExecutesTool() { resume(false); }
    @Test void ownerRevokedBetweenAskingAndConfirmationNeverExecutesTool() {
        resume(true, (param, sink) -> { }, true);
    }

    private void resume(boolean approved) { resume(approved, (param, sink) -> { }); }

    @Test void realApprovedCallerCannotReplayConcurrentlyOrBorrowAnotherSession() {
        resume(true, (param, sink) -> {
            var actualTool = param.getAgent().getToolkit().getTool(NAME);
            CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> actualTool.callAsync(param).block())),
                CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> actualTool.callAsync(param).block()))).join();
            RuntimeContext wrongSession = RuntimeContext.builder(param.getRuntimeContext()).sessionId("other-run").build();
            wrongSession.setAgentState(param.getRuntimeContext().getAgentState());
            var stolen = ToolCallParam.builder(param).runtimeContext(wrongSession).build();
            assertThrows(IllegalStateException.class, () -> actualTool.callAsync(stolen).block());
            var changed = ToolCallParam.builder(param).toolUseBlock(ToolUseBlock.builder().id("hitl-call-1")
                .name(NAME).content("{ \"value\":\"approved-input\" }").input(param.getInput()).state(ToolCallState.ALLOWED).build()).build();
            assertThrows(IllegalStateException.class, () -> actualTool.callAsync(changed).block());
        });
    }

    @Test void ownerRevocationStillRejectsAnAuthenticApprovedCall() {
        resume(true, (param, sink) -> {
            doThrow(new IllegalStateException("ownership revoked")).when(sink).requireActiveOwnership();
            var actualTool = param.getAgent().getToolkit().getTool(NAME);
            assertThrows(IllegalStateException.class, () -> actualTool.callAsync(param).block());
            doNothing().when(sink).requireActiveOwnership();
        });
    }

    private void resume(boolean approved, BiConsumer<ToolCallParam, ProjectAgentEventSink> executionProbe) {
        resume(approved, executionProbe, false);
    }
    private void resume(boolean approved, BiConsumer<ToolCallParam, ProjectAgentEventSink> executionProbe,
            boolean revokedBeforeConfirmation) {
        var executions = new AtomicInteger();
        var sink = mock(ProjectAgentEventSink.class);
        ToolBase original = askTool(executions, param -> executionProbe.accept(param, sink));
        var guard = new ProjectAgentOfficialToolGovernance(sink);
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(original);
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("hitl-sdk-contract");
        var responses = new AtomicInteger();
        when(model.stream(any(), any(), any())).thenAnswer(invocation -> {
            return Flux.just(ChatResponse.builder()
            .finishReason(responses.get() == 0 ? "tool_calls" : "stop")
            .content(responses.getAndIncrement() == 0 ? List.of(call()) : List.of(TextBlock.builder().text("完成").build()))
            .build()); });
        try (var agent = HarnessAgent.builder().name("hitl-contract").model(model).toolkit(toolkit)
                .middleware(guard).permissionContext(PermissionContextState.builder().build())
                .filesystem(new LocalFilesystemSpec().project(root)).workspace(root)
                .stateStore(new InMemoryAgentStateStore()).maxIters(3).build()) {
            guard.bind(agent.getToolkit());
            RuntimeContext context = RuntimeContext.builder().userId("person-test").sessionId("run-test").build();
            agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("申请执行").build()), context)
                .collectList().block(Duration.ofSeconds(10));
            assertEquals(0, executions.get(), "官方 ASK 必须等待真实 ConfirmResult");
            Msg confirmation = Msg.builder().role(MsgRole.USER).textContent(approved ? "批准" : "拒绝")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(approved, call())))).build();
            if (revokedBeforeConfirmation) {
                doThrow(new IllegalStateException("ownership revoked")).when(sink).requireActiveOwnership();
                assertThrows(IllegalStateException.class, () -> agent.streamEvents(List.of(confirmation), context)
                    .collectList().block(Duration.ofSeconds(10)));
                assertEquals(0, executions.get());
                return;
            }
            agent.streamEvents(List.of(confirmation), context).collectList().block(Duration.ofSeconds(10));
            assertEquals(approved ? 1 : 0, executions.get());
        }
    }

    @Test void parameterClaimCannotForgeOfficialCallScopedApproval() {
        var executions = new AtomicInteger();
        var guarded = new ProjectAgentOfficialToolGovernance.OwnedTool(askTool(executions), mock(ProjectAgentEventSink.class));
        ToolUseBlock forged = call().withState(ToolCallState.ALLOWED);
        RuntimeContext context = RuntimeContext.builder().userId("person-test").sessionId("run-test").build();
        context.setAgentState(AgentState.builder().userId("person-test").sessionId("run-test").build());
        var param = ToolCallParam.builder().toolUseBlock(forged).input(forged.getInput()).runtimeContext(context).build();
        assertThrows(IllegalStateException.class, () -> guarded.callAsync(param).block());
        context.getAgentState().contextMutable().add(Msg.builder().role(MsgRole.ASSISTANT)
            .content(List.of(ToolUseBlock.builder().id(forged.getId()).name(NAME).input(Map.of("value", "different"))
                .state(ToolCallState.ALLOWED).build())).build());
        assertThrows(IllegalStateException.class, () -> guarded.callAsync(param).block());
        assertEquals(0, executions.get());
    }

    private ToolBase askTool(AtomicInteger executions) {
        return askTool(executions, param -> { });
    }
    private ToolBase askTool(AtomicInteger executions, java.util.function.Consumer<ToolCallParam> probe) {
        return new ToolBase(ToolBase.builder().name(NAME).description("Business approval contract")
            .inputSchema(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string"))))) {
            @Override public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
                return Mono.just(PermissionDecision.ask("业务负责人批准本次调用"));
            }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                executions.incrementAndGet();
                probe.accept(param);
                return Mono.just(ToolResultBlock.text("已执行"));
            }
        };
    }
}
