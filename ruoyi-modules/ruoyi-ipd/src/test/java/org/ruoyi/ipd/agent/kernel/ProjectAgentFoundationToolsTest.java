package org.ruoyi.ipd.agent.kernel;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.tool.MemoryGetTool;
import io.agentscope.harness.agent.tool.MemorySaveTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

class ProjectAgentFoundationToolsTest {

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println(label + "=PASS");
    }

    @Test
    void officialNativeFileShellMemoryAndWebFixture() throws Exception {
        DockerSandboxClientOptions options = new DockerSandboxClientOptions()
            .image("alpine:3").workspaceRoot("/workspace").network("none")
            .additionalRunArgs("--cap-drop=ALL", "--security-opt=no-new-privileges");
        Sandbox sandbox = new DockerSandboxClient().create(
            new WorkspaceSpec(), new NoopSnapshotSpec(), options);
        try {
            sandbox.start();
            var file = ProjectAgentFoundationTools.filesystemTool(sandbox);
            require(!file.writeFile(null, "/workspace/probe.txt", "isolated-content")
                .startsWith("Error"), "FILE_WRITE");
            require(file.readFile(null, "/workspace/probe.txt", 0, 0)
                .contains("isolated-content"), "FILE_READ");
            var shell = ProjectAgentFoundationTools.shellTool(sandbox);
            require(shell.execute(null, "printf isolated-shell", null, 5)
                .contains("isolated-shell"), "NATIVE_SHELL");
            require(shell.execute(null, "printf no", "../outside", 5)
                .startsWith("Error"), "SHELL_WORKDIR_BOUNDARY");
            require(shell.execute(null,
                "test ! -e /Users/mac/Documents/ruoyi-ai && printf host-inaccessible", null, 5)
                .contains("host-inaccessible"), "HOST_REPO_NOT_MOUNTED");
            Path workspace = Files.createTempDirectory("foundation-memory-");
            WorkspaceManager manager = new WorkspaceManager(
                workspace, new PinnedSandboxFilesystem(sandbox));
            require(new MemorySaveTool(manager)
                .memorySave(null, "- synthetic isolated preference").startsWith("Saved"), "MEMORY_WRITE");
            require(new MemoryGetTool(manager).memoryGet(null, "MEMORY.md", 1, 100)
                .contains("synthetic isolated preference"), "MEMORY_READ");
        } finally {
            sandbox.shutdown();
            System.out.println("OWN_CONTAINER_CLEANUP=RETURNED");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture", exchange -> {
            byte[] body = "fixture-web-content".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            require(ProjectAgentFoundationTools.webFetchTool()
                .webFetch("http://127.0.0.1:" + server.getAddress().getPort() + "/fixture", 100)
                .contains("fixture-web-content"), "WEB_FETCH_FIXTURE");
            // Runtime SDK 2.0.3 returns an explicit tool error before credentials or network access.
            var search = ProjectAgentFoundationTools.webSearchTool();
            for (String invalidQuery : new String[]{null, "", "   "}) {
                require("Error: query is required".equals(search.webSearch(invalidQuery, 1)), "WEB_SEARCH_SCHEMA");
            }
            System.out.println("TAVILY_KEY_PRESENT="
                + !Objects.toString(System.getenv("TAVILY_API_KEY"), "").isBlank());
        } finally {
            server.stop(0);
        }
    }
    @Test
    void freshBuilderKeepsAllOfficialDefaultConsumers() throws Exception {
        io.agentscope.core.model.Model noNetworkModel = new io.agentscope.core.model.Model() {
            public String getModelName() { return "isolated-no-network-model"; }
            public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
                java.util.List<io.agentscope.core.message.Msg> messages,
                java.util.List<io.agentscope.core.model.ToolSchema> tools,
                io.agentscope.core.model.GenerateOptions options) {
                throw new AssertionError("model must not be called in construction test");
            }
        };
        Path workspace = Files.createTempDirectory("foundation-full-defaults-");
        var scope = new ProjectAgentFoundationTools.Scope("1", "1", "999999999", workspace);
        var agent = ProjectAgentFoundationTools.configureFullCapabilities(
            io.agentscope.harness.agent.HarnessAgent.builder().name("full-defaults-test").workspace(workspace).model(noNetworkModel),
            scope, "alpine:3", new io.agentscope.core.state.InMemoryAgentStateStore(),
            new io.agentscope.harness.agent.transcript.FilesystemTranscriptStore(workspace.resolve("transcripts")),
            io.agentscope.harness.agent.memory.MemoryConfig.defaults(),
            (candidate, context) -> reactor.core.publisher.Mono.just(
                new io.agentscope.harness.agent.skill.curator.SkillPromotionGate.PromotionDecision.Defer(
                    java.time.Duration.ofMinutes(1), "fixture owner approval pending")),
            (skills, context) -> skills,
            (context, request) -> io.agentscope.harness.agent.artifact.ArtifactDeliveryResult.success("fixture only"))
            .build();
        try {
            var names = agent.getToolkit().getToolNames();
            require(names.containsAll(ProjectAgentFoundationTools.nativeToolIds()), "OFFICIAL31_ENABLED");
            require(names.containsAll(java.util.Set.of("agent_list", "agent_send", "agent_spawn",
                "task_cancel", "task_list", "task_output", "load_skill_through_path")), "OTHER_DEFAULT_CONSUMERS");
            require(scope.runtimeContext().getSessionId().equals("999999999"), "RUN_CONTEXT");
            require(scope.runWorkingDirectory().equals("runs/999999999"), "RUN_DIRECTORY_IDENTITY");
        } finally {
            agent.close();
        }
    }
    @Test
    void persistentCopyRemovesThinkingWithoutChangingLiveStateOrToolIdentity() {
        var thought = io.agentscope.core.message.ThinkingBlock.builder().thinking("private-fixture-needle").build();
        var text = io.agentscope.core.message.TextBlock.builder().text("delivered-fixture-body").build();
        var tool = io.agentscope.core.message.ToolUseBlock.builder().id("call-fixture").name("read_file")
            .input(java.util.Map.of("path", "draft.md")).content("{\"path\":\"draft.md\"}").build();
        var message = io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT)
            .content(java.util.List.of(thought, text, tool)).build();
        var live = io.agentscope.core.state.AgentState.builder().userId("project-person").sessionId("run")
            .summary("summary-fixture").curIter(2).context(java.util.List.of(message)).build();
        var delegate = new io.agentscope.core.state.InMemoryAgentStateStore();
        var safe = ProjectAgentDeliveryStateMiddleware.persistentStore(delegate);
        safe.save("project-person", "run", "agent", live);
        var persisted = safe.get("project-person", "run", "agent", io.agentscope.core.state.AgentState.class).orElseThrow();
        require(live.toJson().contains("private-fixture-needle"), "LIVE_STATE_UNCHANGED");
        require(!persisted.toJson().contains("private-fixture-needle"), "PERSISTED_THINKING_REMOVED");
        require(persisted.toJson().contains("delivered-fixture-body")
            && persisted.toJson().contains("call-fixture") && persisted.getCurIter() == 2,
            "DELIVERY_TOOLS_AND_STATE_PRESERVED");
        require(safe.supportsVersioning() == delegate.supportsVersioning(), "VERSION_SUPPORT_UNCHANGED");
    }
}
