package org.ruoyi.ipd.hr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HR 真源定时同步调度器（R149-v1 D3；FA-HR-Sync 每日凌晨 0 点增量调度入口，2026-09-29 xlsx 口径）。
 *
 * <p><b>错峰原则</b>：与既有 09:00（{@code PersonResignEscalator}）/
 * 09:05（{@code HandoverOverdueScanner}）错峰 6h+，避免并发争抢数据库连接池。
 *
 * <p><b>装配闸门</b>：{@code @ConditionalOnExpression} 实现两重开关：
 * <ol>
 *   <li>{@code ipd.hr.enabled=true}（必须有 HR 真凭据才装配）</li>
 *   <li>{@code ipd.hr.sync.enabled=true}（允许独立关闭调度而不关 HTTP 客户端）</li>
 *   <li>{@code ipd.hr.sync.cron != ''}（空串 ⇒ 不装配，防错位空跑）</li>
 * </ol>
 *
 * <p><b>超时</b>：单次同步全链路用 {@link Executors#newSingleThreadExecutor()} 跑 + {@code timeoutMs}
 * 兜底；超时即放弃本次（数据未变更不影响下次）。
 *
 * <p><b>last-run 看板</b>：{@link #lastResult()} 暴露给 controller 看板视图。
 */
@Slf4j
@Component
@ConditionalOnExpression(
    "'${ipd.hr.enabled:false}'=='true' "
    + "and '${ipd.hr.sync.enabled:true}'=='true' "
    + "and '${ipd.hr.sync.cron:0 0 0 * * ?}'!=''")
@RequiredArgsConstructor
public class HrSyncJob {

    private final HrSyncProperties properties;
    private final RealHrSyncAdapter adapter;

    /** 看板 / controller 用：上一次同步结果快照。 */
    private final AtomicReference<LastRun> lastResult = new AtomicReference<>();

    /** 每日凌晨 0 点 cron 同步（xlsx 调度口径；默认增量 NEW，scope=ALL 可切全量；0 点无既有 job 撞点）。 */
    @Scheduled(cron = "${ipd.hr.sync.cron:0 0 0 * * ?}")
    public void dailySyncJob() {
        runOnce("CRON_DAILY",
            "ALL".equalsIgnoreCase(properties.getSync().getScope()) ? "ALL" : "NEW");
    }

    /** 手动触发（来自 controller）。 */
    public LastRun runOnce(String triggerBy, String mode) {
        long start = System.currentTimeMillis();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hr-sync-" + triggerBy);
            t.setDaemon(true);
            return t;
        });
        Future<RealHrSyncAdapter.SyncStats> future = null;
        Throwable failure = null;
        try {
            future = executor.submit(() -> "ALL".equalsIgnoreCase(mode)
                ? adapter.syncAll(triggerBy) : adapter.syncIncremental(triggerBy));
            RealHrSyncAdapter.SyncStats stats = future.get(
                properties.getSync().getTimeoutMs(), TimeUnit.MILLISECONDS);
            LastRun lr = new LastRun(start, System.currentTimeMillis(), triggerBy,
                stats, null);
            lastResult.set(lr);
            log.info("R149-v1 dailySyncJob OK: {}", lr);
            return lr;
        } catch (TimeoutException te) {
            failure = te;
            log.error("R149-v1 dailySyncJob TIMEOUT after {}ms", properties.getSync().getTimeoutMs());
            if (future != null) future.cancel(true);
        } catch (Exception e) {
            failure = e;
            log.error("R149-v1 dailySyncJob FAILED: {}", e.getMessage(), e);
        } finally {
            executor.shutdownNow();
            if (failure != null) {
                LastRun lr = new LastRun(start, System.currentTimeMillis(), triggerBy,
                    null, failure.getClass().getSimpleName() + ":" + failure.getMessage());
                lastResult.set(lr);
            }
        }
        return lastResult.get();
    }

    /** 上次同步结果（controller / 看板读取）。 */
    public LastRun lastResult() {
        return lastResult.get();
    }

    /** 同步结果快照（DTO；不可变）。 */
    public record LastRun(long startedAt, long finishedAt, String triggerBy,
                          RealHrSyncAdapter.SyncStats stats, String errorMessage) {
        public boolean isOk() { return errorMessage == null && stats != null; }
        public long costMs() { return finishedAt - startedAt; }
    }
}
