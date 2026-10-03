package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.service.*;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.security.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 正式接线候选的身份与权限验证；不发送模型请求、不写业务数据。 */
class ProjectAgentAguiControllerTest {
    private final IpdPermission permission = mock(IpdPermission.class);
    private final ProjectAgentAguiStream stream = mock(ProjectAgentAguiStream.class);
    private final ProjectAgentRunService runs = mock(ProjectAgentRunService.class);
    private final ProjectAgentController controller = new ProjectAgentController(
        mock(ProjectAgentCapabilityService.class), runs, mock(AiFeedbackService.class), permission, stream);

    @Test void resumeUsesOriginalRunAndTrustedPersonWithoutCreatingOrCancelling() throws Exception {
        var actor = new IpdActor(9001L, "alice", "MARKET_PM", 100L);
        when(permission.requireInternal()).thenReturn(actor);
        var input = io.agentscope.core.agui.model.RunAgentInput.builder().threadId("42").runId("42").build();
        controller.resume("42", new org.ruoyi.ipd.agent.dto.AgentRunResumeReq(9L, input));
        verify(runs).resume(actor, 42L, 9L, input);
        verifyNoMoreInteractions(runs);
        verifyNoInteractions(stream);
        var method = ProjectAgentController.class.getMethod("resume", String.class, org.ruoyi.ipd.agent.dto.AgentRunResumeReq.class);
        assertEquals(IpdAuthSession.LOGIN_TYPE, method.getAnnotation(SaCheckPermission.class).type());
        assertArrayEquals(new String[]{"/agent-runs/{runId}/resume"},
            method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value());
    }

    @Test void deniedPersonCannotResume() {
        when(permission.requireInternal()).thenThrow(new IpdPermissionException(403, ApiV1ErrorCode.FORBIDDEN));
        assertThrows(IpdPermissionException.class, () -> controller.resume("42", null));
        verifyNoInteractions(stream, runs);
    }

    @Test void streamUsesTrustedPersonAndStringIdWithoutCreatingOrCancellingRun() {
        var actor = new IpdActor(9001L, "alice", "MARKET_PM", 100L);
        var emitter = new SseEmitter();
        when(permission.requireInternal()).thenReturn(actor);
        when(stream.open(actor, 42L, 9L, "8")).thenReturn(emitter);
        assertSame(emitter, controller.streamEvents("42", 9L, "8"));
        verify(stream).open(actor, 42L, 9L, "8");
        verifyNoInteractions(runs);
    }

    @Test void deniedPersonCannotOpenAnyStream() {
        when(permission.requireInternal()).thenThrow(new IpdPermissionException(403, ApiV1ErrorCode.FORBIDDEN));
        assertThrows(IpdPermissionException.class, () -> controller.streamEvents("42", 0L, null));
        verifyNoInteractions(stream, runs);
    }

    @Test void invalidRunIdCannotTouchStream() {
        when(permission.requireInternal()).thenReturn(new IpdActor(9001L, "alice", "MARKET_PM", 100L));
        assertThrows(RuntimeException.class, () -> controller.streamEvents("bad-id", 0L, null));
        verifyNoInteractions(stream, runs);
    }

    @Test void routePreservesOriginalIpdPermissionAndSseContract() throws Exception {
        var method = ProjectAgentController.class.getMethod("streamEvents", String.class, Long.class, String.class);
        var permission = method.getAnnotation(SaCheckPermission.class);
        assertEquals(IpdAuthSession.LOGIN_TYPE, permission.type());
        assertArrayEquals(new String[]{IpdPermissionCode.OPERATION_AI_COPILOT}, permission.value());
        var route = method.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[]{"/agent-runs/{runId}/events/stream"}, route.value());
        assertArrayEquals(new String[]{"text/event-stream"}, route.produces());
    }
}
