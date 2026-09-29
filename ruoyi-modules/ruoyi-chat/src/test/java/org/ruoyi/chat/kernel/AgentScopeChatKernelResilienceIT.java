package org.ruoyi.chat.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.poc.kernel.PocKernelSupport;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 韧性补测：并发目录替换、双副本、崩溃恢复（对照 C1 验收缺口 grep 实证：三类此前零测试）。
 * <p>模型只 mock 输出、状态使用隔离 PoC 真库（合法三规约），数据按测试数据政策留库。
 * <p>已知边界诚实披露：JVM 级整轮锁保证同进程多实例同 slot 互斥；跨进程双副本仍待分布式锁，
 * 本测试以同 JVM 双实例模拟双副本互不污染语义，不得外推为跨 JVM 结论。
 */
@Tag("dev")
@DisplayName("C1 韧性补测：并发目录替换 / 双副本 / 崩溃恢复")
class AgentScopeChatKernelResilienceIT {

    private static final String RUN = "RS" + Long.toString(System.nanoTime(), 36);

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
    @DisplayName("并发目录替换：同键双线程分桶恰一次 + 段被换成符号链接后下一轮 fail-closed 拒绝")
    void concurrentDirectoryCreationAndSymlinkReplacement() throws Exception {
        String sessionId = "RACE-" + RUN;
        Path root = Files.createTempDirectory("kernel-resilience-RACE-" + RUN + "-");
        try (AgentScopeChatKernel kernel = newKernel(new EchoModel("RACE"), root)) {
            CountDownLatch done = new CountDownLatch(2);
            RecordingSink sinkA = new RecordingSink();
            RecordingSink sinkB = new RecordingSink();
            runConcurrently(done,
                () -> kernel.stream("PR", "UR", "emp-rs", sessionId, "first " + RUN, null,
                        new LatchSink(sinkA, done)),
                () -> kernel.stream("PR", "UR", "emp-rs", sessionId, "second " + RUN, null,
                        new LatchSink(sinkB, done)));
            assertTrue(done.await(60, TimeUnit.SECONDS), "同键双线程都应收尾");
            assertTrue(sinkA.errors.isEmpty() && sinkB.errors.isEmpty(),
                "并发创建同一分桶不得报错（CREATE_NEW 竞争应被容忍），errors=" + errors(sinkA, sinkB));
        }
        Path bucket = root.resolve("PR").resolve("UR").resolve("emp-rs");
        assertTrue(Files.isDirectory(bucket), "分桶应恰创建一次");
        assertTrue(Files.isRegularFile(bucket.resolve("AGENTS.md")), "AGENTS.md 应为常规文件");

        // 对抗性替换：轮间把已存在的中间段换成符号链接（模拟目录替换攻击：攻击者删段再挂链）。
        Path personSegment = bucket.getParent();
        Path outside = root.resolve("outside-" + RUN);
        Files.createDirectories(outside);
        deleteRecursively(personSegment);
        Files.createSymbolicLink(personSegment, outside);
        try (AgentScopeChatKernel kernel = newKernel(new EchoModel("RACE2"), root)) {
            RecordingSink sink = new RecordingSink();
            CountDownLatch done = new CountDownLatch(1);
            kernel.stream("PR", "UR", "emp-rs", sessionId, "after-replace", null,
                    new LatchSink(sink, done));
            assertTrue(done.await(30, TimeUnit.SECONDS), "替换后请求必须立即收尾（拒绝或完成）");
            assertEquals(List.of("KERNEL_ERROR"), sink.errors,
                "段被替换成符号链接必须 fail-closed 拒绝（装配期拒绝=KERNEL_ERROR，不触状态与模型）");
            try (var entries = Files.list(outside)) {
                assertTrue(entries.findAny().isEmpty(), "受信根外不得写入任何工作区文件");
            }
        }
    }

