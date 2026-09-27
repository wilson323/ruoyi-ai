package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.AiAgentTaskQueryService;
import org.ruoyi.ipd.vo.AiAgentTaskView;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R232 P2-04：{@code GET /api/v1/ai-agent-tasks/{taskId}} + {@code GET /api/v1/ai-agent-tasks?projectId=}
 * 只读查询端点单测（最小面双端点）。{@code @Tag("dev")} 必须，否则 Surefire 静默跳过（假绿陷阱）。
 *
 * <p>覆盖 4 个维度：
 * <ol>
 *   <li>单查正常：taskId 透传 service → 包络 code=0 + data 完整；</li>
 *   <li>列表正常：projectId 透传 → 空列表不是 null/404；</li>
 *   <li>鉴权失败：requireInternal 抛 IpdPermissionException → 不触达 service（鉴权前置）；</li>
 *   <li>资源不存在：service 抛 50001（IpdResources 收口）→ 透传不吞。</li>
 * </ol>
 *
 * <p>另附 prompt 防泄漏哨兵：{@link AiAgentTaskView} 组件面不得出现
 * fillPayload / inputDigest / prompt / dedupKey（状态呈现到 result_summary 粒度）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AiAgentTaskControllerQueryTest {

    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private AiAgentTaskQueryService aiAgentTaskQueryService;

    @InjectMocks
    private AiAgentTaskController controller;

    private static AiAgentTaskView sampleView() {
        return new AiAgentTaskView(2104L, 200L, "C11", 300L, "PASSIVE", "HUMAN_GATE",
            "SUCCEEDED", 1, "G1 备料完成，草稿已生成（aiDocId=9001）", 9001L, null, 9001L, null, null);
    }

    @Test
    @DisplayName("P2-04 #1：get(taskId) 透传 service，包络 code=0 + data 完整")
    void get_passesTaskId_andReturnsEnvelope() {
        IpdActor actor = new IpdActor(9001L, "alice", "MARKET_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(aiAgentTaskQueryService.getByTaskId(2104L)).thenReturn(sampleView());

        ApiV1Response<AiAgentTaskView> resp = controller.get(2104L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData().id()).isEqualTo(2104L);
        assertThat(resp.getData().status()).isEqualTo("SUCCEEDED");
        assertThat(resp.getData().resultSummary()).contains("G1 备料完成");
        assertThat(resp.getData().aiDocId()).isEqualTo(9001L);
        verify(aiAgentTaskQueryService).getByTaskId(2104L);
    }

    @Test
    @DisplayName("P2-04 #2：listByProject(projectId=200) 透传；空项目返回空列表不是 404")
    void listByProject_passesProjectId_emptyListIsOk() {
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(9001L, "bob", "RD_PM", 100L));
        when(aiAgentTaskQueryService.listByProject(200L)).thenReturn(List.of());

        ApiV1Response<List<AiAgentTaskView>> resp = controller.listByProject(200L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isEmpty();
        verify(aiAgentTaskQueryService).listByProject(200L);
    }

    @Test
    @DisplayName("P2-04 #3：鉴权失败（requireInternal 抛 403）→ 端点不调 service")
    void authFailure_neverTouchesService() {
        when(ipdPermission.requireInternal())
            .thenThrow(new IpdPermissionException(403, org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> controller.get(2104L))
            .isInstanceOf(IpdPermissionException.class);
        assertThatThrownBy(() -> controller.listByProject(200L))
            .isInstanceOf(IpdPermissionException.class);
        verify(aiAgentTaskQueryService, never()).getByTaskId(2104L);
        verify(aiAgentTaskQueryService, never()).listByProject(200L);
    }

    @Test
    @DisplayName("P2-04 #4：任务不存在（service 抛 50001 NOT_FOUND）→ 透传不吞")
    void notFound_propagates() {
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(9001L, "carol", "GROUP_LEADER", 100L));
        when(aiAgentTaskQueryService.getByTaskId(4044L))
            .thenThrow(new IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.NOT_FOUND,
                "AI 执行任务不存在: 4044"));

        assertThatThrownBy(() -> controller.get(4044L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("P2-04 哨兵：AiAgentTaskView 组件面零 prompt/fillPayload/inputDigest/dedupKey 泄漏")
    void viewNeverExposesPromptFields() {
        List<String> componentNames = Arrays.stream(AiAgentTaskView.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
        assertThat(componentNames)
            .doesNotContain("prompt", "fillPayload", "inputDigest", "dedupKey", "input_digest");
        // 状态呈现到 result_summary 粒度：必须有 resultSummary/status/aiDocId（直达审批卡）
        assertThat(componentNames)
            .contains("status", "resultSummary", "aiDocId", "errorMsg");
    }
}
