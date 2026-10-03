package org.ruoyi.ipd.agent.kernel;
import com.fasterxml.jackson.databind.*;
import io.agentscope.core.agui.model.*;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ProjectAgentAguiFrontendBoundaryTest {
 static final RunAgentInput INPUT=RunAgentInput.builder().threadId("42").runId("42")
     .tools(List.of(new AguiTool("frontend","canonical",Map.of("type","object")))).build();
 static Msg result(String name,GenerateReason reason) {
  var call=ToolUseBlock.builder().id("exact-call").name(name).input(Map.of("arg","original")).content("{\"arg\":\"original\"}").build();
  return Msg.builder().id("original-reply").role(MsgRole.ASSISTANT).content(List.of(call,ToolResultBlock.suspended(call))).build().withGenerateReason(reason);
 }
 static ProjectAgentAguiBridge bridge(List<String> frames) {
  var sink=(ProjectAgentEventSink)java.lang.reflect.Proxy.newProxyInstance(ProjectAgentAguiFrontendBoundaryTest.class.getClassLoader(),
    new Class<?>[]{ProjectAgentEventSink.class},(p,m,a)->{if(m.getName().equals("onStep")) frames.addAll((List<String>)((Map<?,?>)a[1]).get("events"));return null;});
  return new ProjectAgentAguiBridge(sink,42L,INPUT);
 }
 static void outcomePreservesExactlyOneInterrupt(String tool) throws Exception {
  var frames=new ArrayList<String>();var bridge=bridge(frames);
  bridge.accept(new AgentResultEvent(result(tool,GenerateReason.TOOL_SUSPENDED)));
  bridge.accept(new AgentEndEvent("original-reply"));
  var json=new ObjectMapper();var finishes=frames.stream().map(s->{try{return json.readTree(s);}catch(Exception e){throw new RuntimeException(e);}})
    .filter(n->"RUN_FINISHED".equals(n.path("type").asText())).toList();
  assertEquals(1,finishes.size());assertEquals(1,finishes.get(0).path("outcome").path("interrupts").size());
  assertEquals("original-reply:exact-call",finishes.get(0).path("outcome").path("interrupts").get(0).path("id").asText());
  assertEquals(1,bridge.pendingInterrupts().size());
 }
 @org.junit.jupiter.api.Test void originalInterruptBoundaries() throws Exception {
  outcomePreservesExactlyOneInterrupt("frontend");System.out.println("PASS frontend native final outcome contains one original interrupt");
  outcomePreservesExactlyOneInterrupt("backend_read");System.out.println("PASS default backend mapping preserved without duplicate drain/enrich");
  var frames=new ArrayList<String>();var child=bridge(frames);
  child.accept(new AgentResultEvent(result("frontend",GenerateReason.TOOL_SUSPENDED)).withSource("child-1"));
  child.accept(new AgentEndEvent("original-reply").withSource("child-1"));
  assertTrue(child.pendingInterrupts().isEmpty());assertFalse(frames.stream().anyMatch(s->s.contains("RUN_FINISHED")));
  System.out.println("PASS child suspension cannot create parent pending or lifecycle");
  var permission=bridge(new ArrayList<>());permission.accept(new AgentResultEvent(result("frontend",GenerateReason.PERMISSION_ASKING)));
  assertTrue(permission.pendingInterrupts().isEmpty());System.out.println("PASS permission reason cannot become frontend execution suspension");
  var original=result("frontend",GenerateReason.TOOL_SUSPENDED);
  var missing=original.withContent(List.of(original.getFirstContentBlock(ToolResultBlock.class)));
  assertThrows(IllegalArgumentException.class,()->bridge(new ArrayList<>()).accept(new AgentResultEvent(missing)));
  var duplicate=original.withContent(List.of(original.getFirstContentBlock(ToolUseBlock.class),original.getFirstContentBlock(ToolResultBlock.class),original.getFirstContentBlock(ToolResultBlock.class)));
  assertThrows(IllegalArgumentException.class,()->bridge(new ArrayList<>()).accept(new AgentResultEvent(duplicate)));
  System.out.println("PASS missing or duplicate exact original frontend call rejects");
 }
}
