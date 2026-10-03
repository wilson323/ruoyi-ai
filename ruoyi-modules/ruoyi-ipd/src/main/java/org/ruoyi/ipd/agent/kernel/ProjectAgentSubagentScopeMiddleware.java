package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/** 在动态注册刷新之后装饰官方 leaf 工厂，授权绑定可信父运行而非任意子 session。 */
public final class ProjectAgentSubagentScopeMiddleware implements MiddlewareBase {
    private final ProjectAgentFoundationTools.Scope scope;
    private final Runnable requireCurrentAccess;
    private final ChildConsumer childConsumer;
    private final Object trustToken = new Object();
    private final String contextKey;
    private final String trustedUser;
    private final String trustedSession;
    private final ProjectAgentChildLineageRegistry lineage;
    private java.util.function.BiConsumer<Agent, RuntimeContext> onRegistered = (actor, context) -> { };
    public ProjectAgentSubagentScopeMiddleware onRegistered(java.util.function.BiConsumer<Agent, RuntimeContext> callback) {
        this.onRegistered = java.util.Objects.requireNonNull(callback); return this;
    }
    private java.util.function.Supplier<HarnessAgent> parentHarness = () -> null;

    @FunctionalInterface
    public interface ChildConsumer {
        Agent bind(Agent officialLeaf, ProjectAgentFoundationTools.Scope parent, RuntimeContext context);
    }

    public ProjectAgentSubagentScopeMiddleware(ProjectAgentFoundationTools.Scope scope,
                                               Runnable requireCurrentAccess, ChildConsumer childConsumer) {
        this(scope, scope.runtimeContext(), requireCurrentAccess, childConsumer);
    }

    public ProjectAgentSubagentScopeMiddleware(ProjectAgentFoundationTools.Scope scope,
                                               RuntimeContext trustedRootContext,
                                               Runnable requireCurrentAccess, ChildConsumer childConsumer) {
        this.scope = java.util.Objects.requireNonNull(scope);
        this.trustedUser = java.util.Objects.requireNonNull(trustedRootContext.getUserId());
        this.trustedSession = java.util.Objects.requireNonNull(trustedRootContext.getSessionId());
        this.lineage = new ProjectAgentChildLineageRegistry(trustedRootContext, requireCurrentAccess);
        this.requireCurrentAccess = java.util.Objects.requireNonNull(requireCurrentAccess);
        this.childConsumer = java.util.Objects.requireNonNull(childConsumer);
        this.contextKey = getClass().getName() + ":" + scope.runId();
    }

    public ProjectAgentSubagentScopeMiddleware bindParent(java.util.function.Supplier<HarnessAgent> parent) {
        this.parentHarness = java.util.Objects.requireNonNull(parent);
        return this;
    }

    public ProjectAgentChildLineageRegistry lineage() {
        return lineage;
    }

    private void bindRootIfAvailable(Agent actor, RuntimeContext context) {
        HarnessAgent parent = parentHarness.get();
        if (parent != null) { lineage.bindRoot(parent); }
        else if (trustedSession.equals(context.getSessionId()) && actor instanceof HarnessAgent direct) {
            lineage.bindRoot(direct);
        }
    }

    @Override
    public int order() {
        return Integer.MIN_VALUE + 1;
    }

