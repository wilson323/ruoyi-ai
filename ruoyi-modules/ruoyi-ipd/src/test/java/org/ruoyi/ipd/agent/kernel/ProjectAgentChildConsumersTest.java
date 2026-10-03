package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.transcript.FilesystemTranscriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("dev")
@Tag("dev")
class ProjectAgentChildConsumersTest {
    private static final Model NO_NETWORK = new Model() {
        public String getModelName() { return "no-network-fixture"; }
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new AssertionError("Construction test must not call a model"));
        }
    };

    @Test
    void canonicalParentAllowsTrustedChildSessionAndRevocationClosesFactory() throws Exception {
        var workspace = Files.createTempDirectory("child-canonical-test-");
        var scope = new ProjectAgentFoundationTools.Scope("1", "2", "3", workspace);
        var canonical = RuntimeContext.builder().userId("p1:u2").sessionId("aproject:s3").build();
        var allowed = new AtomicBoolean(true);
        Runnable access = () -> { if (!allowed.get()) throw new SecurityException("revoked"); };
        var guard = new ProjectAgentSubagentScopeMiddleware(scope, canonical, access,
            (leaf, trusted, context) -> leaf);
        guard.lineage().bindPolicy(ProjectAgentChildLineageRegistry.hash("canonical-parent-fixture"));
        var parent = HarnessAgent.builder().name("canonical-parent").model(NO_NETWORK).workspace(workspace).build();
        try {
            guard.onReasoning(parent, canonical, null, input -> Flux.empty()).blockLast();
            var manager = parent.getSubagentAgentManager();
            var name = manager.getAgentFactories().keySet().iterator().next();
            var issued=guard.lineage().issueParentCall(parent,canonical,io.agentscope.core.message.ToolUseBlock.builder()
                .id("canonical-spawn").name("agent_spawn").input(java.util.Map.of("agent_id",name)).build());
            var child = (HarnessAgent) manager.createAgent(name, issued);
            try {
                var trustedChild = RuntimeContext.builder(issued).sessionId("independent-child").build();
                assertThrows(SecurityException.class, () -> guard.lineage().requireKnown(child, trustedChild));
                assertDoesNotThrow(() -> guard.onAgent(child, trustedChild, null, input -> Flux.empty()).blockLast());
                assertDoesNotThrow(() -> guard.lineage().requireKnown(child, trustedChild));
                var unregisteredSession = RuntimeContext.builder(canonical)
                    .sessionId("sub-11111111-1111-1111-1111-111111111111").build();
                assertThrows(SecurityException.class, () -> guard.lineage().requireKnown(child, unregisteredSession));
                var impostor = HarnessAgent.builder().name(child.getName()).model(NO_NETWORK)
                    .workspace(workspace).build();
                try {
                    assertThrows(SecurityException.class, () -> guard.lineage().requireKnown(impostor, trustedChild));
                } finally {
                    impostor.close();
                }
                var forged = RuntimeContext.builder().userId(canonical.getUserId()).sessionId("independent-child").build();
                assertThrows(SecurityException.class, () -> guard.onAgent(child, forged, null, input -> Flux.empty()));
                allowed.set(false);
                assertThrows(SecurityException.class, () -> manager.createAgent(name, canonical));
                assertThrows(SecurityException.class, () -> guard.onActing(child, trustedChild, null, input -> Flux.empty()));
            } finally {
                child.close();
            }
        } finally {
            parent.close();
        }
    }

    @Test
    void realDelegateFailurePublishesOnlyFixedCategoryAndKeepsOriginalError() throws Exception {
        var workspace = Files.createTempDirectory("child-failure-test-");
        var scope = new ProjectAgentFoundationTools.Scope("1", "2", "3", workspace);
        var root = RuntimeContext.builder().userId("p1:u2").sessionId("aproject:s3").build();
        var parentRef = new AtomicReference<HarnessAgent>();
        var failure = new AtomicReference<ProjectAgentChildConsumers.ChildFailure>();
        var transcripts = new FilesystemTranscriptStore(workspace.resolve("transcripts"));
        var consumers = new ProjectAgentChildConsumers(parentRef::get, root, transcripts, "fixture",
            (context, request) -> ArtifactDeliveryResult.success("DRAFT fixture"),
            context -> Mono.empty(), () -> { }).failureSink(failure::set);
        var parent = ProjectAgentFoundationTools.configureFullCapabilities(
            HarnessAgent.builder().name("failure-parent").model(NO_NETWORK), scope, root, "alpine:3",
            new InMemoryAgentStateStore(), transcripts, MemoryConfig.defaults(),
            (candidate, context) -> Mono.just(new SkillPromotionGate.PromotionDecision.Defer(
                Duration.ofMinutes(1), "owner pending")), (skills, context) -> skills,
            (context, request) -> ArtifactDeliveryResult.success("DRAFT fixture")).build();
        assertEquals(root.getSessionId(), parent.getDefaultSessionId());
        parentRef.set(parent);
        var manager = parent.getSubagentAgentManager();
        var child = (HarnessAgent) manager.createAgent(manager.getAgentFactories().keySet().iterator().next(), root);
        try {
            var lineage=new ProjectAgentChildLineageRegistry(root,()->{});
            lineage.bindRoot(parent);
            consumers.childLineage(lineage);
            var parentCall=lineage.issueParentCall(parent,root,io.agentscope.core.message.ToolUseBlock.builder()
                .id("fixture-spawn").name("agent_spawn").input(java.util.Map.of("agent_id","general-purpose")).build());
            consumers.bind(child, scope, parentCall);
            var childContext = RuntimeContext.builder(root).sessionId("independent-child").build();
            var original = new IllegalStateException("private-exception-needle");
            assertSame(original, assertThrows(IllegalStateException.class, () -> consumers.onAgent(
                child.getDelegate(), childContext, null, input -> Flux.error(original)).blockLast()));
            assertEquals("CHILD_EXECUTION_FAILED", failure.get().reasonCode());
            assertEquals("IllegalStateException", failure.get().exceptionCategory());
            assertEquals(root.getSessionId(), failure.get().parentSession());
            assertEquals("independent-child", failure.get().childSession());
            assertFalse(failure.get().toString().contains("private-exception-needle"));
        } finally {
            child.close();
            parent.close();
        }
    }
    @Test
    void registeredOfficialChildSpawnKeepsNativeMaximumDepth() throws Exception {
        var workspace=Files.createTempDirectory("child-depth-test-");
        var foundation=new ProjectAgentFoundationTools.Scope("1","2","3",workspace);
        var root=RuntimeContext.builder().userId("p1:u2").sessionId("aproject:s3").build();
        var parentRef=new AtomicReference<HarnessAgent>();
        var consumerRef=new AtomicReference<ProjectAgentChildConsumers>();
        var guard=new ProjectAgentSubagentScopeMiddleware(foundation,root,()->{},
            (leaf,scope,context)->consumerRef.get().bind(leaf,scope,context));
        guard.bindParent(parentRef::get);
        guard.lineage().bindPolicy(ProjectAgentChildLineageRegistry.hash("native-depth-fixture"));
        var consumers=new ProjectAgentChildConsumers(parentRef::get,root,
            new FilesystemTranscriptStore(workspace.resolve("transcripts")),"fixture",
            (context,request)->ArtifactDeliveryResult.success("fixture"),context->Mono.empty(),()->{})
            .failureSink(failure->{throw new AssertionError(failure);}).childLineage(guard.lineage());
        consumerRef.set(consumers);
        var builder=HarnessAgent.builder().name("depth-parent").model(NO_NETWORK).workspace(workspace)
            .middleware(guard).enableSkillManageTool(true).enablePlanMode().enableTaskList()
            .enableSkillPromotionGate((candidate,context)->Mono.just(new SkillPromotionGate.PromotionDecision.Defer(
                Duration.ofMinutes(1),"owner pending")),(skills,context)->skills);
        var children=new java.util.ArrayList<HarnessAgent>();
        try(var parent=builder.build()) {
            parentRef.set(parent);guard.lineage().bindRoot(parent);
            guard.onReasoning(parent,root,null,input->Flux.empty()).blockLast();
            HarnessAgent actor=parent;RuntimeContext context=root;
            for(int depth=1;depth<=3;depth++) {
                var call=io.agentscope.core.message.ToolUseBlock.builder().id("spawn-"+depth).name("agent_spawn")
                    .input(java.util.Map.of("agent_id","general-purpose","task","depth proof")).build();
                var issued=guard.lineage().issueParentCall(actor,context,call);
                assertEquals(depth,guard.lineage().childSpawnDepth(issued));
                var child=(HarnessAgent)parent.getSubagentAgentManager().createAgent("general-purpose",issued);
                children.add(child);
                assertNotNull(child.getToolkit().getTool("agent_spawn"));
                assertNotNull(child.getToolkit().getTool("agent_send"));
                assertNotNull(child.getToolkit().getTool("agent_list"));
                context=RuntimeContext.builder(issued).sessionId("trusted-depth-"+depth).build();
                guard.onAgent(child,context,null,input->Flux.empty()).blockLast();
                actor=child;
            }
            var call=io.agentscope.core.message.ToolUseBlock.builder().id("spawn-4").name("agent_spawn")
                .input(java.util.Map.of("agent_id","general-purpose","task","must stop","timeout_seconds",10)).build();
            var result=actor.getToolkit().getTool("agent_spawn").callAsync(io.agentscope.core.tool.ToolCallParam.builder()
                .toolUseBlock(call).input(call.getInput()).agent(actor).runtimeContext(context).build()).block(Duration.ofSeconds(5));
            assertNotNull(result);
            assertTrue(result.getOutput().stream().filter(io.agentscope.core.message.TextBlock.class::isInstance)
                .map(io.agentscope.core.message.TextBlock.class::cast).anyMatch(text->text.getText().contains("Maximum spawn depth exceeded (max=3)")));
        } finally { children.forEach(HarnessAgent::close); }
    }

}
