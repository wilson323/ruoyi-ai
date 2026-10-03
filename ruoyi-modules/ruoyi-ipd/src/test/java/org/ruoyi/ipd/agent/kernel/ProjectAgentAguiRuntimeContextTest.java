package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ProjectAgentAguiRuntimeContextTest {
    @Test void officialProtocolMetadataKeepsTrustedIsolationAndDoesNotImportClientState() {
        Object authority = new Object();
        var trusted = RuntimeContext.builder().userId("p1:u2").sessionId("aagent:s3")
            .put("server.authority", authority).build();
        var input = RunAgentInput.builder().threadId("3").runId("3")
            .state(Map.of("userId", "client", "sessionId", "other"))
            .forwardedProps(Map.of("personId", "other-person")).build();
        var actual = ProjectAgentAguiRuntimeContext.prepare(mock(Agent.class), input, trusted);
        assertEquals("p1:u2", actual.getUserId());
        assertEquals("aagent:s3", actual.getSessionId());
        assertSame(authority, actual.get("server.authority"));
        assertSame(input, actual.get(RunAgentInput.class));
        assertEquals("3", actual.get("agui.threadId"));
        assertEquals("3", actual.get("agui.runId"));
        assertSame(input.getMessages(), actual.get("agui.messages"));
        assertSame(input.getTools(), actual.get("agui.tools"));
        assertSame(input.getContext(), actual.get("agui.context"));
        assertSame(input.getState(), actual.get("agui.state"));
        assertSame(input.getForwardedProps(), actual.get("agui.forwardedProps"));
        assertSame(input.getResume(), actual.get("agui.resume"));
        assertNull(actual.get("personId"));
    }
    @Test void nonAguiRunRetainsOriginalTrustedContext() {
        var trusted = RuntimeContext.builder().userId("p1:u2").sessionId("aagent:s3").build();
        assertSame(trusted, ProjectAgentAguiRuntimeContext.prepare(mock(Agent.class), null, trusted));
    }
}
