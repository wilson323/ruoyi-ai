package org.ruoyi.ipd.agent.service;

import io.agentscope.core.agui.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.support.RunServiceHarness;
import org.ruoyi.ipd.common.IpdBusinessException;
import static org.junit.jupiter.api.Assertions.*;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.*;

@Tag("dev")
class ProjectAgentAguiCreateIntegrationTest {
    private AgentRunCreateReq req(String key, RunAgentInput input) {
        var old = c02(key, "请对本项目做竞品分析：功能、价格、渠道、技术路线");
        return new AgentRunCreateReq(old.capabilityPackCode(), old.capabilityPackVersion(), old.modelConfigId(),
            old.skillNames(), old.toolIds(), old.actionCode(), old.message(), old.idempotencyKey(),
            old.productLineId(), old.requirementId(), old.previousRunId(), old.targetDocumentId(), old.baseVersionId(), input);
    }
    private RunAgentInput input(String text) {
        return RunAgentInput.builder().threadId("client-thread").runId("client-run")
            .messages(List.of(AguiMessage.userMessage("m", text)))
            .state(Map.of("tab", "steps")).build();
    }
    @Test void originalCreateBindsFullInputAndPersistsOnlyDigest() {
        var h = new RunServiceHarness(true, false, 4);
        var created = h.service.create(ACTOR, PROJECT_ID, req("agui-create-01", input("private full message")));
        var run = h.store.findRun(Long.valueOf(created.runId())).orElseThrow();
        var spec = h.kernel.last().spec();
        assertEquals(created.runId(), spec.aguiInput().getRunId());
        assertEquals(created.runId(), spec.aguiInput().getThreadId());
        assertEquals("private full message", spec.aguiInput().getMessages().get(0).getTextContent());
        assertTrue(run.getConfigSnapshot().contains("aguiInputDigest"));
        assertFalse(run.getConfigSnapshot().contains("private full message"));
        assertEquals(64, run.getInputDigest().length());
        assertThrows(IpdBusinessException.class, () -> h.service.create(ACTOR, PROJECT_ID,
            req("agui-create-01", input("changed protocol message"))));
        assertEquals(1, h.kernel.executions.size());
    }
    @Test void frontendToolWithoutServerCatalogAndResumeCreationAreRejectedBeforeWrite() {
        var h = new RunServiceHarness(true, false, 4);
        var tool = RunAgentInput.builder().threadId("t").runId("r")
            .messages(input("question").getMessages())
            .tools(List.of(new AguiTool("arbitrary", "client claims authority", Map.of("type", "object")))).build();
        assertThrows(IpdBusinessException.class, () -> h.service.create(ACTOR, PROJECT_ID, req("agui-create-02", tool)));
        var resumed = RunAgentInput.builder().threadId("t").runId("r")
            .messages(input("question").getMessages())
            .resume(List.of(new AguiResume("i", "resolved", Map.of("approved", true)))).build();
        assertThrows(IpdBusinessException.class, () -> h.service.create(ACTOR, PROJECT_ID, req("agui-create-03", resumed)));
        assertEquals(0, h.store.runInserts.get());
        assertTrue(h.kernel.executions.isEmpty());
    }
    @Test void resumePreparationRevalidatesOriginalConfigurationWithoutCreatingRun() throws Exception {
        var h = new RunServiceHarness(true, false, 4);
        var created = h.service.create(ACTOR, PROJECT_ID, req("agui-create-04", input("original")));
        Long runId = Long.valueOf(created.runId());
        h.store.transition(runId, java.util.Set.of(org.ruoyi.ipd.agent.model.AgentRunStatus.RUNNING),
            org.ruoyi.ipd.agent.model.AgentRunStatus.WAITING_APPROVAL, null, new java.util.Date());
        var resume = RunAgentInput.builder().threadId(created.runId()).runId(created.runId())
            .resume(List.of(new AguiResume("i", "resolved", Map.of("approved", false)))).build();
        var spec = h.service.prepareAguiResume(ACTOR, runId, resume);
        assertEquals(runId, spec.runId());
        assertEquals(ACTOR.id(), spec.personId());
        assertEquals(PROJECT_ID, spec.projectId());
        assertEquals(1, h.store.runInserts.get());
        assertEquals(1, h.kernel.executions.size());
        assertThrows(IpdBusinessException.class, () -> h.service.prepareAguiResume(ACTOR, runId, input("wrong ids")));
        var run = h.store.findRun(runId).orElseThrow();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var snapshot = json.readTree(run.getConfigSnapshot());
        ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.get("skills").get(0)).put("sha256", "changed");
        // 内存 store 查找返回副本；直接改测试存储行模拟持久配置损坏。
        var rows = h.store.getClass().getDeclaredField("runs");
        rows.setAccessible(true);
        ((org.ruoyi.ipd.agent.domain.IpdAgentRun)((Map<?,?>)rows.get(h.store)).get(runId))
            .setConfigSnapshot(json.writeValueAsString(snapshot));
        assertThrows(IpdBusinessException.class, () -> h.service.prepareAguiResume(ACTOR, runId, resume));
    }
    @Test void detailReportsOnlyCurrentUnconsumedPauseSequence() {
        var h = new RunServiceHarness(true, false, 4);
        var created = h.service.create(ACTOR, PROJECT_ID, req("agui-create-05", input("original")));
        Long runId = Long.valueOf(created.runId());
        var run = h.store.findRun(runId).orElseThrow();
        var handle = new ProjectAgentRunHandle(run, h.store, MAPPER, h.clock::get, () -> { });
        handle.onStep("AWAIT_USER", Map.of("reason", "AGUI_INTERRUPT"));
        long oldPause = h.store.maxSeq(runId);
        handle.onStep("AGUI_RESUMED", Map.of("pauseSeq", oldPause));
        handle.onStep("AWAIT_USER", Map.of("reason", "AGUI_INTERRUPT"));
        long currentPause = h.store.maxSeq(runId);
        h.store.transition(runId, java.util.Set.of(org.ruoyi.ipd.agent.model.AgentRunStatus.RUNNING),
            org.ruoyi.ipd.agent.model.AgentRunStatus.WAITING_APPROVAL, null, new java.util.Date());
        assertEquals(currentPause, h.service.get(ACTOR, runId).pauseSeq());
        handle.onStep("AGUI_RESUMED", Map.of("pauseSeq", currentPause));
        assertNull(h.service.get(ACTOR, runId).pauseSeq());
        h.store.transition(runId, java.util.Set.of(org.ruoyi.ipd.agent.model.AgentRunStatus.WAITING_APPROVAL),
            org.ruoyi.ipd.agent.model.AgentRunStatus.RUNNING, null, new java.util.Date());
        assertNull(h.service.get(ACTOR, runId).pauseSeq());
    }
    @Test void resumeFrontendCatalogUsesServerSchemaAndRejectsClientUnknownTool() throws Exception {
        var h = new RunServiceHarness(true, false, 4);
        var field = ProjectAgentRunService.class.getDeclaredField("planner"); field.setAccessible(true);
        var planner = (ProjectAgentRunPlanner)field.get(h.service);
        planner.setAguiFrontendToolResolver((plan, client) -> {
            assertEquals(List.of("project_knowledge_search"), plan.toolIds());
            return Map.of("ui_preview", new AguiTool("ui_preview", "server authorized preview",
                Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")))));
        });
        var created = h.service.create(ACTOR, PROJECT_ID, req("agui-create-06", org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.withFrontendTools(input("original"),
            List.of(new AguiTool("ui_preview", "client schema", Map.of())))));
        Long runId = Long.valueOf(created.runId());
        h.store.transition(runId, java.util.Set.of(org.ruoyi.ipd.agent.model.AgentRunStatus.RUNNING),
            org.ruoyi.ipd.agent.model.AgentRunStatus.WAITING_APPROVAL, null, new java.util.Date());
        var client = RunAgentInput.builder().threadId(created.runId()).runId(created.runId())
            .tools(List.of(new AguiTool("ui_preview", "forged schema", Map.of("unsafe", true)))).build();
        var canonical = h.service.resolveAguiResumeTools(ACTOR, runId, client).get("ui_preview");
        assertEquals("server authorized preview", canonical.getDescription());
        assertFalse(canonical.getParameters().containsKey("unsafe"));
        assertTrue(h.service.resolveAguiResumeTools(ACTOR, runId, client).containsKey("request_clarification"));
        var internalForged = RunAgentInput.builder().threadId(created.runId()).runId(created.runId())
            .tools(List.of(client.getTools().get(0), new AguiTool("request_clarification", "forged", Map.of("unsafe", true)))).build();
        assertFalse(h.service.resolveAguiResumeTools(ACTOR, runId, internalForged).get("request_clarification").getParameters().containsKey("unsafe"));
        var omitted = RunAgentInput.builder().threadId(created.runId()).runId(created.runId()).build();
        assertEquals(canonical.getDescription(), h.service.resolveAguiResumeTools(ACTOR, runId, omitted)
            .get("ui_preview").getDescription());
        assertEquals(32, h.service.get(ACTOR, runId).configSnapshot().executionToolIds().size());
        var unknown = RunAgentInput.builder().threadId(created.runId()).runId(created.runId())
            .tools(List.of(new AguiTool("unapproved", "forged", Map.of()))).build();
        assertThrows(IpdBusinessException.class, () -> h.service.resolveAguiResumeTools(ACTOR, runId, unknown));
        assertEquals(1, h.store.runInserts.get());
    }
    @Test void executionSnapshotKeepsHistoricalScopeAndSeparateBusinessSelection() {
        var historical = new org.ruoyi.ipd.agent.vo.ProjectAgentViews.ConfigSnapshot(
            "pack", "v1", "1", List.of(), List.of("project_knowledge_search"));
        assertNull(historical.executionToolIds());
        var ids = org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog.executionToolIds(historical.toolIds());
        var current = historical.withExecutionToolIds(ids).withServerFrontendTools(List.of());
        assertEquals(32, current.executionToolIds().size());
        assertEquals(List.of("project_knowledge_search"), current.toolIds());
        assertThrows(UnsupportedOperationException.class, () -> current.executionToolIds().add("forged"));
        assertNull(historical.withServerFrontendTools(List.of()).executionToolIds());
    }

    @Test void resumeOwnershipRaceOnlyReplaysSameConsumedResponse() throws Exception {
        for (boolean alreadyConsumed : new boolean[] {false, true}) {
            var h = new RunServiceHarness(true, false, 4);
            var created = h.service.create(ACTOR, PROJECT_ID,
                req(alreadyConsumed ? "agui-race-done" : "agui-race-busy", input("original")));
            Long runId = Long.valueOf(created.runId());
            var pauses = org.mockito.Mockito.mock(ProjectAgentAguiPauseResumeService.class);
            org.mockito.Mockito.when(pauses.replayConsumed(org.mockito.ArgumentMatchers.eq(ACTOR),
                org.mockito.ArgumentMatchers.eq(runId), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any())).thenReturn(false, alreadyConsumed);
            h.service.setAguiPauseResume(pauses, (a,r,p,i) -> Map.of());
            var competing = org.mockito.Mockito.mock(ProjectAgentRunExecutor.class);
            org.mockito.Mockito.doThrow(new ProjectAgentRunOwnership.OwnershipLost())
                .when(competing).resumePrepared(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            var field = ProjectAgentRunService.class.getDeclaredField("executor");
            field.setAccessible(true); field.set(h.service, competing);
            var answer = RunAgentInput.builder().threadId(created.runId()).runId(created.runId()).build();
            if (alreadyConsumed) assertEquals(created.runId(), h.service.resume(ACTOR, runId, 1L, answer).runId());
            else {
                var conflict = assertThrows(IpdBusinessException.class,
                    () -> h.service.resume(ACTOR, runId, 1L, answer));
                assertEquals(org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT, conflict.getErrorCode());
            }
            org.mockito.Mockito.verify(pauses, org.mockito.Mockito.never()).consume(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

}
