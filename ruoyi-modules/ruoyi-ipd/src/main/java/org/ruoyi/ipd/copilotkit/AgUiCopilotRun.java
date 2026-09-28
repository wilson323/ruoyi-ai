package org.ruoyi.ipd.copilotkit;

import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.ipd.dto.AiCopilotResp;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.AiCopilotService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CopilotKit AG-UI 桥（2026-09-28，单轨融合）：RunAgentInput → 既有 ai-copilot 流式链 → AG-UI 事件。
 *
 * <p><b>严格单轨</b>：不建第二套 agent/会话/历史存储，不加任何新写入端点（C08 红线）——
 * 直接复用 {@link AiCopilotService#chatStream}（AiGateway/模型配置消费/审计/越权/RAG 全在其内），
 * 本类只做协议翻译：RunAgentInput → {@link AiCopilotReq}，四帧 sink → {@link AgUiFrameTranslator}。
 *
 * <p><b>鉴权</b>：与 {@code /api/v1/ai-copilot/chat} 同款 Bearer 会话校验
 * （{@link IpdPermission#requireInternal()}，sa-token 线程上下文故在调用线程执行），
 * 用户身份从会话取、不信任 body；错误语义对齐既有 SSE 端点（in-band RUN_ERROR 帧，不回 JSON——
 * EventSource MIME 教训）：IpdBusinessException → code=业务码串（如 50001）、
 * IpdPermissionException → code=20001/30001、未预期 → INTERNAL_ERROR。
 */
public class AgUiCopilotRun {

    /** RunAgentInput → AiCopilotReq 参数上限（与 DTO @Size 对齐；桥侧绕过 @Valid 手动校验）。 */
    static final int MAX_MESSAGE = 2000;
    static final int MAX_PAGE_CONTEXT = 4000;

    /** AG-UI 事件下发口（控制器接 SseEmitter，测试接记录器）。 */
    public interface AgUiSseSink {
        void send(List<Map<String, Object>> events);

        void complete();
    }

    private final AiCopilotService service;
    private final IpdPermission permission;
    private final Executor executor;

    public AgUiCopilotRun(AiCopilotService service, IpdPermission permission, Executor executor) {
        this.service = service;
        this.permission = permission;
        this.executor = executor;
    }

    /**
     * 执行一次 run：鉴权 + 输入翻译在调用线程（sa-token 上下文），流式推送进 executor（与既有
     * ai-copilot SSE 的 SSE_EXECUTOR 模式一致）。done/error 终结帧后 complete，且终结帧只发一次。
     */
    public void execute(RunAgentInput input, AgUiSseSink out) {
        String threadId = nonBlank(input == null ? null : input.threadId())
            ? input.threadId() : UUID.randomUUID().toString();
        String runId = nonBlank(input == null ? null : input.runId())
            ? input.runId() : UUID.randomUUID().toString();
        AgUiFrameTranslator tx = new AgUiFrameTranslator(threadId, runId);

        IpdActor actor;
        try {
            actor = permission.requireInternal();
        } catch (IpdPermissionException pe) {
            fail(out, tx, String.valueOf(pe.getErrorCode().getCode()), pe.getErrorCode().getMessage());
            return;
        }
        AiCopilotReq req;
        try {
            req = toCopilotReq(input);
        } catch (IllegalArgumentException bad) {
            fail(out, tx, String.valueOf(ApiV1ErrorCode.PARAM_INVALID.getCode()), bad.getMessage());
            return;
        }
        executor.execute(() -> pushStream(actor, req, tx, out));
    }

    private void pushStream(IpdActor actor, AiCopilotReq req, AgUiFrameTranslator tx, AgUiSseSink out) {
        AtomicBoolean terminated = new AtomicBoolean(false);
        try {
            service.chatStream(actor, req, new AiCopilotService.CopilotStreamSink() {
                @Override
                public void meta(AiCopilotResp resp) {
                    out.send(tx.onMeta());
                }

                @Override
                public void delta(String token) {
                    out.send(tx.onDelta(token));
                }

                @Override
                public void done(AiCopilotResp resp) {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    out.send(tx.onDone(doneFrame(resp)));
                    out.complete();
                }

                @Override
                public void error(String code, String message) {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    out.send(tx.onError(code, message));
                    out.complete();
                }
            });
        } catch (IpdBusinessException biz) {
            // 同步前置错误（message 空 / 项目不可见→50001 一类既有语义）：in-band RUN_ERROR 帧
            if (terminated.compareAndSet(false, true)) {
                String code = biz.getErrorCode() == null
                    ? "PARAM_INVALID" : String.valueOf(biz.getErrorCode().getCode());
                out.send(tx.onError(code, biz.getMessage()));
                out.complete();
            }
        } catch (IpdPermissionException pe) {
            if (terminated.compareAndSet(false, true)) {
                out.send(tx.onError(String.valueOf(pe.getErrorCode().getCode()), pe.getErrorCode().getMessage()));
                out.complete();
            }
        } catch (Exception e) {
            if (terminated.compareAndSet(false, true)) {
                out.send(tx.onError("INTERNAL_ERROR", "AI 副驾流式推送失败"));
                out.complete();
            }
        }
    }

    /** done 帧载荷：镜像既有 {@code AiCopilotController} done map（fillPayload 仅非空时加键）。 */
    static Map<String, Object> doneFrame(AiCopilotResp resp) {
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", "ok");
        done.put("tokenPrompt", resp.tokenPrompt());
        done.put("tokenCompletion", resp.tokenCompletion());
        done.put("latencyMs", resp.latencyMs());
        if (resp.fillPayload() != null) {
            done.put("fillPayload", resp.fillPayload());
        }
        return done;
    }

    /**
     * RunAgentInput → AiCopilotReq：最后一条 user 纯文本消息 = message，其余 user/assistant 纯文本 = history；
     * projectId/pageContext 取 context 项（description=键名），fallback forwardedProps 同名键
     * （两者皆官方 RunAgentInput 字段，非发明）。参数违规抛 {@link IllegalArgumentException}（→10001 帧）。
     */
    static AiCopilotReq toCopilotReq(RunAgentInput input) {
        List<RunAgentInput.Message> messages =
            input == null || input.messages() == null ? List.of() : input.messages();
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            RunAgentInput.Message m = messages.get(i);
            if (m != null && "user".equals(m.role()) && m.content() instanceof String s && !s.isBlank()) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) {
            throw new IllegalArgumentException("messages 缺少 user 文本消息");
        }
        String message = (String) messages.get(lastUserIdx).content();
        if (message.length() > MAX_MESSAGE) {
            throw new IllegalArgumentException("message 超长（上限 " + MAX_MESSAGE + "）");
        }
        List<AiCopilotReq.CopilotTurn> history = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (i == lastUserIdx) {
                continue;
            }
            RunAgentInput.Message m = messages.get(i);
            if (m == null || !(m.content() instanceof String s) || s.isBlank()) {
                continue;
            }
            if ("user".equals(m.role()) || "assistant".equals(m.role())) {
                history.add(new AiCopilotReq.CopilotTurn(m.role(), s));
            }
        }
        Long projectId = parseProjectId(input);
        String pageContext = lookupString(input, "pageContext");
        if (pageContext != null && pageContext.length() > MAX_PAGE_CONTEXT) {
            throw new IllegalArgumentException("pageContext 超长（上限 " + MAX_PAGE_CONTEXT + "）");
        }
        return new AiCopilotReq(projectId, message, history, null, pageContext);
    }

    private static Long parseProjectId(RunAgentInput input) {
        Object v = lookup(input, "projectId");
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equals(s)) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("projectId 非法");
        }
    }

    private static String lookupString(RunAgentInput input, String key) {
        Object v = lookup(input, key);
        return v == null ? null : String.valueOf(v);
    }

    /** 契约取值顺序：context 项（description=key）优先，fallback forwardedProps 同名键。 */
    private static Object lookup(RunAgentInput input, String key) {
        if (input.context() != null) {
            for (RunAgentInput.Context c : input.context()) {
                if (c != null && key.equals(c.description())) {
                    return c.value();
                }
            }
        }
        return input.forwardedProps() == null ? null : input.forwardedProps().get(key);
    }

    private static void fail(AgUiSseSink out, AgUiFrameTranslator tx, String code, String message) {
        out.send(tx.onError(code, message));
        out.complete();
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
