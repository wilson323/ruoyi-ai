package org.ruoyi.common.sse.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * SseEmitter 发送生命周期公共支撑（R32 并入）。
 *
 * <p>来源：已下线模块 ruoyi-aiflow（2026-10-02 整模块下线）手写
 * {@code org.ruoyi.workflow.helper.SSEEmitterHelper} 的生命周期部分下沉至此
 * （消灭双套 SSE 管理）；业务事件协议（[NODE_CHUNK_] 多行拆帧、[START]/[DONE]/[ERROR]
 * 帧语义）原样保留。
 *
 * <p>本类治理两个既知缺陷（补遗 §5 R6）：
 * <ul>
 *   <li><b>D4 并发 send 交错</b>：{@link SseEmitter#send} 非线程安全，多节点线程并发推送会
 *       交错/报错。所有发送与收尾按 emitter 实例监视器串行化（per-emitter synchronized），
 *       逻辑消息（{@link #parseAndSendPartialMsg} 整段拆帧）持锁不放，保证同帧流不被插入。</li>
 *   <li><b>D14 收尾二次错误路径</b>：{@link #complete} / {@link #completeWithError} 的异常
 *       只转日志（仅记异常类型，遵循本模块日志边界约定），不再向外抛 RuntimeException
 *       引爆 onError 回调链。</li>
 * </ul>
 *
 * <p>completed 状态机：emitter 一旦收尾/发送失败即标记 completed，后续发送被静默抑制
 * （与原实现一致）；标记表为弱键集合（原实现为 Guava 10 分钟过期缓存），emitter 被 GC 后
 * 条目自动清理，无泄漏。
 *
 * <p>纯 Java/Spring 实现，不引入任何新依赖。
 */
@Slf4j
public final class SseEmitterHelper {

    /**
     * 已收尾（或发送失败置废）的 emitter 标记表。弱键：emitter 无引用后自动清理。
     */
    private static final Set<SseEmitter> COMPLETED_SSE =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private SseEmitterHelper() {
    }

    /**
     * emitter 是否已收尾/置废（已收尾则后续发送会被抑制）
     */
    public static boolean isCompleted(SseEmitter sseEmitter) {
        return sseEmitter != null && COMPLETED_SSE.contains(sseEmitter);
    }

    /**
     * 多行内容按换行拆分逐帧发送（前端折行协议，原样保留）：
     * 首行 {@code " " + lines[0]}，其余行前插 {@code "-_wrap_-"} 帧再发 {@code " " + lines[i]}；
     * 拆分规则 {@code split("[\\r\\n]", -1)}（单字符 \r 或 \n 均为分隔，语义与原实现逐字节一致）。
     * 整段持 per-emitter 锁，多线程并发推送时逻辑消息不交错（D4）。
     *
     * @param sseEmitter 连接对象
     * @param name       事件名（如 [NODE_CHUNK_uuid]），空则发裸数据帧
     * @param content    文本内容
     */
    public static void parseAndSendPartialMsg(SseEmitter sseEmitter, String name, String content) {
        synchronized (sseEmitter) {
            if (isCompleted(sseEmitter)) {
                log.warn("sseEmitter already completed,name:{}", name);
                return;
            }
            String[] lines = content.split("[\\r\\n]", -1);
            if (lines.length > 1) {
                sendPartial(sseEmitter, name, " " + lines[0]);
                for (int i = 1; i < lines.length; i++) {
                    sendPartial(sseEmitter, name, "-_wrap_-");
                    sendPartial(sseEmitter, name, " " + lines[i]);
                }
            } else {
                sendPartial(sseEmitter, name, " " + content);
            }
        }
    }

    /**
     * 发送单帧（data 恒带，不做空白省略）：name 非空白 → 事件名帧，否则裸数据帧。
     * 发送失败（IllegalStateException=已收尾 / IOException=客户端断开）吞掉并置废 emitter，不外抛。
     */
    public static void sendPartial(SseEmitter sseEmitter, String name, String msg) {
        synchronized (sseEmitter) {
            if (isCompleted(sseEmitter)) {
                log.warn("sseEmitter already completed,name:{}", name);
                return;
            }
            try {
                if (isNotBlank(name)) {
                    sseEmitter.send(SseEmitter.event().name(name).data(msg));
                } else {
                    sseEmitter.send(msg);
                }
            } catch (IllegalStateException ise) {
                // SSE连接已关闭（用户刷新页面、关闭标签页或重新提交）
                log.warn("SSE emitter already completed for event [{}], ignoring", name);
                markCompleted(sseEmitter);
            } catch (IOException ioException) {
                log.error("sse_send status=FAILED errorType={}", ioException.getClass().getName());
                markCompleted(sseEmitter);
            }
        }
    }

    /**
     * 发送事件名帧，data 为空白时省略 data 行（[START] 握手帧契约：无 data 时只发事件名）。
     *
     * @return true=帧已交给 emitter；false=已收尾或发送失败（失败已置废 emitter）
     */
    public static boolean sendEvent(SseEmitter sseEmitter, String name, String data) {
        synchronized (sseEmitter) {
            if (isCompleted(sseEmitter)) {
                log.warn("sseEmitter already completed,name:{}", name);
                return false;
            }
            try {
                SseEmitter.SseEventBuilder builder = SseEmitter.event().name(name);
                if (isNotBlank(data)) {
                    builder.data(data);
                }
                sseEmitter.send(builder);
                return true;
            } catch (IllegalStateException ise) {
                log.warn("SSE emitter already completed for event [{}], ignoring", name);
                markCompleted(sseEmitter);
                return false;
            } catch (IOException ioException) {
                log.error("sse_send status=FAILED errorType={}", ioException.getClass().getName());
                markCompleted(sseEmitter);
                return false;
            }
        }
    }

    /**
     * 正常收尾（D14：complete 异常只转日志，不再外抛引爆 onError 二次错误路径）。
     */
    public static void complete(SseEmitter sseEmitter) {
        if (sseEmitter == null) {
            return;
        }
        synchronized (sseEmitter) {
            markCompleted(sseEmitter);
            try {
                sseEmitter.complete();
            } catch (Exception e) {
                log.warn("sse_complete status=IGNORED errorType={}", e.getClass().getName());
            }
        }
    }

    /**
     * 异常收尾（D14 同上：吞异常转日志）。
     */
    public static void completeWithError(SseEmitter sseEmitter, Throwable cause) {
        if (sseEmitter == null) {
            return;
        }
        synchronized (sseEmitter) {
            markCompleted(sseEmitter);
            try {
                sseEmitter.completeWithError(cause);
            } catch (Exception e) {
                log.warn("sse_completeWithError status=IGNORED errorType={}", e.getClass().getName());
            }
        }
    }

    private static void markCompleted(SseEmitter sseEmitter) {
        COMPLETED_SSE.add(sseEmitter);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
