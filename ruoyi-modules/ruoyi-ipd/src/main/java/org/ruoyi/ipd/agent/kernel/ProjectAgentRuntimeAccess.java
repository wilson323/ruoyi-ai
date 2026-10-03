package org.ruoyi.ipd.agent.kernel;

import java.util.Objects;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.IpdCopilotAccess;

/** Per-subscription membership revalidation using the trusted run identity. */
public final class ProjectAgentRuntimeAccess {
    private final IpdCopilotAccess access;
    private final IpdActor actor;
    private final Long projectId;

    public ProjectAgentRuntimeAccess(IpdCopilotAccess access, IpdActor actor, Long projectId) {
        this.access = Objects.requireNonNull(access);
        this.actor = Objects.requireNonNull(actor);
        this.projectId = Objects.requireNonNull(projectId);
    }

    /** Call inside each deferred tool permission and execution subscription. */
    public void requireAuthorized() {
        access.requireVisible(actor, projectId);
    }
}
