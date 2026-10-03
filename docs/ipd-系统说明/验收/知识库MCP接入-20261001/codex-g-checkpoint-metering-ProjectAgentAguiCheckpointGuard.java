package org.ruoyi.ipd.agent.service;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.security.IpdActor;
import java.util.*;
import java.util.function.BiFunction;

/** 校验原 SDK 检查点与原中断一致；浏览器状态不进入 AgentState。 */
public final class ProjectAgentAguiCheckpointGuard implements ProjectAgentAguiPauseResumeService.TrustedGuard {
    private final AgentStateStore states;
    private final BiFunction<IpdActor, RunAgentInput, Map<String, AguiTool>> authorizedTools;

    public ProjectAgentAguiCheckpointGuard(AgentStateStore states,
            BiFunction<IpdActor, RunAgentInput, Map<String, AguiTool>> authorizedTools) {
        this.states = Objects.requireNonNull(states);
        this.authorizedTools = Objects.requireNonNull(authorizedTools);
    }

    @Override public Map<String, AguiTool> validate(IpdActor actor, IpdAgentRun run,
            ProjectAgentAguiPauseResumeService.PauseCheckpoint pause, RunAgentInput input) {
        if (!Objects.equals(actor.id(), run.getPersonId()) || !String.valueOf(run.getId()).equals(pause.runId()))
            throw new IllegalArgumentException("恢复身份与原运行不匹配");
        var scope = KernelScopeKey.of(String.valueOf(run.getProjectId()), String.valueOf(run.getPersonId()),
            ProjectAgentConstants.AGENT_ID, String.valueOf(run.getId()));
        var checkpoint = states.getVersioned(scope.userId(), scope.sessionId(), "agent_state", AgentState.class);
        if (!checkpoint.isPresent() || checkpoint.version() != pause.checkpointVersion())
            throw new IllegalStateException("原运行检查点已变更或不存在");
        AgentState state = checkpoint.value();
        if (!scope.userId().equals(state.getUserId()) || !scope.sessionId().equals(state.getSessionId()))
            throw new IllegalStateException("检查点不属于原运行");
        Map<String, ToolUseBlock> calls = new LinkedHashMap<>();
        Map<String, ToolResultBlock> suspended = new LinkedHashMap<>();
        for (Msg message : state.getContext()) {
            for (ContentBlock block : message.getContent()) {
                if (message.getRole() == MsgRole.ASSISTANT && block instanceof ToolUseBlock use) {
                    calls.put(use.getId(), use);
                    suspended.remove(use.getId());
                }
                if (block instanceof ToolResultBlock result) suspended.put(result.getId(), result);
            }
        }
        for (AguiEvent.Interrupt interrupt : pause.pending().values()) {
            ToolUseBlock call = calls.get(interrupt.toolCallId());
            Map<String, Object> metadata = interrupt.metadata() == null ? Map.of() : interrupt.metadata();
            if (call == null || !Objects.equals(call.getName(), metadata.get("toolName"))
                || !Objects.equals(call.getInput(), metadata.getOrDefault("toolInput", Map.of())))
                throw new IllegalStateException("原工具中断与检查点不一致");
            String content = io.agentscope.core.util.JsonUtils.resolveToolCallArgsJson(call);
            if (!Objects.equals(content, metadata.get("toolContent")))
                throw new IllegalStateException("原工具参数与检查点不一致");
            if ("permission_confirm".equals(metadata.get("agentscope.interruptKind"))) {
                if (call.getState() != ToolCallState.ASKING
                    || (suspended.containsKey(call.getId()) && !suspended.get(call.getId()).isSuspended()))
                    throw new IllegalStateException("原工具没有等待确认或已处理完成");
            } else if (call.getState() != ToolCallState.ALLOWED
                || (suspended.containsKey(call.getId()) && !suspended.get(call.getId()).isSuspended())) {
                // 官方 TOOL_SUSPENDED 的 pending result 只在原结果事件中，未追加到持久 context。
                // 其证据是服务端已持久化的原中断，加同一检查点内尚未完成的 ALLOWED 原调用。
                throw new IllegalStateException("原工具没有等待返回结果或已处理完成");
            }
        }
        return Map.copyOf(Objects.requireNonNull(authorizedTools.apply(actor, input), "authorized frontend catalog"));
    }
}