    private void authorize(RuntimeContext context) {
        if (context == null || !trustedUser.equals(context.getUserId())) {
            throw new SecurityException("project person scope is unavailable");
        }
        if (context.get(contextKey) != trustToken && !trustedSession.equals(context.getSessionId())) {
            throw new SecurityException("trusted parent run is unavailable");
        }
        requireCurrentAccess.run();
        context.put(contextKey, trustToken);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                   Function<AgentInput, Flux<AgentEvent>> next) {
        bindRootIfAvailable(agent, context);
        authorize(context);
        lineage.registerInvocation(agent, context);
        onRegistered.accept(agent, context);
        Flux<AgentEvent> downstream = next.apply(input).doOnNext(event -> lineage.captureSuspension(agent,context,event));
        String locator = lineage.invocationLocator(agent, context);
        if (locator == null) return downstream;
        return Flux.deferContextual(view -> {
            var forwarding = io.agentscope.core.event.AgentEventEmitter.fromForwardingContext(view);
            if (forwarding.isEmpty()) return downstream; // Direct server resume has no parent forwarding stream.
            io.agentscope.core.event.AgentEventEmitter tagged = event -> {
                lineage.requireKnown(agent, context);
                event.withMetadataEntry(ProjectAgentChildLineageRegistry.INTERNAL_ORIGIN, locator);
                lineage.captureAndStrip(event); // Capture synchronously before parent tool completion.
                forwarding.get().emit(event.withMetadataEntry(ProjectAgentChildLineageRegistry.INTERNAL_ORIGIN, locator));
            };
            return downstream.contextWrite(c -> c.put(io.agentscope.core.event.AgentEventEmitter.FORWARDING_CONTEXT_KEY, tagged));
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                       Function<ReasoningInput, Flux<AgentEvent>> next) {
        bindRootIfAvailable(agent, context);
        authorize(context);
        if (trustedSession.equals(context.getSessionId())) { lineage.registerInvocation(agent, context); }
        HarnessAgent harness = agent instanceof HarnessAgent direct ? direct : parentHarness.get();
        if (harness != null && harness.getAgentId().equals(agent.getAgentId())
            && harness.getSubagentAgentManager() != null) {
            prepareFactories(harness);
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                    Function<ActingInput, Flux<AgentEvent>> next) {
        authorize(context);
        lineage.requireKnown(agent, context);
        return next.apply(input);
    }

    void prepareFactories(HarnessAgent harness) {
        var manager=harness.getSubagentAgentManager();
        if(manager==null) throw new IllegalStateException("Official subagent manager missing");
        var entries=manager.getAgentFactories().entrySet().stream().map(entry->{
            var declaration=manager.getDeclaration(entry.getKey()).orElse(null);
            return new SubagentEntry(entry.getKey(),"Governed official subagent",
                entry.getValue() instanceof GuardedFactory ? entry.getValue():new GuardedFactory(entry.getValue(),lineage.descriptor(entry.getKey(),declaration)),declaration);
        }).toList();
        manager.refreshEntries(entries);
    }
    record RestoredChild(Agent actor, RuntimeContext context) { }
    RestoredChild restoreChild(ProjectAgentChildLineageRegistry.ChildApproval approval,
                               io.agentscope.core.state.AgentStateStore store) {
        if(approval.factory()==null||approval.parentCall()==null) throw new SecurityException("Legacy child receipt has no factory/parent provenance");
        HarnessAgent root=java.util.Objects.requireNonNull(parentHarness.get());
        prepareFactories(root);
        var manager=root.getSubagentAgentManager();
        var expected=lineage.descriptor(approval.factory().name(),manager.getDeclaration(approval.factory().name()).orElse(null));
        if(!expected.equals(approval.factory()))throw new SecurityException("Official child frozen factory/policy changed");
        var live=lineage.liveInvocation(approval);
        if(live!=null) {lineage.requireKnown(live.actor(),live.context());return new RestoredChild(java.util.Objects.requireNonNull(lineage.factoryHandle(live.actor())),live.context());}
        var parent=lineage.restoreParentContext(approval.parentCall(),store);
        var factory=manager.getAgentFactories().get(approval.factory().name());
        if(factory==null)throw new SecurityException("Original official factory missing");
        Agent actual=factory.create(parent);
        var context=RuntimeContext.builder().from(parent).sessionId(approval.sessionId()).build();
        authorize(context);lineage.admitRestored(actual,context,approval);
        // Admission is based on server parent checkpoint + captured factory provenance, not the session string.
        var checkpoint=store.getVersioned(approval.userId(),approval.sessionId(),"agent_state",io.agentscope.core.state.AgentState.class);
        if(!checkpoint.isPresent()||checkpoint.version()!=approval.checkpointVersion())throw new SecurityException("Original child checkpoint changed");
        return new RestoredChild(actual,context);
    }

    private final class GuardedFactory implements SubagentFactory {
        private final SubagentFactory official;

        private final ProjectAgentChildLineageRegistry.FactoryDescriptor descriptor;
        private GuardedFactory(SubagentFactory official, ProjectAgentChildLineageRegistry.FactoryDescriptor descriptor) {
            this.official = official; this.descriptor = descriptor;
        }

        @Override
        public Agent create(RuntimeContext parent) {
            authorize(parent);
            Agent child = official.create(parent);
            lineage.registerFactoryChild(child, parent, descriptor);
            return java.util.Objects.requireNonNull(childConsumer.bind(child, scope, parent));
        }
    }
}
