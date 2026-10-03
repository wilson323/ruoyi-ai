import com.mysql.cj.jdbc.MysqlDataSource;
import org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver;
/** Two JVM production lease probe; no mapper operation or workflow row writes. */
public class WorkflowLeaseReadonlyProbe {
    public static void main(String[] args) throws Exception {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL("jdbc:mysql://127.0.0.1:13306/ipd_dev?useSSL=false&allowPublicKeyRetrieval=true");
        ds.setUser(System.getenv("IPD_PROBE_DB_USER"));
        ds.setPassword(System.getenv("IPD_PROBE_DB_PASSWORD"));
        JdbcCheckpointSaver saver = new JdbcCheckpointSaver(null, ds);
        String phase = args[0], thread = args[1];
        if (phase.equals("contend")) {
            try (var lease = saver.acquireRun(thread)) {
                throw new AssertionError("second JVM acquired held lease");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("不能同时恢复")) throw expected;
                System.out.println("LEASE_REJECTED");
            }
            return;
        }
        try (var lease = saver.acquireRun(thread)) {
            lease.requireHeld();
            System.out.println("LEASE_HELD");
            System.out.flush();
            if (phase.equals("hold")) System.in.read();
            lease.requireHeld();
        }
        System.out.println("LEASE_RELEASED");
    }
}
