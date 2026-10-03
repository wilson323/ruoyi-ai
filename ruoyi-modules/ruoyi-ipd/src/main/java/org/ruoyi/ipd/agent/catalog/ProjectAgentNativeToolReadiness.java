package org.ruoyi.ipd.agent.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Provider prerequisites only. The supplied names declare assembly configuration, not a live Toolkit inventory. */
public final class ProjectAgentNativeToolReadiness implements ProjectAgentNativeToolCatalog.Provider {
    @FunctionalInterface interface CommandProbe { boolean succeeds(List<String> command); }
    private final Path workspace;
    private final String image;
    private final Supplier<Set<String>> configuredTools;
    private final BooleanSupplier state, artifacts, collaboration;
    private final Supplier<String> searchKey;
    private final CommandProbe probe;
    private long checkedAt;
    private ProjectAgentNativeToolCatalog.Readiness sandbox;

    public ProjectAgentNativeToolReadiness(Path workspace, String image, Supplier<Set<String>> configuredTools,
            BooleanSupplier state, BooleanSupplier artifacts, BooleanSupplier collaboration) {
        this(workspace, image, configuredTools, state, artifacts, collaboration,
            () -> System.getenv("TAVILY_API_KEY"), ProjectAgentNativeToolReadiness::commandSucceeds);
    }

    ProjectAgentNativeToolReadiness(Path workspace, String image, Supplier<Set<String>> configuredTools,
            BooleanSupplier state, BooleanSupplier artifacts, BooleanSupplier collaboration,
            Supplier<String> searchKey, CommandProbe probe) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.image = image;
        this.configuredTools = configuredTools;
        this.state = state; this.artifacts = artifacts; this.collaboration = collaboration;
        this.searchKey = searchKey; this.probe = probe;
    }

    @Override public ProjectAgentNativeToolCatalog.Readiness status(String id) {
        if (!ProjectAgentNativeToolCatalog.IDS.contains(id)) return unavailable("工具不在官方能力目录中");
        Set<String> names = configuredTools.get();
        if (names == null || !names.contains(id)) return unavailable("工具未纳入官方装配清单");
        if (!state.getAsBoolean()) return unavailable("运行状态存储不可用");
        // Every production run assembles the sandbox before any tool can be called, including host WebTools.
        var common = sandboxReadiness();
        if (!common.available()) return common;
        if ("web_search".equals(id)) {
            String key = searchKey.get();
            return key == null || key.isBlank() ? unavailable("网页查询服务尚未配置") : ready();
        }
        if ("web_fetch".equals(id)) return ready();
        if ("deliver_artifact".equals(id) && !artifacts.getAsBoolean()) return unavailable("产物交付服务尚未接入");
        if (Set.of("agent_list", "agent_send", "agent_spawn", "wait_async_results", "task_list",
                "task_output", "task_cancel").contains(id) && !collaboration.getAsBoolean()) {
            return unavailable("智能体协作服务不可用");
        }
        return common;
    }

    private synchronized ProjectAgentNativeToolCatalog.Readiness sandboxReadiness() {
        long now = System.nanoTime();
        if (sandbox != null && now - checkedAt < TimeUnit.SECONDS.toNanos(10)) return sandbox;
        Path existing = workspace;
        boolean unsafePath = false;
        for (Path part = workspace; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) { unsafePath = true; break; }
            if (Files.exists(part, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(part)) {
                unsafePath = true; break;
            }
        }
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (unsafePath || existing == null || !Files.isDirectory(existing) || !Files.isWritable(existing)) {
            sandbox = unavailable("运行工作区不可写");
        } else if (!probe.succeeds(List.of("docker", "info", "--format", "{{.ServerVersion}}"))) {
            sandbox = unavailable("Docker 沙箱服务不可用");
        } else if (image == null || image.isBlank()
                || !probe.succeeds(List.of("docker", "image", "inspect", image))) {
            sandbox = unavailable("运行沙箱镜像尚未就绪");
        } else sandbox = ready();
        checkedAt = System.nanoTime();
        return sandbox;
    }

    private static boolean commandSucceeds(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.io.IOException unavailable) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
    private static ProjectAgentNativeToolCatalog.Readiness ready() {
        return new ProjectAgentNativeToolCatalog.Readiness(true, null);
    }
    private static ProjectAgentNativeToolCatalog.Readiness unavailable(String reason) {
        return new ProjectAgentNativeToolCatalog.Readiness(false, reason);
    }
}
