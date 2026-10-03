package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.*;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentClarificationSdkTest {
    @org.junit.jupiter.api.io.TempDir Path root;
    @Test void genuineQuestionSuspendsWithoutPermissionApprovalAndResumesWithSelectedId() {
        actualSdk(ProjectAgentClarificationContractTest.input(), "a");
    }
    @Test void genuineFreeTextQuestionResumesWithOriginalText() {
        actualSdk(ProjectAgentClarificationContractTest.freeInput(), "华东地区的实际范围");
    }
    private void actualSdk(Map<String,Object> questions, String answer) {
        var schema = ProjectAgentOutputContract.clarificationTool();
        var input = RunAgentInput.builder().threadId("8123").runId("8123").tools(List.of(schema)).build();
        var call = ToolUseBlock.builder().id("question-call").name(schema.getName()).input(questions).content(io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(questions)).build();
        var toolkit = new Toolkit(); toolkit.registerAgentTool(new SchemaOnlyTool(new io.agentscope.core.agui.converter.AguiToolConverter().toToolSchemaList(List.of(schema)).get(0)));
        var sink = mock(ProjectAgentEventSink.class);
        var governance = new ProjectAgentOfficialToolGovernance(sink);
        var model = mock(Model.class); when(model.getModelName()).thenReturn("synthetic");
        var turns = new AtomicInteger();
        var selectedConsumed = new java.util.concurrent.atomic.AtomicBoolean();
        when(model.stream(any(), any(), any())).thenAnswer(invocation -> {
            int turn = turns.getAndIncrement();
            if (turn > 0) {
                @SuppressWarnings("unchecked") var messages = (List<Msg>)invocation.getArgument(0);
                if (messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                    .filter(b -> "question-call".equals(b.getId())).anyMatch(b -> b.getOutput().toString().contains(answer))) selectedConsumed.set(true);
            }
            return Flux.just(ChatResponse.builder().id("r" + turn).finishReason(turn == 0 ? "tool_calls" : "stop")
                .content(turn == 0 ? List.of(call) : List.of(TextBlock.builder().text("回答已消费").build())).build());
        });
        try (var agent = HarnessAgent.builder().name("root").model(model).toolkit(toolkit).middleware(governance)
            .permissionContext(ProjectAgentOfficialPermissions.workspace()).filesystem(new LocalFilesystemSpec().project(root))
            .workspace(root).stateStore(new InMemoryAgentStateStore()).maxIters(3).build()) {
            governance.bind(agent.getToolkit());
            var context = RuntimeContext.builder().userId("owner").sessionId("owned-run").build();
            var bridge = new ProjectAgentAguiBridge(sink, 8123L, input);
            var first = agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("Need scope clarification").build()), context)
                .collectList().block(Duration.ofSeconds(10));
            first.forEach(bridge::accept);

            assertEquals(1, bridge.pendingInterrupts().size());
            var interrupt = bridge.pendingInterrupts().values().iterator().next();
            assertEquals("request_clarification", interrupt.metadata().get("toolName"));
            assertFalse(interrupt.metadata().containsKey("agentscope.interruptKind"));
            assertEquals(ProjectAgentOutputContract.responseSchema(call.getInput()), interrupt.responseSchema());
            assertTrue(first.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast)
                .anyMatch(e -> e.getResult().getGenerateReason() == GenerateReason.TOOL_SUSPENDED));
            var resumed = RunAgentInput.builder().threadId("8123").runId("8123").tools(List.of(schema))
                .resume(List.of(new AguiResume(interrupt.id(), "resolved", Map.of("scope", answer)))).build();
            org.ruoyi.ipd.agent.service.ProjectAgentAguiResumeValidation.validate(resumed,
                new org.ruoyi.ipd.agent.service.ProjectAgentAguiResumeValidation.Binding("8123", "8123", "owner", 1, 1, bridge.pendingInterrupts()), "owner", 1, 1, java.time.Instant.now());
            var messages = new io.agentscope.core.agui.converter.AguiMessageConverter().toMsgList(resumed, bridge.pendingInterrupts());
            var completed = agent.streamEvents(messages, context).collectList().block(Duration.ofSeconds(10));
            assertTrue(completed.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast)
                .anyMatch(e -> e.getResult().getTextContent().contains("回答已消费")));
            assertTrue(selectedConsumed.get(), "Original tool result must reach model");
            verify(sink, never()).onDocument(any()); verify(sink, never()).onArtifactPayload(any());
        }
    }
}
