package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import io.agentscope.harness.agent.skill.runtime.HarnessSkillEntry;
import org.mockito.Mockito;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class ProjectAgentSkillGovernanceTest {
    @TempDir Path root;

    private ProjectAgentRunSpec run() {
        var skill = AgentTestFixtures.skillCatalog(AgentTestFixtures.manifest())
            .load("competitor-analysis-ipd").orElseThrow();
        return new ProjectAgentRunSpec(101L, 20260929L, "tenant-a", 11L, "C02", "竞品分析",
            List.of(skill), List.of(), new KernelModelRequest("fixture", "openai", null, null), Duration.ofSeconds(3));
    }

    private SkillCandidate draft(String name) {
        return new SkillCandidate(name, "draft", "# pending", List.of(), null, null, List.of());
    }

    private RuntimeContext context() {
        return KernelScopeKey.of("20260929", "11", ProjectAgentConstants.AGENT_ID, "101").toRuntimeContext();
    }

    @Test void ownerReviewDefersAndProducesAnIdempotentReadableReceipt() throws Exception {
        var run = run();
        Path workspace = ProjectAgentWorkspace.prepare(root, "20260929", "11", "agent");
        var governance = new ProjectAgentSkillGovernance(workspace, run, new FrozenProjectAgentSkills(run.skills()));
        var filesystem = new LocalFilesystem(workspace);
        governance.bind(new WorkspaceManager(workspace, filesystem, null));
        Path draftPath = Files.createDirectories(workspace.resolve("skills/_drafts/candidate"));
        Files.writeString(draftPath.resolve("SKILL.md"), "---\nname: candidate\ndescription: draft\n---\n# pending\n");
        var decision = governance.review(draft("candidate"), context()).block();
        assertThat(decision).isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
        assertThat(((SkillPromotionGate.PromotionDecision.Defer) decision).reason())
            .contains("待技能 owner 批准", "skills/_drafts/candidate", "状态回读");
        try (var files = Files.list(draftPath)) {
            Path receipt = files.filter(file -> file.getFileName().toString().startsWith(".ipd-review-101-"))
                .findFirst().orElseThrow();
            String body = Files.readString(receipt);
            assertThat(body).contains("PENDING_OWNER_APPROVAL", "20260929", "11", "101");
            governance.review(draft("candidate"), context()).block();
            assertThat(Files.readString(receipt)).isEqualTo(body);
        }
    }

    @Test void unapprovedDraftAndSameNameTamperingNeverReachOfficialPromptVisibility() {
        var run = run();
        var frozen = new FrozenProjectAgentSkills(run.skills());
        var governance = new ProjectAgentSkillGovernance(root, run, frozen);
        AgentSkill approved = frozen.getSkill("competitor-analysis-ipd");
        AgentSkill changed = approved.toBuilder().skillContent("unapproved replacement").build();
        assertThat(governance.filter(List.of(approved, changed), context())).containsExactly(approved);
        assertThat(governance.filter(List.of(AgentSkill.builder().name("candidate")
            .description("draft").skillContent("draft").build()), context())).isEmpty();
    }

    @Test void missingDraftAndAnotherRuntimeCannotProduceAReviewReceipt() {
        var run = run();
        var governance = new ProjectAgentSkillGovernance(root, run, new FrozenProjectAgentSkills(run.skills()));
        governance.bind(new WorkspaceManager(root, new LocalFilesystem(root), null));
        assertThatThrownBy(() -> governance.review(draft("candidate"), context()).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("draft does not exist");
        assertThatThrownBy(() -> governance.review(draft("candidate"), RuntimeContext.empty()).block())
            .isInstanceOf(SecurityException.class);
        assertThat(governance.filter(List.of(), RuntimeContext.empty())).isEmpty();
        assertThatThrownBy(() -> governance.requireOwnerContext(RuntimeContext.empty()))
            .isInstanceOf(SecurityException.class);
    }
    @Test void actualOfficialMiddlewareDoesNotExposeSkillsWhenRuntimeOwnershipIsWrong() {
        var run = run();
        var frozen = new FrozenProjectAgentSkills(run.skills());
        var governance = new ProjectAgentSkillGovernance(root, run, frozen);
        var middleware = new HarnessSkillMiddleware(List.of(frozen), new Toolkit(), frozen.filter(), governance);
        String denied = middleware.onSystemPrompt(null, RuntimeContext.empty(), "base").block();
        assertThat(denied).doesNotContain("competitor-analysis-ipd");
        String allowed = middleware.onSystemPrompt(null, context(), "base").block();
        assertThat(allowed).contains("competitor-analysis-ipd");
    }

    @Test void inheritedRuntimeGuardRejectsActualChildCatalogPollutionBeforeReasoning() {
        var run = run();
        var frozen = new FrozenProjectAgentSkills(run.skills());
        var sink = Mockito.mock(ProjectAgentEventSink.class, Mockito.CALLS_REAL_METHODS);
        var guard = new ProjectAgentSkillRuntimeGuard(frozen, run, sink);
        RuntimeContext child = RuntimeContext.builder(context()).sessionId("official-child-session").build();
        var changed = frozen.getSkill("competitor-analysis-ipd").toBuilder()
            .skillContent("unapproved child replacement").build();
        // Simulate the actual SDK parent/child directory merge, without installing visibilityFilter.
        var pollution = new io.agentscope.core.skill.repository.AgentSkillRepository() {
            @Override public java.util.List<AgentSkill> getAllSkills() { return java.util.List.of(changed); }
            @Override public AgentSkill getSkill(String name) { return changed; }
            @Override public java.util.List<String> getAllSkillNames() { return java.util.List.of(changed.getName()); }
            @Override public boolean skillExists(String name) { return true; }
            @Override public boolean save(java.util.List<AgentSkill> skills, boolean force) { return false; }
            @Override public boolean delete(String name) { return false; }
            @Override public io.agentscope.core.skill.repository.AgentSkillRepositoryInfo getRepositoryInfo() {
                return new io.agentscope.core.skill.repository.AgentSkillRepositoryInfo("fixture", "fixture", false);
            }
            @Override public String getSource() { return "fixture"; }
            @Override public void setWriteable(boolean writable) { }
            @Override public boolean isWriteable() { return false; }
        };
        var official = new HarnessSkillMiddleware(java.util.List.of(pollution), new Toolkit(), frozen.filter());
        official.onSystemPrompt(null, child, "base").block();
        assertThat(child.get(SkillCatalog.class).all()).hasSize(1);
        assertThatThrownBy(() -> guard.verify(child)).isInstanceOf(SecurityException.class);
        assertThat(child.get(SkillCatalog.class).isEmpty()).isTrue();
        assertThatThrownBy(() -> guard.verify(child)).isInstanceOf(SecurityException.class);
    }

}
