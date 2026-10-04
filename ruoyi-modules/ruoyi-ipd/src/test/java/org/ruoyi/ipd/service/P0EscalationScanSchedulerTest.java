package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 升级链阈值检查调度器单测（R215-GAP-B4）。
 *
 * <p>纯 mock 编排验证：09:35 job 委托 P0EscalationService.checkEscalation，
 * 与真库手动端点（POST /p0/escalation-chain/check）共用同一 service 入口。
 * 升级幂等由服务层双保险保障（PENDING→ESCALATED 状态翻转 + publish 永久 dedupKey）。
 *
 * <p>@Tag("dev") 必须：Surefire groups=${profiles.active} 过滤，缺 tag 会被静默跳过（假绿陷阱）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class P0EscalationScanSchedulerTest {

    @Mock
    private P0EscalationService p0EscalationService;

    private P0EscalationScanScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new P0EscalationScanScheduler(p0EscalationService);
    }

    @Test
    @DisplayName("09:35 检查 job：委托 checkEscalation 一次，返回值来自服务层")
    void escalationCheck_delegatesOnce() {
        LocalDate fixed = LocalDate.parse("2026-09-25");
        scheduler.setClock(Clock.fixed(fixed.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        when(p0EscalationService.checkEscalation()).thenReturn(2);

        scheduler.dailyEscalationCheck();

        verify(p0EscalationService, times(1)).checkEscalation();
    }

    @Test
    @DisplayName("无达阈链行时（返回 0）同样静默委托，无额外副作用路径")
    void escalationCheck_zeroResultStillIdempotent() {
        when(p0EscalationService.checkEscalation()).thenReturn(0);

        scheduler.dailyEscalationCheck();

        verify(p0EscalationService, times(1)).checkEscalation();
    }

    @Test
    @DisplayName("setClock(null) 回退系统时钟，不影响检查委托")
    void setClock_nullFallsBackToSystemClock() {
        scheduler.setClock(null);
        when(p0EscalationService.checkEscalation()).thenReturn(0);

        scheduler.dailyEscalationCheck();

        verify(p0EscalationService).checkEscalation();
    }

    // ===== 2026-10-03 补：把「表空（写入侧未接线）」与「无到期行」分开 =====

    @Test
    @DisplayName("escalated=0 时探测 pendingCount —— 这一探就是区分「表空」与「无到期行」的关键")
    void zeroEscalated_probesPendingCount() {
        when(p0EscalationService.checkEscalation()).thenReturn(0);
        when(p0EscalationService.pendingCount()).thenReturn(0L);

        scheduler.dailyEscalationCheck();

        verify(p0EscalationService, times(1)).pendingCount();
    }

    @Test
    @DisplayName("escalated>0 时短路，不再探 pendingCount（有升级动作即证明写入侧通着）")
    void escalatedNonZero_skipsPendingCountProbe() {
        when(p0EscalationService.checkEscalation()).thenReturn(1);

        scheduler.dailyEscalationCheck();

        verify(p0EscalationService, never()).pendingCount();
    }

    @Test
    @DisplayName("有 PENDING 行但未达阈值：仍探 pendingCount，且不当作「写入侧未接线」")
    void pendingRowsButNoneDue_stillProbes() {
        when(p0EscalationService.checkEscalation()).thenReturn(0);
        when(p0EscalationService.pendingCount()).thenReturn(5L);

        scheduler.dailyEscalationCheck();

        verify(p0EscalationService, times(1)).pendingCount();
    }
}
