package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
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
    @Mock private AiAgentTaskMapper taskMapper;
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
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService, taskMapper);
        AiAgentTask t = AiAgentTask.builder().id(2L).actionCode("C08").stageActionId(9003L).build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("对话填表");
        verify(stageActionService, never()).transit(anyLong(), any(), any(), any());
    }

    /** M1 链路闭环：PASSIVE 行（人点了执行，triggeredBy 非空）自身无 payload 时回捞同动作最近 CHAT 行载荷 */
    @Test
    void deepRecoversChatPayloadFromLinkedChatTask() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService, taskMapper);
        when(taskMapper.selectOne(any())).thenReturn(AiAgentTask.builder().id(9L)
            .actionCode("C08").stageActionId(9003L).triggerType("CHAT")
            .fillPayload("{\"scene\":\"stage-action-fields\",\"fields\":{\"baselineSales\":\"10000\"}}")
            .build());
        org.ruoyi.system.domain.vo.SysOssVo vo = new org.ruoyi.system.domain.vo.SysOssVo();
        vo.setOssId(8801L);
        when(ossService.upload(any(org.springframework.web.multipart.MultipartFile.class))).thenReturn(vo);
        AiAgentTask t = AiAgentTask.builder().id(2L).projectId(100L).actionCode("C08").stageActionId(9003L)
            .triggerType("PASSIVE").triggeredBy(77L).build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isTrue();
        verify(stageActionService).addDeliverable(eq(9003L), any(String.class), eq(8801L), eq("0"));
        verify(stageActionService).transit(eq(9003L), eq("DONE"), any(String.class), eq("0"));
    }

    /** 复审问题2：SCHEDULE 主动扫描行不得自动采用未经人确认的对话建议载荷（fail 引导，连查都不查） */
    @Test
    void deepScheduleDoesNotRecoverUnconfirmedChatPayload() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService, taskMapper);
        AiAgentTask t = AiAgentTask.builder().id(3L).projectId(100L).actionCode("C08").stageActionId(9003L)
            .triggerType("SCHEDULE").build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("对话填表");
        verify(taskMapper, never()).selectOne(any());
        verify(stageActionService, never()).recordFields(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 复审问题1：PASSIVE 但无自然人触发者（triggeredBy=null，非端点来源）同样禁止消费回捞载荷 */
    @Test
    void deepPassiveWithoutHumanTriggerDoesNotRecover() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService, taskMapper);
        AiAgentTask t = AiAgentTask.builder().id(4L).projectId(100L).actionCode("C08").stageActionId(9003L)
            .triggerType("PASSIVE").build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isFalse();
        verify(taskMapper, never()).selectOne(any());
    }

    /** 复审问题6：合法 JSON 但 fields 缺失/空对象 → 副作用前 fail，不得空载荷把动作转 DONE */
    @Test
    void deepEmptyFieldsFailsBeforeSideEffects() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService, taskMapper);
        AiAgentTask t = AiAgentTask.builder().id(5L).actionCode("C08").stageActionId(9003L)
            .fillPayload("{\"scene\":\"stage-action-fields\",\"fields\":{}}").build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("对话填表");
        verify(stageActionService, never()).recordFields(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(ossService, never()).upload(any(org.springframework.web.multipart.MultipartFile.class));
    }

    /** N2：非法 JSON 在副作用前就 fail 引导，不得先落完成日 */
    @Test
    void deepInvalidJsonFailsBeforeSideEffects() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService, taskMapper);
        AiAgentTask t = AiAgentTask.builder().id(2L).actionCode("C08").stageActionId(9003L)
            .fillPayload("not-a-json").build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("对话填表");
        verify(stageActionService, never()).recordFields(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    /** M3 红线：终态动作（DONE）重复触发必须 no-op，不得改写历史完成日 */
    @Test
    void deepSkipsTerminalAction() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService, taskMapper);
        StageAction done = new StageAction();
        done.setStatus("DONE");
        when(stageActionService.getById(9003L)).thenReturn(done);
        AiAgentTask t = AiAgentTask.builder().id(2L).actionCode("C08").stageActionId(9003L)
            .fillPayload("{\"fields\":{\"a\":\"1\"}}").build();

        AiExecResult r = deep.execute(t, CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.summary()).contains("no-op");
        verify(stageActionService, never()).recordFields(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(ossService, never()).upload(any(org.springframework.web.multipart.MultipartFile.class));
    }

    /** M3 红线：P08 重复触发不得把 DONE 动作的完成日改成当天 */
    @Test
    void lightSkipsTerminalAction() {
        StageAction done = new StageAction();
        done.setStatus("DONE");
        when(stageActionService.getById(9002L)).thenReturn(done);
        AiAgentTask t = AiAgentTask.builder().id(1L).actionCode("P08").stageActionId(9002L).build();

        AiExecResult r = light.execute(t, CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.summary()).contains("no-op");
        verify(stageActionService, never()).recordFields(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(stageActionService, never()).transit(anyLong(), any(), any(), any());
    }

    @Test
    void deepWithPayloadUploadsDeliverableThenTransitsDone() {
        DeepDirectExecutor deep = new DeepDirectExecutor(stageActionService, ossService, taskMapper);
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
