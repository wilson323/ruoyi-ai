package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.mapper.NotificationEventMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 通知 outbox 生产链调度扫描器（2026-09-11 修复轮；OPS-05 × ROOT-R2-P0-2 接线）。
 *
 * <p>背景：publish 落库（PENDING）与多通道异步投递（Redisson 队列 → handler）此前
 * 两侧各自完整、中间无生产调用者——任何事件永停 PENDING（存量 65 行从未投递）。
 * 本扫描器补环，每轮：
 * <ol>
 *   <li>扫描到期行（PENDING，或 FAILED 且退避期满）→ {@code dispatchAsync} 入队</li>
 *   <li>{@code consumeOnce} 消费队列 → 按 target_channel 路由 handler → 翻 SENT/FAILED/DEAD</li>
 * </ol>
 *
 * <p>与旧链 {@link NotificationService#dispatchPending}（单通道 MOCK）的关系：本扫描器
 * 只驱动新链（多通道路由 + 重试 + 死信）；旧链保留为运维手动端点
 * （POST /api/v1/notifications/dispatch-pending），不被调度。
 *
 * <p>幂等与并发：重复入队由 consumeOnce 的「前置状态校验 + 条件 UPDATE 乐观守卫」兜底；
 * 多实例部署时同一行可能被双实例入队，但只会成功投递一次。调度启用由
 * {@code IpdSchedulingConfig}（@EnableScheduling）提供。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOutboxScanner {

    /** 单轮扫描（入队）上限 */
    static final int SCAN_LIMIT = 200;
    /** 单轮消费上限 */
    static final int CONSUME_LIMIT = 200;

    private final NotificationEventMapper mapper;
    private final AsyncNotificationDispatcher dispatcher;
    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    /**
     * 每 30s 一轮（间隔可配）：先扫到期行入队，再消费队列投递。
     * 手动验证可直调本方法（或 {@link #enqueueDue()}）。
     */
    @Scheduled(fixedDelayString = "${ipd.notification.dispatch.interval-ms:30000}")
    public void dispatchCycle() {
        int enqueued = enqueueDue();
        Map<String, Integer> consumed = dispatcher.consumeOnce(CONSUME_LIMIT);
        int sent = consumed.getOrDefault("sent", 0);
        int failed = consumed.getOrDefault("failed", 0);
        int dead = consumed.getOrDefault("dead", 0);
        if (enqueued > 0 || sent > 0 || failed > 0 || dead > 0) {
            log.info("[notify-outbox] enqueued={} sent={} failed={} dead={} skipped={}",
                enqueued, sent, failed, dead, consumed.getOrDefault("skipped", 0));
        }
    }

    /** 扫描到期 PENDING/FAILED 行并逐条入队；返回本轮入队成功数（包内可见供测试直调）。 */
    int enqueueDue() {
        Date now = now();
        List<NotificationEvent> due = mapper.selectList(
            new LambdaQueryWrapper<NotificationEvent>()
                .in(NotificationEvent::getDeliveryStatus, "PENDING", "FAILED")
                .and(w -> w.isNull(NotificationEvent::getNextRetryAt)
                    .or().le(NotificationEvent::getNextRetryAt, now))
                .orderByAsc(NotificationEvent::getId)
                .last("limit " + SCAN_LIMIT));
        int enqueued = 0;
        for (NotificationEvent event : due) {
            try {
                if (dispatcher.dispatchAsync(event)) {
                    enqueued++;
                }
            } catch (RuntimeException e) {
                // 单行入队失败不阻断后续行（下一轮重扫兜底）
                log.warn("[notify-outbox] 入队失败 eventId={} err={}", event.getId(), e.getMessage());
            }
        }
        return enqueued;
    }
}
