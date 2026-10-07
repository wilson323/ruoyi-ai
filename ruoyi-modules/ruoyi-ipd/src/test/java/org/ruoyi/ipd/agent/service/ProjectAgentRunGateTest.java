package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.kernel.ProjectAgentIntent;
import org.ruoyi.ipd.agent.support.RunServiceHarness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.MODEL_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.c02;

/**
 * 执行闸门：澄清或未绑定动作的计划停在等待用户，不调用内核、不写产物。
 * 提示词里的「不要调用工具」只是给模型看的句子，闸门以未进入 kernel.execute 为准。
 */
@Tag("dev")
class ProjectAgentRunGateTest {

    @Test
    @DisplayName("澄清：WAITING_APPROVAL + AWAIT_USER，事件里没有工具和产物")
    void clarificationDoesNotExecuteKernel() {
        String message = "做功能对比还是做价格对比";
        ProjectAgentIntent.Decision decision = ProjectAgentIntent.decide(message, "C02", List.of("unused"));
        assertThat(ProjectAgentIntent.prompt(decision)).contains("不要调用工具");

        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        String runId = h.service.create(ACTOR, PROJECT_ID, c02("gate-clarify-01", message)).runId();

        assertThat(h.kernel.executions).isEmpty();
        assertThat(h.store.findRun(Long.valueOf(runId)).orElseThrow().getStatus()).isEqualTo("WAITING_APPROVAL");
        List<IpdAgentRunEvent> events = h.store.events(Long.valueOf(runId));
        assertThat(events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("RUN_STARTED", "STEP", "STEP", "STEP")
            .doesNotContain("TOOL_CALL", "ARTIFACT");
        assertThat(kinds(events)).containsExactly("SKILL_SELECTED", "INTENT", "AWAIT_USER");
        assertThat(payload(events.get(3))).containsEntry("reason", "CLARIFICATION");
    }

    @Test
    @DisplayName("自由计划且未绑定动作：停在 PLAN_CONFIRM，不调用内核")
    void unboundPlanWaitsForConfirm() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        AgentRunCreateReq req = new AgentRunCreateReq("market-research", "v1", String.valueOf(MODEL_ID),
            List.of(), List.of("project_knowledge_search"), null,
            "先分析功能。再对比价格。", "gate-plan-0001");
        String runId = h.service.create(ACTOR, PROJECT_ID, req).runId();

        assertThat(h.kernel.executions).isEmpty();
        List<IpdAgentRunEvent> events = h.store.events(Long.valueOf(runId));
        assertThat(events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("RUN_STARTED", "STEP", "STEP");
        assertThat(kinds(events)).containsExactly("INTENT", "AWAIT_USER");
        assertThat(payload(events.get(2))).containsEntry("reason", "PLAN_CONFIRM");
        assertThat(asStrings(payload(events.get(1)).get("steps")))
            .containsExactly("先分析功能", "再对比价格");
    }

    @Test
    @DisplayName("已确认计划：没有动作也调用内核，事件里没有 AWAIT_USER")
    void confirmedPlanExecutesKernel() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        String message = "按已确认计划执行\n先分析功能、再对比价格\n做功能对比还是做价格对比\n";
        AgentRunCreateReq req = new AgentRunCreateReq("market-research", "v1", String.valueOf(MODEL_ID),
            List.of(), List.of("project_knowledge_search"), null, message, "gate-confirm-0001");
        String runId = h.service.create(ACTOR, PROJECT_ID, req).runId();

        assertThat(h.kernel.executions).hasSize(1);
        List<IpdAgentRunEvent> events = h.store.events(Long.valueOf(runId));
        assertThat(events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("RUN_STARTED", "STEP");
        assertThat(kinds(events)).containsExactly("INTENT").doesNotContain("AWAIT_USER");
        assertThat(asStrings(payload(events.get(1)).get("steps"))).containsExactly(
            "先分析功能、再对比价格",
            "做功能对比还是做价格对比");
        assertThat(payload(events.get(1))).containsEntry("needsClarification", false);
    }

    @Test
    @DisplayName("只有确认开头、没有步骤：仍停在 PLAN_CONFIRM，不调用内核")
    void confirmedHeaderWithoutStepsStillWaits() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        AgentRunCreateReq req = new AgentRunCreateReq("market-research", "v1", String.valueOf(MODEL_ID),
            List.of(), List.of("project_knowledge_search"), null,
            "按已确认计划执行\n\n", "gate-confirm-empty");
        String runId = h.service.create(ACTOR, PROJECT_ID, req).runId();

        assertThat(h.kernel.executions).isEmpty();
        List<IpdAgentRunEvent> events = h.store.events(Long.valueOf(runId));
        assertThat(kinds(events)).containsExactly("INTENT", "AWAIT_USER");
        assertThat(payload(events.get(events.size() - 1))).containsEntry("reason", "PLAN_CONFIRM");
        assertThat(h.store.findRun(Long.valueOf(runId)).orElseThrow().getStatus()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    @DisplayName("已绑定动作且需要计划时先等人确认，不直接跑内核")
    void boundPlanWaitsBeforeKernel() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        String runId = h.service.create(ACTOR, PROJECT_ID,
            c02("gate-still-0001", "我还是要做竞品的功能和价格")).runId();

        assertThat(h.kernel.executions).isEmpty();
        List<IpdAgentRunEvent> events = h.store.events(Long.valueOf(runId));
        assertThat(payload(events.get(events.size() - 1))).containsEntry("reason", "PLAN_CONFIRM");
        assertThat(h.store.findRun(Long.valueOf(runId)).orElseThrow().getStatus()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    @DisplayName("空问题列表不算澄清，不进等待")
    void emptyQuestionsDoNotAwait() {
        ProjectAgentIntent.Decision decision = new ProjectAgentIntent.Decision(
            true, List.of(), false, List.of(), "空问题");
        assertThat(ProjectAgentRunExecutor.shouldAwaitUser(decision, "C02")).isFalse();
        assertThat(ProjectAgentRunExecutor.shouldAwaitUser(
            ProjectAgentIntent.decide("先分析功能。再对比价格。", null, List.of()), null)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStrings(Object raw) {
        return ((List<Object>) raw).stream().map(String::valueOf).toList();
    }

    private static List<String> kinds(List<IpdAgentRunEvent> events) {
        return events.stream()
            .filter(event -> "STEP".equals(event.getEventType()))
            .map(event -> String.valueOf(payload(event).get("kind")))
            .toList();
    }

    private static Map<String, Object> payload(IpdAgentRunEvent event) {
        try {
            return new ObjectMapper().readValue(event.getPayload(), new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
