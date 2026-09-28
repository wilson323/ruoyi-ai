package org.ruoyi.chat.poc.kernel;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [PoC G3] AgentScope 最小内核四维隔离实证(真库 ipd_poc · MysqlAgentStateStore · HarnessAgent)。
 *
 * <p>铁律对齐:mock 合法三规约——stub model 只 mock 模型输出,存储路径(agentscope_sessions 真库真写真读)全真实。
 * 测试数据留库不删(R214),报告登记。
 *
 * <p>用法: mvn -pl ruoyi-modules/ruoyi-chat -Dtest=AgentScopeKernelPocIT test
 */
@Tag("dev")
class AgentScopeKernelPocIT {

    private static final String RUN = "R" + Long.toString(System.nanoTime(), 36);
    private static final String TABLE = "ipd_poc.agentscope_sessions";

    private static DataSource dataSource;
    private static MysqlAgentStateStore stateStore;
    private static HarnessAgent agentEmpA1;
    private static HarnessAgent agentEmpA2;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = buildDataSource();
        stateStore = new MysqlAgentStateStore(dataSource, "ipd_poc", "agentscope_sessions", false);
        agentEmpA1 = buildAgent("emp-a1");
        agentEmpA2 = buildAgent("emp-a2");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (agentEmpA1 != null) agentEmpA1.close();
        if (agentEmpA2 != null) agentEmpA2.close();
        if (stateStore != null) stateStore.close();
    }

    // ==================== 正例:同 agent 两个 (project,user,session) 分桶互不可见 ====================

    @Test
    void positiveSameAgentTwoBucketsMutuallyInvisible() throws Exception {
        KernelScopeKey.Scope a = KernelScopeKey.of("P1", "U1", "emp-a1", "S1-" + RUN);
        KernelScopeKey.Scope b = KernelScopeKey.of("P2", "U2", "emp-a1", "S2-" + RUN);
        String markerA = "G3-POS-A-" + RUN;
        String markerB = "G3-POS-B-" + RUN;

        writeTurn(agentEmpA1, a, markerA);
        writeTurn(agentEmpA1, b, markerB);

        Set<String> rows = sessionIdsOfTable();
        assertTrue(rows.contains(a.slotId()), "分桶行 A 缺失: " + a.slotId() + " rows=" + rows);
        assertTrue(rows.contains(b.slotId()), "分桶行 B 缺失: " + b.slotId() + " rows=" + rows);
        assertFalse(a.slotId().equals(b.slotId()), "两个组合必须落不同分桶行");

        String dataA = rawAgentState(a);
        String dataB = rawAgentState(b);
        assertTrue(dataA.contains(markerA), "A 桶应含 A 内容");
        assertFalse(dataA.contains(markerB), "A 桶不得含 B 内容");
        assertTrue(dataB.contains(markerB), "B 桶应含 B 内容");
        assertFalse(dataB.contains(markerA), "B 桶不得含 A 内容");

        assertEquals(Set.of(a.slotId()), bucketsContaining(markerA), "A 内容只许出现在 A 桶");
        assertEquals(Set.of(b.slotId()), bucketsContaining(markerB), "B 内容只许出现在 B 桶");

        // 内核读路径(store.get)复核
        Optional<AgentState> readA = stateStore.get(a.userId(), a.sessionId(), "agent_state", AgentState.class);
        assertTrue(readA.isPresent(), "A 桶内核读路径应命中");
        assertTrue(contextText(readA.get()).contains(markerA));
        assertFalse(contextText(readA.get()).contains(markerB));
    }

    // ==================== 负例 6 用例(方案 §6.3):每个负例断言"A 桶读不到 B 桶" ====================

    @Test
    void negativeCrossProjectSameUser() throws Exception {
        // B: 同 user 同 agent 同 session,不同 project
        KernelScopeKey.Scope a = KernelScopeKey.of("P1", "U1", "emp-a1", "SN1-" + RUN);
        KernelScopeKey.Scope b = KernelScopeKey.of("P2", "U1", "emp-a1", "SN1-" + RUN);
        assertBucketCannotRead(a, b, "G3-NEG1-" + RUN);
    }

    @Test
    void negativeCrossUserSameProject() throws Exception {
        // B: 同 project 同 agent 同 session,不同 user
        KernelScopeKey.Scope a = KernelScopeKey.of("P1", "U1", "emp-a1", "SN2-" + RUN);
        KernelScopeKey.Scope b = KernelScopeKey.of("P1", "U2", "emp-a1", "SN2-" + RUN);
        assertBucketCannotRead(a, b, "G3-NEG2-" + RUN);
    }

    @Test
    void negativeCrossAgent() throws Exception {
        // B: 同 project 同 user 同 session,不同 agent(数字员工)
        KernelScopeKey.Scope a = KernelScopeKey.of("P1", "U1", "emp-a1", "SN3-" + RUN);
        KernelScopeKey.Scope b = KernelScopeKey.of("P1", "U1", "emp-a2", "SN3-" + RUN);
        assertBucketCannotRead(a, b, "G3-NEG3-" + RUN, agentEmpA2);
    }

    @Test
    void negativeCrossSession() throws Exception {
        // B: 同 project 同 user 同 agent,不同 session
        KernelScopeKey.Scope a = KernelScopeKey.of("P1", "U1", "emp-a1", "SN4A-" + RUN);
        KernelScopeKey.Scope b = KernelScopeKey.of("P1", "U1", "emp-a1", "SN4B-" + RUN);
        assertBucketCannotRead(a, b, "G3-NEG4-" + RUN);
    }

    @Test
    void negativeBlankUserIdDegradesToSession() throws Exception {
        // 空 userId 降级 SESSION: user 段坍缩 __anon__,会话桶仍硬隔离
        KernelScopeKey.Scope a = KernelScopeKey.of("P1", null, "emp-a1", "SN5A-" + RUN);
        KernelScopeKey.Scope b = KernelScopeKey.of("P1", "", "emp-a1", "SN5B-" + RUN);
        assertEquals("pP1:u" + KernelScopeKey.ANONYMOUS_USER_SEGMENT, a.userId(),
                "空 userId 应降级到 __anon__ 命名空间(SESSION 语义)");
        assertEquals(a.userId(), b.userId(), "降级后两者同 user 命名空间");
        assertBucketCannotRead(a, b, "G3-NEG5-" + RUN);
    }

    @Test
    void negativeCompositeKeyInjectionRejected() throws Exception {
        // B 桶照常落数据,注入者试图用 ':' / '..' 伪造复合键寻址 → 收口层 fail-closed
        KernelScopeKey.Scope a = KernelScopeKey.of("P1", "U1", "emp-a1", "SN6-" + RUN);
        KernelScopeKey.Scope b = KernelScopeKey.of("P2", "U2", "emp-a2", "SN6-" + RUN);
        String markerB = "G3-NEG6-" + RUN;
        writeTurn(agentEmpA2, b, markerB);
        awaitBucket(b.slotId(), markerB);

        // ':' 注入:伪造成别桶的复合 userId
        assertThrows(IllegalArgumentException.class,
                () -> KernelScopeKey.of("P1", "U1:aemp-a2:sSN6-" + RUN, "emp-a1", "SN6-" + RUN));
        // ':' 注入经 agentId/sessionId 段
        assertThrows(IllegalArgumentException.class,
                () -> KernelScopeKey.of("P1", "U1", "emp-a2:sSN6-" + RUN, "SN6-" + RUN));
        // '..' 注入:路径穿越特征
        assertThrows(IllegalArgumentException.class,
                () -> KernelScopeKey.of("P1", "U1", "emp-a1", "../SN6-" + RUN));
        assertThrows(IllegalArgumentException.class,
                () -> KernelScopeKey.of("P1..P2", "U1", "emp-a1", "SN6-" + RUN));

        // 收口拒绝 = 无法寻址 B 桶;A 桶实读依旧读不到 B 内容
        assertCannotSee(a, markerB);
        assertEquals(Set.of(b.slotId()), bucketsContaining(markerB), "B 内容只许出现在 B 桶");
    }

    // ==================== helpers ====================

    /** 负例主断言:B 桶真实写入后,A 桶(store 读路径 + 真库 SQL)读不到 B 内容;B 内容只落 B 桶。 */
    private static void assertBucketCannotRead(KernelScopeKey.Scope a, KernelScopeKey.Scope b, String markerB)
            throws Exception {
        assertBucketCannotRead(a, b, markerB, agentEmpA1);
    }

    private static void assertBucketCannotRead(
            KernelScopeKey.Scope a, KernelScopeKey.Scope b, String markerB, HarnessAgent writer) throws Exception {
        assertFalse(a.slotId().equals(b.slotId()), "A/B 必须是不同桶");
        writeTurn(writer, b, markerB);
        awaitBucket(b.slotId(), markerB);
        assertCannotSee(a, markerB);
        assertEquals(Set.of(b.slotId()), bucketsContaining(markerB), "B 内容只许出现在 B 桶");
    }

    private static void assertCannotSee(KernelScopeKey.Scope a, String markerB) throws Exception {
        String dataA = rawAgentState(a);
        assertFalse(dataA.contains(markerB), "A 桶读到了 B 桶内容(隔离被击穿): slot=" + a.slotId());
        Optional<AgentState> readA =
                stateStore.get(a.userId(), a.sessionId(), "agent_state", AgentState.class);
        if (readA.isPresent()) {
            assertFalse(contextText(readA.get()).contains(markerB),
                    "A 桶内核读路径读到了 B 桶内容(隔离被击穿)");
        }
    }

    private static void writeTurn(HarnessAgent agent, KernelScopeKey.Scope scope, String marker) {
        Msg reply =
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("remember this fact: " + marker)
                                        .build(),
                                scope.toRuntimeContext())
                        .block();
        assertNotNull(reply, "stub 模型应有回复");
        assertTrue(reply.getTextContent().contains(marker), "stub 回复应含 marker: " + marker);
    }

    private static String contextText(AgentState state) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : state.getContext()) {
            sb.append(m.getTextContent()).append('\n');
        }
        return sb.toString();
    }

    private static void awaitBucket(String slotId, String marker) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (bucketsContaining(marker).contains(slotId)) return;
            Thread.sleep(200);
        }
        throw new AssertionError("bucket write timeout: " + slotId + " marker=" + marker);
    }

    private static String rawAgentState(KernelScopeKey.Scope scope) throws Exception {
        String sql = "SELECT state_data FROM " + TABLE + " WHERE session_id = ? AND state_key = 'agent_state'";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope.slotId());
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) sb.append(rs.getString(1)).append('\n');
                return sb.toString();
            }
        }
    }

    private static Set<String> bucketsContaining(String marker) throws Exception {
        String sql = "SELECT DISTINCT session_id FROM " + TABLE + " WHERE state_data LIKE CONCAT('%', ?, '%')";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, marker);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> out = new LinkedHashSet<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    private static Set<String> sessionIdsOfTable() throws Exception {
        String sql = "SELECT DISTINCT session_id FROM " + TABLE;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            Set<String> out = new LinkedHashSet<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    private static HarnessAgent buildAgent(String agentId) throws Exception {
        Path ws = Files.createTempDirectory("poc-kernel-" + agentId + "-");
        Files.writeString(ws.resolve("AGENTS.md"), "# PoC " + agentId + "\n\nIsolation PoC employee.\n");
        return HarnessAgent.builder()
                .name(agentId)
                .sysPrompt("You are a note-taking assistant. Echo every fact verbatim.")
                .model(new StubEchoModel())
                .workspace(ws)
                .stateStore(stateStore)
                .build();
    }

    private static DataSource buildDataSource() throws Exception {
        Properties cnf = readCnf(locateRepoRoot().resolve(".codex/ipd-dev/config/mysql-app.cnf"));
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://127.0.0.1:13306/ipd_poc?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
        ds.setUser(cnf.getProperty("user"));
        ds.setPassword(cnf.getProperty("password"));
        return ds;
    }

    private static Path locateRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(".codex/ipd-dev/config/mysql-app.cnf"))) return dir;
            dir = dir.getParent();
        }
        throw new IllegalStateException("repo root with .codex/ipd-dev/config/mysql-app.cnf not found");
    }

    private static Properties readCnf(Path path) throws Exception {
        Properties props = new Properties();
        for (String line : Files.readAllLines(path)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) props.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return props;
    }

    /** stub 模型:只 mock 模型输出(合法三规约),存储路径全真实。 */
    private static final class StubEchoModel implements Model {

        @Override
        public String getModelName() {
            return "stub-echo-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String lastUser = "";
            for (Msg m : messages) {
                if (m.getTextContent() != null && !m.getTextContent().isBlank()) {
                    lastUser = m.getTextContent();
                }
            }
            String reply = "STUB-REPLY[" + lastUser + "]";
            return Flux.just(
                    new ChatResponse(
                            "stub-" + UUID.randomUUID(),
                            List.of(TextBlock.builder().text(reply).build()),
                            null,
                            Map.of(),
                            "stop"));
        }
    }
}
