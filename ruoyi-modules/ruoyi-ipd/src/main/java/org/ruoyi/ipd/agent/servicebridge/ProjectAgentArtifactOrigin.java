package org.ruoyi.ipd.agent.servicebridge;

import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * Original ARTIFACT event is the durable attachment-origin ledger; no independent sequence
 * allocator.
 */
public final class ProjectAgentArtifactOrigin {
    public interface OwnedArtifactAppender {
        void append(Map<String, Object> payload);
    }

    private final AgentRunStore runs;
    private final OwnedArtifactAppender appender;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public ProjectAgentArtifactOrigin(AgentRunStore runs, OwnedArtifactAppender appender) {
        this.runs = Objects.requireNonNull(runs);
        this.appender = Objects.requireNonNull(appender);
    }

    public void record(
            ProjectAgentArtifactDelivery.Attachment a,
            String artifactId,
            String title,
            int versionNo) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Artifact origin requires original transaction");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("artifactId", artifactId);
        payload.put("title", title);
        payload.put("version", versionNo);
        payload.put("versionId", String.valueOf(a.versionId()));
        payload.put("contentHash", a.sha256());
        payload.put("attachmentOrigin", "IPD_NATIVE_DELIVERY_V1");
        payload.put("attachment", a);
        appender.append(payload);
        if (!find(a.tenantId(), a.runId(), a.versionId()).filter(a::equals).isPresent())
            throw new IllegalStateException("Artifact origin readback failed");
    }

    public Optional<ProjectAgentArtifactDelivery.Attachment> find(
            String tenant, Long run, Long version) {
        return find(tenant, run, version, null);
    }

    public Optional<ProjectAgentArtifactDelivery.Attachment> find(
            org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion row) {
        return find(row.getTenantId(), row.getRunId(), row.getId(), row);
    }

    private Optional<ProjectAgentArtifactDelivery.Attachment> find(
            String tenant,
            Long run,
            Long version,
            org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion row) {
        long after = 0;
        for (; ; ) {
            var events = runs.listEvents(run, after, 200);
            if (events.isEmpty()) return Optional.empty();
            long previous = after;
            for (var event : events) {
                if (event.getSeq() == null || event.getSeq() <= after)
                    throw new IllegalStateException("Artifact event cursor mismatch");
                after = event.getSeq();
                if (!run.equals(event.getRunId()) || !tenant.equals(event.getTenantId()))
                    throw new SecurityException("Artifact origin event scope mismatch");
                if (!"ARTIFACT".equals(event.getEventType())) continue;
                try {
                    var payload = json.readTree(event.getPayload());
                    if (!"IPD_NATIVE_DELIVERY_V1".equals(payload.path("attachmentOrigin").asText())
                            || !String.valueOf(version).equals(payload.path("versionId").asText()))
                        continue;
                    var a =
                            json.treeToValue(
                                    payload.path("attachment"),
                                    ProjectAgentArtifactDelivery.Attachment.class);
                    if (!version.equals(a.versionId())
                            || !run.equals(a.runId())
                            || !tenant.equals(a.tenantId())
                            || !a.sha256().equals(payload.path("contentHash").asText()))
                        throw new SecurityException("Artifact origin receipt mismatch");
                    if (row != null
                            && (!Objects.equals(
                                            row.getArtifactId(),
                                            payload.path("artifactId").asText())
                                    || !Objects.equals(
                                            row.getVersionNo(), payload.path("version").asInt())
                                    || !Objects.equals(
                                            row.getTitle(), payload.path("title").asText()))) {
                        throw new SecurityException("Artifact original version identity mismatch");
                    }
                    return Optional.of(a);
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    throw new IllegalStateException("Artifact origin payload invalid");
                }
            }
            if (after <= previous)
                throw new IllegalStateException("Artifact origin cursor did not advance");
        }
    }

    public void requireLegacyText(org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion row) {
        long after = 0;
        for (; ; ) {
            var page = runs.listEvents(row.getRunId(), after, 200);
            if (page.isEmpty())
                throw new IllegalStateException("Artifact trusted origin unavailable");
            for (var event : page) {
                if (event.getSeq() == null || event.getSeq() <= after)
                    throw new IllegalStateException("Artifact event cursor mismatch");
                after = event.getSeq();
                if (!row.getRunId().equals(event.getRunId())
                        || !row.getTenantId().equals(event.getTenantId()))
                    throw new SecurityException("Artifact origin event scope mismatch");
                if (!"ARTIFACT".equals(event.getEventType())) continue;
                try {
                    var payload = json.readTree(event.getPayload());
                    if (!String.valueOf(row.getId()).equals(payload.path("versionId").asText()))
                        continue;
                    if (payload.has("attachmentOrigin") || payload.has("attachment"))
                        throw new IllegalStateException("Artifact attachment origin incomplete");
                    if (!Objects.equals(row.getArtifactId(), payload.path("artifactId").asText())
                            || row.getVersionNo() != payload.path("version").asInt()
                            || !Objects.equals(
                                    row.getContentSha256(), payload.path("contentHash").asText())
                            || row.getContent() == null
                            || !Objects.equals(row.getContentSha256(), sha(row.getContent())))
                        throw new SecurityException("Artifact legacy text origin mismatch");
                    return;
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    throw new IllegalStateException("Artifact origin payload invalid");
                }
            }
        }
    }

    private static String sha(String text) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            text.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
