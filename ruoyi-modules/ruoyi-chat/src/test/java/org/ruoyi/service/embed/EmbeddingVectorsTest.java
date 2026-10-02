package org.ruoyi.service.embed;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class EmbeddingVectorsTest {
    private EmbeddingModel model(java.util.function.Function<String, double[]> values) {
        return new EmbeddingModel() {
            public Mono<double[]> embed(ContentBlock block) { return Mono.fromCallable(() -> values.apply(((TextBlock) block).getText())); }
            public String getModelName() { return "test"; }
            public int getDimensions() { return 2; }
        };
    }

    @Test
    void preservesOrderAndRejectsInvalidVectors() {
        var vectors = EmbeddingVectors.embedAll(model(text -> new double[] {Double.parseDouble(text), 0}), List.of("2", "1"));
        assertArrayEquals(new float[] {2, 0}, vectors.get(0));
        assertArrayEquals(new float[] {1, 0}, vectors.get(1));
        assertThrows(IllegalStateException.class, () -> EmbeddingVectors.embed(model(text -> new double[] {1}), "text"));
        assertThrows(IllegalStateException.class, () -> EmbeddingVectors.embed(model(text -> new double[] {Double.NaN, 0}), "text"));
        assertThrows(IllegalStateException.class, () -> EmbeddingVectors.embed(model(text -> new double[] {Double.MAX_VALUE, 0}), "text"));
        assertThrows(IllegalStateException.class, () -> EmbeddingVectors.embed(model(text -> null), "text"));
    }

    @Test
    void batchDeadlineDoesNotRestartAfterEachVectorAndCancelsCurrentRequest() {
        var started = new java.util.concurrent.atomic.AtomicInteger();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        EmbeddingModel delayed = new EmbeddingModel() {
            public Mono<double[]> embed(ContentBlock block) {
                int index = started.incrementAndGet();
                return index == 1
                    ? Mono.delay(java.time.Duration.ofMillis(100)).map(ignored -> new double[] {1, 0})
                    : Mono.<double[]>never().doOnCancel(() -> cancelled.set(true));
            }
            public String getModelName() { return "deadline-test"; }
            public int getDimensions() { return 2; }
        };
        long before = System.nanoTime();
        assertThrows(RuntimeException.class, () -> EmbeddingVectors.embedAll(delayed,
            List.of("first", "second", "must-not-start"), java.time.Duration.ofMillis(300)));
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertEquals(2, started.get());
        assertTrue(cancelled.get());
        assertTrue(elapsedMillis < 380, "deadline must not restart after the first vector: " + elapsedMillis);
    }

    @Test
    void builtinUsesNativeUnauthenticatedOllama() {
        var model = EmbeddingModels.create("openai", "qwen3-embedding:0.6b", "http://127.0.0.1:11434/v1", null, 1024,
            java.time.Duration.ofSeconds(2));
        assertInstanceOf(io.agentscope.core.embedding.ollama.OllamaTextEmbedding.class, model);
        assertEquals(1024, model.getDimensions());
    }
}
