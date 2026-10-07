package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import org.ruoyi.ipd.agent.support.AgentOwnershipTestTransactions;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.support.FakeProjectAgentKernel;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计 D1：生产装配（ownership 非空）下 WAITING_APPROVAL 必须有超时出口。
 *
 * <p>修复前 {@code awaitUser} 仅在 ownership == null 时调度 {@code scheduleAwaitTimeout}，
 * 生产永不为 null，运行会永久停在 WAITING_APPROVAL（事实死状态）。
 */
@Tag("dev")
class ProjectAgentRunExecutorAwaitTimeoutTest {

    @Test
    @DisplayName("生产装配：等待审批挂超时，且句柄释放后仍能收口 FAILED/RUN_TIMEOUT")
    void awaitUserSchedulesTimeoutUnderOwnedAssembly() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        FakeProjectAgentKernel kernel = new FakeProjectAgentKernel();

        ProjectAgentRunOwnership owner = mock(ProjectAgentRunOwnership.class);
        ProjectAgentRunOwnership.Lease lease = mock(ProjectAgentRunOwnership.Lease.class);
        when(owner.acquire(anyLong())).thenReturn(Optional.of(lease));
        when(lease.held()).thenReturn(true);
        when(lease.token()).thenReturn(9L);

        List<Runnable> startTasks = new ArrayList<>();
        List<Runnable> timeoutTasks = new ArrayList<>();
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.schedule(any(Runnable.class))).thenAnswer(call -> {
            startTasks.add(call.getArgument(0));
            return mock(Disposable.class);
        });
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
            timeoutTasks.add(call.getArgument(0));
            return mock(Disposable.class);
        });
        when(scheduler.schedulePeriodically(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
            .thenReturn(mock(Disposable.class));

        ProjectAgentRunExecutor executor = new ProjectAgentRunExecutor(store, kernel,
            AgentTestFixtures.MAPPER, scheduler, () -> 1_700_000_000_000L, 2);
        executor.setOwnership(owner);
        executor.setFinishTransaction(AgentOwnershipTestTransactions.create());

        IpdAgentRun run = IpdAgentRun.builder().id(11L).tenantId("t").personId(1L).projectId(2L)
            .status("PENDING").version(0).agentId("project-agent").build();
        store.insertRun(run);
        ProjectAgentRunSpec spec = new ProjectAgentRunSpec(11L, 2L, "t", 1L, null, "看一下",
            List.of(), List.of(), new KernelModelRequest("m", "p", "sk", "https://example.invalid"),
            Duration.ofSeconds(60));

        executor.submit(run, spec);
        startTasks.forEach(Runnable::run);

        assertThat(store.findRun(11L).orElseThrow().getStatus()).isEqualTo("WAITING_APPROVAL");
        assertThat(kernel.executions).isEmpty();
        // 修复前：ownership 非空 → 不调度，这里为 0，运行无出口。
        assertThat(timeoutTasks).hasSize(1);

        timeoutTasks.forEach(Runnable::run);

        IpdAgentRun finished = store.findRun(11L).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo("FAILED");
        assertThat(finished.getErrorCode()).isEqualTo("RUN_TIMEOUT");
    }
}
