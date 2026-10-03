package org.ruoyi.ipd.agent.kernel;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("dev")
class ProjectAgentClarificationContractTest {
    static Map<String,Object> input() { return Map.of("kind", "CLARIFICATION", "questions", List.of(
        Map.of("id", "scope", "prompt", "报告覆盖范围？", "options", List.of(Map.of("id", "a", "label", "国内"), Map.of("id", "b", "label", "全球"))))); }
    @Test void flatAnswerSchemaUsesStableOptionIdsNotLabels() {
        var schema = ProjectAgentOutputContract.responseSchema(input());
        assertEquals(false, schema.get("additionalProperties")); assertEquals(List.of("scope"), schema.get("required"));
        var property = (Map<?,?>)((Map<?,?>)schema.get("properties")).get("scope");
        assertEquals(List.of("a", "b"), property.get("enum")); assertEquals("报告覆盖范围？", property.get("title"));
    }
    @Test void duplicateQuestionsUnknownKindAndArbitraryFieldsAreRejected() {
        var source = new LinkedHashMap<>(input()); source.put("approved", true);
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOutputContract.responseSchema(source));
        var q = ((List<?>)input().get("questions")).get(0);
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOutputContract.responseSchema(Map.of("kind", "CLARIFICATION", "questions", List.of(q,q))));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOutputContract.responseSchema(Map.of("kind", "DOCUMENT", "questions", List.of(q))));
    }
    @Test void invalidStableIdOrDuplicateOptionFails() {
        var options = List.of(Map.of("id", "a", "label", "国内"), Map.of("id", "a", "label", "全球"));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOutputContract.responseSchema(Map.of("kind", "CLARIFICATION", "questions", List.of(Map.of("id", "scope", "prompt", "范围？", "options", options)))));
    }
    @Test void answersRejectUnknownIdsLabelsAndApprovalPayload() {
        assertDoesNotThrow(() -> ProjectAgentOutputContract.validateAnswers(input(), Map.of("scope", "a")));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOutputContract.validateAnswers(input(), Map.of("scope", "国内")));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOutputContract.validateAnswers(input(), Map.of("approved", true)));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentOutputContract.validateAnswers(input(), Map.of("scope", "a", "other", "b")));
    }
    @Test void serverResumeRejectsLabelsAndPermissionApprovalEvenForValidInterrupt() {
        var interrupt = new io.agentscope.core.agui.event.AguiEvent.Interrupt("q", "tool_call", "回答范围", "call",
            ProjectAgentOutputContract.responseSchema(input()), null, Map.of("toolName", ProjectAgentOutputContract.CLARIFICATION_TOOL, "toolInput", input()));
        var binding = new org.ruoyi.ipd.agent.service.ProjectAgentAguiResumeValidation.Binding("run", "run", "owner", 1, 2, Map.of("q", interrupt));
        for (var payload : List.of(Map.<String,Object>of("scope", "a"), Map.<String,Object>of("scope", "国内"), Map.<String,Object>of("approved", true))) {
            var request = io.agentscope.core.agui.model.RunAgentInput.builder().threadId("run").runId("run")
                .resume(List.of(new io.agentscope.core.agui.model.AguiResume("q", "resolved", payload))).build();
            if (payload.equals(Map.of("scope", "a"))) assertDoesNotThrow(() -> org.ruoyi.ipd.agent.service.ProjectAgentAguiResumeValidation.validate(request, binding, "owner", 1, 2, java.time.Instant.now()));
            else assertThrows(IllegalArgumentException.class, () -> org.ruoyi.ipd.agent.service.ProjectAgentAguiResumeValidation.validate(request, binding, "owner", 1, 2, java.time.Instant.now()));
        }
    }
    @Test void childCannotAskParentAndMissingStateCannotBypassSchemaValidation() {
        var scope = org.ruoyi.chat.kernel.KernelScopeKey.of("2", "1", org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID, "3");
        var agent = org.mockito.Mockito.mock(io.agentscope.core.agent.Agent.class);
        var governance = new ProjectAgentOfficialToolGovernance(org.mockito.Mockito.mock(ProjectAgentEventSink.class), scope, (a,c) -> true);
        var child = io.agentscope.core.agent.RuntimeContext.builder().userId(scope.userId()).sessionId("child-session").build();
        var use = io.agentscope.core.message.ToolUseBlock.builder().id("q").name(ProjectAgentOutputContract.CLARIFICATION_TOOL).input(input()).build();
        assertThrows(SecurityException.class, () -> governance.onActing(agent, child, new io.agentscope.core.middleware.ActingInput(List.of(use)), v -> reactor.core.publisher.Flux.empty()).blockLast());
        var root = io.agentscope.core.agent.RuntimeContext.builder().userId(scope.userId()).sessionId(scope.sessionId()).build();
        var invalid = io.agentscope.core.message.ToolUseBlock.builder().id("q").name(ProjectAgentOutputContract.CLARIFICATION_TOOL).input(Map.of("approved", true)).build();
        assertThrows(IllegalArgumentException.class, () -> governance.onActing(agent, root, new io.agentscope.core.middleware.ActingInput(List.of(invalid)), v -> reactor.core.publisher.Flux.empty()).blockLast());
    }
    static Map<String,Object> freeInput() { return Map.of("kind", "CLARIFICATION", "questions", List.of(Map.of("id", "scope", "prompt", "请说明具体范围？", "options", List.of()))); }
    @Test void freeTextSchemaAndServerAnswersHaveStrictBounds() {
        var property = (Map<?,?>)((Map<?,?>)ProjectAgentOutputContract.responseSchema(freeInput()).get("properties")).get("scope");
        assertEquals(Map.of("type", "string", "title", "请说明具体范围？", "minLength", 1, "maxLength", 4000), property);
        assertDoesNotThrow(() -> ProjectAgentOutputContract.validateAnswers(freeInput(), Map.of("scope", "  华东地区，按实际市场事实  ")));
        for (var invalid : List.of(Map.<String,Object>of("scope", "  "), Map.<String,Object>of("scope", "x".repeat(4001)), Map.<String,Object>of("scope", 3), Map.<String,Object>of("other", "a"), Map.<String,Object>of("approved", true)))
            assertThrows(IllegalArgumentException.class, () -> ProjectAgentOutputContract.validateAnswers(freeInput(), invalid));
    }
}
