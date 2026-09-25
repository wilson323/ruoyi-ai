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
 * 条件遗留逾期提醒调度器单测（R215-GAP-B4）。
 *
 * <p>纯 mock 编排验证：09:30 job 以系统身份委托 GateElementResultService.scanOverdue，
 * 与真库手动端点（POST /gates/legacy/scan-overdue）共用同一 service 入口。
 * 通知幂等由服务层 publishDaily（dedupKey 含自然日）保障，同日重扫不重发。
 *
 * <p>@Tag("dev") 必须：Surefire groups=${profiles.active} 过滤，缺 tag 会被静默跳过（假绿陷阱）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateLegacyScanSchedulerTest {

    @Mock
    private GateElementResultService gateElementResultService;

    private GateLegacyScanScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GateLegacyScanScheduler(gateElementResultService);
    }

    @Test
    @DisplayName("09:30 逾期 job：以系统身份委托 scanOverdue 一次")
    void overdueScan_delegatesWithSystemActor() {
        LocalDate fixed = LocalDate.parse("2026-09-25");
        scheduler.setClock(Clock.fixed(fixed.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        when(gateElementResultService.scanOverdue(any(IpdActor.class))).thenReturn(3);

        scheduler.dailyLegacyOverdueScan();

        verify(gateElementResultService, times(1))
            .scanOverdue(eq(new IpdActor(0L, "system", "SYSTEM", null)));
    }

    @Test
    @DisplayName("setClock(null) 回退系统时钟，不影响扫描委托")
    void setClock_nullFallsBackToSystemClock() {
        scheduler.setClock(null);
        when(gateElementResultService.scanOverdue(any(IpdActor.class))).thenReturn(0);

        scheduler.dailyLegacyOverdueScan();

        verify(gateElementResultService).scanOverdue(any(IpdActor.class));
    }
}
