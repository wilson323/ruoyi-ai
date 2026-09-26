package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.audit.IpdEntityType;

import static org.assertj.core.api.Assertions.assertThat;

/** R221：AI 代理执行闭环的审计登记前置（AI_ROLES + 实体类型常量）。 */
@Tag("dev")
class AiExecAuditRegistryTest {

    @Test
    void aiRolesContainsAgentExec() throws Exception {
        var f = AuditEventData.class.getDeclaredField("AI_ROLES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var roles = (java.util.Set<String>) f.get(null);
        assertThat(roles).contains("agent_exec");
    }

    @Test
    void entityTypeRegistered() {
        assertThat(IpdEntityType.AI_AGENT_TASK).isEqualTo("ai_agent_task");
    }
}
