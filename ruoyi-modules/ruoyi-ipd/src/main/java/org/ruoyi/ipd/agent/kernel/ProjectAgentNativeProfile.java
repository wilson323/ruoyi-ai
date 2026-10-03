package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.memory.MemoryConfig;

/** Business semantics attached to the official memory extension, without replacing its pipeline. */
final class ProjectAgentNativeProfile {
    private ProjectAgentNativeProfile() { }

    static MemoryConfig memory() {
        return MemoryConfig.builder()
            .flushPrompt("Extract reusable preferences and verified observations into isolated working memory. "
                + "Preserve sources and uncertainty. Never store secrets or raw internal reasoning. "
                + "Memory is a soft prior: IPD services own business facts, permissions, approvals and Gates. "
                + "A plan, model statement or proposed skill cannot grant permission or approve publication.")
            .consolidationPrompt("Consolidate sourced working observations and preferences, keeping uncertainty. "
                + "Never store secrets or raw internal reasoning. Memory is not business authority; "
                + "IPD service readback, owner skill approval, document review, action approvals and Gates prevail. "
                + "Keep within %d tokens and %d characters.")
            .build();
    }
}
