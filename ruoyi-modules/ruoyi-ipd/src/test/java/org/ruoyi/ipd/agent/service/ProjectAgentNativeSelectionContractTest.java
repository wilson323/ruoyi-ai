package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentNativeToolCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentNativeSelectionContractTest {
    private final CapabilityManifest manifest = new CapabilityManifest(1, List.of(),
        List.of(new CapabilityManifest.ToolEntry("project_knowledge_search", "Knowledge", true)),
        List.of(new CapabilityManifest.PackEntry("native-contract", "v1", "Native", "", List.of(), List.of(), List.of(), List.of())));

    private ProjectAgentRunPlanner planner(String unavailable) {
        var tools = new ProjectAgentToolCatalog(manifest, id -> new ProjectAgentNativeToolCatalog.Readiness(
            !id.equals(unavailable), id.equals(unavailable) ? "测试运行环境不可用" : null));
        return new ProjectAgentRunPlanner(manifest, AgentTestFixtures.skillCatalog(manifest), tools, AgentTestFixtures.modelCatalog());
    }
    private AgentRunCreateReq request(List<String> ids) {
        return new AgentRunCreateReq("native-contract", "v1", AgentTestFixtures.MODEL_ID.toString(), List.of(), ids,
            null, "检查授权工具", "native-selection-contract");
    }

    @Test void everyNativeToolAdvertisedByPackIsSelectableWithoutExpandingBusinessTools() {
        var advertised = ProjectAgentToolCatalog.executionToolIds(List.of());
        var plan = planner(null).plan(request(advertised));
        assertEquals(advertised, plan.toolIds());
        assertEquals(advertised, plan.snapshot().toolIds());
        assertEquals(advertised, plan.executionToolIds());
    }

    @Test void unavailableNativeToolStillFailsAndUnknownOrForeignBusinessToolCannotEnter() {
        var blocked = assertThrows(IpdBusinessException.class, () -> planner("web_search").plan(request(List.of("web_search"))));
        assertTrue(blocked.getMessage().contains("测试运行环境不可用"));
        for (String id : List.of("unknown", "project_knowledge_search", "123456789")) {
            var error = assertThrows(IpdBusinessException.class, () -> planner(null).plan(request(List.of(id))));
            assertTrue(error.getMessage().contains("不属于该能力包"));
        }
    }
}
