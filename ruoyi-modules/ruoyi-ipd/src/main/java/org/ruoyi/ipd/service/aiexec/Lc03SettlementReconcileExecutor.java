package org.ruoyi.ipd.service.aiexec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.Lc03SettlementReconcileService;
import org.ruoyi.ipd.service.Lc03SettlementReconcileService.Item;
import org.ruoyi.ipd.service.Lc03SettlementReconcileService.ReconcileReport;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * R232-LC03 上市后 6 个月终算对账执行器（智能体节点执行者样板 #2，镜像 W14 {@link KpiSharedReconcileExecutor}）。
 *
 * <p>接管 LC03「上市后6个月终算(回款达成率+奖金池)」节点的真实产出——只读复算「奖金池 finalPool + 回款达成率」
 * 的 stored vs recomputed → 生成终算对账台账 md → OSS → 挂动作交付物 → 知会产品组长真人复核。
 * 公式同源：{@link org.ruoyi.ipd.service.BonusPoolService} §三.2.5 完整公式 + {@code ReceiptLedgerService}
 * 窗口净额/达成率，不自建第二套口径（GatePrep M2 教训）。
 *
 * <p>边界（四判据之「不改流程天花板」）：只读对账 + 交付物留痕，<b>不 transit、不 recordFields、不改任何
 * 业务表</b>——奖金池最终金额与分配由人和制度流程决定，裁决权留真人签署链（G5 复盘/终算确认仍是真人路径）。
 * {@code supportsSchedule()=false}：台账只由自然人在动作详情页「AI 执行」PASSIVE 触发
 * （LC03 若被调度器每日自动派发会累积重复台账——同 C08 填表族与 K01-K04 的排除先例）。
 *
 * <p>终态守卫不适用：对账是只读复核，LC03 DONE 后再对账仍合法（不复用 terminalNoOp，防误吞复核）。
 * 失败即引擎退避重试、≥3 次 DEAD 转人工（AiExecutionEngine 既有链路，本类不自建失败路径）；
 * 缺值在台账如实标 PENDING_DATA 转待补，不按 0 伪判、不阻断收尾（W14-02 语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Lc03SettlementReconcileExecutor implements AiActionExecutor {

    private final Lc03SettlementReconcileService reconcileService;
    private final ISysOssService ossService;
    private final StageActionService stageActionService;
    private final NotificationService notificationService;

    @Override
    public Set<String> supportedActionCodes() {
        // 复用既有动作码 LC03（已 in ActionCatalog），不新增 ActionDef（69 数不动）
        return Set.of("LC03");
    }

    @Override
    public boolean supportsSchedule() {
        return false;
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        ReconcileReport report = reconcileService.reconcile(task.getProjectId(), ctx.clock());
        String fileName = "LC03-终算对账台账-" + report.period() + "-"
            + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ctx.clock().getZone())
                .format(ctx.clock().instant()) + ".md";
        Long ossId = uploadMd(fileName, buildLedger(task, report, ctx));

        if (task.getStageActionId() != null) {
            // 台账挂触发动作做证据留痕（BR-IPD-03 深管交付物通道）；只挂不改状态、不 transit
            stageActionService.addDeliverable(task.getStageActionId(), fileName, ossId, "0");
        }
        notifyLeaders(task, ctx, report);

        String summary = "LC03 上市后6个月终算对账 period=" + report.period() + "：" + verdictLine(report)
            + "；台账 " + fileName + "（ossId=" + ossId + "），处置待组长复核";
        return AiExecResult.ok(summary, ossId);
    }

    private String verdictLine(ReconcileReport report) {
        Map<String, Long> counts = report.verdictCounts();
        StringBuilder sb = new StringBuilder();
        counts.forEach((verdict, n) -> sb.append(verdict).append(' ').append(n).append(" 项；"));
        sb.append("最终奖金池").append(report.finalPoolVerdict());
        return sb.toString();
    }

    private Long uploadMd(String fileName, String markdown) {
        SysOssVo vo = ossService.upload(new ByteArrayMultipartFile(
            "file", fileName, "text/markdown", markdown.getBytes(StandardCharsets.UTF_8)));
        if (vo == null || vo.getOssId() == null) {
            throw new IllegalStateException("终算对账台账上传失败（OSS 未返回 ossId）: " + fileName);
        }
        return vo.getOssId();
    }

    String buildLedger(AiAgentTask task, ReconcileReport report, AiExecContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 上市后 6 个月终算对账台账（LC03 确定性复算 · AI 节点产出）\n\n");
        sb.append("- 项目 ID: ").append(report.projectId()).append('\n');
        sb.append("- 终算期: ").append(report.period()).append('\n');
        sb.append("- 回款窗口口径: ").append(report.windowMonths())
            .append(" 自然月（bonus.windowMonths，起算点=上市日期，AC-INC-32）\n");
        sb.append("- 触发动作: ").append(task.getActionCode())
            .append("（stageActionId=").append(task.getStageActionId()).append("）\n");
        sb.append("- 生成时间: ").append(ctx.clock().instant()).append('\n');
        sb.append("- 任务 ID: ").append(task.getId()).append('\n');
        if (report.noData()) {
            sb.append("\n> 尚无奖金池计算记录：台账全项标待补（W14-02 不按 0 伪判），请组长确认终算进度。\n");
        }
        sb.append("\n## 逐条对账（存储值 vs 确定性复算）\n\n");
        sb.append("| 项 | 口径 | 存储值 | 复算值 | 判定 | 备注 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Item i : report.items()) {
            sb.append("| ").append(cell(i.code())).append(" | ").append(cell(i.label()))
                .append(" | ").append(cell(i.stored())).append(" | ").append(cell(i.recomputed()))
                .append(" | ").append(cell(i.verdict())).append(" | ").append(cell(i.note())).append(" |\n");
        }
        sb.append("\n## 最终奖金池（ZK-IPD §三.2.5 完整公式）\n\n");
        sb.append("- 公式: finalPool = 回款基数 × poolRate × 项目系数 × 阶梯系数 × 个人绩效系数\n");
        sb.append("- 存储 finalPool: ").append(cell(report.storedFinalPool())).append('\n');
        sb.append("- 复算: ").append(cell(report.recomputedFinalPool())).append('\n');
        sb.append("- 判定: ").append(report.finalPoolVerdict()).append('\n');
        sb.append("- 个人绩效系数说明: ").append(cell(report.personalCoefficientNote())).append('\n');
        sb.append("\n> 免责声明：本台账为 AI 节点确定性复算产出（只读，未改写任何业务表）；")
            .append("奖金池最终金额与分配由人和制度流程决定，DIFF/DRIFT 的处置与是否重算，以产品组长真人复核为准。\n");
        return sb.toString();
    }

    /** 知会项目产品组长（终算对账结果责任人）；查不到组长仅 log.warn 跳过，与 W14 台账通知同严。 */
    private void notifyLeaders(AiAgentTask task, AiExecContext ctx, ReconcileReport report) {
        Set<Long> leaders = reconcileService.leadersOf(task.getProjectId());
        if (leaders.isEmpty()) {
            log.warn("R232 LC03 终算对账通知跳过：项目 {} 未解析到产品组长（taskId={}）",
                task.getProjectId(), task.getId());
            return;
        }
        Date day = Date.from(ctx.clock().instant());
        String title = "LC03 终算对账台账已生成（" + report.period() + "）";
        String content = "AI 已完成 LC03 上市后6个月终算对账：" + verdictLine(report)
            + "。请进入项目详情复核台账并处置差异（奖金池最终金额与分配由人决定）。";
        for (Long leaderId : leaders) {
            notificationService.publishDaily(leaderId, NotificationService.Types.SETTLEMENT_RECONCILE_LEDGER,
                NotificationService.KIND_ACTION, "ai_agent_task", task.getId(),
                title, content, "/projects/" + task.getProjectId(), day);
        }
    }

    private static String cell(String v) {
        return v == null ? "—" : v.replace("|", "\\|").replace("\n", " ");
    }
}
