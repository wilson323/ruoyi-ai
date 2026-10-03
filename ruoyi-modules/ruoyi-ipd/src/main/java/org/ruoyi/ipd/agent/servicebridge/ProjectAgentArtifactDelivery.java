package org.ruoyi.ipd.agent.servicebridge;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.*;

import org.ruoyi.ipd.agent.domain.*;
import org.ruoyi.ipd.agent.store.*;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.springframework.transaction.support.*;

import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** DRAFT delivery, never document apply/review. Root-context binding is server-created. */
public final class ProjectAgentArtifactDelivery implements ArtifactDeliveryTarget {
    public static final String FILE_RECEIPT = "ipd-artifact-file:v1:";

    public interface RuntimeAuthority {
        void requireAuthorized(Binding binding, RuntimeContext runtime);
    }

    public record Attachment(
            Long versionId,
            String tenantId,
            Long runId,
            String sha256,
            long byteLength,
            boolean binary) {}

    public interface RunOwnerTransaction {
        <T> T owned(Supplier<T> body);
    }

    public record Binding(
            IpdActor actor,
            Long projectId,
            String tenantId,
            Long runId,
            String userId,
            String sessionId) {}

    private final ProjectAgentArtifactOrigin origin;
    private final RuntimeAuthority runtimeAuthority;
    private final Binding binding;
    private final IpdCopilotAccess access;
    private final AgentRunStore runs;
    private final ArtifactVersionStore versions;
    private final TransactionTemplate tx;
    private final RunOwnerTransaction owner;
    private final Path root;
    private final LongSupplier ids;

