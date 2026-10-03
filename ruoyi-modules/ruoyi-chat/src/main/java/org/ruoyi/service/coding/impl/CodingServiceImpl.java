package org.ruoyi.service.coding.impl;

import cn.hutool.core.util.StrUtil;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.ruoyi.chat.kernel.AgentScopeModelFactory;
import org.ruoyi.chat.kernel.ChatOfficialCapabilities;
import org.ruoyi.chat.kernel.KernelModelRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.json.utils.JsonUtils;
import org.ruoyi.domain.bo.coding.CodingRequestBo;
import org.ruoyi.mcp.tools.DeleteFileTool;
import org.ruoyi.mcp.tools.EditFileTool;
import org.ruoyi.mcp.tools.ExecuteCommandTool;
import org.ruoyi.mcp.tools.ListDirectoryTool;
import org.ruoyi.mcp.tools.ReadFileTool;
import org.ruoyi.mcp.tools.WriteFileTool;
import org.ruoyi.service.coding.CodingEventChannel;
import org.ruoyi.service.coding.CodingSseEvent;
import org.ruoyi.service.coding.CodingWorkspaceService;
import org.ruoyi.service.coding.ICodingService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 编程能力 Service 实现。
 *
 * <p>B 路径：自建 SseEmitter（不进 SseEmitterManager 全局注册表），照 ShortDramaServiceImpl 骨架。
 * 模型配置 → 解析工作目录 → 原生 Toolkit 注入 channel+root → HarnessAgent 构建 →
 * 异步执行，工具内部通过 channel 实时推事件，drain 线程把事件写到 emitter。
 *
 * @author ageerle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodingServiceImpl implements ICodingService {

    static final String SAFE_CODING_ERROR_MESSAGE = "编程任务执行失败，请稍后重试";

    private final IChatModelService chatModelService;
    private final CodingWorkspaceService workspaceService;
    private final Map<SseEmitter, AtomicBoolean> activeEmitters = new ConcurrentHashMap<>();

    @Override
    public SseEmitter chat(CodingRequestBo bo, Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Authenticated coding user is required");
        }
        // 在建立 SSE 和调用模型前同步拒绝非受控工作区，让调用方获得明确错误。
        Path root = workspaceService.resolveRoot(bo.getWorkspacePath());

        SseEmitter emitter = new SseEmitter(1_800_000L);
        AtomicBoolean emitterActive = new AtomicBoolean(true);
        activeEmitters.put(emitter, emitterActive);
        emitter.onCompletion(() -> closeEmitter(emitter));
        emitter.onTimeout(() -> closeEmitter(emitter));
        emitter.onError(error -> closeEmitter(emitter));

        CompletableFuture.runAsync(() -> {
            CodingEventChannel channel = new CodingEventChannel();
            Thread drainThread = new Thread(() -> {
                try {
                    channel.drain(event -> sendEmitterEvent(emitter, toSseEvent(event)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    log.error("coding_sse operation=DRAIN status=FAILED errorType={}", errorType(t));
                }
            }, "coding-sse-drain");
            drainThread.start();

            try {
                // 推送思考开始
                channel.send(CodingSseEvent.thinking("正在分析指令..."));

                // 1. 拿模型三步（不硬编码配置）
                ChatModelVo modelVo = chatModelService.selectModelByName(bo.getModel());
                if (modelVo == null) {
                    throw new IllegalStateException("模型未找到: " + bo.getModel()
                        + "，请在 chat_model 表配置该模型名称");
                }
                KernelModelRequest selectedModel = KernelModelRequest.from(modelVo);
                Model chatModel = AgentScopeModelFactory.create(selectedModel);

                // 2. 解析工作目录
                Files.createDirectories(root);

                // 3. new 工具实例（不走 BuiltinToolRegistry，注入会话工作目录与 channel）
                ReadFileTool read = new ReadFileTool(root, channel);
                EditFileTool edit = new EditFileTool(root, channel);
                ListDirectoryTool list = new ListDirectoryTool(root, channel);
                WriteFileTool write = new WriteFileTool(root, channel);
                DeleteFileTool delete = new DeleteFileTool(root, channel);
                ExecuteCommandTool exec = new ExecuteCommandTool(root, channel);

                // Native execution keeps the existing workspace guard and tool event channel.
                Toolkit toolkit = new Toolkit();
                for (Object tool : java.util.List.of(read, edit, list, write, delete, exec)) {
                    toolkit.registerTool(tool);
                }
                String result;
                // The existing business tools own real host-workspace changes and their SSE receipts.
                // Official filesystem/shell/memory/plan tools execute in a separate session sandbox;
                // changes there are working artifacts, never silently claimed as host delivery.
                Path runtimeWorkspace = Files.createTempDirectory("coding-native-runtime-");
                var builder = HarnessAgent.builder().name("coding")
                    .model(chatModel).toolkit(toolkit).maxIters(30)
                    // 模型/工具调用超时与重试套官方默认（模型5min+3次尝试，工具5min单次）；不设时SDK不套任何重试。
                    .modelExecutionConfig(ExecutionConfig.MODEL_DEFAULTS)
                    .toolExecutionConfig(ExecutionConfig.TOOL_DEFAULTS)
                    // 长对话压缩：官方 Builder 默认即装配全默认配置（主模型+官方摘要prompt+动态阈值）。
                    // 此处显式声明与默认等价的配置，固化意图防官方默认漂移；溢出硬失败仅在 disableCompaction 时出现。
                    .compaction(CompactionConfig.builder().build())
                    .sysPrompt("你是编程助手。只操作当前受控工作目录；修改前读取文件，命令失败检查输出，"
                        + "完成后简要总结。工作区写入和命令执行仍受官方权限检查。"
                        + "readFile/writeFile/editFile/deleteFile/listDirectory/executeCommand 操作已授权业务工作目录并提供交付事件；"
                        + "官方 read_file/write_file/execute 在隔离工作区处理本次运行草案，不能把其中的暂存文件当业务目录已交付。")
                    .stateStore(new InMemoryAgentStateStore()).workspace(runtimeWorkspace);
                var capabilities = ChatOfficialCapabilities.configure(builder, runtimeWorkspace, String.valueOf(userId),
                    System.getProperty("chat.kernel.agentscope.sandbox-image", "python:3.13-alpine"),
                    selectedModel.apiKey() == null ? java.util.List.of() : java.util.List.of(selectedModel.apiKey()));
                try (HarnessAgent agent = builder.build()) {
                    capabilities.bind(agent);
                    RuntimeContext context = RuntimeContext.builder().userId(String.valueOf(userId))
                        .sessionId(java.util.UUID.randomUUID().toString()).build();
                    Msg answer = agent.call(Msg.builder().role(MsgRole.USER).textContent(bo.getPrompt()).build(), context)
                        .block(java.time.Duration.ofMinutes(30));
                    if (answer == null) { throw new IllegalStateException("Native coding agent returned no result"); }
                    result = answer.getTextContent();
                }

                // 6. 推送最终文本
                if (StrUtil.isNotBlank(result)) {
                    channel.send(CodingSseEvent.text(result));
                }
                channel.send(CodingSseEvent.done());
                channel.complete();
                drainThread.join(5_000);
                completeEmitter(emitter);

            } catch (Exception e) {
                log.error("coding_chat status=FAILED errorType={}", errorType(e));
                channel.send(safeFailureEvent(e));
                channel.complete();
                try {
                    drainThread.join(2_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                completeEmitter(emitter);
            }
        });

        return emitter;
    }

    /**
     * 把结构化事件转成 SseEmitter 事件。
     */
    private SseEmitter.SseEventBuilder toSseEvent(CodingSseEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.filePath() != null) payload.put("filePath", event.filePath());
        if (event.command() != null) payload.put("command", event.command());
        if (event.content() != null) payload.put("content", event.content());
        if (event.status() != null) payload.put("status", event.status());
        return SseEmitter.event()
            .name(event.eventType())
            .data(JsonUtils.toJsonString(payload));
    }

    // ==================== SSE 发送封装（抄自 ShortDramaServiceImpl） ====================

    private boolean sendEmitterEvent(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        AtomicBoolean active = activeEmitters.get(emitter);
        if (active == null || !active.get()) return false;
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            closeEmitter(emitter);
            return false;
        }
    }

    private void closeEmitter(SseEmitter emitter) {
        AtomicBoolean active = activeEmitters.remove(emitter);
        if (active != null) active.set(false);
    }

    private void completeEmitter(SseEmitter emitter) {
        AtomicBoolean active = activeEmitters.get(emitter);
        if (active == null || !active.compareAndSet(true, false)) return;
        activeEmitters.remove(emitter);
        try { emitter.complete(); } catch (IllegalStateException ignored) { }
    }

    static CodingSseEvent safeFailureEvent(Throwable ignored) {
        return CodingSseEvent.error(SAFE_CODING_ERROR_MESSAGE);
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getName();
    }
}
