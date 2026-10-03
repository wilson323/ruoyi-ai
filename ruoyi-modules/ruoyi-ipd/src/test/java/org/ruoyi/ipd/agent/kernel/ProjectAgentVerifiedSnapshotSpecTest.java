package org.ruoyi.ipd.agent.kernel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentVerifiedSnapshotSpecTest {
    @TempDir Path root;
    @Test void noAcquiredSandboxRequiresNoInventedArchive() {
        assertTrue(new ProjectAgentVerifiedSnapshotSpec(root).verifyReleased().isEmpty());
    }
    @Test void acquiredSnapshotRequiresActualPersistAndReadback() throws Exception {
        var spec = new ProjectAgentVerifiedSnapshotSpec(root);
        var snapshot = spec.build("session-one");
        assertThrows(IllegalStateException.class, spec::verifyReleased);
        snapshot.persist(new ByteArrayInputStream("actual tar bytes".getBytes()));
        assertEquals(1, spec.verifyReleased().size());
        Files.writeString(root.resolve("session-one.tar"), "tampered");
        assertThrows(IllegalStateException.class, spec::verifyReleased);
    }
    @Test void failedPersistCannotBecomeSuccessfulFromAnOlderArchive() throws Exception {
        var spec = new ProjectAgentVerifiedSnapshotSpec(root);
        var snapshot = spec.build("session-one");
        snapshot.persist(new ByteArrayInputStream("old".getBytes()));
        var failure = assertThrows(io.agentscope.harness.agent.sandbox.SandboxException.SnapshotException.class, () -> snapshot.persist(new InputStream() {
            public int read() throws IOException { throw new IOException("archive interrupted"); }
        }));
        assertInstanceOf(IOException.class, failure.getCause());
        assertThrows(IllegalStateException.class, spec::verifyReleased);
    }
    @Test void parallelSessionsHaveDistinctArchivesAndColdResumeUsesSameArchive() throws Exception {
        var spec = new ProjectAgentVerifiedSnapshotSpec(root);
        var first = spec.build("session-one");
        var second = spec.build("session-two");
        first.persist(new ByteArrayInputStream("one".getBytes()));
        second.persist(new ByteArrayInputStream("two".getBytes()));
        assertNotEquals(spec.verifyReleased().get(0).sha256(), spec.verifyReleased().get(1).sha256());
        var cold = new ProjectAgentVerifiedSnapshotSpec(root);
        var restored = cold.build("session-one");
        assertEquals("one", new String(restored.restore().readAllBytes()));
        assertThrows(IllegalStateException.class, cold::verifyReleased);
        restored.persist(new ByteArrayInputStream("one resumed".getBytes()));
        assertEquals(1, cold.verifyReleased().size());
    }
    @Test void officialColdDeserializerRebindsObserverAndKeepsLocalWireType() throws Exception {
        var managed = ProjectAgentOfficialSandbox.managedFilesystem(root, "python:3.13-alpine",
            org.mockito.Mockito.mock(ProjectAgentEventSink.class));
        var context = managed.spec().toSandboxContext(root);
        var state = new io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState();
        state.setSessionId("cold-session");
        state.setSnapshot(context.getSnapshotSpec().build("cold-session"));
        state.getSnapshot().persist(new ByteArrayInputStream("first".getBytes()));
        var json = context.getClient().serializeState(state);
        assertTrue(json.contains("\"type\":\"local\""));
        var restored = context.getClient().deserializeState(json, context.getSnapshotSpec());
        assertThrows(IllegalStateException.class, managed::verifyReleased);
        restored.getSnapshot().persist(new ByteArrayInputStream("second".getBytes()));
        assertEquals(1, managed.verifyReleased().size());
        assertEquals("second", new String(restored.getSnapshot().restore().readAllBytes()));
    }

}
