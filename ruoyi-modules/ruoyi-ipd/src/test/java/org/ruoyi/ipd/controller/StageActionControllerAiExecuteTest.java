package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.AiExecutionTrigger;
import org.ruoyi.ipd.service.StageActionService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R221 Task 8：POST /stage-actions/{id}/ai-execute 端点行为锁。
 *
 * <p>复审问题5：StageActionService.getById 对不存在 id 抛 ServiceException 永不返回 null，
 * 资源不存在必须按本仓契约收口为 50001 NOT_FOUND（40401 语义是"产品已下架"，严禁复用）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("R221 ai-execute 端点（PASSIVE 触发 + NOT_FOUND 契约）")
class StageActionControllerAiExecuteTest {

    @Mock private StageActionService stageActionService;
    @Mock private IpdPermission ipdPermission;
    @Mock private AiExecutionTrigger aiExecutionTrigger;

    @InjectMocks private StageActionController controller;

    private static final IpdActor PM = new IpdActor(77L, "市场PM", "MARKET_PM", 100L);

    @Test
    @DisplayName("[正例] 动作存在 → triggerPassive 落行即返，data 含 taskId/status/actionCode")
    void aiExecuteTriggersPassiveTask() {
        StageAction a = new StageAction();
        a.setId(9003L);
        a.setProjectId(100L);
        a.setActionCode("C08");
        when(stageActionService.getById(9003L)).thenReturn(a);
        when(ipdPermission.requireActionWriter(any())).thenReturn(PM);
        when(aiExecutionTrigger.triggerPassive(eq(100L), eq("C08"), eq(9003L), eq(77L)))
            .thenReturn(AiAgentTask.builder().id(501L).projectId(100L).actionCode("C08")
                .status(AiAgentTask.STATUS_PENDING).build());

        ApiV1Response<Map<String, Object>> resp = controller.aiExecute(9003L);

        assertThat(resp.getCode()).isZero();
        assertThat(resp.getData()).containsEntry("taskId", 501L)
            .containsEntry("status", AiAgentTask.STATUS_PENDING)
            .containsEntry("actionCode", "C08");
    }

    @Test
    @DisplayName("[负例] 动作不存在（getById 抛 ServiceException）→ 50001 NOT_FOUND，不建任务")
    void aiExecuteNotFoundUsesContractErrorCode() {
        when(stageActionService.getById(9999L)).thenThrow(new ServiceException("动作实例不存在: 9999"));

        ApiV1Response<Map<String, Object>> resp = controller.aiExecute(9999L);

        assertThat(resp.getCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND.getCode());
        assertThat(resp.getMessage()).isEqualTo("动作不存在");
        verify(aiExecutionTrigger, never()).triggerPassive(any(), any(), any(), any());
    }
}
