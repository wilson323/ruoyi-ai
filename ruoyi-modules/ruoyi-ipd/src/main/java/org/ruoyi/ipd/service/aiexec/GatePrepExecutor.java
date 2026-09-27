package org.ruoyi.ipd.service.aiexec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.GateElementResultService;
import org.ruoyi.ipd.service.GateElementService;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * R221 Gate 备料执行器（R236 扩至全部 5 个 HUMAN_GATE 动作）：AI 生成评审材料/会议纪要 md → OSS →
 * 对非否决要素批量预判 PASS 草稿 → 尝试 submit；否决项绝不代判（spec §5.1 红线）。
 * submit 被人判门槛拒绝属预期路径：仍通知双 PM 后 fail 引导（统一退避重试）。
 *
 * <p>R236 泛化：Gate 码一律取 {@code ActionCatalog.byCode(code).gate()}（本来就已数据驱动），
 * 本轮仅消除两处 {@code "C11"} 字面量（返回串与 log）并扩 {@code supportedActionCodes}。
 * 5 码的 ownerRole 均为 BOTH，故双 PM 通知（MARKET_PM + RD_PM）适配全部。
 * <b>本执行器不调 LLM</b>（材料/纪要为确定性模板），故不绑定节点智能体。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatePrepExecutor implements AiActionExecutor {

    private final GateMapper gateMapper;
    private final GateElementResultService gateElementResultService;
    private final ISysOssService ossService;
    private final NotificationService notificationService;
    private final ProjectMemberMapper projectMemberMapper;
    private final org.ruoyi.ipd.service.StageActionService stageActionService;

    /** 5 个 HUMAN_GATE 动作（契约 §7.3）：C11/G1、P13/G2、D05/G3、L07/G4、LC02/G5。 */
    static final Set<String> CODES = Set.of("C11", "P13", "D05", "L07", "LC02");

    @Override
    public Set<String> supportedActionCodes() {
        return CODES;
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        // 复审问题4（M3 同款）：动作级终态守卫——人判完成后重复触发不得再备料/上传/通知
        AiExecResult terminal = AiActionExecutor.terminalNoOp(stageActionService.getById(task.getStageActionId()));
        if (terminal != null) {
            return terminal;
        }
        String gateCode = ActionCatalog.byCode(task.getActionCode()).gate();
        Gate gate = gateMapper.selectOne(new LambdaQueryWrapper<Gate>()
            .eq(Gate::getProjectId, task.getProjectId())
            .eq(Gate::getGateCode, gateCode)
            .orderByDesc(Gate::getId)
            .last("LIMIT 1"));
        if (gate == null || gate.getStartedAt() != null || !"PENDING".equals(gate.getStatus())) {
            return AiExecResult.ok("no-op: Gate 已提交/终态/不存在，AI 不重复备料");
        }

        String day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ctx.clock().getZone())
            .format(ctx.clock().instant());
        // M2（CodeReview）：适用集必须复用 submit 同一权威口径（published + 排除 '0'），
        // 自建第二套查询会漏判 enabled=NULL/'Y' 行、多判 draft 越权行，导致备料集 ≠ 提交集。
        List<GateElement> elements = gateElementResultService.enabledElements(gate.getGateCode());
        Long materialsOssId = uploadMd(gateCode + "-评审材料-" + day + ".md", buildMaterials(gate, elements, ctx));
        Long minutesOssId = uploadMd(gateCode + "-会议纪要模板-" + day + ".md", buildMinutes(gate, ctx));

        for (GateElement el : elements) {
            if (isVeto(el)) {
                continue; // 否决项绝不代判——AI 不代签红线（spec §5.1）
            }
            gateElementResultService.judge(gate.getId(), el.getId(), "PASS", null,
                "AI-DRAFT-R221", 5, 0, null, null, ctx.systemActor());
        }

        String submitError = null;
        try {
            gateElementResultService.submit(gate.getId(), materialsOssId, minutesOssId, ctx.systemActor());
        } catch (ServiceException ex) {
            submitError = ex.getMessage();
        }
        notifyDualPm(task, ctx, gateCode, submitError);

        if (submitError != null) {
            return AiExecResult.fail("备料完成，待人判否决要素后提交: " + submitError);
        }
        return AiExecResult.ok(task.getActionCode() + ' ' + gateCode
            + " 备料完成：材料与纪要已挂、非否决要素预判 PASS、评审已提交待签署");
    }

    /** 否决判定：复用 GateElementService.normalizeFlag 归一（'Y'/'1' 同源语义，R219 双编码兼容）。 */
    private boolean isVeto(GateElement el) {
        return "1".equals(GateElementService.normalizeFlag(el.getIsVeto()));
    }

    private Long uploadMd(String fileName, String markdown) {
        SysOssVo vo = ossService.upload(new ByteArrayMultipartFile(
            "file", fileName, "text/markdown", markdown.getBytes(StandardCharsets.UTF_8)));
        if (vo == null || vo.getOssId() == null) {
            throw new ServiceException("Gate 备料文件上传失败（OSS 未返回 ossId）: " + fileName);
        }
        return vo.getOssId();
    }

    private String buildMaterials(Gate gate, List<GateElement> elements, AiExecContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(gate.getGateCode()).append(" 评审材料（AI 备料草稿）\n\n");
        sb.append("- 项目 ID: ").append(gate.getProjectId()).append('\n');
        sb.append("- Gate ID: ").append(gate.getId()).append('\n');
        sb.append("- 备料时间: ").append(ctx.clock().instant()).append('\n');
        sb.append("- 轮次: 第 ").append(gate.getCurrentRound() == null ? 1 : gate.getCurrentRound()).append(" 轮\n\n");
        sb.append("## 评审要素清单\n\n");
        for (GateElement el : elements) {
            sb.append("- ").append(el.getElementCode()).append(" ").append(el.getElementName())
                .append(isVeto(el) ? "（否决项，待人判）: " : ": ")
                .append(el.getPassStandard() == null ? "—" : el.getPassStandard()).append('\n');
        }
        sb.append("\n> 免责声明：本材料为 AI 备料草稿，结论以人签为准。\n");
        return sb.toString();
    }

    private String buildMinutes(Gate gate, AiExecContext ctx) {
        return "# " + gate.getGateCode() + " 会议纪要模板（AI 备料草稿）\n\n"
            + "- 项目 ID: " + gate.getProjectId() + "\n"
            + "- Gate ID: " + gate.getId() + "\n"
            + "- 备料时间: " + ctx.clock().instant() + "\n\n"
            + "## 议题\n\n（评审现场填写）\n\n## 结论与行动项\n\n（评审现场填写）\n\n"
            + "> 免责声明：本模板为 AI 备料草稿，结论以人签为准。\n";
    }

    /** 双 PM 通知（MARKET_PM + RD_PM 各一条，日级 dedup 防重试刷屏）；查不到成员仅 log.warn 跳过。 */
    private void notifyDualPm(AiAgentTask task, AiExecContext ctx, String gateCode, String submitError) {
        Date day = Date.from(ctx.clock().instant());
        String title = "Gate " + gateCode + " AI 备料" + (submitError == null ? "已提交，待签署" : "完成，待你判否决要素");
        String content = submitError == null
            ? "AI 已完成 " + gateCode + " 评审备料并提交，请进入评审页处理签署。"
            : "AI 已完成 " + gateCode + " 评审备料，但提交被拦：" + submitError + "。请人工判定否决要素后重新提交。";
        String actionUrl = "/projects/" + task.getProjectId();
        for (String role : List.of("MARKET_PM", "RD_PM")) {
            List<ProjectMember> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, task.getProjectId())
                .eq(ProjectMember::getRole, role)
                .isNull(ProjectMember::getExitDate));
            if (members.isEmpty()) {
                log.warn("R221 {} 通知跳过：项目 {} 无在任 {}（taskId={}）",
                    task.getActionCode(), task.getProjectId(), role, task.getId());
                continue;
            }
            for (ProjectMember m : members) {
                notificationService.publishDaily(m.getPersonId(), NotificationService.Types.AI_PREPARED_GATE,
                    NotificationService.KIND_ACTION, "ai_agent_task", task.getId(),
                    title, content, actionUrl, day);
            }
        }
    }
}
