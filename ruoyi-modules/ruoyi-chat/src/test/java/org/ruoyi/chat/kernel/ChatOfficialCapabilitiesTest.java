package org.ruoyi.chat.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolUseBlock;
import org.ruoyi.mcp.tools.WriteFileTool;
import org.ruoyi.mcp.tools.ReadFileTool;
import org.ruoyi.service.coding.CodingEventChannel;
import org.ruoyi.service.coding.CodingSseEvent;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ChatOfficialCapabilitiesTest {
    @TempDir Path root;

    @Test void builderRegistersOfficialCapabilitiesWithoutEmptyToolkitOrHostShellFallback() {
        var builder = HarnessAgent.builder().name("chat-fixture").workspace(root)
            .model(mock(Model.class)).stateStore(new InMemoryAgentStateStore());
        var profile = ChatOfficialCapabilities.configure(builder, root, "pP:uU", "python:3.13-alpine");
        try (var agent = builder.build()) {
            profile.bind(agent);
            assertThat(agent.getToolkit().getToolNames()).contains("read_file", "write_file", "execute",
                "memory_search", "memory_get", "memory_save", "session_search", "web_fetch", "web_search",
                "plan_enter", "plan_write", "plan_exit", "skill_manage");
            assertThat(agent.getCompactionHook()).isNotNull();
        }
    }

    @Test void foreignContextAndUnapprovedWorkspaceSkillCannotBecomeOwnerEvidence() {
        var profile = ChatOfficialCapabilities.configure(HarnessAgent.builder(), root, "pP:uU", "python:3.13-alpine");
        var skill = AgentSkill.builder().name("unapproved").description("draft").skillContent("draft").build();
        assertThat(profile.filter(List.of(skill), RuntimeContext.empty())).isEmpty();
        assertThat(profile.filter(List.of(skill), RuntimeContext.builder().userId("pP:uU").sessionId("aA:sS").build()))
            .isEmpty();
    }

    @Test void requestedCredentialIsOnlyAnInMemoryRedactionValueAndNeverInPlanText() {
        var selector = new KernelModelSelector("stub:model", (key, context) -> mock(Model.class));
        var plan = selector.plan(new KernelModelRequest("selected", "openai", "KNOWN_SECRET", null));
        assertThat(plan.knownSecrets()).containsExactly("KNOWN_SECRET");
        assertThat(plan.toString()).doesNotContain("KNOWN_SECRET");
    }
    @Test void codingBusinessToolsKeepRealHostDeliveryAndCannotEscapeTheApprovedWorkspace() throws Exception {
        Path businessRoot = Files.createDirectory(root.resolve("business"));
        Path runtimeRoot = Files.createDirectory(root.resolve("official-runtime"));
        var channel = new CodingEventChannel();
        var toolkit = new Toolkit();
        toolkit.registerTool(new WriteFileTool(businessRoot, channel));
        toolkit.registerTool(new ReadFileTool(businessRoot, channel));
        var builder = HarnessAgent.builder().name("coding").model(mock(Model.class)).toolkit(toolkit)
            .stateStore(new InMemoryAgentStateStore()).workspace(runtimeRoot);
        var profile = ChatOfficialCapabilities.configure(builder, runtimeRoot, "900103", "python:3.13-alpine");
        try (var agent = builder.build()) {
            profile.bind(agent);
            assertThat(agent.getToolkit().getToolNames()).contains("writeFile", "readFile", "write_file", "execute", "skill_manage", "plan_enter");
            var write = (ToolBase) agent.getToolkit().getTool("writeFile");
            Path destination = businessRoot.resolve("reviewable.txt");
            var input = Map.<String, Object>of("filePath", destination.toString(), "content", "REAL_BUSINESS_OUTPUT");
            var denied = PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build();
            assertThat(new PermissionEngine(denied).checkPermission(write, input).block().getBehavior())
                .isEqualTo(PermissionBehavior.DENY);
            assertThat(destination).doesNotExist();
            // Explicit test approval exercises the official permission extension; it is not an owner API acceptance claim.
            var approved = PermissionContextState.builder().mode(PermissionMode.DONT_ASK)
                .addAllowRule("writeFile", new PermissionRule("writeFile", null, PermissionBehavior.ALLOW, "test-approved"))
                .build();
            assertThat(new PermissionEngine(approved).checkPermission(write, input).block().getBehavior())
                .isEqualTo(PermissionBehavior.ALLOW);
            var context = RuntimeContext.builder().userId("900103").sessionId("coding-contract").build();
            context.setAgentState(AgentState.builder().permissionContext(approved).build());
            write.callAsync(ToolCallParam.builder().toolUseBlock(new ToolUseBlock("business-write", "writeFile", input))
                .input(input).agent(agent.getDelegate()).runtimeContext(context).build()).block(Duration.ofSeconds(5));
            assertThat(Files.readString(destination)).isEqualTo("REAL_BUSINESS_OUTPUT");
            assertThat(runtimeRoot.resolve("reviewable.txt")).doesNotExist();
            Path outside = root.resolve("outside.txt");
            var escape = Map.<String, Object>of("filePath", outside.toString(), "content", "MUST_NOT_WRITE");
            write.callAsync(ToolCallParam.builder().toolUseBlock(new ToolUseBlock("escape", "writeFile", escape))
                .input(escape).agent(agent.getDelegate()).runtimeContext(context).build()).block(Duration.ofSeconds(5));
            assertThat(outside).doesNotExist();
            channel.complete();
            var events = new ArrayList<CodingSseEvent>();
            channel.drain(events::add);
            assertThat(events).extracting(CodingSseEvent::eventType).contains("add-start", "add-end");
        }
    }

}