    @Test
    @DisplayName("双副本：两实例共享真库状态、同键并发不崩溃且互不污染，目标 slot 单行原子")
    void twoReplicasShareStateStoreWithoutCorruption() throws Exception {
        String sessionId = "DUAL-" + RUN;
        MarkerModel modelA = new MarkerModel("REPLICA-A-" + RUN);
        MarkerModel modelB = new MarkerModel("REPLICA-B-" + RUN);
        CountDownLatch done = new CountDownLatch(2);
        RecordingSink sinkA = new RecordingSink();
        RecordingSink sinkB = new RecordingSink();
        runConcurrently(done,
            () -> {
                try (AgentScopeChatKernel kernel = newKernel(modelA)) {
                    kernel.stream("PU", "UU", "emp-rs", sessionId, "from-" + modelA.marker, null,
                            new LatchSink(sinkA, done));
                    done.await(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    done.countDown();
                }
            },
            () -> {
                try (AgentScopeChatKernel kernel = newKernel(modelB)) {
                    kernel.stream("PU", "UU", "emp-rs", sessionId, "from-" + modelB.marker, null,
                            new LatchSink(sinkB, done));
                    done.await(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    done.countDown();
                }
            });
        assertTrue(done.await(90, TimeUnit.SECONDS), "双副本请求都应收尾");
        assertTrue(sinkA.errors.isEmpty() && sinkB.errors.isEmpty(),
            "双副本并发不得崩溃（JVM 级整轮锁互斥同 slot，状态行原子），errors=" + errors(sinkA, sinkB));
        assertFalse(sinkA.text().contains(modelB.marker), "副本 A 输出不得混入副本 B 内容");
        assertFalse(sinkB.text().contains(modelA.marker), "副本 B 输出不得混入副本 A 内容");
        String slotId = KernelScopeKey.of("PU", "UU", "emp-rs", sessionId).slotId();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state_data FROM ipd_poc.agentscope_sessions"
                             + " WHERE session_id = ? AND state_key = 'agent_state'")) {
            statement.setString(1, slotId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "目标 slot 必须有状态行");
                assertFalse(rows.next(), "双副本不得产生重复 agent_state 行（原子 upsert）");
            }
        }
    }

    @Test
    @DisplayName("崩溃恢复：实例消亡后同键重建续接历史，工作区与 AGENTS.md 不被覆盖")
    void crashRecoveryContinuesHistoryOnSameKey() throws Exception {
        String sessionId = "CRASH-" + RUN;
        String firstMarker = "MARK-1-" + RUN;
        String customAgentsMd = "# custom-employee-" + RUN + "\n";
        Path root = Files.createTempDirectory("kernel-resilience-CRASH-" + RUN + "-");
        try (AgentScopeChatKernel kernel = newKernel(new EchoModel("CRASH"), root)) {
            RecordingSink sink = new RecordingSink();
            CountDownLatch done = new CountDownLatch(1);
            kernel.stream("PC", "UC", "emp-rs", sessionId, firstMarker, null,
                    new LatchSink(sink, done));
            assertTrue(done.await(30, TimeUnit.SECONDS), "首轮应完成");
            assertTrue(sink.errors.isEmpty(), "首轮不应报错");
        }
        // 模拟崩溃窗口期的现场：自定义 AGENTS.md（若恢复逻辑覆盖即被抓住）。
        Path bucket = root.resolve("PC").resolve("UC").resolve("emp-rs");
        Files.writeString(bucket.resolve("AGENTS.md"), customAgentsMd);

        try (AgentScopeChatKernel kernel = newKernel(new EchoModel("CRASH"), root)) {
            RecordingSink sink = new RecordingSink();
            CountDownLatch done = new CountDownLatch(1);
            kernel.stream("PC", "UC", "emp-rs", sessionId, "second-turn-" + RUN, null,
                    new LatchSink(sink, done));
            assertTrue(done.await(30, TimeUnit.SECONDS), "崩溃后重建实例同键请求必须完成（门不悬挂）");
            assertTrue(sink.errors.isEmpty(), "重建后请求不应报错");
        }
        assertEquals(customAgentsMd, Files.readString(bucket.resolve("AGENTS.md")),
            "重建实例不得覆盖既有 AGENTS.md（崩溃恢复 = 续接现场，不是重置）");
        String slotId = KernelScopeKey.of("PC", "UC", "emp-rs", sessionId).slotId();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state_data FROM ipd_poc.agentscope_sessions"
                             + " WHERE session_id = ? AND state_key = 'agent_state'")) {
            statement.setString(1, slotId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "崩溃恢复后状态必须仍在目标 slot");
                String stored = rows.getString(1);
                assertTrue(stored.contains(firstMarker), "状态应保留崩溃前轮次历史：" + firstMarker);
                assertFalse(rows.next(), "目标 slot 不应有重复 agent_state 行");
            }
        }
    }

    private static void runConcurrently(CountDownLatch done, Runnable first, Runnable second) {
        new Thread(first, "kernel-resilience-1").start();
        new Thread(second, "kernel-resilience-2").start();
    }

    private static void deleteRecursively(Path path) throws Exception {
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static AgentScopeChatKernel newKernel(Model model) throws Exception {
        return newKernel(model, Files.createTempDirectory("kernel-resilience-" + RUN + "-"));
    }

    private static AgentScopeChatKernel newKernel(Model model, Path root) {
        return new AgentScopeChatKernel(model, "stub:resilience",
            () -> new MysqlAgentStateStore(dataSource, PocKernelSupport.POC_DATABASE,
                PocKernelSupport.STATE_TABLE, false), root);
    }

    private static String errors(RecordingSink... sinks) {
        List<String> all = new ArrayList<>();
        for (RecordingSink sink : sinks) {
            all.addAll(sink.errors);
        }
        return all.toString();
    }

    /** 回声模型：把最后一条用户文本带回（合法三规约：只 mock 输出）。 */
    private static final class EchoModel implements Model {
        private final String name;

        private EchoModel(String name) {
            this.name = name;
        }

        @Override
        public String getModelName() {
            return "stub-echo-" + name;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String lastUser = "";
            for (Msg m : messages) {
                if (m.getTextContent() != null && !m.getTextContent().isBlank()) {
                    lastUser = m.getTextContent();
                }
            }
            return Flux.just(new ChatResponse("stub-" + UUID.randomUUID(),
                List.of(TextBlock.builder().text("ECHO[" + lastUser + "]").build()),
                null, Map.of(), "stop"));
        }
    }

    /** 标记模型：输出恒为固定 marker（供双副本互不污染断言）。 */
    private static final class MarkerModel implements Model {
        private final String marker;

        private MarkerModel(String marker) {
            this.marker = marker;
        }

        @Override
        public String getModelName() {
            return "stub-marker-" + marker;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(new ChatResponse("stub-" + UUID.randomUUID(),
                List.of(TextBlock.builder().text(marker).build()),
                null, Map.of(), "stop"));
        }
    }

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
            // stub 无推理输出
        }

        @Override
        public void onMcpTool(String toolName, String status, String result) {
            // W1 对话内核不挂工具
        }

        @Override
        public void onError(String code, String message) {
            errors.add(code);
        }

        @Override
        public void onComplete() {
            // 由 LatchSink 收尾
        }

        private String text() {
            synchronized (content) {
                return content.toString();
            }
        }
    }

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
