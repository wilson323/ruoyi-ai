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

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;
    private static final String SKILL = "competitor-analysis-ipd";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "C01,market-opportunity-research-ipd,访谈,意向",
        "C02,competitor-analysis-ipd,功能,技术路线"
    })
    @DisplayName("动作绑定技能经冻结中间件进入真实模型请求系统提示")
    void actionBoundSkillEntersPromptWhenFileExists(String actionCode, String skillName,
                                                   String firstKeyword, String secondKeyword) throws Exception {
        IpdActionSkillMapService maps = mock(IpdActionSkillMapService.class);
        when(maps.findByActionCode(actionCode)).thenReturn(IpdActionSkillMap.builder()
            .actionCode(actionCode)
            .skillNames("[\"" + skillName + "\"]").sortOrder(1).build());
        ProjectAgentRunPlanner planner = planner(maps);

        AgentRunCreateReq req = new AgentRunCreateReq("market-research", "v1",
            String.valueOf(AgentTestFixtures.MODEL_ID), List.of(), List.of("project_knowledge_search"),
            actionCode, "请根据已有资料整理分析与缺项", "idem-action-skill-01");
        ProjectAgentRunPlanner.RunPlan plan = planner.plan(req);

        assertThat(plan.skills()).extracting(s -> s.name()).containsExactly(skillName);
        var captured = new java.util.concurrent.atomic.AtomicReference<List<io.agentscope.core.message.Msg>>();
        var invoked = new java.util.concurrent.CountDownLatch(1);
        var model = mock(io.agentscope.core.model.Model.class);
        when(model.getModelName()).thenReturn("fixture");
        when(model.stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                captured.set(invocation.getArgument(0)); invoked.countDown();
                return reactor.core.publisher.Flux.just(io.agentscope.core.model.ChatResponse.builder()
                    .content(List.of(io.agentscope.core.message.TextBlock.builder().text("done").build()))
                    .finishReason("stop").build());
            });
        var assembler = mock(org.ruoyi.ipd.agent.kernel.ProjectAgentModelAssembler.class);
        when(assembler.assemble(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(model);
        var kernel = new org.ruoyi.ipd.agent.kernel.AgentScopeProjectAgentKernel(
            assembler,
            (project, type, query) -> new org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext(0, 0, ""), workspace, 2);
        var lifecycle = org.ruoyi.ipd.agent.support.AgentKernelTestLifecycle.create();
        var running = kernel.execute(spec(plan, req.message()), lifecycle);
        try {
            assertThat(invoked.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String prompt = captured.get().stream().filter(msg -> msg.getRole() == io.agentscope.core.message.MsgRole.SYSTEM)
                .map(io.agentscope.core.message.Msg::getTextContent).collect(java.util.stream.Collectors.joining());
            String identity = "<skill-id>" + skillName + "_classpath-ipd-skills</skill-id>";
            assertThat(prompt).contains("<available_skills>", "<name>" + skillName + "</name>",
                "<version>" + plan.skills().get(0).version() + "</version>",
                "<ipd-action>" + actionCode + "</ipd-action>", identity,
                firstKeyword, secondKeyword);
            assertThat(prompt.indexOf(identity)).isGreaterThanOrEqualTo(0)
                .isEqualTo(prompt.lastIndexOf(identity));
        } finally { running.dispose(); }
    }

    @Test
    void c01ExistingBindingIsFrozenWithoutInventingFacts() {
        IpdActionSkillMapService maps = mock(IpdActionSkillMapService.class);
        when(maps.findByActionCode("C01")).thenReturn(IpdActionSkillMap.builder()
            .actionCode("C01").skillNames("[\"market-opportunity-research-ipd\"]").build());
        AgentRunCreateReq req = new AgentRunCreateReq("market-research", "v1",
            String.valueOf(AgentTestFixtures.MODEL_ID), List.of(), List.of("project_knowledge_search"),
            "C01", "请整理已提供的访谈和缺项", "idem-c01-skill-01");
        var plan = planner(maps).plan(req);
        assertThat(plan.actionCode()).isEqualTo("C01");
        assertThat(plan.skills()).singleElement().satisfies(skill -> {
            assertThat(skill.name()).isEqualTo("market-opportunity-research-ipd");
            assertThat(skill.version()).isEqualTo("1.1.0");
            assertThat(skill.sha256()).isEqualTo("c116dac4094ed5dea7013a7952943f5482e1632880c40287b4f0130641223c28");
            assertThat(skill.content()).contains("访谈", "未取得", "禁止编造");
        });
        assertThat(plan.toolIds()).containsExactly("project_knowledge_search");
    }

    @Test
    void marketPackDoesNotExpandOtherActionsOrExplicitSkills() {
        IpdActionSkillMapService maps = mock(IpdActionSkillMapService.class);
        var planner = planner(maps);
        var unrelatedAction = new AgentRunCreateReq("market-research", "v1",
            String.valueOf(AgentTestFixtures.MODEL_ID), List.of(), List.of("project_knowledge_search"),
            "C03", "请做分析", "idem-c03-deny-01");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.plan(unrelatedAction))
            .hasMessageContaining("不在能力包适用范围");
        var unrelatedSkill = new AgentRunCreateReq("market-research", "v1",
            String.valueOf(AgentTestFixtures.MODEL_ID), List.of("product-prd-ipd"), List.of(),
            "C01", "请做分析", "idem-skill-deny-01");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.plan(unrelatedSkill))
            .hasMessageContaining("Skill 不属于该能力包");
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

    @Test
    void unavailableActionBindingRejectsRun() {
        IpdActionSkillMapService maps = mock(IpdActionSkillMapService.class);
        when(maps.findByActionCode("C02")).thenReturn(IpdActionSkillMap.builder()
            .actionCode("C02").skillNames("[\"missing-skill\"]").build());
        AgentRunCreateReq req = new AgentRunCreateReq("market-research", "v1",
            String.valueOf(AgentTestFixtures.MODEL_ID), List.of(), List.of("project_knowledge_search"),
            "C02", "请做竞品分析", "idem-missing-skill");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner(maps).plan(req))
            .hasMessageContaining("Skill 不可用");
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
