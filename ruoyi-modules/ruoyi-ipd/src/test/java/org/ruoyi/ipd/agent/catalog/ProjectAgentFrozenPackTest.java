package org.ruoyi.ipd.agent.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.service.ProjectAgentRunPlanner;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews.ConfigSnapshot;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentFrozenPackTest {
    final CapabilityManifest manifest=AgentTestFixtures.manifest();
    final ProjectAgentPackCatalog dynamic=mock(ProjectAgentPackCatalog.class);
    AgentRunCreateReq req() {
        return new AgentRunCreateReq("market-research", "v1", String.valueOf(AgentTestFixtures.MODEL_ID),
            List.of(), List.of("project_knowledge_search"), "C02", "继续整理已有资料", "idem-frozen-pack-01");
    }
    ProjectAgentRunPlanner planner() {
        return new ProjectAgentRunPlanner(manifest, AgentTestFixtures.skillCatalog(manifest),
            new ProjectAgentToolCatalog(manifest), AgentTestFixtures.modelCatalog(), null, dynamic);
    }
    @Test void frozenPackSurvivesJsonAndAllSnapshotCopyOperations() throws Exception {
        var pack=manifest.pack("market-research", "v1").orElseThrow();
        var snapshot=new ConfigSnapshot(pack.code(), pack.version(), "1", List.of(), List.of())
            .withFrozenPack(pack).withOutputContractVersion(1).withModelIdentityVersion(1, null)
            .withExecutionToolIds(List.of()).withServerFrontendTools(List.of()).withModelFingerprint(null);
        var json=new ObjectMapper();
        var restored=json.readValue(json.writeValueAsBytes(snapshot), ConfigSnapshot.class);
        assertThat(restored.frozenPack()).isEqualTo(pack);
    }
    @Test void administratorDeletionCannotChangeExistingRunPack() {
        var pack=manifest.pack("market-research", "v1").orElseThrow();
        var recovered=planner().plan(req(), "tenant-a", 1L, 1L, List.of(), pack);
        assertThat(recovered.pack()).isEqualTo(pack);
        assertThat(recovered.snapshot().frozenPack()).isEqualTo(pack);
        verifyNoInteractions(dynamic);
    }
    @Test void historicalBuiltinRunDoesNotPickUpNewDatabaseOverride() {
        var recovered=planner().plan(req(), "tenant-a", 1L, 1L, List.of());
        assertThat(recovered.pack().actionCodes()).contains("C01", "C02");
        verifyNoInteractions(dynamic);
    }
    @Test void wrongFrozenPackIdentityIsRejected() {
        var pack=manifest.pack("ipd-plan", "v1").orElseThrow();
        assertThatThrownBy(() -> planner().plan(req(), "tenant-a", 1L, 1L, List.of(), pack))
            .hasMessageContaining("冻结身份不一致");
        verifyNoInteractions(dynamic);
    }
}
