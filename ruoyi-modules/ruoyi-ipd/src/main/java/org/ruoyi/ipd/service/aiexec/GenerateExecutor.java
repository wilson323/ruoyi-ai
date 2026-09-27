package org.ruoyi.ipd.service.aiexec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.AiGenerationService;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.StageActionService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * R221 AI 生成执行器（首切片 C01）：AI 草稿 → 动作只到 IN_PROGRESS 待人审，
 * 绝不代签 DONE（人审通过后由 review hook 挂交付物收尾，spec §4.2/§5.1）。
 * 通知市场 PM 审草稿：project_members role=MARKET_PM，日级 dedup 防重试刷屏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerateExecutor implements AiActionExecutor {

    private final AiGenerationService aiGenerationService;
    private final StageActionService stageActionService;
    private final NotificationService notificationService;
    private final ProjectMemberMapper projectMemberMapper;

    @Override
    public Set<String> supportedActionCodes() {
        return Set.of("C01");
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        AiExecResult terminal = AiActionExecutor.terminalNoOp(stageActionService.getById(task.getStageActionId()));
        if (terminal != null) {
            return terminal; // M3：人审已完结后重复触发不得把 DONE 打回 IN_PROGRESS/重复生成
        }
        var def = ActionCatalog.byCode(task.getActionCode());
        String title = def.code() + " " + def.name() + "（AI 草稿）";
        String prompt = "请为项目 " + task.getProjectId() + " 起草「" + def.name() + "」（"
            + def.stage() + " 阶段动作 " + def.code() + "）。要求：基于市场机会与用户痛点视角，"
            + "输出结构化草稿供市场 PM 审核；不确定数据标注[待补]，不得编造实测结论。";
        AiDocument doc = aiGenerationService.generate(ctx.systemActor(),
            new AiGenerateReq(task.getProjectId(), "MARKET_RESEARCH", title, prompt));
        stageActionService.transit(task.getStageActionId(), "IN_PROGRESS", "R221 AI 生成草稿待人审", "0");
        notifyPm(task, ctx, "MARKET_PM", title);
        return AiExecResult.ok(def.code() + " AI 草稿已生成，待市场 PM 审核后自动挂交付物（aiDocId=" + doc.getId() + "）",
            doc.getId());
    }

    /** 通知对在任指定角色 PM；查不到成员仅 log.warn 跳过不炸（范式照 StageActionService.notifyOverdueActions）。 */
    private void notifyPm(AiAgentTask task, AiExecContext ctx, String role, String docTitle) {
        List<ProjectMember> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, task.getProjectId())
            .eq(ProjectMember::getRole, role)
            .isNull(ProjectMember::getExitDate));
        if (members.isEmpty()) {
            log.warn("R221 C01 通知跳过：项目 {} 无在任 {}（taskId={}）", task.getProjectId(), role, task.getId());
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
