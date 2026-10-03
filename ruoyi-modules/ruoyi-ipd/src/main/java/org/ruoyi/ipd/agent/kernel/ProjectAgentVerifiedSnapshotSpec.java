package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.sandbox.snapshot.LocalSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 官方本地快照扩展：释放日志不能替代归档字节回读，失败时拒绝业务成功。 */
public final class ProjectAgentVerifiedSnapshotSpec implements SandboxSnapshotSpec {
    public record Receipt(String snapshotId, String sha256) { }
    private final Path basePath;
    private final java.util.Map<String, VerifiedSnapshot> issued = new LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean failedPersist = new java.util.concurrent.atomic.AtomicBoolean();

    public ProjectAgentVerifiedSnapshotSpec(Path basePath) {
        this.basePath = Objects.requireNonNull(basePath).toAbsolutePath().normalize();
    }

    @Override
    public synchronized SandboxSnapshot build(String snapshotId) {
        var snapshot = new VerifiedSnapshot(basePath.toString(), snapshotId);
        issued.put(snapshotId, snapshot);
        return snapshot;
    }

    /** 未获取沙箱时返回空；已获取的每个官方 SESSION 都必须实际落盘并回读。 */
    public synchronized List<Receipt> verifyReleased() {
        if (failedPersist.get()) throw new IllegalStateException("Official sandbox snapshot persistence failed");
        return issued.values().stream().map(VerifiedSnapshot::requireReceipt).toList();
    }

    @com.fasterxml.jackson.annotation.JsonTypeName("local")
    private final class VerifiedSnapshot extends LocalSandboxSnapshot {
        private Receipt receipt;
        private boolean failed;
        private VerifiedSnapshot(String basePath, String id) { super(basePath, id); }

        @Override
        public synchronized void persist(InputStream archive) throws Exception {
            receipt = null;
            try {
                var source = MessageDigest.getInstance("SHA-256");
                super.persist(new DigestInputStream(archive, source));
                var restored = MessageDigest.getInstance("SHA-256");
                try (var input = new DigestInputStream(super.restore(), restored)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
                String expected = HexFormat.of().formatHex(source.digest());
                if (!expected.equals(HexFormat.of().formatHex(restored.digest()))) {
                    throw new IllegalStateException("Official sandbox snapshot digest mismatch");
                }
                receipt = new Receipt(getId(), expected);
            } catch (Exception error) {
                failed = true;
                failedPersist.set(true);
                throw error;
            }
        }

        private synchronized Receipt requireReceipt() {
            if (failed || receipt == null) {
                throw new IllegalStateException("Official sandbox snapshot release is unverified");
            }
            // 检查验收前产物仍存在且字节未漂移，不能只认内存中的曾成功状态。
            try (var input = new DigestInputStream(super.restore(), MessageDigest.getInstance("SHA-256"))) {
                input.transferTo(OutputStream.nullOutputStream());
                if (!receipt.sha256().equals(HexFormat.of().formatHex(input.getMessageDigest().digest()))) {
                    throw new IllegalStateException("Official sandbox snapshot changed after release");
                }
                return receipt;
            } catch (Exception error) {
                throw new IllegalStateException("Official sandbox snapshot readback failed", error);
            }
        }
    }
}
