package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.state.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LTM v2 触发链端到端：middleware 装配 → onAgent → concatWith → record 落 mapper。 */
@Tag("dev")
class ProjectAgentLongTermMemoryMiddlewareIT {
    @TempDir Path workspace;

    @Test void kernelExecuteTriggersLongTermMemoryRecordThroughMiddleware() throws Exception {
        var inserts = new AtomicInteger();
        var insertedContent = new CopyOnWriteArrayList<String>();
        org.ruoyi.ipd.mapper.IpdAgentMemoryMapper mapper =
            mock(org.ruoyi.ipd.mapper.IpdAgentMemoryMapper.class);
        when(mapper.insertIgnoreDuplicate(any())).thenAnswer(inv -> {
            inserts.incrementAndGet();
            insertedContent.add(((org.ruoyi.ipd.domain.IpdAgentMemory) inv.getArgument(0)).getContent());
            return 1;
        });
        when(mapper.recallForScope(anyLong(), anyLong(), anyInt())).thenReturn(List.of());

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test");
        // 主链回复与 LTM 抽取共用同一 mock：抽取输出必须含 MEM| 行才会落库。
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(ChatResponse.builder()
            .finishReason("stop")
            .content(List.of(
                ThinkingBlock.builder().thinking("private reasoning").build(),
                TextBlock.builder().text("MEM|PREFERENCE|结论先行简明格式").build()))
            .build()));

