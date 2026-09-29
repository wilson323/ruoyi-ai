package org.ruoyi.chat.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.core.state.VersionedState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * W2 模型层内核接线测试（ADR-0075 矩阵 #8「替换」+ #9「包装」）。
 *
 * <p>被测语义：请求 {@code model} 字段经 {@link KernelModelSelector} 唯一装配点路由
 * （Agent 缓存键含模型注册键，换模型换实例）；默认配置 {@code chat.kernel.agentscope.model-id}
 * 仅用于未选择模型的请求（BLANK_REQUEST）；显式选型装配失败拒绝本轮，不执行默认模型。
 * 装配缝记录 (key, context) 供断言；
 * stub 模型只 mock 输出（合法三规约），状态存储 mock 只隔离（真库由并发/状态验收负责）。
 */
@Tag("dev")
@DisplayName("W2 模型路由：请求模型装配 + 默认回退 + 降级留痕")
class AgentScopeChatKernelModelRoutingTest {

    private static final String DEFAULT_KEY = "minimax:MiniMax-M3";

    @TempDir
    Path workspace;

    /** 记录型装配缝 + 按键可路由的 stub 模型（进入模型即计数，供异步断言）。 */
    private static final class RecordingAssembler implements KernelModelSelector.ModelAssembler {

        private final List<String> keys = new CopyOnWriteArrayList<>();
        private final List<ModelCreationContext> contexts = new CopyOnWriteArrayList<>();
        private final Set<String> failingKeys = ConcurrentHashMap.newKeySet();
        private final Map<String, CountDownLatch> streamed = new ConcurrentHashMap<>();
        private final List<String> executedConfigurations = new CopyOnWriteArrayList<>();

        private CountDownLatch track(String key) {
            return streamed.computeIfAbsent(key, ignored -> new CountDownLatch(1));
        }

        @Override
        public Model assemble(String registryKey, ModelCreationContext context) {
            keys.add(registryKey);
            contexts.add(context);
            if (failingKeys.contains(registryKey)) {
                throw new IllegalStateException("assemble failed: " + registryKey);
            }
            return new LatchModel(registryKey, context.getBaseUrl(), context.getApiKey(), this);
        }
    }

    /** stub 模型：只 mock 输出；进入 stream 即为该注册键计数。 */
    private static final class LatchModel implements Model {

        private final String key;
        private final String endpoint;
        private final String credential;
        private final RecordingAssembler assembler;

        private LatchModel(String key, String endpoint, String credential, RecordingAssembler assembler) {
            this.key = key;
            this.endpoint = endpoint;
            this.credential = credential;
            this.assembler = assembler;
        }

        @Override
        public String getModelName() {
            return key;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            assembler.track(key).countDown();
            assembler.executedConfigurations.add(endpoint + "|" + credential);
            return Flux.just(new ChatResponse("stub-" + UUID.randomUUID(),
                    List.of(TextBlock.builder().text("REPLY-" + key).build()), null, Map.of(), "stop"));
        }
    }

    /** 收尾 latch sink：完成/错误都计数（内容与帧映射由 W1 面测试负责）。 */
    private static final class LatchSink implements KernelChatSink {

        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<String> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onContent(String delta) {
            // 内容断言不在本测试面
        }

        @Override
        public void onReasoning(String delta) {
            // stub 无推理输出
        }

        @Override
        public void onMcpTool(String toolName, String status, String result) {
            // W1/W2 对话内核不挂工具，不应出现
        }

        @Override
        public void onError(String code, String message) {
            errors.add(code);
            finished.countDown();
        }

        @Override
        public void onComplete() {
            finished.countDown();
        }
    }

    /**
     * 状态存储 mock：AgentScope 原生 {@code ReActAgent} 载入状态要求 {@code getVersioned}
     * 非 null（实证 NPE），stub 返回「无状态」VersionedState；存储语义由真库验收负责。
     */
    private static MysqlAgentStateStore absentStateStore() {
        MysqlAgentStateStore store = mock(MysqlAgentStateStore.class);
        doReturn(new VersionedState<>(null, 0L)).when(store).getVersioned(any(), any(), any(), any());
        return store;
    }

