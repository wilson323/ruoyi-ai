package org.ruoyi.common.sse.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R32 公共 SSE 生命周期契约测试（D4 并发串行化 / D14 收尾二次错误 / 帧协议原样保留）。
 *
 * <p>测试边界：调用方与 {@link SseEmitter} 之间——断言「发出的帧序列」（事件名、data、帧序），
 * 即前端契约层；帧文本由 {@link SseEmitter.SseEventBuilder#build()} 展平得到
 * （event:/data: 片段拼接），不依赖 HTTP 层。
 *
 * <p>mock 合法性：无数据库参与；RecordingSseEmitter 只模拟 SseEmitter 收发口的现实行为
 * （send 抛 IOException=客户端断开 / IllegalStateException=已收尾，complete 二次调用抛
 * IllegalStateException），均为真实运行期可达状态，不造不可能的数据组合。
 *
 * <p>红绿对照见 R32 交付记录：故意移除 per-emitter synchronized → D4 两例红；
 * 让 complete 重抛异常 → D14 两例红；改 "-_wrap_-" 分隔符/前导空格 → 协议三例红。
 */
@Tag("dev")
@DisplayName("R32 公共 SSE 生命周期契约（帧协议 / D4 串行化 / D14 收尾吞异常）")
class SseEmitterHelperTest {

    // ======================== 帧协议（前端契约，逐帧原样保留） ========================

    @Test
    @DisplayName("多行内容按 -_wrap_- 折行协议拆帧，事件名逐帧原样保留")
    void multiLineContentUsesWrapSplitProtocol() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        SseEmitterHelper.parseAndSendPartialMsg(e, "[NODE_CHUNK_u1]", "L1\nL2");

        assertEquals(3, e.frames().size(), "两行内容 → 首行 + (-_wrap_- + 次行)");
        assertTrue(e.frames().get(0).contains("event:[NODE_CHUNK_u1]"), "事件名逐帧保留");
        assertTrue(e.frames().get(0).contains("data: L1"), "首行带前导空格");
        assertTrue(e.frames().get(1).contains("data:-_wrap_-"), "折行分隔帧原样保留");
        assertTrue(e.frames().get(2).contains("data: L2"));
    }

    @Test
    @DisplayName("CRLF 按单字符拆分（现状即契约）：'A\\r\\nB' → A / -_wrap_- / 空行 / -_wrap_- / B")
    void crlfSplitKeepsPerCharacterSeparation() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        SseEmitterHelper.parseAndSendPartialMsg(e, "T", "A\r\nB");

        // split("[\\r\\n]", -1) 对 \r\n 是两刀 → ["A", "", "B"]，与原实现逐字节一致
        assertEquals(5, e.frames().size(), "CRLF 两刀拆分语义不得改动（前端按此对齐）");
        assertTrue(e.frames().get(0).contains("data: A"));
        assertTrue(e.frames().get(1).contains("data:-_wrap_-"));
        assertTrue(e.frames().get(2).contains("data: ") && e.frames().get(2).length() < 30);
        assertTrue(e.frames().get(3).contains("data:-_wrap_-"));
        assertTrue(e.frames().get(4).contains("data: B"));
    }

    @Test
    @DisplayName("单行内容只发一帧且保留前导空格")
    void singleLineContentKeepsLeadingSpace() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        SseEmitterHelper.parseAndSendPartialMsg(e, "T", "hello");

        assertEquals(1, e.frames().size());
        assertTrue(e.frames().get(0).contains("data: hello"), "data 前导空格是协议的一部分");
    }

    @Test
    @DisplayName("空白事件名 → 裸数据帧（不带 event: 行）")
    void blankNameSendsRawDataFrame() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        SseEmitterHelper.sendPartial(e, "", "raw");

        assertEquals(1, e.payloads.size());
        assertEquals("raw", e.payloads.get(0), "裸数据帧直接送 String payload");
    }

    @Test
    @DisplayName("sendPartial 的 data 恒带（不做空白省略），sendEvent 空白 data 省略 data 行（[START] 无 data 帧契约）")
    void dataLineSemanticsMatchOriginalContract() {
        RecordingSseEmitter a = new RecordingSseEmitter();
        SseEmitterHelper.sendPartial(a, "T", "");
        assertTrue(a.frames().get(0).contains("data:"), "sendPartial 空串也带 data 行（[DONE]/[ERROR] 契约）");

        RecordingSseEmitter b = new RecordingSseEmitter();
        assertTrue(SseEmitterHelper.sendEvent(b, "[START]", "  "), "空白 data → 只发事件名帧");
        assertFalse(b.frames().get(0).contains("data:"), "[START] 无 data 时不得出现 data 行");
        assertTrue(b.frames().get(0).contains("event:[START]"));

        RecordingSseEmitter c = new RecordingSseEmitter();
        assertTrue(SseEmitterHelper.sendEvent(c, "[START]", "{\"id\":1}"));
        assertTrue(c.frames().get(0).contains("data:{\"id\":1}"), "带 data 时 data 行原样");
    }

    @Test
    @DisplayName("已收尾 emitter 的后续发送被静默抑制（completed 状态机）")
    void completedEmitterSuppressesFurtherSends() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        SseEmitterHelper.complete(e);
        assertTrue(SseEmitterHelper.isCompleted(e));

        SseEmitterHelper.parseAndSendPartialMsg(e, "T", "x");
        SseEmitterHelper.sendPartial(e, "T", "y");
        assertFalse(SseEmitterHelper.sendEvent(e, "[START]", "z"), "已收尾时 sendEvent 返回 false");

        assertTrue(e.payloads.isEmpty(), "已收尾后零发送");
        assertEquals(1, e.completeCount.get(), "complete 只被调一次");
    }

    @Test
    @DisplayName("send 抛 IOException（客户端断开）→ 吞掉并置废，不外抛")
    void sendIOExceptionIsSwallowedAndMarksCompleted() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.failWithIOException = true;

        assertDoesNotThrow(() -> SseEmitterHelper.sendPartial(e, "T", "x"));
        assertTrue(SseEmitterHelper.isCompleted(e), "发送失败即置废");
        assertTrue(e.payloads.isEmpty());

        e.failWithIOException = false;
        SseEmitterHelper.sendPartial(e, "T", "y");
        assertTrue(e.payloads.isEmpty(), "置废后不再尝试发送");
    }

    @Test
    @DisplayName("send 抛 IllegalStateException（已收尾并发）→ 吞掉并置废，不外抛")
    void sendIllegalStateIsSwallowedAndMarksCompleted() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.failWithIllegalState = true;

        assertDoesNotThrow(() -> SseEmitterHelper.sendPartial(e, "T", "x"));
        assertTrue(SseEmitterHelper.isCompleted(e));
    }

    // ======================== D14 收尾二次错误路径 ========================

    @Test
    @DisplayName("D14：complete() 自身抛异常只转日志，不外抛引爆 onError 二次错误路径")
    void completeSwallowsSecondErrorPath() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.completeThrows = true;

        assertDoesNotThrow(() -> SseEmitterHelper.complete(e));
        assertTrue(SseEmitterHelper.isCompleted(e), "收尾后置废，后续发送被抑制");
    }

    @Test
    @DisplayName("D14：completeWithError() 自身抛异常只转日志，不外抛")
    void completeWithErrorSwallowsSecondErrorPath() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.completeThrows = true;

        assertDoesNotThrow(() -> SseEmitterHelper.completeWithError(e, new IOException("boom")));
        assertTrue(SseEmitterHelper.isCompleted(e));
    }

    // ======================== D4 并发 send 串行化 ========================

    @Test
    @DisplayName("D4：同一 emitter 上两个线程的发送永不重叠（per-emitter 串行化）")
    void concurrentSendsToSameEmitterNeverOverlap() throws Exception {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.blockOnNthSend(1);
        try {
            Thread t1 = new Thread(() -> SseEmitterHelper.sendPartial(e, "T", "1"), "t1");
            t1.setDaemon(true);
            t1.start();
            assertTrue(e.blockEntered.await(2, TimeUnit.SECONDS), "t1 应进入发送并停在桩内");

            Thread t2 = new Thread(() -> SseEmitterHelper.sendPartial(e, "T", "2"), "t2");
            t2.setDaemon(true);
            t2.start();
            // t2 若无串行化会在此窗口内闯入 send → maxConcurrent=2
            Thread.sleep(300);

            assertEquals(1, e.maxConcurrent.get(), "同一 emitter 的 send 调用必须串行（D4）");
        } finally {
            e.releaseBlockedSend();
        }
        Thread.sleep(100);
        assertEquals(2, e.payloads.size(), "两帧均送达");
    }

    @Test
    @DisplayName("D4：并发逻辑消息不交错——多帧消息整体持锁，外来单帧不得插入帧序列中间")
    void concurrentLogicalMessagesStayContiguous() throws Exception {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.blockOnNthSend(2);
        try {
            // t1 的三帧消息：L1 / -_wrap_- / L2，卡在第 2 帧
            Thread t1 = new Thread(() -> SseEmitterHelper.parseAndSendPartialMsg(e, "T1", "L1\nL2"), "t1");
            t1.setDaemon(true);
            t1.start();
            assertTrue(e.blockEntered.await(2, TimeUnit.SECONDS));

            Thread t2 = new Thread(() -> SseEmitterHelper.parseAndSendPartialMsg(e, "T2", "X"), "t2");
            t2.setDaemon(true);
            t2.start();
            Thread.sleep(300);
        } finally {
            e.releaseBlockedSend();
        }
        Thread.sleep(100);

        List<String> frames = e.frames();
        assertEquals(4, frames.size(), "t1 三帧 + t2 一帧");
        assertTrue(frames.get(0).contains("event:T1") && frames.get(0).contains("data: L1"));
        assertTrue(frames.get(1).contains("event:T1") && frames.get(1).contains("data:-_wrap_-"), "t1 第 2 帧紧跟第 1 帧，不得被 T2 插入（D4）");
        assertTrue(frames.get(2).contains("event:T1") && frames.get(2).contains("data: L2"));
        assertTrue(frames.get(3).contains("event:T2") && frames.get(3).contains("data: X"), "T2 整帧排在 T1 消息之后");
    }

    // ======================== 测试桩：记录帧序列并模拟现实失败形态 ========================

    /**
     * SseEmitter 收发口记录桩：只模拟真实运行期可达行为（断开抛 IOException、
     * 已收尾抛 IllegalStateException、complete 二次收尾抛 IllegalStateException）。
     * 未覆盖 DDL 合法性——本类不涉及数据库。
     */
    static class RecordingSseEmitter extends SseEmitter {

        final List<Object> payloads = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger completeCount = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        volatile boolean failWithIOException;
        volatile boolean failWithIllegalState;
        volatile boolean completeThrows;

        final CountDownLatch blockEntered = new CountDownLatch(1);
        private final CountDownLatch blockRelease = new CountDownLatch(1);
        private final AtomicBoolean blockConsumed = new AtomicBoolean(false);
        private final AtomicInteger sendCount = new AtomicInteger();
        private volatile int blockOnSendNo = -1;
        private final AtomicInteger inFlight = new AtomicInteger();

        void blockOnNthSend(int n) {
            this.blockOnSendNo = n;
        }

        void releaseBlockedSend() {
            blockRelease.countDown();
        }

        List<String> frames() {
            List<String> result = new ArrayList<>();
            synchronized (payloads) {
                for (Object payload : payloads) {
                    result.add(flatten(payload));
                }
            }
            return result;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            handleSend(builder);
        }

        @Override
        public void send(Object object) throws IOException {
            handleSend(object);
        }

        @Override
        public void send(Object object, MediaType mediaType) throws IOException {
            handleSend(object);
        }

        private void handleSend(Object object) throws IOException {
            int current = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(current, Math::max);
            try {
                if (failWithIOException) {
                    throw new IOException("client gone");
                }
                if (failWithIllegalState) {
                    throw new IllegalStateException("ResponseBodyEmitter has already completed");
                }
                int no = sendCount.incrementAndGet();
                if (no == blockOnSendNo && blockConsumed.compareAndSet(false, true)) {
                    blockEntered.countDown();
                    try {
                        blockRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                payloads.add(object);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public void complete() {
            completeCount.incrementAndGet();
            if (completeThrows) {
                throw new IllegalStateException("already completed");
            }
        }

        @Override
        public void completeWithError(Throwable throwable) {
            completeCount.incrementAndGet();
            if (completeThrows) {
                throw new IllegalStateException("already completed");
            }
        }

        private static String flatten(Object payload) {
            if (payload instanceof SseEventBuilder builder) {
                StringBuilder sb = new StringBuilder();
                for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                    sb.append(part.getData());
                }
                return sb.toString();
            }
            return String.valueOf(payload);
        }
    }
}
