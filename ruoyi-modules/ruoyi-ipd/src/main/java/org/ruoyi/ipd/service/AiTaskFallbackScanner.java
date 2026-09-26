package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * R221 兜底扫描器（spec §3.3）：outbox 口径 fixedDelay 30s 触发引擎异步派发，
 * 兜住 afterCommit 派发丢失/JVM 重启遗留——PENDING 滞留与 FAILED 到期重试
 * 都由 {@link AiExecutionEngine#dispatchCycle(int)} 的扫描条件覆盖，本类只做节拍。
 *
 * <p>复审问题3：只调 {@link AiExecutionEngine#dispatchAsync()} 提交到引擎自有单线程池，
 * 不得在调度线程上 inline 跑 dispatchCycle——Spring 默认单线程调度器会被 AI 生成耗时
 * 占住饿死 cron 家族，且 inline 派发破坏引擎串行不变量。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：常驻间隔任务（非整点），与 09:00-10:00
 * 整点 cron 家族无时刻冲突；间隔可配 {@code ipd.aiexec.fallback.interval-ms}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTaskFallbackScanner {

    private final AiExecutionEngine engine;

    @Scheduled(fixedDelayString = "${ipd.aiexec.fallback.interval-ms:30000}")
    public void scan() {
        try {
            engine.dispatchAsync();
        } catch (Exception e) {
            // 单轮异常不吞调度线程：下轮继续（NotificationOutboxScanner 同款语义）
            log.warn("[R221] AiTaskFallbackScanner 本轮异常（不阻断下轮）", e);
        }
    }
}
