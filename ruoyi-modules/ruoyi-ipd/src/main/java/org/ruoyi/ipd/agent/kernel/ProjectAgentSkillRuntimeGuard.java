package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import java.util.Objects;
import java.util.function.Function;

/** Checks the actual official per-call catalog, including default-factory subagents. */
final class ProjectAgentSkillRuntimeGuard implements MiddlewareBase {
    private final FrozenProjectAgentSkills approved;
    private final ProjectAgentEventSink ownership;
    private final String expectedUser;
    private java.util.function.Supplier<io.agentscope.harness.agent.filesystem.AbstractFilesystem> filesystem;

    ProjectAgentSkillRuntimeGuard(FrozenProjectAgentSkills approved, ProjectAgentRunSpec run,
                                  ProjectAgentEventSink ownership) {
        this.approved = Objects.requireNonNull(approved);
        this.ownership = Objects.requireNonNull(ownership);
        this.expectedUser = KernelScopeKey.of(String.valueOf(run.projectId()), String.valueOf(run.personId()),
            ProjectAgentConstants.AGENT_ID, String.valueOf(run.runId())).userId();
    }

    ProjectAgentSkillRuntimeGuard(FrozenProjectAgentSkills approved, ProjectAgentRunSpec run,
            ProjectAgentEventSink ownership,
            java.util.function.Supplier<io.agentscope.harness.agent.filesystem.AbstractFilesystem> filesystem) {
        this(approved, run, ownership);
        this.filesystem = Objects.requireNonNull(filesystem);
    }

    @Override public int order() { return Integer.MAX_VALUE; }

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            requireOwnership(context);
            if (filesystem != null) approved.installInto(Objects.requireNonNull(filesystem.get()), context);
            return next.apply(input);
        });
    }

    @Override public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromCallable(() -> { verify(context); return prompt; });
    }

    @Override public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        // This guard runs before the model even if a system-prompt pipeline handles an error.
        return Flux.defer(() -> { verify(context); return next.apply(input); });
    }

    void verify(RuntimeContext context) {
        requireOwnership(context);
        SkillCatalog catalog = context.get(SkillCatalog.class);
        if (catalog == null) { return; }
        for (var entry : catalog.all()) {
            var actual = entry.skill();
            var locked = approved.getSkill(actual.getName());
            if (locked == null || !Objects.equals(locked.getSkillContent(), actual.getSkillContent())
                || !Objects.equals(locked.getDescription(), actual.getDescription())
                || !Objects.equals(locked.getMetadataValue("version"), actual.getMetadataValue("version"))
                || !Objects.equals(locked.getResources(), actual.getResources())) {
                // The load tool reads this same catalog. Clear it before surfacing the failure.
                context.put(SkillCatalog.class, SkillCatalog.empty());
                context.put("ipd.skill.catalog.rejected", Boolean.TRUE);
                throw new SecurityException("Official skill catalog differs from owner-approved run snapshot");
            }
        }
        if (Boolean.TRUE.equals(context.get("ipd.skill.catalog.rejected"))) {
            throw new SecurityException("Official skill catalog was rejected for this call");
        }
    }

    private void requireOwnership(RuntimeContext context) {
        ownership.requireActiveOwnership();
        if (context == null || !expectedUser.equals(context.getUserId()) || context.getSessionId() == null
            || context.getSessionId().isBlank()) {
            throw new SecurityException("Official skill runtime ownership mismatch");
        }
    }
}
