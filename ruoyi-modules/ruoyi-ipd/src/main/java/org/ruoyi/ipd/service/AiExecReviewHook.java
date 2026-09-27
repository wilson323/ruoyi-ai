package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.aiexec.ByteArrayMultipartFile;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R221 EVENT hook 两闭环点（spec §3.2 Task 10）：
 * <ul>
 *   <li>{@link #onDocumentReviewed}：AI 草稿人审通过 → 交付物自动挂载 + 动作 DONE +
 *       同阶段「可自动派发」后继唤醒（EVENT 档）；</li>
 *   <li>{@link #onBootstrapped}：项目结构初始化尾 → 唤醒 dueDate 最近的第一个可自动派发 AI 档动作。</li>
 * </ul>
 * 无关联任务的普通文档审核完全 no-op（存量链路零影响）。宿主调用点均 try/catch 只 WARN
 * 不炸主链；hook 失败由兜底扫描器/人工触发兼容。唤醒口径 =
 * {@link AiExecutionEngine#scheduleWiredActionCodes()}（AI 档 ∧ 已接线 ∧ supportsSchedule），
 * 绝不清零到未接线/填表族码——建了必死（复审问题7 / 遗留 MINOR#2 同源教训）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiExecReviewHook {

    private final AiAgentTaskMapper taskMapper;
    private final StageActionService stageActionService;
    private final AiExecutionTrigger trigger;
    private final ISysOssService ossService;
    private final AiExecutionEngine engine;

    /**
     * 人审通过闭环（AiDocumentService.review 真实流转分支尾部调用）。
     *
     * @param docId     审核通过的 ai_documents 版本行 ID（= 任务行 ai_doc_id）
     * @param contentMd 审核版本正文（作为交付物 markdown 归档）
     */
    public void onDocumentReviewed(Long docId, String contentMd) {
        if (docId == null) {
            return;
        }
        List<AiAgentTask> linked = taskMapper.selectList(new LambdaQueryWrapper<AiAgentTask>()
            .eq(AiAgentTask::getAiDocId, docId)
            .eq(AiAgentTask::getStatus, AiAgentTask.STATUS_SUCCEEDED));
        if (linked.isEmpty()) {
            return; // 普通文档：零影响
        }
        Set<Long> closedStages = new HashSet<>();
        Long projectId = null;
        for (AiAgentTask task : linked) {
            if (task.getStageActionId() == null) {
                log.warn("[R221] review hook 跳过脏行（SUCCEEDED 无 stageActionId）taskId={}", task.getId());
                continue;
            }
            closeOne(task, contentMd);
            closedStages.add(stageIdOf(task.getStageActionId()));
            projectId = task.getProjectId();
        }
        if (projectId != null) {
            wakeSameStageSuccessors(projectId, closedStages);
        }
    }

    /** bootstrap 尾唤醒（ProjectBootstrapService.bootstrap 首次初始化成功路径调用）。 */
    public void onBootstrapped(Long projectId) {
        if (projectId == null) {
            return;
        }
        Set<String> auto = engine.scheduleWiredActionCodes();
        stageActionService.listByProject(projectId).stream()
            .filter(a -> "NOT_STARTED".equals(a.getStatus()))
            .filter(a -> a.getActionCode() != null && auto.contains(ActionCatalog.resolveCode(a.getActionCode())))
            .min(Comparator.comparing(StageAction::getDueDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .ifPresent(a -> trigger.triggerEvent(projectId, ActionCatalog.resolveCode(a.getActionCode()), a.getId()));
    }

    /** 单任务闭环：正文 → OSS → addDeliverable → transit(DONE)。 */
    private void closeOne(AiAgentTask task, String contentMd) {
        String day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault())
            .format(LocalDate.now(ZoneId.systemDefault()));
        String fileName = task.getActionCode() + "-人审闭环-" + day + ".md";
        SysOssVo vo = ossService.upload(new ByteArrayMultipartFile(
            "file", fileName, "text/markdown",
            (contentMd == null ? "" : contentMd).getBytes(StandardCharsets.UTF_8)));
        if (vo == null || vo.getOssId() == null) {
            throw new IllegalStateException("R221 review hook OSS 未返回 ossId: " + fileName);
        }
        stageActionService.addDeliverable(task.getStageActionId(), fileName, vo.getOssId(), "0");
        stageActionService.transit(task.getStageActionId(), "DONE", "R221 人审通过自动闭环", "0");
        log.info("[R221] review hook 闭环 actionId={} doc 交付物 ossId={}", task.getStageActionId(), vo.getOssId());
    }

    private Long stageIdOf(Long stageActionId) {
        StageAction a = stageActionService.getById(stageActionId);
        return a == null ? null : a.getStageId();
    }

    /** 宁漏勿错（首切片）：只唤醒同项目同阶段、NOT_STARTED、可自动派发的后继（spec §3.2）。 */
    private void wakeSameStageSuccessors(Long projectId, Set<Long> closedStages) {
        if (closedStages.isEmpty()) {
            return;
        }
        Set<String> auto = engine.scheduleWiredActionCodes();
        for (StageAction a : stageActionService.listByProject(projectId)) {
            if (!"NOT_STARTED".equals(a.getStatus()) || !closedStages.contains(a.getStageId())) {
                continue;
            }
            String code = ActionCatalog.resolveCode(a.getActionCode());
            if (code == null || !auto.contains(code)) {
                continue; // 未接线/填表族/HUMAN_GATE 一律不唤醒——EVENT 无载荷行建了必死
            }
            trigger.triggerEvent(projectId, code, a.getId());
        }
    }
}
