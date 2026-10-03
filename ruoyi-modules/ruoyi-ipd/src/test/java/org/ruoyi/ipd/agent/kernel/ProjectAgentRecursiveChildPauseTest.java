package org.ruoyi.ipd.agent.kernel;
import io.agentscope.core.agent.*;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.state.*;
import io.agentscope.core.model.*;
import io.agentscope.core.event.AgentEvent;import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.permission.*;
import io.agentscope.harness.agent.*;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.util.*;import java.nio.file.*;import java.time.*;import java.util.concurrent.atomic.*;import java.util.function.*;
import reactor.core.publisher.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
@org.junit.jupiter.api.Tag("dev")
@Tag("dev")
class ProjectAgentRecursiveChildPauseTest {
 @TempDir Path root;
 @Test void recursiveColdPermissionPauseReturnsExactResultsToEachNativeParent() { run(PermissionBehavior.ASK,true,true); }

 private void run(PermissionBehavior prior,boolean confirm,boolean cold) {
  List<String> order=Collections.synchronizedList(new ArrayList<>());
  var store=new InMemoryAgentStateStore(){@Override public <T extends State> VersionedState<T> getVersioned(String u,String s,String k,Class<T> t){order.add("LOAD:"+s);return super.getVersioned(u,s,k,t);}};
  var kernelScope=org.ruoyi.chat.kernel.KernelScopeKey.of("9","7",org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID,"123");
  var ctx=kernelScope.toRuntimeContext();
  var sink=mock(ProjectAgentEventSink.class,CALLS_REAL_METHODS);
  var foundation=new ProjectAgentFoundationTools.Scope("9","7","123",root);
  var parent=new AtomicReference<HarnessAgent>();
  var consumersRef=new AtomicReference<ProjectAgentChildConsumers>();
  var scope=new ProjectAgentSubagentScopeMiddleware(foundation,ctx,()->{},(leaf,foundationScope,rc)->consumersRef.get().bind(leaf,foundationScope,rc));
  var consumers=consumers(parent,ctx,root).childLineage(scope.lineage());consumersRef.set(consumers);
  var frozenSpec=new ProjectAgentRunSpec(123L,9L,"fixture",7L,null,"spawn",List.of(),List.of("write_file","agent_spawn"),new org.ruoyi.chat.kernel.KernelModelRequest("child-order","fixture",null,null),Duration.ofSeconds(20),null,null,null);
  scope.lineage().bindPolicy(ProjectAgentChildPreflight.policyHash(frozenSpec));
  var strictStore=new ProjectAgentTemporaryStateStore(store,kernelScope,sink,scope.lineage());
  var governance=new ProjectAgentOfficialToolGovernance(sink,kernelScope,(actor,runtime)->{scope.lineage().requireKnown(actor,runtime);return true;});
  governance.childLineage(scope.lineage());
  var replacements=new AtomicInteger();
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
  scope.bindParent(parent::get);
  var recorder=new MiddlewareBase(){ public int order(){return Integer.MIN_VALUE;} public Flux<AgentEvent> onAgent(Agent a,RuntimeContext c,AgentInput i,Function<AgentInput,Flux<AgentEvent>> n){try{scope.lineage().requireKnown(a,c);order.add("REGISTERED:"+c.getSessionId());}catch(SecurityException e){order.add("UNKNOWN:"+c.getSessionId());}return n.apply(i);}};
  Model model=mock(Model.class);when(model.getModelName()).thenReturn("child-order");var count=new AtomicInteger();
  when(model.stream(any(),any(),any())).thenAnswer(inv->{
   List<io.agentscope.core.model.ToolSchema> schemas=inv.getArgument(1);
   if(schemas==null || schemas.stream().noneMatch(t->t.getName().equals("write_file") || t.getName().equals("agent_spawn")))
    return Flux.just(ChatResponse.builder().finishReason("stop").content(List.of(TextBlock.builder().text("fixture maintenance").build())).build());
   int index=count.getAndIncrement();System.out.println("REPEAT_PRIMARY_MODEL_INDEX="+index);
   ToolUseBlock use=index<2?ToolUseBlock.builder().id(index==0?"spawn-1":"spawn-2").name("agent_spawn")
    .content("{\"agent_id\":\"general-purpose\",\"task\":\"write proof\",\"timeout_seconds\":10}")
    .input(Map.of("agent_id","general-purpose","task","write proof","timeout_seconds",10)).build()
    :ToolUseBlock.builder().id(index==3?"child-write-2":"child-write").name("write_file").content(index==3?"{\"path\":\"child-proof-2.txt\",\"content\":\"CHILD_EFFECT_2\"}":"{\"path\":\"child-proof.txt\",\"content\":\"CHILD_EFFECT\"}")
      .input(index==3?Map.of("path","child-proof-2.txt","content","CHILD_EFFECT_2"):Map.of("path","child-proof.txt","content","CHILD_EFFECT")).build();
   return Flux.just(ChatResponse.builder().finishReason(index<4?"tool_calls":"stop")
    .content(index<4?List.of(use):List.of(TextBlock.builder().text("child proof").build())).build());
  });
  try(var harness=HarnessAgent.builder().name("parent-order").model(model).filesystem(new LocalFilesystemSpec().project(root)).workspace(root).stateStore(strictStore).middleware(scope).middleware(consumers).middleware(recorder).middleware(governance).permissionContext(ProjectAgentOfficialPermissions.workspace()).maxIters(3).enableSkillManageTool(true).enableSkillPromotionGate((candidate,context)->reactor.core.publisher.Mono.just(new io.agentscope.harness.agent.skill.curator.SkillPromotionGate.PromotionDecision.Defer(Duration.ofMinutes(1),"owner pending")),(skills,context)->skills).enablePlanMode().enableTaskList().build()){
   parent.set(harness);scope.lineage().bindRoot(harness);
   harness.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("spawn").build()),ctx).collectList().block(Duration.ofSeconds(20));
   System.out.println("ACTUAL_SDK_ORDER="+order);
   var child=order.stream().filter(e->e.startsWith("REGISTERED:sub-")).reduce((a,b)->b).orElseThrow();
   String session=child.substring("REGISTERED:".length());
   String storageSession=kernelScope.sessionId()+"/official/"+Base64.getUrlEncoder().withoutPadding().encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8));
   assertTrue(order.indexOf("LOAD:"+storageSession)>order.indexOf(child),"authoritative registration must precede strict child checkpoint load");
   assertEquals(2,replacements.get(),"only initial child baseline requires installation");
   assertFalse(Files.exists(root.resolve(kernelScope.userId()).resolve("child-proof.txt")),"prior decision must retain zero effect");
   var childState=strictStore.get(kernelScope.userId(),session,"agent_state",AgentState.class).orElseThrow();
   assertTrue(prior==PermissionBehavior.DENY?childState.getPermissionContext().getDenyRules().containsKey("write_file")
    :childState.getPermissionContext().getAskRules().containsKey("write_file"));
   if(confirm) {
    var pending=childState.getContext().stream().flatMap(m->m.getContentBlocks(ToolUseBlock.class).stream())
      .filter(t->t.getId().equals("child-write")).findFirst().orElseThrow();
    assertEquals(ToolCallState.ASKING,pending.getState());
    var receipt=Msg.builder().role(MsgRole.USER).textContent("批准")
      .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,List.of(new io.agentscope.core.event.ConfirmResult(true,pending)))).build();
    var rootCheckpoint=strictStore.getVersioned(ctx.getUserId(),ctx.getSessionId(),"agent_state",AgentState.class);
    var proposals=scope.lineage().checkpointApprovals(strictStore,rootCheckpoint.version());
    assertEquals(1,proposals.size());
    var approval=roundtrip(proposals.get(0));
    assertNotNull(approval.factory());assertNotNull(approval.parentCall());
    assertNotNull(approval.parentCall().factory());assertNotNull(approval.parentCall().ancestor());
    assertEquals(ctx.getSessionId(),approval.parentCall().ancestor().sessionId());
    assertEquals("general-purpose",approval.factory().name());
    var actorCreates=new AtomicInteger();
    var readOnlyBuilder=HarnessAgent.builder().subagentFactory("uncalled-fixture",name->{actorCreates.incrementAndGet();throw new IllegalStateException("must never create an actor during preflight");});
    var writesBefore=new AtomicInteger();
    AgentStateStore readOnly=mock(AgentStateStore.class);
    var parentOverride=new AtomicReference<AgentState>();
    doAnswer(inv->{if(parentOverride.get()!=null&&ctx.getSessionId().equals(inv.getArgument(1)))return new VersionedState<>(parentOverride.get(),approval.parentCall().checkpointVersion());
      return store.getVersioned(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3));}).when(readOnly).getVersioned(any(),any(),any(),any());
    doAnswer(inv->{writesBefore.incrementAndGet();throw new IllegalStateException("preflight wrote checkpoint");}).when(readOnly).saveIfVersion(any(),any(),any(),any(State.class),anyLong());
    doAnswer(inv->{writesBefore.incrementAndGet();throw new IllegalStateException("preflight wrote checkpoint");}).when(readOnly).save(any(),any(),any(),any(State.class));
    ProjectAgentChildPreflight.preflight(frozenSpec,approval,readOnly,readOnlyBuilder,root,()->{});
    var nested=approval.parentCall();
    var ancestor=nested.ancestor();
    var changedCall=new ProjectAgentChildLineageRegistry.CallSnapshot("another-spawn",ancestor.call().name(),ancestor.call().canonicalInput(),ancestor.call().rawContent());
    var changedAncestor=new ProjectAgentChildLineageRegistry.ParentCall(ancestor.nonce(),ancestor.userId(),ancestor.sessionId(),changedCall,ancestor.suspension(),ancestor.checkpointVersion());
    var changedNested=new ProjectAgentChildLineageRegistry.ParentCall(nested.nonce(),nested.userId(),nested.sessionId(),nested.call(),nested.suspension(),nested.checkpointVersion(),nested.factory(),changedAncestor);
    var invalidAncestor=new ProjectAgentChildLineageRegistry.ChildApproval(approval.locator(),approval.userId(),approval.sessionId(),approval.checkpointVersion(),approval.replyId(),approval.calls(),approval.factory(),changedNested);
    assertThrows(SecurityException.class,()->ProjectAgentChildPreflight.preflight(frozenSpec,invalidAncestor,readOnly,readOnlyBuilder,root,()->{}));
    assertEquals(0,actorCreates.get());assertEquals(0,writesBefore.get());
    Path kernelWorkspaceRoot=root.resolve("kernel-workspaces");
    try {ProjectAgentWorkspace.prepare(kernelWorkspaceRoot,"9","7",org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID);}catch(Exception e){throw new IllegalStateException(e);}
    var rawParent=store.getVersioned(ctx.getUserId(),ctx.getSessionId(),"agent_state",AgentState.class).value();
    String realUser=rawParent.getUserId(),realSession=rawParent.getSessionId();
    parentOverride.set(AgentState.builder().userId("wrong-person").sessionId(realSession).context(rawParent.getContext()).build());
    assertThrows(SecurityException.class,()->ProjectAgentChildPreflight.preflight(frozenSpec,approval,readOnly,readOnlyBuilder,root,()->{}));parentOverride.set(null);
    parentOverride.set(AgentState.builder().userId(realUser).sessionId("another-run").context(rawParent.getContext()).build());
    assertThrows(SecurityException.class,()->ProjectAgentChildPreflight.preflight(frozenSpec,approval,readOnly,readOnlyBuilder,root,()->{}));parentOverride.set(null);
    var oldParent=approval.parentCall();
    var noProof=new ProjectAgentChildLineageRegistry.ParentCall(oldParent.nonce(),oldParent.userId(),oldParent.sessionId(),oldParent.call());
    var forged=new ProjectAgentChildLineageRegistry.ChildApproval(approval.locator(),approval.userId(),approval.sessionId(),approval.checkpointVersion(),approval.replyId(),approval.calls(),approval.factory(),noProof);
    assertThrows(SecurityException.class,()->ProjectAgentChildPreflight.preflight(frozenSpec,forged,readOnly,readOnlyBuilder,root,()->{}));
    assertEquals(0,actorCreates.get());assertEquals(0,writesBefore.get());
    System.out.println("PREFLIGHT_IDENTITY_COUNTEREXAMPLES=2 NO_NATIVE_PROOF_REJECTED=true ACTOR_CREATES=0 CHECKPOINT_WRITES=0");
    var preflight=new ProjectAgentChildResumeDispatcher(scope,strictStore,a->{}); // fixture issuer only; production uses mandatory durable original-run CAS
    var legacy=new ProjectAgentChildLineageRegistry.ChildApproval(approval.locator(),approval.userId(),approval.sessionId(),approval.checkpointVersion(),approval.replyId(),approval.calls());
    assertThrows(SecurityException.class,()->preflight.resume(harness,ctx,List.of(new ProjectAgentChildResumeDispatcher.Resume(legacy,List.of(receipt))),null).collectList().block(Duration.ofSeconds(20)));
    var descriptor=approval.factory();
    var changed=new ProjectAgentChildLineageRegistry.FactoryDescriptor(descriptor.name(),descriptor.sdkVersion(),descriptor.sourceJarHash(),descriptor.moduleJarHash(),descriptor.declarationHash(),"changed-policy");
    var invalidPolicy=new ProjectAgentChildLineageRegistry.ChildApproval(approval.locator(),approval.userId(),approval.sessionId(),approval.checkpointVersion(),approval.replyId(),approval.calls(),changed,approval.parentCall());
    assertThrows(SecurityException.class,()->preflight.resume(harness,ctx,List.of(new ProjectAgentChildResumeDispatcher.Resume(invalidPolicy,List.of(receipt))),null).collectList().block(Duration.ofSeconds(20)));
    assertFalse(Files.exists(root.resolve(kernelScope.userId()).resolve("child-proof.txt")));
    if(!cold) {
      new ProjectAgentChildResumeDispatcher(scope,strictStore,a->{assertEquals(approval,a);}).resume(harness,ctx,List.of(new ProjectAgentChildResumeDispatcher.Resume(approval,List.of(receipt))),null).doOnNext(scope.lineage()::captureAndStrip).collectList().block(Duration.ofSeconds(20));
      assertFalse(scope.lineage().hasPendingChildApprovals(),"only persisted exact completed child pending must be removed");
    } else {
      var coldRef=new AtomicReference<HarnessAgent>();
      var coldConsumersRef=new AtomicReference<ProjectAgentChildConsumers>();
      var coldScope=new ProjectAgentSubagentScopeMiddleware(foundation,ctx,()->{},(leaf,pa,rc)->coldConsumersRef.get().bind(leaf,pa,rc));
      var coldConsumers=consumers(coldRef,ctx,root).childLineage(coldScope.lineage());coldConsumersRef.set(coldConsumers);
      coldScope.lineage().bindPolicy(ProjectAgentChildPreflight.policyHash(frozenSpec));
      var coldStore=new ProjectAgentTemporaryStateStore(store,kernelScope,sink,coldScope.lineage());
      var coldGov=new ProjectAgentOfficialToolGovernance(sink,kernelScope,(a,rc)->{coldScope.lineage().requireKnown(a,rc);return true;}).childLineage(coldScope.lineage());
      coldScope.onRegistered((a,rc)->{var actual=a instanceof HarnessAgent h?h.getDelegate():(io.agentscope.core.ReActAgent)a;
        var existing=actual.getAgentState(rc.getUserId(),rc.getSessionId()).getPermissionContext();var extended=ProjectAgentOfficialPermissions.extend(existing);
        if(!existing.getAllowRules().equals(extended.getAllowRules()))actual.replacePermissionContext(rc.getUserId(),rc.getSessionId(),extended);
      });
      coldScope.bindParent(coldRef::get);
      try(var rebuilt=HarnessAgent.builder().name("parent-order").model(model).filesystem(new LocalFilesystemSpec().project(root)).workspace(root).stateStore(coldStore).middleware(coldScope).middleware(coldConsumers).middleware(coldGov).permissionContext(ProjectAgentOfficialPermissions.workspace()).maxIters(3).enableSkillManageTool(true).enableSkillPromotionGate((candidate,context)->reactor.core.publisher.Mono.just(new io.agentscope.harness.agent.skill.curator.SkillPromotionGate.PromotionDecision.Defer(Duration.ofMinutes(1),"owner pending")),(skills,context)->skills).enablePlanMode().enableTaskList().build()) {
        coldRef.set(rebuilt);coldScope.lineage().bindRoot(rebuilt);
        assertFalse(coldScope.lineage().ownsSession(ctx.getUserId(),session),"cold registry starts with no admitted child string");
        new ProjectAgentChildResumeDispatcher(coldScope,coldStore,a->{assertEquals(approval,a);}).resume(rebuilt,ctx,List.of(new ProjectAgentChildResumeDispatcher.Resume(approval,List.of(receipt))),null).doOnNext(coldScope.lineage()::captureAndStrip).collectList().block(Duration.ofSeconds(20));
        assertTrue(coldScope.lineage().ownsSession(ctx.getUserId(),session),"server factory reconstruction registers actual actor before strict native restoration");
        assertTrue(coldScope.lineage().hasPendingChildApprovals(),"second native ASK must remain pending");
        assertEquals(4,count.get(),"original parent must not continue while the child asks again");
        var repeated=coldScope.lineage().checkpointApprovals(coldStore,rootCheckpoint.version());
        assertEquals(1,repeated.size(),"cold repeated ASK must remain durably resumable");
        assertEquals("child-write-2",repeated.get(0).calls().get(0).id());
        assertEquals(approval.parentCall(),repeated.get(0).parentCall(),"original suspended parent provenance must survive cold resume");
        assertTrue(coldStore.get(ctx.getUserId(),ctx.getSessionId(),"agent_state",AgentState.class).orElseThrow()
          .getContext().stream().flatMap(m->m.getContentBlocks(ToolResultBlock.class).stream()).noneMatch(t->t.getId().equals("spawn-1")),"second pause cannot commit parent tool result");
        assertFalse(Files.exists(root.resolve(kernelScope.userId()).resolve("child-proof-2.txt")));
        verifyRepeatedPauseBridge(coldScope.lineage(),coldStore,rootCheckpoint.version(),repeated);
        var nextAsk=coldStore.get(ctx.getUserId(),session,"agent_state",AgentState.class).orElseThrow().getContext().stream()
          .flatMap(m->m.getContentBlocks(ToolUseBlock.class).stream()).filter(t->t.getId().equals("child-write-2")).findFirst().orElseThrow();
        var nextReceipt=Msg.builder().role(MsgRole.USER).textContent("批准第二个工具")
          .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,List.of(new ConfirmResult(true,nextAsk)))).build();
        var finalEvents=new ProjectAgentChildResumeDispatcher(coldScope,coldStore,a->assertEquals(repeated.get(0),a))
          .resume(rebuilt,ctx,List.of(new ProjectAgentChildResumeDispatcher.Resume(repeated.get(0),List.of(nextReceipt))),null)
          .doOnNext(coldScope.lineage()::captureAndStrip).collectList().block(Duration.ofSeconds(20));
        assertFalse(coldScope.lineage().hasPendingChildApprovals());
        assertEquals(7,count.get(),"each approved child continuation and final parent must execute exactly once");
        assertTrue(finalEvents.stream().filter(AgentResultEvent.class::isInstance).anyMatch(event->
            (ctx.getSessionId()+"/general-purpose").equals(event.getSource())),"middle native result must carry its original upper-parent source");
        verifyParentBridgeFiltersNestedResults(coldScope.lineage(),coldStore,rootCheckpoint.version(),finalEvents);

      }
    }
    var parentCompleted=strictStore.get(ctx.getUserId(),ctx.getSessionId(),"agent_state",AgentState.class).orElseThrow();
    assertEquals(1,parentCompleted.getContext().stream().flatMap(m->m.getContentBlocks(ToolResultBlock.class).stream()).filter(t->t.getId().equals("spawn-1") && t.getState()==ToolResultState.SUCCESS).count(),"parent completes once after both approvals");
    var middle=strictStore.get(ctx.getUserId(),approval.parentCall().sessionId(),"agent_state",AgentState.class).orElseThrow();
    assertEquals(1,middle.getContext().stream().flatMap(m->m.getContentBlocks(ToolResultBlock.class).stream()).filter(t->t.getId().equals("spawn-2") && t.getState()==ToolResultState.SUCCESS).count(),"middle receives only its exact native grandchild result");
    assertEquals("CHILD_EFFECT_2",read(root.resolve(kernelScope.userId()).resolve("child-proof-2.txt")));
    assertEquals("CHILD_EFFECT",read(root.resolve(kernelScope.userId()).resolve("child-proof.txt")));
    assertEquals(2,replacements.get(),"resume must not replace already extended permission context");
   }

  }
 }
 private static ProjectAgentChildConsumers consumers(AtomicReference<HarnessAgent> parent,RuntimeContext root,Path workspace) {
  return new ProjectAgentChildConsumers(parent::get,root,new io.agentscope.harness.agent.transcript.FilesystemTranscriptStore(workspace.resolve("transcripts")),"fixture",
    (context,request)->io.agentscope.harness.agent.artifact.ArtifactDeliveryResult.success("fixture"),context->Mono.empty(),()->{}).failureSink(failure->{throw new AssertionError(failure);});
 }
 private static void verifyParentBridgeFiltersNestedResults(ProjectAgentChildLineageRegistry lineage,AgentStateStore store,long version,List<AgentEvent> events) {
  try {
   var sink=mock(ProjectAgentEventSink.class);
   var type=Class.forName(AgentScopeProjectAgentKernel.class.getName()+"$EventBridge");
   var constructor=type.getDeclaredConstructor(ProjectAgentEventSink.class,Long.class,io.agentscope.core.agui.model.RunAgentInput.class,ProjectAgentChildLineageRegistry.class,AgentStateStore.class,java.util.function.LongSupplier.class);
   constructor.setAccessible(true);
   var bridge=constructor.newInstance(sink,123L,null,lineage,store,(java.util.function.LongSupplier)()->version);
   var dispatch=type.getDeclaredMethod("dispatch",AgentEvent.class);dispatch.setAccessible(true);
   for(var event:events)dispatch.invoke(bridge,event);
   verify(sink,times(1)).onText("child proof");
   verify(sink,never()).onFinalText(anyString());
   verify(sink,never()).onToolResult(eq("spawn-2"),anyString(),anyString());
  } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
 }
 private static void verifyRepeatedPauseBridge(ProjectAgentChildLineageRegistry lineage,AgentStateStore store,long version,List<ProjectAgentChildLineageRegistry.ChildApproval> expected) {
  try {
   var sink=mock(ProjectAgentEventSink.class);
   var type=Class.forName(AgentScopeProjectAgentKernel.class.getName()+"$EventBridge");
   var constructor=type.getDeclaredConstructor(ProjectAgentEventSink.class,Long.class,io.agentscope.core.agui.model.RunAgentInput.class,ProjectAgentChildLineageRegistry.class,AgentStateStore.class,java.util.function.LongSupplier.class);
   constructor.setAccessible(true);
   var bridge=constructor.newInstance(sink,123L,null,lineage,store,(java.util.function.LongSupplier)()->version);
   var complete=type.getDeclaredMethod("complete");complete.setAccessible(true);complete.invoke(bridge);
   verify(sink).onChildInterrupt(expected,version);
   verify(sink,never()).onComplete();verify(sink,never()).onError(anyString());
  } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
 }
 private static ProjectAgentChildLineageRegistry.ChildApproval roundtrip(ProjectAgentChildLineageRegistry.ChildApproval approval) {
  try {var mapper=new com.fasterxml.jackson.databind.ObjectMapper();return mapper.readValue(mapper.writeValueAsString(approval),ProjectAgentChildLineageRegistry.ChildApproval.class);}
  catch(Exception failure){throw new IllegalStateException(failure);}
 }
 private static String read(Path path) {try{return Files.readString(path);}catch(Exception e){throw new IllegalStateException(e);}}
}
