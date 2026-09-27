package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R221 Task 10：EVENT hook 两闭环点（spec §3.2）。
 * review 通过后自动挂交付物 + DONE + 唤醒同阶段后继；bootstrap 尾唤醒首个已接线 AI 档动作。
 * mock 合法性：SUCCEEDED + ai_doc_id 关联行是 GenerateExecutor 落库的真态组合；
 * 无关联任务的普通文档是存量主流（钩子必须 no-op，零影响）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AiExecEventHookTest {

    @Mock private AiAgentTaskMapper taskMapper;
    @Mock private StageActionService stageActionService;
    @Mock private AiExecutionTrigger trigger;
    @Mock private ISysOssService ossService;
    @Mock private AiExecutionEngine engine;

    private AiExecReviewHook hook() {
        return new AiExecReviewHook(taskMapper, stageActionService, trigger, ossService, engine);
    }

    private static StageAction action(long id, long projectId, long stageId, String code, String status) {
        StageAction a = new StageAction();
        a.setId(id);
        a.setProjectId(projectId);
        a.setStageId(stageId);
        a.setActionCode(code);
        a.setStatus(status);
        return a;
    }

    private static org.ruoyi.system.domain.vo.SysOssVo ossVo(long ossId) {
        var vo = new org.ruoyi.system.domain.vo.SysOssVo();
        vo.setOssId(ossId);
        return vo;
    }

    /** 复审 W1 行为锁（CodeReview 54b1e3ec..8a8fe615）：host 事务活跃时 closeOne 必须延迟到
     * afterCommit 独立事务运行——addDeliverable/transit 抛 ServiceException 会把 review 事务打成
     * rollback-only，同步内联调用时宿主 try/catch 拦不住（审核被反噬 500 + OSS 孤儿）。
     * 无事务同步（单测直调）同步执行是既有语义，由 closesLoop 正例锁住。 */
    @Test
    void closeDefersToAfterCommitWhenHostTransactionActive() {
        AiAgentTask linked = AiAgentTask.builder().id(3L).projectId(100L).actionCode("C01")
            .stageActionId(9001L).status(AiAgentTask.STATUS_SUCCEEDED).aiDocId(4401L).build();
        when(taskMapper.selectList(any())).thenReturn(List.of(linked));
        when(stageActionService.getById(9001L)).thenReturn(action(9001L, 100L, 10L, "C01", "IN_PROGRESS"));
        when(ossService.upload(any(org.springframework.web.multipart.MultipartFile.class)))
            .thenReturn(ossVo(777L));
        when(engine.scheduleWiredActionCodes()).thenReturn(Set.of("C01", "P08"));
        when(stageActionService.listByProject(100L)).thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            hook().onDocumentReviewed(4401L, "# 草稿正文");
            verify(stageActionService, never()).transit(anyLong(), anyString(), anyString(), anyString());
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            // afterCommit 时机（review 事务已提交，不再是 rollback-only 风险窗口）→ 闭环真正执行
            syncs.get(0).afterCommit();
            verify(stageActionService).transit(9001L, "DONE", "R221 人审通过自动闭环", "0");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** review 通过 hook：文档挂着 SUCCEEDED 任务 → 自动挂交付物 + DONE；同阶段后继唤醒只碰可自动派发集
     *（C08 属 DEEP 填表族 supportsSchedule=false，唤醒即必死；C02 未接线；首切片 CONCEPT 阶段无自动后继） */
    @Test
    void reviewApprovedWithLinkedTaskClosesLoop() {
        AiAgentTask linked = AiAgentTask.builder().id(3L).projectId(100L).actionCode("C01")
            .stageActionId(9001L).status(AiAgentTask.STATUS_SUCCEEDED).aiDocId(4401L).build();
        when(taskMapper.selectList(any())).thenReturn(List.of(linked));
        var vo = new org.ruoyi.system.domain.vo.SysOssVo();
        vo.setOssId(8803L);
        when(ossService.upload(any(org.springframework.web.multipart.MultipartFile.class))).thenReturn(vo);
        when(stageActionService.getById(9001L)).thenReturn(action(9001L, 100L, 500L, "C01", "IN_PROGRESS"));
        when(stageActionService.listByProject(100L)).thenReturn(List.of(
            action(9002L, 100L, 500L, "C08", "NOT_STARTED"),
            action(9003L, 100L, 500L, "C02", "NOT_STARTED"),
            action(9004L, 100L, 600L, "P08", "NOT_STARTED")));
        when(engine.scheduleWiredActionCodes()).thenReturn(java.util.Set.of("C01", "P08"));

        hook().onDocumentReviewed(4401L, "# C01 调研草稿正文");

        verify(stageActionService).addDeliverable(eq(9001L), any(String.class), eq(8803L), eq("0"));
        verify(stageActionService).transit(eq(9001L), eq("DONE"), any(String.class), eq("0"));
        // 可自动派发的 P08 在异阶段：首切片宁漏勿错，只唤醒同阶段 → 本场景零唤醒
        verify(trigger, never()).triggerEvent(anyLong(), anyString(), anyLong());
    }

    /** 无关联任务的普通文档审核 → 完全 no-op（存量链路零影响） */
    @Test
    void reviewApprovedWithoutLinkedTaskIsNoOp() {
        when(taskMapper.selectList(any())).thenReturn(List.of());

        hook().onDocumentReviewed(5555L, "正文");

        verify(stageActionService, never()).transit(anyLong(), anyString(), anyString(), anyString());
        verify(ossService, never()).upload(any(org.springframework.web.multipart.MultipartFile.class));
        verify(trigger, never()).triggerEvent(any(), any(), any());
    }

    /** SUCCEEDED 行存在但 stageActionId 缺失（历史脏数据防御）→ 不炸主链，跳过该行 */
    @Test
    void linkedTaskWithoutStageActionIdIsSkipped() {
        AiAgentTask dirty = AiAgentTask.builder().id(4L).projectId(100L).actionCode("C01")
            .status(AiAgentTask.STATUS_SUCCEEDED).aiDocId(4402L).build();
        when(taskMapper.selectList(any())).thenReturn(List.of(dirty));

        hook().onDocumentReviewed(4402L, "正文");

        verify(ossService, never()).upload(any(org.springframework.web.multipart.MultipartFile.class));
        verify(stageActionService, never()).transit(anyLong(), anyString(), anyString(), anyString());
    }

    /** bootstrap 尾：只唤醒 dueDate 最近的「可自动派发」AI 档 NOT_STARTED 动作；
     * C08（填表族）/C02（未接线）/更早日期的未接线码都不碰 */
    @Test
    void bootstrappedTriggersEarliestWiredAiActionOnly() {
        StageAction c01 = action(1L, 100L, 500L, "C01", "NOT_STARTED");
        c01.setDueDate(Date.from(Instant.parse("2026-10-01T00:00:00Z")));
        StageAction c08Late = action(2L, 100L, 500L, "C08", "NOT_STARTED");
        c08Late.setDueDate(Date.from(Instant.parse("2026-10-05T00:00:00Z")));
        StageAction c02Unwired = action(3L, 100L, 500L, "C02", "NOT_STARTED");
        c02Unwired.setDueDate(Date.from(Instant.parse("2026-09-30T00:00:00Z"))); // 更早但未接线
        when(stageActionService.listByProject(100L)).thenReturn(List.of(c01, c08Late, c02Unwired));
        when(engine.scheduleWiredActionCodes()).thenReturn(java.util.Set.of("C01", "P08"));

        hook().onBootstrapped(100L);

        verify(trigger).triggerEvent(100L, "C01", 1L);
        verify(trigger, never()).triggerEvent(anyLong(), eq("C08"), anyLong());
        verify(trigger, never()).triggerEvent(anyLong(), eq("C02"), anyLong());
    }

    /** bootstrap 项目无可唤醒候选（全 DONE / 全不可自动派发）→ no-op 不抛 */
    @Test
    void bootstrappedWithoutCandidateIsNoOp() {
        when(stageActionService.listByProject(100L)).thenReturn(List.of(
            action(1L, 100L, 500L, "C01", "DONE"),
            action(2L, 100L, 500L, "C02", "NOT_STARTED")));
        when(engine.scheduleWiredActionCodes()).thenReturn(java.util.Set.of("C01", "P08"));

        hook().onBootstrapped(100L);

        verify(trigger, never()).triggerEvent(any(), any(), any());
    }
}
