package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.*;
import io.agentscope.core.state.*;
import io.agentscope.harness.agent.HarnessAgent;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only original-run receipt validation. Does not create actors, consume receipts or write checkpoints. */
public final class ProjectAgentChildPreflight {
    private ProjectAgentChildPreflight() { }
    /** Shared projection used by both Kernel assembly and pre-consumption guard; credentials never leave the digest. */
    public static String policyHash(ProjectAgentRunSpec spec) {
        var frozen=new LinkedHashMap<String,Object>();
        frozen.put("model",spec.model().modelName());frozen.put("provider",spec.model().providerCode());
        frozen.put("endpointHash",ProjectAgentChildLineageRegistry.hash(spec.model().apiHost()));
        frozen.put("credentialHash",ProjectAgentChildLineageRegistry.hash(spec.model().apiKey()));
        frozen.put("temperature",spec.model().temperature());frozen.put("maxTokens",spec.model().maxTokens());
        frozen.put("modelTimeoutMs",spec.model().timeoutMs());frozen.put("tools",spec.toolIds());frozen.put("executionTools",spec.executionToolIds());
        frozen.put("skills",spec.skills().stream().map(skill->{var row=new LinkedHashMap<String,Object>();row.put("name",skill.name());row.put("version",skill.version());row.put("sha256",skill.sha256());return row;}).toList());
        frozen.put("officialPermissions",ProjectAgentOfficialPermissions.workspace());
        frozen.put("frontendTools",spec.aguiInput()==null?null:spec.aguiInput().getTools());
        return ProjectAgentChildLineageRegistry.hash(frozen);
    }
    /** officialFrozenBuilder must be the server's original factory configuration, including programmatic declarations. */
    public static void preflight(ProjectAgentRunSpec spec,ProjectAgentChildLineageRegistry.ChildApproval approval,
                                 AgentStateStore actualStore,HarnessAgent.Builder officialFrozenBuilder,
                                 Path workspace,Runnable requireOriginalRunOwner) {
        Objects.requireNonNull(requireOriginalRunOwner).run();
        var root=KernelScopeKey.of(String.valueOf(spec.projectId()),String.valueOf(spec.personId()),ProjectAgentConstants.AGENT_ID,String.valueOf(spec.runId()));
        if(approval==null||approval.factory()==null||approval.parentCall()==null||!root.userId().equals(approval.userId())
            ||!root.userId().equals(approval.parentCall().userId())||!root.sessionId().equals(approval.parentCall().sessionId()))
            throw new SecurityException("Original owning run child provenance missing");
        ProjectAgentChildLineageRegistry.requireNativeSuspension(approval.parentCall());
        var entries=officialFrozenBuilder.buildSubagentEntries(workspace).stream().filter(entry->entry.name().equals(approval.factory().name())).toList();
        if(entries.size()!=1)throw new SecurityException("Original official factory catalog missing or ambiguous");
        var expected=ProjectAgentChildLineageRegistry.frozenDescriptor(entries.get(0).name(),entries.get(0).declaration(),policyHash(spec));
        if(!expected.equals(approval.factory()))throw new SecurityException("Frozen official factory/configuration changed");
        var parent=actualStore.getVersioned(root.userId(),root.sessionId(),"agent_state",AgentState.class);
        requireOriginalRunOwner.run();
        if(!parent.isPresent()||parent.version()!=approval.parentCall().checkpointVersion()||!root.userId().equals(parent.value().getUserId())||!root.sessionId().equals(parent.value().getSessionId()))throw new SecurityException("Original parent checkpoint version changed");
        requireCall(parent.value(),approval.parentCall().call(),false);
        String childSlot=root.sessionId()+"/official/"+Base64.getUrlEncoder().withoutPadding().encodeToString(approval.sessionId().getBytes(StandardCharsets.UTF_8));
        var child=actualStore.getVersioned(root.userId(),childSlot,"agent_state",AgentState.class);
        requireOriginalRunOwner.run();
        if(!child.isPresent()||child.version()!=approval.checkpointVersion()||!root.userId().equals(child.value().getUserId())||!approval.sessionId().equals(child.value().getSessionId()))
            throw new SecurityException("Original child checkpoint identity/version changed");
        if(approval.calls().isEmpty())throw new SecurityException("Original child ASK calls missing");
        approval.calls().forEach(call->requireCall(child.value(),call,true));
    }
    private static void requireCall(AgentState state,ProjectAgentChildLineageRegistry.CallSnapshot call,boolean asking) {
        var latest=state.getContext().stream().filter(m->m.getRole()==MsgRole.ASSISTANT).reduce((a,b)->b).orElseThrow(()->new SecurityException("Original active assistant missing"));
        if(latest.getContentBlocks(ToolUseBlock.class).stream().filter(t->ProjectAgentChildLineageRegistry.CallSnapshot.of(t).equals(call)
            && (!asking||t.getState()==ToolCallState.ASKING)).count()!=1
            ||state.getContext().stream().flatMap(m->m.getContentBlocks(ToolResultBlock.class).stream()).anyMatch(t->call.id().equals(t.getId())))
            throw new SecurityException("Original native call is no longer unfinished");
    }
}
