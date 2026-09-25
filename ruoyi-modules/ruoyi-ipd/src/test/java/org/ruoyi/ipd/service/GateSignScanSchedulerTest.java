package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.security.IpdActor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate 签署提醒/超时弃权调度器单测（R215-GAP-B4）。
 *
 * <p>纯 mock 编排验证：调度方法以系统身份（id=0/system/SYSTEM）委托服务层既有扫描入口，
 * 与真库手动端点（POST /gates/sign/scan-remind、/scan-timeout）共用同一 service 方法。
 * 幂等性由服务层取证保障（publishDaily 自然日去重 / 状态机落终态），不在本测范围。
 *
 * <p>@Tag("dev") 必须：Surefire groups=${profiles.active} 过滤，缺 tag 会被静默跳过（假绿陷阱）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateSignScanSchedulerTest {

    @Mock
    private GateReviewService gateReviewService;

    private GateSignScanScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GateSignScanScheduler(gateReviewService);
    }

    private void fixClock(String date) {
        LocalDate fixed = LocalDate.parse(date);
        scheduler.setClock(Clock.fixed(
            fixed.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("09:20 提醒 job：以系统身份委托 scanRemind，返回计数来自服务层")
    void remindScan_delegatesWithSystemActor() {
        fixClock("2026-09-25");
        when(gateReviewService.scanRemind(any(IpdActor.class))).thenReturn(4);

        scheduler.dailySignRemindScan();

        verify(gateReviewService, times(1))
            .scanRemind(eq(new IpdActor(0L, "system", "SYSTEM", null)));
    }

    @Test
    @DisplayName("09:25 超时 job：以系统身份委托 scanTimeout（重跑幂等靠状态机，服务层取证）")
    void timeoutScan_delegatesWithSystemActor() {
        fixClock("2026-09-25");
        when(gateReviewService.scanTimeout(any(IpdActor.class))).thenReturn(0);

        scheduler.dailySignTimeoutScan();

        verify(gateReviewService, times(1))
            .scanTimeout(eq(new IpdActor(0L, "system", "SYSTEM", null)));
    }

    @Test
    @DisplayName("同日双触发（cron + 手动重跑）各自独立委托服务层，去重责任在服务层")
    void repeatedTriggers_delegateEachTime_serviceLayerDedups() {
        when(gateReviewService.scanRemind(any(IpdActor.class))).thenReturn(1);

        scheduler.dailySignRemindScan();
        scheduler.dailySignRemindScan();

        verify(gateReviewService, times(2)).scanRemind(any(IpdActor.class));
    }

    @Test
    @DisplayName("setClock(null) 回退系统时钟，不影响扫描委托")
    void setClock_nullFallsBackToSystemClock() {
        scheduler.setClock(null);
        when(gateReviewService.scanTimeout(any(IpdActor.class))).thenReturn(0);

        scheduler.dailySignTimeoutScan();

        verify(gateReviewService).scanTimeout(any(IpdActor.class));
    }
}
