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

    /** 与 ProjectAgentTemporaryStateStoreTest.Sink 同构的最小事件接收器（private 不可跨类引用）。 */
    private static class Sink implements ProjectAgentEventSink {
        final CountDownLatch done = new CountDownLatch(1); final List<String> errors = new CopyOnWriteArrayList<>();
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
        public void onError(String error) { errors.add(error); done.countDown(); }
        public void onComplete() { lifecycle.onComplete(); assertTrue(lifecycle.isClosed()); complete = true; done.countDown(); }
    }
}
