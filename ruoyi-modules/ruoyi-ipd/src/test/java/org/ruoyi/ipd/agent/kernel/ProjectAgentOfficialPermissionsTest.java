package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.permission.*;
import io.agentscope.core.tool.ToolBase;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentOfficialPermissionsTest {
    @Test void sandboxCapabilitiesAreAuthorizedAndBusinessApprovalIsNotGranted() {
        var context = ProjectAgentOfficialPermissions.workspace();
        assertEquals(PermissionMode.DEFAULT, context.getMode());
        var engine = new PermissionEngine(context);
        for (String name : new String[]{"execute", "write_file", "agent_spawn", "team", "skill_manage"})
            assertEquals(PermissionBehavior.ALLOW, engine.checkPermission(tool(name), Map.of()).block().getBehavior());
        for (String name : new String[]{"approve_document", "approve_action", "approve_gate"})
            assertEquals(PermissionBehavior.ASK, engine.checkPermission(tool(name), Map.of()).block().getBehavior());
    }

    @Test void inheritedBusinessGuardStillWinsOverOfficialCapabilityGrant() {
        var original = PermissionContextState.builder().addDenyRule("execute",
            new PermissionRule("execute", null, PermissionBehavior.DENY, "run ownership revoked")).build();
        var inherited = ProjectAgentOfficialPermissions.extend(original);
        assertEquals(PermissionBehavior.DENY,
            new PermissionEngine(inherited).checkPermission(tool("execute"), Map.of()).block().getBehavior());
    }

    @Test void planConfirmationRemainsAskAndInheritedDenialWins() {
        assertEquals(PermissionBehavior.ASK, new PermissionEngine(ProjectAgentOfficialPermissions.workspace())
            .checkPermission(tool("plan_exit"), Map.of()).block().getBehavior());
        var revoked = PermissionContextState.builder().addDenyRule("plan_exit",
            new PermissionRule("plan_exit", null, PermissionBehavior.DENY, "revoked")).build();
        assertEquals(PermissionBehavior.DENY, new PermissionEngine(ProjectAgentOfficialPermissions.extend(revoked))
            .checkPermission(tool("plan_exit"), Map.of()).block().getBehavior());
    }

    private static ToolBase tool(String name) {
        return new ToolBase(ToolBase.builder().name(name).description("official capability").inputSchema(Map.of())) { };
    }
}
