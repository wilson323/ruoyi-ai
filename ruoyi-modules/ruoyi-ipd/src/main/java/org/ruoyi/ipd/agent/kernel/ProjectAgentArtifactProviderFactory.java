package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import java.util.Objects;
import java.util.function.BiConsumer;

/** 原业务配置提供运行租约及交付事务；内核提供同一真实 actor lineage。 */
@FunctionalInterface
public interface ProjectAgentArtifactProviderFactory {
    Provider create(ProjectAgentRunSpec spec, RuntimeContext trustedRoot, ProjectAgentEventSink sink,
                    BiConsumer<Agent, RuntimeContext> requireKnownActor);

    record Provider(ProjectAgentExecutionClaims claims, ArtifactDeliveryTarget target) {
        public Provider {
            Objects.requireNonNull(claims, "Server execution claims are required");
            Objects.requireNonNull(target, "Owned artifact target is required");
        }
    }
}
