import com.mysql.cj.jdbc.MysqlDataSource;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.workflow.mapper.*;
import org.ruoyi.workflow.service.*;
import org.ruoyi.workflow.workflow.WorkflowEngine;
import org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Evidence-only extension of the production Engine QA probe; compile in temporary directory. */
public class WorkflowEngineCompetitionProbe extends WorkflowEngineRecoveryProbe {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("prepare")) {
            WorkflowEngineRecoveryProbe.main(new String[]{"fail", "replay", "-"});
            return;
        }
        String mode = args[0];
        if (!List.of("owner", "contender", "recover", "terminal", "snapshot").contains(mode)) throw new IllegalArgumentException("mode");
        phase = "resume"; kind = "replay";
        ds = new MysqlDataSource();
        ds.setURL("jdbc:mysql://127.0.0.1:13306/ipd_dev?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC");
        ds.setUser(System.getenv("IPD_PROBE_DB_USER"));
        ds.setPassword(System.getenv("IPD_PROBE_DB_PASSWORD"));
        openSession(); springTemplates(); loadFixture(args[1]);
        check(oneLong("SELECT is_enable FROM t_workflow WHERE id=?", workflowId) == 0, "only disabled QA workflow allowed");
        check(oneString("SELECT title FROM t_workflow WHERE id=?", workflowId).equals(MARK), "only exact QA marker allowed");
        check(oneLong("SELECT COUNT(*) FROM t_workflow_node n JOIN t_workflow_component c ON c.id=n.workflow_component_id WHERE n.workflow_id=? AND c.name NOT IN ('Start','End')", workflowId) == 0,
            "only Start/End components allowed");
        if (mode.equals("snapshot")) { snapshot(); return; }
        String before = fingerprint();
        WorkflowRuntimeService runtimes = new WorkflowRuntimeService();
        WorkflowRuntimeNodeService nodeService = new WorkflowRuntimeNodeService();
        bind(runtimes, session.getMapper(WorkflowRunMapper.class));
        bind(nodeService, session.getMapper(WorkflowRuntimeNodeMapper.class));
        JdbcCheckpointSaver saver = new JdbcCheckpointSaver(session.getMapper(WorkflowCheckpointMapper.class), ds) {
            @Override public RunLease acquireRun(String uuid) throws Exception {
                RunLease lease = super.acquireRun(uuid);
                if (mode.equals("owner")) {
                    System.out.println("ENGINE_OWNER_HELD runtimeUuid=" + uuid);
                    System.out.flush();
                    try { System.in.read(); }
                    catch (Exception error) { lease.close(); throw error; }
                }
                return lease;
            }
        };
        User user = new User(); user.setId(0L);
        engine = new WorkflowEngine(workflow(true), components(), nodes(), edges(), runtimes, nodeService, saver);
        engine.resume(user, runtimes.getByUuidForResume(runtimeUuid), null, null, null, null);
        if (mode.equals("contender") || mode.equals("terminal")) {
            check(engine.getWfState() == null, "loser entered execution preparation");
            check(before.equals(fingerprint()), "loser changed persisted runtime/node/checkpoint rows");
            System.out.println(mode.equals("terminal") ? "ENGINE_TERMINAL_ZERO_WRITES" : "ENGINE_LOSER_ZERO_WRITES");
        } else {
            check(oneLong("SELECT status FROM t_workflow_runtime WHERE id=?", runtimeId) == 3, "owner/recovery did not succeed");
            check(oneLong("SELECT is_enable FROM t_workflow WHERE id=?", workflowId) == 0, "QA workflow became enabled");
            System.out.println("ENGINE_RECOVERY_SUCCEEDED");
        }
        snapshot();
    }
    static String fingerprint() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String sql : List.of("SELECT * FROM t_workflow_runtime WHERE id=? ORDER BY id",
                "SELECT * FROM t_workflow_runtime_node WHERE workflow_runtime_id=? ORDER BY id")) {
            try (var c = ds.getConnection(); var q = c.prepareStatement(sql)) {
                q.setLong(1, runtimeId);
                try (var rows = q.executeQuery()) { hashRows(digest, rows); }
            }
        }
        try (var c = ds.getConnection(); var q = c.prepareStatement("SELECT * FROM t_workflow_checkpoint WHERE thread_id=? ORDER BY id")) {
            q.setString(1, runtimeUuid);
            try (var rows = q.executeQuery()) { hashRows(digest, rows); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void hashRows(MessageDigest digest, java.sql.ResultSet rows) throws Exception {
        int count = rows.getMetaData().getColumnCount();
        while (rows.next()) for (int column = 1; column <= count; column++) {
            String value = rows.getString(column);
            byte[] bytes = (value == null ? "<SQL_NULL>" : value).getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
    }
    static void snapshot() throws Exception {
        System.out.println("ENGINE_SNAPSHOT runtimeUuid=" + runtimeUuid + " runtimeId=" + runtimeId
            + " workflowId=" + workflowId + " sha256=" + fingerprint());
    }
}
