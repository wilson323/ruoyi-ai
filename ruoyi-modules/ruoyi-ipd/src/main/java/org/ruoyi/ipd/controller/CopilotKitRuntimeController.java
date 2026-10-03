package org.ruoyi.ipd.controller;

import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import org.ruoyi.common.sse.core.SseErrorEmitter;
import org.ruoyi.ipd.copilotkit.AgUiCopilotRun;
import org.ruoyi.ipd.copilotkit.RunAgentInput;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.ruoyi.ipd.service.AiCopilotService;
import org.ruoyi.ipd.service.CopilotRunRegistryService;
import org.ruoyi.ipd.security.IpdPermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CopilotKit AG-UI 桥（2026-09-28，单轨融合）：CopilotKit Runtime 兼容端点（多路由形态）。
 *
 * <ul>
 *   <li>{@code GET /copilotkit/info}：runtime info + agents 元数据（agentId 固定 {@code ipd_copilot}），
 *       响应形状对齐官方 {@code @copilotkit/runtime} get-runtime-info（agents 为 name→meta 映射）；</li>
 *   <li>{@code POST /copilotkit/agent/{agentId}/run}：body=AG-UI RunAgentInput → {@code text/event-stream}
 *       SSE 流（AG-UI 事件）；未知 agentId → 404 官方 error 形状
 *       {@code {"error":"Agent not found","message":"Agent '<id>' does not exist"}}。</li>
 * </ul>
 *
 * <p>鉴权：run 端点走 {@link AgUiCopilotRun} 的 Bearer 会话校验（与 {@code /api/v1/ai-copilot/chat} 对齐，
 * 身份从会话取、不信任 body）；info 端点无敏感信息（静态元数据），与官方 runtime 探测行为一致（客户端
 * GET {runtimeUrl}/info 2xx → rest transport），不需要登录。基线 SecurityConfig 已 exclude {@code /copilotkit/**}
 * （见 application.yml security.excludes，与 /api/v1/** 同款 IPD 会话自管）。
 *
 * <p>线格式：每帧一个 AG-UI 事件 JSON，由官方 {@link AguiEventEncoder}（AgentScope 2.0.3）序列化——
 * {@code encodeToJson} 产事件 JSON，{@code data: {json}} 事件帧由 SseEmitter 组包（与官方 encode() 产帧同构；
 * 官方 @ag-ui/client parseSSEStream 取帧内 data: 行 JSON.parse，按 type 分发）；空闲期发官方
 * {@code keepAlive()} 注释帧防中间层断连（SSE 注释行前端可安全忽略）。
 */
@Slf4j
@RestController
@RequestMapping("/copilotkit")
public class CopilotKitRuntimeController {

    /** SSE 连接超时（覆盖最长 chitchat 30s + 流式推送延迟，与既有 ai-copilot SSE 一致）。 */
    static final long SSE_TIMEOUT_MS = 60_000L;
    /** 契约固定 agentId（与前端 CopilotKit agents 配置一致）。 */
    public static final String AGENT_ID = "ipd_copilot";
    /** 与 @copilotkit/runtime 1.74 契约兼容的版本标识（客户端仅信息展示，无强校验）。 */
    static final String RUNTIME_VERSION = "1.74.0";
    /** 官方事件序列化器（AgentScope 2.0.3，无状态可共享）。 */
    private static final AguiEventEncoder AGUI_ENCODER = new AguiEventEncoder();
    /** SSE 数据帧 mediaType（显式 UTF-8：防 StringHttpMessageConverter 默认 ISO-8859-1 乱码中文）。 */
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json;charset=UTF-8");
    /** 官方 keep-alive 心跳间隔（SSE_TIMEOUT_MS 内多拍防中间层空闲断连）。 */
    static final long KEEP_ALIVE_INTERVAL_MS = 15_000L;
    /** 心跳调度器（daemon 单线程，与 SSE_EXECUTOR 同款自管线程模式）。 */
    private static final ScheduledExecutorService KEEP_ALIVE_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ipd-copilotkit-agui-keepalive");
            t.setDaemon(true);
            return t;
        });

    private final AiCopilotService service;
    private final IpdPermission ipdPermission;
    private final CopilotRunRegistryService runRegistry;
    private final Executor executor;

    /** 复用独立 cachedThreadPool 推 SSE 流（与 AiCopilotController.SSE_EXECUTOR 同模式，不分业务线程池）。 */
    private static final Executor SSE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ipd-copilotkit-agui");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public CopilotKitRuntimeController(AiCopilotService service, IpdPermission ipdPermission,
                                       CopilotRunRegistryService runRegistry) {
        this(service, ipdPermission, runRegistry, SSE_EXECUTOR);
    }

    /** 测试口：注入直通 executor（Runnable::run）保证同步确定性。 */
    CopilotKitRuntimeController(AiCopilotService service, IpdPermission ipdPermission,
                                CopilotRunRegistryService runRegistry, Executor executor) {
        this.service = service;
        this.ipdPermission = ipdPermission;
        this.runRegistry = runRegistry;
        this.executor = executor;
    }

    /** Runtime info（官方 get-runtime-info 形状）：agents 元数据 + sse 模式声明。 */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", AGENT_ID);
        agent.put("description", "IPD AI 副驾（单轨复用 ai-copilot 流式链的 AG-UI 桥）");
        agent.put("capabilities", List.of());
        Map<String, Object> agents = new LinkedHashMap<>();
        agents.put(AGENT_ID, agent);

        Map<String, Object> threadEndpoints = new LinkedHashMap<>();
        threadEndpoints.put("list", false);
        threadEndpoints.put("inspect", false);
        threadEndpoints.put("mutations", false);
        threadEndpoints.put("realtimeMetadata", false);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", RUNTIME_VERSION);
        info.put("agents", agents);
        info.put("audioFileTranscriptionEnabled", false);
        info.put("mode", "sse");
        info.put("threadEndpoints", threadEndpoints);
        info.put("suggestions", false);
        info.put("a2uiEnabled", false);
        info.put("openGenerativeUIEnabled", false);
        info.put("telemetryDisabled", true);
        return info;
    }

    /**
     * AG-UI run 端点：RunAgentInput → SSE（AG-UI 事件流）。未知 agentId → 404 官方 error 形状；
     * 鉴权/参数/越权/流式错误全部 in-band RUN_ERROR 帧（SSE 契约模式 B，不回 JSON——EventSource MIME 教训）。
     */
    @PostMapping(value = "/agent/{agentId}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Object> run(@PathVariable String agentId, @RequestBody RunAgentInput input) {
        if (!AGENT_ID.equals(agentId)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Agent not found");
            body.put("message", "Agent '" + agentId + "' does not exist");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        try {
            new AgUiCopilotRun(service, ipdPermission, executor, runRegistry).execute(input, sseSink(emitter));
        } catch (Exception e) {
            // 同步拒绝/同步异常兜底（execute 内部业务错误已 in-band RUN_ERROR；此处只接漏网同步抛出）
            SseErrorEmitter.completeWithError(emitter, "RUN_ERROR", e.getMessage(), log);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /** SseEmitter 事件下发适配（官方 encoder 序列化 + keep-alive 心跳；断开静默吞，与既有防御一致）。 */
    static AgUiCopilotRun.AgUiSseSink sseSink(SseEmitter emitter) {
        return new AgUiCopilotRun.AgUiSseSink() {
            private final AtomicBoolean heartbeatStopped = new AtomicBoolean(false);
            /** 官方 keep-alive 心跳：注释行交 SseEmitter 注释通道（不占 data 帧，前端 EventSource 安全忽略）。 */
            private final ScheduledFuture<?> keepAlive = KEEP_ALIVE_SCHEDULER.scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event().comment(keepAliveComment()));
                } catch (Exception e) {
                    stopHeartbeat();
                }
            }, KEEP_ALIVE_INTERVAL_MS, KEEP_ALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
            private Runnable cancellation = this::stopHeartbeat;

            private void stopHeartbeat() {
                if (heartbeatStopped.compareAndSet(false, true)) {
                    keepAlive.cancel(false);
                }
            }

            @Override public void onDisconnect(Runnable action) {
                cancellation = () -> { action.run(); stopHeartbeat(); };
                emitter.onCompletion(cancellation);
                emitter.onTimeout(() -> { action.run(); stopHeartbeat(); emitter.complete(); });
                emitter.onError(error -> cancellation.run());
            }
            @Override
            public void send(List<AguiEvent> events) {
                if (events == null) {
                    return;
                }
                for (AguiEvent event : events) {
                    try {
                        emitter.send(SseEmitter.event().data(AGUI_ENCODER.encodeToJson(event), JSON_UTF8));
                    } catch (IOException | IllegalStateException e) {
                        cancellation.run();
                        // 客户端已断开，静默（不重复推 error，避免 SIGPIPE 噪声）
                    }
                }
            }

            @Override
            public void complete() {
                stopHeartbeat();
                try {
                    emitter.complete();
                } catch (Exception e) {
                    // 已完成/已断开，静默
                }
            }
        };
    }

    /** 官方 keepAlive() 产完整注释帧（": keep-alive"）——剥帧前缀取注释文本交 SseEmitter（内容随官方）。 */
    private static String keepAliveComment() {
        return AGUI_ENCODER.keepAlive().replaceFirst("^:\\s*", "").trim();
    }
}
