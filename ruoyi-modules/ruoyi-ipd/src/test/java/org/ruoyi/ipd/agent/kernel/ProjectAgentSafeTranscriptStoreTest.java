package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentSafeTranscriptStoreTest {
    @TempDir Path root;

    @Test
    void officialProducerKeepsAnswerButNeverWritesThinkingIntoLocalTranscript() throws Exception {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("transcript-pipeline-fixture");
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(ChatResponse.builder()
            .id("safe-response").finishReason("stop").content(List.of(
                ThinkingBlock.builder().thinking("INTERNAL_THINKING_MUST_NOT_PERSIST").build(),
                TextBlock.builder().text("VISIBLE_ANSWER_KNOWN_CREDENTIAL_VALUE").build())).build()));
        var safe = new ProjectAgentSafeTranscriptStore(List.of("KNOWN_CREDENTIAL_VALUE"));
        try (HarnessAgent agent = HarnessAgent.builder().name("safe-transcript-test")
                .workspace(root).model(model).stateStore(new InMemoryAgentStateStore())
                .middleware(safe).transcriptStore(safe).build()) {
            safe.bind(agent.getWorkspaceManager());
            var context = RuntimeContext.builder().userId("safe-person").sessionId("safe-run").build();
            var answer = agent.call("User visible input", context).block(Duration.ofSeconds(10));
            assertNotNull(answer);
            assertTrue(answer.getTextContent().contains("VISIBLE_ANSWER"));
            List<Path> transcripts;
            try (var paths = Files.walk(root)) {
                transcripts = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList();
            }
            assertFalse(transcripts.isEmpty(), "Official local transcript must actually persist");
            String content = "";
            for (Path path : transcripts) content += Files.readString(path);
            assertTrue(content.contains("VISIBLE_ANSWER"), content);
            assertFalse(content.contains("INTERNAL_THINKING_MUST_NOT_PERSIST"), content);
            assertFalse(content.contains("KNOWN_CREDENTIAL_VALUE"), content);
            assertTrue(content.contains("[REDACTED]"), content);
        }
    }

    @Test
    void boundStoreExactRedactsConfiguredCredentialAndPreservesSegmentLifecycle() {
        TranscriptStore delegate = mock(TranscriptStore.class);
        AtomicReference<byte[]> written = new AtomicReference<>();
        when(delegate.appendSegment(any(), anyLong(), anyLong(), any(), any())).thenAnswer(invocation -> {
            written.set(invocation.getArgument(4));
            return "segment-key";
        });
        var safe = new ProjectAgentSafeTranscriptStore(delegate, List.of("EXACT_CONFIGURED_SECRET"));
        var ref = new TranscriptRef("tenant", "agent", "session");
        String key = safe.appendSegment(ref, 0, 0, "writer", ("{\"type\":\"message\",\"role\":\"ASSISTANT\","
            + "\"content\":\"ordinary text EXACT_CONFIGURED_SECRET\"}\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("segment-key", key);
        String payload = new String(written.get(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("ordinary text [REDACTED]"));
        assertFalse(payload.contains("EXACT_CONFIGURED_SECRET"));
        when(delegate.readSegment(key)).thenReturn(new ByteArrayInputStream(written.get()));
        when(delegate.listSegments(ref)).thenReturn(List.of());
        try (InputStream input = safe.readSegment(key)) {
            assertEquals(payload, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) { fail(e); }
        assertEquals(List.of(), safe.listSegments(ref));
        safe.compact(ref);
        verify(delegate).compact(ref);
    }
}
