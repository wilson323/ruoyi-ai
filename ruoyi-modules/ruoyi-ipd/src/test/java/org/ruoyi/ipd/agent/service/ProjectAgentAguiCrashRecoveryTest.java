package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.*;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.support.*;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import reactor.core.scheduler.Scheduler;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.*;

/** 实际 Service/Executor/Recovery 管线，内存 store 与替身 SDK；不冒充真实 DB/跨 JVM。 */
class ProjectAgentAguiCrashRecoveryTest {
    private final InMemoryAgentRunStore store=new InMemoryAgentRunStore();
    private final RunServiceHarness accessFixture=new RunServiceHarness(true,false,4);
    private final ObjectMapper mapper=AgentTestFixtures.MAPPER;
    private final ProjectAgentRunOwnership ownership=mock(ProjectAgentRunOwnership.class);
    private final List<Runnable> tasks=new ArrayList<>();
    private final List<ProjectAgentRunOwnership.Lease> leases=new ArrayList<>();
    private record Pipeline(ProjectAgentRunService service,ProjectAgentRunExecutor executor,FakeProjectAgentKernel kernel) { }
    private Pipeline pipeline(boolean reject) {
        when(ownership.acquire(anyLong())).thenAnswer(ignored->{var lease=mock(ProjectAgentRunOwnership.Lease.class);when(lease.held()).thenReturn(true);leases.add(lease);return Optional.of(lease);});
        var scheduler=mock(Scheduler.class);
        when(scheduler.schedule(any(Runnable.class))).thenAnswer(inv->{if(reject)throw new RejectedExecutionException("fixture scheduling rejected");tasks.add(inv.getArgument(0));return reactor.core.Disposables.single();});
        when(scheduler.schedulePeriodically(any(Runnable.class),anyLong(),anyLong(),any())).thenReturn(reactor.core.Disposables.single());
        var kernel=new FakeProjectAgentKernel();
        var executor=new ProjectAgentRunExecutor(store,kernel,mapper,scheduler,System::currentTimeMillis,4);
        executor.setOwnership(ownership);executor.setFinishTransaction(AgentOwnershipTestTransactions.create());
        var service=new ProjectAgentRunService(true,accessFixture.access,AgentTestFixtures.planner(),store,executor,mapper,System::currentTimeMillis,Duration.ofSeconds(60));
        var pauses=new ProjectAgentAguiPauseResumeService(store,service,mapper);
        executor.setAguiPauseResume(pauses);
        service.setAguiPauseResume(pauses,(actor,run,pause,input)->service.resolveAguiResumeTools(actor,run.getId(),input));
        return new Pipeline(service,executor,kernel);
    }
    private long pause(Pipeline pipeline) {
        long id=Long.parseLong(pipeline.service().create(ACTOR,PROJECT_ID,c02("crash-intent-fixture","请对本项目做竞品分析：功能、价格、渠道、技术路线")).runId());
        tasks.remove(0).run();
        pipeline.kernel().last().sink().onAguiInterrupt(Map.of("reply:call",new AguiEvent.Interrupt("reply:call","tool_call","确认","call",null,null,
            Map.of("agentscope.interruptKind","permission_confirm","toolName","request_approval","toolInput",Map.of(),"replyId","reply"))),0);
        return id;
    }
    private RunAgentInput response(long id) {
        return RunAgentInput.builder().threadId(Long.toString(id)).runId(Long.toString(id))
            .resume(List.of(new AguiResume("reply:call","resolved",Map.of("approved",false)))).build();
    }
    private ProjectAgentRunRecovery recovery(Pipeline pipeline) {
        var recovery=new ProjectAgentRunRecovery(store,ownership,AgentOwnershipTestTransactions.create(),mapper);
        recovery.setResumeRecovery(run->pipeline.service().recoverAguiIntent(ACTOR,run.getId()));return recovery;
    }
    @Test void durablePauseReleasesLeaseAndColdServiceResumesOriginalRun() {
        var first=pipeline(false);long id=pause(first);long seq=store.maxSeq(id);
        assertEquals("WAITING_APPROVAL",store.findRun(id).orElseThrow().getStatus());
        assertTrue(first.executor().handle(id).isEmpty());
        assertFalse(leases.isEmpty());
        leases.forEach(lease->verify(lease).close());
        var cold=pipeline(false);
        clearInvocations(ownership);
        assertFalse(recovery(cold).recover(store.findRun(id).orElseThrow()));
        verify(ownership,never()).acquire(anyLong());
        assertTrue(store.terminalSeq(id).isEmpty());
        cold.service().resume(ACTOR,id,seq,response(id));
        tasks.remove(0).run();
        assertEquals(1,cold.kernel().executions.size());
        assertEquals(1,store.runInserts.get());
        assertEquals("RUNNING",store.findRun(id).orElseThrow().getStatus());
        cold.executor().handle(id).orElseThrow().abandonOwnership();
    }
    @Test void heldLeasePreventsColdRecoveryFromDispatchingConsumedIntent() {
        var first=pipeline(false);long id=pause(first);
        first.service().resume(ACTOR,id,store.maxSeq(id),response(id));
        tasks.clear();var cold=pipeline(false);
        when(ownership.acquire(id)).thenReturn(Optional.empty());
        assertFalse(recovery(cold).recover(store.findRun(id).orElseThrow()));
        assertEquals("RUNNING",store.findRun(id).orElseThrow().getStatus());
        assertTrue(cold.kernel().executions.isEmpty());assertTrue(tasks.isEmpty());
        assertTrue(store.terminalSeq(id).isEmpty());
        first.executor().handle(id).orElseThrow().abandonOwnership();
    }
    @Test void committedIntentBeforeDispatchRestartsSameRunThroughOriginalRecovery() {
        var first=pipeline(false);long id=pause(first);long seq=store.maxSeq(id);
        first.service().resume(ACTOR,id,seq,response(id));
        assertEquals(1,first.kernel().executions.size());
        first.executor().handle(id).orElseThrow().abandonOwnership();tasks.clear();
        var cold=pipeline(false);
        assertTrue(recovery(cold).recover(store.findRun(id).orElseThrow()));
        assertEquals("RUNNING",store.findRun(id).orElseThrow().getStatus());assertTrue(store.terminalSeq(id).isEmpty());
        tasks.remove(0).run();assertEquals(1,cold.kernel().executions.size());assertEquals(1,store.runInserts.get());
        assertFalse(cold.kernel().last().spec().serverResumeMessages().isEmpty());
        assertEquals(1,store.events(id).stream().filter(e->e.getPayload().contains("\"kind\":\"AGUI_RESUMED\"")).count());
        cold.executor().handle(id).orElseThrow().abandonOwnership();
    }
    @Test void unknownToolEffectIsNotAutomaticallyRepeatedByOriginalRecovery() {
        var first=pipeline(false);long id=pause(first);first.service().resume(ACTOR,id,store.maxSeq(id),response(id));
        var handle=first.executor().handle(id).orElseThrow();handle.onStep("TOOL_EXECUTION",Map.of("state","STARTED","toolName","write_file"));
        handle.abandonOwnership();tasks.clear();var cold=pipeline(false);
        assertTrue(recovery(cold).recover(store.findRun(id).orElseThrow()));
        assertEquals("FAILED",store.findRun(id).orElseThrow().getStatus());assertTrue(cold.kernel().executions.isEmpty());assertTrue(tasks.isEmpty());
        assertEquals(1,store.runInserts.get());
    }
    @Test void schedulingRejectionAfterCommitPreservesRecoverableRunningIntent() throws Exception {
        var first=pipeline(false);long id=pause(first);
        // Switch only the original dispatch scheduler after real create/pause, keeping the same service and run.
        var rejecting=mock(Scheduler.class);when(rejecting.schedule(any(Runnable.class))).thenThrow(new RejectedExecutionException("fixture reject"));
        when(rejecting.schedulePeriodically(any(Runnable.class),anyLong(),anyLong(),any())).thenReturn(reactor.core.Disposables.single());
        var field=ProjectAgentRunExecutor.class.getDeclaredField("scheduler");field.setAccessible(true);field.set(first.executor(),rejecting);
        assertThrows(ProjectAgentRunExecutor.ResumeDeferred.class,()->first.service().resume(ACTOR,id,store.maxSeq(id),response(id)));
        assertEquals("RUNNING",store.findRun(id).orElseThrow().getStatus());assertTrue(store.terminalSeq(id).isEmpty());assertEquals(1,first.kernel().executions.size());
        var cold=pipeline(false);assertTrue(recovery(cold).recover(store.findRun(id).orElseThrow()));
        tasks.remove(0).run();assertEquals(1,cold.kernel().executions.size());assertEquals(1,store.runInserts.get());
        cold.executor().handle(id).orElseThrow().abandonOwnership();
    }
}
