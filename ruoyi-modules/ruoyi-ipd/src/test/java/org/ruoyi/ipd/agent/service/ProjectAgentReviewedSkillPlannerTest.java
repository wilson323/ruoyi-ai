package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.kernel.ProjectAgentSkillBundle;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews.SkillRef;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

/** 已批准技能的项目/用户隔离及原运行版本冻结，不模拟审核批准本身。 */
@Tag("dev")
class ProjectAgentReviewedSkillPlannerTest {
    @Test void publishedSkillIsSelectableOnlyByItsUserInItsProject() {
        var f = new Fixture("my-reviewed-method");
        assertThat(f.planner.plan(f.request(), AgentTestFixtures.TENANT, AgentTestFixtures.PROJECT_ID,
            AgentTestFixtures.ACTOR.id()).skills()).containsExactly(f.latest);
        assertThatThrownBy(() -> f.planner.plan(f.request(), AgentTestFixtures.TENANT,
            AgentTestFixtures.PROJECT_ID, AgentTestFixtures.OTHER_ACTOR.id())).hasMessageContaining("尚未");
        assertThatThrownBy(() -> f.planner.plan(f.request(), AgentTestFixtures.TENANT,
            AgentTestFixtures.PROJECT_ID + 1, AgentTestFixtures.ACTOR.id())).hasMessageContaining("尚未");
        assertThatThrownBy(() -> f.planner.plan(f.request())).hasMessageContaining("不属于该能力包");
    }

    @Test void resumedRunUsesOriginalPublishedDigestAfterNewVersionWasApproved() {
        var f = new Fixture("my-reviewed-method");
        var resumed = f.planner.plan(f.request(), AgentTestFixtures.TENANT, AgentTestFixtures.PROJECT_ID,
            AgentTestFixtures.ACTOR.id(), List.of(new SkillRef(f.original.name(), f.original.sha256())));
        assertThat(resumed.skills()).containsExactly(f.original);
        assertThat(resumed.snapshot().skills()).containsExactly(new SkillRef(f.original.name(), f.original.sha256()));
        assertThatThrownBy(() -> f.planner.plan(f.request(), AgentTestFixtures.TENANT,
            AgentTestFixtures.PROJECT_ID, AgentTestFixtures.ACTOR.id(),
            List.of(new SkillRef(f.original.name(), "a".repeat(64))))).hasMessageContaining("尚未");
    }

    @Test void scopedApprovalCanReplaceSameNamedBuiltInWithoutChangingOldDigest() {
        var f = new Fixture("competitor-analysis-ipd");
        var builtIn = f.catalog.load(f.original.name()).orElseThrow();
        assertThat(f.planner.plan(f.request(), AgentTestFixtures.TENANT, AgentTestFixtures.PROJECT_ID,
            AgentTestFixtures.ACTOR.id()).skills()).containsExactly(f.latest);
        var old = f.planner.plan(f.request(), AgentTestFixtures.TENANT, AgentTestFixtures.PROJECT_ID,
            AgentTestFixtures.ACTOR.id(), List.of(new SkillRef(builtIn.name(), builtIn.sha256())));
        assertThat(old.skills()).containsExactly(builtIn);
        assertThat(f.planner.plan(f.request()).skills()).containsExactly(builtIn);
    }

    @Test void scopedApprovalDoesNotUnlockOtherBuiltInSkillsOrActionsOutsidePack() {
        var f = new Fixture("product-prd-ipd");
        assertThatThrownBy(() -> f.planner.plan(f.request(), AgentTestFixtures.TENANT,
            AgentTestFixtures.PROJECT_ID, AgentTestFixtures.ACTOR.id())).hasMessageContaining("不属于该能力包");
    }

    private static class Fixture {
        final ProjectAgentSkillCatalog catalog;
        final ProjectAgentRunPlanner planner;
        final ProjectAgentSkillCatalog.LoadedSkill original;
        final ProjectAgentSkillCatalog.LoadedSkill latest;
        Fixture(String name) {
            var manifest = AgentTestFixtures.manifest();
            catalog = AgentTestFixtures.skillCatalog(manifest);
            original = skill(name, "original method"); latest = skill(name, "new approved method");
            catalog.setPublishedResolver(new ProjectAgentSkillCatalog.PublishedResolver() {
                public Optional<ProjectAgentSkillCatalog.LoadedSkill> load(String tenant, Long project, Long person,
                        String requestedName, String digest) {
                    if (!AgentTestFixtures.TENANT.equals(tenant) || !AgentTestFixtures.PROJECT_ID.equals(project)
                        || !AgentTestFixtures.ACTOR.id().equals(person) || !name.equals(requestedName)) return Optional.empty();
                    if (digest == null || latest.sha256().equals(digest)) return Optional.of(latest);
                    return original.sha256().equals(digest) ? Optional.of(original) : Optional.empty();
                }
                public List<ProjectAgentSkillCatalog.SkillStatus> statuses(String tenant, Long project, Long person) {
                    return load(tenant, project, person, name, null).stream().map(s ->
                        new ProjectAgentSkillCatalog.SkillStatus(s.name(), s.version(), s.sha256(), true, null)).toList();
                }
            });
            planner = new ProjectAgentRunPlanner(manifest, catalog, new ProjectAgentToolCatalog(manifest),
                AgentTestFixtures.modelCatalog());
        }
        AgentRunCreateReq request() {
            return new AgentRunCreateReq("market-research", "v1", String.valueOf(AgentTestFixtures.MODEL_ID),
                List.of(original.name()), List.of(), "C02", "请使用我审核的技能", "skill-reviewed-123");
        }
    }
    private static ProjectAgentSkillCatalog.LoadedSkill skill(String name, String body) {
        String md = "---\nname: " + name + "\ndescription: Reviewed method\n---\n\n" + body;
        var file = new ProjectAgentSkillBundle.File("SKILL.md", md,
            ProjectAgentSkillCatalog.sha256Hex(md), "utf-8");
        var bundle = new ProjectAgentSkillBundle(name, ProjectAgentSkillBundle.digest(List.of(file)), List.of(file));
        var skill = io.agentscope.core.skill.util.SkillUtil.createFrom(md, java.util.Map.of());
        return new ProjectAgentSkillCatalog.LoadedSkill(name, "approved-v1", bundle.sha256(), skill.getSkillContent(), bundle);
    }
}
