package org.ruoyi.workflow.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R32 aiflow 侧工作流 SSE 会话契约测试（[START]/[DONE]/[ERROR]/[NODE_CHUNK_] 前端契约 +
 * USER_ASKING 互斥键胶水 + D14 收尾不外抛）。
 *
 * <p>生命周期实现已并入 ruoyi-common-sse {@code SseEmitterHelper}（其契约见
 * {@code SseEmitterHelperTest}）；本类钉死 aiflow 胶水层语义：业务事件名、帧序、
 * 互斥键读写形态与收尾单错误路径。
 *
 * <p>mock 合法性三规约（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md）：
 * 无数据库参与；StringRedisTemplate mock 的键形态 {@code user:asking:{userId}}、值 {@code "1"}
 * 与 {@code startSse} 真实写入路径完全一致（同一写入函数产生的形态），未覆盖 DDL 合法性
 * （不涉及表）。RecordingSseEmitter 仅模拟 SseEmitter 收发口现实失败形态。
 */
@Tag("dev")
@DisplayName("R32 aiflow 工作流 SSE 会话契约（业务事件帧 / 互斥键 / D14）")
class WorkflowSseLifecycleContractTest {

    private static final String ASKING_KEY = "user:asking:42";

    private MockedStatic<SpringUtil> springUtil;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        springUtil = Mockito.mockStatic(SpringUtil.class);
        springUtil.when(() -> SpringUtil.getBean(StringRedisTemplate.class)).thenReturn(redisTemplate);
        user = mock(User.class);
        when(user.getId()).thenReturn(42L);
    }

    @AfterEach
    void tearDown() {
        springUtil.close();
    }

    // ======================== 前端事件契约 ========================

    @Test
    @DisplayName("[NODE_CHUNK_<uuid>] 事件名前缀原样保留（前端契约零变更）")
    void nodeChunkEventNameIsPreserved() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        WorkflowNode node = mock(WorkflowNode.class);
        when(node.getUuid()).thenReturn("u1");

        WorkflowMessageUtil.sendEmitterMessage(e, node, "hi");

        assertEquals(1, e.frames().size());
        assertTrue(e.frames().get(0).contains("event:[NODE_CHUNK_u1]"), "事件名前缀不得改名");
        assertTrue(e.frames().get(0).contains("data: hi"));
    }

    @Test
    @DisplayName("sendComplete：[DONE] 帧 → 清互斥键 → complete，帧序与语义原样")
    void sendCompleteEmitsDoneFrameThenCompletesAndClearsAskingKey() {
        RecordingSseEmitter e = new RecordingSseEmitter();

        WorkflowMessageUtil.sendComplete(42L, e, "OUT");

        assertEquals(1, e.frames().size());
        assertTrue(e.frames().get(0).contains("event:[DONE]"), "[DONE] 事件名原样");
        assertTrue(e.frames().get(0).contains("data:OUT"), "data 恒带（空串也不省略）");
        assertEquals(1, e.completeCount.get(), "正常收尾必须 complete");
        verify(redisTemplate).delete(ASKING_KEY);
    }

    @Test
    @DisplayName("sendComplete：已收尾 emitter 不再推第二帧 [DONE]，但仍清互斥键")
    void sendCompleteOnCompletedEmitterSkipsSecondDoneFrame() {
        RecordingSseEmitter e = new RecordingSseEmitter();

        WorkflowMessageUtil.sendComplete(42L, e, "OUT");
        WorkflowMessageUtil.sendComplete(42L, e, "OUT2");

        assertEquals(1, e.frames().size(), "第二帧 [DONE] 被抑制");
        verify(redisTemplate, Mockito.times(2)).delete(ASKING_KEY);
    }

    @Test
    @DisplayName("sendErrorAndComplete：[ERROR] 帧（null 兜底空串）→ 清互斥键 → complete")
    void sendErrorAndCompleteEmitsErrorFrameAndClearsAskingKey() {
        RecordingSseEmitter e = new RecordingSseEmitter();

        WorkflowMessageUtil.sendErrorAndComplete(42L, e, "节点炸了");

        assertEquals(1, e.frames().size());
        assertTrue(e.frames().get(0).contains("event:[ERROR]"), "[ERROR] 事件名原样");
        assertTrue(e.frames().get(0).contains("data:节点炸了"));
        assertEquals(1, e.completeCount.get());
        verify(redisTemplate).delete(ASKING_KEY);

        RecordingSseEmitter n = new RecordingSseEmitter();
        WorkflowMessageUtil.sendErrorAndComplete(42L, n, null);
        assertTrue(n.frames().get(0).contains("event:[ERROR]"));
        assertFalse(n.frames().get(0).contains("data:null"), "null 错误消息兜底为空串，不得出现 data:null");
    }

    @Test
    @DisplayName("startSse：置 USER_ASKING 互斥键（15s TTL）并发 [START] 帧（带 runtime JSON）")
    void startSseSetsAskingKeyWithTtlAndEmitsStartFrame() {
        RecordingSseEmitter e = new RecordingSseEmitter();

        WorkflowMessageUtil.startSse(user, e, "{\"uuid\":\"rt-1\"}");

        verify(valueOps).set(ASKING_KEY, "1", 15, TimeUnit.SECONDS);
        assertEquals(1, e.frames().size());
        assertTrue(e.frames().get(0).contains("event:[START]"));
        assertTrue(e.frames().get(0).contains("data:{\"uuid\":\"rt-1\"}"));
    }

    @Test
    @DisplayName("startSse：data 空白时 [START] 帧不带 data 行（握手帧契约）")
    void startSseWithoutDataOmitsDataFrame() {
        RecordingSseEmitter e = new RecordingSseEmitter();

        WorkflowMessageUtil.startSse(user, e, "");

        assertEquals(1, e.frames().size());
        assertTrue(e.frames().get(0).contains("event:[START]"));
        assertFalse(e.frames().get(0).contains("data:"), "无 data 时不得出现 data 行");
    }

    @Test
    @DisplayName("checkOrComplete：回复中（互斥键在）→ [ERROR]「正在回复中...」收尾并返回 false")
    void checkOrCompleteRejectsWhileAsking() {
        when(valueOps.get(ASKING_KEY)).thenReturn("1");
        RecordingSseEmitter e = new RecordingSseEmitter();

        assertFalse(WorkflowMessageUtil.checkOrComplete(user, e));
        assertEquals(1, e.frames().size());
        assertTrue(e.frames().get(0).contains("event:[ERROR]"));
        assertTrue(e.frames().get(0).contains("data:正在回复中..."));
        assertEquals(1, e.completeCount.get());
        verify(redisTemplate).delete(ASKING_KEY);
    }

    @Test
    @DisplayName("checkOrComplete：空闲（无互斥键）→ 放行且不发任何帧")
    void checkOrCompletePassesWhenIdle() {
        when(valueOps.get(ASKING_KEY)).thenReturn(null);
        RecordingSseEmitter e = new RecordingSseEmitter();

        assertTrue(WorkflowMessageUtil.checkOrComplete(user, e));
        assertTrue(e.payloads.isEmpty());
    }

    // ======================== D14 收尾二次错误路径 ========================

    @Test
    @DisplayName("D14：sendComplete 遇 send 失败（客户端断开）不得外抛 RuntimeException，仍 complete + 清键")
    void sendCompleteNeverThrowsWhenSendFails() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.failWithIOException = true;

        assertDoesNotThrow(() -> WorkflowMessageUtil.sendComplete(42L, e, "OUT"),
            "D14：sendComplete 抛 RuntimeException 会引爆 onError 二次错误路径");
        assertEquals(1, e.completeCount.get(), "send 失败仍要收尾");
        verify(redisTemplate).delete(ASKING_KEY);
    }

    @Test
    @DisplayName("D14：sendComplete 遇 send + complete 双失败（二次错误场景）仍不得外抛")
    void sendCompleteNeverThrowsWhenSendAndCompleteBothFail() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.failWithIOException = true;
        e.completeThrows = true;

        assertDoesNotThrow(() -> WorkflowMessageUtil.sendComplete(42L, e, "OUT"));
        verify(redisTemplate).delete(ASKING_KEY);
    }

    @Test
    @DisplayName("startSse 发送失败：吞异常、错误收尾并清互斥键（单错误路径）")
    void startSseSendFailureIsSwallowedAndClearsAskingKey() {
        RecordingSseEmitter e = new RecordingSseEmitter();
        e.failWithIOException = true;

        assertDoesNotThrow(() -> WorkflowMessageUtil.startSse(user, e, "{}"));
        assertEquals(1, e.completeCount.get(), "失败走 completeWithError 收尾");
        verify(valueOps).set(ASKING_KEY, "1", 15, TimeUnit.SECONDS);
        verify(redisTemplate).delete(ASKING_KEY);
    }

    // ======================== 测试桩（形态同公共模块契约测试，见 mock 合法性说明） ========================

    static class RecordingSseEmitter extends SseEmitter {

        final List<Object> payloads = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger completeCount = new AtomicInteger();
        volatile boolean failWithIOException;
        volatile boolean completeThrows;

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
            if (failWithIOException) {
                throw new IOException("client gone");
            }
            payloads.add(object);
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
