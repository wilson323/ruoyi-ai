package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.*;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.kernel.ProjectAgentAguiBridge;
import org.ruoyi.ipd.agent.kernel.ProjectAgentEventSink;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectAgentAguiProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> emitted = new ArrayList<>();
    private ProjectAgentAguiBridge bridge() {
        ProjectAgentEventSink sink = (ProjectAgentEventSink) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ProjectAgentEventSink.class}, (p,m,a) -> {
                if (m.getName().equals("onStep")) emitted.addAll((List<String>) ((Map<?,?>)a[1]).get("events"));
                return null;
            });
        return new ProjectAgentAguiBridge(sink, 42L);
    }
    @Test void officialTextToolArgsAndUsageArePreserved() throws Exception {
        var bridge = bridge();
        bridge.accept(new TextBlockDeltaEvent("reply", "text", "正文"));
        bridge.accept(new ToolCallStartEvent("reply", "call", "search"));
        bridge.accept(new ToolCallDeltaEvent("reply", "call", "search", "{\"query\":\"材料\"}"));
        bridge.accept(new ModelCallEndEvent("reply", new ChatUsage(11, 7, 0.1)));
        assertTrue(emitted.stream().anyMatch(s -> s.contains("TEXT_MESSAGE_CONTENT") && s.contains("正文")));
        assertTrue(emitted.stream().anyMatch(s -> s.contains("TOOL_CALL_ARGS") && s.contains("材料")));
        assertTrue(emitted.stream().anyMatch(s -> s.contains("token_usage") && s.contains("11") && s.contains("7")));
        for (String json : emitted) assertTrue(mapper.readTree(json).hasNonNull("timestamp"));
    }
    @Test void childEventsCannotContaminateParentLifecycleOrText() throws Exception {
        var bridge = bridge();
        bridge.accept(new AgentStartEvent("session", "reply", "child").withSource("child-1"));
        bridge.accept(new TextBlockDeltaEvent("reply", "text", "子任务").withSource("child-1"));
        bridge.accept(new AgentEndEvent("reply").withSource("child-1"));
        assertEquals(3, emitted.size());
        for (String json : emitted) {
            var event = mapper.readTree(json);
            assertEquals("CUSTOM", event.get("type").asText());
            assertTrue(event.get("name").asText().startsWith("subagent."));
            assertTrue(json.contains("child-1"));
        }
    }
    @Test void nativeSuccessWaitsForBusinessCommit() {
        var bridge = bridge();
        bridge.accept(new AgentStartEvent("session", "reply", "main"));
        bridge.accept(new TextBlockDeltaEvent("reply", "text", "正文"));
        bridge.accept(new AgentEndEvent("reply"));
        assertFalse(emitted.stream().anyMatch(s -> s.contains("RUN_STARTED") || s.contains("RUN_FINISHED")));
        assertTrue(emitted.stream().anyMatch(s -> s.contains("TEXT_MESSAGE_END")));
    }
    @Test void nativePermissionInterruptIsRetainedForServerCheckpoint() {
        var bridge = bridge();
        var tool = io.agentscope.core.message.ToolUseBlock.builder()
            .id("call").name("write_file").input(Map.of("path", "result.md")).build();
        bridge.accept(new RequireUserConfirmEvent("reply", List.of(tool)));
        // SDK 在 AgentResult 前保存 checkpoint；不必等 AgentEnd/eager cleanup 才能取得原 interrupt。
        bridge.accept(new AgentResultEvent(io.agentscope.core.message.Msg.builder()
            .role(io.agentscope.core.message.MsgRole.ASSISTANT)
            .generateReason(io.agentscope.core.message.GenerateReason.PERMISSION_ASKING).build()));
        assertEquals(1, bridge.pendingInterrupts().size());
        bridge.accept(new AgentEndEvent("reply"));
        assertEquals(1, bridge.pendingInterrupts().size());
        var interrupt = bridge.pendingInterrupts().values().iterator().next();
        assertEquals("call", interrupt.toolCallId());
        assertEquals("write_file", interrupt.metadata().get("toolName"));
        assertTrue(emitted.stream().anyMatch(s -> s.contains("RUN_FINISHED") && s.contains("interrupts")));
        assertThrows(UnsupportedOperationException.class, () -> bridge.pendingInterrupts().clear());
    }
    @Test void thinkingActivityHasTimestampAndNeverPersistsRawReasoning() throws Exception {
        var bridge = bridge();
        bridge.accept(new ThinkingBlockDeltaEvent("reply","thought","private-secret-thought"));
        bridge.accept(new ThinkingBlockDeltaEvent("reply","thought","child-secret-thought").withSource("child-1"));
        assertEquals(2, emitted.size());
        assertEquals("ipd.thinking", mapper.readTree(emitted.get(0)).get("name").asText());
        assertEquals("subagent.thinking", mapper.readTree(emitted.get(1)).get("name").asText());
        for (String json : emitted) {
            var event = mapper.readTree(json);
            assertTrue(event.hasNonNull("timestamp"));
            assertTrue(event.get("value").get("active").asBoolean());
            assertFalse(event.get("value").has("delta"));
            assertFalse(json.contains("secret-thought"));
        }
    }
    @Test void projectionHasOneBusinessTerminalAndFinalCursorFrame() throws Exception {
        var projection = new ProjectAgentAguiProjection(mapper);
        var frames = projection.encode("42", new ProjectAgentViews.Event(9, "ERROR",
            Map.of("errorCode", "FAILED", "message", "失败"), "now"));
        assertEquals("RUN_ERROR", mapper.readTree(frames.get(0)).get("type").asText());
        assertFalse(frames.stream().anyMatch(s -> s.contains("RUN_FINISHED")));
        var last = mapper.readTree(frames.get(frames.size()-1));
        assertEquals("ipd_event", last.get("name").asText());
        assertEquals(9, last.get("value").get("seq").asLong());
        var row = new ProjectAgentViews.Event(10,"STEP",Map.of("kind","AGUI","events",List.of("{\"type\":\"CUSTOM\",\"name\":\"ipd_event\"}")),"now");
        assertEquals(2, projection.encode("42",row).size());
    }
    @Test void cursorRejectsNegativeAndMalformedLastId() {
        assertEquals(8, ProjectAgentAguiStream.cursor(5L,"8"));
        assertEquals(8, ProjectAgentAguiStream.cursor(8L,"5"));
        assertThrows(RuntimeException.class, () -> ProjectAgentAguiStream.cursor(0L,"-1"));
        assertThrows(RuntimeException.class, () -> ProjectAgentAguiStream.cursor(0L,"oops"));
        assertThrows(RuntimeException.class, () -> ProjectAgentAguiStream.cursor(-1L,"8"));
    }
    @Test void streamDefaultTimeoutCoversRunAndReconnectGrace() {
        var runs = mock(ProjectAgentRunService.class);
        var actor = mock(IpdActor.class);
        when(runs.events(actor,42L,0L)).thenReturn(new ProjectAgentViews.Events(List.of(),0,true));
        var emitter = new ProjectAgentAguiStream(runs,mapper).open(actor,42L,0L,null);
        assertEquals(330_000L, emitter.getTimeout());
    }
    @Test void streamTimeoutUsesConfiguredRunDeadline() {
        var runs = mock(ProjectAgentRunService.class);
        var actor = mock(IpdActor.class);
        when(runs.events(actor,42L,0L)).thenReturn(new ProjectAgentViews.Events(List.of(),0,true));
        var emitter = new ProjectAgentAguiStream(runs,mapper,600).open(actor,42L,0L,null);
        assertEquals(630_000L, emitter.getTimeout());
    }
    @Test void transportCompletionNeverCancelsBackgroundRun() throws Exception {
        var runs = mock(ProjectAgentRunService.class);
        var actor = mock(IpdActor.class);
        when(runs.events(actor,42L,0L)).thenReturn(new ProjectAgentViews.Events(List.of(),0,false));
        var emitter = new ProjectAgentAguiStream(runs,mapper).open(actor,42L,0L,null);
        var field = ResponseBodyEmitter.class.getDeclaredField("completionCallback");
        field.setAccessible(true);
        ((Runnable)field.get(emitter)).run();
        verify(runs,never()).cancel(any(),any());
        verify(runs,timeout(100).times(1)).events(actor,42L,0L);
    }
    @Test void waitingApprovalDoesNotTruncateFullReplayPage() {
        var runs = mock(ProjectAgentRunService.class);
        var actor = mock(IpdActor.class);
        List<ProjectAgentViews.Event> rows = new ArrayList<>();
        for (int i=1;i<=200;i++) rows.add(new ProjectAgentViews.Event(i,"STEP",Map.of("kind","MODEL_CALL"),"now"));
        when(runs.events(actor,42L,0L)).thenReturn(new ProjectAgentViews.Events(rows,200,false));
        when(runs.events(actor,42L,200L)).thenReturn(new ProjectAgentViews.Events(
            List.of(new ProjectAgentViews.Event(201,"RUN_FINISHED",Map.of(),"now")),201,true));
        new ProjectAgentAguiStream(runs,mapper).open(actor,42L,0L,null);
        verify(runs,timeout(3000)).events(actor,42L,200L);
        // 满页仍有未读事件时，不得按当前等待状态提前结束历史回放。
        verify(runs,never()).get(actor,42L);
        verify(runs,never()).cancel(any(),any());
    }
    @Test void historicalAwaitCannotStopBeforeCurrentPersistedPause() throws Exception {
        var runs = mock(ProjectAgentRunService.class);
        var actor = mock(IpdActor.class);
        when(runs.events(actor,42L,0L)).thenReturn(new ProjectAgentViews.Events(
            List.of(new ProjectAgentViews.Event(1,"STEP",Map.of("kind","AWAIT_USER"),"now")),1,false));
        when(runs.events(actor,42L,1L)).thenReturn(new ProjectAgentViews.Events(List.of(
            new ProjectAgentViews.Event(2,"STEP",Map.of("kind","AGUI_RESUMED","pauseSeq",1),"now"),
            new ProjectAgentViews.Event(3,"STEP",Map.of("kind","AWAIT_USER"),"now")),3,false));
        when(runs.get(actor,42L)).thenReturn(new ProjectAgentViews.Run("42","9","ipd_project_agent",
            "WAITING_APPROVAL",null,null,null,"now",null,List.of(),3L));
        var emitter = new ProjectAgentAguiStream(runs,mapper).open(actor,42L,0L,null);
        var complete = ResponseBodyEmitter.class.getDeclaredField("complete"); complete.setAccessible(true);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() -> complete.getBoolean(emitter));
        verify(runs).events(actor,42L,1L);
        verify(runs,never()).cancel(any(),any());
    }
    @Test void sequenceIdIsAttachedOnlyAfterAllNativeFrames() throws Exception {
        var runs = mock(ProjectAgentRunService.class);
        var actor = mock(IpdActor.class);
        var row = new ProjectAgentViews.Event(9,"STEP",Map.of("kind","AGUI","events",
            List.of("{\"type\":\"CUSTOM\",\"name\":\"ipd_event\"}")),"now");
        when(runs.events(actor,42L,0L)).thenReturn(new ProjectAgentViews.Events(List.of(row),9,true));
        var emitter = new ProjectAgentAguiStream(runs,mapper).open(actor,42L,0L,null);
        var complete = ResponseBodyEmitter.class.getDeclaredField("complete");
        complete.setAccessible(true);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3))
            .until(() -> complete.getBoolean(emitter));
        var pending = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        pending.setAccessible(true);
        StringBuilder wire = new StringBuilder();
        for (Object item : (Set<?>)pending.get(emitter)) {
            wire.append(((ResponseBodyEmitter.DataWithMediaType)item).getData());
        }
        String text = wire.toString();
        assertEquals(1, text.split("id:9",-1).length-1);
        assertTrue(text.indexOf("id:9") > text.indexOf("\"name\":\"ipd_event\""));
    }
}
