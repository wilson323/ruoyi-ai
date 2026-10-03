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
    ProjectAgentChildResumeDispatcher(ProjectAgentSubagentScopeMiddleware scope,AgentStateStore store,java.util.function.Consumer<ProjectAgentChildLineageRegistry.ChildApproval> requireDurableConsumed) {
        this.scope=scope;this.store=store;this.requireDurableConsumed=java.util.Objects.requireNonNull(requireDurableConsumed);
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
                        scope.lineage().acknowledgeSaved(request.approval(),store);
                        replies.computeIfAbsent(request.approval().parentCall(),key->new ArrayList<>()).add(actual.getTextContent());
                        return Flux.empty();
                    }));
            });
            return childEvents.concatWith(Flux.defer(()->{
                var content=new ArrayList<ContentBlock>();
                replies.forEach((parent,actual)->{
                    if(!parent.sessionId().equals(rootContext.getSessionId()))throw new SecurityException("Nested parent resume requires its original native actor");
                    content.add(ToolResultBlock.of(parent.call().id(),parent.call().name(),TextBlock.builder()
                        .text(ProjectAgentChildLineageRegistry.canonical(actual)).build()).withState(ToolResultState.SUCCESS));
                });
                var messages=new ArrayList<Msg>();
                if(!content.isEmpty())messages.add(Msg.builder().role(MsgRole.TOOL).content(content).build());
                if(rootMessages!=null)messages.addAll(rootMessages);
                if(messages.isEmpty())throw new SecurityException("No actual parent results to resume");
                return root.streamEvents(messages,rootContext);
            }));
        });
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
