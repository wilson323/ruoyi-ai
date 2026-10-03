package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agui.model.*;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentAguiInputTest {
    @Test void frozenInputOwnsAllMutableSourceCollections() {
        var messages = new ArrayList<>(List.of(AguiMessage.userMessage("m", "original")));
        var schema = new LinkedHashMap<String,Object>(); schema.put("type", "object");
        var tools = new ArrayList<>(List.of(new AguiTool("read", "read", schema)));
        var context = new ArrayList<>(List.of(new AguiContext("hint", "original")));
        var payload = new LinkedHashMap<String,Object>(); payload.put("approved", false);
        var responses = new ArrayList<>(List.of(new AguiResume("i", "resolved", payload)));
        var client = RunAgentInput.builder().threadId("t").runId("r")
            .messages(messages).tools(tools).context(context).resume(responses).build();
        var frozen = ProjectAgentAguiInput.freeze(client);
        String digest = ProjectAgentAguiInput.digest(frozen);
        messages.clear(); tools.clear(); context.clear(); responses.clear();
        schema.put("type", "changed"); payload.put("approved", true);
        assertTrue(client.getMessages().isEmpty());
        assertEquals(1, frozen.getMessages().size());
        assertEquals("object", frozen.getTools().get(0).getParameters().get("type"));
        assertEquals(1, frozen.getContext().size());
        assertEquals(false, ((Map<?,?>)frozen.getResume().get(0).getPayload()).get("approved"));
        assertEquals(digest, ProjectAgentAguiInput.digest(frozen));
    }
    @Test void fullInputFreezeAndDigestPreserveContentAndIgnoreObjectKeyOrder() {
        var state = new LinkedHashMap<String,Object>();
        state.put("tab", "steps"); state.put("theme", "dark");
        var input = RunAgentInput.builder().threadId("t").runId("r")
            .messages(List.of(AguiMessage.userMessage("m", "secret user text")))
            .state(state).build();
        var frozen = ProjectAgentAguiInput.freeze(input);
        assertEquals("secret user text", ProjectAgentAguiInput.messages(frozen, Map.of()).get(0).getTextContent());
        var reordered = RunAgentInput.builder().threadId("t").runId("r")
            .messages(input.getMessages()).state(Map.of("theme", "dark", "tab", "steps")).build();
        assertEquals(ProjectAgentAguiInput.digest(input), ProjectAgentAguiInput.digest(reordered));
        assertNotEquals(ProjectAgentAguiInput.digest(input), ProjectAgentAguiInput.digest(
            RunAgentInput.builder().threadId("t").runId("r")
                .messages(List.of(AguiMessage.userMessage("m", "different"))).state(state).build()));
    }
    @Test void creationBindsServerIdentityAndPreservesOfficialFields() {
        var context = new AguiContext("display hint","材料");
        var client = RunAgentInput.builder().threadId("client-thread").runId("client-run").threadId("untrusted-thread").runId("untrusted-run")
            .messages(List.of(AguiMessage.userMessage("m1","正文"))).context(List.of(context))
            .state(Map.of("selectedTab","steps")).forwardedProps(Map.of("theme","dark")).build();
        var bound = ProjectAgentAguiInput.bind(client,"server-thread","server-run",Map.of());
        assertEquals("server-thread",bound.getThreadId());
        assertEquals("server-run",bound.getRunId());
        assertEquals(List.of(context),bound.getContext());
        assertEquals("steps",bound.getState().get("selectedTab"));
        assertEquals("dark",bound.getForwardedProps().get("theme"));
        assertEquals("untrusted-run",client.getRunId());
    }
    @Test void officialConverterPreservesOrderedMessagesToolArgumentsAndResults() {
        var call = new AguiToolCall("call1",new AguiFunctionCall("search","{\"query\":\"材料\"}"));
        var client = RunAgentInput.builder().threadId("client-thread").runId("client-run").messages(List.of(
            AguiMessage.userMessage("user1","问题"),
            AguiMessage.textMessage("assistant1","assistant","检索",List.of(call),null),
            AguiMessage.toolMessage("tool1","call1","有来源的结果"))).build();
        var msgs = ProjectAgentAguiInput.messages(ProjectAgentAguiInput.bind(ProjectAgentAguiInput.freeze(client),"thread","run",Map.of()),Map.of());
        assertEquals(3,msgs.size());
        assertEquals(MsgRole.USER,msgs.get(0).getRole());
        assertEquals(MsgRole.ASSISTANT,msgs.get(1).getRole());
        var use = msgs.get(1).getFirstContentBlock(ToolUseBlock.class);
        assertEquals("call1",use.getId());
        assertEquals("材料",use.getInput().get("query"));
        var result = msgs.get(2).getFirstContentBlock(ToolResultBlock.class);
        assertEquals("call1",result.getId());
        assertEquals("有来源的结果",((TextBlock)result.getOutput().get(0)).getText());
    }
    @Test void multimodalImageIsNotFlattenedIntoText() {
        var image = new ImageInputContent(new InputContentUrlSource("https://example.invalid/test.png"),Map.of());
        var client = RunAgentInput.builder().threadId("client-thread").runId("client-run").messages(List.of(AguiMessage.userMessage("image1",List.of(image)))).build();
        var msgs = ProjectAgentAguiInput.messages(ProjectAgentAguiInput.bind(ProjectAgentAguiInput.freeze(client),"thread","run",Map.of()),Map.of());
        assertEquals(1,msgs.size());
        assertTrue(msgs.get(0).hasContentBlocks(ImageBlock.class));
        assertFalse(msgs.get(0).hasContentBlocks(TextBlock.class));
    }
    @Test void frontendToolsRequireServerCatalogAndUseCanonicalSchema() {
        var requested = new AguiTool("read","client says write",Map.of("dangerous",true));
        var canonical = new AguiTool("read","server read-only",Map.of("type","object"));
        var client = RunAgentInput.builder().threadId("client-thread").runId("client-run").tools(List.of(requested)).build();
        var bound = ProjectAgentAguiInput.bind(client,"thread","run",Map.of("read",canonical));
        assertSame(canonical,bound.getTools().get(0));
        assertEquals(Map.of("type","object"),bound.getTools().get(0).getParameters());
        assertThrows(IllegalArgumentException.class,()->ProjectAgentAguiInput.bind(client,"thread","run",Map.of()));
        var duplicate = RunAgentInput.builder().threadId("client-thread").runId("client-run").tools(List.of(requested,requested)).build();
        assertThrows(IllegalArgumentException.class,()->ProjectAgentAguiInput.bind(duplicate,"thread","run",Map.of("read",canonical)));
    }
    @Test void uiMetadataCannotOverrideNestedIdentityPermissionOrState() {
        for (String key : List.of("personId","project_id","permissions","AgentState","sessionId","approved")) {
            var state = RunAgentInput.builder().threadId("client-thread").runId("client-run").state(Map.of("ui",Map.of(key,"spoof"))).build();
            assertThrows(IllegalArgumentException.class,()->ProjectAgentAguiInput.bind(state,"thread","run",Map.of()));
            var props = RunAgentInput.builder().threadId("client-thread").runId("client-run").forwardedProps(Map.of("ui",List.of(Map.of(key,"spoof")))).build();
            assertThrows(IllegalArgumentException.class,()->ProjectAgentAguiInput.bind(props,"thread","run",Map.of()));
        }
    }
    @Test void metadataSnapshotCannotBeChangedAfterAuthorization() {
        Map<String,Object> nested = new LinkedHashMap<>(); nested.put("tab","steps"); nested.put("nullable",null);
        Map<String,Object> state = new LinkedHashMap<>(); state.put("ui",nested);
        var bound = ProjectAgentAguiInput.bind(RunAgentInput.builder().threadId("client-thread").runId("client-run").state(state).build(),"thread","run",Map.of());
        nested.put("tab","changed");
        var ui = (Map<?,?>)bound.getState().get("ui");
        assertEquals("steps",ui.get("tab"));
        assertTrue(ui.containsKey("nullable"));
        assertThrows(UnsupportedOperationException.class,()->bound.getState().put("permission","write"));
    }
    @Test void resumeWithoutOriginalServerInterruptIsRejected() {
        var resume = new AguiResume("reply:call",AguiResume.STATUS_RESOLVED,Map.of("approved",true));
        var client = RunAgentInput.builder().threadId("client-thread").runId("client-run").resume(List.of(resume)).build();
        var bound = ProjectAgentAguiInput.bind(client,"thread","run",Map.of());
        assertThrows(IllegalArgumentException.class,()->ProjectAgentAguiInput.messages(bound,Map.of()));
    }
    @Test void clientMetadataCannotSupplyInternalChildLocator() {
        String key = "ipd.server.child.invocation";
        var state = RunAgentInput.builder().threadId("t").runId("r")
            .state(Map.of("nested", Map.of(key, "forged"))).build();
        var forwarded = RunAgentInput.builder().threadId("t").runId("r")
            .forwardedProps(Map.of(key, "forged")).build();
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentAguiInput.bind(state, "t", "r", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentAguiInput.bind(forwarded, "t", "r", Map.of()));
        var ordinary = RunAgentInput.builder().threadId("t").runId("r")
            .state(Map.of("ipd.display.preference", "steps")).build();
        assertEquals("steps", ProjectAgentAguiInput.bind(ordinary, "t", "r", Map.of()).getState()
            .get("ipd.display.preference"));
    }

}
