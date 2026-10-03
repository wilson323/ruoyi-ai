package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectAgentAguiPublicEventTest {
    private static final String KEY = ProjectAgentAguiPublicEvent.INTERNAL_ORIGIN;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String,Object> receipt = Map.of("locator","server-secret-locator", "userId","server-user",
        "sessionId","server-child-session", "checkpointVersion",3L);
    private Map<String,Object> officialMetadata() {
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put(KEY,receipt); metadata.put("agentscope.interruptKind","permission_confirm");
        metadata.put("toolName","read_file"); metadata.put("replyId","reply-original");
        metadata.put("toolInput",Map.of("path","evidence.txt",KEY,Map.of("locator","argument-not-authority")));
        metadata.put("toolContent","{\"ipd.server.child.invocation\":\"literal-tool-argument\"}");
        metadata.put("locator","public-business-locator"); metadata.put("sessionId","official-public-session");
        return metadata;
    }
    private AguiEvent.Interrupt interrupt(Map<String,Object> metadata) {
        return new AguiEvent.Interrupt("reply-original:call-original","tool_call","批准", "call-original",null,null,metadata);
    }
    @Test void pausePublicRowStripsInternalReceiptButPreservesOfficialMetadataAndStore() throws Exception {
        var metadata=officialMetadata(); var originalInterrupt=interrupt(metadata);
        var payload=new LinkedHashMap<String,Object>();payload.put("kind","AWAIT_USER");payload.put("reason","AGUI_INTERRUPT");
        payload.put(KEY,receipt);payload.put("interrupts",Map.of(originalInterrupt.id(),originalInterrupt));
        var row=new ProjectAgentViews.Event(41,"STEP",payload,"2026-10-02T00:00:00Z");
        var publicRow=ProjectAgentAguiPublicEvent.project(mapper,row);
        assertEquals(row.seq(),publicRow.seq());assertEquals(row.type(),publicRow.type());assertEquals(row.createdAt(),publicRow.createdAt());
        var tree=mapper.valueToTree(publicRow.payload());assertFalse(tree.has(KEY));
        var projected=tree.path("interrupts").path(originalInterrupt.id()).path("metadata");
        assertFalse(projected.has(KEY));assertEquals("permission_confirm",projected.path("agentscope.interruptKind").asText());
        assertEquals("read_file",projected.path("toolName").asText());assertEquals("reply-original",projected.path("replyId").asText());
        assertEquals("public-business-locator",projected.path("locator").asText());
        assertEquals("official-public-session",projected.path("sessionId").asText());
        assertEquals("argument-not-authority",projected.path("toolInput").path(KEY).path("locator").asText());
        assertEquals(metadata.get("toolContent"),projected.path("toolContent").asText());
        assertSame(receipt,payload.get(KEY));assertSame(receipt,metadata.get(KEY));
        for(String frame:new ProjectAgentAguiProjection(mapper).encode("42",row)) {
            assertFalse(frame.contains("server-secret-locator"));assertFalse(frame.contains("server-child-session"));
        }
    }
    @Test void encodedNativeEventsAndFinalCursorFrameBothUsePublicProjection() throws Exception {
        String nativeJson=new AguiEventEncoder().encodeToJson(new AguiEvent.RunFinished("42","42",null,
            new AguiEvent.RunFinishedInterruptOutcome(List.of(interrupt(officialMetadata())))));
        var row=new ProjectAgentViews.Event(42,"STEP",Map.of("kind","AGUI","events",List.of(nativeJson)),"now");
        var frames=new ProjectAgentAguiProjection(mapper).encode("42",row);assertEquals(2,frames.size());
        for(String frame:frames) assertFalse(frame.contains("server-secret-locator"));
        assertTrue(nativeJson.contains("server-secret-locator"));
        var nativeFrame=mapper.readTree(frames.get(0));
        var metadata=nativeFrame.path("outcome").path("interrupts").get(0).path("metadata");
        assertEquals("permission_confirm",metadata.path("agentscope.interruptKind").asText());
        assertEquals("argument-not-authority",metadata.path("toolInput").path(KEY).path("locator").asText());
        var finalFrame=mapper.readTree(frames.get(1));assertEquals("ipd_event",finalFrame.path("name").asText());
        assertEquals(42,finalFrame.path("value").path("seq").asLong());
    }
    @Test void rawAndSubagentOfficialMetadataAreScopedWithoutChangingSourceOrArguments() throws Exception {
        var args=Map.of(KEY,"business-argument", "metadata",Map.of(KEY,"nested-business-argument"));
        var raw=Map.of("type","RAW","source","sub-child-public", "event",Map.of("metadata",officialMetadata(),"toolInput",args),
            "rawEvent",Map.of("metadata",officialMetadata(),"toolContent","unchanged"));
        var child=Map.of("type","CUSTOM","name","subagent.require_confirm", "value",Map.of("source","sub-child-public",
            "metadata",officialMetadata(),"toolInput",args));
        var row=new ProjectAgentViews.Event(8,"STEP",Map.of("kind","AGUI","events",List.of(mapper.writeValueAsString(raw),mapper.writeValueAsString(child))),"now");
        var frames=new ProjectAgentAguiProjection(mapper).encode("42",row);
        var rawView=mapper.readTree(frames.get(0));assertEquals("sub-child-public",rawView.path("source").asText());
        assertFalse(rawView.path("event").path("metadata").has(KEY));assertFalse(rawView.path("rawEvent").path("metadata").has(KEY));
        assertEquals("business-argument",rawView.path("event").path("toolInput").path(KEY).asText());
        assertEquals("nested-business-argument",rawView.path("event").path("toolInput").path("metadata").path(KEY).asText());
        var childView=mapper.readTree(frames.get(1));assertEquals("sub-child-public",childView.path("value").path("source").asText());
        assertFalse(childView.path("value").path("metadata").has(KEY));
        assertEquals("business-argument",childView.path("value").path("toolInput").path(KEY).asText());
    }
    @Test void ordinaryBusinessDataAndPlainTextAreNeverTreatedAsServerMetadata() {
        var source=new ProjectAgentViews.Event(9,"SOURCE",Map.of(KEY,"business-source", "metadata",Map.of(KEY,"business-metadata")),"now");
        assertEquals(source.payload(),ProjectAgentAguiPublicEvent.project(mapper,source).payload());
        var text=new ProjectAgentViews.Event(10,"TEXT_DELTA",Map.of("text",KEY+" is literal user content"),"now");
        assertEquals(text.payload(),ProjectAgentAguiPublicEvent.project(mapper,text).payload());
    }
    @Test void publicViewUsesIndependentPayloadSnapshot() {
        var payload=mapper.createObjectNode();payload.put("kind","AWAIT_USER");payload.set(KEY,mapper.valueToTree(receipt));payload.put("label","before");
        var publicRow=ProjectAgentAguiPublicEvent.project(mapper,new ProjectAgentViews.Event(11,"STEP",payload,"now"));
        assertTrue(payload.has(KEY));payload.put("label","after");
        assertEquals("before",mapper.valueToTree(publicRow.payload()).path("label").asText());
    }
    @Test void malformedEncodedNativeEventFailsInsteadOfLeakingUnparsedBody() {
        var row=new ProjectAgentViews.Event(12,"STEP",Map.of("kind","AGUI","events",List.of("not JSON "+KEY)),"now");
        assertThrows(IllegalStateException.class,()->ProjectAgentAguiPublicEvent.project(mapper,row));
    }
}
