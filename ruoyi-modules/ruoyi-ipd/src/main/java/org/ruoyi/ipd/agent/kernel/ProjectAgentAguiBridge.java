package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 官方事件转换挂在既有后台订阅上；协议事件复用原 STEP/seq 持久化，不另起运行。 */
public final class ProjectAgentAguiBridge {
    private final ProjectAgentEventSink sink;
    private final AguiStreamContext context;
    private final AgentEventConverterRegistry converters;
    private final AguiEventEncoder encoder = new AguiEventEncoder();
    private volatile Map<String, AguiEvent.Interrupt> pendingInterrupts = Map.of();

    public ProjectAgentAguiBridge(ProjectAgentEventSink sink, Long runId) {
        this(sink, runId, null);
    }

    public ProjectAgentAguiBridge(ProjectAgentEventSink sink, Long runId,
            io.agentscope.core.agui.model.RunAgentInput serverBoundInput) {
        this.sink = sink;
        var config = AguiAdapterConfig.builder()
            .emitStateEvents(true).emitToolCallArgs(true).emitTokenUsage(true)
            .enableReasoning(true).emitRunFinishedAfterError(false)
            .emitSubagentEventsAsNative(false).baseEventPropertiesEnricherEnabled(true)
            .addEventConverter(new FrontendSuspensionConverter(serverBoundInput)).build();
        context = new AguiStreamContext(String.valueOf(runId), String.valueOf(runId), config, serverBoundInput);
        converters = new AgentEventConverterRegistry(config.getEventConverters(), config.getEventEnrichers(),
            config.isEmitSubagentEventsAsNative());
    }

    public void accept(AgentEvent source) {
        List<String> encoded = new ArrayList<>();
        // 思考原文不进入业务事件表；保留官方扩展事件与子任务来源供 UI 显示活动状态。
        if (source instanceof ThinkingBlockDeltaEvent) {
            encoded.add(encoder.encodeToJson(new AguiEvent.Custom(context.getThreadId(), context.getRunId(),
                source.getSource() == null ? "ipd.thinking" : "subagent.thinking",
                Map.of("active", true, "source", source.getSource() == null ? "" : source.getSource()),
                System.currentTimeMillis(), null)));
        } else {
            for (AguiEvent event : converters.convert(source, context)) {
                // SDK 结束不代表业务产物通过；main 生命周期由已提交的原运行事件投影。
                if (event instanceof AguiEvent.RunStarted || event instanceof AguiEvent.RunError) continue;
                if (event instanceof AguiEvent.RunFinished finished
                    && !(finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome)) continue;
                if (event instanceof AguiEvent.RunFinished finished
                    && finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome interrupted) {
                    var pending = new java.util.LinkedHashMap<String, AguiEvent.Interrupt>();
                    for (AguiEvent.Interrupt interrupt : interrupted.interrupts()) {
                        if (pending.putIfAbsent(interrupt.id(), interrupt) != null) {
                            throw new IllegalArgumentException("duplicate native interrupt id");
                        }
                    }
                    pendingInterrupts = Map.copyOf(pending);
                }
                if (event instanceof AguiEvent.Raw raw) {
                    // 未映射 SDK 事件可能含模型请求或内部状态，只披露类型与来源。
                    event = new AguiEvent.Raw(raw.threadId(), raw.runId(),
                        Map.of("type", source.getType().name()), raw.source(), raw.timestamp(), null);
                }
                encoded.add(encoder.encodeToJson(event));
            }
        }
        if (!encoded.isEmpty()) sink.onStep("AGUI", Map.of("events", List.copyOf(encoded)));
    }


