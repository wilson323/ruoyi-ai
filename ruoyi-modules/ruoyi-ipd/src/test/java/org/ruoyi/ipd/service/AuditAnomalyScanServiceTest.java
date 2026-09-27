package org.ruoyi.ipd.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.vo.AuditOperatorWindowStats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * AI-P3 #7 审计异常检测单测（纯 Mock：检测逻辑是「只读 GROUP BY 聚合行」上的纯函数判定，
 * SQL 本身零写路径由接口形状锁定——mapper 只加 @Select，无 UPDATE/DELETE 可调）。
 *
 * <p>正/负向双向断言：异常样本必发通知、正常样本必不发（防空转假绿）；
 * 另锁窗口数学（24h）、阈值边界（49/50）、双超管 fan-out、只读交互面。
 */
@Tag("dev")
@DisplayName("AI-P3#7 审计异常检测：启发式判定 + 超管通知（只报不拦）")
class AuditAnomalyScanServiceTest {

    private static final Date NOW = Date.from(Instant.parse("2026-09-27T02:05:00Z"));

    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final PersonMapper personMapper = mock(PersonMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final AuditAnomalyScanService service =
        new AuditAnomalyScanService(auditLogMapper, personMapper, notificationService);

    private static AuditOperatorWindowStats stats(Long id, String name, String role, long writes, long sensitive) {
        AuditOperatorWindowStats s = new AuditOperatorWindowStats();
        s.setOperatorId(id);
        s.setOperatorName(name);
        s.setOperatorRole(role);
        s.setWriteCount(writes);
        s.setSensitiveOffRoleCount(sensitive);
        return s;
    }

    private void givenAdmins(Person... admins) {
        when(personMapper.selectList(any())).thenReturn(List.of(admins));
    }

    @Test
    @DisplayName("规则 A 命中：窗口内写型动作 ≥50 → 每名在任超管收到 1 条 FYI（AUDIT_ANOMALY_BULK_WRITE）")
    void bulkWriteAnomalyNotifiesAllActiveSuperAdmins() {
        when(auditLogMapper.selectOperatorWindowStats(any(), any()))
            .thenReturn(List.of(stats(101L, "冒名批量侠", "RD_PM", 60L, 0L)));
        givenAdmins(Person.builder().id(1L).build(), Person.builder().id(2L).build());

        int hits = service.scanAndNotify(NOW);

        assertThat(hits).isEqualTo(1);
        verify(notificationService).publishDaily(eq(1L), eq("AUDIT_ANOMALY_BULK_WRITE"),
            eq(NotificationService.KIND_FYI), eq("audit_anomaly"), eq(101L),
            anyString(), anyString(), anyString(), eq(NOW));
        verify(notificationService).publishDaily(eq(2L), eq("AUDIT_ANOMALY_BULK_WRITE"),
            eq(NotificationService.KIND_FYI), eq("audit_anomaly"), eq(101L),
            anyString(), anyString(), anyString(), eq(NOW));
    }

    @Test
    @DisplayName("规则 B 命中：非超管敏感动作 ≥1 → AUDIT_ANOMALY_SENSITIVE_OFFROLE；角色缺失也算非常规")
    void sensitiveOffRoleAnomalyNotifies() {
        when(auditLogMapper.selectOperatorWindowStats(any(), any()))
            .thenReturn(List.of(stats(202L, "越权者", "MARKET_PM", 0L, 1L),
                stats(null, null, null, 0L, 3L))); // operatorId/role 缺失的匿名聚合组
        givenAdmins(Person.builder().id(1L).build());

        assertThat(service.scanAndNotify(NOW)).isEqualTo(2);

        verify(notificationService).publishDaily(eq(1L), eq("AUDIT_ANOMALY_SENSITIVE_OFFROLE"),
            eq(NotificationService.KIND_FYI), eq("audit_anomaly"), eq(202L),
            anyString(), anyString(), anyString(), eq(NOW));
        // operatorId=null 匿名组以 0 作 sourceId（publishDaily 的 sourceId 必填护栏不炸）
        verify(notificationService).publishDaily(eq(1L), eq("AUDIT_ANOMALY_SENSITIVE_OFFROLE"),
            eq(NotificationService.KIND_FYI), eq("audit_anomaly"), eq(0L),
            anyString(), anyString(), anyString(), eq(NOW));
    }

    @Test
    @DisplayName("负向：正常流量（写 49 条 <阈值、无敏感动作）→ 零通知零命中（防空转假绿）")
    void normalTrafficStaysSilent() {
        when(auditLogMapper.selectOperatorWindowStats(any(), any()))
            .thenReturn(List.of(stats(303L, "打工人", "RD_PM", 49L, 0L)));
        givenAdmins(Person.builder().id(1L).build());

        assertThat(service.scanAndNotify(NOW)).isZero();

        verify(notificationService, never()).publishDaily(any(), anyString(), anyString(),
            anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("阈值边界：50 恰好命中 / 超管本人敏感动作已由 SQL 排除（聚合侧口径）")
    void thresholdBoundaryIsInclusive() {
        givenAdmins(Person.builder().id(1L).build());
        when(auditLogMapper.selectOperatorWindowStats(any(), any()))
            .thenReturn(List.of(stats(404L, "边界人", "RD_PM", AuditAnomalyScanService.BULK_WRITE_THRESHOLD, 0L)));

        assertThat(service.scanAndNotify(NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("同人双规则双报：bulk + offrole 各自成一条通知（eventType 维度独立去重）")
    void sameOperatorBothRulesFireTwice() {
        when(auditLogMapper.selectOperatorWindowStats(any(), any()))
            .thenReturn(List.of(stats(505L, "双料", "GROUP_LEADER", 80L, 2L)));
        givenAdmins(Person.builder().id(1L).build());

        assertThat(service.scanAndNotify(NOW)).isEqualTo(2);
        verify(notificationService, times(2)).publishDaily(eq(1L), anyString(),
            eq(NotificationService.KIND_FYI), eq("audit_anomaly"), eq(505L),
            anyString(), anyString(), anyString(), eq(NOW));
    }

    @Test
    @DisplayName("窗口数学：每日跑一次回看 24h，[now-24h, now) 左闭右开无缝衔接")
    void windowIs24HoursEndingAtNow() {
        when(auditLogMapper.selectOperatorWindowStats(any(), any())).thenReturn(List.of());

        service.scanAndNotify(NOW);

        ArgumentCaptor<Date> start = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<Date> end = ArgumentCaptor.forClass(Date.class);
        verify(auditLogMapper).selectOperatorWindowStats(start.capture(), end.capture());
        assertThat(end.getValue()).isEqualTo(NOW);
        assertThat(start.getValue().toInstant()).isEqualTo(NOW.toInstant().minus(24, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("只报不拦·只读证明：audit_logs 仅一次 selectOperatorWindowStats 交互，零写调用")
    void scanIsReadOnlyAgainstAuditLogs() {
        when(auditLogMapper.selectOperatorWindowStats(any(), any()))
            .thenReturn(List.of(stats(606L, "批量", "RD_PM", 999L, 0L)));
        givenAdmins(Person.builder().id(1L).build());

        service.scanAndNotify(NOW);

        verify(auditLogMapper).selectOperatorWindowStats(any(), any());
        verifyNoMoreInteractions(auditLogMapper); // insert/update/delete/updateChainHash 均未被触碰
    }

    @Test
    @DisplayName("无在任超管：命中仍计数返回（日志可观察），不抛异常不通知")
    void noAdminMeansNoNotifyButStillReported() {
        when(auditLogMapper.selectOperatorWindowStats(any(), any()))
            .thenReturn(List.of(stats(707L, "孤狼", "RD_PM", 60L, 0L)));
        givenAdmins(); // 空名单

        assertThat(service.scanAndNotify(NOW)).isEqualTo(1);
        verify(notificationService, never()).publishDaily(any(), anyString(), anyString(),
            anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }
}
