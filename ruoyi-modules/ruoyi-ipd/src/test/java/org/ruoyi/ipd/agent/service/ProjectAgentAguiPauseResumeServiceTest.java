package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.*;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际 handle/内存 store 的状态与事件验证；事务管理器替身不证明真实 DB 回滚。 */
class ProjectAgentAguiPauseResumeServiceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final InMemoryAgentRunStore store=new InMemoryAgentRunStore();
    private final ProjectAgentRunService runs=mock(ProjectAgentRunService.class);
    private final IpdActor actor=new IpdActor(7L,"owner","PM",null);
    private final IpdAgentRun run=IpdAgentRun.builder().id(42L).personId(7L).projectId(9L).tenantId("t")
        .version(1).status("RUNNING").idempotencyKey("create-42").build();
    private final ProjectAgentRunHandle handle;
    private final ProjectAgentAguiPauseResumeService service;
    ProjectAgentAguiPauseResumeServiceTest() {
        store.insertRun(run);
        handle=new ProjectAgentRunHandle(run,store,mapper,()->1000L,()->{});
        var lease=mock(ProjectAgentRunOwnership.Lease.class); when(lease.held()).thenReturn(true);
        handle.setOwnership(lease,1);
        var transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(ignored->new SimpleTransactionStatus());
        handle.setFinishTransaction(new TransactionTemplate(transactions));
        service=new ProjectAgentAguiPauseResumeService(store,runs,mapper,Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"),ZoneOffset.UTC));
    }
    private Map<String,AguiEvent.Interrupt> pending() {
        return Map.of("reply:call",new AguiEvent.Interrupt("reply:call","tool_call","批准", "call",null,null,
            Map.of("agentscope.interruptKind","permission_confirm","toolName","request_approval","toolInput",Map.of(),"replyId","reply")));
    }
    private RunAgentInput input() {
        return RunAgentInput.builder().threadId("42").runId("42")
            .resume(List.of(new AguiResume("reply:call","resolved",Map.of("approved",false,"reason","不同意")))).build();
    }
    @Test void pausePersistsNativeInterruptOutcomeAndOriginalBindingWithoutBusinessTerminal() throws Exception {
        var subscription=mock(reactor.core.Disposable.class); handle.attach(subscription);
        var pause=service.pause(handle,42L,0,pending());
        assertEquals("WAITING_APPROVAL",store.findRun(42L).orElseThrow().getStatus());
        assertEquals(1,pause.pauseEpoch()); assertEquals(0,pause.checkpointVersion());
        assertEquals("42",pause.runId()); assertEquals("7",pause.ownerPersonId());
        var row=store.listEvents(42L,pause.pauseSeq()-1,200).get(0); var payload=mapper.readTree(row.getPayload());
        assertEquals("AWAIT_USER",payload.get("kind").asText());
        assertEquals("AGUI_INTERRUPT",payload.get("reason").asText());
        assertEquals("reply:call",payload.get("interrupts").get("reply:call").get("id").asText());
        assertTrue(store.terminalSeq(42L).isEmpty()); assertFalse(handle.isClosed());
        verify(subscription,never()).dispose();
        var protocol=mapper.readTree(store.listEvents(42L,0,200).get(0).getPayload());
        var outcome=mapper.readTree(protocol.get("events").get(0).asText());
        assertEquals("RUN_FINISHED",outcome.get("type").asText());
        assertEquals("reply:call",outcome.get("outcome").get("interrupts").get(0).get("id").asText());
    }
    @Test void consumeUsesGuardAndOfficialMessagesExactlyOnce() throws Exception {
        var pause=service.pause(handle,42L,3,pending()); var guards=new AtomicInteger();
        ProjectAgentAguiPauseResumeService.TrustedGuard guard=(a,r,p,i)->{assertEquals(3,p.checkpointVersion());guards.incrementAndGet();return Map.of();};
        var first=service.consume(actor,handle,42L,pause.pauseSeq(),input(),guard);
        assertTrue(first.consumed()); assertFalse(first.messages().isEmpty());
        assertEquals("RUNNING",store.findRun(42L).orElseThrow().getStatus());
        var repeated=service.consume(actor,handle,42L,pause.pauseSeq(),input(),guard);
        assertFalse(repeated.consumed()); assertTrue(repeated.messages().isEmpty()); assertEquals(1,guards.get());
        assertEquals(pause.pauseSeq()+1,store.eventInserts.get()); assertEquals(1,store.runInserts.get());
        var resumed=mapper.readTree(store.listEvents(42L,pause.pauseSeq(),200).get(0).getPayload());
        assertEquals("AGUI_RESUMED",resumed.get("kind").asText()); assertEquals(pause.pauseSeq(),resumed.get("pauseSeq").asLong());
        verify(runs,times(4)).get(actor,42L);
    }
    @Test void missingGuardWrongOwnerOrWrongThreadCannotResume() {
        var pause=service.pause(handle,42L,1,pending());
        assertThrows(NullPointerException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),input(),null));
        assertThrows(IllegalArgumentException.class,()->service.consume(new IpdActor(8L,"other","PM",null),handle,42L,pause.pauseSeq(),input(),(a,r,p,i)->Map.of()));
        var wrong=RunAgentInput.builder().threadId("other").runId("42").resume(input().getResume()).build();
        assertThrows(IllegalArgumentException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),wrong,(a,r,p,i)->Map.of()));
        assertEquals("WAITING_APPROVAL",store.findRun(42L).orElseThrow().getStatus()); assertEquals(pause.pauseSeq(),store.eventInserts.get());
    }
    @Test void failedTrustedCheckpointOrApprovalWritesNothing() {
        var pause=service.pause(handle,42L,1,pending());
        assertThrows(IllegalStateException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),input(),
            (a,r,p,i)->{throw new IllegalStateException("checkpoint or approval rejected");}));
        assertEquals("WAITING_APPROVAL",store.findRun(42L).orElseThrow().getStatus()); assertEquals(pause.pauseSeq(),store.eventInserts.get());
    }
    @Test void resumeRetainsOnlyReauthorizedCanonicalFrontendTools() {
        var pause=service.pause(handle,42L,1,pending());
        var requested=new AguiTool("ui_preview","client schema",Map.of("unsafe",true));
        var canonical=new AguiTool("ui_preview","server schema",Map.of("type","object"));
        var response=RunAgentInput.builder().threadId("42").runId("42").resume(input().getResume()).tools(List.of(requested)).build();
        var resumed=service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->Map.of("ui_preview",canonical));
        assertTrue(resumed.consumed()); assertEquals(canonical,resumed.input().getTools().get(0));
    }
    @Test void emptyClientToolsRestoreOriginalCatalogWithoutLosingMessages() {
        var pause=service.pause(handle,42L,1,pending());
        var original=new AguiTool("ui_preview","server schema",Map.of("type","object"));
        var resumed=service.consume(actor,handle,42L,pause.pauseSeq(),input(),(a,r,p,i)->Map.of("ui_preview",original));
        assertTrue(resumed.consumed()); assertEquals(List.of(original),resumed.input().getTools());
        assertFalse(resumed.messages().isEmpty());
    }
    @Test void nonemptyClientToolsCannotRemoveOrAddOriginalSelections() {
        var pause=service.pause(handle,42L,1,pending());
        var first=new AguiTool("first","server",Map.of());
        var second=new AguiTool("second","server",Map.of());
        var partial=RunAgentInput.builder().threadId("42").runId("42").resume(input().getResume()).tools(List.of(first)).build();
        assertThrows(IllegalArgumentException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),partial,
            (a,r,p,i)->Map.of("first",first,"second",second)));
        var added=RunAgentInput.builder().threadId("42").runId("42").resume(input().getResume()).tools(List.of(first,second)).build();
        assertThrows(IllegalArgumentException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),added,
            (a,r,p,i)->Map.of("first",first)));
        assertEquals("WAITING_APPROVAL",store.findRun(42L).orElseThrow().getStatus());assertEquals(pause.pauseSeq(),store.eventInserts.get());
    }
    @Test void absentCatalogOrUnauthorizedFrontendToolCannotSilentlyResume() {
        var pause=service.pause(handle,42L,1,pending());
        assertThrows(NullPointerException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),input(),(a,r,p,i)->null));
        var response=RunAgentInput.builder().threadId("42").runId("42").resume(input().getResume())
            .tools(List.of(new AguiTool("unknown","unknown",Map.of()))).build();
        assertThrows(IllegalArgumentException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->Map.of()));
        assertEquals("WAITING_APPROVAL",store.findRun(42L).orElseThrow().getStatus()); assertEquals(pause.pauseSeq(),store.eventInserts.get());
    }
    @Test void changedDuplicateResponseConflictsAndCannotWriteOrExecuteAgain() {
        var pause=service.pause(handle,42L,1,pending()); var guards=new AtomicInteger();
        ProjectAgentAguiPauseResumeService.TrustedGuard guard=(a,r,p,i)->{guards.incrementAndGet();return Map.of();};
        assertFalse(service.replayConsumed(actor,42L,pause.pauseSeq(),input()));
        assertTrue(service.consume(actor,handle,42L,pause.pauseSeq(),input(),guard).consumed());
        assertTrue(service.replayConsumed(actor,42L,pause.pauseSeq(),input()));
        var changed=RunAgentInput.builder().threadId("42").runId("42")
            .resume(List.of(new AguiResume("reply:call","resolved",Map.of("approved",true)))).build();
        assertThrows(IllegalArgumentException.class,()->service.replayConsumed(actor,42L,pause.pauseSeq(),changed));
        assertThrows(IllegalArgumentException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),changed,guard));
        assertEquals(1,guards.get()); assertEquals(pause.pauseSeq()+1,store.eventInserts.get()); assertEquals(1,store.runInserts.get());
    }
    @Test void legacyConsumptionWithoutDigestCannotApproveReplay() {
        var pause=service.pause(handle,42L,1,pending());
        handle.onStep("AGUI_RESUMED",Map.of("pauseSeq",pause.pauseSeq()));
        assertThrows(IllegalArgumentException.class,()->service.replayConsumed(actor,42L,pause.pauseSeq(),input()));
        assertThrows(IllegalArgumentException.class,()->service.consume(actor,handle,42L,pause.pauseSeq(),input(),(a,r,p,i)->Map.of()));
        assertEquals(pause.pauseSeq()+1,store.eventInserts.get());
    }
    @Test @SuppressWarnings("unchecked") void mutableResponseOrGuardCannotChangeAuthorizedSnapshot() {
        var pause=service.pause(handle,42L,1,pending());
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("approved",false);payload.put("reason","不同意");
        var response=RunAgentInput.builder().threadId("42").runId("42")
            .resume(List.of(new AguiResume("reply:call","resolved",payload))).build();
        var resumed=service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->{
            payload.put("approved",true);
            ((Map<String,Object>)i.getResume().get(0).getPayload()).put("approved",true);
            return Map.of();
        });
        assertEquals(false,((Map<?,?>)resumed.input().getResume().get(0).getPayload()).get("approved"));
        assertTrue(service.replayConsumed(actor,42L,pause.pauseSeq(),input()));
        assertThrows(IllegalArgumentException.class,()->service.replayConsumed(actor,42L,pause.pauseSeq(),response));
    }
    @Test void latestPauseBeyondTwoHundredRowsRejectsOldResponse() {
        var old=service.pause(handle,42L,1,pending());
        assertTrue(service.consume(actor,handle,42L,old.pauseSeq(),input(),(a,r,p,i)->Map.of()).consumed());
        for(int i=0;i<205;i++) handle.onStep("MODEL_CALL",Map.of());
        var latest=service.pause(handle,42L,2,pending()); assertTrue(latest.pauseSeq()>200);
        assertThrows(IllegalArgumentException.class,()->service.consume(actor,handle,42L,old.pauseSeq(),input(),(a,r,p,i)->Map.of()));
        assertTrue(service.consume(actor,handle,42L,latest.pauseSeq(),input(),(a,r,p,i)->Map.of()).consumed());
    }
    @Test void wrongHandleLostEpochAndEmptyPendingRejectBeforePauseWrites() {
        assertThrows(IllegalArgumentException.class,()->service.pause(handle,43L,1,pending()));
        assertThrows(IllegalArgumentException.class,()->service.pause(handle,42L,1,Map.of()));
        assertThrows(IllegalArgumentException.class,()->service.pause(handle,42L,-1,pending()));
        store.claimEpoch(42L,1,Set.of(AgentRunStatus.RUNNING));
        assertThrows(ProjectAgentRunOwnership.OwnershipLost.class,()->service.pause(handle,42L,1,pending()));
        assertEquals("RUNNING",store.findRun(42L).orElseThrow().getStatus()); assertEquals(0,store.eventInserts.get());
    }

    private org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval child(String locator,String session,String call) {
        return new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval(locator,"p9:u7",session,0,"reply",
            List.of(new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.CallSnapshot(call,"request_approval","{\"x\":1}","{\"x\":1}")));
    }
    @Test void childPauseStoresTypedReceiptAndNativeOutcomeButPublicProjectionHidesAuthority() throws Exception {
        var pause=service.pauseChildren(handle,42L,0,List.of(child("private-locator","private-session","call")));
        assertEquals(1,pause.childApprovals().size());
        assertFalse(pause.pending().keySet().iterator().next().contains("private-locator"));
        var events=store.listEvents(42L,0,200);
        assertEquals(2,events.size());
        assertTrue(events.get(0).getPayload().contains("RUN_FINISHED"));
        assertFalse(events.get(0).getPayload().contains("private-locator"));
        assertTrue(events.get(1).getPayload().contains("private-locator"));
        var row=new org.ruoyi.ipd.agent.vo.ProjectAgentViews.Event(events.get(1).getSeq(),"STEP",mapper.readValue(events.get(1).getPayload(),Map.class),null);
        String publicJson=mapper.writeValueAsString(ProjectAgentAguiPublicEvent.project(mapper,row));
        assertFalse(publicJson.contains("private-locator")); assertFalse(publicJson.contains("private-session"));
        assertTrue(events.get(1).getPayload().contains("private-session"));
    }
    @Test void childConsumeGroupsExactCallsAndDoesNotApproveRoot() {
        var pause=service.pauseChildren(handle,42L,0,List.of(child("locator","session","one"),child("locator","session","two")));
        var response=RunAgentInput.builder().threadId("42").runId("42").resume(pause.pending().keySet().stream()
            .map(id->new AguiResume(id,"resolved",Map.of("approved",false,"reason","denied"))).toList()).build();
        var result=service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->Map.of());
        assertTrue(result.messages().isEmpty()); assertEquals(1,result.childResumes().size());
        assertEquals(2,result.childResumes().get(0).approval().calls().size());
        assertFalse(result.childResumes().get(0).messages().isEmpty());
        assertFalse(service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->{throw new AssertionError();}).consumed());
    }
    @Test void duplicateMissingOrWrongOwnerChildCallsRejectBeforeAnyWrite() {
        var child=child("locator","session","call");
        assertThrows(IllegalArgumentException.class,()->service.pauseChildren(handle,42L,0,List.of(child,child)));
        var empty=new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval("l","p9:u7","s",0,"r",List.of());
        assertThrows(IllegalArgumentException.class,()->service.pauseChildren(handle,42L,0,List.of(empty)));
        var other=new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval("l","p9:u8","s",0,"r",child.calls());
        assertThrows(IllegalArgumentException.class,()->service.pauseChildren(handle,42L,0,List.of(other)));
        assertEquals(0,store.maxSeq(42L)); assertEquals("RUNNING",store.findRun(42L).orElseThrow().getStatus());
    }

    @Test void typedReceiptsFreezeCallListsAndRejectRootSessionImpersonation() {
        var original=child("locator","session","call");
        var mutable=new ArrayList<>(original.calls());
        var approval=new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval(original.locator(),
            original.userId(),original.sessionId(),0,original.replyId(),mutable);
        var resume=new ProjectAgentAguiPauseResumeService.ChildResume(approval,List.of());
        mutable.clear(); assertEquals(1,resume.approval().calls().size());
        assertThrows(UnsupportedOperationException.class,()->resume.approval().calls().clear());
        var root=child("locator","aipd_project_agent:s42","call");
        assertThrows(IllegalArgumentException.class,()->service.pauseChildren(handle,42L,0,List.of(root)));
        assertEquals(0,store.maxSeq(42L));
    }
    @Test void childDispatchRequiresOriginalConsumedReceiptAndCompleteCalls() throws Exception {
        var one=child("locator","session","one"); var two=child("locator","session","two");
        var pause=service.pauseChildren(handle,42L,0,List.of(one,two));
        assertThrows(IllegalStateException.class,()->service.requireConsumedChild(handle,42L,one));
        var response=RunAgentInput.builder().threadId("42").runId("42").resume(pause.pending().keySet().stream()
            .map(id->new AguiResume(id,"resolved",Map.of("approved",false))).toList()).build();
        var consumed=service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->Map.of());
        var approval=consumed.childResumes().get(0).approval();
        assertDoesNotThrow(()->service.requireConsumedChild(handle,42L,approval));
        assertThrows(IllegalStateException.class,()->service.requireConsumedChild(handle,42L,one));
        var changed=mapper.valueToTree(approval);
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("locator","forged");
        var forged=mapper.treeToValue(changed,org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval.class);
        assertThrows(IllegalStateException.class,()->service.requireConsumedChild(handle,42L,forged));
        var changedCall=mapper.valueToTree(approval);
        ((com.fasterxml.jackson.databind.node.ObjectNode)changedCall.path("calls").get(0)).put("canonicalInput","{\"x\":2}");
        var altered=mapper.treeToValue(changedCall,org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval.class);
        assertThrows(IllegalStateException.class,()->service.requireConsumedChild(handle,42L,altered));
        assertEquals(pause.pauseSeq()+1,store.maxSeq(42L));
    }
    @Test void childDispatchRejectsConsumedReceiptFromDifferentExecutionEpoch() throws Exception {
        var pause=service.pauseChildren(handle,42L,0,List.of(child("locator","session","one")));
        var response=RunAgentInput.builder().threadId("42").runId("42").resume(pause.pending().keySet().stream()
            .map(id->new AguiResume(id,"resolved",Map.of("approved",false))).toList()).build();
        var consumed=service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->Map.of());
        var row=store.listEvents(42L,pause.pauseSeq(),1).get(0);
        var stale=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(row.getPayload());
        stale.put("executionEpoch",2); row.setPayload(mapper.writeValueAsString(stale));
        assertThrows(IllegalStateException.class,()->service.requireConsumedChild(handle,42L,consumed.childResumes().get(0).approval()));
        assertEquals("RUNNING",store.findRun(42L).orElseThrow().getStatus());
        assertEquals(pause.pauseSeq()+1,store.maxSeq(42L));
    }

    private org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval boundChild(String call,String policy,String nonce) {
        var original=child("locator","session",call);
        var factory=new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.FactoryDescriptor(
            "general-purpose","2.0.3","source-hash","module-hash",null,policy);
        var parent=new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ParentCall(nonce,"p9:u7",
            "aipd_project_agent:s42",new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.CallSnapshot(
                "spawn","agent_spawn","{}","{}"));
        return new org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval(original.locator(),
            original.userId(),original.sessionId(),original.checkpointVersion(),original.replyId(),original.calls(),factory,parent);
    }
    @Test void factoryAndParentBindingSurvivePersistenceFreezeAndMergedResume() throws Exception {
        var first=boundChild("one","server-policy","parent-nonce");
        var second=boundChild("two","server-policy","parent-nonce");
        var pause=service.pauseChildren(handle,42L,0,List.of(first,second));
        assertEquals(first.factory(),pause.childApprovals().values().iterator().next().factory());
        assertEquals(first.parentCall(),pause.childApprovals().values().iterator().next().parentCall());
        var response=RunAgentInput.builder().threadId("42").runId("42").resume(pause.pending().keySet().stream()
            .map(id->new AguiResume(id,"resolved",Map.of("approved",false))).toList()).build();
        var consumed=service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->Map.of());
        var approval=consumed.childResumes().get(0).approval();
        assertEquals(2,approval.calls().size()); assertEquals(first.factory(),approval.factory());
        assertEquals(first.parentCall(),approval.parentCall());
        assertDoesNotThrow(()->service.requireConsumedChild(handle,42L,approval));
        var modified=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(approval);
        ((com.fasterxml.jackson.databind.node.ObjectNode)modified.path("factory")).put("policyHash","forged");
        var changed=mapper.treeToValue(modified,org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval.class);
        assertThrows(IllegalStateException.class,()->service.requireConsumedChild(handle,42L,changed));
    }
    @Test void oneSessionCannotMergeDifferentFactoryOrParentBinding() {
        for (boolean changedFactory:new boolean[] {true,false}) {
            var fixture=new ProjectAgentAguiPauseResumeServiceTest();
            var first=fixture.boundChild("one","policy","nonce");
            var second=fixture.boundChild("two",changedFactory?"changed-policy":"policy",changedFactory?"nonce":"changed-nonce");
            var pause=fixture.service.pauseChildren(fixture.handle,42L,0,List.of(first,second));
            var response=RunAgentInput.builder().threadId("42").runId("42").resume(pause.pending().keySet().stream()
                .map(id->new AguiResume(id,"resolved",Map.of("approved",false))).toList()).build();
            assertThrows(IllegalArgumentException.class,()->fixture.service.consume(fixture.actor,fixture.handle,42L,
                pause.pauseSeq(),response,(a,r,p,i)->Map.of()));
            assertEquals("WAITING_APPROVAL",fixture.store.findRun(42L).orElseThrow().getStatus());
            assertEquals(pause.pauseSeq(),fixture.store.maxSeq(42L));
        }
    }

    @Test void coldServiceRebuildsConsumedRootMessagesFromPrivateIntentWithoutClientContext() throws Exception {
        var pause=service.pause(handle,42L,3,pending());
        var request=RunAgentInput.builder().threadId("42").runId("42").resume(input().getResume())
            .messages(List.of(AguiMessage.userMessage("u1","恢复说明")))
            .state(Map.of("transient","do-not-persist-state"))
            .forwardedProps(Map.of("display","do-not-persist-props")).build();
        var first=service.consume(actor,handle,42L,pause.pauseSeq(),request,(a,r,p,i)->Map.of());
        var cold=new ProjectAgentAguiPauseResumeService(store,runs,mapper);
        int before=store.eventInserts.get();
        var recovered=cold.loadConsumedIntent(handle,42L,pause.pauseSeq());
        assertEquals(first.messages().size(),recovered.messages().size());
        assertEquals("恢复说明",recovered.messages().get(0).getTextContent());
        assertConfirmationEquivalent(first.messages().get(1),recovered.messages().get(1));
        assertEquals(1,recovered.intent().executionEpoch());assertEquals(before,store.eventInserts.get());
        String persisted=store.listEvents(42L,pause.pauseSeq(),200).get(0).getPayload();
        assertFalse(persisted.contains("do-not-persist-state"));assertFalse(persisted.contains("do-not-persist-props"));
        var event=new org.ruoyi.ipd.agent.vo.ProjectAgentViews.Event(pause.pauseSeq()+1,"STEP",mapper.readTree(persisted),null);
        String publicJson=mapper.writeValueAsString(ProjectAgentAguiPublicEvent.project(mapper,event));
        assertFalse(publicJson.contains(ProjectAgentAguiPauseResumeService.INTERNAL_RESUME_INTENT));
        assertFalse(publicJson.contains("恢复说明"));assertTrue(publicJson.contains("AGUI_RESUMED"));
    }
    @Test void coldServiceRebuildsChildTypedConfirmationWithCompleteFactoryAndParentBinding() {
        var child=boundChild("one","policy","nonce");
        var pause=service.pauseChildren(handle,42L,0,List.of(child));
        var request=RunAgentInput.builder().threadId("42").runId("42").resume(pause.pending().keySet().stream()
            .map(id->new AguiResume(id,"resolved",Map.of("approved",false,"reason","deny"))).toList()).build();
        var first=service.consume(actor,handle,42L,pause.pauseSeq(),request,(a,r,p,i)->Map.of());
        var cold=new ProjectAgentAguiPauseResumeService(store,runs,mapper);
        var recovered=cold.loadConsumedIntent(handle,42L,pause.pauseSeq());
        assertTrue(recovered.messages().isEmpty());assertEquals(first.childResumes().get(0).approval(),recovered.childResumes().get(0).approval());
        assertConfirmationEquivalent(first.childResumes().get(0).messages().get(0),recovered.childResumes().get(0).messages().get(0));
        assertEquals(child.factory(),recovered.intent().childGroups().get(0).factory());
        assertEquals(child.parentCall(),recovered.intent().childGroups().get(0).parentCall());
    }
    @Test void consumedDigestAloneDoesNotReconstructApprovalAndLaterPauseInvalidatesIntent() {
        var pause=service.pause(handle,42L,0,pending());
        assertThrows(IllegalStateException.class,()->service.loadConsumedIntent(handle,42L,pause.pauseSeq()));
        store.transition(42L,Set.of(AgentRunStatus.WAITING_APPROVAL),AgentRunStatus.RUNNING,null,new Date());
        handle.onStep("AGUI_RESUMED",Map.of("pauseSeq",pause.pauseSeq(),"executionEpoch",1,"inputDigest","old-digest"));
        assertThrows(IllegalStateException.class,()->service.loadConsumedIntent(handle,42L,pause.pauseSeq()));
        var next=service.pause(handle,42L,1,pending());
        assertThrows(IllegalStateException.class,()->service.loadConsumedIntent(handle,42L,pause.pauseSeq()));
        assertEquals("WAITING_APPROVAL",store.findRun(42L).orElseThrow().getStatus());
        assertEquals(next.pauseSeq(),store.eventInserts.get());
    }

    private void assertConfirmationEquivalent(io.agentscope.core.message.Msg expected,io.agentscope.core.message.Msg actual) {
        String key=io.agentscope.core.message.Msg.METADATA_CONFIRM_RESULTS;
        var expectedResults=(List<?>)expected.getMetadata().get(key);var actualResults=(List<?>)actual.getMetadata().get(key);
        assertEquals(expectedResults.size(),actualResults.size());
        for(int i=0;i<expectedResults.size();i++) {
            var a=assertInstanceOf(io.agentscope.core.event.ConfirmResult.class,expectedResults.get(i));
            var b=assertInstanceOf(io.agentscope.core.event.ConfirmResult.class,actualResults.get(i));
            assertEquals(a.isConfirmed(),b.isConfirmed());assertEquals(a.getRules(),b.getRules());
            assertEquals(a.getToolCall().getId(),b.getToolCall().getId());assertEquals(a.getToolCall().getName(),b.getToolCall().getName());
            assertEquals(a.getToolCall().getInput(),b.getToolCall().getInput());assertEquals(a.getToolCall().getContent(),b.getToolCall().getContent());
        }
    }

    @Test void untouchedConsumedIntentCanBeFencedIntoNewEpochWithoutRepeatingConsumption() {
        var pause=service.pauseChildren(handle,42L,0,List.of(child("locator","session","one")));
        var response=RunAgentInput.builder().threadId("42").runId("42").resume(pause.pending().keySet().stream()
            .map(id->new AguiResume(id,"resolved",Map.of("approved",false))).toList()).build();
        var consumed=service.consume(actor,handle,42L,pause.pauseSeq(),response,(a,r,p,i)->Map.of());
        handle.abandonOwnership();
        int epoch=store.claimEpoch(42L,1,Set.of(AgentRunStatus.RUNNING)).orElseThrow();
        var current=store.findRun(42L).orElseThrow();
        var recoveredHandle=new ProjectAgentRunHandle(current,store,mapper,()->1000L,()->{});
        var lease=mock(ProjectAgentRunOwnership.Lease.class);when(lease.held()).thenReturn(true);recoveredHandle.setOwnership(lease,epoch);
        var transactions=mock(PlatformTransactionManager.class);when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        recoveredHandle.setFinishTransaction(new TransactionTemplate(transactions));
        var cold=new ProjectAgentAguiPauseResumeService(store,runs,mapper);
        var intent=cold.loadLatestConsumedIntent(recoveredHandle,42L);
        assertThrows(IllegalStateException.class,()->cold.requireConsumedChild(recoveredHandle,42L,consumed.childResumes().get(0).approval()));
        int before=store.eventInserts.get();cold.authorizeRecoveredIntent(recoveredHandle,42L,intent);
        cold.requireConsumedChild(recoveredHandle,42L,consumed.childResumes().get(0).approval());
        assertEquals(before+1,store.eventInserts.get());assertEquals(1,store.runInserts.get());
        assertEquals(1,cold.loadLatestConsumedIntent(recoveredHandle,42L).intent().executionEpoch());
    }
    @Test void possiblyStartedToolEffectCannotBeAutomaticallyReplayed() {
        var pause=service.pause(handle,42L,0,pending());service.consume(actor,handle,42L,pause.pauseSeq(),input(),(a,r,p,i)->Map.of());
        var intent=service.loadLatestConsumedIntent(handle,42L);
        handle.onStep("TOOL_EXECUTION",Map.of("state","STARTED","toolName","write_file"));
        int before=store.eventInserts.get();
        assertThrows(IllegalStateException.class,()->service.authorizeRecoveredIntent(handle,42L,intent));
        assertEquals(before,store.eventInserts.get());assertEquals("RUNNING",store.findRun(42L).orElseThrow().getStatus());
    }
    @Test void durableIntentRecoveryIsBoundedAndDoesNotCreateAnotherRun() {
        var pause=service.pause(handle,42L,0,pending());service.consume(actor,handle,42L,pause.pauseSeq(),input(),(a,r,p,i)->Map.of());
        var intent=service.loadLatestConsumedIntent(handle,42L);
        service.authorizeRecoveredIntent(handle,42L,intent);service.authorizeRecoveredIntent(handle,42L,intent);
        int before=store.eventInserts.get();assertThrows(IllegalStateException.class,()->service.authorizeRecoveredIntent(handle,42L,intent));
        assertEquals(before,store.eventInserts.get());assertEquals(1,store.runInserts.get());
    }

}
