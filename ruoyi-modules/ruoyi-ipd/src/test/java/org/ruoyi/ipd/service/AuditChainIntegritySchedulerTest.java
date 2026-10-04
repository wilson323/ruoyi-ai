package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.dto.AuditChainVerifyResult;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.service.AuditLogServiceImpl.AnchorVerdict;
import org.ruoyi.ipd.service.AuditLogServiceImpl.AnchoredChainVerifyResult;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审计链完整性定时自检（{@link AuditChainIntegrityScheduler}）单测。
 *
 * <p>纯 Mock：判定逻辑是「验链结果上的纯函数」，mapper 只加 @Select/@Update 形状由接口锁定，
 * 本类不触碰 audit_logs 写路径。双向断言——正常态必不报警、注入断裂必报警、开关关闭必不执行
 * （防单绿假绿：只测「会报」不测「不会滥报」，等于没测噪音治理）。
 *
 * <p>关键用例 ④/④b/④c：<b>行级判据全绿、只有锚表判据报篡改</b>——链尾被删（{@code ANCHOR_TRUNCATED}）、
 * 链尾被改写（{@code ANCHOR_TAIL_REWRITTEN}）、整表被清空（{@code ANCHOR_CLEARED}）。这三种形态
 * 恰恰不触碰行级判据（{@code hashBroken}/{@code gaps} 全绿），正是锚表判据存在的理由，
 * 只看行级会三种一起静默放过。三条同时是 {@code Finding.alarm()} 的变异检查点：
 * 删掉其中的 {@code anchor.tampered()} 后，它们必须立刻转红。
 */
@Tag("dev")
@DisplayName("审计链完整性定时自检：基线内静默 / 增量与篡改告警 / 开关关闭不执行")
class AuditChainIntegritySchedulerTest {

    private static final Date NOW = Date.from(Instant.parse("2026-10-03T19:30:00Z"));
    private static final String ACTION_URL = "/ipd/audit-logs";

