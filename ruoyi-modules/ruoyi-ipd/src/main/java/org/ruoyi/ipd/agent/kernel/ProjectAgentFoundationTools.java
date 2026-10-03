package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import java.nio.file.Path;
import java.util.Objects;
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import io.agentscope.harness.agent.tool.WebTools;
import java.util.Map;
import java.util.Set;

/** 官方 2.0.3 原生基础工具；容器由 Harness 生命周期持有，不回落宿主 Shell。 */
public final class ProjectAgentFoundationTools {
    private ProjectAgentFoundationTools() { }
    public static Set<String> nativeToolIds() {
        return Set.of("read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files",
            "execute", "web_fetch", "web_search", "memory_get", "memory_search", "memory_save",
            "session_search", "session_list", "session_history", "wait_async_results",
            "agent_list", "agent_send", "agent_spawn", "task_cancel", "task_list", "task_output",
            "load_skill_through_path", "plan_enter", "plan_write", "plan_exit", "skill_manage",
            "propose_skill", "todo_write", "reset_equipped_tools", "deliver_artifact");
    }
    public static DockerFilesystemSpec dockerFilesystem(String image) {
        return dockerFilesystem(image, null);
    }
    public static DockerFilesystemSpec dockerFilesystem(String image, String workspaceVolume) {
        if (image == null || image.isBlank()) throw new IllegalArgumentException("sandbox image is required");
        if (workspaceVolume != null && !workspaceVolume.matches("ipd-agent-[a-z0-9-]{1,100}"))
            throw new IllegalArgumentException("managed workspace volume name is invalid");
        DockerFilesystemSpec spec = new DockerFilesystemSpec().image(image).workspaceRoot("/workspace")
            .environment(Map.of()).network("none")
            .additionalRunArgs(workspaceVolume == null
                ? new String[]{"--cap-drop=ALL", "--security-opt=no-new-privileges"}
                : new String[]{"--cap-drop=ALL", "--security-opt=no-new-privileges", "--mount",
                    "type=volume,source=" + workspaceVolume + ",target=/workspace"});
        return spec;
    }
    public record Scope(String projectId, String personId, String runId, Path managedWorkspace) {
        public Scope {
            for (String id : new String[]{projectId, personId, runId}) {
                if (id == null || !id.matches("[0-9]+"))
                    throw new IllegalArgumentException("trusted scope identifiers are required");
            }
            Objects.requireNonNull(managedWorkspace, "managedWorkspace");
        }
        public String volumeName() { return "ipd-agent-" + projectId + "-" + personId; }
        public String runWorkingDirectory() { return "runs/" + runId; }
        public RuntimeContext runtimeContext() {
            return RuntimeContext.builder().userId(projectId + "-" + personId)
                .sessionId(runId).build();
        }
    }

    /** 新 Builder 保留官方 hooks/transcript/context/skills/subagents；调用方供应持久且已清洗的存储。 */
    public static HarnessAgent.Builder configure(HarnessAgent.Builder builder, Scope scope,
                                                 String image, AgentStateStore stateStore,
                                                 TranscriptStore transcriptStore, MemoryConfig memory) {
        return configure(builder, scope, scope.runtimeContext(), image, stateStore, transcriptStore, memory);
    }

