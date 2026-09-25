package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.service.KpiSharedCollectionService.DeadlineScanResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 共担 KPI 月度截止催办调度器单测（R215-GAP-B1）。
 *
 * <p>纯 mock 编排验证：调度器对 service 双扫描（scanDueSoon + scanMonthlyDeadlines）的
 * 周期推导（固定时钟 → 上一自然月）与调用组合。被测两方法签名与真库手动端点
 * （POST /kpi/shared/deadline-scan → scanMonthlyDeadlines）共用同一 service 入口，
 * mock 返回值 (0,0,0)/(n,m,k) 均为真扫描路径可产生的结果形态。
 *
 * <p>@Tag("dev") 必须：Surefire groups=${profiles.active} 过滤，缺 tag 会被静默跳过（假绿陷阱）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KpiSharedDeadlineSchedulerTest {

    @Mock
    private KpiSharedCollectionService collectionService;

    private KpiSharedDeadlineScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new KpiSharedDeadlineScheduler(collectionService);
    }

    private void fixClock(String date) {
        LocalDate fixed = LocalDate.parse(date);
        scheduler.setClock(Clock.fixed(
            fixed.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("周期推导：2026-09-20 触发 → 双扫描均作用于上一自然月 2026-08")
    void dailyScan_usesPreviousMonthPeriod() {
        fixClock("2026-09-20");
        when(collectionService.scanDueSoon(eq(LocalDate.of(2026, 9, 20)), eq(YearMonth.of(2026, 8))))
            .thenReturn(new DeadlineScanResult(0, 0, 0));
        when(collectionService.scanMonthlyDeadlines(eq(LocalDate.of(2026, 9, 20)), eq(YearMonth.of(2026, 8))))
            .thenReturn(new DeadlineScanResult(2, 1, 3));

        scheduler.dailyDeadlineScan();

        verify(collectionService).scanDueSoon(LocalDate.of(2026, 9, 20), YearMonth.of(2026, 8));
        verify(collectionService).scanMonthlyDeadlines(LocalDate.of(2026, 9, 20), YearMonth.of(2026, 8));
    }

    @Test
    @DisplayName("跨年边界：2026-01-10 触发 → 周期为 2025-12（minusMonths 跨年正确）")
    void dailyScan_crossesYearBoundary() {
        fixClock("2026-01-10");
        when(collectionService.scanDueSoon(eq(LocalDate.of(2026, 1, 10)), eq(YearMonth.of(2025, 12))))
            .thenReturn(new DeadlineScanResult(0, 0, 0));
        when(collectionService.scanMonthlyDeadlines(eq(LocalDate.of(2026, 1, 10)), eq(YearMonth.of(2025, 12))))
            .thenReturn(new DeadlineScanResult(0, 0, 0));

        scheduler.dailyDeadlineScan();

        verify(collectionService).scanMonthlyDeadlines(LocalDate.of(2026, 1, 10), YearMonth.of(2025, 12));
    }

    @Test
    @DisplayName("setClock(null) 回退系统时钟，不影响后续扫描委托")
    void setClock_nullFallsBackToSystemClock() {
        scheduler.setClock(null);
        when(collectionService.scanDueSoon(eq(LocalDate.now()), eq(YearMonth.from(LocalDate.now()).minusMonths(1))))
            .thenReturn(new DeadlineScanResult(0, 0, 0));
        when(collectionService.scanMonthlyDeadlines(eq(LocalDate.now()), eq(YearMonth.from(LocalDate.now()).minusMonths(1))))
            .thenReturn(new DeadlineScanResult(0, 0, 0));

        scheduler.dailyDeadlineScan();

        verify(collectionService).scanMonthlyDeadlines(
            eq(LocalDate.now()), eq(YearMonth.from(LocalDate.now()).minusMonths(1)));
    }
}
