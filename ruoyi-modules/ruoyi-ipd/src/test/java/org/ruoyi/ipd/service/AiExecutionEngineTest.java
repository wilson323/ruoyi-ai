package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.service.aiexec.AiActionExecutor;
import org.ruoyi.ipd.service.aiexec.AiExecContext;
import org.ruoyi.ipd.service.aiexec.AiExecResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AiExecutionEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    @Mock private AiAgentTaskMapper taskMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private NotificationService notificationService;

    private AiExecutionEngine engine;
    private RecordingExecutor executor;

    /** mock 合法性：executor 记录调用并返回可编程结果，不伪造业务态 */
    static class RecordingExecutor implements AiActionExecutor {
        AiExecResult result = AiExecResult.ok("done-summary");
        RuntimeException boom;
        AiAgentTask lastTask;
        @Override public Set<String> supportedActionCodes() { return Set.of("P08"); }
        @Override public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
            lastTask = task;
            if (boom != null) { throw boom; }
            return result;
        }
    }

    @BeforeAll
    static void initTable() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
            AiAgentTask.class);
    }

    @BeforeEach
    void setUp() {
        executor = new RecordingExecutor();
        engine = new AiExecutionEngine(taskMapper, List.of(executor), auditLogService, notificationService);
        engine.withClock(Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    private AiAgentTask pending() {
        return AiAgentTask.builder().id(1L).projectId(100L).actionCode("P08").stageActionId(9002L)
            .triggerType(AiAgentTask.TRIGGER_PASSIVE).execMode("AI_DIRECT")
            .status(AiAgentTask.STATUS_PENDING).dedupKey("P08:9002:PASSIVE").attempt(0).version(0)
            .build();
    }

    @Test
    void claimUsesConditionalUpdateAndSkipsWhenLost() {
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending()));
        when(taskMapper.update(isNull(), any())).thenReturn(0); // 并发被抢

        int n = engine.dispatchCycle(10);

        assertThat(n).isZero();
        assertThat(executor.lastTask).isNull();
    }

    @Test
    void successPathMarksSucceededWithSummaryAndAudit() {
        AiAgentTask t = pending();
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectById(1L)).thenReturn(t);

        int n = engine.dispatchCycle(10);

        assertThat(n).isEqualTo(1);
        assertThat(t.getStatus()).isEqualTo(AiAgentTask.STATUS_SUCCEEDED);
        assertThat(t.getResultSummary()).isEqualTo("done-summary");
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo("AI_EXEC");
        assertThat(cap.getValue().getEntityType()).isEqualTo("ai_agent_task");
    }

    @Test
    void failureBelowMaxRetriesMarksFailedWithBackoff30s() {
        executor.boom = new IllegalStateException("transit rejected");
        AiAgentTask t = pending();
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectById(1L)).thenReturn(t);

        engine.dispatchCycle(10);

        assertThat(t.getStatus()).isEqualTo(AiAgentTask.STATUS_FAILED);
        assertThat(t.getAttempt()).isEqualTo(1);
        assertThat(t.getNextRetryAt().toInstant()).isEqualTo(NOW.plusSeconds(30));
        assertThat(t.getErrorMsg()).contains("transit rejected");
    }

    @Test
    void thirdFailureMarksDeadAndNotifies() {
        executor.boom = new IllegalStateException("still failing");
        AiAgentTask t = pending();
        t.setAttempt(2); // mock 合法性：真库 FAILED 退避路径可产生 attempt=2
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectById(1L)).thenReturn(t);

        engine.dispatchCycle(10);

        assertThat(t.getStatus()).isEqualTo(AiAgentTask.STATUS_DEAD);
        verify(notificationService).publish(any(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo("AI_EXEC_FAILED");
    }

    @Test
    void noExecutorForCodeFailsFastWithGuidance() {
        AiAgentTask t = pending();
        t.setActionCode("C01"); // executor 只支持 P08
        t.setExecMode("AI_GENERATE");
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(t));
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(taskMapper.selectById(1L)).thenReturn(t);

        engine.dispatchCycle(10);

        assertThat(t.getStatus()).isEqualTo(AiAgentTask.STATUS_FAILED);
        assertThat(t.getErrorMsg()).contains("无已接线执行器");
    }
}
