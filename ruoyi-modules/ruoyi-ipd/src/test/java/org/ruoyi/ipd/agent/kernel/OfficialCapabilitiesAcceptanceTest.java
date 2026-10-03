package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Docker effects and deterministic permission isolation; no model/network mock acceptance. */
@Tag("dev")
class OfficialCapabilitiesAcceptanceTest {
    @TempDir Path root;
    private HarnessAgent harness;
    private InMemoryAgentStateStore harnessStore;
    private final java.util.concurrent.atomic.AtomicReference<ToolUseBlock> nextCall = new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicInteger modelResponses = new AtomicInteger();
    private String actualContainerId;
    private boolean realContainerWriteVerified;

    @Test
    void callScopedPermissionCannotBorrowAnotherSessionsAuthorization() {
        ToolBase delegate = mock(ToolBase.class);
        when(delegate.getName()).thenReturn("isolated_write");
        when(delegate.getDescription()).thenReturn("A protected write");
        when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
        when(delegate.checkPermissions(any(), any())).thenReturn(Mono.just(PermissionDecision.passthrough("native")));
        when(delegate.callAsync(any())).thenReturn(Mono.just(ToolResultBlock.text("unexpected write")));
        Agent agent = mock(Agent.class);
        when(agent.getAgentState()).thenReturn(AgentState.builder().permissionContext(
            PermissionContextState.builder().mode(PermissionMode.BYPASS).build()).build());
        RuntimeContext context = RuntimeContext.builder().userId("person-a").sessionId("run-a").build();
        context.setAgentState(AgentState.builder().permissionContext(
            PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build()).build());
        var tool = new ProjectAgentOfficialToolGovernance.OwnedTool(delegate, mock(ProjectAgentEventSink.class));
        assertThrows(RuntimeException.class, () -> tool.callAsync(param("isolated_write", Map.of(), agent, context))
            .block(Duration.ofSeconds(3)));
        verify(delegate, never()).callAsync(any());
    }

