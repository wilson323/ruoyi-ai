package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.SkillPromoter;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Explicit user review reconstructs the approved bytes; never needs a live run agent. */
public final class ProjectAgentSkillPublisher {
    public record Result(boolean published, String scanSummary) { }
    public Result publish(Path trustedWorkspace, RuntimeContext trustedContext,
                          ProjectAgentSkillBundle bundle, String reviewerId) {
        var parsed = io.agentscope.core.skill.util.SkillUtil.createFrom(bundle.markdown(), bundle.textResources());
        if (!bundle.skillName().equals(parsed.getName())) throw new IllegalArgumentException("Skill package name differs from body");
        // The caller owns run locking and authorization. This directory is a rebuildable projection.
        Path staging = trustedWorkspace.resolve(".skill-publication").resolve(bundle.sha256());
        for (Path current = staging; current != null && current.startsWith(trustedWorkspace); current = current.getParent())
            if (java.nio.file.Files.isSymbolicLink(current)) throw new SecurityException("Skill publication symbolic link rejected");
        LocalFilesystem fs = new LocalFilesystem(staging, true, 16);
        String drafts = "skills/_drafts";
        String root = drafts + "/" + bundle.skillName();
        ProjectAgentSkillBundle.rejectSymbolicLinks(fs, trustedContext, root);
        for (var file : bundle.files()) {
            String target = root + "/" + file.path();
            var existing = fs.downloadFiles(trustedContext, List.of(target));
            if (existing.size() == 1 && existing.get(0).isSuccess()) {
                if (!java.util.Arrays.equals(file.bytes(), existing.get(0).content()))
                    throw new IllegalStateException("Skill publication projection changed");
            } else {
                var uploaded = fs.uploadFiles(trustedContext, List.of(Map.entry(target, file.bytes())));
                if (uploaded.size() != 1 || !uploaded.get(0).isSuccess())
                    throw new IllegalStateException("Skill publication byte upload failed");
            }
        }
        var scan = SkillSecurityScanner.scan(bundle.skillName(), bundle.markdown(), bundle.textResources());
        String summary = scan.verdict().name() + ": " + scan.reportText();
        if (!SkillSecurityScanner.shouldAllow(SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict()))
            return new Result(false, summary);
        SkillPromotionGate gate = (candidate, ctx) -> {
            if (!trustedContext.getUserId().equals(ctx.getUserId())
                || !trustedContext.getSessionId().equals(ctx.getSessionId())
                || !bundle.skillName().equals(candidate.name())) return Mono.error(new SecurityException("Skill approval scope mismatch"));
            var current = ProjectAgentSkillBundle.capture(fs, ctx, root, bundle.skillName());
            if (!current.sha256().equals(bundle.sha256())) return Mono.error(new IllegalStateException("Approved skill bytes changed"));
            return Mono.just(new SkillPromotionGate.PromotionDecision.Approve(reviewerId, List.of("ipd-user"), Instant.now()));
        };
        try (WorkspaceManager workspace = new WorkspaceManager(staging, fs)) {
            var source = new WorkspaceSkillRepository(fs, drafts, () -> trustedContext);
            var target = new WorkspaceSkillRepository(fs, "skills", () -> trustedContext);
            var promoter = new SkillPromoter(source, target, workspace, null, gate, drafts, "skills");
            // Retry after a committed publication only verifies the final projection; never reruns a move.
            if (!fs.exists(trustedContext, "skills/" + bundle.skillName() + "/SKILL.md")) {
                var result = promoter.promote(bundle.skillName(), reviewerId, trustedContext).block(java.time.Duration.ofSeconds(30));
                if (result == null || result.status() != SkillPromoter.PromotionResult.Status.APPROVED)
                    return new Result(false, summary + "; official promotion did not approve");
            }
            var published = ProjectAgentSkillBundle.capture(fs, trustedContext, "skills/" + bundle.skillName(), bundle.skillName());
            if (!published.sha256().equals(bundle.sha256())) throw new IllegalStateException("Published skill bytes changed");
            return new Result(true, summary);
        }
    }
}