    /** 保留内置生命周期转换，只补原工具结果中真实挂起的、服务端登记的前端调用。 */
    private static final class FrontendSuspensionConverter
            implements io.agentscope.core.agui.adapter.strategy.AgentEventConverter {
        private final java.util.Set<String> frontendNames;
        private final AgentEventConverterRegistry defaults = new AgentEventConverterRegistry();
        FrontendSuspensionConverter(io.agentscope.core.agui.model.RunAgentInput input) {
            var names=new java.util.HashSet<String>();
            if (input!=null) for (var tool:input.getTools()) {
                if (!names.add(tool.getName())) throw new IllegalArgumentException("duplicate server frontend tool");
            }
            frontendNames=java.util.Set.copyOf(names);
        }
        public java.util.Set<Class<? extends AgentEvent>> eventTypes() {
            return java.util.Set.of(io.agentscope.core.event.AgentResultEvent.class);
        }
        public void convert(AgentEvent event,AguiStreamContext stream) {
            // 自定义 converter 覆盖同类型默认项；显式调用无自定义的官方 registry 保留原行为。
            for (AguiEvent converted:defaults.convert(event,stream)) stream.emit(converted);
            var result=((io.agentscope.core.event.AgentResultEvent)event).getResult();
            if (result==null || result.getGenerateReason()!=io.agentscope.core.message.GenerateReason.TOOL_SUSPENDED) return;
            var calls=new java.util.LinkedHashMap<String,io.agentscope.core.message.ToolUseBlock>();
            for (var call:result.getContentBlocks(io.agentscope.core.message.ToolUseBlock.class)) {
                if (call.getId()==null || call.getId().isBlank()) throw new IllegalArgumentException("suspended frontend call requires stable id");
                if (calls.putIfAbsent(call.getId(),call)!=null) throw new IllegalArgumentException("duplicate suspended tool call id");
            }
            var suspendedIds=new java.util.HashSet<String>();
            for (var suspended:result.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class)) {
                if (!suspended.isSuspended()) continue;
                var call=calls.get(suspended.getId());
                if (call==null) {
                    if (frontendNames.contains(suspended.getName())) throw new IllegalArgumentException("frontend suspension lacks original tool call");
                    continue;
                }
                if (!frontendNames.contains(call.getName())) continue;
                if (!suspendedIds.add(suspended.getId())) throw new IllegalArgumentException("duplicate frontend suspension id");
                if (suspended.getName()!=null && !suspended.getName().equals(call.getName()))
                    throw new IllegalArgumentException("frontend result does not match original tool call");
                String interruptId=result.getId()==null || result.getId().isBlank()
                    ? call.getId() : result.getId()+":"+call.getId();
                var metadata=new java.util.LinkedHashMap<String,Object>();
                metadata.put("toolName",call.getName());
                if (call.getInput()!=null) metadata.put("toolInput",call.getInput());
                metadata.put("toolContent",io.agentscope.core.util.JsonUtils.resolveToolCallArgsJson(call));
                if (result.getId()!=null && !result.getId().isBlank()) metadata.put("replyId",result.getId());
                String message=suspended.getOutput()==null ? null : suspended.getOutput().stream()
                    .filter(io.agentscope.core.message.TextBlock.class::isInstance)
                    .map(io.agentscope.core.message.TextBlock.class::cast)
                    .map(io.agentscope.core.message.TextBlock::getText).collect(java.util.stream.Collectors.joining("\n"));
                stream.addInterrupt(new AguiEvent.Interrupt(interruptId,"tool_call",message,call.getId(),null,null,Map.copyOf(metadata)));
            }
        }
    }

    /** 原始官方中断供服务器暂停路径持久化；不能用客户端 state 重建此记录。 */
    public Map<String, AguiEvent.Interrupt> pendingInterrupts() {
        var pending = new java.util.LinkedHashMap<String, AguiEvent.Interrupt>(pendingInterrupts);
        for (AguiEvent.Interrupt interrupt : context.getPendingInterrupts()) {
            AguiEvent.Interrupt previous = pending.putIfAbsent(interrupt.id(), interrupt);
            if (previous != null && !previous.equals(interrupt)) {
                throw new IllegalArgumentException("conflicting native interrupt id");
            }
        }
        return Map.copyOf(pending);
    }
}
