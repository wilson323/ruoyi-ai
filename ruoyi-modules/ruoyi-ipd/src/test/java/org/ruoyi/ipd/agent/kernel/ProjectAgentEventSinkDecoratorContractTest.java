package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.service.*;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 拒绝生产装饰器静默落回接口的空/default生命周期实现。 */
class ProjectAgentEventSinkDecoratorContractTest {
    private void requireWholeContract(ProjectAgentEventSink sink) throws Exception {
        for(var method:ProjectAgentEventSink.class.getDeclaredMethods())
            assertNotEquals(ProjectAgentEventSink.class,sink.getClass().getMethod(method.getName(),method.getParameterTypes()).getDeclaringClass(),
                "decorator lost lifecycle: "+method.getName());
    }
    @Test void usageAndRuntimeAccessDecoratorsPreserveTheWholeLifecycleContract() throws Exception {
        var delegate=mock(ProjectAgentEventSink.class);when(delegate.executionEpoch()).thenReturn(19L);when(delegate.isPaused()).thenReturn(true);
        var usage=new ProjectAgentUsageSink(delegate,mock(AiModelUsageLedgerService.class),1L,"actor","trace");
        var guarded=new ProjectAgentRuntimeAccessSink(delegate,mock(ProjectAgentRunSpec.class),spec->{});
        for(var sink:List.of(usage,guarded)) {
            requireWholeContract(sink);assertEquals(19,sink.executionEpoch());assertTrue(sink.isPaused());
            Runnable receipt=new java.util.concurrent.atomic.AtomicInteger()::incrementAndGet;sink.registerTerminalSuccessReceipt(receipt);verify(delegate).registerTerminalSuccessReceipt(receipt);
        }
    }
    @Test void actualExecutorUsageLedgerBranchKeepsCommitChildPauseAndConsumedGuard() throws Exception {
        var handle=mock(ProjectAgentRunHandle.class);when(handle.executionEpoch()).thenReturn(23L);
        var executor=new ProjectAgentRunExecutor(mock(AgentRunStore.class),mock(ProjectAgentKernel.class),new ObjectMapper(),
            reactor.core.scheduler.Schedulers.immediate(),System::currentTimeMillis,1);
        executor.setUsageLedger(mock(AiModelUsageLedgerService.class));
        var method=ProjectAgentRunExecutor.class.getDeclaredMethod("usageSink",ProjectAgentRunHandle.class,IpdAgentRun.class);method.setAccessible(true);
        var sink=(ProjectAgentEventSink)method.invoke(executor,handle,IpdAgentRun.builder().id(1L).personId(7L).modelConfigId(2L).build());
        requireWholeContract(sink);assertEquals(23,sink.executionEpoch());Runnable receipt=()->{};
        sink.registerTerminalSuccessReceipt(receipt);verify(handle).registerTerminalSuccessReceipt(receipt);
        var approval=new ProjectAgentChildLineageRegistry.ChildApproval("locator","user","child",0,"reply",List.of());
        sink.onChildInterrupt(List.of(approval),0);verify(handle).onChildInterrupt(List.of(approval),0);
        sink.requireChildResumeConsumed(approval);verify(handle).requireChildResumeConsumed(approval);
    }
}