    private final IAuditLogService auditLogService = mock(IAuditLogService.class);
    private final PersonMapper personMapper = mock(PersonMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    private AuditChainIntegrityScheduler scheduler(boolean enabled, String baselineGaps) {
        return new AuditChainIntegrityScheduler(auditLogService, personMapper, notificationService,
            enabled, baselineGaps);
    }

    // ===== helpers =====

    private static AnchoredChainVerifyResult anchorOk(List<Long> hashBroken, List<Long> gaps, int total) {
        return new AnchoredChainVerifyResult(
            new AuditChainVerifyResult(hashBroken, gaps, total),
            new AnchorVerdict(AuditLogServiceImpl.ANCHOR_OK, 11244L, "aaaa", 11244L, "aaaa"));
    }

    private static AnchoredChainVerifyResult anchored(List<Long> hashBroken, List<Long> gaps, int total,
                                                      String anchorCode, Long anchorLastSeq, String anchorLastHash,
                                                      Long tableLastSeq, String tableLastHash) {
        return new AnchoredChainVerifyResult(
            new AuditChainVerifyResult(hashBroken, gaps, total),
            new AnchorVerdict(anchorCode, anchorLastSeq, anchorLastHash, tableLastSeq, tableLastHash));
    }

    private void givenAdmins(Person... admins) {
        when(personMapper.selectList(any())).thenReturn(List.of(admins));
    }

    /** ADR-0076 §1 登记的 17 个基线 seq（从生产常量解析，保证测的就是默认配置本身）。 */
    private static List<Long> defaultBaselineGaps() {
        return Arrays.stream(AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS.split(","))
            .map(String::trim).map(Long::parseLong).toList();
    }

    // ===== ① 正常态不报警 =====

    @Test
    @DisplayName("① 正常态：GAP 全在 ADR-0076 基线内 + 哈希零断裂 + 锚一致 → 不告警、不查超管、不发通知")
    void healthyChainWithOnlyBaselineGapsStaysSilent() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchorOk(List.of(), List.of(1889L, 1892L), 9805));

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isFalse();
        assertThat(finding.newGaps()).isEmpty();
        assertThat(finding.baselineGapCount()).isEqualTo(2);   // 基线内不干净的事实仍可见，只是不告警
        assertThat(finding.total()).isEqualTo(9805);
        verifyNoInteractions(notificationService);
        verifyNoInteractions(personMapper);                    // 干净路径连超管名单都不查
    }

    @Test
    @DisplayName("①b 全部 17 个默认基线 GAP 同时出现 → 仍静默（默认基线串解析逐值生效）")
    void allSeventeenRegisteredBaselineGapsStaySilent() {
        List<Long> baseline = defaultBaselineGaps();
        assertThat(baseline).hasSize(17);                      // 常量自身形状先锁死
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchorOk(List.of(), baseline, 9805));

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isFalse();
        assertThat(finding.baselineGapCount()).isEqualTo(17);
        verifyNoInteractions(notificationService);
    }

    // ===== ② 注入断裂后报警 =====

    @Test
    @DisplayName("② 基线外新增一处 GAP（注入 9999）→ 告警并通知每名在任超管（FYI / AUDIT_CHAIN_BROKEN）")
    void newGapBeyondBaselineAlarmsAndNotifies() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchorOk(List.of(), List.of(1889L, 9999L), 9806));
        givenAdmins(Person.builder().id(1L).build(), Person.builder().id(2L).build());

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isTrue();
        assertThat(finding.newGaps()).containsExactly(9999L);
        assertThat(finding.summary()).contains("基线外新增 seq 空洞 1 处");
        verify(notificationService).publishDaily(eq(1L), eq("AUDIT_CHAIN_BROKEN"),
            eq(NotificationService.KIND_FYI), eq("audit_chain_verify"), eq(0L),
            anyString(), anyString(), eq(ACTION_URL), eq(NOW));
        verify(notificationService).publishDaily(eq(2L), eq("AUDIT_CHAIN_BROKEN"),
            eq(NotificationService.KIND_FYI), eq("audit_chain_verify"), eq(0L),
            anyString(), anyString(), eq(ACTION_URL), eq(NOW));

        // 通知正文必须带足以定位的字段（seq），否则收件人无从复核
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(2)).publishDaily(anyLong(), anyString(), anyString(),
            anyString(), anyLong(), anyString(), content.capture(), anyString(), any());
        assertThat(content.getAllValues()).allSatisfy(c -> assertThat(c).contains("9999"));
    }

    @Test
    @DisplayName("③ 行级哈希断裂（零基线）→ 告警（即使 seq 无空洞）")
    void hashBreakageAlarmsEvenWithoutGaps() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchored(List.of(42L), List.of(), 9805,
                AuditLogServiceImpl.ANCHOR_OK, 11244L, "aaaa", 11244L, "aaaa"));
        givenAdmins(Person.builder().id(1L).build());

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isTrue();
        assertThat(finding.hashBroken()).containsExactly(42L);
        verify(notificationService).publishDaily(eq(1L), eq("AUDIT_CHAIN_BROKEN"),
            eq(NotificationService.KIND_FYI), eq("audit_chain_verify"), eq(0L),
            anyString(), anyString(), eq(ACTION_URL), eq(NOW));
    }

    @Test
    @DisplayName("④ 删链尾：行级判据全绿（hashBroken/gaps 皆空）但锚表报 ANCHOR_TRUNCATED → 必须告警")
    void deletedTailAlarmsEvenWhenRowLevelVerdictIsClean() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchored(List.of(), List.of(), 9803,
                AuditLogServiceImpl.ANCHOR_TRUNCATED, 11244L, "expected-hash", 11241L, "actual-hash"));
        givenAdmins(Person.builder().id(7L).build());

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isTrue();
        assertThat(finding.hashBroken()).isEmpty();            // 行级判据看不见——锚表判据的存在理由
        assertThat(finding.summary()).contains("ANCHOR_TRUNCATED");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(notificationService).publishDaily(eq(7L), eq("AUDIT_CHAIN_BROKEN"),
            eq(NotificationService.KIND_FYI), eq("audit_chain_verify"), eq(0L),
            anyString(), content.capture(), eq(ACTION_URL), eq(NOW));
        // 锚表判据的「期望 / 实际」链尾哈希必须落进正文（这是本形态唯一的定位线索）
        assertThat(content.getValue()).contains("expected-hash").contains("actual-hash")
            .contains("11244").contains("11241");
    }

    @Test
    @DisplayName("④b 链尾被改写：行级判据全绿但锚表报 ANCHOR_TAIL_REWRITTEN → 必须告警（篡改类，与④同一判据）")
    void rewrittenTailAlarmsEvenWhenRowLevelVerdictIsClean() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchored(List.of(), List.of(), 9805,
                AuditLogServiceImpl.ANCHOR_TAIL_REWRITTEN, 11244L, "expected-hash", 11244L, "actual-hash"));
        givenAdmins(Person.builder().id(7L).build());

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isTrue();
        assertThat(finding.hashBroken()).isEmpty();            // 行级判据看不见——锚表判据的存在理由
        assertThat(finding.newGaps()).isEmpty();
        assertThat(finding.summary()).contains("ANCHOR_TAIL_REWRITTEN");
        verify(notificationService).publishDaily(eq(7L), eq("AUDIT_CHAIN_BROKEN"),
            eq(NotificationService.KIND_FYI), eq("audit_chain_verify"), eq(0L),
            anyString(), anyString(), eq(ACTION_URL), eq(NOW));
    }

    @Test
    @DisplayName("④c 整表被清空：行级判据无行可断（total=0）但锚表报 ANCHOR_CLEARED → 必须告警")
    void clearedTableAlarmsEvenWhenRowLevelVerdictIsClean() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchored(List.of(), List.of(), 0,
                AuditLogServiceImpl.ANCHOR_CLEARED, 11244L, "expected-hash", null, null));
        givenAdmins(Person.builder().id(7L).build());

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isTrue();
        assertThat(finding.total()).isZero();                  // 表被清空后行级判据全绿，只有锚表看得见
        assertThat(finding.hashBroken()).isEmpty();
        assertThat(finding.newGaps()).isEmpty();
        assertThat(finding.summary()).contains("ANCHOR_CLEARED");
        verify(notificationService).publishDaily(eq(7L), eq("AUDIT_CHAIN_BROKEN"),
            eq(NotificationService.KIND_FYI), eq("audit_chain_verify"), eq(0L),
            anyString(), anyString(), eq(ACTION_URL), eq(NOW));
    }

    @Test
    @DisplayName("⑤ 锚行缺失：链尾证据不可用（降级态）→ 告警")
    void missingAnchorAlarms() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchored(List.of(), List.of(), 9805,
                AuditLogServiceImpl.ANCHOR_MISSING, null, null, 11244L, "actual-hash"));
        givenAdmins(Person.builder().id(1L).build());

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isTrue();
        assertThat(finding.summary()).contains("锚行缺失");
        verify(notificationService).publishDaily(eq(1L), eq("AUDIT_CHAIN_BROKEN"),
            eq(NotificationService.KIND_FYI), eq("audit_chain_verify"), eq(0L),
            anyString(), anyString(), eq(ACTION_URL), eq(NOW));
    }

    @Test
    @DisplayName("通知一个收件人失败仍尝试其余收件人，并报告部分失败")
    void failedRecipientDoesNotPreventRemainingNotifications() {
        when(auditLogService.verifyChainAnchored()).thenReturn(anchorOk(List.of(9000L), List.of(), 10));
        givenAdmins(Person.builder().id(1L).build(), Person.builder().id(2L).build());
        org.mockito.Mockito.doThrow(new IllegalStateException("notification unavailable"))
            .when(notificationService).publishDaily(eq(1L), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), any(Date.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            scheduler(true, "").scanAndNotify(NOW))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("成功 1 人");
        verify(notificationService).publishDaily(eq(2L), eq("AUDIT_CHAIN_BROKEN"),
            eq(NotificationService.KIND_FYI), eq("audit_chain_verify"), eq(0L),
            anyString(), anyString(), eq(ACTION_URL), eq(NOW));
    }

    // ===== ⑥ 开关关闭 =====

    @Test
    @DisplayName("⑥ 开关关闭：连验链都不调（不是「调了但没报」），零交互")
    void disabledSwitchSkipsEverything() {
        scheduler(false, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).dailyChainIntegrityScan();

        verifyNoInteractions(auditLogService);
        verifyNoInteractions(personMapper);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("⑥b 开关打开：日任务确实走完一轮验链（防「开关打开也不跑」的静默空转）")
    void enabledSwitchActuallyRunsScan() {
        when(auditLogService.verifyChainAnchored()).thenReturn(anchorOk(List.of(), List.of(), 10));
        givenAdmins(Person.builder().id(1L).build());

        scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).dailyChainIntegrityScan();

        verify(auditLogService).verifyChainAnchored();
        verify(notificationService, never()).publishDaily(anyLong(), anyString(), anyString(),
            anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("⑦ 验链抛异常不得打死日任务，也不得被当成「链完好」静默（异常向上抛出前已落 error）")
    void verifyFailureDoesNotKillTheJob() {
        when(auditLogService.verifyChainAnchored()).thenThrow(new IllegalStateException("db down"));

        scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).dailyChainIntegrityScan();

        verifyNoInteractions(notificationService); // 不误报；error 日志由 catch 分支负责
    }

    // ===== ⑧ 边界 =====

    @Test
    @DisplayName("⑧ 无在任超管：告警结论照常返回（日志可观察），不抛异常不通知")
    void noActiveSuperAdminStillReportsAlarm() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchorOk(List.of(), List.of(9999L), 9806));
        givenAdmins(); // 空名单

        AuditChainIntegrityScheduler.Finding finding =
            scheduler(true, AuditChainIntegrityScheduler.DEFAULT_BASELINE_GAPS).scanAndNotify(NOW);

        assertThat(finding.alarm()).isTrue();
        verify(notificationService, never()).publishDaily(anyLong(), anyString(), anyString(),
            anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("⑨ 基线配空 = 不豁免任何 GAP：ADR 那 17 处也报（换库/清库后的严格档，配置即可切换）")
    void emptyBaselineAlertsOnEveryGap() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchorOk(List.of(), List.of(1889L), 9805));
        givenAdmins(Person.builder().id(1L).build());

        AuditChainIntegrityScheduler.Finding finding = scheduler(true, "").scanAndNotify(NOW);

        assertThat(finding.alarm()).isTrue();
        assertThat(finding.newGaps()).containsExactly(1889L);
        assertThat(finding.baselineGapCount()).isZero();
    }

    @Test
    @DisplayName("⑩ 基线配置含脏项（空串/非数字）不炸启动，合法项照常生效")
    void malformedBaselineTokensAreSkipped() {
        when(auditLogService.verifyChainAnchored())
            .thenReturn(anchorOk(List.of(), List.of(1889L, 1892L), 9805));

        AuditChainIntegrityScheduler.Finding finding = scheduler(true, " 1889 , ,abc,1892 ,").scanAndNotify(NOW);

        assertThat(finding.alarm()).isFalse();
        assertThat(finding.baselineGapCount()).isEqualTo(2);
    }

    // ===== ⑪ 调度接线 =====

    @Test
    @DisplayName("⑪ 调度接线：唯一 @Scheduled 方法 cron 锁定 0 30 3 * * ?（每日 03:30，错峰表已登记）")
    void scheduledCronIsLocked() throws Exception {
        Method scheduled = null;
        for (Method m : AuditChainIntegrityScheduler.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Scheduled.class)) {
                assertThat(scheduled).as("只允许一个 @Scheduled 方法（多则错峰表口径失效）").isNull();
                scheduled = m;
            }
        }
        assertThat(scheduled).as("缺少 @Scheduled 方法 = 自检永不运行").isNotNull();
        assertThat(scheduled.getName()).isEqualTo("dailyChainIntegrityScan");
        assertThat(scheduled.getAnnotation(Scheduled.class).cron()).isEqualTo("0 30 3 * * ?");
    }
}
