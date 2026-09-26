package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/** R221：任务行实体契约——乐观锁/逻辑删除/状态与触发常量（防漂移）。 */
@Tag("dev")
class AiAgentTaskContractTest {

    @Test
    void versionAndLogicDeleteAnnotated() throws Exception {
        Field version = AiAgentTask.class.getDeclaredField("version");
        assertThat(version.isAnnotationPresent(Version.class)).isTrue();
        Field delFlag = AiAgentTask.class.getDeclaredField("delFlag");
        assertThat(delFlag.isAnnotationPresent(TableLogic.class)).isTrue();
    }

    @Test
    void statusConstants() {
        assertThat(AiAgentTask.STATUS_PENDING).isEqualTo("PENDING");
        assertThat(AiAgentTask.STATUS_RUNNING).isEqualTo("RUNNING");
        assertThat(AiAgentTask.STATUS_SUCCEEDED).isEqualTo("SUCCEEDED");
        assertThat(AiAgentTask.STATUS_FAILED).isEqualTo("FAILED");
        assertThat(AiAgentTask.STATUS_DEAD).isEqualTo("DEAD");
    }

    @Test
    void triggerConstants() {
        assertThat(AiAgentTask.TRIGGER_PASSIVE).isEqualTo("PASSIVE");
        assertThat(AiAgentTask.TRIGGER_CHAT).isEqualTo("CHAT");
        assertThat(AiAgentTask.TRIGGER_EVENT).isEqualTo("EVENT");
        assertThat(AiAgentTask.TRIGGER_SCHEDULE).isEqualTo("SCHEDULE");
    }

    @Test
    void builderRoundTrip() {
        AiAgentTask t = AiAgentTask.builder()
            .projectId(1L).actionCode("C01").triggerType(AiAgentTask.TRIGGER_PASSIVE)
            .execMode("AI_GENERATE").status(AiAgentTask.STATUS_PENDING)
            .dedupKey("C01:100:PASSIVE").attempt(0)
            .build();
        t.setCreateBy(0L);
        assertThat(t.getDedupKey()).isEqualTo("C01:100:PASSIVE");
        assertThat(t.getCreateBy()).isEqualTo(0L);
    }
}
