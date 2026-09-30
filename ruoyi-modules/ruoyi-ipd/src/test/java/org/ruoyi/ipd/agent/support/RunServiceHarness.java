package org.ruoyi.ipd.agent.support;

import org.ruoyi.ipd.agent.service.ProjectAgentRunExecutor;
import org.ruoyi.ipd.agent.service.ProjectAgentRunService;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行服务测试装配：内存存储 + 替身内核 + 真实执行器/规划器 + 访问守卫替身。
 *
 * <p>访问守卫替身语义对齐 {@code IpdCopilotAccess}：ACTOR/OTHER_ACTOR 对 PROJECT_ID 可见（返回租户），
 * 对 OTHER_PROJECT_ID 抛 NOT_FOUND（非成员/跨项目），FROZEN 身份抛 FORBIDDEN（冻结/非 FULL）。
 */
public final class RunServiceHarness {

    public static final IpdActor FROZEN = new IpdActor(13L, "冻结账号", "MARKET_PM", 100L);

    public final InMemoryAgentRunStore store = new InMemoryAgentRunStore();
    public final FakeProjectAgentKernel kernel = new FakeProjectAgentKernel();
    public final IpdCopilotAccess access = mock(IpdCopilotAccess.class);
    public final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    public final List<Runnable> deferred = new ArrayList<>();
    public final ProjectAgentRunExecutor executor;
    public final ProjectAgentRunService service;

    /**
     * @param enabled 开关
     * @param deferStart true 时启动任务暂存于 {@link #deferred}（模拟调度尚未执行，运行保持 PENDING）
     * @param maxConcurrent 并发上限
     */
    public RunServiceHarness(boolean enabled, boolean deferStart, int maxConcurrent) {
        when(access.requireVisible(any(IpdActor.class), eq(AgentTestFixtures.PROJECT_ID)))
            .thenReturn(AgentTestFixtures.TENANT);
        when(access.requireVisible(any(IpdActor.class), eq(AgentTestFixtures.OTHER_PROJECT_ID)))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"));
        when(access.requireVisible(eq(FROZEN), any()))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN));
        Scheduler scheduler = deferStart ? Schedulers.fromExecutor(deferred::add) : Schedulers.immediate();
        executor = new ProjectAgentRunExecutor(store, kernel, AgentTestFixtures.MAPPER, scheduler, clock::get,
            maxConcurrent);
        service = new ProjectAgentRunService(enabled, access, AgentTestFixtures.planner(), store, executor,
            AgentTestFixtures.MAPPER, clock::get, Duration.ofSeconds(60));
    }

    /** 执行暂存的启动任务。 */
    public void runDeferred() {
        List<Runnable> tasks = new ArrayList<>(deferred);
        deferred.clear();
        tasks.forEach(Runnable::run);
    }
}
