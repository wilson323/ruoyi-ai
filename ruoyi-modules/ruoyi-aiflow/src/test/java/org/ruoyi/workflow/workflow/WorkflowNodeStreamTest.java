package org.ruoyi.workflow.workflow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class WorkflowNodeStreamTest {
    @Test void synchronousCallbacksRetainTokensBeforeConsumerStarts() {
        WorkflowNodeStream stream = new WorkflowNodeStream();
        stream.chunk("第一段"); stream.chunk("第二段"); stream.complete();
        stream.chunk("终态之后"); stream.complete();
        List<String> seen = new ArrayList<>(); stream.consume(seen::add);
        assertEquals(List.of("第一段", "第二段"), seen);
    }
    @Test void failuresKeepOriginalCauseAndDoNotReportCompletion() {
        WorkflowNodeStream stream = new WorkflowNodeStream();
        var cause = new IllegalArgumentException("provider failure");
        stream.fail(cause); stream.complete();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> stream.consume(x -> {}));
        assertSame(cause, error.getCause());
    }
    @Test void terminalFailureRejectsLateResponseWrites() {
        WorkflowNodeStream stream = new WorkflowNodeStream();
        var wrote = new java.util.concurrent.atomic.AtomicBoolean();
        stream.fail(new IllegalStateException("failed"));
        stream.complete(() -> wrote.set(true));
        assertFalse(wrote.get());
        assertThrows(IllegalStateException.class, () -> stream.consume(x -> {}));
    }
    @Test void responseStoreFailureIsDeliveredToConsumer() {
        WorkflowNodeStream stream = new WorkflowNodeStream();
        var cause = new IllegalArgumentException("cannot store response");
        stream.complete(() -> { throw cause; });
        var error = assertThrows(IllegalStateException.class, () -> stream.consume(x -> {}));
        assertSame(cause, error.getCause());
    }
    @Test void interruptedConsumerKeepsInterruptFlag() {
        WorkflowNodeStream stream = new WorkflowNodeStream();
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> stream.consume(x -> {}));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
