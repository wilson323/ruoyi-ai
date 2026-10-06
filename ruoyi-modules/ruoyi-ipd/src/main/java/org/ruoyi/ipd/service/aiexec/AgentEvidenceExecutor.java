package org.ruoyi.ipd.service.aiexec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.AiGenerationService;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.ipd.service.ai.NodeAgentResolver;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Set;

/**
 * R236 节点智能体证据执行器：AI_DIRECT ∧ DEEP ∧ 无 valueFields 的 15 个动作。
 * 智能体（{@code agent_info} 中 {@code IPD-<动作码>} 行）出证据正文 → 经**已治理的**
 * {@link AiGenerationService#generate} 落 {@code ai_documents} → 同一正文上传 OSS 挂为交付物
 * （BR-IPD-03 深管 ≥1 交付物）→ {@code transit(DONE)}。
 *
 * <p><b>LC03 让渡（R232-LC03 终算对账执行者接管）</b>：LC03 原属本执行器 18 码，现改由
 * {@link Lc03SettlementReconcileExecutor} 确定性对账（零 LLM）接管——终算对账需 stored vs
 * 公式复算的硬对账而非 LLM 证据，故本执行器码集 18→17、种子 SQL 亦不再为 IPD-LC03 建行。
 *
 * <p><b>LC01 退役（2026-10-03）</b>：LC01（上市后销售与回款跟踪）已随「回款台账」功能块从
 * {@link org.ruoyi.ipd.seed.ActionCatalog} 整体退役（原 v3 的 69 动作 → 67 动作）。该动作已不在
 * 目录内，本执行器同步让出该码：码集 16→15。种子 SQL 中历史遗留的 {@code IPD-LC01} 建行由
 * 「待 owner 拍板」清理脚本草稿负责删除，本类不感知。
 *
 * <p><b>{@code supportsSchedule()=false}（契约 §7 B4，安全红线）</b>：本执行器把 LLM 产物直接
 * 作为 DONE 的门禁交付物，**没有独立人审环节**。若放开调度，SCHEDULE 每日自动派发会让
 * 「未人审的 LLM 草稿」成为动作完成的唯一证据 = 实质 AI 代签完成，违反红线 2。故只接受
 * 自然人在动作详情页点「AI 执行」的 PASSIVE 触发（与 C08 填表族、K01-K04 台账同款先例）。
 *
 * <p><b>降级不伪造（红线 5）</b>：未绑定智能体时降级为**确定性归集证据**并 {@code log.warn}
 * （md 内显式声明未使用智能体，不编造结论）——「AI 是快车道不是唯一车道」；但**已绑定却生成
 * 失败**绝不静默降级，一律 {@code fail} 走引擎退避重试、≥3 次 DEAD 转人工。
 *
 * <p><b>动态深度（契约 §7 B3）</b>：V11 在 {@code ActionCatalog.expectedDepth} 下随模板变深度
 * （SOLUTION → DEEP），而 {@code StageActionService} L117 按**实例** depth 判定、DEEP 强制 ≥1
 * 交付物。若 V11 归 {@code LightDirectExecutor}（无交付物）则 SOLUTION 项目必死于 BR-IPD-03 →
 * 退避 → DEAD。故 V11 归本执行器；其 LIGHT 实例同样产出证据文档（过度产出但合法，
 * LIGHT 只要求 actualDoneAt）。
 *
 * @see <a href="../../../../../../../../docs/ipd-系统说明/R236-生命周期节点智能体接线设计-20260927.md">R236 接线设计契约</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEvidenceExecutor implements AiActionExecutor {

    /** 15 码（契约 §7.3，R232-LC03 让出 LC03、R232-LC04 让出 LC04、2026-10-03 退役让出 LC01 后）：AI_DIRECT ∧ DEEP ∧ 无 valueFields，含动态深度码 V11。 */
    static final Set<String> CODES = Set.of(
        "C07", "C09", "C10", "P02", "P12", "V03", "V09", "V10",
        "V11", "V12", "L02", "L06", "LC05",
        "LC07", "LC09");

    private final StageActionService stageActionService;
    private final AiGenerationService aiGenerationService;
    private final ISysOssService ossService;
    private final NodeAgentResolver nodeAgentResolver;

    @Override
    public Set<String> supportedActionCodes() {
        return CODES;
    }

    /** 见类注释：无独立人审环节，不得被调度器自动派发（契约 §7 B4）。 */
    @Override
    public boolean supportsSchedule() {
        return false;
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        Long id = task.getStageActionId();
        AiExecResult terminal = AiActionExecutor.terminalNoOp(stageActionService.getById(id));
        if (terminal != null) {
            return terminal; // 人判/已完成动作重复触发不得再产证据、再挂交付物、再流转
        }
        ActionDef def = ActionCatalog.byCode(task.getActionCode());
        String agentPrompt = nodeAgentResolver.systemPromptOf(def.code());

        String markdown;
        Long aiDocId = null;
        if (agentPrompt != null) {
            String title = def.code() + " " + def.name() + "（AI 执行证据）";
            AiDocument doc;
            try {
                // 裁决 A：唯一 LLM 出口。generate() 内建 SSRF 前置 / 预算预检 / Semaphore 限流 /
                // RAG 注入 / 瞬时失败重试 / ai_documents 落库 / AI_GENERATE 审计（含 token 计量）
                doc = aiGenerationService.generate(ctx.systemActor(), new AiGenerateReq(
                    task.getProjectId(), ActionCatalog.docTypeOf(def.code()), title,
                    agentPrompt + "\n\n" + nodeContext(task, def)));
            } catch (RuntimeException ex) {
                // 红线 5：已绑定智能体却失败 → fail 走退避重试，不静默降级伪造完成
                return AiExecResult.fail(def.code() + " 节点智能体生成执行证据失败: " + ex.getMessage());
            }
            markdown = doc.getContent();
            if (markdown == null || markdown.isBlank()) {
                return AiExecResult.fail(def.code() + " 节点智能体返回空证据，不挂空交付物");
            }
            aiDocId = doc.getId();
        } else {
            log.warn("R236 {} 未绑定节点智能体 {}{}（种子 SQL 未 apply？），降级为确定性归集证据（taskId={}）",
                def.code(), NodeAgentResolver.NAME_PREFIX, def.code(), task.getId());
            markdown = fallbackEvidence(task, def, ctx);
        }

        String fileName = def.code() + "-" + def.name() + "-执行证据-"
            + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ctx.clock().getZone()).format(ctx.clock().instant())
            + ".md";
        // 先上传再落任何库状态：上传失败时不留下「有完成日却无交付物」的半成品
        SysOssVo uploaded = ossService.upload(new ByteArrayMultipartFile(
            "file", fileName, "text/markdown", markdown.getBytes(StandardCharsets.UTF_8)));
        if (uploaded == null || uploaded.getOssId() == null) {
            return AiExecResult.fail(def.code() + " 执行证据上传失败（OSS 未返回 ossId）");
        }
        Date now = Date.from(ctx.clock().instant());
        stageActionService.recordFields(id, now, null, null, null, null, null, null, "0");
        stageActionService.addDeliverable(id, fileName, uploaded.getOssId(), "0");
        stageActionService.transit(id, "DONE", "R236 AI 直接执行（DEEP 证据）", "0");
        return aiDocId == null
            ? AiExecResult.ok(def.code() + " 执行证据已确定性归集（未绑定智能体，降级路径），交付物 ossId="
                + uploaded.getOssId())
            : AiExecResult.ok(def.code() + " 节点智能体证据已生成并挂交付物（aiDocId=" + aiDocId
                + ", ossId=" + uploaded.getOssId() + "）", aiDocId);
    }

    /** 交给智能体的执行上下文（不含任何敏感字段；prompt 上限 30000 由 AiGenerateReq 校验）。 */
    private String nodeContext(AiAgentTask task, ActionDef def) {
        return "【执行上下文】\n"
            + "- 项目 ID: " + task.getProjectId() + '\n'
            + "- 阶段动作 ID: " + task.getStageActionId() + '\n'
            + "- 动作: " + def.code() + ' ' + def.name() + '\n'
            + "- 所属阶段: " + def.stage() + '\n'
            + "- 管控深度: " + def.depth() + "（深管动作须给出可核验依据）\n"
            + "- 责任角色: " + def.ownerRole() + '\n'
            + "- 触发方式: " + task.getTriggerType() + "（自然人触发，产物将作为本动作的执行证据）\n\n"
            + "【产出要求】直接输出 markdown 正文作为执行证据：须含结论、依据/数据来源、"
            + "以及显式标注的不确定项；禁止编造数据与引用，禁止输出「已批准/已通过/已签署」等代替人判的结论。";
    }

    /**
     * 降级证据（未绑定智能体）：只归集**已知事实**，显式声明未使用智能体、结论待人工补充。
     * 绝不编造执行结果——这是「降级不伪造」红线的落点。
     */
    private String fallbackEvidence(AiAgentTask task, ActionDef def, AiExecContext ctx) {
        return "# " + def.code() + ' ' + def.name() + "（执行证据 · 确定性归集）\n\n"
            + "- 项目 ID: " + task.getProjectId() + '\n'
            + "- 阶段动作 ID: " + task.getStageActionId() + '\n'
            + "- 所属阶段: " + def.stage() + '\n'
            + "- 责任角色: " + def.ownerRole() + '\n'
            + "- 归集时间: " + ctx.clock().instant() + '\n'
            + "- 触发方式: " + task.getTriggerType() + '\n'
            + "- 任务 ID: " + task.getId() + "\n\n"
            + "## 说明\n\n"
            + "本动作**未绑定节点智能体**（`agent_info` 中查无启用行 `"
            + NodeAgentResolver.NAME_PREFIX + def.code() + "`），故本文件仅归集系统已知事实，"
            + "**不含任何 AI 生成结论**。\n\n"
            + "## 待人工补充\n\n（执行结论、依据与数据来源由责任人补充）\n";
    }
}