        var kernel = new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler((key, context) -> model),
            (p, d, q) -> new RetrievalContext(0, 0, ""), workspace, 2);
        kernel.setStateStore(new InMemoryAgentStateStore());
        kernel.setLongTermMemoryMapper(mapper);

        var sink = new Sink();
        var execution = kernel.execute(new ProjectAgentRunSpec(1234567L, 9L, "tenant", 7L, null,
            "question", List.of(), List.of(),
            new KernelModelRequest("test", "openai", "test", "https://example.invalid/v1"),
            Duration.ofSeconds(30)), sink);
        try {
            assertTrue(sink.done.await(40, TimeUnit.SECONDS), "run must finish");
            assertTrue(sink.complete, sink.errors.toString());
            // 成功终态必须发生在记忆入库之后；不轮询掩盖后台写入。
            assertEquals(1, inserts.get(), "middleware 链应触发 record 并写入 ipd_agent_memory");
            assertTrue(insertedContent.get(0).contains("结论先行"));
        } finally { execution.dispose(); }
    }

    /**
     * 原故障 2106378468009717761 的整链对照：主链正常交付答案，<b>只有记忆抽取那一次模型调用失败</b>。
     *
     * <p>旧实现下这会让 concatWith 把错误抛进主流 → sink.onError(STREAM_ERROR) → 整轮 FAILED，
     * 而正文与 TEXT_MESSAGE_END 早已推送。这里断言的是<b>整轮业务终态</b>，不是 record() 的返回值——
     * 这正是单元测试覆盖不到、而这里必须覆盖的那一层。
     *
     * <p>抽取与主链共用同一个 Model 实例（AgentScopeProjectAgentKernel:490-494 vs :519），
     * 因此只能按<b>提示词内容</b>区分：抽取提示词固定包含 "MEM|KIND|CONTENT"。这与真实装配一致。
     */
    @Test void memoryExtractionFailureDoesNotFlipBusinessTerminalState() throws Exception {
        org.ruoyi.ipd.mapper.IpdAgentMemoryMapper mapper =
            mock(org.ruoyi.ipd.mapper.IpdAgentMemoryMapper.class);
        when(mapper.insertIgnoreDuplicate(any())).thenReturn(1);
        when(mapper.recallForScope(anyLong(), anyLong(), anyInt())).thenReturn(List.of());

        var extractionCalls = new AtomicInteger();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test");
        when(model.stream(any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<io.agentscope.core.message.Msg> msgs = invocation.getArgument(0);
            // Msg.toString() 不含文本块内容，必须真正解析 content，否则检测永远不命中（本测试会假绿）。
            var prompt = new StringBuilder();
            if (msgs != null) {
                for (var m : msgs) {
                    if (m == null || m.getContent() == null) continue;
                    for (var block : m.getContent()) {
                        if (block instanceof io.agentscope.core.message.TextBlock t && t.getText() != null) {
                            prompt.append(t.getText());
                        }
                    }
                }
            }
            if (prompt.indexOf("MEM|KIND|CONTENT") >= 0) {
                extractionCalls.incrementAndGet();
                // 真实故障形态：流已建立但再无数据，靠空闲期限被掐断。
                return Flux.concat(Flux.just(ChatResponse.builder().finishReason("stop")
                        .content(List.of(TextBlock.builder().text("MEM|").build())).build()),
                    Flux.never());
            }
            return Flux.just(ChatResponse.builder().finishReason("stop")
                .content(List.of(TextBlock.builder().text("运行验收连接正常。").build())).build());
        });

        var kernel = new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler((key, context) -> model),
            (p, d, q) -> new RetrievalContext(0, 0, ""), workspace, 2);
        kernel.setStateStore(new InMemoryAgentStateStore());
        kernel.setLongTermMemoryMapper(mapper);

        var sink = new Sink();
        var execution = kernel.execute(new ProjectAgentRunSpec(7654321L, 9L, "tenant", 7L, null,
            "问题", List.of(), List.of(),
            new KernelModelRequest("test", "openai", "test", "https://example.invalid/v1"),
            // 必须 > 记忆最坏耗时（2×10s + 0.5s = 20.5s），否则整轮先被运行期限掐断，
            // 那就变成在测运行超时而不是测记忆失败不改判终态。
            Duration.ofSeconds(90)), sink);
        try {
            assertTrue(sink.done.await(80, TimeUnit.SECONDS), "run must finish");
            assertTrue(sink.complete, "业务终态必须是成功而非失败，errors=" + sink.errors);
            assertTrue(sink.errors.isEmpty(),
                "记忆失败不得产生任何 onError，原故障正是这里报了 STREAM_ERROR，实际 errors=" + sink.errors);
            assertTrue(extractionCalls.get() > 0, "必须真的走到记忆抽取，否则本测试是假绿");
            assertEquals(1, sink.receipts.size(), "应恰好写一条记忆回执，实际=" + sink.receipts);
            var receipt = sink.receipts.get(0);
            assertEquals("WRITE_FAILED", receipt.get("status"), "回执状态");
            assertEquals(Boolean.TRUE, receipt.get("retryable"), "超时属可重试，必须允许补写");
            assertEquals(0, receipt.get("saved"), "失败时不得有任何入库");
            verify(mapper, never()).insertIgnoreDuplicate(any());
        } finally { execution.dispose(); }
    }

    /** 与 ProjectAgentTemporaryStateStoreTest.Sink 同构的最小事件接收器（private 不可跨类引用）。 */
    private static class Sink implements ProjectAgentEventSink {
        final CountDownLatch done = new CountDownLatch(1); final List<String> errors = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> receipts = new CopyOnWriteArrayList<>();
        volatile boolean complete;
        final org.ruoyi.ipd.agent.service.ProjectAgentRunHandle lifecycle = org.ruoyi.ipd.agent.support.AgentKernelTestLifecycle.create();
        public void registerTerminalSuccessReceipt(Runnable receipt) { lifecycle.registerTerminalSuccessReceipt(receipt); }
        public void registerTemporaryStateCleanup(Runnable cleanup) { lifecycle.registerTemporaryStateCleanup(cleanup); }
        public void releaseTemporaryState() { lifecycle.releaseTemporaryState(); }
        public void onStep(String kind, Map<String, Object> detail) { }
        public void onToolCall(String id, String name) { }
        public void onToolResult(String id, String name, String result) { }
        public void onSource(Map<String, Object> source) { }
        public void onText(String text) { lifecycle.onText(text); }
        public void onArtifact(String id, String title, String hash, int version) { }
        public void onMemoryReceipt(Map<String, Object> receipt) { receipts.add(receipt); }
        public void onError(String error) { errors.add(error); done.countDown(); }
        public void onComplete() { lifecycle.onComplete(); assertTrue(lifecycle.isClosed()); complete = true; done.countDown(); }
    }
}
