package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.KpiSharedReconcileService;
import org.ruoyi.ipd.service.KpiSharedReconcileService.ConfirmRow;
import org.ruoyi.ipd.service.KpiSharedReconcileService.Item;
import org.ruoyi.ipd.service.KpiSharedReconcileService.ReconcileReport;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R232-W14 对账执行器单测：路由声明 / 台账产出 / 交付物留痕不 transit / 组长通知 / OSS 失败交引擎退避。
 * <p>报告构造全部走公开 canonical 构造，字段值与 KpiSharedReconcileServiceTest 真公式产出对齐
 * （96.25/100.00 等均为复算可得值，不造真库不可能出现的组合）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KpiSharedReconcileExecutorTest {

    private static final AiExecContext CTX = new AiExecContext(
        new org.ruoyi.ipd.security.IpdActor(0L, "system", "SYSTEM", null),
        Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("Asia/Shanghai")));

    @Mock private KpiSharedReconcileService reconcileService;
    @Mock private ISysOssService ossService;
    @Mock private StageActionService stageActionService;
    @Mock private NotificationService notificationService;
    @InjectMocks private KpiSharedReconcileExecutor executor;

    private static AiAgentTask task() {
        return AiAgentTask.builder().id(77L).projectId(100L).actionCode("K01")
            .stageActionId(9001L).triggerType(AiAgentTask.TRIGGER_PASSIVE)
            .triggeredBy(9L).execMode("AI_DIRECT").build();
    }

    private static Item item(String code, String stored, String recomputed, String verdict) {
        return new Item(code, "FINANCE_OR_ERP", "100", "100", "100", stored, recomputed, verdict, "");
    }

    private static ReconcileReport matchReport() {
        return new ReconcileReport(100L, "2026-08", false,
            List.of(item("K01", "100.00", "100.00", KpiSharedReconcileService.VERDICT_MATCH),
                item("K02", "90.00", "90.00", KpiSharedReconcileService.VERDICT_MATCH),
                item("K03", "100.00", "100.00", KpiSharedReconcileService.VERDICT_MATCH),
                item("K04", "90.00", "90.00", KpiSharedReconcileService.VERDICT_MATCH)),
            "96.25", "96.25", KpiSharedReconcileService.VERDICT_MATCH,
            KpiSharedReconcileService.VERDICT_MATCH, "双 PM 同分（revision=1）",
            List.of(new ConfirmRow("K01", "PENDING", "9", null)), 1);
    }

    private static SysOssVo uploaded(long ossId) {
        SysOssVo vo = new SysOssVo();
        vo.setOssId(ossId);
        return vo;
    }

    @Test
    void declaresKCodesAndRejectsScheduleAutoDispatch() {
        assertThat(executor.supportedActionCodes())
            .containsExactlyInAnyOrder("K01", "K02", "K03", "K04");
        // 防调度器每日自动建台账（同 C08 填表族排除先例）——只走组长 PASSIVE
        assertThat(executor.supportsSchedule()).isFalse();
    }

    @Test
    void producesLedgerAttachesDeliverableWithoutTransitAndReturnsDocId() {
        when(reconcileService.reconcile(eq(100L), any())).thenReturn(matchReport());
        when(ossService.upload(any(MultipartFile.class))).thenReturn(uploaded(777L));
        when(reconcileService.leadersOf(100L)).thenReturn(Set.of(9L));

        AiExecResult result = executor.execute(task(), CTX);

        assertThat(result.ok()).isTrue();
        assertThat(result.aiDocId()).isEqualTo(777L);
        assertThat(result.summary()).contains("W14 共担KPI对账 period=2026-08")
            .contains("MATCH 4 项").contains("ossId=777");
        // 台账挂触发动作做证据（actor=0 系统留痕），但绝不 transit——裁决权留真人
        verify(stageActionService).addDeliverable(eq(9001L), anyString(), eq(777L), eq("0"));
        verify(stageActionService, never()).transit(any(), anyString(), anyString(), anyString());
        verify(stageActionService, never()).recordFields(any(), any(), any(), any(), any(), any(), any(), any(), anyString());
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(notificationService).publishDaily(eq(9L),
            eq(NotificationService.Types.KPI_RECONCILE_LEDGER),
            eq(NotificationService.KIND_ACTION), eq("ai_agent_task"), eq(77L),
            title.capture(), anyString(), eq("/projects/100"), any());
        assertThat(title.getValue()).contains("2026-08");
    }

    @Test
    void ledgerRendersPerItemTableWithSourceColumnsAndDisclaimer() {
        String md = executor.buildLedger(task(), matchReport(), CTX);
        assertThat(md).contains("# 共担 KPI 数据对账台账")
            .contains("| K01 | FINANCE_OR_ERP | 100 | 100 | 100 | 100.00 | 100.00 | MATCH |")
            .contains("双 PM 同分（AC-KPI-11）")
            .contains("| K01 | PENDING | 9 | — |") // 确认链二签缺失显示占位
            .contains("免责声明");
    }

    @Test
    void awaitingLedgerIsSuccessOutputNotFailure() {
        // W14-02 语义：无归集记录仍是有效台账（全项待补），执行成功而非失败重试
        ReconcileReport pending = new ReconcileReport(100L, "2026-08", true,
            List.of(item("K01", null, null, KpiSharedReconcileService.VERDICT_PENDING)),
            null, null, KpiSharedReconcileService.VERDICT_PENDING,
            KpiSharedReconcileService.VERDICT_PENDING, "无归集行", List.of(), 0);
        when(reconcileService.reconcile(eq(100L), any())).thenReturn(pending);
        when(ossService.upload(any(MultipartFile.class))).thenReturn(uploaded(778L));
        when(reconcileService.leadersOf(100L)).thenReturn(Set.of(9L));

        AiExecResult result = executor.execute(task(), CTX);
        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("PENDING_DATA 1 项");
    }

    @Test
    void ossUploadWithoutIdFailsLoudForEngineBackoff() {
        // 不自建失败路径：抛异常交引擎统一退避/DEAD（AiExecutionEngine runOne catch）
        when(reconcileService.reconcile(eq(100L), any())).thenReturn(matchReport());
        when(ossService.upload(any(MultipartFile.class))).thenReturn(null);
        assertThatThrownBy(() -> executor.execute(task(), CTX))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS 未返回 ossId");
        verify(stageActionService, never()).addDeliverable(any(), anyString(), any(), anyString());
    }

    @Test
    void missingLeaderSkipsNotificationWithoutFailingLedger() {
        // 与 GatePrep 通知同严：查不到组长仅跳过推送，台账产出本身不受影响
        when(reconcileService.reconcile(eq(100L), any())).thenReturn(matchReport());
        when(ossService.upload(any(MultipartFile.class))).thenReturn(uploaded(779L));
        when(reconcileService.leadersOf(100L)).thenReturn(Set.of());

        AiExecResult result = executor.execute(task(), CTX);
        assertThat(result.ok()).isTrue();
        verify(notificationService, never()).publishDaily(any(), anyString(), anyString(),
            anyString(), any(), anyString(), anyString(), anyString(), any());
    }
}
