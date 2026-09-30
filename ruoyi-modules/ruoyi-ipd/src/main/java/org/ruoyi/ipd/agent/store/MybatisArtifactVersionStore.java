package org.ruoyi.ipd.agent.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.mapper.IpdAgentArtifactVersionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link ArtifactVersionStore} 的 MyBatis-Plus 实现。
 */
@Repository
@RequiredArgsConstructor
public class MybatisArtifactVersionStore implements ArtifactVersionStore {

    private final IpdAgentArtifactVersionMapper mapper;

    /** {@inheritDoc} */
    @Override
    public boolean insert(IpdAgentArtifactVersion version) {
        try {
            return mapper.insert(version) == 1;
        } catch (DuplicateKeyException duplicated) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IpdAgentArtifactVersion> findById(Long versionId) {
        if (versionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(versionId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IpdAgentArtifactVersion> findLatest(Long runId, String artifactId) {
        if (runId == null || artifactId == null || artifactId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<IpdAgentArtifactVersion>()
            .eq(IpdAgentArtifactVersion::getRunId, runId)
            .eq(IpdAgentArtifactVersion::getArtifactId, artifactId.trim())
            .orderByDesc(IpdAgentArtifactVersion::getVersionNo)
            .last("LIMIT 1")));
    }

    /** {@inheritDoc} */
    @Override
    public boolean markApplied(Long versionId, Long documentId) {
        if (versionId == null || documentId == null) {
            return false;
        }
        return mapper.update(null, new LambdaUpdateWrapper<IpdAgentArtifactVersion>()
            .eq(IpdAgentArtifactVersion::getId, versionId)
            .eq(IpdAgentArtifactVersion::getStatus, IpdAgentArtifactVersion.STATUS_DRAFT)
            .set(IpdAgentArtifactVersion::getStatus, IpdAgentArtifactVersion.STATUS_APPLIED)
            .set(IpdAgentArtifactVersion::getDocumentId, documentId)) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public Set<Long> findRunIdsByContent(String tenantId, String text) {
        if (tenantId == null || text == null || text.isBlank()) {
            return Set.of();
        }
        String pattern = LikePatterns.containsPattern(text.trim());
        return mapper.selectList(new LambdaQueryWrapper<IpdAgentArtifactVersion>()
                .eq(IpdAgentArtifactVersion::getTenantId, tenantId)
                .apply("(title LIKE {0} ESCAPE '\\\\' OR content LIKE {0} ESCAPE '\\\\')", pattern)
                .select(IpdAgentArtifactVersion::getRunId))
            .stream()
            .map(IpdAgentArtifactVersion::getRunId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    /** {@inheritDoc} */
    @Override
    public List<IpdAgentArtifactVersion> listByRunIds(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<IpdAgentArtifactVersion>()
            .in(IpdAgentArtifactVersion::getRunId, runIds)
            .orderByAsc(IpdAgentArtifactVersion::getRunId)
            .orderByAsc(IpdAgentArtifactVersion::getVersionNo));
    }
}
