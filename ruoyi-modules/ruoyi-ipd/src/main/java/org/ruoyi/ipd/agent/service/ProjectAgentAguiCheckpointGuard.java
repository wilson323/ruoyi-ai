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

    @FunctionalInterface public interface ChildPreflight {
        void validate(IpdActor actor,IpdAgentRun run,RunAgentInput canonical,
            org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval approval);
    }
    private final ChildPreflight childPreflight;
    public ProjectAgentAguiCheckpointGuard(AgentStateStore states,
            BiFunction<IpdActor, RunAgentInput, Map<String, AguiTool>> authorizedTools) {
        this(states,authorizedTools,(actor,run,input,approval)->{throw new IllegalStateException("原子恢复预检未装配");});
    }
    public ProjectAgentAguiCheckpointGuard(AgentStateStore states,
            BiFunction<IpdActor,RunAgentInput,Map<String,AguiTool>> authorizedTools,ChildPreflight preflight) {
        this.states=Objects.requireNonNull(states);this.authorizedTools=Objects.requireNonNull(authorizedTools);
        this.childPreflight=Objects.requireNonNull(preflight);
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
        if (!pause.pending().keySet().containsAll(pause.childApprovals().keySet()))
            throw new IllegalStateException("子审批不属于原中断集合");
        Map<String,AguiTool> tools=Map.copyOf(Objects.requireNonNull(authorizedTools.apply(actor,input),"authorized frontend catalog"));
        var canonical=org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.bind(
            org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.withFrontendTools(input,tools.values().stream()
                .sorted(java.util.Comparator.comparing(AguiTool::getName)).toList()),pause.threadId(),pause.runId(),tools);
        for (var entry : pause.pending().entrySet()) {
            AguiEvent.Interrupt interrupt = entry.getValue();
            var child = pause.childApprovals().get(entry.getKey());
            if (child != null) {
                validateChild(scope, child, interrupt);
                childPreflight.validate(actor,run,canonical,child);
                continue;
            }
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
        return tools;
    }

    /** 这里只核服务器持久审批与 SDK 状态；实际 child actor 必须由可信工厂恢复。 */
    private void validateChild(KernelScopeKey.Scope scope,
            org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval child,
            AguiEvent.Interrupt interrupt) {
        if (!scope.userId().equals(child.userId()) || child.sessionId() == null
            || child.sessionId().isBlank() || scope.sessionId().equals(child.sessionId()))
            throw new IllegalStateException("子检查点不属于原运行");
        // 原 TemporaryStateStore 将 SDK 子会话映射到运行命名空间；状态本身仍保留 SDK 原 sessionId。
        String storageSession = scope.sessionId() + "/official/" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(child.sessionId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var saved = states.getVersioned(child.userId(), storageSession, "agent_state", AgentState.class);
        if (!saved.isPresent() || saved.version() != child.checkpointVersion()
            || !child.userId().equals(saved.value().getUserId())
            || !child.sessionId().equals(saved.value().getSessionId()))
            throw new IllegalStateException("子检查点已变更或不存在");
        var context = saved.value().getContext();
        var latest = context.stream().filter(m -> m.getRole() == MsgRole.ASSISTANT)
            .reduce((first, second) -> second).orElseThrow(() -> new IllegalStateException("没有当前子调用"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (child.calls() == null || child.calls().isEmpty())
            throw new IllegalStateException("子审批缺少原调用");
        var seen = new HashSet<String>();
        for (var original : child.calls()) {
            if (!seen.add(original.id())) throw new IllegalStateException("重复子调用");
            var matches = latest.getContentBlocks(ToolUseBlock.class).stream()
                .filter(c -> Objects.equals(c.getId(), original.id())).toList();
            if (matches.size() != 1) throw new IllegalStateException("子调用不是当前调用");
            var call = matches.get(0);
            try {
                if (call.getState() != ToolCallState.ASKING || !Objects.equals(call.getName(), original.name())
                    || !Objects.equals(call.getContent(), original.rawContent())
                    || !mapper.valueToTree(call.getInput()).equals(mapper.readTree(original.canonicalInput())))
                    throw new IllegalStateException("子审批原调用已变更");
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalStateException("子调用参数不可验证", invalid);
            }
            if (context.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .anyMatch(r -> Objects.equals(r.getId(), original.id())))
                throw new IllegalStateException("子调用已处理完成");
        }
        var selected = child.calls().stream().filter(c -> Objects.equals(c.id(), interrupt.toolCallId())).toList();
        if (selected.size() != 1) throw new IllegalStateException("子中断与原调用不匹配");
        var metadata = interrupt.metadata() == null ? Map.<String,Object>of() : interrupt.metadata();
        var original = selected.get(0);
        var call = latest.getContentBlocks(ToolUseBlock.class).stream()
            .filter(c -> Objects.equals(c.getId(), original.id())).findFirst().orElseThrow();
        if (!"permission_confirm".equals(metadata.get("agentscope.interruptKind"))
            || !Objects.equals(original.name(), metadata.get("toolName"))
            || !Objects.equals(call.getInput(), metadata.get("toolInput"))
            || !Objects.equals(io.agentscope.core.util.JsonUtils.resolveToolCallArgsJson(call), metadata.get("toolContent")))
            throw new IllegalStateException("子中断参数与原调用不匹配");
    }
}