    private AgentScopeChatKernel newKernel(RecordingAssembler assembler) {
        return new AgentScopeChatKernel(
                new KernelModelSelector(DEFAULT_KEY, assembler),
                AgentScopeChatKernelModelRoutingTest::absentStateStore,
                workspace);
    }

    @Test
    @DisplayName("请求 model 路由装配且不装默认模型，凭据走 ModelCreationContext")
    void requestModelRoutesWithoutDefaultFallback() throws Exception {
        RecordingAssembler assembler = new RecordingAssembler();
        try (AgentScopeChatKernel kernel = newKernel(assembler)) {
            LatchSink sink = new LatchSink();
            kernel.stream("P1", "U1", "emp-route", "S-T1", "hi", null,
                    new KernelModelRequest("m1", "minimax", "sk-test", "https://api.test/v1"), sink);

            assertTrue(sink.finished.await(30, TimeUnit.SECONDS), "流应收尾");
            assertTrue(sink.errors.isEmpty(), "不应报错: " + sink.errors);
            assertEquals(List.of("minimax:m1"), assembler.keys,
                    "明确选型不得暗中装配默认模型");
            assertEquals("sk-test", assembler.contexts.get(0).getApiKey(), "凭据走 ModelCreationContext");
            assertEquals("https://api.test/v1", assembler.contexts.get(0).getBaseUrl());
            assertTrue(assembler.track("minimax:m1").await(5, TimeUnit.SECONDS), "请求模型必须真正被路由调用");
        }
    }

    @Test
    @DisplayName("换模型换实例：同 agent+提示词、不同请求模型 → 缓存键含模型注册键，不得串用")
    void sameAgentDifferentModelsUseSeparateInstances() throws Exception {
        RecordingAssembler assembler = new RecordingAssembler();
        try (AgentScopeChatKernel kernel = newKernel(assembler)) {
            LatchSink first = new LatchSink();
            kernel.stream("P1", "U1", "emp-route", "S-T2a", "hi", null,
                    new KernelModelRequest("m1", "minimax", null, null), first);
            assertTrue(first.finished.await(30, TimeUnit.SECONDS), "第一路应收尾");
            assertTrue(assembler.track("minimax:m1").await(5, TimeUnit.SECONDS), "m1 应被路由调用");

            LatchSink second = new LatchSink();
            kernel.stream("P1", "U1", "emp-route", "S-T2b", "hi", null,
                    new KernelModelRequest("m2", "minimax", null, null), second);
            assertTrue(second.finished.await(30, TimeUnit.SECONDS), "第二路应收尾");
            assertTrue(assembler.track("minimax:m2").await(5, TimeUnit.SECONDS),
                    "换模型必须装配并调用新模型（缓存键含模型注册键，禁串用旧实例）");
        }
    }

    @Test
    @DisplayName("同名模型端点或凭据 A→B→A 更新后，每轮都执行对应配置")
    void sameModelConfigurationChangeDoesNotReuseStaleAgent() throws Exception {
        RecordingAssembler assembler = new RecordingAssembler();
        try (AgentScopeChatKernel kernel = newKernel(assembler)) {
            KernelModelRequest a = new KernelModelRequest("m1", "minimax", "test-key-a", "https://a.test/v1");
            KernelModelRequest b = new KernelModelRequest("m1", "minimax", "test-key-b", "https://b.test/v1");
            for (int i = 0; i < 3; i++) {
                LatchSink sink = new LatchSink();
                kernel.stream("P1", "U1", "emp-route", "S-C" + i, "hi", null, i == 1 ? b : a, sink);
                assertTrue(sink.finished.await(30, TimeUnit.SECONDS), "第 " + i + " 轮应收尾");
                assertTrue(sink.errors.isEmpty(), "第 " + i + " 轮不应失败: " + sink.errors);
            }
            assertEquals(List.of("https://a.test/v1|test-key-a", "https://b.test/v1|test-key-b",
                    "https://a.test/v1|test-key-a"), assembler.executedConfigurations,
                    "配置更新后必须调用该轮的端点和凭据，回切也必须一致");
        }
    }

