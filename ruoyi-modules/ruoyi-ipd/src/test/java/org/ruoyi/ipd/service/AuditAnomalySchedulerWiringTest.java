package org.ruoyi.ipd.service;

import java.lang.reflect.Method;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AI-P3 #7 调度接线锁定（范式照 R219SchedulerWiringTest）：@Scheduled cron 字面量
 * 10:05（错峰表已登记 IpdSchedulingConfig，防撞 09:00-09:55 晨间家族 + 每月 1 日 10:00）
 * + 方法委托 scanAndNotify(now)。
 */
@Tag("dev")
@DisplayName("AI-P3#7 AuditAnomalyScanScheduler 接线：cron 10:05 + 委托 scanAndNotify")
class AuditAnomalySchedulerWiringTest {

    private static Method scheduledMethod() {
        for (Method m : AuditAnomalyScanScheduler.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Scheduled.class)) {
                return m;
            }
        }
        throw new AssertionError("AuditAnomalyScanScheduler 缺少 @Scheduled 方法");
    }

    @Test
    @DisplayName("cron 字面量锁定 0 5 10 * * ?（每日 10:05，SchedulingCronContractSentinelTest 同步校验防撞）")
    void cronIsDailyAt1005() {
        Scheduled s = scheduledMethod().getAnnotation(Scheduled.class);
        assertThat(s.cron()).isEqualTo("0 5 10 * * ?");
    }

    @Test
    @DisplayName("调度方法委托 AuditAnomalyScanService.scanAndNotify 并携带当前时间")
    void delegatesToServiceWithNow() throws Exception {
        AuditAnomalyScanService service = mock(AuditAnomalyScanService.class);
        AuditAnomalyScanScheduler scheduler = new AuditAnomalyScanScheduler(service);

        scheduledMethod().invoke(scheduler);

        ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
        verify(service).scanAndNotify(now.capture());
        assertThat(now.getValue()).isNotNull();
    }
}
