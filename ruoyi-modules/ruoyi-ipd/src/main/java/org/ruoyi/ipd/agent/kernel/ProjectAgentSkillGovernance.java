package org.ruoyi.ipd.agent.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Official skill extension points: draft review receipt and immutable approved-run visibility. */
final class ProjectAgentSkillGovernance implements SkillPromotionGate, SkillVisibilityFilter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private volatile WorkspaceManager manager;
    private final ProjectAgentRunSpec run;
    private final FrozenProjectAgentSkills published;

    ProjectAgentSkillGovernance(Path workspace, ProjectAgentRunSpec run, FrozenProjectAgentSkills published) {
        Objects.requireNonNull(workspace);
        this.run = Objects.requireNonNull(run);
        this.published = Objects.requireNonNull(published);
    }

    /** Bind the exact official workspace/filesystem after agent build, before any model call. */
    void bind(WorkspaceManager workspaceManager) {
        if (manager != null) { throw new IllegalStateException("Skill governance already bound"); }
        manager = Objects.requireNonNull(workspaceManager);
    }

    @Override public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext context) {
        return Mono.fromCallable(() -> {
            requireOwnerContext(context);
            WorkspaceManager official = Objects.requireNonNull(manager, "Official workspace is not bound");
            if (candidate == null || candidate.name() == null
                || !candidate.name().matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid skill review identity");
            }
            var filesystem = Objects.requireNonNull(official.getFilesystem(), "Official filesystem is required");
            var drafts = new WorkspaceSkillRepository(filesystem, "skills/_drafts", () -> context);
            AgentSkill actual = drafts.getSkill(candidate.name(), context);
            if (actual == null) { throw new IllegalStateException("Official draft does not exist"); }
            String draftPath = drafts.resolveSkillRoot(candidate.name());
            var raw = filesystem.read(context, draftPath + "/SKILL.md", 0, 0);
            if (!raw.isSuccess() || raw.fileData() == null || raw.fileData().content() == null) {
                throw new IllegalStateException("Official draft cannot be read");
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.fileData().content().getBytes(StandardCharsets.UTF_8)));
            String relative = draftPath + "/.ipd-review-" + run.runId() + "-" + digest + ".json";
            String content = JSON.writeValueAsString(Map.of(
                "status", "PENDING_OWNER_APPROVAL", "skillName", candidate.name(), "sha256", digest,
                "projectId", String.valueOf(run.projectId()), "personId", String.valueOf(run.personId()),
                "runId", String.valueOf(run.runId()), "draftPath", draftPath,
                "reason", "Skill owner approval and catalog publication are required; action mapping is not an approval record."));
            var existing = filesystem.read(context, relative, 0, 0);
            if (!existing.isSuccess()) {
                official.writeDraftSkillFile(context, relative, content);
            }
            var receipt = filesystem.read(context, relative, 0, 0);
            if (!receipt.isSuccess() || receipt.fileData() == null
                || !content.equals(receipt.fileData().content())) {
                throw new IllegalStateException("Skill owner review receipt cannot be verified");
            }
            return (PromotionDecision) new PromotionDecision.Defer(Duration.ofHours(24),
                "待技能 owner 批准并发布；草案：" + draftPath + "；状态回读：" + relative);
        });
    }

    @Override public List<AgentSkill> filter(List<AgentSkill> candidates, RuntimeContext context) {
        // The SDK catches visibility-filter exceptions and falls back to the original list.
        // Return a closed set for invalid identities; throwing here would expose every skill.
        if (!isOwnerContext(context)) { return List.of(); }
        if (candidates == null) { return List.of(); }
        // Name-only checks would let skill_manage overwrite an approved name with a new draft body.
        return candidates.stream().filter(Objects::nonNull).filter(skill -> {
            AgentSkill approved = published.getSkill(skill.getName());
            return approved != null && Objects.equals(approved.getSkillContent(), skill.getSkillContent())
                && Objects.equals(approved.getMetadataValue("version"), skill.getMetadataValue("version"))
                && Objects.equals(approved.getResources(), skill.getResources());
        }).toList();
    }

    void requireOwnerContext(RuntimeContext context) {
        if (!isOwnerContext(context)) {
            throw new SecurityException("Skill governance runtime ownership mismatch");
        }
    }

    private boolean isOwnerContext(RuntimeContext context) {
        var expected = KernelScopeKey.of(String.valueOf(run.projectId()), String.valueOf(run.personId()),
            ProjectAgentConstants.AGENT_ID, String.valueOf(run.runId()));
        return context != null && expected.userId().equals(context.getUserId())
            && expected.sessionId().equals(context.getSessionId());
    }
}