    @Test
    @DisplayName("W1 兼容面：7 参 stream 委托默认模型（model-id 配置），不触请求键")
    void sevenArgStreamRoutesToDefaultModelOnly() throws Exception {
        RecordingAssembler assembler = new RecordingAssembler();
        try (AgentScopeChatKernel kernel = newKernel(assembler)) {
            LatchSink sink = new LatchSink();
            kernel.stream("P1", "U1", "emp-route", "S-T3", "hi", null, sink);

            assertTrue(sink.finished.await(30, TimeUnit.SECONDS), "流应收尾");
            assertTrue(sink.errors.isEmpty(), "不应报错: " + sink.errors);
            assertEquals(List.of(DEFAULT_KEY), assembler.keys, "7 参路径只装配默认模型（BLANK_REQUEST 留痕）");
            assertTrue(assembler.track(DEFAULT_KEY).await(5, TimeUnit.SECONDS), "默认模型应被调用");
        }
    }

    @Test
    @DisplayName("空 model 字段（from(null,null)）→ 降级默认模型续跑，降级路径有记录")
    void blankRequestModelDegradesToDefault() throws Exception {
        RecordingAssembler assembler = new RecordingAssembler();
        try (AgentScopeChatKernel kernel = newKernel(assembler)) {
            LatchSink sink = new LatchSink();
            kernel.stream("P1", "U1", "emp-route", "S-T4", "hi", null,
                    KernelModelRequest.from(null, null), sink);

            assertTrue(sink.finished.await(30, TimeUnit.SECONDS), "流应收尾");
            assertTrue(sink.errors.isEmpty(), "空请求模型不得报错: " + sink.errors);
            assertEquals(List.of(DEFAULT_KEY), assembler.keys, "空请求 → 默认模型（BLANK_REQUEST）");
            assertTrue(assembler.track(DEFAULT_KEY).await(5, TimeUnit.SECONDS), "降级后默认模型应被调用");
        }
    }

    @Test
    @DisplayName("请求模型装配失败 → 安全错误且默认模型绝不执行")
    void assembleFailureFailsClosedWithoutDefaultModel() throws Exception {
        RecordingAssembler assembler = new RecordingAssembler();
        assembler.failingKeys.add("bad:m1");
        try (AgentScopeChatKernel kernel = newKernel(assembler)) {
            LatchSink sink = new LatchSink();
            kernel.stream("P1", "U1", "emp-route", "S-T5", "hi", null,
                    new KernelModelRequest("m1", "bad", null, null), sink);

            assertTrue(sink.finished.await(30, TimeUnit.SECONDS), "错误应结束当前流");
            assertEquals(1, sink.errors.size(), "必须向调用方显式报告模型错误");
            assertEquals(List.of("bad:m1"), assembler.keys,
                    "明确选型失败后绝不装配默认模型");
        }
    }

    @Test
    @DisplayName("W2 回滚点：模型路由开关关 → 忽略请求 model 字段（W1 静态 model-id 行为）")
    void routingSwitchOffIgnoresRequestModel() throws Exception {
        RecordingAssembler assembler = new RecordingAssembler();
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(
                new KernelModelSelector(DEFAULT_KEY, assembler),
                false,
                AgentScopeChatKernelModelRoutingTest::absentStateStore,
                workspace)) {
            LatchSink sink = new LatchSink();
            kernel.stream("P1", "U1", "emp-route", "S-T6", "hi", null,
                    new KernelModelRequest("m1", "minimax", null, null), sink);

            assertTrue(sink.finished.await(30, TimeUnit.SECONDS), "流应收尾");
            assertTrue(sink.errors.isEmpty(), "不应报错: " + sink.errors);
            assertEquals(List.of(DEFAULT_KEY), assembler.keys,
                    "路由开关关 → 请求 model 不路由（回退 W1 静态 model-id）");
            assertTrue(assembler.track(DEFAULT_KEY).await(5, TimeUnit.SECONDS), "默认模型应被调用");
        }
    }
}
