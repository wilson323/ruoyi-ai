package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 离职冻结 15 日倒计时升级调度器（P2-2.2；AC-HAND-01 末段）。
 *
 * <p>每日 09:00（server 时区）扫描 FROZEN_PENDING_HANDOVER 超过 {@link HrSyncService#DEFAULT_HANDOVER_DEADLINE_DAYS}
 * 天的人员，给全部 SUPER_ADMIN 发 ACTION 通知。已发过升级通知的通过 NotificationService 内置
 * dedup_key 幂等去重（同 sourceType:eventType:sourceId:receiverId 不重复发）。
 *
 * <p>调度启用条件：本类被 @Component 注册；启用 @Scheduled 需 application 配置
 * {@code @EnableScheduling}（后续 OPS-04 scheduler 卡合入主树时启用；当前为占位入口，
 * 可由 HrSyncController.escalate-stale-resignations 手动触发）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonResignEscalator {

    private final HrSyncService hrSyncService;

    /**
     * 每日 09:00 扫描并升级。cron 声明已恢复（2026-09-09 治理轮）：
     * 应用未配 @EnableScheduling 时 Spring 忽略 @Scheduled 注解，无副作用；
     * OPS-04 scheduler 卡合入 @EnableScheduling 后本 job 与 HandoverOverdueScanner（09:05）
     * 同日生效。测试覆盖走 HrSyncService.escalateStaleResignations 直接调用。
     *
     * <p>手动触发路径：POST /api/v1/hr-sync/escalate-stale-resignations
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void dailyEscalationJob() {
        IpdActor systemActor = new IpdActor(0L, "SYSTEM", "SUPER_ADMIN", null);
        int escalated = hrSyncService.escalateStaleResignations(
            HrSyncService.DEFAULT_HANDOVER_DEADLINE_DAYS, systemActor);
        log.info("P2-2.2 dailyEscalationJob: escalated={}", escalated);
    }
}
