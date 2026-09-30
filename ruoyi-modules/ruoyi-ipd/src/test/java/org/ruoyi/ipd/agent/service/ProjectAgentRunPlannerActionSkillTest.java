package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.kernel.ProjectAgentPrompt;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.service.IpdActionSkillMapService;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 按 actionCode 查库绑定 Skill：有绑定且 classpath 有 SKILL.md 则进入系统提示；
 * NULL 绑定不注入技能名。
 */
@Tag("dev")
class ProjectAgentRunPlannerActionSkillTest {

    private static final String SKILL = "competitor-analysis-ipd";

    @Test
    @DisplayName("C02 有绑定且文件存在：plan.skills 与系统提示含 competitor-analysis-ipd")
    void actionBoundSkillEntersPromptWhenFileExists() {
        IpdActionSkillMapService maps = mock(IpdActionSkillMapService.class);
        when(maps.findByActionCode("C02")).thenReturn(IpdActionSkillMap.builder()
            .actionCode("C02").subStageCode("CONCEPT-S2")
            .skillNames("[\"competitor-analysis-ipd\"]").sortOrder(1).build());
        ProjectAgentRunPlanner planner = planner(maps);

        AgentRunCreateReq req = new AgentRunCreateReq("market-research", "v1",
            String.valueOf(AgentTestFixtures.MODEL_ID), List.of(), List.of("project_knowledge_search"),
            "C02", "请做竞品分析", "idem-action-skill-01");
        ProjectAgentRunPlanner.RunPlan plan = planner.plan(req);

        assertThat(plan.skills()).extracting(s -> s.name()).containsExactly(SKILL);
        String prompt = ProjectAgentPrompt.build(spec(plan, req.message()));
        assertThat(prompt).contains("Skill: " + SKILL).contains("功能").contains("技术路线");
    }

    @Test
    @DisplayName("动作 skill_names 为 NULL：plan.skills 为空，系统提示不含技能名")
    void nullActionBindingKeepsPromptFreeOfSkill() {
        IpdActionSkillMapService maps = mock(IpdActionSkillMapService.class);
        when(maps.findByActionCode("C02")).thenReturn(IpdActionSkillMap.builder()
            .actionCode("C02").subStageCode("CONCEPT-S2").skillNames(null).sortOrder(1).build());
        ProjectAgentRunPlanner planner = planner(maps);

        AgentRunCreateReq req = new AgentRunCreateReq("market-research", "v1",
            String.valueOf(AgentTestFixtures.MODEL_ID), List.of(), List.of("project_knowledge_search"),
            "C02", "请做竞品分析", "idem-action-skill-02");
        ProjectAgentRunPlanner.RunPlan plan = planner.plan(req);

        assertThat(plan.skills()).isEmpty();
        String prompt = ProjectAgentPrompt.build(spec(plan, req.message()));
        assertThat(prompt).doesNotContain("Skill: " + SKILL).doesNotContain(SKILL);
    }

    private static ProjectAgentRunPlanner planner(IpdActionSkillMapService maps) {
        CapabilityManifest manifest = AgentTestFixtures.manifest();
        return new ProjectAgentRunPlanner(manifest, AgentTestFixtures.skillCatalog(manifest),
            new ProjectAgentToolCatalog(manifest), AgentTestFixtures.modelCatalog(), maps);
    }

    private static ProjectAgentRunSpec spec(ProjectAgentRunPlanner.RunPlan plan, String message) {
        return new ProjectAgentRunSpec(1L, AgentTestFixtures.PROJECT_ID, AgentTestFixtures.TENANT,
            AgentTestFixtures.ACTOR.id(), plan.actionCode(), message, plan.skills(), plan.toolIds(),
            plan.model(), Duration.ofSeconds(30));
    }
}
