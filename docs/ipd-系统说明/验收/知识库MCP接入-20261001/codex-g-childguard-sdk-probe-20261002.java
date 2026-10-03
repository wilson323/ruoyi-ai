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
public class GChildGuardProbe { public static void main(String[] args)throws Exception{var t=new GChildGuardProbe();t.root=Files.createTempDirectory("g-childguard-work-");t.twoParallelChildrenHaveUniqueTrustedOriginsAndConsumeOnce();}
 @TempDir Path root;

 @Test void twoSequentialChildrenHaveUniqueTrustedOriginsAndConsumeOnce() { run(PermissionBehavior.ASK,true,false); }
 @Test void twoParallelChildrenHaveUniqueTrustedOriginsAndConsumeOnce(){run(PermissionBehavior.ASK,true,true);}
 private void run(PermissionBehavior prior,boolean confirm,boolean parallel) {
  List<String> order=Collections.synchronizedList(new ArrayList<>());
  var store=new InMemoryAgentStateStore(){@Override public <T extends State> VersionedState<T> getVersioned(String u,String s,String k,Class<T> t){order.add("LOAD:"+s);return super.getVersioned(u,s,k,t);}};
  var kernelScope=org.ruoyi.chat.kernel.KernelScopeKey.of("9","7",org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID,"123");
  var ctx=kernelScope.toRuntimeContext();
  var sink=mock(ProjectAgentEventSink.class,CALLS_REAL_METHODS);
  var writeEffects=new AtomicInteger();
  doAnswer(inv->{if("TOOL_EXECUTION".equals(inv.getArgument(0))){Map<String,Object> detail=inv.getArgument(1);if("write_file".equals(detail.get("toolName"))&&"RETURNED".equals(detail.get("state")))writeEffects.incrementAndGet();}return null;}).when(sink).onStep(anyString(),any());
  var foundation=new ProjectAgentFoundationTools.Scope("9","7","123",root);
  var scope=new ProjectAgentSubagentScopeMiddleware(foundation,ctx,()->{},(leaf,parent,rc)->leaf);
  var strictStore=new ProjectAgentTemporaryStateStore(store,kernelScope,sink,scope.lineage());
  var governance=new ProjectAgentOfficialToolGovernance(sink,kernelScope,(actor,runtime)->{scope.lineage().requireKnown(actor,runtime);return true;});
  var replacements=new AtomicInteger();
  record LivePending(Agent actor,RuntimeContext context,ToolUseBlock use) { }
  final String originKey="ipd.server.child.invocation";
  var origins=new java.util.concurrent.ConcurrentHashMap<String,LivePending>();
  var tokens=new java.util.concurrent.ConcurrentHashMap<String,String>();
  var live=new AtomicReference<LivePending>();
  var proposals=Collections.synchronizedList(new ArrayList<LivePending>());
  var confirmations=Collections.synchronizedList(new ArrayList<io.agentscope.core.event.RequireUserConfirmEvent>());
  var capture=new MiddlewareBase(){
   public int order(){return Integer.MAX_VALUE;}
   public Flux<AgentEvent> onAgent(Agent actor,RuntimeContext runtime,AgentInput input,Function<AgentInput,Flux<AgentEvent>> next){
    return Flux.deferContextual(view->{
     Flux<AgentEvent> downstream=next.apply(input);
     if(!runtime.getSessionId().equals(kernelScope.sessionId())) {
      scope.lineage().requireKnown(actor,runtime);
      var old=io.agentscope.core.event.AgentEventEmitter.fromForwardingContext(view).orElse(null);
      String token=tokens.computeIfAbsent(runtime.getSessionId(),key->java.util.UUID.randomUUID().toString());
      io.agentscope.core.event.AgentEventEmitter tagged=event->{
       scope.lineage().requireKnown(actor,runtime);
       old.emit(event.withMetadataEntry(originKey,token));
      };
      if(old!=null)downstream=downstream.contextWrite(c->c.put(io.agentscope.core.event.AgentEventEmitter.FORWARDING_CONTEXT_KEY,tagged));
     }
     return downstream.doOnNext(event->{
      if(event instanceof io.agentscope.core.event.RequireUserConfirmEvent ask) confirmations.add(ask);
     });
    });
   }

   public Flux<AgentEvent> onActing(Agent actor,RuntimeContext runtime,ActingInput input,Function<ActingInput,Flux<AgentEvent>> next){
    if(!runtime.getSessionId().equals(kernelScope.sessionId())){
     scope.lineage().requireKnown(actor,runtime);
     for(ToolUseBlock use:input.toolCalls()){
      var tuple=new LivePending(actor,runtime,use);proposals.add(tuple);
      origins.put(java.util.Objects.requireNonNull(tokens.get(runtime.getSessionId())),tuple);
     }
    }
    return next.apply(input);
   }
  };
  var seeded=new java.util.HashSet<String>();
  var childActor=new AtomicReference<io.agentscope.core.ReActAgent>();
  var childContext=new AtomicReference<RuntimeContext>();
  scope.onRegistered((actor,runtime)->{
   scope.lineage().requireKnown(actor,runtime);
   order.add("REGISTERED:"+runtime.getSessionId());
   io.agentscope.core.ReActAgent actual=(io.agentscope.core.ReActAgent)(actor instanceof HarnessAgent h?h.getDelegate():actor);
   if(!runtime.getSessionId().equals(kernelScope.sessionId())) {
    childActor.set(actual);childContext.set(runtime);
    if(seeded.add(runtime.getSessionId())) {
     var old=strictStore.getVersioned(runtime.getUserId(),runtime.getSessionId(),"agent_state",AgentState.class);
     var baseline=PermissionContextState.builder();
     var rule=new PermissionRule("write_file",null,prior,"prior-owner-decision");
     if(prior==PermissionBehavior.DENY)baseline.addDenyRule("write_file",rule);else baseline.addAskRule("write_file",rule);
     strictStore.saveIfVersion(runtime.getUserId(),runtime.getSessionId(),"agent_state",AgentState.builder()
      .userId(runtime.getUserId()).sessionId(runtime.getSessionId()).permissionContext(baseline.build()).build(),old.version());
    }
   }
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
   if(index==0){
    var input=Map.<String,Object>of("agent_id","general-purpose","task","write proof","timeout_seconds",10);
    String raw="{\"agent_id\":\"general-purpose\",\"task\":\"write proof\",\"timeout_seconds\":10}";
    return Flux.just(ChatResponse.builder().finishReason("tool_calls").content(List.of(
      ToolUseBlock.builder().id("spawn1").name("agent_spawn").input(input).content(raw).build(),
      ToolUseBlock.builder().id("spawn2").name("agent_spawn").input(input).content(raw).build())).build());
   }
   List<Msg> modelMessages=inv.getArgument(0);
   boolean initialChild=modelMessages.get(modelMessages.size()-1).getTextContent().equals("write proof");
   if(initialChild)return Flux.just(ChatResponse.builder().finishReason("tool_calls").content(List.of(
    ToolUseBlock.builder().id("identical-child-call").name("write_file").content("{\"path\":\"child-proof.txt\",\"content\":\"CHILD_EFFECT\"}")
     .input(Map.of("path","child-proof.txt","content","CHILD_EFFECT")).build())).build());
   return Flux.just(ChatResponse.builder().finishReason("stop").content(List.of(TextBlock.builder().text("parent stop").build())).build());
  });
  try(var harness=HarnessAgent.builder().name("parent-order").model(model).toolkit(new io.agentscope.core.tool.Toolkit(io.agentscope.core.tool.ToolkitConfig.builder().parallel(parallel).build())).filesystem(new LocalFilesystemSpec().project(root)).workspace(root).stateStore(strictStore).middleware(scope).middleware(recorder).middleware(governance).middleware(capture).permissionContext(ProjectAgentOfficialPermissions.workspace()).maxIters(3).build()){
   parent.set(harness);scope.lineage().bindRoot(harness);
   harness.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("spawn").build()),ctx).collectList().block(Duration.ofSeconds(20));
   System.out.println("ACTUAL_SDK_ORDER="+order);
   assertEquals(2,proposals.size());assertEquals(2,confirmations.size());
   assertNotEquals(proposals.get(0).context().getSessionId(),proposals.get(1).context().getSessionId());
   assertNotSame(proposals.get(0).actor(),proposals.get(1).actor());
   assertEquals(confirmations.get(0).getSource(),confirmations.get(1).getSource());
   String first=(String)confirmations.get(0).getMetadata().get(originKey);
   String second=(String)confirmations.get(1).getMetadata().get(originKey);
   assertNotNull(first);assertNotNull(second);assertNotEquals(first,second);
   assertEquals(0,writeEffects.get());
   var consumed=new java.util.concurrent.ConcurrentHashMap<String,AtomicBoolean>();
   for(var confirmation:confirmations){
    String token=(String)confirmation.getMetadata().get(originKey);
    LivePending tuple=java.util.Objects.requireNonNull(origins.get(token));
    scope.lineage().requireKnown(tuple.actor(),tuple.context());
    var saved=strictStore.getVersioned(kernelScope.userId(),tuple.context().getSessionId(),"agent_state",AgentState.class);
    var original=confirmation.getToolCalls().get(0);
    var rootSaved=store.getVersioned(kernelScope.userId(),kernelScope.sessionId(),"agent_state",AgentState.class);
    var originalCall=confirmation.getToolCalls().get(0);
    var snapshot=new ProjectAgentChildLineageRegistry.CallSnapshot(originalCall.getId(),originalCall.getName(),new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(originalCall.getInput()).toString(),originalCall.getContent());
    var approval=new ProjectAgentChildLineageRegistry.ChildApproval(token,kernelScope.userId(),tuple.context().getSessionId(),saved.version(),"reply",List.of(snapshot));
    var md=Map.<String,Object>of("agentscope.interruptKind","permission_confirm","toolName",originalCall.getName(),"toolInput",originalCall.getInput(),"toolContent",io.agentscope.core.util.JsonUtils.resolveToolCallArgsJson(originalCall));
    var interrupt=new io.agentscope.core.agui.event.AguiEvent.Interrupt("i","permission_confirm",null,originalCall.getId(),null,null,md);
    var pause=new org.ruoyi.ipd.agent.service.ProjectAgentAguiPauseResumeService.PauseCheckpoint(1,1,rootSaved.version(),"123","123","7",Map.of("i",interrupt),Map.of("i",approval));
    var run=org.ruoyi.ipd.agent.domain.IpdAgentRun.builder().id(123L).projectId(9L).personId(7L).build();
    var actor=new org.ruoyi.ipd.security.IpdActor(7L,"test","MEMBER",1L);
    var guard=new org.ruoyi.ipd.agent.service.ProjectAgentAguiCheckpointGuard(store,(a,in)->Map.of());
    guard.validate(actor,run,pause,null);
    System.out.println("ACTUAL_CHILD_GUARD_ACCEPT=true nativeSession="+approval.sessionId());
    java.util.function.Consumer<ProjectAgentChildLineageRegistry.ChildApproval> deny=a->{var bad=new org.ruoyi.ipd.agent.service.ProjectAgentAguiPauseResumeService.PauseCheckpoint(1,1,rootSaved.version(),"123","123","7",Map.of("i",interrupt),Map.of("i",a));assertThrows(IllegalStateException.class,()->guard.validate(actor,run,bad,null));};
    deny.accept(new ProjectAgentChildLineageRegistry.ChildApproval(token,kernelScope.userId(),approval.sessionId(),saved.version()+1,"reply",List.of(snapshot)));
    deny.accept(new ProjectAgentChildLineageRegistry.ChildApproval(token,kernelScope.userId(),"other-session",saved.version(),"reply",List.of(snapshot)));
    deny.accept(new ProjectAgentChildLineageRegistry.ChildApproval(token,"other-user",approval.sessionId(),saved.version(),"reply",List.of(snapshot)));
    deny.accept(new ProjectAgentChildLineageRegistry.ChildApproval(token,kernelScope.userId(),approval.sessionId(),saved.version(),"reply",List.of(new ProjectAgentChildLineageRegistry.CallSnapshot(snapshot.id(),snapshot.name(),"{}",snapshot.rawContent()))));
    deny.accept(new ProjectAgentChildLineageRegistry.ChildApproval(token,kernelScope.userId(),approval.sessionId(),saved.version(),"reply",List.of(new ProjectAgentChildLineageRegistry.CallSnapshot(snapshot.id(),snapshot.name(),snapshot.canonicalInput(),"{}"))));
    var rootOnly=new org.ruoyi.ipd.agent.service.ProjectAgentAguiPauseResumeService.PauseCheckpoint(1,1,rootSaved.version(),"123","123","7",Map.of("i",interrupt));assertThrows(IllegalStateException.class,()->guard.validate(actor,run,rootOnly,null));
    System.out.println("ACTUAL_CHILD_GUARD_NEGATIVE6=PASS");


    assertEquals(tuple.use().getId(),original.getId());assertEquals(tuple.use().getName(),original.getName());
    assertEquals(tuple.use().getInput(),original.getInput());assertEquals(tuple.use().getContent(),original.getContent());
    consumed.put(token,new AtomicBoolean());
    java.util.function.LongConsumer requireReceipt=version->{
     sink.requireActiveOwnership();scope.lineage().requireKnown(tuple.actor(),tuple.context());
     if(version!=saved.version()||strictStore.getVersioned(kernelScope.userId(),tuple.context().getSessionId(),"agent_state",AgentState.class).version()!=saved.version())throw new SecurityException("stale child checkpoint");
     if(!consumed.get(token).compareAndSet(false,true))throw new SecurityException("receipt already consumed");
    };
    int before=writeEffects.get();
    assertThrows(SecurityException.class,()->requireReceipt.accept(saved.version()+1));
    assertEquals(before,writeEffects.get());
    var winners=new AtomicInteger();var rejected=new AtomicInteger();
    Runnable consume=()->{try{requireReceipt.accept(saved.version());winners.incrementAndGet();}catch(SecurityException duplicate){rejected.incrementAndGet();}};
    java.util.concurrent.CompletableFuture.allOf(java.util.concurrent.CompletableFuture.runAsync(consume),java.util.concurrent.CompletableFuture.runAsync(consume)).join();
    assertEquals(1,winners.get());assertEquals(1,rejected.get());assertEquals(before,writeEffects.get());
    var receipt=Msg.builder().role(MsgRole.USER).textContent("批准")
     .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,List.of(new io.agentscope.core.event.ConfirmResult(true,original)))).build();
    ((io.agentscope.core.ReActAgent)tuple.actor()).streamEvents(List.of(receipt),tuple.context()).collectList().block(Duration.ofSeconds(20));
    assertEquals(before+1,writeEffects.get());
    var freshChild=strictStore.getVersioned(kernelScope.userId(),tuple.context().getSessionId(),"agent_state",AgentState.class);
    var finishedApproval=new ProjectAgentChildLineageRegistry.ChildApproval(token,kernelScope.userId(),approval.sessionId(),freshChild.version(),"reply",List.of(snapshot));
    deny.accept(finishedApproval);System.out.println("ACTUAL_CHILD_GUARD_FINISHED_OLD_ASK=REJECT");

    assertThrows(SecurityException.class,()->requireReceipt.accept(saved.version()));
    assertEquals(before+1,writeEffects.get());
   }
   assertEquals(2,writeEffects.get());
   assertEquals("CHILD_EFFECT",read(root.resolve(kernelScope.userId()).resolve("child-proof.txt")));
   System.out.println("TAGGED_CHILD_ORIGINS_UNIQUE=2 EFFECTS=2 WRONG_VERSION_EFFECTS=0 REPLAY_EFFECTS=0");

  }
 }
 private static String read(Path path) {try{return Files.readString(path);}catch(Exception e){throw new IllegalStateException(e);}}
}