    /** 生产调用必须传入既有 KernelScopeKey 产出的可信 RuntimeContext；不另拼隔离键。 */
    public static HarnessAgent.Builder configure(HarnessAgent.Builder builder, Scope scope,
                                                 RuntimeContext trustedRootContext, String image,
                                                 AgentStateStore stateStore, TranscriptStore transcriptStore,
                                                 MemoryConfig memory) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(trustedRootContext.getUserId(), "trusted user identity");
        Objects.requireNonNull(trustedRootContext.getSessionId(), "trusted session identity");
        return configureShared(builder.filesystem(
            dockerFilesystem(image, scope.volumeName()).isolationScope(IsolationScope.USER)),
            scope, trustedRootContext, stateStore, transcriptStore, memory);
    }

    private static HarnessAgent.Builder configureShared(HarnessAgent.Builder builder, Scope scope,
        RuntimeContext trustedRootContext, AgentStateStore stateStore, TranscriptStore transcriptStore,
        MemoryConfig memory) {
        return builder.workspace(scope.managedWorkspace()).defaultSessionId(trustedRootContext.getSessionId())
            .stateStore(ProjectAgentDeliveryStateMiddleware.persistentStore(
                Objects.requireNonNull(stateStore, "stateStore")))
            .middleware(new ProjectAgentDeliveryStateMiddleware())
            .transcriptStore(Objects.requireNonNull(transcriptStore, "transcriptStore"))
            .transcriptTenant(scope.projectId() + "-" + scope.personId())
            .memory(Objects.requireNonNull(memory, "memory"));
    }

    /** 官方可选消费者也启用；审批、技能可见性与产物目的地由业务 SPI 提供。 */
    public static HarnessAgent.Builder configureFullCapabilities(
        HarnessAgent.Builder builder, Scope scope, String image, AgentStateStore stateStore,
        TranscriptStore transcriptStore, MemoryConfig memory, SkillPromotionGate skillApproval,
        SkillVisibilityFilter skillVisibility, ArtifactDeliveryTarget artifactDelivery) {
        return configureFullCapabilities(builder, scope, scope.runtimeContext(), image, stateStore,
            transcriptStore, memory, skillApproval, skillVisibility, artifactDelivery);
    }

    public static HarnessAgent.Builder configureFullCapabilities(
        HarnessAgent.Builder builder, Scope scope, RuntimeContext trustedRootContext, String image,
        AgentStateStore stateStore, TranscriptStore transcriptStore, MemoryConfig memory,
        SkillPromotionGate skillApproval, SkillVisibilityFilter skillVisibility,
        ArtifactDeliveryTarget artifactDelivery) {
        return enableFullConsumers(configure(builder, scope, trustedRootContext, image, stateStore,
            transcriptStore, memory), skillApproval, skillVisibility, artifactDelivery);
    }

    /** 官方固定 sandbox 引用；调用方负责后台任务排空后关闭持有的 sandbox。 */
    public static HarnessAgent.Builder configurePinnedFullCapabilities(
        HarnessAgent.Builder builder, Scope scope, RuntimeContext trustedRootContext,
        io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem filesystem,
        AgentStateStore stateStore, TranscriptStore transcriptStore,
        MemoryConfig memory, SkillPromotionGate skillApproval, SkillVisibilityFilter skillVisibility,
        ArtifactDeliveryTarget artifactDelivery) {
        return enableFullConsumers(configureShared(builder.abstractFilesystem(filesystem), scope,
            trustedRootContext, stateStore, transcriptStore, memory), skillApproval, skillVisibility,
            artifactDelivery);
    }

    private static HarnessAgent.Builder enableFullConsumers(HarnessAgent.Builder builder,
        SkillPromotionGate skillApproval, SkillVisibilityFilter skillVisibility,
        ArtifactDeliveryTarget artifactDelivery) {
        return builder.enablePlanMode().enableTaskList().enableMetaTool(true).enablePendingToolRecovery(true)
            .enableSkillManageTool(true)
            .enableSkillPromotionGate(Objects.requireNonNull(skillApproval, "skillApproval"),
                Objects.requireNonNull(skillVisibility, "skillVisibility"))
            .enableSkillCurator(SkillCuratorConfig.defaults())
            .artifactDeliveryTarget(Objects.requireNonNull(artifactDelivery, "artifactDelivery"));
    }

    public static FilesystemTool filesystemTool(Sandbox sandbox) {
        return new FilesystemTool(new PinnedSandboxFilesystem(sandbox));
    }
    public static ShellExecuteTool shellTool(Sandbox sandbox) {
        return new ShellExecuteTool(new PinnedSandboxFilesystem(sandbox));
    }
    public static WebTools.WebFetchTool webFetchTool() { return new WebTools.WebFetchTool(); }
    public static WebTools.WebSearchTool webSearchTool() { return new WebTools.WebSearchTool(); }
}
