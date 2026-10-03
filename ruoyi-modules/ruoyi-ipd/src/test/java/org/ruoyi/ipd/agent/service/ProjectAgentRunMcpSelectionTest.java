package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.catalog.ProductLineMcpCatalog;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.support.RunServiceHarness;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.mapper.ProductLineNameMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.*;

@Tag("dev")
class ProjectAgentRunMcpSelectionTest {
    @Test
    void mismatchIsRejectedBeforeScheduling() {
        RunServiceHarness h = harness(ProductLineMcpCatalog.all().get(0).serviceId());
        String other = ProductLineMcpCatalog.all().get(1).serviceId();
        assertThatThrownBy(() -> h.service.create(ACTOR, PROJECT_ID, request("mcp-mismatch", List.of(other))))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("未包含项目绑定");
        assertThat(h.deferred).isEmpty();
    }

    @Test
    void emptyChoiceAndUnboundProjectRemainAllowed() {
        RunServiceHarness empty = harness("unknown-service");
        empty.service.create(ACTOR, PROJECT_ID, request("mcp-empty-01", List.of()));
        assertThat(empty.deferred).hasSize(1);
        RunServiceHarness unbound = harness(null);
        unbound.service.create(ACTOR, PROJECT_ID,
            request("mcp-unbound-01", List.of(ProductLineMcpCatalog.all().get(0).serviceId())));
        assertThat(unbound.deferred).hasSize(1);
    }

    private RunServiceHarness harness(String serviceId) {
        RunServiceHarness h = new RunServiceHarness(true, true, 4);
        ProductLineNameMapper names = mock(ProductLineNameMapper.class);
        when(names.selectServiceId(PROJECT_ID)).thenReturn(serviceId);
        h.service.setProductLineNames(names);
        return h;
    }

    private AgentRunCreateReq request(String key, List<String> tools) {
        return new AgentRunCreateReq("market-research", "v1", String.valueOf(MODEL_ID),
            List.of("competitor-analysis-ipd"), tools, "C02", "竞品分析", key);
    }
}
