package org.ruoyi.ipd.agent.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProjectAgentAguiPauseLifecycleTest {
 private ProjectAgentRunHandle handle() {var run=IpdAgentRun.builder().id(42L).personId(7L).tenantId("t").status("RUNNING").version(1).idempotencyKey("pause42").build();var store=new InMemoryAgentRunStore();store.insertRun(run);return new ProjectAgentRunHandle(run,store,new ObjectMapper(),()->1000L,()->{});}
 private Map<String,AguiEvent.Interrupt> pending(){return Map.of("i",new AguiEvent.Interrupt("i","tool_call",null,"call",null,null,Map.of()));}
 @Test void persistedCallbackPrecedesPausedDisposeAndCleanupIsPreserved(){var h=handle();var writes=new AtomicInteger();var cleanup=new AtomicInteger();h.registerTemporaryStateCleanup(cleanup::incrementAndGet);h.setAguiInterruptHandler((p,v)->{assertFalse(h.isPaused());assertFalse(h.isClosed());writes.incrementAndGet();});var subscription=mock(reactor.core.Disposable.class);doAnswer(a->{assertEquals(1,writes.get());assertTrue(h.isPaused());return null;}).when(subscription).dispose();h.attach(subscription);h.onAguiInterrupt(pending(),3);h.releaseTemporaryState();assertTrue(h.isClosed());assertEquals(0,cleanup.get());verify(subscription).dispose();}
 @Test void persistenceFailureLeavesSubscriptionAndCheckpointActive(){var h=handle();h.setAguiInterruptHandler((p,v)->{throw new IllegalStateException("persist rejected");});var subscription=mock(reactor.core.Disposable.class);h.attach(subscription);assertThrows(IllegalStateException.class,()->h.onAguiInterrupt(pending(),3));assertFalse(h.isClosed());assertFalse(h.isPaused());verify(subscription,never()).dispose();}
 @Test void missingHandlerFailsLoudWithoutClosing(){var h=handle();assertThrows(IllegalStateException.class,()->h.onAguiInterrupt(pending(),3));assertFalse(h.isClosed());assertFalse(h.isPaused());}
}
