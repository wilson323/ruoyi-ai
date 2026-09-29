package org.ruoyi.chat.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.chat.poc.kernel.PocKernelSupport;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1 并发合同：桥接 streamEvents 使用 SDK LocalSessionTurnGate。
 * 修改前同键流式实测 maxActive=2；修复后要求1，异键仍要求2。
 * 模型只模拟输出，状态使用隔离PoC真库，数据按项目政策保留。
 * W1关闭未纳入预算与隔离验收的异步记忆钩子。
 */
@Tag("dev")
@DisplayName("W1 并发负例：同键串行化 / 异键并行（§6.3-5）")
class AgentScopeKernelConcurrencyPocIT {

    private static final String RUN = "R" + Long.toString(System.nanoTime(), 36);

    private static DataSource dataSource;

    @BeforeAll
    static void setUp() {
        dataSource = PocKernelSupport.pocDataSource();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    @DisplayName("同 (userId, sessionId) 两并发 call() → 内核原生串行化：模型调用零重叠")
    void sameScopeCallsAreSerializedByKernel() throws Exception {
        ProbeModel probe = new ProbeModel(500L, 150L);
        KernelScopeKey.Scope scope = KernelScopeKey.of("P1", "U1", "emp-cy", "SC1-" + RUN);
        try (HarnessAgent agent = newAgent(probe)) {
            CountDownLatch done = new CountDownLatch(2);
            runConcurrently(done,
                () -> {
                    callQuietly(agent, scope, "CY-CALL-A-" + RUN);
                    done.countDown();
                },
                () -> {
                    callQuietly(agent, scope, "CY-CALL-B-" + RUN);
                    done.countDown();
                });
            assertTrue(done.await(60, TimeUnit.SECONDS), "两个同键 call 都应在超时内收尾");

            assertEquals(1, probe.maxActive.get(),
                "同键并发必须串行化（call 路径，模型调用应零重叠），实测最大重叠=" + probe.maxActive.get());
        }
    }

    @Test
    @DisplayName("不同 sessionId 两并发 streamEvents（W1 桥）→ 实测并行：模型调用在栅栏处会师（重叠=2）")
    void differentSessionsRunInParallel() throws Exception {
        ProbeModel probe = new ProbeModel(10_000L, 150L);
        try (AgentScopeChatKernel kernel = newKernel(probe)) {
            RecordingSink sinkA = new RecordingSink();
            RecordingSink sinkB = new RecordingSink();

            CountDownLatch done = new CountDownLatch(2);
            runConcurrently(done,
                () -> kernel.stream("P1", "U1", "emp-cy", "SC2A-" + RUN, "say A", null,
                        new LatchSink(sinkA, done)),
                () -> kernel.stream("P1", "U1", "emp-cy", "SC2B-" + RUN, "say B", null,
                        new LatchSink(sinkB, done)));
            assertTrue(done.await(60, TimeUnit.SECONDS), "两个异键请求都应在超时内收尾");

            assertTrue(probe.rendezvousSatisfied(),
                "异键并发必须并行（两次模型调用须在 10s 栅栏内会师），计数=" + probe.arrived.getCount());
            assertEquals(2, probe.maxActive.get(),
                "异键并发应实测重叠=2，实测最大重叠=" + probe.maxActive.get());
            assertFalse(sinkA.text().contains("say B"), "A 输出不得混入 B 内容");
            assertFalse(sinkB.text().contains("say A"), "B 输出不得混入 A 内容");
        }
    }

    @Test
    @DisplayName("同键两并发 streamEvents 必须串行化（maxActive=1）")
    void sameScopeStreamsAreSerialized() throws Exception {
        ProbeModel probe = new ProbeModel(500L, 150L);
        try (AgentScopeChatKernel kernel = newKernel(probe)) {
            RecordingSink sinkA = new RecordingSink();
            RecordingSink sinkB = new RecordingSink();
            String markerA = "CY-STR-A-" + RUN;
            String markerB = "CY-STR-B-" + RUN;

            CountDownLatch done = new CountDownLatch(2);
            runConcurrently(done,
                () -> kernel.stream("P1", "U1", "emp-cy", "SC3-" + RUN, "remember " + markerA, "Aa",
                        new LatchSink(sinkA, done)),
                () -> kernel.stream("P1", "U1", "emp-cy", "SC3-" + RUN, "remember " + markerB, "BB",
                        new LatchSink(sinkB, done)));
            assertTrue(done.await(60, TimeUnit.SECONDS), "两个同键流式请求都应在超时内收尾");

            // 业务合同：同一作用域的流式轮次不得重叠。
            assertEquals(1, probe.maxActive.get(), "同键流式请求必须串行，避免状态互相覆盖");
            // 输出仍须各归各（无交错污染），这层由 (userId,sessionId) 状态分桶保证
            assertFalse(sinkA.text().contains(markerB), "A 输出不得混入 B 内容（交错写坏）");
            assertFalse(sinkB.text().contains(markerA), "B 输出不得混入 A 内容（交错写坏）");
        }
    }

    @Test
    @DisplayName("失败轮次释放原生门，后续同键请求可完成")
    void failedStreamReleasesGate() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        ProbeModel delegate = new ProbeModel(1L, 1L);
        Model model = new Model() {
            @Override public String getModelName() { return "stub-failure-recovery"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return failing.get() ? Flux.error(new IllegalStateException("SECRET_CANARY"))
                        : delegate.stream(messages, tools, options);
            }
        };
        try (AgentScopeChatKernel kernel = newKernel(model)) {
            RecordingSink first = new RecordingSink();
            CountDownLatch failed = new CountDownLatch(1);
            kernel.stream("P1", "U1", "emp-cy", "REC-" + RUN, "first", null, new LatchSink(first, failed));
            assertTrue(failed.await(30, TimeUnit.SECONDS));
            assertEquals(List.of("KERNEL_STREAM_ERROR"), first.errors);
            failing.set(false);
            RecordingSink next = new RecordingSink();
            CountDownLatch completed = new CountDownLatch(1);
            kernel.stream("P1", "U1", "emp-cy", "REC-" + RUN, "second", null, new LatchSink(next, completed));
            assertTrue(completed.await(30, TimeUnit.SECONDS), "失败后同键门必须释放");
            assertTrue(next.errors.isEmpty());
            assertTrue(next.text().contains("second"));
        }
    }

