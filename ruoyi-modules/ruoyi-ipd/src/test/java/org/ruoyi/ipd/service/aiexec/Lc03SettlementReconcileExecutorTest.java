package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.Lc03SettlementReconcileService;
import org.ruoyi.ipd.service.Lc03SettlementReconcileService.Item;
import org.ruoyi.ipd.service.Lc03SettlementReconcileService.ReconcileReport;
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
 * R232-LC03 终算对账执行器单测：路由声明 / 台账产出 / 交付物留痕不 transit 不 recordFields / 组长通知 / OSS 失败交引擎退避。
 * <p>报告构造全部走公开 canonical 构造，字段值与 Lc03SettlementReconcileServiceTest 真公式产出对齐
 * （15/9 等均为复算可得值，不造真库不可能出现的组合）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class Lc03SettlementReconcileExecutorTest {

    private static final AiExecContext CTX = new AiExecContext(
        new org.ruoyi.ipd.security.IpdActor(0L, "system", "SYSTEM", null),
        Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("Asia/Shanghai")));

    @Mock private Lc03SettlementReconcileService reconcileService;
    @Mock private ISysOssService ossService;
    @Mock private StageActionService stageActionService;
    @Mock private NotificationService notificationService;
    @InjectMocks private Lc03SettlementReconcileExecutor executor;

    private static AiAgentTask task() {
        return AiAgentTask.builder().id(77L).projectId(100L).actionCode("LC03")
            .stageActionId(9001L).triggerType(AiAgentTask.TRIGGER_PASSIVE)
            .triggeredBy(9L).execMode("AI_DIRECT").build();
    }

    private static Item item(String code, String stored, String recomputed, String verdict) {
        return new Item(code, "窗口净回款", stored, recomputed, verdict, "");
    }

    /** 与 Lc03SettlementReconcileServiceTest 真公式产出对齐：finalPool 1000×0.05×1.0×0.3=15 全 MATCH。 */
    private static ReconcileReport matchReport() {
        return new ReconcileReport(100L, "2026-09", false,
            List.of(item("RECEIPTS_NET", "1000", "1000", Lc03SettlementReconcileService.VERDICT_MATCH),
                item("POOL_RATE", "0.05", "0.05", Lc03SettlementReconcileService.VERDICT_MATCH),
                item("LEVEL_COEFFICIENT", "1", "1", Lc03SettlementReconcileService.VERDICT_MATCH),
                item("ACHIEVEMENT_RATE", "50.0000", "50", Lc03SettlementReconcileService.VERDICT_MATCH),
                item("TIER_COEFFICIENT", "0.3", "0.3", Lc03SettlementReconcileService.VERDICT_MATCH),
                item("FINAL_POOL", "15", "15", Lc03SettlementReconcileService.VERDICT_MATCH)),
            6, "15", "15", Lc03SettlementReconcileService.VERDICT_MATCH,
            "两路复算同值（个人绩效系数=中性 1.0）");
    }

    private static SysOssVo uploaded(long ossId) {
        SysOssVo vo = new SysOssVo();
        vo.setOssId(ossId);
        return vo;
    }

    @Test
    void declaresLc03AndRejectsScheduleAutoDispatch() {
        // 复用既有动作码（69 数不动）；防调度器每日自动堆台账——只走自然人 PASSIVE
        assertThat(executor.supportedActionCodes()).containsExactlyInAnyOrder("LC03");
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
        assertThat(result.summary()).contains("LC03 上市后6个月终算对账 period=2026-09")
            .contains("MATCH 6 项").contains("ossId=777");
        // 台账挂触发动作做证据（actor=0 系统留痕），但绝不 transit/recordFields——裁决权留真人签署链
        verify(stageActionService).addDeliverable(eq(9001L), anyString(), eq(777L), eq("0"));
        verify(stageActionService, never()).transit(any(), anyString(), anyString(), anyString());
        verify(stageActionService, never()).recordFields(any(), any(), any(), any(), any(), any(), any(), anyString());
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(notificationService).publishDaily(eq(9L),
            eq(NotificationService.Types.SETTLEMENT_RECONCILE_LEDGER),
            eq(NotificationService.KIND_ACTION), eq("ai_agent_task"), eq(77L),
            title.capture(), anyString(), eq("/projects/100"), any());
        assertThat(title.getValue()).contains("2026-09");
    }

    @Test
    void ledgerRendersPerItemTableWithFormulaAndDisclaimer() {
        String md = executor.buildLedger(task(), matchReport(), CTX);
        assertThat(md).contains("# 上市后 6 个月终算对账台账")
            .contains("| RECEIPTS_NET | 窗口净回款 | 1000 | 1000 | MATCH |")
            .contains("finalPool = 回款基数 × poolRate × 项目系数 × 阶梯系数 × 个人绩效系数")
            .contains("个人绩效系数说明: 两路复算同值（个人绩效系数=中性 1.0）")
            .contains("奖金池最终金额与分配由人和制度流程决定")
            .contains("免责声明");
    }

    @Test
    void awaitingLedgerIsSuccessOutputNotFailure() {
        // W14-02 语义：无奖金池记录仍是有效台账（全项待补），执行成功而非失败重试
        ReconcileReport pending = new ReconcileReport(100L, "2026-09", true,
            List.of(item("FINAL_POOL", null, null, Lc03SettlementReconcileService.VERDICT_PENDING)),
            6, null, null, Lc03SettlementReconcileService.VERDICT_PENDING, "无奖金池行");
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
        // 与 W14 台账通知同严：查不到组长仅跳过推送，台账产出本身不受影响
        when(reconcileService.reconcile(eq(100L), any())).thenReturn(matchReport());
        when(ossService.upload(any(MultipartFile.class))).thenReturn(uploaded(779L));
        when(reconcileService.leadersOf(100L)).thenReturn(Set.of());

        AiExecResult result = executor.execute(task(), CTX);
        assertThat(result.ok()).isTrue();
        verify(notificationService, never()).publishDaily(any(), anyString(), anyString(),
            anyString(), any(), anyString(), anyString(), anyString(), any());
    }
}
