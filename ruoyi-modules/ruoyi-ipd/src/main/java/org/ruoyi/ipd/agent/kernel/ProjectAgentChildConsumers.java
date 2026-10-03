package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.harness.agent.memory.session.SessionTranscriptWriter;
import io.agentscope.harness.agent.tool.ArtifactDeliveryTool;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** 官方 leaf 保持原类型；补接父级官方工具、审计 transcript 与业务 owner 生命周期。 */
public final class ProjectAgentChildConsumers
    implements ProjectAgentSubagentScopeMiddleware.ChildConsumer, MiddlewareBase, AutoCloseable {
    private final Supplier<HarnessAgent> parentAgent;
    private final RuntimeContext trustedRoot;
    private final TranscriptStore transcripts;
    private final String tenant;
    private final ArtifactDeliveryTarget artifacts;
    private final Function<RuntimeContext, Mono<Void>> ownerLifecycle;
    private final Runnable requireCurrentAccess;
    private java.util.function.Consumer<ChildFailure> failureSink;
    private ProjectAgentChildLineageRegistry childLineage;
    private final java.util.concurrent.atomic.AtomicBoolean childrenClosed=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile RuntimeException childCloseFailure;

    /** DefaultAgentManager has no child-close ownership in SDK 2.0.3; this adapter owns every bound leaf. */
    public synchronized void closeChildren() {
        if(!childrenClosed.compareAndSet(false,true)) {
            if(childCloseFailure!=null) throw childCloseFailure;
            return;
        }
        var actualChildren=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<HarnessAgent,Boolean>());
        synchronized(boundChildren) { actualChildren.addAll(boundChildren.values()); }
        RuntimeException failed=null;
        for(var child:actualChildren) {
            try { child.close(); }
            catch(RuntimeException error) { if(failed==null) failed=error;else if(failed!=error) failed.addSuppressed(error); }
        }
        boundChildren.clear();
        childCloseFailure=failed;
        if(failed!=null) throw failed;
    }
    @Override public void close() { closeChildren(); }

    public ProjectAgentChildConsumers childLineage(ProjectAgentChildLineageRegistry lineage) {
        if(childLineage!=null && childLineage!=lineage) throw new SecurityException("Child lineage cannot be replaced");
        childLineage=Objects.requireNonNull(lineage);
        return this;
    }

    public record ChildFailure(String parentSession, String childSession, String agentId,
                               String reasonCode, String exceptionCategory) { }

    public ProjectAgentChildConsumers failureSink(java.util.function.Consumer<ChildFailure> sink) {
        this.failureSink = Objects.requireNonNull(sink);
        return this;
    }
    private final java.util.Map<Agent, HarnessAgent> boundChildren = java.util.Collections.synchronizedMap(
        new java.util.IdentityHashMap<>());

    public ProjectAgentChildConsumers(Supplier<HarnessAgent> parentAgent, RuntimeContext trustedRoot,
                                       TranscriptStore transcripts, String tenant,
                                       ArtifactDeliveryTarget artifacts,
                                       Function<RuntimeContext, Mono<Void>> ownerLifecycle,
                                       Runnable requireCurrentAccess) {
        this.parentAgent = Objects.requireNonNull(parentAgent);
        this.trustedRoot = Objects.requireNonNull(trustedRoot);
        this.transcripts = Objects.requireNonNull(transcripts);
        this.tenant = Objects.requireNonNull(tenant);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.ownerLifecycle = Objects.requireNonNull(ownerLifecycle);
        this.requireCurrentAccess = Objects.requireNonNull(requireCurrentAccess);
    }

    @Override
    public int order() {
        return Integer.MIN_VALUE + 2;
    }

    @Override
    public synchronized Agent bind(Agent officialLeaf, ProjectAgentFoundationTools.Scope scope, RuntimeContext context) {
        requireCurrentAccess.run();
        if(childrenClosed.get()) throw new IllegalStateException("Child consumers are already closed");
        Objects.requireNonNull(failureSink, "Child failure audit sink must be bound before spawn");
        if (!(officialLeaf instanceof HarnessAgent child)) {
            throw new IllegalStateException("Official Harness leaf is required");
        }
        HarnessAgent parent = Objects.requireNonNull(parentAgent.get(), "Parent must be bound before spawn");
        for (String name : new String[]{"skill_manage", "propose_skill", "plan_enter", "plan_write",
            "plan_exit", "todo_write"}) {
            var nativeTool = parent.getToolkit().getTool(name);
            if (nativeTool == null) {
                throw new IllegalStateException("Required official parent tool is unavailable: " + name);
            }
            if (child.getToolkit().getTool(name) == null) {
                child.getToolkit().registerAgentTool(nativeTool);
            }
        }
        // Never share the parent's AgentSpawnTool object: its fixed depth would reset the SDK depth guard.
        int depth=Objects.requireNonNull(childLineage,"Original child lineage must be bound before spawn")
            .childSpawnDepth(context);
        var manager=Objects.requireNonNull(parent.getSubagentAgentManager(),"Original official subagent manager required");
        child.getToolkit().registerTool(new io.agentscope.harness.agent.tool.AgentSpawnTool(
            manager,parent.getTaskRepository(),depth));
        child.getToolkit().registerMetaTool();
        child.getToolkit().registerAgentTool(new RuntimeNormalizedDeliveryTool(child, artifacts));
        boundChildren.put(child.getDelegate(), child);
        return child;
    }

    /** Use the executing child's prepared official normalizer; construction context is too early. */
    private static final class RuntimeNormalizedDeliveryTool extends io.agentscope.core.tool.ToolBase {
        private final HarnessAgent child;
        private final ArtifactDeliveryTarget target;
        private final io.agentscope.core.tool.ToolBase schema;
        RuntimeNormalizedDeliveryTool(HarnessAgent child, ArtifactDeliveryTarget target) {
            this(child, target, nativeTool(child, null, target));
        }
        private RuntimeNormalizedDeliveryTool(HarnessAgent child, ArtifactDeliveryTarget target,
                                             io.agentscope.core.tool.ToolBase schema) {
            super(io.agentscope.core.tool.ToolBase.builder().name(schema.getName())
                .description(schema.getDescription()).inputSchema(schema.getParameters())
                .readOnly(schema.isReadOnly()).concurrencySafe(schema.isConcurrencySafe())
                .externalTool(schema.isExternalTool()).stateInjected(schema.isStateInjected()));
            this.child = child; this.target = target; this.schema = schema;
        }
        private static io.agentscope.core.tool.ToolBase nativeTool(HarnessAgent child,
                io.agentscope.harness.agent.workspace.WorkspacePathNormalizer normalizer,
                ArtifactDeliveryTarget target) {
            var toolkit = new io.agentscope.core.tool.Toolkit();
            toolkit.registerTool(new ArtifactDeliveryTool(child.getWorkspaceManager().getFilesystem(), normalizer, target));
            return (io.agentscope.core.tool.ToolBase) toolkit.getTool("deliver_artifact");
        }
        @Override public Boolean getStrict() { return schema.getStrict(); }
        @Override public java.util.Map<String,Object> getOutputSchema() { return schema.getOutputSchema(); }
        @Override public boolean matchRule(String rule, java.util.Map<String,Object> input) { return schema.matchRule(rule,input); }
        @Override public java.util.List<io.agentscope.core.permission.PermissionRule> generateSuggestions(java.util.Map<String,Object> input) { return schema.generateSuggestions(input); }
        @Override public Mono<io.agentscope.core.permission.PermissionDecision> checkPermissions(
                java.util.Map<String,Object> input, io.agentscope.core.permission.PermissionContextState context) {
            return schema.checkPermissions(input,context);
        }
        @Override public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(io.agentscope.core.tool.ToolCallParam param) {
            return Mono.defer(() -> {
                var normalizer = Objects.requireNonNull(param.getRuntimeContext().get(
                    io.agentscope.harness.agent.workspace.WorkspacePathNormalizer.class),
                    "Executing child must have its official prepared workspace normalizer");
                return nativeTool(child,normalizer,target).callAsync(param);
            });
        }
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                   Function<AgentInput, Flux<AgentEvent>> next) {
        if (trustedRoot.getSessionId().equals(context.getSessionId())) {
            return next.apply(input);
        }
        return Flux.defer(() -> next.apply(input)).concatWith(Mono.defer(() -> {
            requireCurrentAccess.run();
            HarnessAgent child = agent instanceof HarnessAgent direct ? direct : boundChildren.get(agent);
            if (child == null) {
                return Mono.error(new IllegalStateException("Official Harness leaf is required"));
            }
            var state = RuntimeContext.resolveAgentState(context, child);
            if (state != null) {
                new SessionTranscriptWriter(child.getWorkspaceManager(), transcripts, tenant)
                    .appendMessages(context, state.getContext(), child.getAgentId(), context.getSessionId());
            }
            // Only this trusted adapter normalizes the owner context; child session remains in audit.
            RuntimeContext ownerContext = RuntimeContext.builder(context)
                .userId(trustedRoot.getUserId()).sessionId(trustedRoot.getSessionId()).build();
            ownerContext.put("ipd.audit.childSession", context.getSessionId());
            return ownerLifecycle.apply(ownerContext).then(Mono.<AgentEvent>empty());
        })).doOnError(error -> failureSink.accept(new ChildFailure(
            trustedRoot.getSessionId(), context.getSessionId(), agent.getAgentId(),
            "CHILD_EXECUTION_FAILED", safeExceptionCategory(error))));
    }

    private static String safeExceptionCategory(Throwable error) {
        if (error instanceof SecurityException) { return "SecurityException"; }
        if (error instanceof IllegalStateException) { return "IllegalStateException"; }
        if (error instanceof IllegalArgumentException) { return "IllegalArgumentException"; }
        return "ExecutionError";
    }
}

