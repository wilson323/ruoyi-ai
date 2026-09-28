package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.Lc04ContributionReconcileService;
import org.ruoyi.ipd.service.Lc04ContributionReconcileService.Item;
import org.ruoyi.ipd.service.Lc04ContributionReconcileService.ReconcileReport;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R232-LC04 贡献度对账执行器单测：路由声明 / 台账产出 / 交付物留痕不 transit 不 recordFields / 组长通知 / OSS 失败交引擎退避。
 * <p>报告构造全部走公开 canonical 构造，字段值与 Lc04ContributionReconcileServiceTest 真公式产出对齐
 * （0.8/0.44/8.25 等均为复算可得值，不造真库不可能出现的组合）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class Lc04ContributionReconcileExecutorTest {

    private static final AiExecContext CTX = new AiExecContext(
        new org.ruoyi.ipd.security.IpdActor(0L, "system", "SYSTEM", null),
        Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("Asia/Shanghai")));

    @Mock private Lc04ContributionReconcileService reconcileService;
    @Mock private ISysOssService ossService;
    @Mock private StageActionService stageActionService;
    @Mock private NotificationService notificationService;
    @InjectMocks private Lc04ContributionReconcileExecutor executor;

    private static AiAgentTask task() {
        return AiAgentTask.builder().id(77L).projectId(100L).actionCode("LC04")
            .stageActionId(9001L).triggerType(AiAgentTask.TRIGGER_PASSIVE)
            .triggeredBy(9L).execMode("AI_DIRECT").build();
    }

    private static Item item(String code, String stored, String recomputed, String verdict) {
        return new Item(code, "五维加权 tier 修正系数", stored, recomputed, verdict, "");
    }

    /** 与 Lc04ContributionReconcileServiceTest 真公式产出对齐：tier 0.8 双路同值、分配行全 MATCH。 */
    private static ReconcileReport matchReport() {
        return new ReconcileReport(100L, "2026-09", false,
            List.of(item("TIER_COEFFICIENT", "0.8", "0.8", Lc04ContributionReconcileService.VERDICT_MATCH),
                item("SHARE_CONSTRAINT", "market=0.55，rd=0.45", "区间合规且合计 1.0", Lc04ContributionReconcileService.VERDICT_MATCH),
                item("ALLOCATED_AMOUNT", "8.25", "8.25", Lc04ContributionReconcileService.VERDICT_MATCH),
                item("CONTRIBUTION_RATE", "0.44", "0.44", Lc04ContributionReconcileService.VERDICT_MATCH),
                item("PERFORMANCE_COEFFICIENT", "1.0", "1", Lc04ContributionReconcileService.VERDICT_MATCH)),
            null);
    }

    private static SysOssVo uploaded(long ossId) {
        SysOssVo vo = new SysOssVo();
        vo.setOssId(ossId);
        return vo;
    }

    @Test
    void supportedActionCodesIsLc04Only() {
        assertThat(executor.supportedActionCodes()).containsExactly("LC04");
    }

    @Test
    void doesNotAcceptScheduleDispatch() {
        // 台账 PASSIVE 触发（契约 §7 B4）：不接受 SCHEDULE 自动派发，防每日重复台账
        assertThat(executor.supportsSchedule()).isFalse();
    }

    @Test
    void executeUploadsLedgerAttachesDeliverableNotifiesLeaders() {
        when(reconcileService.reconcile(eq(100L), any())).thenReturn(matchReport());
        when(reconcileService.leadersOf(100L)).thenReturn(Set.of(9L));
        when(ossService.upload(any(MultipartFile.class))).thenReturn(uploaded(501L));

        AiExecResult result = executor.execute(task(), CTX);

        assertThat(result.ok()).isTrue();
        verify(stageActionService).addDeliverable(eq(9001L), anyString(), eq(501L), eq("0"));
        verify(notificationService).publishDaily(eq(9L), eq(NotificationService.Types.CONTRIBUTION_RECONCILE_LEDGER),
            anyString(), anyString(), eq(77L), anyString(), anyString(), anyString(), any());
        // 只读对账：不 transit 状态机（裁决权留 G5 真人签署链）
        verify(stageActionService, never()).transit(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void buildLedgerDisclosesTierDivergenceAndFormulas() {
        ReconcileReport report = new ReconcileReport(100L, "2026-09", false,
            List.of(item("TIER_COEFFICIENT", "0.8", "0.8", Lc04ContributionReconcileService.VERDICT_MATCH)),
            "分歧披露：双 PM 自评复算不一致（market 路=0.8，rd 路=0.6），待人工裁决");

        String ledger = executor.buildLedger(task(), report, CTX);

        assertThat(ledger)
            .contains("tier 分歧披露")
            .contains("后写覆盖")
            .contains("§三.2.5")
            .contains("§三.2.4")
            .contains("AC-INC-35")
            .contains("免责声明");
    }

    @Test
    void noDataLedgerMarksPendingNotZero() {
        ReconcileReport report = new ReconcileReport(100L, "2026-09", true,
            List.of(item("TIER_COEFFICIENT", null, null, Lc04ContributionReconcileService.VERDICT_PENDING)), null);

        String ledger = executor.buildLedger(task(), report, CTX);

        assertThat(ledger)
            .contains("台账全项标待补")
            .contains("不按 0 伪判");
    }

    @Test
    void ossFailurePropagatesToEngineBackoff() {
        when(reconcileService.reconcile(eq(100L), any())).thenReturn(matchReport());
        when(ossService.upload(any(MultipartFile.class))).thenReturn(null);

        // OSS 无 ossId → 抛 IllegalStateException 交引擎退避（不静默假成功）
        assertThatThrownBy(() -> executor.execute(task(), CTX))
            .isInstanceOf(IllegalStateException.class);
    }
}
