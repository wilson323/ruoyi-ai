package org.ruoyi.ipd.agent.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.model.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.*;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.service.ProjectAgentRunHandle;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import reactor.core.publisher.Flux;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit real-Docker integration: offline model fixture, production Kernel/provider/handle; no business DB acceptance. */
@Tag("dev")
@Tag("docker")
class ProjectAgentProductionPauseReleaseTest {
    @TempDir Path root;

    @Test void originalHandlePausesOnlyAfterRealSandboxReleaseAndCheckpointPersistence() throws Exception {
        var primaryCalls=new AtomicInteger();
        Model fixture=new Model() {
            public String getModelName(){return "pause-release-offline-fixture";}
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> schemas,GenerateOptions options) {
                boolean primary=schemas!=null && schemas.stream().anyMatch(s->Set.of("fixture_frontend","agent_spawn","equip_tools","write_file").contains(s.getName()));
                if(!primary)return Flux.just(ChatResponse.builder().finishReason("stop").content(List.of(TextBlock.builder().text("fixture maintenance").build())).build());
                primaryCalls.incrementAndGet();
                var call=ToolUseBlock.builder().id("pause-release-call").name("fixture_frontend")
                    .input(Map.of("value","fixture")).content("{\"value\":\"fixture\"}").build();
                return Flux.just(ChatResponse.builder().finishReason("tool_calls").content(List.of(call)).build());
            }
        };
        var run=new IpdAgentRun();run.setId(830001L);run.setTenantId("fixture");run.setPersonId(7L);run.setStatus("RUNNING");
        var runStore=mock(AgentRunStore.class);when(runStore.findRun(run.getId())).thenReturn(Optional.of(run));
        when(runStore.appendEvent(any())).thenReturn(true);
        var closed=new CountDownLatch(1);
        var handle=new ProjectAgentRunHandle(run,runStore,new ObjectMapper(),()->1000L,closed::countDown);
        var archived=new AtomicReference<List<?>>();
        var errors=new AtomicReference<String>();
        var pauseObserved=new AtomicBoolean();
        var sandboxWrites=new AtomicInteger();
        var rawStore=new InMemoryAgentStateStore() {
            @Override public void save(String u,String s,String key,State state) {
                if("_sandbox_state".equals(key)) {
                    assertFalse(handle.isClosed(),"SDK release must persist before original handle closes");
                    sandboxWrites.incrementAndGet();
                }
                super.save(u,s,key,state);
            }
        };
        handle.setAguiInterruptHandler((pending,version)->{
            assertFalse(pending.isEmpty());assertTrue(version>=0);
            assertNotNull(archived.get(),"verified real Docker archive must precede the owning pause CAS");
            assertFalse(archived.get().isEmpty());assertTrue(sandboxWrites.get()>0,"official sandbox state must be saved before lease is released");
            pauseObserved.set(true);
        });
        ProjectAgentEventSink sink=new ProjectAgentEventSink() {
            public void requireActiveOwnership(){handle.requireActiveOwnership();}
            public <T>T withActiveOwnership(java.util.function.Supplier<T> action){return handle.withActiveOwnership(action);}
            public long executionEpoch(){requireActiveOwnership();return 1;}
            public void registerTemporaryStateCleanup(Runnable cleanup){handle.registerTemporaryStateCleanup(cleanup);}
            public void registerTerminalSuccessReceipt(Runnable receipt){handle.registerTerminalSuccessReceipt(receipt);}
            public void onAguiInterrupt(Map<String,io.agentscope.core.agui.event.AguiEvent.Interrupt> pending,long version){handle.onAguiInterrupt(pending,version);}
            public boolean isPaused(){return handle.isPaused();}
            public void onStep(String kind,Map<String,Object> details){if("SANDBOX_ARCHIVED".equals(kind))archived.set((List<?>)details.get("snapshots"));handle.onStep(kind,details);}
            public void onToolCall(String id,String name){handle.onToolCall(id,name);}
            public void onToolResult(String id,String name,String state){handle.onToolResult(id,name,state);}
            public void onSource(Map<String,Object> source){handle.onSource(source);}
            public void onText(String text){handle.onText(text);}
            public void onFinalText(String text){handle.onFinalText(text);}
            public void onArtifact(String id,String title,String hash,int version){handle.onArtifact(id,title,hash,version);}
            public void onError(String code){errors.set(code);closed.countDown();}
            public void onComplete(){errors.set("UNEXPECTED_COMPLETE");closed.countDown();}
        };
        var schema=new AguiTool("fixture_frontend","offline fixture asks the real owning run",Map.of("type","object","properties",Map.of("value",Map.of("type","string"))));
        var input=RunAgentInput.builder().threadId("830001").runId("830001")
            .messages(List.of(AguiMessage.userMessage("request","pause for fixture operation"))).tools(List.of(schema)).build();
        var spec=new ProjectAgentRunSpec(830001L,9L,"fixture",7L,null,"pause fixture",List.of(),List.of(),
            new KernelModelRequest("pause-release-offline-fixture","fixture",null,null),Duration.ofSeconds(45)).withAguiInput(input);
        var kernel=new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler((key,context)->fixture),
            (project,type,query)->new org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext(0,0,""),root,3);
        kernel.setStateStore(rawStore);
        kernel.setArtifactProviderFactory((sp,context,ownedSink,authority)->new ProjectAgentArtifactProviderFactory.Provider(
            new ProjectAgentExecutionClaims(new ProjectAgentExecutionClaims.Binding(String.valueOf(sp.runId()),context.getUserId(),1),
                ownedSink::executionEpoch,authority,(actor,runtime,path)->path),
            (context1,request)->{throw new AssertionError("pause fixture must not deliver business artifacts");}));
        var subscription=kernel.execute(spec,sink);handle.attach(subscription);
        try {
            assertTrue(closed.await(50,TimeUnit.SECONDS),"real Docker pause must finish within its deadline");
            assertNull(errors.get(),"production Kernel error: "+errors.get());
            assertTrue(pauseObserved.get());assertTrue(handle.isPaused());assertTrue(handle.isClosed());
            assertThrows(org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership.OwnershipLost.class,handle::requireActiveOwnership);
            var scope=KernelScopeKey.of("9","7",org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID,"830001");
            assertTrue(rawStore.getVersioned(scope.userId(),scope.sessionId(),"agent_state",AgentState.class).isPresent(),"paused native agent checkpoint must survive cleanup");
            String sandboxSlot=scope.sessionId()+"/official/"+Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("sandbox/session/"+scope.sessionId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(rawStore.get(scope.userId(),sandboxSlot,"_sandbox_state",State.class).isPresent(),"real sandbox resume metadata must survive owning pause");
            assertEquals(1,primaryCalls.get(),"offline fixture must stop at the first real permission request");
            System.out.println("PRODUCTION_KERNEL_REAL_DOCKER_RELEASE_BEFORE_PAUSE=PASS SNAPSHOT_RECEIPTS="+archived.get());
        } finally {subscription.dispose();}
    }
}
