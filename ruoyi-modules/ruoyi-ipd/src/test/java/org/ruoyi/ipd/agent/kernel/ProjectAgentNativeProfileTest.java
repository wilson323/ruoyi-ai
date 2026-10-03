package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.memory.MemoryConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class ProjectAgentNativeProfileTest {
    @Test void officialMemoryHooksRemainEnabledAndRespectBusinessAuthority() {
        MemoryConfig memory = ProjectAgentNativeProfile.memory();
        assertThat(memory.flushTrigger().mode()).isEqualTo(MemoryConfig.FlushMode.ALWAYS);
        assertThat(memory.flushPrompt()).contains("IPD services own business facts", "Never store secrets");
        assertThat(String.format(memory.consolidationPrompt(), 4000, 16000))
            .contains("4000 tokens", "16000 characters", "owner skill approval");
    }
}
