package org.ruoyi.observability;

import io.agentscope.core.hook.Hook;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 原生 AgentScope Hook 注册；唯一原生事件观测入口。 */
@Configuration
public class AgentScopeObservabilityConfig {
    @Bean public Hook agentScopeAuditHook() { return new AgentScopeAuditHook("agent"); }
}
