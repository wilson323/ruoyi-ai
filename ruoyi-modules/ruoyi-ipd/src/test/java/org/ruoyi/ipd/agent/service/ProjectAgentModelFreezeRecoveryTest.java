package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agui.model.RunAgentInput;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.support.RunServiceHarness;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.*;

@Tag("dev")
class ProjectAgentModelFreezeRecoveryTest {
    private Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private ProjectAgentRunPlanner planner(RunServiceHarness h) throws Exception {
        return (ProjectAgentRunPlanner) field(h.service, "planner");
    }
    private AiModelConfigMapper models(RunServiceHarness h) throws Exception {
        return (AiModelConfigMapper) field((ProjectAgentModelCatalog) field(planner(h), "modelCatalog"), "mapper");
    }
    private Long pause(RunServiceHarness h, String key) {
        Long id = Long.valueOf(h.service.create(ACTOR, PROJECT_ID,
            c02(key, "按已确认计划执行\n1. 整理现有资料")).runId());
        assertTrue(h.store.transition(id, Set.of(AgentRunStatus.RUNNING), AgentRunStatus.WAITING_APPROVAL, null, new java.util.Date()));
        return id;
    }
    private RunAgentInput input(Long id) { return RunAgentInput.builder().runId(id.toString()).threadId(id.toString()).build(); }

    @Test void primaryAndFallbackDriftAreRejectedBeforeResume() throws Exception {
        var h = new RunServiceHarness(true, false, 4);
        var mapper = models(h);
        var primary = mapper.selectById(MODEL_ID);
        Long id = pause(h, "model-freeze-main");
        var resumed = h.service.prepareAguiResume(ACTOR, id, input(id));
        assertNotNull(resumed.frozenModels());
        assertNotNull(h.service.get(ACTOR, id).configSnapshot().modelFingerprint());
        assertFalse(h.store.findRun(id).orElseThrow().getConfigSnapshot().contains("sk-test-only"));
        primary.setEndpointUrl("https://changed.example/v1");
        assertThrows(IpdBusinessException.class, () -> h.service.prepareAguiResume(ACTOR, id, input(id)));

        var second = new RunServiceHarness(true, false, 4);
        var secondMapper = models(second);
        var backup = new org.ruoyi.ipd.domain.AiModelConfig();
        backup.setId(202L); backup.setProvider("zhipu"); backup.setModelName("GLM-5.3-Flash");
        backup.setEndpointUrl("https://fixture.example/v1");
        String name = secondMapper.selectById(MODEL_ID).getModelName();
        backup.setConfigJson("{\"fallbackFor\":\"" + name + "\"}");
        when(secondMapper.selectList(any())).thenReturn(List.of(backup));
        Long backupRun = pause(second, "model-freeze-backup");
        assertEquals("GLM-5.3-Flash", second.service.prepareAguiResume(ACTOR, backupRun, input(backupRun)).frozenModels().fallback().modelName());
        backup.setId(203L);
        assertThrows(IpdBusinessException.class, () -> second.service.prepareAguiResume(ACTOR, backupRun, input(backupRun)));
        backup.setId(202L);
        backup.setModelName("changed-backup");
        assertThrows(IpdBusinessException.class, () -> second.service.prepareAguiResume(ACTOR, backupRun, input(backupRun)));
    }

    @Test void frontendToolAuthorizationResolvesModelOnlyOnce() throws Exception {
        var h = new RunServiceHarness(true, false, 4);
        Long id = pause(h, "model-freeze-tool");
        var mapper = models(h);
        clearInvocations(mapper);
        assertEquals(java.util.Set.of("request_clarification"), h.service.resolveAguiResumeTools(ACTOR, id, input(id)).keySet());
        verify(mapper, times(1)).selectById(MODEL_ID);
        verify(mapper, times(1)).selectList(any());
    }

    @Test void historicalRunRemainsReadableButCannotPretendToFreezeOriginalModel() throws Exception {
        var h = new RunServiceHarness(true, false, 4);
        Long id = pause(h, "model-freeze-legacy");
        var persisted = h.store.findRun(id).orElseThrow();
        ObjectNode snapshot = (ObjectNode) MAPPER.readTree(persisted.getConfigSnapshot());
        snapshot.remove("modelIdentityVersion"); // 参数指纹尚在，但历史快照没有模型行身份。
        ((org.ruoyi.ipd.agent.domain.IpdAgentRun) ((Map<?, ?>) field(h.store, "runs")).get(id))
            .setConfigSnapshot(MAPPER.writeValueAsString(snapshot));
        assertNotNull(h.service.get(ACTOR, id));
        var error = assertThrows(IpdBusinessException.class, () -> h.service.prepareAguiResume(ACTOR, id, input(id)));
        assertTrue(error.getMessage().contains("关联的新尝试"));
        assertEquals(AgentRunStatus.WAITING_APPROVAL.name(), h.store.findRun(id).orElseThrow().getStatus());
        h.service.cancel(ACTOR, id);
        assertEquals(AgentRunStatus.CANCELLED.name(), h.store.findRun(id).orElseThrow().getStatus());
    }
    @Test void unsupportedOrMissingOutputVersionIsReadableAndCancelableButNotResumable() throws Exception {
        for (Integer version : java.util.Arrays.asList(null, 99)) {
            var h = new RunServiceHarness(true, false, 4); Long id = pause(h, "output-version-" + version);
            ObjectNode snapshot = (ObjectNode) MAPPER.readTree(h.store.findRun(id).orElseThrow().getConfigSnapshot());
            if (version == null) snapshot.remove("outputContractVersion"); else snapshot.put("outputContractVersion", version);
            ((org.ruoyi.ipd.agent.domain.IpdAgentRun) ((Map<?, ?>) field(h.store, "runs")).get(id)).setConfigSnapshot(MAPPER.writeValueAsString(snapshot));
            assertNotNull(h.service.get(ACTOR, id));
            assertThrows(IpdBusinessException.class, () -> h.service.prepareAguiResume(ACTOR, id, input(id)));
            assertThrows(IpdBusinessException.class, () -> h.service.resolveAguiResumeTools(ACTOR, id, input(id)));
            assertEquals(AgentRunStatus.WAITING_APPROVAL.name(), h.store.findRun(id).orElseThrow().getStatus());
            h.service.cancel(ACTOR, id); assertEquals(AgentRunStatus.CANCELLED.name(), h.store.findRun(id).orElseThrow().getStatus());
        }
    }
}