    public ProjectAgentArtifactDelivery(
            Binding binding,
            IpdCopilotAccess access,
            AgentRunStore runs,
            ArtifactVersionStore versions,
            TransactionTemplate tx,
            RunOwnerTransaction owner,
            Path root,
            LongSupplier ids,
            RuntimeAuthority runtimeAuthority,
            ProjectAgentArtifactOrigin origin) {
        this.origin = Objects.requireNonNull(origin);
        this.runtimeAuthority = Objects.requireNonNull(runtimeAuthority);
        this.binding = Objects.requireNonNull(binding);
        this.access = Objects.requireNonNull(access);
        this.runs = Objects.requireNonNull(runs);
        this.versions = Objects.requireNonNull(versions);
        this.tx = Objects.requireNonNull(tx);
        this.owner = Objects.requireNonNull(owner);
        this.ids = Objects.requireNonNull(ids);
        try {
            this.root = root.toRealPath();
            Path q = root.toAbsolutePath();
            while (q != null) {
                if (Files.isSymbolicLink(q)) throw new SecurityException("Artifact root symlink");
                q = q.getParent();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Controlled artifact storage unavailable");
        }
    }

    private void authorize() {
        if (!binding.tenantId().equals(access.requireVisible(binding.actor(), binding.projectId())))
            throw new SecurityException("Artifact tenant mismatch");
        var r =
                runs.findRun(binding.runId())
                        .orElseThrow(() -> new SecurityException("Artifact run unavailable"));
        if (!binding.actor().id().equals(r.getPersonId())
                || !binding.projectId().equals(r.getProjectId())
                || !binding.tenantId().equals(r.getTenantId()))
            throw new SecurityException("Artifact run owner mismatch");
    }

    private void context(RuntimeContext c) {
        runtimeAuthority.requireAuthorized(binding, c);
        authorize();
    }

    public ArtifactDeliveryResult deliver(RuntimeContext c, ArtifactDeliveryRequest request) {
        try {
            context(c);
            if (request == null
                    || request.content() == null
                    || request.fileName() == null
                    || request.fileName().indexOf(47) >= 0
                    || request.fileName().indexOf(92) >= 0
                    || request.fileName().indexOf(0) >= 0
                    || request.fileName().equals(".")
                    || request.fileName().equals("..")
                    || request.fileName().length() > 200)
                throw new IllegalArgumentException("Invalid artifact filename");
            byte[] bytes = request.content().clone();
            String artifact =
                    sha(request.fileName().getBytes(StandardCharsets.UTF_8)).substring(0, 48);
            var row =
                    owner.owned(
                            () ->
                                    tx.execute(
                                            status -> {
                                                context(c);
                                                var run =
                                                        runs.findRun(binding.runId()).orElseThrow();
                                                if (!"RUNNING".equals(run.getStatus()))
                                                    throw new IllegalStateException(
                                                            "Artifact run is not executing");
                                                var latest =
                                                        versions.findLatestForUpdate(
                                                                binding.tenantId(),
                                                                binding.runId(),
                                                                artifact);
                                                if (latest.isPresent() && !request.force())
                                                    throw new Conflict();
                                                long id = ids.getAsLong();
                                                Path file = file(id);
                                                try {
                                                    try (var channel =
                                                            java.nio.channels.FileChannel.open(
                                                                    file,
                                                                    StandardOpenOption.CREATE_NEW,
                                                                    StandardOpenOption.WRITE)) {
                                                        var buffer =
                                                                java.nio.ByteBuffer.wrap(bytes);
                                                        while (buffer.hasRemaining())
                                                            channel.write(buffer);
                                                        channel.force(true);
                                                    }
                                                } catch (java.io.IOException e) {
                                                    throw new IllegalStateException(
                                                            "Artifact bytes persistence failed");
                                                }
                                                if (!TransactionSynchronizationManager
                                                        .isSynchronizationActive()) {
                                                    remove(file);
                                                    throw new IllegalStateException(
                                                            "Artifact delivery transaction"
                                                                    + " required");
                                                }
                                                TransactionSynchronizationManager
                                                        .registerSynchronization(
                                                                new TransactionSynchronization() {
                                                                    public void afterCompletion(
                                                                            int result) {
                                                                        if (result
                                                                                != STATUS_COMMITTED) {
                                                                            remove(file);
                                                                            remove(metadata(id));
                                                                        }
                                                                    }
                                                                });
                                                String text = decode(bytes, request.fileName());
                                                try {
                                                    var attachment =
                                                            new Attachment(
                                                                    id,
                                                                    binding.tenantId(),
                                                                    binding.runId(),
                                                                    sha(bytes),
                                                                    bytes.length,
                                                                    text == null);
                                                    Files.writeString(
                                                            metadata(id),
                                                            new com.fasterxml.jackson.databind
                                                                            .ObjectMapper()
                                                                    .writeValueAsString(attachment),
                                                            StandardOpenOption.CREATE_NEW,
                                                            StandardOpenOption.WRITE);
                                                } catch (java.io.IOException failure) {
                                                    throw new IllegalStateException(
                                                            "Artifact attachment metadata"
                                                                    + " persistence failed");
                                                }
                                                var v = new IpdAgentArtifactVersion();
                                                v.setId(id);
                                                v.setTenantId(binding.tenantId());
                                                v.setRunId(binding.runId());
                                                v.setArtifactId(artifact);
                                                v.setVersionNo(
                                                        latest.map(
                                                                        x ->
                                                                                Math.addExact(
                                                                                        x
                                                                                                .getVersionNo(),
                                                                                        1))
                                                                .orElse(1));
                                                v.setTitle(request.fileName());
                                                v.setContent(
                                                        text == null
                                                                ? FILE_RECEIPT
                                                                        + id
                                                                        + ":"
                                                                        + bytes.length
                                                                        + ":"
                                                                        + sha(bytes)
                                                                : text);
                                                v.setContentSha256(sha(bytes));
                                                v.setStatus("DRAFT");
                                                v.setDelFlag("0");
                                                v.setCreateBy(binding.actor().id());
                                                if (!versions.insert(v))
                                                    throw new IllegalStateException(
                                                            "Artifact version conflict");
                                                var saved =
                                                        versions.findById(id)
                                                                .orElseThrow(
                                                                        () ->
                                                                                new IllegalStateException(
                                                                                        "Artifact"
                                                                                            + " row readback"
                                                                                            + " failed"));
                                                if (!saved.getContentSha256()
                                                        .equals(sha(readBytes(file))))
                                                    throw new IllegalStateException(
                                                            "Artifact SHA readback mismatch");
                                                origin.record(
                                                        new Attachment(
                                                                id,
                                                                binding.tenantId(),
                                                                binding.runId(),
                                                                sha(bytes),
                                                                bytes.length,
                                                                text == null),
                                                        artifact,
                                                        request.fileName(),
                                                        v.getVersionNo());
                                                return saved;
                                            }));
            if (row == null)
                throw new IllegalStateException("Artifact transaction returned no receipt");
            download(binding.actor(), row.getId());
            return ArtifactDeliveryResult.success(
                    "Draft stored; version=" + row.getId() + "; sha256=" + row.getContentSha256());
        } catch (Conflict conflict) {
            return ArtifactDeliveryResult.conflict(
                    "Artifact exists; force creates a new draft version");
        } catch (RuntimeException failure) {
            return ArtifactDeliveryResult.fail("Artifact delivery was not verified");
        }
    }

    /** Narrow authenticated controller consumer; never exposes a host path. */
    public byte[] download(IpdActor requester, Long versionId) {
        if (requester == null || !binding.actor().id().equals(requester.id()))
            throw new SecurityException("Artifact download actor mismatch");
        authorize();
        var v =
                versions.findById(versionId)
                        .orElseThrow(() -> new SecurityException("Artifact version unavailable"));
        if (!binding.runId().equals(v.getRunId()) || !binding.tenantId().equals(v.getTenantId()))
            throw new SecurityException("Artifact version scope mismatch");
        return verified(v).bytes();
    }

    public void requireDocumentContent(IpdAgentArtifactVersion supplied) {
        authorize();
        var stored =
                versions.findById(supplied.getId())
                        .orElseThrow(() -> new SecurityException("Artifact version unavailable"));
        if (!binding.runId().equals(stored.getRunId())
                || !binding.tenantId().equals(stored.getTenantId())
                || !Objects.equals(stored.getContent(), supplied.getContent()))
            throw new SecurityException("Artifact guard version mismatch");
        var issued = origin.find(stored);
        if (issued.isEmpty()) {
            if (Files.exists(file(stored.getId())) || Files.exists(metadata(stored.getId())))
                throw new IllegalStateException("Artifact durable origin unavailable");
            origin.requireLegacyText(stored);
            return;
        }
        var a = issued.get();
        if (!a.sha256().equals(stored.getContentSha256()))
            throw new SecurityException("Artifact origin hash mismatch");
        if (a.binary())
            throw new IllegalStateException(
                    "Binary artifact is available for download, not document apply");
        verified(stored);
    }

    private record Verified(Attachment attachment, byte[] bytes) {}

    private Verified verified(IpdAgentArtifactVersion row) {
        try {
            Attachment a =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(Files.readString(metadata(row.getId())), Attachment.class);
            byte[] bytes = readBytes(file(row.getId()));
            var issued =
                    origin.find(row)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Artifact durable origin unavailable"));
            if (!issued.equals(a))
                throw new SecurityException("Artifact durable origin receipt mismatch");
            if (!row.getId().equals(a.versionId())
                    || !row.getRunId().equals(a.runId())
                    || !row.getTenantId().equals(a.tenantId())
                    || !a.sha256().equals(row.getContentSha256())
                    || !a.sha256().equals(sha(bytes))
                    || a.byteLength() != bytes.length)
                throw new SecurityException("Artifact attachment receipt mismatch");
            if (!a.binary()
                    && !Objects.equals(row.getContent(), new String(bytes, StandardCharsets.UTF_8)))
                throw new SecurityException("Artifact text receipt mismatch");
            if (a.binary()
                    && !Objects.equals(
                            row.getContent(),
                            FILE_RECEIPT + row.getId() + ":" + bytes.length + ":" + sha(bytes)))
                throw new SecurityException("Artifact binary receipt mismatch");
            return new Verified(a, bytes);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Artifact attachment metadata unavailable");
        }
    }

    private Path metadata(Long id) {
        Path p = root.resolve(id + ".receipt.json");
        if (Files.isSymbolicLink(p)) throw new SecurityException("Artifact metadata symlink");
        return p;
    }

    private Path file(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("Invalid artifact version");
        Path p = root.resolve(id + ".bin");
        if (Files.isSymbolicLink(p)) throw new SecurityException("Artifact file symlink");
        return p;
    }

    private static byte[] readBytes(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Artifact bytes unavailable");
        }
    }

    private static void remove(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (java.io.IOException ignored) {
            /* orphan is not a success receipt; operational reconciliation required */
        }
    }

    private static String decode(byte[] b, String name) {
        if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|json|html|csv|yaml|yml|xml|svg)$"))
            return null;
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String sha(byte[] b) {
        try {
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Conflict extends RuntimeException {}
}
