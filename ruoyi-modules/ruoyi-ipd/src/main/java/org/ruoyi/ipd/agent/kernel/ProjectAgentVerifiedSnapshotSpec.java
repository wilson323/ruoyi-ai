package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.sandbox.snapshot.LocalSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
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
    private final ProjectAgentBackgroundMemoryLifecycle lifecycle;
    private final java.util.Map<String, VerifiedSnapshot> issued = new LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean failedPersist = new java.util.concurrent.atomic.AtomicBoolean();

    public ProjectAgentVerifiedSnapshotSpec(Path basePath) {
        this(basePath, null);
    }

    ProjectAgentVerifiedSnapshotSpec(Path basePath, ProjectAgentBackgroundMemoryLifecycle lifecycle) {
        this.basePath = Objects.requireNonNull(basePath).toAbsolutePath().normalize();
        this.lifecycle = lifecycle;
    }

    @Override
    public synchronized SandboxSnapshot build(String snapshotId) {
        var snapshot = new VerifiedSnapshot(basePath.toString(), snapshotId);
        issued.put(snapshotId, snapshot);
        return snapshot;
    }

    /** 未获取沙箱时返回空；已获取的每个官方 SESSION 都必须实际落盘并回读。 */
    public synchronized List<Receipt> verifyReleased() {
        if (lifecycle != null) lifecycle.requireHealthy();
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
            Path candidate = null;
            Exception persistFailure = null;
            try {
                // SDK has already generated the archive. Root-only settlement belongs before
                // doPersistWorkspace; waiting here cannot add bytes and can block sibling calls.
                if (lifecycle != null) lifecycle.requireHealthy();
                Files.createDirectories(basePath);
                candidate = Files.createTempFile(basePath, ".ipd-snapshot-candidate-", ".tar");
                var source = MessageDigest.getInstance("SHA-256");
                try (var output = Files.newOutputStream(candidate)) {
                    new DigestInputStream(archive, source).transferTo(output);
                } catch (java.io.IOException error) {
                    throw new io.agentscope.harness.agent.sandbox.SandboxException.SnapshotException(
                        getId(), "Failed to stage sandbox snapshot", error);
                }
                // 必须在官方原子替换前核验候选，拒绝的完整归档不得覆盖旧恢复点。
                if (lifecycle != null) try (var input = Files.newInputStream(candidate)) {
                    lifecycle.verifyArchive(getId(), input);
                }
                try (var input = Files.newInputStream(candidate)) { super.persist(input); }
                var restored = MessageDigest.getInstance("SHA-256");
                try (var input = new DigestInputStream(super.restore(), restored)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
                String expected = HexFormat.of().formatHex(source.digest());
                if (!expected.equals(HexFormat.of().formatHex(restored.digest()))) {
                    throw new IllegalStateException("Official sandbox snapshot digest mismatch");
                }
                if (lifecycle != null) try (var input = super.restore()) { lifecycle.verifyArchive(getId(), input); }
                receipt = new Receipt(getId(), expected);
            } catch (Exception error) {
                persistFailure = error;
                if (lifecycle != null) lifecycle.recordFailure(error);
                failed = true;
                failedPersist.set(true);
                throw error;
            } finally {
                if (candidate != null) try { Files.deleteIfExists(candidate); }
                catch (java.io.IOException cleanupFailure) {
                    if (lifecycle != null) lifecycle.recordFailure(cleanupFailure);
                    failed = true;
                    failedPersist.set(true);
                    if (persistFailure != null) {
                        if (java.util.Arrays.stream(persistFailure.getSuppressed()).noneMatch(error -> error == cleanupFailure))
                            persistFailure.addSuppressed(cleanupFailure);
                    } else throw cleanupFailure;
                }
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
                if (lifecycle != null) try (var archive = super.restore()) { lifecycle.verifyArchive(getId(), archive); }
                return receipt;
            } catch (Exception error) {
                throw new IllegalStateException("Official sandbox snapshot readback failed", error);
            }
        }
    }
}
