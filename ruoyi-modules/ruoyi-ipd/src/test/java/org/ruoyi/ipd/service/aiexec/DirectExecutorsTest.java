package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.system.service.ISysOssService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R221 Task 6：Light(P08)/Deep(C08) 直接执行器单测。 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DirectExecutorsTest {

    private static final AiExecContext CTX = new AiExecContext(
        new IpdActor(0L, "system", "SYSTEM", null),
        Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("UTC")));

    @Mock private StageActionService stageActionService;
    @Mock private ISysOssService ossService;
    @InjectMocks private LightDirectExecutor light;

    @Test
    void lightRecordsDoneAtThenTransitsDoneWithSystemOperator() {
        AiAgentTask t = AiAgentTask.builder().id(1L).projectId(100L).actionCode("P08")
            .stageActionId(9002L).execMode("AI_DIRECT").build();

        AiExecResult r = light.execute(t, CTX);

        assertThat(r.ok()).isTrue();
        verify(stageActionService).recordFields(eq(9002L), any(Date.class),
            isNull(), isNull(), isNull(), isNull(), isNull(), eq("0"));
        verify(stageActionService).transit(eq(9002L), eq("DONE"), any(String.class), eq("0"));
    }

    @Test
    void lightTransitFailurePropagates() {
        when(stageActionService.transit(anyLong(), any(), any(), any()))
            .thenThrow(new org.ruoyi.common.core.exception.ServiceException("非法目标状态"));
        AiAgentTask t = AiAgentTask.builder().id(1L).actionCode("P08").stageActionId(9002L).build();

        assertThrows(RuntimeException.class, () -> light.execute(t, CTX));
    }

    @Test
    void deepWithoutPayloadFailsFastWithGuidance() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService);
        AiAgentTask t = AiAgentTask.builder().id(2L).actionCode("C08").stageActionId(9003L).build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("对话填表");
        verify(stageActionService, never()).transit(anyLong(), any(), any(), any());
    }

    @Test
    void deepWithPayloadUploadsDeliverableThenTransitsDone() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService);
        org.ruoyi.system.domain.vo.SysOssVo vo = new org.ruoyi.system.domain.vo.SysOssVo();
        vo.setOssId(8801L);
        when(ossService.upload(any(org.springframework.web.multipart.MultipartFile.class))).thenReturn(vo);
        AiAgentTask t = AiAgentTask.builder().id(2L).actionCode("C08").stageActionId(9003L)
            .fillPayload("{\"scene\":\"stage-action-fields\",\"fields\":{\"baselineSales\":\"10000\"}}")
            .build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isTrue();
        verify(stageActionService).addDeliverable(eq(9003L), any(String.class), eq(8801L), eq("0"));
        verify(stageActionService).transit(eq(9003L), eq("DONE"), any(String.class), eq("0"));
    }
}
