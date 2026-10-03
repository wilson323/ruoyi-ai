package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.harness.agent.tools.HarnessPlatformTools;
import java.util.LinkedHashSet;
import java.util.Set;

/** 官方能力授权只作用于本次隔离工作区，不授予 IPD 审核、动作批准或 Gate 权限。 */
public final class ProjectAgentOfficialPermissions {
    private static final String PLAN_EXIT = "plan_exit";
    /**
     * todo_write 是纯内部的待办状态记录：只影响智能体自己的推进清单，不产出对外内容、
     * 不改工作区、不发起外呼。owner 判定与 {@code plan_exit}（收工节点，值得人看一眼）
     * 性质不同，不应因它打断用户。
     * 实测代价：未放行时模型一次 todo_write 就会把运行推入 WAITING_APPROVAL，
     * 表现为「权限已自动放行却仍弹审批」——两条同名不同机制的路径被误看成一条。
     */
    private static final String TODO_WRITE = "todo_write";
    private static final Set<String> WORKSPACE_TOOLS = Set.of(
        "read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files", "execute",
        "web_fetch", "web_search", "memory_search", "memory_get", "memory_save", "session_search",
        "session_list", "session_history", "deliver_artifact", "get_pending_completion",
        ProjectAgentOutputContract.CLARIFICATION_TOOL, TODO_WRITE);

    private ProjectAgentOfficialPermissions() { }

    public static PermissionContextState workspace() {
        return extend(PermissionContextState.builder().build());
    }

    /** 子智能体沿用相同授权，已有官方 deny/ask 表保留且优先于能力授权。 */
    public static PermissionContextState extend(PermissionContextState existing) {
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(existing.getMode());
        existing.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        existing.getAllowRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addAllowRule(tool, rule)));
        existing.getDenyRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addDenyRule(tool, rule)));
        existing.getAskRules().forEach((tool, rules) -> rules.forEach(rule -> builder.addAskRule(tool, rule)));
        Set<String> authorized = new LinkedHashSet<>(WORKSPACE_TOOLS);
        authorized.addAll(HarnessPlatformTools.NAMES);
        for (String name : authorized) {
            if (!existing.getAllowRules().containsKey(name)) {
                builder.addAllowRule(name, new PermissionRule(name, null, PermissionBehavior.ALLOW,
                    "project-agent-isolated-workspace"));
            }
        }
        // SDK 2.0.3 的工具自检 ASK 可能被 allowRule 覆盖；由官方 askRule 保留计划确认。
        if (!existing.getAskRules().containsKey(PLAN_EXIT)) {
            builder.addAskRule(PLAN_EXIT, new PermissionRule(PLAN_EXIT, null, PermissionBehavior.ASK,
                "project-agent-plan-confirmation"));
        }
        return builder.build();
    }
}