    @Test
    @DisplayName("取消运行中的流须取消模型并释放同键门")
    void cancellationReleasesGate() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        Model model = new Model() {
            @Override public String getModelName() { return "stub-cancellation"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (first.getAndSet(false)) {
                    return Flux.<ChatResponse>never()
                            .doOnSubscribe(ignored -> started.countDown())
                            .doOnCancel(cancelled::countDown);
                }
                return Flux.just(new ChatResponse("stub-cancel-next",
                        List.of(TextBlock.builder().text("NEXT_TURN").build()), null, Map.of(), "stop"));
            }
        };
        try (AgentScopeChatKernel kernel = newKernel(model)) {
            String sessionId = "CANCEL-" + RUN;
            Disposable active = kernel.stream("P1", "U1", "emp-cy", sessionId,
                    "first", null, new RecordingSink());
            assertTrue(started.await(30, TimeUnit.SECONDS), "首轮应进入模型");
            active.dispose();
            assertTrue(cancelled.await(30, TimeUnit.SECONDS), "取消须传到模型订阅");
            RecordingSink next = new RecordingSink();
            CountDownLatch done = new CountDownLatch(1);
            kernel.stream("P1", "U1", "emp-cy", sessionId,
                    "second", null, new LatchSink(next, done));
            assertTrue(done.await(30, TimeUnit.SECONDS), "取消后同键门须释放");
            assertTrue(next.errors.isEmpty());
            assertTrue(next.text().contains("NEXT_TURN"));
        }
    }

    @Test
    @DisplayName("同 slot 提示词 A→B→A 后模型输入与真库均保留三轮历史")
    void promptConfigurationSwitchPreservesHistory() throws Exception {
        String sessionId = "PROMPT-SWITCH-" + RUN;
        String[] markers = {"FIRST-" + RUN, "SECOND-" + RUN, "THIRD-" + RUN};
        List<List<String>> observedInputs = new ArrayList<>();
        Model model = new Model() {
            @Override public String getModelName() { return "stub-prompt-switch"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                List<String> snapshot = messages.stream().map(Msg::getTextContent)
                        .filter(text -> text != null && !text.isBlank()).toList();
                observedInputs.add(snapshot);
                return Flux.just(new ChatResponse("stub-" + UUID.randomUUID(),
                        List.of(TextBlock.builder().text("REPLY-" + observedInputs.size() + "-" + RUN).build()),
                        null, Map.of(), "stop"));
            }
        };
        try (AgentScopeChatKernel kernel = newKernel(model)) {
            String[] prompts = {"A", "B", "A"};
            for (int i = 0; i < markers.length; i++) {
                RecordingSink sink = new RecordingSink();
                CountDownLatch done = new CountDownLatch(1);
                kernel.stream("P1", "U1", "emp-cy", sessionId,
                        markers[i], prompts[i], new LatchSink(sink, done));
                assertTrue(done.await(30, TimeUnit.SECONDS), "第" + (i + 1) + "轮必须完成");
                assertTrue(sink.errors.isEmpty());
                assertTrue(sink.text().contains("REPLY-" + (i + 1) + "-" + RUN));
            }
            assertEquals(3, observedInputs.size());
            String thirdInput = String.join("\n", observedInputs.get(2));
            assertTrue(thirdInput.contains(markers[0]), "第三轮模型输入应含第一轮用户消息");
            assertTrue(thirdInput.contains(markers[1]), "第三轮模型输入应含第二轮用户消息");
            assertTrue(thirdInput.contains("REPLY-1-" + RUN), "第三轮模型输入应含第一轮助手消息");
            assertTrue(thirdInput.contains("REPLY-2-" + RUN), "第三轮模型输入应含第二轮助手消息");
        }
        String slotId = KernelScopeKey.of("P1", "U1", "emp-cy", sessionId).slotId();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state_data FROM ipd_poc.agentscope_sessions"
                             + " WHERE session_id = ? AND state_key = 'agent_state'")) {
            statement.setString(1, slotId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "最终状态必须落在目标 slot");
                String stored = rows.getString(1);
                for (String marker : markers) {
                    assertTrue(stored.contains(marker), "真库状态缺少轮次 " + marker);
                }
                assertFalse(rows.next(), "目标 slot 不应有重复 agent_state 行");
            }
        }
    }

    private static void runConcurrently(CountDownLatch done, Runnable first, Runnable second) {
        new Thread(first, "kernel-concurrency-1").start();
        new Thread(second, "kernel-concurrency-2").start();
    }

    private static void callQuietly(HarnessAgent agent, KernelScopeKey.Scope scope, String marker) {
        try {
            agent.call(
                    Msg.builder().role(MsgRole.USER).textContent("remember " + marker).build(),
                    scope.toRuntimeContext())
                .block();
        } catch (Exception ignored) {
            // 探针只测并发形态，回复内容不断言
        }
    }

    private static HarnessAgent newAgent(Model model) throws Exception {
        Path workspace = Files.createTempDirectory("kernel-concurrency-agent-" + RUN + "-");
        return HarnessAgent.builder()
                .name("emp-cy")
                .sysPrompt("You are a note-taking assistant.")
                .model(model)
                .workspace(workspace)
                .stateStore(newStateStore())
                // 原生开关：关掉记忆/日账后台钩子（其附加模型调用异步脱离 call 串行门，
                // 会污染「主链零重叠」探针）。主链串行化语义由此单独暴露（成熟方案优先，用原生配置）。
                .disableMemoryHooks()
                .build();
    }

    private static AgentScopeChatKernel newKernel(Model model) throws Exception {
        Path workspace = Files.createTempDirectory("kernel-concurrency-" + RUN + "-");
        return new AgentScopeChatKernel(model, "stub:concurrency-probe",
                AgentScopeKernelConcurrencyPocIT::newStateStore, workspace);
    }

    private static MysqlAgentStateStore newStateStore() {
        return new MysqlAgentStateStore(dataSource, PocKernelSupport.POC_DATABASE,
                PocKernelSupport.STATE_TABLE, false);
    }

    /**
     * 并发探针 stub 模型：只 mock 输出（合法三规约）。进入模型即计活跃数；
     * 「到达栅栏」等待第二个调用会师（限时），用于区分串行（不会师）与并行（会师）。
     */
    private static final class ProbeModel implements Model {

        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final CountDownLatch arrived = new CountDownLatch(2);
        private final long rendezvousMillis;
        private final long workMillis;

        private ProbeModel(long rendezvousMillis, long workMillis) {
            this.rendezvousMillis = rendezvousMillis;
            this.workMillis = workMillis;
        }

        private boolean rendezvousSatisfied() {
            return arrived.getCount() == 0;
        }

        @Override
        public String getModelName() {
            return "stub-concurrency-probe";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                arrived.countDown();
                try {
                    arrived.await(rendezvousMillis, TimeUnit.MILLISECONDS);
                    Thread.sleep(workMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String lastUser = "";
                for (Msg m : messages) {
                    if (m.getTextContent() != null && !m.getTextContent().isBlank()) {
                        lastUser = m.getTextContent();
                    }
                }
                String reply = "STUB-REPLY[" + lastUser + "]";
                return Flux.just(new ChatResponse(
                        "stub-" + UUID.randomUUID(),
                        List.of(TextBlock.builder().text(reply).build()),
                        null,
                        Map.of(),
                        "stop"));
            }).doFinally(signal -> active.decrementAndGet());
        }
    }

    /** 落地帧记录：content 拼接 + 错误码留痕。 */
    private static final class RecordingSink implements KernelChatSink {

        private final StringBuilder content = new StringBuilder();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void onContent(String delta) {
            synchronized (content) {
                content.append(delta);
            }
        }

        @Override
        public void onReasoning(String delta) {
            // 契约帧留白：stub 无推理输出
        }

        @Override
        public void onMcpTool(String toolName, String status, String result) {
            // W1 对话内核不挂工具，不应出现
        }

        @Override
        public void onError(String code, String message) {
            errors.add(code);
        }

        private String text() {
            synchronized (content) {
                return content.toString();
            }
        }

        @Override
        public void onComplete() {
            // 由 LatchSink 收尾
        }
    }

    /** 收尾信号：complete/error 各计一次（done latch）。 */
    private static final class LatchSink implements KernelChatSink {

        private final RecordingSink delegate;
        private final CountDownLatch done;

        private LatchSink(RecordingSink delegate, CountDownLatch done) {
            this.delegate = delegate;
            this.done = done;
        }

        @Override
        public void onContent(String delta) {
            delegate.onContent(delta);
        }

        @Override
        public void onReasoning(String delta) {
            delegate.onReasoning(delta);
        }

        @Override
        public void onMcpTool(String toolName, String status, String result) {
            delegate.onMcpTool(toolName, status, result);
        }

        @Override
        public void onError(String code, String message) {
            delegate.onError(code, message);
            done.countDown();
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
            done.countDown();
        }
    }
}
