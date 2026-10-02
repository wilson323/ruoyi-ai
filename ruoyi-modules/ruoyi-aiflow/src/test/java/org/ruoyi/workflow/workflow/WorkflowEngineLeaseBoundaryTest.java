package org.ruoyi.workflow.workflow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.workflow.entity.Workflow;
import org.ruoyi.workflow.entity.WorkflowRuntime;
import org.ruoyi.workflow.service.WorkflowRuntimeService;
import org.ruoyi.workflow.service.WorkflowRuntimeNodeService;
import org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver;
import java.util.List;
import static org.mockito.Mockito.*;

/** 竞争者和失去租约的恢复尝试不能改原运行或创建节点。 */
@Tag("dev")
class WorkflowEngineLeaseBoundaryTest {
    @Test
    void rejectedResumeDoesNotWriteRuntimeOrNodes() throws Exception {
        JdbcCheckpointSaver saver = mock(JdbcCheckpointSaver.class);
        when(saver.acquireRun("busy-runtime")).thenThrow(new IllegalStateException("工作流正在执行，不能同时恢复"));
        WorkflowRuntimeService runtimes = mock(WorkflowRuntimeService.class);
        WorkflowRuntimeNodeService nodes = mock(WorkflowRuntimeNodeService.class);
        WorkflowEngine engine = new WorkflowEngine(new Workflow(), List.of(), List.of(), List.of(), runtimes, nodes, saver);
        WorkflowRuntime runtime = new WorkflowRuntime();
        runtime.setUuid("busy-runtime");
        runtime.setId(1L);
        engine.resume(new User(), runtime, null, null, null, null);
        verifyNoInteractions(runtimes, nodes);
        verify(saver).acquireRun("busy-runtime");
    }

    @Test
    void freshSucceededRuntimeRejectsStaleFailedSnapshotWithoutWrites() throws Exception {
        JdbcCheckpointSaver saver = mock(JdbcCheckpointSaver.class);
        JdbcCheckpointSaver.RunLease lease = mock(JdbcCheckpointSaver.RunLease.class);
        when(saver.acquireRun("ended-runtime")).thenReturn(lease);
        WorkflowRuntimeService runtimes = mock(WorkflowRuntimeService.class);
        WorkflowRuntimeNodeService nodes = mock(WorkflowRuntimeNodeService.class);
        Workflow workflow = new Workflow();
        workflow.setId(7L);
        WorkflowRuntime stale = new WorkflowRuntime();
        stale.setUuid("ended-runtime"); stale.setId(3L); stale.setWorkflowId(7L); stale.setStatus(4);
        WorkflowRuntime fresh = new WorkflowRuntime();
        fresh.setUuid("ended-runtime"); fresh.setId(3L); fresh.setWorkflowId(7L); fresh.setStatus(3);
        when(runtimes.getByUuidForResume("ended-runtime")).thenReturn(fresh);
        WorkflowEngine engine = new WorkflowEngine(workflow, List.of(), List.of(), List.of(), runtimes, nodes, saver);
        engine.resume(new User(), stale, null, null, null, null);
        verify(runtimes).getByUuidForResume("ended-runtime");
        verifyNoMoreInteractions(runtimes);
        verifyNoInteractions(nodes);
        verify(lease).close();
    }

