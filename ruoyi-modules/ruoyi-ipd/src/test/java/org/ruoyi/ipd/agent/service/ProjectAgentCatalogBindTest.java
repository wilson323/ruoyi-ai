package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.support.FakeProjectAgentKernel;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class ProjectAgentCatalogBindTest {

    @Mock DemandCatalogBinder binder;

    @Test
    void runStartedCarriesCatalogCodesAndSuccessAppliesThem() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        FakeProjectAgentKernel kernel = new FakeProjectAgentKernel();
        ProjectAgentRunExecutor executor = new ProjectAgentRunExecutor(store, kernel, AgentTestFixtures.MAPPER,
            Schedulers.immediate(), () -> 1_700_000_000_000L, 2);
        executor.setDemandBinder(binder);
        IpdAgentRun run = IpdAgentRun.builder().id(11L).tenantId("t").personId(900101L).projectId(9190003L)
            .status("PENDING").agentId("project-agent").build();
        store.insertRun(run);
        DemandCatalogBinder.CatalogHit hit = new DemandCatalogBinder.CatalogHit("catalog-access", "ZK-X");
        when(binder.open(7L, "t")).thenReturn(new DemandCatalogBinder.BindContext("附录", hit));
        ProjectAgentRunSpec spec = new ProjectAgentRunSpec(11L, 9190003L, "t", 900101L, null,
            "门禁考勤一体机要刷脸", List.of(), List.of(),
            new KernelModelRequest("MiniMax-M3", "MiniMax", "sk", "https://example.invalid"),
            Duration.ofMinutes(1), 7L);

        executor.start(run, spec);
        kernel.last().sink().onText("正文");
        kernel.last().sink().onComplete();

        verify(binder).apply(7L, "正文", hit, "t");
        String started = store.listEvents(11L, 0, 20).stream()
            .filter(event -> AgentEventType.RUN_STARTED.name().equals(event.getEventType()))
            .map(IpdAgentRunEvent::getPayload)
            .findFirst()
            .orElse("");
        assertThat(started).contains("\"lineCode\":\"catalog-access\"");
        assertThat(started).contains("\"productCode\":\"ZK-X\"");
        assertThat(started).contains("\"requirementId\":\"7\"");
        assertThat(kernel.last().spec().catalogAppendix()).isEqualTo("附录");
    }
}
