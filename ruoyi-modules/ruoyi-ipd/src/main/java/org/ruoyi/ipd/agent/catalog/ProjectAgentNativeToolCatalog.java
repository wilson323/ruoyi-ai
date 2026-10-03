package org.ruoyi.ipd.agent.catalog;

import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact official 2.0.3 full profile; provider readiness is separate from business-pack selection. */
public final class ProjectAgentNativeToolCatalog {
    private ProjectAgentNativeToolCatalog() { }
    public static final String VERSION = "agentscope-harness-2.0.3";
    public static final List<String> IDS = List.of("read_file", "write_file", "edit_file",
        "grep_files", "glob_files", "list_files", "execute", "web_fetch", "web_search",
        "memory_get", "memory_search", "memory_save", "session_search", "session_list",
        "session_history", "wait_async_results", "agent_list", "agent_send", "agent_spawn",
        "task_cancel", "task_list", "task_output", "load_skill_through_path",
        "deliver_artifact", "plan_enter", "plan_exit", "plan_write", "propose_skill",
        "reset_equipped_tools", "skill_manage", "todo_write");
    public record Readiness(boolean available, String reason) {
        public Readiness {
            if (!available && (reason == null || reason.isBlank())) reason = "工具运行环境尚未核验";
            if (available) reason = null;
        }
    }
    @FunctionalInterface public interface Provider { Readiness status(String toolId); }
    public static Provider unverified() { return id -> new Readiness(false, "工具运行环境尚未核验"); }
    /** Actual registered IDs plus per-provider checks, supplied by host assembly; never inferred from a label. */
    public static Provider providers(Set<String> registered, Map<String, Readiness> readiness) {
        Set<String> names = Set.copyOf(Objects.requireNonNull(registered));
        Map<String, Readiness> checks = Map.copyOf(Objects.requireNonNull(readiness));
        return id -> !names.contains(id) ? new Readiness(false, "工具未在运行内核注册")
            : checks.getOrDefault(id, new Readiness(false, "工具运行环境尚未核验"));
    }
    public static List<String> executionIds(List<String> businessIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(IDS);
        if (businessIds != null) ids.addAll(businessIds);
        return List.copyOf(ids);
    }
    public static ToolDescriptor descriptor(String id) {
        if (!IDS.contains(id)) return null;
        EnumSet<ToolCapability> effects = EnumSet.of(ToolCapability.READ);
        if (List.of("write_file", "edit_file", "memory_save", "plan_write", "propose_skill",
            "skill_manage", "todo_write", "deliver_artifact").contains(id)) effects.add(ToolCapability.WRITE);
        if ("execute".equals(id)) effects.add(ToolCapability.EXECUTE);
        if (List.of("web_fetch", "web_search").contains(id)) effects.add(ToolCapability.NETWORK);
        if (id.contains("search") || id.equals("grep_files") || id.equals("glob_files")) effects.add(ToolCapability.SEARCH);
        if (List.of("wait_async_results", "agent_send", "agent_spawn", "task_cancel", "plan_enter",
            "plan_exit", "reset_equipped_tools", "skill_manage", "load_skill_through_path",
            "deliver_artifact").contains(id)) effects.add(ToolCapability.CONTROL);
        return new ToolDescriptor(id, effects, !effects.contains(ToolCapability.WRITE)
            && !effects.contains(ToolCapability.EXECUTE) && !effects.contains(ToolCapability.NETWORK)
            && !effects.contains(ToolCapability.CONTROL), 30_000L, 65_536L, 65_536L, false,
            "官方工具；绑定当前项目和人员，由官方运行权限扩展逐调用校验");
    }
    public static boolean readOnly(String id) {
        ToolDescriptor d = descriptor(id);
        return d != null && !d.hasCapability(ToolCapability.WRITE) && !d.hasCapability(ToolCapability.EXECUTE)
            && !d.hasCapability(ToolCapability.NETWORK) && !d.hasCapability(ToolCapability.CONTROL);
    }
}
