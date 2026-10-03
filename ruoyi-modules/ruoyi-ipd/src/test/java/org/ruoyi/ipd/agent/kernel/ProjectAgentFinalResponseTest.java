package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.util.Map;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentFinalResponseTest {
    @Test void finalResultReplacesAccumulatedBodyOnlyWhenDifferent() throws Exception {
        ProjectAgentEventSink sink = mock(ProjectAgentEventSink.class);
        Class<?> type = Class.forName(AgentScopeProjectAgentKernel.class.getName() + "$EventBridge");
        var constructor = type.getDeclaredConstructor(ProjectAgentEventSink.class, Long.class);
        constructor.setAccessible(true);
        Object bridge = constructor.newInstance(sink, 7L);
        var accumulated = type.getDeclaredField("accumulated");
        accumulated.setAccessible(true);
        ((StringBuilder) accumulated.get(bridge)).append("old");
        var dispatch = type.getDeclaredMethod("dispatch", AgentEvent.class);
        dispatch.setAccessible(true);
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("old")
            .metadata(Map.of("replacementText", "final")).build();
        dispatch.invoke(bridge, new AgentResultEvent(result));
        dispatch.invoke(bridge, new AgentResultEvent(result));
        verify(sink, times(1)).onFinalText("final");
        verify(sink, never()).onText(any());
    }
}
