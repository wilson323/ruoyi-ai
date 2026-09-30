package org.ruoyi.ipd.agent.support;

import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存产物版本存储（单测替身）。
 */
public final class InMemoryArtifactVersionStore implements ArtifactVersionStore {

    private final Map<Long, IpdAgentArtifactVersion> byId = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(9_100_000_000_000_000_000L);

    /** {@inheritDoc} */
    @Override
    public boolean insert(IpdAgentArtifactVersion version) {
        if (version.getId() == null) {
            version.setId(seq.incrementAndGet());
        }
        boolean conflict = byId.values().stream().anyMatch(existing ->
            existing.getRunId().equals(version.getRunId())
                && existing.getArtifactId().equals(version.getArtifactId())
                && existing.getVersionNo().equals(version.getVersionNo()));
        if (conflict) {
            return false;
        }
        byId.put(version.getId(), version);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IpdAgentArtifactVersion> findById(Long versionId) {
        return Optional.ofNullable(byId.get(versionId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IpdAgentArtifactVersion> findLatest(Long runId, String artifactId) {
        return byId.values().stream()
            .filter(v -> v.getRunId().equals(runId) && v.getArtifactId().equals(artifactId))
            .max(Comparator.comparingInt(IpdAgentArtifactVersion::getVersionNo));
    }

    /** {@inheritDoc} */
    @Override
    public boolean markApplied(Long versionId, Long documentId) {
        IpdAgentArtifactVersion current = byId.get(versionId);
        if (current == null || !IpdAgentArtifactVersion.STATUS_DRAFT.equals(current.getStatus())) {
            return false;
        }
        current.setStatus(IpdAgentArtifactVersion.STATUS_APPLIED);
        current.setDocumentId(documentId);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public Set<Long> findRunIdsByContent(String tenantId, String text) {
        if (tenantId == null || text == null || text.isBlank()) {
            return Set.of();
        }
        String needle = text.trim().toLowerCase(Locale.ROOT);
        return byId.values().stream()
            .filter(version -> tenantId.equals(version.getTenantId()))
            .filter(version -> contains(version.getTitle(), needle) || contains(version.getContent(), needle))
            .map(IpdAgentArtifactVersion::getRunId)
            .collect(Collectors.toSet());
    }

    /** {@inheritDoc} */
    @Override
    public List<IpdAgentArtifactVersion> listByRunIds(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        return byId.values().stream()
            .filter(version -> runIds.contains(version.getRunId()))
            .sorted(Comparator.comparing(IpdAgentArtifactVersion::getRunId)
                .thenComparing(IpdAgentArtifactVersion::getVersionNo))
            .toList();
    }

    private static boolean contains(String field, String needleLower) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    /** @return 当前行数 */
    public int size() {
        return byId.size();
    }
}
