package org.ruoyi.ipd.agent.servicebridge;

import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.security.IpdActor;

import java.util.Objects;

/**
 * Mandatory trusted server factory for the original apply/download routes; no model metadata
 * authority.
 */
public final class ProjectAgentArtifactAccess {
    public interface OwnedTargetFactory {
        ProjectAgentArtifactDelivery forOwnedRun(IpdActor actor, Long runId);
    }

    private final OwnedTargetFactory factory;

    public ProjectAgentArtifactAccess(OwnedTargetFactory factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    public void requireDocumentContent(
            IpdActor actor, Long runId, IpdAgentArtifactVersion version) {
        if (!Objects.equals(runId, version.getRunId()))
            throw new SecurityException("Artifact apply run mismatch");
        factory.forOwnedRun(actor, runId).requireDocumentContent(version);
    }

    public byte[] download(IpdActor actor, Long runId, Long versionId) {
        return factory.forOwnedRun(actor, runId).download(actor, versionId);
    }
}
