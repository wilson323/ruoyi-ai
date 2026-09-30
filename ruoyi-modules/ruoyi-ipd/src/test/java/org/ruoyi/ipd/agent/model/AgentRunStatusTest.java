package org.ruoyi.ipd.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行状态机纯函数测试：终态不可迁移、取消两段式、恰一终态事件类型互斥。
 */
@Tag("dev")
class AgentRunStatusTest {

    @Test
    @DisplayName("终态集合恰为 SUCCEEDED/FAILED/CANCELLED 且不可再迁移")
    void terminalStatesAreAbsorbing() {
        assertThat(AgentRunStatus.TERMINAL)
            .containsExactlyInAnyOrder(AgentRunStatus.SUCCEEDED, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED);
        for (AgentRunStatus terminal : AgentRunStatus.TERMINAL) {
            for (AgentRunStatus target : AgentRunStatus.values()) {
                assertThat(terminal.canTransitTo(target)).as(terminal + "→" + target).isFalse();
            }
        }
    }

    @Test
    @DisplayName("运行中不能直接 CANCELLED，必须先 CANCEL_REQUESTED")
    void cancellationIsTwoPhaseWhileRunning() {
        assertThat(AgentRunStatus.RUNNING.canTransitTo(AgentRunStatus.CANCELLED)).isFalse();
        assertThat(AgentRunStatus.RUNNING.canTransitTo(AgentRunStatus.CANCEL_REQUESTED)).isTrue();
        assertThat(AgentRunStatus.CANCEL_REQUESTED.canTransitTo(AgentRunStatus.CANCELLED)).isTrue();
        assertThat(AgentRunStatus.CANCEL_REQUESTED.canTransitTo(AgentRunStatus.SUCCEEDED))
            .as("取消请求后迟到的完成不得落为 SUCCEEDED").isFalse();
        assertThat(AgentRunStatus.PENDING.canTransitTo(AgentRunStatus.CANCELLED))
            .as("未启动的运行可直接取消").isTrue();
    }

    @Test
    @DisplayName("sourcesOf 与 canTransitTo 一致")
    void sourcesMatchTransitionTable() {
        assertThat(AgentRunStatus.sourcesOf(AgentRunStatus.SUCCEEDED)).containsExactly(AgentRunStatus.RUNNING);
        assertThat(AgentRunStatus.sourcesOf(AgentRunStatus.CANCELLED))
            .containsExactlyInAnyOrder(AgentRunStatus.PENDING, AgentRunStatus.CANCEL_REQUESTED);
        assertThat(AgentRunStatus.sourcesOf(AgentRunStatus.PENDING)).isEmpty();
        assertThat(AgentRunStatus.sourcesOf(AgentRunStatus.RUNNING))
            .isEqualTo(EnumSet.of(AgentRunStatus.PENDING, AgentRunStatus.WAITING_APPROVAL));
    }

    @Test
    @DisplayName("终态事件类型恰为 RUN_FINISHED 与 ERROR")
    void terminalEventTypes() {
        for (AgentEventType type : AgentEventType.values()) {
            boolean expected = type == AgentEventType.RUN_FINISHED || type == AgentEventType.ERROR;
            assertThat(type.isTerminal()).as(type.name()).isEqualTo(expected);
        }
    }
}
