package org.ruoyi.ipd.agent.kernel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
class ProjectAgentTranscriptContinuationContractTest {
    @TempDir
    Path root;
    @Test
    void realNativeModelContinuationRetainsActualToolEvidenceAndCanonicalRequest() throws Exception {
        var safe = new ProjectAgentSafeTranscriptStore(List.of("KNOWN_CREDENTIAL"));
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new ToolBase(ToolBase
            .builder()
            .name("source_contract")
            .description("Fixture owned source evidence")
            .inputSchema(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))))
            .readOnly(true)) {
            public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                return Mono.just(new ToolResultBlock("evidence-call", "source_contract", List.of(TextBlock
                    .builder().text("REAL_SOURCE_EVIDENCE_CANARY KNOWN_CREDENTIAL").build(), ThinkingBlock
                    .builder().thinking("INTERNAL_TOOL_REASONING").build()), Map.of("sourceId", "approved-document-fixture"), ToolResultState.SUCCESS));
            }
        });
        var call = ToolUseBlock
            .builder().id("evidence-call")
            .name("source_contract")
            .input(Map.of("query", "CANONICAL_ORIGINAL_QUERY"))
            .content("{\"query\":\"CANONICAL_ORIGINAL_QUERY\"}")
            .metadata(Map.of("ownerDecision", "fixture-approved")).build();
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("continuation-fixture");
        var initial = new AtomicBoolean();
        var observed = new AtomicReference<List<Msg>>();
        when(model.stream(any(), any(), any())).thenAnswer(i -> {
            List<Msg> messages = i.getArgument(0);
            boolean second = messages.stream().anyMatch(m -> m.getRole() == MsgRole.USER && "second continuation".equals(m.getTextContent()));
            if (second) {
            observed.set(List.copyOf(messages));
        }
            boolean first = messages.stream().anyMatch(m -> m.getRole() == MsgRole.USER && "first execution".equals(m.getTextContent()));
            return Flux.just(ChatResponse
                .builder().id("fixture")
                .finishReason(first && initial.compareAndSet(false, true) ? "tool_calls" : "stop")
                .content(first && !second && messages.stream().noneMatch(m -> m.hasContentBlocks(ToolUseBlock.class)) ? List.of(call) : List.of(TextBlock
                .builder().text("visible final reply").build())).build());
        });
        var permission = PermissionContextState
            .builder().addAllowRule("source_contract", new PermissionRule("source_contract", null, PermissionBehavior.ALLOW, "explicit fixture owner permission")).build();
        try (var agent = HarnessAgent
            .builder()
            .name("continuation-fixture")
            .model(model)
            .toolkit(toolkit)
            .workspace(root)
            .stateStore(new InMemoryAgentStateStore())
            .middleware(safe)
            .transcriptStore(safe)
            .permissionContext(permission)
            .maxIters(4).build()) {
            safe.bind(agent.getWorkspaceManager());
            var context = RuntimeContext.builder().userId("fixture-person").sessionId("fixture-run").build();
            agent.call("first execution", context).block(Duration.ofSeconds(30));
            agent.call("second continuation", context).block(Duration.ofSeconds(30));
            assertNotNull(observed.get());
            var result = observed.get().stream()
                .flatMap(m -> m
                .getContentBlocks(ToolResultBlock.class).stream())
                .filter(t -> "evidence-call".equals(t.getId()))
                .findFirst().orElseThrow();
            assertTrue(result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .anyMatch(t -> t.getText().contains("REAL_SOURCE_EVIDENCE_CANARY")), "real original tool evidence must remain available to the official model");
            assertEquals("approved-document-fixture", result.getMetadata().get("sourceId"));
            assertEquals(ToolResultState.SUCCESS, result.getState());
            var request = observed.get().stream()
                .flatMap(m -> m
                .getContentBlocks(ToolUseBlock.class).stream())
                .filter(t -> "evidence-call".equals(t.getId()))
                .findFirst().orElseThrow();
            assertEquals(call.getInput(), request.getInput());
            assertEquals(call.getContent(), request.getContent());
            assertEquals("fixture-approved", request.getMetadata().get("ownerDecision"));
            assertNotEquals(ToolCallState.PENDING, request.getState());
            var state = agent.getDelegate().getAgentState(context.getUserId(), context.getSessionId());
            String json = state.toJson();
            assertFalse(json.contains("INTERNAL_TOOL_REASONING"));
            assertFalse(json.contains("KNOWN_CREDENTIAL"));
            assertTrue(json.contains("[REDACTED]"));
        }
    }
    @Test
    void suspendedNativeResultAndTypedConfirmationMetadataPreserveOriginalSemanticState() {
        var safe = new ProjectAgentSafeTranscriptStore(List.of("KNOWN_CREDENTIAL"));
        var use = ToolUseBlock
            .builder().id("native-pending")
            .name("frontend_fixture")
            .input(Map.of("document", "owned-native-reference"))
            .content("{\"document\":\"owned-native-reference\"}")
            .metadata(Map.of("owner", "actual-owner-fixture")).build().withState(ToolCallState.ASKING);
        var confirm = new ConfirmResult(false, use);
        var msg = Msg
            .builder()
            .role(MsgRole.USER)
            .content(List.of(TextBlock
            .builder().text("visible KNOWN_CREDENTIAL").build()))
            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of(confirm), "businessGate", "pending-owner-approval")).build();
        var sanitized = safe.sanitizeMessage(msg);
        assertEquals("pending-owner-approval", sanitized.getMetadata().get("businessGate"));
        var item = ((List< ? >)sanitized.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS)).get(0);
        assertInstanceOf(ConfirmResult.class, item);
        assertFalse(((ConfirmResult)item).isConfirmed());
        assertEquals(use.getInput(), ((ConfirmResult)item).getToolCall().getInput());
        var tool = safe.sanitizeMessage(Msg.builder().role(MsgRole.TOOL).content(List.of(ToolResultBlock.suspended(use))).build());
        assertTrue(tool.getFirstContentBlock(ToolResultBlock.class).isSuspended());
        var assistant = safe.sanitizeMessage(Msg
            .builder()
            .role(MsgRole.ASSISTANT)
            .content(List.of(use, ThinkingBlock
            .builder().thinking("INTERNAL_MESSAGE_REASONING").build())).build());
        assertEquals(ToolCallState.ASKING, assistant.getFirstContentBlock(ToolUseBlock.class).getState());
        assertEquals(use.getInput(), assistant.getFirstContentBlock(ToolUseBlock.class).getInput());
        assertFalse(assistant.hasContentBlocks(ThinkingBlock.class));
    }
    @Test
    void officialArtifactImageReferenceAndRagHintSurviveWithoutKnownCredentials() {
        var safe = new ProjectAgentSafeTranscriptStore(List.of("KNOWN_CREDENTIAL"));
        var image = ImageBlock
            .builder()
            .source(new URLSource("https://example.invalid/owned-artifact-image.png", "image/png"))
            .minPixels(256)
            .maxPixels(1024).build();
        var hint = new HintBlock("approved-hint", "REAL_APPROVED_RAG_HINT KNOWN_CREDENTIAL", "owned-source-reference");
        var safeImage = safe.sanitizeMessage(Msg
            .builder()
            .role(MsgRole.USER)
            .content(List.of(image))
            .metadata(Map.of("artifactId", "owned-artifact-ref", "note", "KNOWN_CREDENTIAL")).build());
        assertEquals(((URLSource)image.getSource()).getUrl(), ((URLSource)safeImage.getFirstContentBlock(ImageBlock.class).getSource()).getUrl());
        assertEquals(((URLSource)image.getSource()).getMimeType(), ((URLSource)safeImage.getFirstContentBlock(ImageBlock.class).getSource()).getMimeType());
        assertEquals(image.getMinPixels(), safeImage.getFirstContentBlock(ImageBlock.class).getMinPixels());
        assertEquals(image.getMaxPixels(), safeImage.getFirstContentBlock(ImageBlock.class).getMaxPixels());
        assertEquals("owned-artifact-ref", safeImage.getMetadata().get("artifactId"));
        assertEquals("[REDACTED]", safeImage.getMetadata().get("note"));
        var safeHint = safe.sanitizeMessage(Msg.builder().role(MsgRole.TOOL).content(List.of(hint)).build());
        assertEquals("REAL_APPROVED_RAG_HINT [REDACTED]", safeHint.getFirstContentBlock(HintBlock.class).getHint());
        assertEquals(hint.getSource(), safeHint.getFirstContentBlock(HintBlock.class).getSource());
    }
}
