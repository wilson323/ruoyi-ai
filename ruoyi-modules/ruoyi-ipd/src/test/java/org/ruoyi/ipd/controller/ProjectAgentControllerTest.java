package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.dto.AiFeedbackReq;
import org.ruoyi.ipd.agent.service.AiFeedbackService;
import org.ruoyi.ipd.agent.service.ProjectAgentCapabilityService;
import org.ruoyi.ipd.agent.service.ProjectAgentRunService;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目智能体 Controller 接线单测（合同 #1–#6）。{@code @Tag("dev")} 必须，否则 Surefire 静默跳过。
 *
 * <p>只验证：鉴权前置、ID 字符串解析、包络 code=0、开关关闭文案透传、不吞业务异常。
 * 业务规则已由 service 层 49 测覆盖，此处不重测。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectAgentControllerTest {

    @Mock
    private ProjectAgentCapabilityService capabilityService;
    @Mock
    private ProjectAgentRunService runService;
    @Mock
    private AiFeedbackService feedbackService;
    @Mock
    private IpdPermission ipdPermission;

    @InjectMocks
    private ProjectAgentController controller;

    private static final IpdActor ACTOR = new IpdActor(9001L, "alice", "MARKET_PM", 100L);

    @Test
    @DisplayName("#1 capabilities：字符串 projectId 透传，包络 code=0")
    void capabilities_ok() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        ProjectAgentViews.Capabilities caps =
            new ProjectAgentViews.Capabilities(List.of(), List.of());
        when(capabilityService.capabilities(ACTOR, 200L)).thenReturn(caps);

        ApiV1Response<ProjectAgentViews.Capabilities> resp = controller.capabilities("200");

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isSameAs(caps);
        verify(capabilityService).capabilities(ACTOR, 200L);
    }

    @Test
    @DisplayName("#1 开关关闭：service 返回 available=false 文案含开关键，Controller 原样包络")
    void capabilities_disabledPack_passesThrough() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        ProjectAgentViews.Pack pack = new ProjectAgentViews.Pack(
            "market-research", "v1", "市场调研", "", List.of(), List.of(),
            false, ProjectAgentConstants.REASON_DISABLED, List.of(), List.of());
        when(capabilityService.capabilities(ACTOR, 200L))
            .thenReturn(new ProjectAgentViews.Capabilities(List.of(pack), List.of()));

        ApiV1Response<ProjectAgentViews.Capabilities> resp = controller.capabilities("200");

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData().packs().get(0).available()).isFalse();
        assertThat(resp.getData().packs().get(0).unavailableReason())
            .contains("ipd.project-agent.enabled=false");
    }

    @Test
    @DisplayName("#2 create：透传 body；开关关闭 STATE_CONFLICT 含开关键")
    void create_disabled_propagatesConflict() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        AgentRunCreateReq req = new AgentRunCreateReq(
            "market-research", "v1", "7001", List.of("competitor-analysis-ipd"),
            List.of("project_knowledge_search"), "C02", "hello", "key-1");
        when(runService.create(eq(ACTOR), eq(200L), eq(req)))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                ProjectAgentConstants.REASON_DISABLED));

        assertThatThrownBy(() -> controller.create("200", req))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("ipd.project-agent.enabled=false");
    }

    @Test
    @DisplayName("#2 create 成功：返回 runId 字符串 + status")
    void create_ok() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        AgentRunCreateReq req = new AgentRunCreateReq(
            "market-research", "v1", "7001", List.of(), List.of(), null, "msg", "k");
        when(runService.create(ACTOR, 200L, req))
            .thenReturn(new ProjectAgentViews.RunStatus("9001", "PENDING"));

        ApiV1Response<ProjectAgentViews.RunStatus> resp = controller.create("200", req);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData().runId()).isEqualTo("9001");
        assertThat(resp.getData().status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("#3/#4/#5 get/events/cancel 透传 runId")
    void runLifecycle_passesRunId() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(runService.get(ACTOR, 77L)).thenReturn(
            new ProjectAgentViews.Run("77", "200", "ipd_project_agent", "RUNNING", null,
                null, null, "2026-09-29T00:00:00Z", null));
        when(runService.events(ACTOR, 77L, 3L))
            .thenReturn(new ProjectAgentViews.Events(List.of(), 3L, false));
        when(runService.cancel(ACTOR, 77L))
            .thenReturn(new ProjectAgentViews.RunStatus("77", "CANCEL_REQUESTED"));

        assertThat(controller.get("77").getData().runId()).isEqualTo("77");
        assertThat(controller.events("77", 3L).getData().nextSeq()).isEqualTo(3L);
        assertThat(controller.cancel("77").getData().status()).isEqualTo("CANCEL_REQUESTED");
        verify(runService).events(ACTOR, 77L, 3L);
    }

    @Test
    @DisplayName("#4 afterSeq 缺省 null 透传（service 内默认 0）")
    void events_nullAfterSeq() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(runService.events(eq(ACTOR), eq(77L), isNull()))
            .thenReturn(new ProjectAgentViews.Events(List.of(), 0L, false));

        assertThat(controller.events("77", null).getData().nextSeq()).isEqualTo(0L);
    }

    @Test
    @DisplayName("#6 feedback：targetType/targetId 原样透传")
    void feedback_ok() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        AiFeedbackReq req = new AiFeedbackReq("UP", null);
        when(feedbackService.put(ACTOR, "RUN_MESSAGE", "77", req))
            .thenReturn(new ProjectAgentViews.Feedback("RUN_MESSAGE", "77", "UP", null,
                "2026-09-29T00:00:00Z"));

        ApiV1Response<ProjectAgentViews.Feedback> resp =
            controller.feedback("RUN_MESSAGE", "77", req);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData().targetType()).isEqualTo("RUN_MESSAGE");
        verify(feedbackService).put(ACTOR, "RUN_MESSAGE", "77", req);
    }

    @Test
    @DisplayName("#7 apply：runId/artifactId 透传，包络 code=0，indexStatus 原样")
    void applyArtifact_ok() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        ProjectAgentViews.ArtifactApply applied = new ProjectAgentViews.ArtifactApply(
            "77", "art-1", "91001", 1, "8001", "GENERATED", "NOT_INDEXED", "待审核");
        when(runService.applyArtifact(ACTOR, 77L, "art-1")).thenReturn(applied);

        ApiV1Response<ProjectAgentViews.ArtifactApply> resp =
            controller.applyArtifact("77", "art-1");

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getMessage()).isEqualTo("待审核");
        assertThat(resp.getData().documentStatus()).isEqualTo("GENERATED");
        assertThat(resp.getData().documentStatusLabel()).isEqualTo("待审核");
        assertThat(resp.getData().indexStatus()).isEqualTo("NOT_INDEXED");
        verify(runService).applyArtifact(ACTOR, 77L, "art-1");
    }

    @Test
    @DisplayName("鉴权失败：不触达任何 service")
    void authFailure_neverTouchesService() {
        when(ipdPermission.requireInternal())
            .thenThrow(new IpdPermissionException(403, ApiV1ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> controller.capabilities("200"))
            .isInstanceOf(IpdPermissionException.class);
        verify(capabilityService, never()).capabilities(any(), anyLong());
        verify(runService, never()).create(any(), anyLong(), any());
        verify(feedbackService, never()).put(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("非法 projectId：PARAM_INVALID，不触达 service")
    void badProjectId_paramInvalid() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);

        assertThatThrownBy(() -> controller.capabilities("not-a-number"))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(((IpdBusinessException) ex).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verify(capabilityService, never()).capabilities(any(), anyLong());
    }
}
