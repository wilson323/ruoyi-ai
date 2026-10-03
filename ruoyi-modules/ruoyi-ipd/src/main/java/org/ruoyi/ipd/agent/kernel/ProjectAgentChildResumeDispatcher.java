package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.state.*;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.*;
import java.util.*;

/** Server-only native child resume. The caller must have consumed the durable original-run receipt CAS. */
final class ProjectAgentChildResumeDispatcher {
    record Resume(ProjectAgentChildLineageRegistry.ChildApproval approval,List<Msg> messages) {
        Resume { messages=List.copyOf(messages); }
    }
    private final ProjectAgentSubagentScopeMiddleware scope;
    private final AgentStateStore store;
    private final java.util.function.Consumer<ProjectAgentChildLineageRegistry.ChildApproval> requireDurableConsumed;
    private final java.util.function.Consumer<ProjectAgentChildLineageRegistry.ChildCompletion> recordCompletion;
    private final java.util.function.Supplier<List<ProjectAgentChildLineageRegistry.ChildCompletion>> loadCompletions;
    ProjectAgentChildResumeDispatcher(ProjectAgentSubagentScopeMiddleware scope,AgentStateStore store,java.util.function.Consumer<ProjectAgentChildLineageRegistry.ChildApproval> requireDurableConsumed) {
        this(scope,store,requireDurableConsumed,c->{},List::of);
    }
    ProjectAgentChildResumeDispatcher(ProjectAgentSubagentScopeMiddleware scope,AgentStateStore store,
            java.util.function.Consumer<ProjectAgentChildLineageRegistry.ChildApproval> requireDurableConsumed,
            java.util.function.Consumer<ProjectAgentChildLineageRegistry.ChildCompletion> recordCompletion,
            java.util.function.Supplier<List<ProjectAgentChildLineageRegistry.ChildCompletion>> loadCompletions) {
        this.scope=scope;this.store=store;this.requireDurableConsumed=java.util.Objects.requireNonNull(requireDurableConsumed);
        this.recordCompletion=java.util.Objects.requireNonNull(recordCompletion);
        this.loadCompletions=java.util.Objects.requireNonNull(loadCompletions);
    }
    Flux<AgentEvent> resume(HarnessAgent root,RuntimeContext rootContext,List<Resume> requests,List<Msg> rootMessages) {
        return Flux.defer(()->{
            if(requests.isEmpty())throw new SecurityException("No server child receipts");
            var prepared=new ArrayList<Map.Entry<Resume,ProjectAgentSubagentScopeMiddleware.RestoredChild>>();
            var sessions=new HashSet<String>();
            for(var request:requests) {
                var approval=request.approval();
                requireDurableConsumed.accept(approval);
                if(!sessions.add(approval.sessionId()))throw new SecurityException("Child receipts must be grouped before dispatch");
                var restored=scope.restoreChild(approval,store);
                validate(request);
                prepared.add(Map.entry(request,restored));
            }
            // Claims are retained on dispatch failure; a retry needs an explicit durable recovery transition.
            requests.forEach(request->scope.lineage().claimDispatch(request.approval()));
            var replies=new LinkedHashMap<ProjectAgentChildLineageRegistry.ParentCall,List<String>>();
            var activeParents=requests.stream().map(r->r.approval().parentCall()).collect(java.util.stream.Collectors.toSet());
            for(var completion:loadCompletions.get()) {
                var approval=completion.approval();
                var completedParent=approval.parentCall();
                // Sibling spawn calls have different call IDs/nonces but share the same unfinished parent checkpoint.
                boolean sameParentCheckpoint=completedParent!=null && activeParents.stream().anyMatch(parent->
                    parent.userId().equals(completedParent.userId()) && parent.sessionId().equals(completedParent.sessionId())
                        && parent.checkpointVersion()==completedParent.checkpointVersion());
                if(!sameParentCheckpoint || sessions.contains(approval.sessionId())) continue;
                scope.restoreCompletedChild(completion,store);
                var saved=store.getVersioned(approval.userId(),approval.sessionId(),"agent_state",AgentState.class);
                if(!saved.isPresent() || saved.version()!=completion.completedCheckpointVersion()
                    || !Set.of("MODEL_STOP","STRUCTURED_OUTPUT","ALL_TOOLS_DENIED").contains(completion.generateReason()))
                    throw new SecurityException("Historical child completion checkpoint changed");
                var finalMessage=saved.value().getContext().stream().filter(m->m.getRole()==MsgRole.ASSISTANT)
                    .reduce((a,b)->b).orElseThrow();
                if(!java.util.Objects.equals(finalMessage.getTextContent(),completion.finalText()))
                    throw new SecurityException("Historical child result changed");
                scope.lineage().acknowledgeSaved(approval,store);
                replies.computeIfAbsent(approval.parentCall(),key->new ArrayList<>()).add(completion.finalText());
            }
            var childEvents=Flux.fromIterable(prepared).concatMap(entry->{
                var request=entry.getKey();var child=entry.getValue();var finalReply=new java.util.concurrent.atomic.AtomicReference<Msg>();
                var actualActor=child.actor() instanceof HarnessAgent h ? h : null;
                if(actualActor==null)throw new SecurityException("Original official Harness child lifecycle required");
                return actualActor.streamEvents(request.messages(),child.context())
                    .doOnNext(event->{if(event instanceof AgentResultEvent result)finalReply.set(result.getResult());})
                    .map(event->event.withSource(request.approval().parentCall().sessionId()+"/"+request.approval().factory().name())
                        .withMetadataEntry(ProjectAgentChildLineageRegistry.INTERNAL_ORIGIN,request.approval().locator()))
                    .concatWith(Flux.defer(()->{
                        Msg actual=finalReply.get();
                        if(actual==null)throw new SecurityException("Child did not produce a completed native result");
                        // A non-null SDK reply also represents permission suspension. It is not a parent result.
                        if(actual.getGenerateReason()==GenerateReason.PERMISSION_ASKING
                            || actual.getGenerateReason()==GenerateReason.TOOL_SUSPENDED) return Flux.empty();
                        if(actual.getGenerateReason()!=GenerateReason.MODEL_STOP
                            && actual.getGenerateReason()!=GenerateReason.STRUCTURED_OUTPUT
                            && actual.getGenerateReason()!=GenerateReason.ALL_TOOLS_DENIED)
                            throw new SecurityException("Child did not reach a completed native lifecycle");
                        scope.lineage().acknowledgeSaved(request.approval(),store);
                        var saved=store.getVersioned(request.approval().userId(),request.approval().sessionId(),"agent_state",AgentState.class);
                        requireSavedFinal(saved,request.approval().userId(),request.approval().sessionId(),actual);
                        recordCompletion.accept(new ProjectAgentChildLineageRegistry.ChildCompletion(request.approval(),
                            saved.version(),actual.getGenerateReason().name(),actual.getTextContent()));
                        replies.computeIfAbsent(request.approval().parentCall(),key->new ArrayList<>()).add(actual.getTextContent());
                        return Flux.empty();
                    }));
            });
            return childEvents.concatWith(Flux.defer(()->{
                if(scope.lineage().hasPendingChildApprovals()) return Flux.empty();
                return resumeParents(root,rootContext,replies,rootMessages);

            }));
        });
    }
    private Flux<AgentEvent> resumeParents(HarnessAgent root,RuntimeContext rootContext,
            Map<ProjectAgentChildLineageRegistry.ParentCall,List<String>> replies,List<Msg> rootMessages) {
        return Flux.defer(()->{
            var nested=replies.keySet().stream().filter(p->!p.sessionId().equals(rootContext.getSessionId())).findFirst();
            if(nested.isPresent()) {
                var parent=nested.get();
                var sameSession=replies.keySet().stream().filter(p->p.sessionId().equals(parent.sessionId())).toList();
                var content=new ArrayList<ContentBlock>();
                sameSession.forEach(p->content.add(ToolResultBlock.of(p.call().id(),p.call().name(),TextBlock.builder()
                    .text(ProjectAgentChildLineageRegistry.canonical(replies.remove(p))).build()).withState(ToolResultState.SUCCESS)));
                var restored=scope.restoreParentActor(parent,store);
                if(!(restored.actor() instanceof HarnessAgent actor)) throw new SecurityException("Original nested Harness actor required");
                var result=new java.util.concurrent.atomic.AtomicReference<Msg>();
                return actor.streamEvents(List.of(Msg.builder().role(MsgRole.TOOL).content(content).build()),restored.context())
                    .doOnNext(event->{if(event instanceof AgentResultEvent finalEvent)result.set(finalEvent.getResult());})
                    .map(event->event.withSource(parent.ancestor().sessionId()+"/"+parent.factory().name())
                        .withMetadataEntry(ProjectAgentChildLineageRegistry.INTERNAL_ORIGIN,
                        scope.lineage().invocationLocator(actor,restored.context())))
                    .concatWith(Flux.defer(()->{
                        var actual=result.get();
                        if(actual==null)throw new SecurityException("Nested parent did not produce its native result");
                        if(actual.getGenerateReason()==GenerateReason.PERMISSION_ASKING || actual.getGenerateReason()==GenerateReason.TOOL_SUSPENDED) return Flux.empty();
                        if(!Set.of(GenerateReason.MODEL_STOP,GenerateReason.STRUCTURED_OUTPUT,GenerateReason.ALL_TOOLS_DENIED).contains(actual.getGenerateReason()))
                            throw new SecurityException("Nested parent did not complete");
                        var saved=store.getVersioned(parent.userId(),parent.sessionId(),"agent_state",AgentState.class);
                        if(!saved.isPresent() || saved.version()<=parent.checkpointVersion())throw new SecurityException("Nested completion checkpoint missing");
                        requireSavedFinal(saved,parent.userId(),parent.sessionId(),actual);
                        var parentApproval=new ProjectAgentChildLineageRegistry.ChildApproval(
                            scope.lineage().invocationLocator(actor,restored.context()),parent.userId(),parent.sessionId(),
                            parent.checkpointVersion(),null,List.of(parent.call()),parent.factory(),parent.ancestor());
                        scope.lineage().acknowledgeSaved(parentApproval,store);
                        recordCompletion.accept(new ProjectAgentChildLineageRegistry.ChildCompletion(parentApproval,
                            saved.version(),actual.getGenerateReason().name(),actual.getTextContent()));
                        replies.computeIfAbsent(parent.ancestor(),key->new ArrayList<>()).add(actual.getTextContent());
                        if(scope.lineage().hasPendingChildApprovals())return Flux.empty();
                        return resumeParents(root,rootContext,replies,rootMessages);
                    }));
            }
            var content=new ArrayList<ContentBlock>();
            replies.forEach((parent,actual)->content.add(ToolResultBlock.of(parent.call().id(),parent.call().name(),TextBlock.builder()
                .text(ProjectAgentChildLineageRegistry.canonical(actual)).build()).withState(ToolResultState.SUCCESS)));
            var messages=new ArrayList<Msg>();
            if(!content.isEmpty())messages.add(Msg.builder().role(MsgRole.TOOL).content(content).build());
            if(rootMessages!=null)messages.addAll(rootMessages);
            if(messages.isEmpty())throw new SecurityException("No actual parent results to resume");
            return root.streamEvents(messages,rootContext);
        });
    }
    private static void requireSavedFinal(VersionedState<AgentState> saved,String user,String session,Msg actual) {
        if(!saved.isPresent() || !user.equals(saved.value().getUserId()) || !session.equals(saved.value().getSessionId()))
            throw new SecurityException("Completed native checkpoint identity changed");
        var finalMessage=saved.value().getContext().stream().filter(m->m.getRole()==MsgRole.ASSISTANT)
            .reduce((a,b)->b).orElseThrow(()->new SecurityException("Completed native assistant missing"));
        if(!Objects.equals(finalMessage.getTextContent(),actual.getTextContent()))
            throw new SecurityException("Completed native result differs from its saved checkpoint");
    }
    private void validate(Resume request) {
        var approval=request.approval();
        var saved=store.getVersioned(approval.userId(),approval.sessionId(),"agent_state",AgentState.class);
        if(!saved.isPresent()||saved.version()!=approval.checkpointVersion())throw new SecurityException("Child checkpoint version changed");
        var latest=saved.value().getContext().stream().filter(m->m.getRole()==MsgRole.ASSISTANT).reduce((a,b)->b).orElseThrow();
        var confirmations=new ArrayList<ConfirmResult>();
        for(var msg:request.messages()) {
            Object raw=msg.getMetadata()==null?null:msg.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            if(!(raw instanceof List<?> list))throw new SecurityException("Native original child confirmations required");
            for(var item:list) {if(!(item instanceof ConfirmResult confirm))throw new SecurityException("Invalid native confirmation");confirmations.add(confirm);}
        }
        if(confirmations.size()!=approval.calls().size())throw new SecurityException("Child confirmation cardinality mismatch");
        for(var call:approval.calls()) {
            if(latest.getContentBlocks(ToolUseBlock.class).stream().filter(t->t.getState()==ToolCallState.ASKING && ProjectAgentChildLineageRegistry.CallSnapshot.of(t).equals(call)).count()!=1)
                throw new SecurityException("Original child ASK changed");
            if(saved.value().getContext().stream().flatMap(m->m.getContentBlocks(ToolResultBlock.class).stream()).anyMatch(t->call.id().equals(t.getId())))throw new SecurityException("Child call already completed");
            if(confirmations.stream().filter(c->ProjectAgentChildLineageRegistry.CallSnapshot.of(c.getToolCall()).equals(call)).count()!=1)
                throw new SecurityException("Child exact confirmation changed");
        }
    }
}
