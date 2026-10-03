package org.ruoyi.ipd.agent.kernel;
import io.agentscope.core.agent.*;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.state.*;
import io.agentscope.core.model.*;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.permission.*;
import io.agentscope.harness.agent.*;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.util.*;import java.nio.file.*;import java.time.*;import java.util.concurrent.atomic.*;import java.util.function.*;
import reactor.core.publisher.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class ProjectAgentAuthoritativeChildExecutionTest {
 @TempDir Path root;
 @Test void realChildToolExecutionUsesRegisteredActorBeforeStrictCheckpointLoad() {
  List<String> order=Collections.synchronizedList(new ArrayList<>());
  var store=new InMemoryAgentStateStore(){@Override public <T extends State> VersionedState<T> getVersioned(String u,String s,String k,Class<T> t){order.add("LOAD:"+s);return super.getVersioned(u,s,k,t);}};
  var kernelScope=org.ruoyi.chat.kernel.KernelScopeKey.of("9","7","agent","123");
  var ctx=kernelScope.toRuntimeContext();
  var sink=mock(ProjectAgentEventSink.class,CALLS_REAL_METHODS);
  var foundation=new ProjectAgentFoundationTools.Scope("9","7","123",root);
  var scope=new ProjectAgentSubagentScopeMiddleware(foundation,ctx,()->{},(leaf,parent,rc)->leaf);
  var strictStore=new ProjectAgentTemporaryStateStore(store,kernelScope,sink,scope.lineage());
  var governance=new ProjectAgentOfficialToolGovernance(sink,kernelScope,(actor,runtime)->{scope.lineage().requireKnown(actor,runtime);return true;});
  var replacements=new AtomicInteger();
  scope.onRegistered((actor,runtime)->{
   scope.lineage().requireKnown(actor,runtime);
   order.add("REGISTERED:"+runtime.getSessionId());
   io.agentscope.core.ReActAgent actual=(io.agentscope.core.ReActAgent)(actor instanceof HarnessAgent h?h.getDelegate():actor);
   var existing=actual.getAgentState(runtime.getUserId(),runtime.getSessionId()).getPermissionContext();
   var extended=ProjectAgentOfficialPermissions.extend(existing);
   if(!existing.getAllowRules().equals(extended.getAllowRules())) {
    actual.replacePermissionContext(runtime.getUserId(),runtime.getSessionId(),extended);
    replacements.incrementAndGet();
   }
  });
  AtomicReference<HarnessAgent> parent=new AtomicReference<>();scope.bindParent(parent::get);
  var recorder=new MiddlewareBase(){ public int order(){return Integer.MIN_VALUE;} public Flux<AgentEvent> onAgent(Agent a,RuntimeContext c,AgentInput i,Function<AgentInput,Flux<AgentEvent>> n){try{scope.lineage().requireKnown(a,c);order.add("REGISTERED:"+c.getSessionId());}catch(SecurityException e){order.add("UNKNOWN:"+c.getSessionId());}return n.apply(i);}};
  Model model=mock(Model.class);when(model.getModelName()).thenReturn("child-order");var count=new AtomicInteger();
  when(model.stream(any(),any(),any())).thenAnswer(inv->{
   int index=count.getAndIncrement();
   ToolUseBlock use=index==0?ToolUseBlock.builder().id("spawn-1").name("agent_spawn")
    .content("{\"agent_id\":\"general-purpose\",\"task\":\"write proof\",\"timeout_seconds\":10}")
    .input(Map.of("agent_id","general-purpose","task","write proof","timeout_seconds",10)).build()
    :ToolUseBlock.builder().id("child-write").name("write_file").content("{\"path\":\"child-proof.txt\",\"content\":\"CHILD_EFFECT\"}")
      .input(Map.of("path","child-proof.txt","content","CHILD_EFFECT")).build();
   return Flux.just(ChatResponse.builder().finishReason(index<2?"tool_calls":"stop")
    .content(index<2?List.of(use):List.of(TextBlock.builder().text("child proof").build())).build());
  });
  try(var harness=HarnessAgent.builder().name("parent-order").model(model).filesystem(new LocalFilesystemSpec().project(root)).workspace(root).stateStore(strictStore).middleware(scope).middleware(recorder).middleware(governance).permissionContext(ProjectAgentOfficialPermissions.workspace()).maxIters(3).build()){
   parent.set(harness);scope.lineage().bindRoot(harness);
   harness.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("spawn").build()),ctx).collectList().block(Duration.ofSeconds(20));
   System.out.println("ACTUAL_SDK_ORDER="+order);
   var child=order.stream().filter(e->e.startsWith("REGISTERED:sub-")).findFirst().orElseThrow();
   String session=child.substring("REGISTERED:".length());
   String storageSession=kernelScope.sessionId()+"/official/"+Base64.getUrlEncoder().withoutPadding().encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8));
   assertTrue(order.indexOf("LOAD:"+storageSession)>order.indexOf(child),"authoritative registration must precede strict child checkpoint load");
   assertEquals(1,replacements.get(),"only initial child baseline requires installation");
   assertEquals("CHILD_EFFECT",read(root.resolve(kernelScope.userId()).resolve("child-proof.txt")));
   assertThrows(IllegalArgumentException.class,()->strictStore.getVersioned(kernelScope.userId(),"sub-deadbeef","agent_state",AgentState.class));
   assertThrows(IllegalArgumentException.class,()->strictStore.getVersioned("other-person",session,"agent_state",AgentState.class));
   var fake=mock(Agent.class);
   assertThrows(SecurityException.class,()->governance.onActing(fake,ctx,new ActingInput(List.of()),in->Flux.empty()).blockLast());
   doThrow(new IllegalStateException("revoked")).when(sink).requireActiveOwnership();
   assertThrows(IllegalStateException.class,()->strictStore.getVersioned(kernelScope.userId(),session,"agent_state",AgentState.class));
   assertTrue(count.get()>=4,"real child model and parent continuation must execute");
  }
 }
 private static String read(Path path) {try{return Files.readString(path);}catch(Exception e){throw new IllegalStateException(e);}}
}
