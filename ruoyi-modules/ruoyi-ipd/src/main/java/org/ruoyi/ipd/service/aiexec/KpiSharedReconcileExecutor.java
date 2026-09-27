package org.ruoyi.ipd.service.aiexec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.KpiSharedReconcileService;
import org.ruoyi.ipd.service.KpiSharedReconcileService.ConfirmRow;
import org.ruoyi.ipd.service.KpiSharedReconcileService.Item;
import org.ruoyi.ipd.service.KpiSharedReconcileService.ReconcileReport;
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
 * R232-W14 共担 KPI 对账执行器（智能体作为工作流节点执行者的首个样板）。
 *
 * <p>方向校准后路线：不是「存量页面外挂建议框」，而是接管 W14-03「确定性计算器交叉对账」节点的
 * 真实产出——逐项复算 / 对源表 / 双 PM 同分校验 → 生成对账台账 md → OSS → 挂动作交付物 →
 * 知会产品组长真人复核（ZK-IPD《IPD业务工作流与完整动作目录》W14-02/03）。
 *
 * <p>边界（四判据之「不改流程天花板」）：只读对账 + 交付物留痕，<b>不 transit、不代签、不改任何
 * 业务表</b>；归集录入与双组长确认仍是真人路径（collect / confirm 端点不动）。
 * {@code supportsSchedule()=false}：台账只由组长在动作详情页「AI 执行」PASSIVE 触发
 * （K01-K04 若被调度器每日自动派发会累积重复台账——同 C08 填表族的排除先例，遗留 MINOR#2）。
 *
 * <p>失败即引擎退避重试、≥3 次 DEAD 转人工（AiExecutionEngine 既有链路，本类不自建失败路径）；
 * 缺值在台账如实标 PENDING_DATA 转待补，不按 0 伪判、不阻断收尾（W14-02 语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiSharedReconcileExecutor implements AiActionExecutor {

    private final KpiSharedReconcileService reconcileService;
    private final ISysOssService ossService;
    private final StageActionService stageActionService;
    private final NotificationService notificationService;

    @Override
    public Set<String> supportedActionCodes() {
        // K01-K04 任一动作的「AI 执行」都触发同一份全周期台账（对账天然是四项整体，非单指标）
        return Set.of("K01", "K02", "K03", "K04");
    }

    @Override
    public boolean supportsSchedule() {
        return false;
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        // 终态守卫不适用：对账是只读复核，K 动作 DONE 后再对账仍合法（不复用 terminalNoOp，防误吞复核）
        ReconcileReport report = reconcileService.reconcile(task.getProjectId(), ctx.clock());
        String fileName = "W14-共担KPI对账台账-" + report.period() + "-"
            + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ctx.clock().getZone())
                .format(ctx.clock().instant()) + ".md";
        Long ossId = uploadMd(fileName, buildLedger(task, report, ctx));

        if (task.getStageActionId() != null) {
            // 台账挂触发动作做证据留痕（BR-IPD-03 深管交付物通道）；只挂不改状态
            stageActionService.addDeliverable(task.getStageActionId(), fileName, ossId, "0");
        }
        notifyLeaders(task, ctx, report);

        String summary = "W14 共担KPI对账 period=" + report.period() + "：" + verdictLine(report)
            + "；台账 " + fileName + "（ossId=" + ossId + "），处置待组长复核";
        return AiExecResult.ok(summary, ossId);
    }

    private String verdictLine(ReconcileReport report) {
        Map<String, Long> counts = report.verdictCounts();
        StringBuilder sb = new StringBuilder();
        counts.forEach((verdict, n) -> sb.append(verdict).append(' ').append(n).append(" 项；"));
        sb.append("综合分").append(report.totalVerdict())
            .append("，双PM").append(report.dualPmVerdict());
        return sb.toString();
    }

    private Long uploadMd(String fileName, String markdown) {
        SysOssVo vo = ossService.upload(new ByteArrayMultipartFile(
            "file", fileName, "text/markdown", markdown.getBytes(StandardCharsets.UTF_8)));
        if (vo == null || vo.getOssId() == null) {
            throw new IllegalStateException("对账台账上传失败（OSS 未返回 ossId）: " + fileName);
        }
        return vo.getOssId();
    }

    String buildLedger(AiAgentTask task, ReconcileReport report, AiExecContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 共担 KPI 数据对账台账（W14-03 确定性复算 · AI 节点产出）\n\n");
        sb.append("- 项目 ID: ").append(report.projectId()).append('\n');
        sb.append("- 对账周期: ").append(report.period()).append('\n');
        sb.append("- 最新 revision: ").append(report.revision()).append('\n');
        sb.append("- 触发动作: ").append(task.getActionCode())
            .append("（stageActionId=").append(task.getStageActionId()).append("）\n");
        sb.append("- 生成时间: ").append(ctx.clock().instant()).append('\n');
        sb.append("- 任务 ID: ").append(task.getId()).append('\n');
        if (report.noData()) {
            sb.append("\n> 本期尚无归集记录：台账全项标待补（W14-02 不按 0 扣分），请组长确认归集进度。\n");
        }
        sb.append("\n## 逐条对账（存储值 vs 确定性复算 vs 源表现值）\n\n");
        sb.append("| 指标 | 来源 | 实际值 | 存储目标 | 源表现目标 | 存储得分 | 复算得分 | 判定 | 备注 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Item i : report.items()) {
            sb.append("| ").append(cell(i.code())).append(" | ").append(cell(i.source()))
                .append(" | ").append(cell(i.actual())).append(" | ").append(cell(i.storedTarget()))
                .append(" | ").append(cell(i.currentTarget())).append(" | ").append(cell(i.storedScore()))
                .append(" | ").append(cell(i.recomputedScore())).append(" | ").append(cell(i.verdict()))
                .append(" | ").append(cell(i.note())).append(" |\n");
        }
        sb.append("\n## 综合分复算\n\n");
        sb.append("- 存储 comprehensive_score: ").append(cell(report.storedTotal())).append('\n');
        sb.append("- 明细同公式复算: ").append(cell(report.recomputedTotal())).append('\n');
        sb.append("- 判定: ").append(report.totalVerdict()).append('\n');
        sb.append("\n## 双 PM 同分（AC-KPI-11）\n\n");
        sb.append("- 判定: ").append(report.dualPmVerdict()).append('\n');
        sb.append("- 说明: ").append(cell(report.dualPmNote())).append('\n');
        sb.append("\n## 确认链状态（K01-K04 双组长签署）\n\n");
        if (report.confirms().isEmpty()) {
            sb.append("（无确认行——归集后由 ensurePendingRows 联动生成）\n");
        } else {
            sb.append("| 指标 | 状态 | 首签 | 二签 |\n|---|---|---|---|\n");
            for (ConfirmRow c : report.confirms()) {
                sb.append("| ").append(cell(c.metricCode())).append(" | ").append(cell(c.status()))
                    .append(" | ").append(cell(c.firstConfirmedBy())).append(" | ")
                    .append(cell(c.secondConfirmedBy())).append(" |\n");
            }
        }
        sb.append("\n> 免责声明：本台账为 AI 节点确定性复算产出（只读，未改写任何业务表）；")
            .append("DIFF/DRIFT 的处置与是否重归集，以产品组长真人复核为准。\n");
        return sb.toString();
    }

    /** 知会项目产品组长（对账结果责任人）；查不到组长仅 log.warn 跳过，与 GatePrep 通知同严。 */
    private void notifyLeaders(AiAgentTask task, AiExecContext ctx, ReconcileReport report) {
        Set<Long> leaders = reconcileService.leadersOf(task.getProjectId());
        if (leaders.isEmpty()) {
            log.warn("R232 W14 对账通知跳过：项目 {} 未解析到产品组长（taskId={}）",
                task.getProjectId(), task.getId());
            return;
        }
        Date day = Date.from(ctx.clock().instant());
        String title = "共担 KPI 对账台账已生成（" + report.period() + "）";
        String content = "AI 已完成 W14 数据对账：" + verdictLine(report) + "。请进入项目详情复核台账并处置差异。";
        for (Long leaderId : leaders) {
            notificationService.publishDaily(leaderId, NotificationService.Types.KPI_RECONCILE_LEDGER,
                NotificationService.KIND_ACTION, "ai_agent_task", task.getId(),
                title, content, "/projects/" + task.getProjectId(), day);
        }
    }

    private static String cell(String v) {
        return v == null ? "—" : v.replace("|", "\\|").replace("\n", " ");
    }
}
