package org.ruoyi.ipd.agent.catalog;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.ipd.agent.kernel.ProjectAgentFoundationTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class ProjectAgentNativeToolReadinessTest {
    @TempDir Path workspace;

    @org.junit.jupiter.api.BeforeEach void canonicalTemporaryWorkspace() throws Exception {
        workspace = workspace.toRealPath();
    }

    private ProjectAgentNativeToolReadiness readiness(Set<String> names, boolean state,
            boolean artifacts, boolean collaboration, String key,
            ProjectAgentNativeToolReadiness.CommandProbe probe) {
        return new ProjectAgentNativeToolReadiness(workspace, "python:3.13-alpine", () -> names,
            () -> state, () -> artifacts, () -> collaboration, () -> key, probe);
    }

    @Test void registeredProviderAndActualEnvironmentAreSeparateChecks() {
        var ready = readiness(Set.copyOf(ProjectAgentNativeToolCatalog.IDS), true, true, true, null, command -> true);
        assertThat(ready.status("read_file").available()).isTrue();
        assertThat(ready.status("web_fetch").available()).isTrue();
        assertThat(ready.status("web_search").reason()).isEqualTo("网页查询服务尚未配置");
        assertThat(readiness(Set.of(), true, true, true, "fixture", command -> true)
            .status("read_file").reason()).isEqualTo("工具未纳入官方装配清单");
        assertThat(ready.status("unknown").available()).isFalse();
    }

    @Test void missingConfiguredConsumersNeverBecomeReady() {
        Set<String> names = Set.copyOf(ProjectAgentNativeToolCatalog.IDS);
        assertThat(readiness(names, false, true, true, null, command -> true)
            .status("web_fetch").reason()).isEqualTo("运行状态存储不可用");
        var missing = readiness(names, true, false, false, null, command -> true);
        assertThat(missing.status("deliver_artifact").reason()).isEqualTo("产物交付服务尚未接入");
        for (String name : List.of("agent_spawn", "agent_send", "wait_async_results", "task_output")) {
            assertThat(missing.status(name).reason()).isEqualTo("智能体协作服务不可用");
        }
    }

    @Test void dockerFailureBlocksHostWebToolsBecauseProductionRunNeedsSandboxFirst() {
        var missing = readiness(Set.copyOf(ProjectAgentNativeToolCatalog.IDS), true, true, true, "fixture", command -> false);
        for (String name : List.of("execute", "read_file", "memory_save", "plan_write", "skill_manage")) {
            assertThat(missing.status(name).reason()).isEqualTo("Docker 沙箱服务不可用");
        }
        assertThat(missing.status("web_fetch").reason()).isEqualTo("Docker 沙箱服务不可用");
        assertThat(missing.status("web_search").reason()).isEqualTo("Docker 沙箱服务不可用");
    }

    @Test void workspaceFileAndSymbolicAncestorsAreRejectedWithoutProbeSideEffects() throws Exception {
        var file = java.nio.file.Files.writeString(workspace.resolve("file"), "fixture");
        var alias = java.nio.file.Files.createSymbolicLink(workspace.resolve("alias"), workspace);
        for (Path path : List.of(file, file.resolve("nested"), alias.resolve("nested"))) {
            var invalid = new ProjectAgentNativeToolReadiness(path, "python:3.13-alpine",
                () -> Set.copyOf(ProjectAgentNativeToolCatalog.IDS), () -> true, () -> true, () -> true,
                () -> "fixture", command -> { throw new AssertionError("Invalid workspace must not probe Docker"); });
            assertThat(invalid.status("web_fetch").reason()).isEqualTo("运行工作区不可写");
        }
    }

    @Test void unwritableWorkspaceIsRejectedBeforeDockerProbe() throws Exception {
        var restricted = java.nio.file.Files.createDirectory(workspace.resolve("restricted"));
        var original = java.nio.file.Files.getPosixFilePermissions(restricted);
        try {
            java.nio.file.Files.setPosixFilePermissions(restricted, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
            assertThat(java.nio.file.Files.isWritable(restricted)).isFalse();
            var invalid = new ProjectAgentNativeToolReadiness(restricted, "python:3.13-alpine",
                () -> Set.copyOf(ProjectAgentNativeToolCatalog.IDS), () -> true, () -> true, () -> true,
                () -> "fixture", command -> { throw new AssertionError("Unwritable workspace must not probe Docker"); });
            assertThat(invalid.status("web_fetch").reason()).isEqualTo("运行工作区不可写");
        } finally { java.nio.file.Files.setPosixFilePermissions(restricted, original); }
    }

    @Test void imageProbeIsReadOnlyAndSharedAcrossCatalogRows() {
        var calls = new AtomicInteger();
        var missing = readiness(Set.copyOf(ProjectAgentNativeToolCatalog.IDS), true, true, true, null, command -> {
            calls.incrementAndGet();
            assertThat(command).doesNotContain("run", "pull", "create");
            return command.contains("info");
        });
        assertThat(missing.status("execute").reason()).isEqualTo("运行沙箱镜像尚未就绪");
        assertThat(missing.status("memory_get").available()).isFalse();
        assertThat(calls).hasValue(2);
    }

    @Test void configuredNamesExistInActualOfficialFullProfileToolkit() throws Exception {
        io.agentscope.core.model.Model model = new io.agentscope.core.model.Model() {
            public String getModelName() { return "readiness-no-network"; }
            public reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> stream(
                    List<io.agentscope.core.message.Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                    io.agentscope.core.model.GenerateOptions options) {
                throw new AssertionError("Construction must not call a model");
            }
        };
        var scope = new ProjectAgentFoundationTools.Scope("1", "1", "999999999", workspace);
        try (var agent = ProjectAgentFoundationTools.configureFullCapabilities(
                io.agentscope.harness.agent.HarnessAgent.builder().name("readiness-profile").workspace(workspace).model(model),
                scope, "python:3.13-alpine", new io.agentscope.core.state.InMemoryAgentStateStore(),
                new io.agentscope.harness.agent.transcript.FilesystemTranscriptStore(workspace.resolve("transcripts")),
                io.agentscope.harness.agent.memory.MemoryConfig.defaults(),
                (candidate, context) -> reactor.core.publisher.Mono.just(
                    new io.agentscope.harness.agent.skill.curator.SkillPromotionGate.PromotionDecision.Defer(
                        java.time.Duration.ofMinutes(1), "Owner approval pending")),
                (skills, context) -> skills,
                (context, request) -> io.agentscope.harness.agent.artifact.ArtifactDeliveryResult.success("fixture"))
                .build()) {
            assertThat(agent.getToolkit().getToolNames()).containsAll(ProjectAgentNativeToolCatalog.IDS);
            var observed = readiness(Set.copyOf(agent.getToolkit().getToolNames()), true, true, true, null, command -> true);
            assertThat(observed.status("read_file").available()).isTrue();
        }
    }
}
