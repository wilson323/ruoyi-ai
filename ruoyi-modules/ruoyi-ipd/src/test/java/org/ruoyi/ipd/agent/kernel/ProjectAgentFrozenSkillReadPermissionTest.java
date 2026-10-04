package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.*;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.permission.*;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.skill.runtime.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentFrozenSkillReadPermissionTest {
    private final AgentSkill frozen = AgentSkill.builder().name("charter-baseline-ipd")
        .source("actual-sdk-catalog-source").description("审核冻结正文")
        .putMetadata("version", "1.0.0").skillContent("FROZEN_APPROVED_INSTRUCTIONS").build();
    private record Fixture(ToolBase tool, ToolCallParam param, PermissionContextState permissions) { }
    private Fixture fixture(Map<String,Object> input, AgentSkill visible, PermissionContextState permissions) {
        Toolkit toolkit = new Toolkit(); toolkit.registerAgentTool(new SkillLoadTool());
        Agent agent = mock(Agent.class); when(agent.getToolkit()).thenReturn(toolkit);
        RuntimeContext runtime = RuntimeContext.builder().userId("real-person").sessionId("own-run").build();
        runtime.put(SkillCatalog.class, SkillCatalog.of(List.of(HarnessSkillEntry.of(visible, null))));
        ToolUseBlock call = ToolUseBlock.builder().id("frozen-read-call").name(SkillLoadTool.TOOL_NAME)
            .input(input).content("sdk-original-input").state(ToolCallState.ALLOWED).build();
        AgentState state = AgentState.builder().userId("real-person").sessionId("own-run")
            .permissionContext(permissions).addMessage(Msg.builder().role(MsgRole.ASSISTANT).content(List.of(call)).build()).build();
        runtime.setAgentState(state);
        var governance = new ProjectAgentOfficialToolGovernance(mock(ProjectAgentEventSink.class))
            .selectedSkillReads(skill -> Objects.equals(frozen.getName(),skill.getName())
                && Objects.equals(frozen.getSkillContent(),skill.getSkillContent())
                && Objects.equals(frozen.getMetadataValue("version"),skill.getMetadataValue("version"))
                && Objects.equals(frozen.getResources(),skill.getResources()));
        governance.onActing(agent,runtime,new ActingInput(List.of(call)),ignored -> Flux.empty()).blockLast();
        return new Fixture((ToolBase)toolkit.getTool(SkillLoadTool.TOOL_NAME),
            ToolCallParam.builder().agent(agent).runtimeContext(runtime).toolUseBlock(call).input(input).build(),state.getPermissionContext());
    }
    private Map<String,Object> exact() { return Map.of("skillId",frozen.getSkillId(),"path","SKILL.md"); }
    @Test void exactActualSdkIdReadsFrozenInstructionsWithoutDefaultConfirmation() {
        var f=fixture(exact(),frozen,PermissionContextState.builder().build());
        assertEquals(PermissionBehavior.ALLOW,new PermissionEngine(f.permissions()).checkPermission(f.tool(),exact()).block().getBehavior());
        assertTrue(f.tool().callAsync(f.param()).block().getOutput().toString().contains("FROZEN_APPROVED_INSTRUCTIONS"));
        assertThrows(IllegalStateException.class,()->f.tool().callAsync(f.param()).block());
    }
    @Test void explicitDenyAndAskRetainPriorityOverFrozenRead() {
        for (PermissionBehavior behavior: List.of(PermissionBehavior.DENY,PermissionBehavior.ASK)) {
            var rules=PermissionContextState.builder();
            var rule=new PermissionRule(SkillLoadTool.TOOL_NAME,null,behavior,"existing-explicit-user-rule");
            if(behavior==PermissionBehavior.DENY)rules.addDenyRule(SkillLoadTool.TOOL_NAME,rule);
            else rules.addAskRule(SkillLoadTool.TOOL_NAME,rule);
            var f=fixture(exact(),frozen,rules.build());
            assertEquals(behavior,new PermissionEngine(f.permissions()).checkPermission(f.tool(),exact()).block().getBehavior());
        }
    }
    @Test void nameAliasAbsoluteTraversalDraftAndOtherResourceCannotInheritReadGrant() {
        for(Map<String,Object> input:List.of(Map.<String,Object>of("skillId",frozen.getName(),"path","SKILL.md"),
            Map.<String,Object>of("skillId",frozen.getSkillId(),"path","/SKILL.md"),
            Map.<String,Object>of("skillId",frozen.getSkillId(),"path","../SKILL.md"),
            Map.<String,Object>of("skillId",frozen.getSkillId(),"path","skills/_drafts/SKILL.md"),
            Map.<String,Object>of("skillId",frozen.getSkillId(),"path","scripts/run.py"))) {
            var f=fixture(input,frozen,PermissionContextState.builder().build());
            assertEquals(PermissionBehavior.DENY,new PermissionEngine(f.permissions()).checkPermission(f.tool(),input).block().getBehavior());
            assertThrows(SecurityException.class,()->f.tool().callAsync(f.param()).block());
        }
    }
    @Test void sameNameChangedBodyOrVersionIsNotTheApprovedSnapshot() {
        for(AgentSkill changed:List.of(frozen.toBuilder().skillContent("UNREVIEWED_DRAFT").build(),
            frozen.toBuilder().putMetadata("version","2.0.0").build(),frozen.toBuilder().name("other-skill").build())) {
            var input=Map.<String,Object>of("skillId",changed.getSkillId(),"path","SKILL.md");
            var f=fixture(input,changed,PermissionContextState.builder().build());
            assertEquals(PermissionBehavior.DENY,new PermissionEngine(f.permissions()).checkPermission(f.tool(),input).block().getBehavior());
        }
    }
    @Test void parametersCannotBeReplacedAfterBindingEvenWithAnAllowedLedgerRow() {
        var f=fixture(exact(),frozen,PermissionContextState.builder().build());
        var changed=ToolCallParam.builder(f.param()).input(Map.of("skillId",frozen.getSkillId(),"path","../SKILL.md")).build();
        assertThrows(SecurityException.class,()->f.tool().callAsync(changed).block());
        assertTrue(f.tool().callAsync(f.param()).block().getOutput().toString().contains("FROZEN_APPROVED_INSTRUCTIONS"));
    }
}
