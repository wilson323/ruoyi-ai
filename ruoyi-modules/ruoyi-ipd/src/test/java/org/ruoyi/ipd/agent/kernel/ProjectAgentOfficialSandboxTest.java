package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.IsolationScope;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentOfficialSandboxTest {
    @Test void requiresConcreteProviderAndRunScopedSnapshotWithoutHostFallback() {
        assertThrows(IllegalArgumentException.class,
            () -> ProjectAgentOfficialSandbox.filesystem(Path.of("/tmp/test-run"), "", org.mockito.Mockito.mock(ProjectAgentEventSink.class)));
        var spec = ProjectAgentOfficialSandbox.filesystem(Path.of("/tmp/test-run"), "python:3.13-alpine", org.mockito.Mockito.mock(ProjectAgentEventSink.class));
        assertEquals(IsolationScope.SESSION, spec.getIsolationScope());
        // toSandboxContext only constructs SDK provider metadata, never starts a container.
        var context = spec.toSandboxContext(Path.of("/tmp/test-run"));
        assertNotNull(context);
    }

    @Test void ownershipMustBeValidatedBeforeOfficialProviderAcquiresAnyContainer() throws Exception {
        var sink = org.mockito.Mockito.mock(ProjectAgentEventSink.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("ownership lost")).when(sink).requireActiveOwnership();
        var spec = ProjectAgentOfficialSandbox.filesystem(Path.of("/tmp/test-run"), "python:3.13-alpine", sink);
        assertThrows(IllegalStateException.class, () -> spec.getExecutionGuard().tryEnter(null));
    }
}
