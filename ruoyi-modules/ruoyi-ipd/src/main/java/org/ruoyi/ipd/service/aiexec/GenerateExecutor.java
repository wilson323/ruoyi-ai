package org.ruoyi.ipd.service.aiexec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.PromptType;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.AiGenerationService;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.ipd.service.ai.NodeAgentResolver;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * R221 AI 生成执行器（R236 扩至全部 24 个 AI_GENERATE 动作）：AI 草稿 → 动作只到 IN_PROGRESS 待人审，
 * 绝不代签 DONE（人审通过后由 review hook 挂交付物收尾，spec §4.2/§5.1）。
 *
 * <p><b>R236 消除三处硬编码（契约 §7 B6）</b>：
 * <ol>
 *   <li><b>prompt</b>：原市场营销文案对 24 码中 10 个 RD_PM 动作不适配 → 改为取节点智能体
 *       {@code IPD-<code>} 的 system_prompt（{@link NodeAgentResolver}）；</li>
 *   <li><b>docType</b>：原写死 {@code "MARKET_RESEARCH"} → 改取 {@link ActionCatalog#docTypeOf}；
 *       docType 会驱动 RAG Phase-2 按类型过滤（{@code AiGenerationService} L143-146），写死即注入错料；</li>
 *   <li><b>通知角色</b>：原写死 {@code "MARKET_PM"} → 改取 {@code def.ownerRole()}，
 *       否则 10 个 RD_PM 动作的草稿会全部通知错人。本执行器 24 码的 ownerRole 实测只取
 *       {@code MARKET_PM}(14)/{@code RD_PM}(10) 两个单角色，故直接按该角色查在任成员，
 *       不做 {@code BOTH} 扇出（{@code project_member} 亦无 GROUP_LEADER 行，产品组长挂在
 *       {@code product_group.leader_person_id}）；若日后目录给 AI_GENERATE 动作引入
 *       {@code BOTH}/{@code GROUP_LEADER}，由哨兵 {@code ExecutorCoverageSentinelTest
 *       #generateCodesHaveSingleNotifiableOwnerRole} 显式失败提示补扇出，而非静默通知不到人。</li>
 * </ol>
 *
 * <p><b>降级链复用成熟件（不硬编码文案）</b>：未绑定智能体时，若 docType 落在
 * {@link PromptType} 值域则把它作为 {@code promptType} 下发，直接走既有 {@code PromptTemplates}
 * 8 套模板（{@code AiGenerationService} L153 已接入）；无匹配模板才用 {@code def} 字段拼确定性指令。
 * 绑定时 {@code promptType} 留 null，避免智能体指令与模板**双重叠加**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerateExecutor implements AiActionExecutor {

    /** 24 码（契约 §7.3）：全部 AI_GENERATE 动作。 */
    static final Set<String> CODES = Set.of(
        "C01", "C02", "C03", "C04", "C05", "C06", "C12", "P01",
        "P03", "P04", "P05", "P06", "P07", "P11", "D01", "D04",
        "D06", "V06", "V07", "V08", "L01", "L03", "L04", "LC08");

    private final AiGenerationService aiGenerationService;
    private final StageActionService stageActionService;
    private final NotificationService notificationService;
    private final ProjectMemberMapper projectMemberMapper;
    private final NodeAgentResolver nodeAgentResolver;

    @Override
    public Set<String> supportedActionCodes() {
        return CODES;
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        AiExecResult terminal = AiActionExecutor.terminalNoOp(stageActionService.getById(task.getStageActionId()));
        if (terminal != null) {
            return terminal; // M3：人审已完结后重复触发不得把 DONE 打回 IN_PROGRESS/重复生成
        }
        ActionDef def = ActionCatalog.byCode(task.getActionCode());
        String docType = ActionCatalog.docTypeOf(def.code());
        String title = def.code() + " " + def.name() + "（AI 草稿）";

        String agentPrompt = nodeAgentResolver.systemPromptOf(def.code());
        String promptType = null;
        String prompt;
        if (agentPrompt != null) {
            prompt = agentPrompt + "\n\n" + draftContext(task, def);
        } else {
            // 降级：docType 命中 PromptType 值域 → 复用既有 8 套成熟模板（而非硬编码文案）
            promptType = PromptType.fromCodeOrNull(docType) == null ? null : docType;
            prompt = fallbackPrompt(task, def);
            log.warn("R236 {} 未绑定节点智能体 {}{}，降级为确定性指令{}（taskId={}）",
                def.code(), NodeAgentResolver.NAME_PREFIX, def.code(),
                promptType == null ? "（无匹配 PromptType 模板）" : "（PromptTemplates " + promptType + "）",
                task.getId());
        }

        AiDocument doc = aiGenerationService.generate(ctx.systemActor(),
            new AiGenerateReq(task.getProjectId(), docType, title, prompt, promptType));
        stageActionService.transit(task.getStageActionId(), "IN_PROGRESS", "R221 AI 生成草稿待人审", "0");
        notifyPm(task, ctx, def.ownerRole(), title);
        return AiExecResult.ok(def.code() + " AI 草稿已生成，待责任人（" + def.ownerRole()
            + "）审核后自动挂交付物（aiDocId=" + doc.getId() + "）", doc.getId());
    }

    /** 交给智能体的草稿上下文（产物是**待人审草稿**，故要求显式标注不确定项）。 */
    private String draftContext(AiAgentTask task, ActionDef def) {
        return "【执行上下文】\n"
            + "- 项目 ID: " + task.getProjectId() + '\n'
            + "- 阶段动作 ID: " + task.getStageActionId() + '\n'
            + "- 动作: " + def.code() + ' ' + def.name() + '\n'
            + "- 所属阶段: " + def.stage() + "；责任角色: " + def.ownerRole() + '\n'
            + "- 触发方式: " + task.getTriggerType() + "\n\n"
            + "【产出要求】输出供人审的 markdown 草稿；不确定数据标注[待补]，不得编造实测结论，"
            + "不得输出「已批准/已通过/已签署」等代替人判的结论。";
    }

    /** 降级指令：仅用 {@code def} 已有字段拼确定性文案，无角色/阶段偏见（取代原市场营销硬编码）。 */
    private String fallbackPrompt(AiAgentTask task, ActionDef def) {
        return "请为项目 " + task.getProjectId() + " 起草「" + def.name() + "」（" + def.stage()
            + " 阶段动作 " + def.code() + "，责任角色 " + def.ownerRole()
            + "）。要求：输出结构化草稿供人审；不确定数据标注[待补]，不得编造实测结论。";
    }

    /** 通知对在任责任角色 PM；查不到成员仅 log.warn 跳过不炸（范式照 StageActionService.notifyOverdueActions）。 */
    private void notifyPm(AiAgentTask task, AiExecContext ctx, String role, String docTitle) {
        List<ProjectMember> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, task.getProjectId())
            .eq(ProjectMember::getRole, role)
            .isNull(ProjectMember::getExitDate));
        if (members.isEmpty()) {
            log.warn("R221 {} 通知跳过：项目 {} 无在任 {}（taskId={}）",
                task.getActionCode(), task.getProjectId(), role, task.getId());
            return;
        }
        Date day = Date.from(ctx.clock().instant());
        String content = "AI 已为动作「" + task.getActionCode() + "」生成草稿《" + docTitle + "》，请进入动作详情页审核。";
        String actionUrl = "/projects/" + task.getProjectId();
        for (ProjectMember m : members) {
            notificationService.publishDaily(m.getPersonId(), NotificationService.Types.AI_PREPARED_GENERATE,
                NotificationService.KIND_ACTION, "ai_agent_task", task.getId(),
                "AI 草稿待审: " + docTitle, content, actionUrl, day);
        }
    }
}
