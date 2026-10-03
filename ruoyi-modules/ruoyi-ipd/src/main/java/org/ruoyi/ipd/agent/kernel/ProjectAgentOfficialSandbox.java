package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.file.Path;
import java.util.Objects;

/** 官方 Docker provider；主机工作区投影与快照由 SDK 执行，不用主机 shell 假装沙箱。 */
public final class ProjectAgentOfficialSandbox {
    private ProjectAgentOfficialSandbox() { }

    /** 镜像由运行配置提供；--pull=never 保证缺镜像时明确失败，不自动下载或回退主机执行。 */
    public static SandboxFilesystemSpec filesystem(Path workspace, String image, ProjectAgentEventSink sink) {
        return managedFilesystem(workspace, image, sink).spec();
    }

    public record ManagedFilesystem(SandboxFilesystemSpec spec, ProjectAgentVerifiedSnapshotSpec snapshots) {
        public java.util.List<ProjectAgentVerifiedSnapshotSpec.Receipt> verifyReleased() {
            return snapshots.verifyReleased();
        }
    }

    public static ManagedFilesystem managedFilesystem(Path workspace, String image, ProjectAgentEventSink sink) {
        Objects.requireNonNull(sink, "Run ownership sink is required");
        Objects.requireNonNull(workspace, "Run workspace is required");
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("Official sandbox image is required");
        }
        Path runWorkspace = workspace.toAbsolutePath().normalize();
        var snapshots = new ProjectAgentVerifiedSnapshotSpec(runWorkspace.resolve(".sandbox-snapshots"));
        DockerFilesystemSpec spec = new DockerFilesystemSpec()
            .client(new DockerSandboxClient() {
                @Override public SandboxState deserializeState(String json, SandboxSnapshotSpec snapshotSpec) {
                    SandboxState state = super.deserializeState(json, snapshotSpec);
                    // SDK203 only rebinds remote snapshots. Rebind this observer after cold resume too.
                    if (state.getSnapshot() != null) state.setSnapshot(snapshotSpec.build(state.getSnapshot().getId()));
                    return state;
                }
            })
            .image(image)
            .workspaceRoot("/workspace")
            .network("none")
            .snapshotSpec(snapshots)
            .additionalRunArgs("--pull=never", "--cap-drop=ALL", "--security-opt=no-new-privileges");
        spec.isolationScope(IsolationScope.SESSION);
        spec.executionGuard(key -> {
            sink.requireActiveOwnership();
            return () -> { };
        });
        return new ManagedFilesystem(spec, snapshots);
    }
}
