package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AiExecutionTriggerTest {

    @Mock private AiAgentTaskMapper taskMapper;
    @Mock private AiExecutionEngine engine;
    @InjectMocks private AiExecutionTrigger trigger;

    @BeforeAll
    static void initTable() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
            AiAgentTask.class);
    }

    @Test
    void passiveTaskInsertedWithMatrixSnapshotAndSystemCreateBy() {
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(taskMapper.insert(any(AiAgentTask.class))).thenReturn(1);

        trigger.triggerPassive(100L, "C01", 9001L, 555L);

        ArgumentCaptor<AiAgentTask> cap = ArgumentCaptor.forClass(AiAgentTask.class);
        verify(taskMapper).insert(cap.capture());
        AiAgentTask t = cap.getValue();
        assertThat(t.getStatus()).isEqualTo(AiAgentTask.STATUS_PENDING);
        assertThat(t.getTriggerType()).isEqualTo(AiAgentTask.TRIGGER_PASSIVE);
        assertThat(t.getExecMode()).isEqualTo("AI_GENERATE"); // C01 附录A档位快照
        assertThat(t.getDedupKey()).isEqualTo("C01:9001:PASSIVE");
        assertThat(t.getTriggeredBy()).isEqualTo(555L);
        assertThat(t.getCreateBy()).isEqualTo(0L); // Global Constraint 4
        assertThat(t.getAttempt()).isZero();
    }

    @Test
    void dedupGuardReturnsExistingRowWithoutInsert() {
        AiAgentTask existing = AiAgentTask.builder().id(77L).status(AiAgentTask.STATUS_RUNNING)
            .dedupKey("P08:9002:PASSIVE").build();
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        AiAgentTask got = trigger.triggerPassive(100L, "P08", 9002L, 555L);

        assertThat(got.getId()).isEqualTo(77L);
        verify(taskMapper, never()).insert(any(AiAgentTask.class));
    }

    @Test
    void eventTriggerHasNullTriggeredByAndEventDedupKey() {
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(taskMapper.insert(any(AiAgentTask.class))).thenReturn(1);

        trigger.triggerEvent(100L, "C01", 9001L);

        ArgumentCaptor<AiAgentTask> cap = ArgumentCaptor.forClass(AiAgentTask.class);
        verify(taskMapper).insert(cap.capture());
        assertThat(cap.getValue().getTriggerType()).isEqualTo(AiAgentTask.TRIGGER_EVENT);
        assertThat(cap.getValue().getDedupKey()).isEqualTo("C01:9001:EVENT");
        assertThat(cap.getValue().getTriggeredBy()).isNull();
    }

    @Test
    void chatTriggerCarriesFillPayloadAndDigest() {
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(taskMapper.insert(any(AiAgentTask.class))).thenReturn(1);

        trigger.triggerChat(100L, "C08", 9003L, 555L, "{\"scene\":\"stage-action-fields\"}", "digest-abc");

        ArgumentCaptor<AiAgentTask> cap = ArgumentCaptor.forClass(AiAgentTask.class);
        verify(taskMapper).insert(cap.capture());
        assertThat(cap.getValue().getTriggerType()).isEqualTo(AiAgentTask.TRIGGER_CHAT);
        assertThat(cap.getValue().getFillPayload()).contains("stage-action-fields");
        assertThat(cap.getValue().getInputDigest()).isEqualTo("digest-abc");
    }
}