    @Test
    void authorizedNativeWriteReadAndShellHaveRealContainerEffectsAndCleanup() throws Exception {
        // Missing daemon/image is a real failed prerequisite, not a skipped green test.
        assertEquals(0, docker("image", "inspect", "python:3.13-alpine", "--format", "{{.Id}}").exit());
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path hostCanary = root.resolve("host-only-canary.txt");
        Files.writeString(hostCanary, "HOST_ONLY_ACCEPTANCE_CANARY");
        var sandboxContext = ProjectAgentOfficialSandbox.filesystem(
            workspace, "python:3.13-alpine", mock(ProjectAgentEventSink.class))
            .toSandboxContext(workspace);
        var client = (DockerSandboxClient) sandboxContext.getClient();
        DockerSandbox sandbox = (DockerSandbox) client.create(sandboxContext.getWorkspaceSpec(),
            sandboxContext.getSnapshotSpec(), (DockerSandboxClientOptions) sandboxContext.getClientOptions());
        String containerId = null;
        try {
            sandbox.start();
            assertTrue(sandbox.isRunning());
            containerId = ((DockerSandboxState) sandbox.getState()).getContainerId();
            assertNotNull(containerId);
            assertEquals(0, docker("inspect", "--format", "{{.State.Running}}", containerId).exit());
            RuntimeContext context = RuntimeContext.builder().userId("acceptance-person").sessionId("acceptance-run").build();
            context.put(SandboxAcquireResult.class, SandboxAcquireResult.userManaged(sandbox));
            var permission = PermissionContextState.builder().mode(PermissionMode.DONT_ASK);
            for (String name : new String[] {"write_file", "read_file", "execute"}) {
                // ToolBase's official matcher uses null for a tool-name-level approval;
                // a literal "*" is deliberately NOT a wildcard and never grants permission.
                permission.addAllowRule(name, new PermissionRule(name, null, PermissionBehavior.ALLOW, "test-approved"));
            }
            AgentState state = AgentState.builder().permissionContext(permission.build()).build();
            context.setAgentState(state);
            Agent agent = mock(Agent.class);
            when(agent.getAgentState()).thenReturn(state);
            SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new FilesystemTool(filesystem));
            toolkit.registerTool(new ShellExecuteTool(filesystem));
            var sink = mock(ProjectAgentEventSink.class);
            // The helper executes through actual Harness onActing and ToolExecutor.
            String write = call(toolkit, sink, workspace, "write_file", Map.of("path", "reports/native.txt", "content", "NATIVE_REAL_CONTENT"), agent, context);
            assertTrue(write.contains("Written to"), write);
            String read = call(toolkit, sink, workspace, "read_file", Map.of("path", "reports/native.txt"), agent, context);
            assertTrue(read.contains("NATIVE_REAL_CONTENT"), read);
            assertTrue(realContainerWriteVerified, "container effect must be checked before official release");
            String shell = call(toolkit, sink, workspace, "execute", Map.of("command", "cat reports/native.txt; printf '\nREAL_SHELL_MARKER\n'"), agent, context);
            assertTrue(shell.contains("Exit code: 0"), shell);
            assertTrue(shell.contains("NATIVE_REAL_CONTENT"), shell);
            assertTrue(shell.contains("REAL_SHELL_MARKER"), shell);
            String hostRead = call(toolkit, sink, workspace, "read_file", Map.of("path", hostCanary.toString()), agent, context);
            assertFalse(hostRead.contains("HOST_ONLY_ACCEPTANCE_CANARY"), hostRead);
            String hostShell = call(toolkit, sink, workspace, "execute", Map.of("command", "test ! -e '" + hostCanary + "' && printf HOST_NOT_MOUNTED"), agent, context);
            assertTrue(hostShell.contains("Exit code: 0"), hostShell);
            assertTrue(hostShell.contains("HOST_NOT_MOUNTED"), hostShell);
            assertEquals("HOST_ONLY_ACCEPTANCE_CANARY", Files.readString(hostCanary));
            verify(sink, atLeast(3)).requireActiveOwnership();
        } finally {
            if (harness != null) harness.close();
            if (actualContainerId != null) assertNotEquals(0, docker("inspect", actualContainerId).exit());
            sandbox.close();
            assertFalse(sandbox.isRunning(), "SDK close must stop the actual sandbox");
            if (containerId != null) {
                assertNotEquals(0, docker("inspect", "--format", "{{.State.Running}}", containerId).exit(),
                    "SDK close must remove its owned container, not merely clear a field");
            }
        }
    }

    private String call(Toolkit toolkit, ProjectAgentEventSink sink, Path workspace, String name,
            Map<String, Object> input, Agent unusedAgent, RuntimeContext context) throws Exception {
        ToolUseBlock use = ToolUseBlock.builder().id("acceptance-" + java.util.UUID.randomUUID()).name(name)
            .content(new ObjectMapper().writeValueAsString(input)).input(input).build();
        nextCall.set(use);
        modelResponses.set(0);
        if (harness == null) {
            var model = mock(Model.class);
            when(model.getModelName()).thenReturn("docker-official-executor-contract");
            when(model.stream(any(), any(), any())).thenAnswer(invocation -> Flux.just(ChatResponse.builder()
                .finishReason(modelResponses.get() == 0 ? "tool_calls" : "stop")
                .content(modelResponses.getAndIncrement() == 0 ? List.of(nextCall.get()) : List.of(TextBlock.builder().text("done").build()))
                .build()));
            harnessStore = new InMemoryAgentStateStore();
            harness = HarnessAgent.builder().name("docker-contract").model(model).toolkit(toolkit)
                .middleware(new ProjectAgentOfficialToolGovernance(sink))
                .middleware(new io.agentscope.core.middleware.MiddlewareBase() {
                    @Override public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onActing(
                            Agent agent, RuntimeContext runtime, io.agentscope.core.middleware.ActingInput acting,
                            java.util.function.Function<io.agentscope.core.middleware.ActingInput, reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>> next) {
                        var acquired = runtime.get(SandboxAcquireResult.class);
                        actualContainerId = ((DockerSandboxState) acquired.getSandbox().getState()).getContainerId();
                        return next.apply(acting).doOnComplete(() -> {
                            if ("write_file".equals(nextCall.get().getName())) {
                                try {
                                    CommandResult effect = docker("exec", actualContainerId, "cat", "/workspace/reports/native.txt");
                                    assertEquals(0, effect.exit(), effect.output());
                                    assertTrue(effect.output().contains("NATIVE_REAL_CONTENT"), effect.output());
                                    realContainerWriteVerified = true;
                                } catch (Exception exception) { throw new IllegalStateException(exception); }
                            }
                        });
                    }
                })
                .permissionContext(context.getAgentState().getPermissionContext())
                .filesystem(ProjectAgentOfficialSandbox.filesystem(workspace, "python:3.13-alpine", sink)).workspace(workspace)
                .stateStore(harnessStore).maxIters(3).build();
        }
        RuntimeContext caller = RuntimeContext.builder().userId(context.getUserId()).sessionId(context.getSessionId()).build();
        {
            harness.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("execute acceptance operation").build()), caller)
                .collectList().block(Duration.ofSeconds(40));
            var results = harnessStore.get(caller.getUserId(), caller.getSessionId(), "agent_state", AgentState.class).orElseThrow().getContext().stream().flatMap(msg -> msg.getContent().stream())
                .filter(block -> block instanceof ToolResultBlock).map(block -> (ToolResultBlock) block)
                .filter(result -> use.getId().equals(result.getId())).toList();
            assertEquals(1, results.size(), "actual SDK ToolExecutor must return exactly one tool result");
            return results.get(0).getOutput().stream()
                .map(block -> block instanceof TextBlock text ? text.getText() : block.toString())
                .collect(Collectors.joining("\n"));
        }
    }

    private static ToolCallParam param(String name, Map<String, Object> input, Agent agent, RuntimeContext context) {
        return ToolCallParam.builder().toolUseBlock(new ToolUseBlock("acceptance-" + name, name, input))
            .input(input).agent(agent).runtimeContext(context).build();
    }

    private record CommandResult(int exit, String output) { }
    private static CommandResult docker(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("docker");
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker verification timed out");
        }
        return new CommandResult(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
