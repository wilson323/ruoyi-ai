package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.Tag;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.*;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.*;import java.time.Duration;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class ProjectAgentAguiFrontendConsumerTest {
 @org.junit.jupiter.api.io.TempDir Path root;
 @org.junit.jupiter.api.Test void actualSdkFrontendResume() throws Exception {

  var schema=new AguiTool("fixture_frontend", "canonical catalog fixture", Map.of("type","object","properties",Map.of("value",Map.of("type","string"))));
  var input=RunAgentInput.builder().threadId("8123").runId("8123").tools(List.of(schema)).build();
  var call=ToolUseBlock.builder().id("frontend-call-1").name(schema.getName()).input(Map.of("value","chosen")).content("{\"value\":\"chosen\"}").build();
  var toolkit=new Toolkit();toolkit.registerAgentTool(new SchemaOnlyTool(new io.agentscope.core.agui.converter.AguiToolConverter().toToolSchemaList(List.of(schema)).get(0)));
  var sink=mock(ProjectAgentEventSink.class);
  var guard=new ProjectAgentOfficialToolGovernance(sink);
  var model=mock(Model.class);when(model.getModelName()).thenReturn("fixture-only");
  var turns=new AtomicInteger();when(model.stream(any(),any(),any())).thenAnswer(i->Flux.just(ChatResponse.builder().id("r").finishReason(turns.get()==0?"tool_calls":"stop").content(turns.getAndIncrement()==0?List.of(call):List.of(TextBlock.builder().text("done").build())).build()));
  var bridge=new ProjectAgentAguiBridge(sink,8123L,input);
  try(var agent=HarnessAgent.builder().name("fixture").model(model).toolkit(toolkit).middleware(guard).permissionContext(PermissionContextState.builder().build()).filesystem(new LocalFilesystemSpec().project(root)).workspace(root).stateStore(new InMemoryAgentStateStore()).maxIters(3).build()){
   guard.bind(agent.getToolkit());assertTrue(agent.getToolkit().isExternalTool(schema.getName()));assertTrue(((ToolBase)agent.getToolkit().getTool(schema.getName())).isConcurrencySafe());
   var ctx=RuntimeContext.builder().userId("fixture-person").sessionId("fixture-session").build();
   var first=agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("request frontend operation").build()),ctx).collectList().block(Duration.ofSeconds(10));
   first.forEach(bridge::accept);
   assertFalse(bridge.pendingInterrupts().isEmpty(),"real ASK must produce native pending interrupt");
   assertEquals(1,bridge.pendingInterrupts().size());
   assertEquals("permission_confirm",bridge.pendingInterrupts().values().iterator().next().metadata().get("agentscope.interruptKind"));
   assertTrue(first.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast)
       .anyMatch(e->e.getResult().getGenerateReason()==GenerateReason.PERMISSION_ASKING));
   System.out.println("ASK_PENDING="+bridge.pendingInterrupts());
   // Genuine SDK confirmation: the fixture user approves this exact pending call; no persistent ALLOW rule.
   var confirm=Msg.builder().role(MsgRole.USER).textContent("approve fixture operation").metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,List.of(new ConfirmResult(true,call)))).build();
   var resumed=agent.streamEvents(List.of(confirm),ctx).collectList().block(Duration.ofSeconds(10));
   var resumedBridge=new ProjectAgentAguiBridge(sink,8123L,input);resumed.forEach(resumedBridge::accept);
   assertFalse(resumedBridge.pendingInterrupts().isEmpty(),"external result must remain pending after genuine confirmation");
   String pending=resumedBridge.pendingInterrupts().toString();
   var suspended=resumed.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast).map(AgentResultEvent::getResult).filter(m->m.getGenerateReason()==GenerateReason.TOOL_SUSPENDED).toList();
   assertFalse(suspended.isEmpty());assertTrue(suspended.stream().flatMap(m->m.getContentBlocks(ToolResultBlock.class).stream()).anyMatch(b->b.isSuspended() && "frontend-call-1".equals(b.getId())));
   System.out.println("FRONTEND_PENDING="+pending);
   var externalInterrupt=resumedBridge.pendingInterrupts().values().iterator().next();
   assertEquals("frontend-call-1",externalInterrupt.toolCallId());
   assertEquals(schema.getName(),externalInterrupt.metadata().get("toolName"));
   assertFalse(externalInterrupt.metadata().containsKey("agentscope.interruptKind"),"external result must not become another approval");
   var externalResume=RunAgentInput.builder().threadId("8123").runId("8123").tools(List.of(schema))
       .resume(List.of(new AguiResume(externalInterrupt.id(),"resolved",Map.of("value","frontend-complete")))).build();
   var externalMsgs=new io.agentscope.core.agui.converter.AguiMessageConverter().toMsgList(externalResume,resumedBridge.pendingInterrupts());
   assertTrue(externalMsgs.stream().flatMap(m->m.getContentBlocks(ToolResultBlock.class).stream())
       .anyMatch(b->"frontend-call-1".equals(b.getId()) && !b.isSuspended()));
   var completed=agent.streamEvents(externalMsgs,ctx).collectList().block(Duration.ofSeconds(10));
   assertTrue(completed.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast)
       .anyMatch(e->e.getResult().getTextContent().contains("done")),"frontend result must resume real SDK to completion");
   System.out.println("FRONTEND_RESULT_RESUME_REAL_SDK_PASS");
   verify(sink,atLeastOnce()).onStep(eq("AGUI"),any());
  }
  var collision=new ProjectAgentRunSpec(8124L,20260929L,"tenant",11L,"C02","fixture",List.of(),List.of(),new org.ruoyi.chat.kernel.KernelModelRequest("fixture","fixture","fixture-key","https://example.invalid/v1"),Duration.ofSeconds(30)).withAguiInput(RunAgentInput.builder().threadId("8124").runId("8124").tools(List.of(new AguiTool("read_file","collision",Map.of("type","object")))).build());
  var kernel=new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler((key,ctx)->model),(p,t,q)->new org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext(0,0,""),root,2);
  assertThrows(IllegalArgumentException.class,()->kernel.buildAgent(collision,model,mock(ProjectAgentEventSink.class)));
  System.out.println("BACKEND_COLLISION_REJECTED; METADATA_ASK_COLLISION_PASS; FRONTEND_PAUSE_CONTRACT_PASS; fixture canonical catalog is not a production provider");

 }
}