    @Test
    void completionNotificationFailureDoesNotDowngradePersistedSuccess() throws Exception {
        JdbcCheckpointSaver saver = mock(JdbcCheckpointSaver.class);
        JdbcCheckpointSaver.RunLease lease = mock(JdbcCheckpointSaver.RunLease.class);
        WorkflowExecutionPlan plan = mock(WorkflowExecutionPlan.class);
        when(plan.executeUnderLease(eq(saver), eq("success-runtime"), eq(true), anyMap(), any(), eq(lease)))
            .thenReturn("__END__");
        WorkflowRuntimeService runtimes = mock(WorkflowRuntimeService.class);
        WorkflowRuntimeNodeService nodes = mock(WorkflowRuntimeNodeService.class);
        WorkflowEngine engine = new WorkflowEngine(new Workflow(), List.of(), List.of(), List.of(), runtimes, nodes, saver);
        User user = new User();
        user.setId(1L);
        var state = new WfState(user, List.of(), "success-runtime", null, null, null, null);
        var response = new org.ruoyi.workflow.dto.workflow.WfRuntimeResp();
        response.setId(3L);
        var saved = new WorkflowRuntime();
        saved.setOutput("{}");
        when(runtimes.updateOutput(3L, state)).thenReturn(saved);
        var emitter = mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "app", plan);
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "wfState", state);
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "wfRuntimeResp", response);
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "user", user);
        engine.setSseEmitter(emitter);
        try (var messages = mockStatic(org.ruoyi.workflow.util.WorkflowMessageUtil.class)) {
            messages.when(() -> org.ruoyi.workflow.util.WorkflowMessageUtil.sendComplete(1L, emitter, "{}"))
                .thenThrow(new IllegalStateException("notification unavailable"));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(engine, "exe", "success-runtime", true, lease);
        }
        org.junit.jupiter.api.Assertions.assertEquals(3, state.getProcessStatus());
        verify(runtimes).updateOutput(3L, state);
        verifyNoMoreInteractions(runtimes);
    }

    @Test
    void failurePersistsBeforeTemplateOrMessageOrConnectionFailure() {
        WorkflowRuntimeService runtimes = mock(WorkflowRuntimeService.class);
        WorkflowEngine engine = new WorkflowEngine(new Workflow(), List.of(), List.of(), List.of(),
            runtimes, mock(WorkflowRuntimeNodeService.class), mock(JdbcCheckpointSaver.class));
        User user = new User(); user.setId(1L);
        var state = new WfState(user, List.of(), "failure-runtime", null, null, null, null);
        var response = new org.ruoyi.workflow.dto.workflow.WfRuntimeResp(); response.setId(5L);
        var emitter = mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "wfRuntimeResp", response);
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "wfState", state);
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "user", user);
        engine.setSseEmitter(emitter);
        try (var messages = mockStatic(org.ruoyi.workflow.util.WorkflowMessageUtil.class)) {
            messages.when(() -> org.ruoyi.workflow.util.WorkflowMessageUtil.getNodeMessageTemplate(anyString()))
                .thenAnswer(call -> {
                    verify(runtimes).updateStatus(5L, 4, "original business failure");
                    throw new IllegalStateException("configuration unavailable");
                });
            messages.when(() -> org.ruoyi.workflow.util.WorkflowMessageUtil.saveWorkflowMessage(state, "original business failure"))
                .thenThrow(new IllegalStateException("message unavailable"));
            messages.when(() -> org.ruoyi.workflow.util.WorkflowMessageUtil.sendErrorAndComplete(1L, emitter, "original business failure"))
                .thenThrow(new IllegalStateException("connection unavailable"));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(engine, "errorWhenExe",
                new IllegalStateException("original business failure"));
        }
        verify(runtimes).updateStatus(5L, 4, "original business failure");
        verifyNoMoreInteractions(runtimes);
    }

    @Test
    void lostLeaseBeforePreparationDoesNotWriteFailure() throws Exception {
        JdbcCheckpointSaver saver = mock(JdbcCheckpointSaver.class);
        JdbcCheckpointSaver.RunLease lease = mock(JdbcCheckpointSaver.RunLease.class);
        when(saver.acquireRun("lost-runtime")).thenReturn(lease);
        doThrow(new IllegalStateException("工作流执行锁已丢失")).when(lease).requireHeld();
        WorkflowRuntimeService runtimes = mock(WorkflowRuntimeService.class);
        WorkflowRuntimeNodeService nodes = mock(WorkflowRuntimeNodeService.class);
        WorkflowEngine engine = new WorkflowEngine(new Workflow(), List.of(), List.of(), List.of(), runtimes, nodes, saver);
        WorkflowRuntime runtime = new WorkflowRuntime();
        runtime.setUuid("lost-runtime");
        runtime.setId(2L);
        engine.resume(new User(), runtime, null, null, null, null);
        verifyNoInteractions(runtimes, nodes);
        verify(lease).close();
    }
}
