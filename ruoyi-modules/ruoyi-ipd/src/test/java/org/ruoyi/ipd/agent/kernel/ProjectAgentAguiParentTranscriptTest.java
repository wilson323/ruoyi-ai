package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证真实业务消费者：子事件协议保留，但不得污染父正文及工具步骤。 */
class ProjectAgentAguiParentTranscriptTest {
    private final ProjectAgentEventSink sink = mock(ProjectAgentEventSink.class);

    private void dispatch(AgentEvent event) throws Exception {
        Class<?> type = Class.forName(AgentScopeProjectAgentKernel.class.getName() + "$EventBridge");
        var constructor = type.getDeclaredConstructor(ProjectAgentEventSink.class, Long.class);
        constructor.setAccessible(true);
        Object bridge = constructor.newInstance(sink, 42L);
        Method dispatch = type.getDeclaredMethod("dispatch", AgentEvent.class);
        dispatch.setAccessible(true);
        dispatch.invoke(bridge, event);
    }

    @Test void childTextIsRetainedInOfficialEventsWithoutEnteringParentText() throws Exception {
        dispatch(new TextBlockDeltaEvent("reply", "text", "子任务正文").withSource("child-1"));
        verify(sink, never()).onText(anyString());
        verify(sink, never()).onFinalText(anyString());
        verify(sink).onStep(eq("AGUI"), argThat(detail -> has(detail, "subagent.text", "child-1")));
    }

    @Test void parentTextStillUsesOriginalBusinessSink() throws Exception {
        dispatch(new TextBlockDeltaEvent("reply", "text", "父任务正文"));
        verify(sink).onText("父任务正文");
        verify(sink).onStep(eq("AGUI"), argThat(detail -> has(detail, "TEXT_MESSAGE_CONTENT", "父任务正文")));
    }

    @Test void childToolCannotCreateParentToolStep() throws Exception {
        dispatch(new ToolCallStartEvent("reply", "call", "read_file").withSource("child-1"));
        verify(sink, never()).onToolCall(anyString(), anyString());
        verify(sink).onStep(eq("AGUI"), argThat(detail -> has(detail, "subagent.tool_call", "child-1")));
    }

    private static boolean has(Map<String, Object> detail, String type, String value) {
        return detail.get("events") instanceof List<?> events
            && events.stream().anyMatch(event -> event instanceof String json && json.contains(type) && json.contains(value));
    }
}
