package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.Objects;

/** 官方完整协议元数据与服务端运行身份同时保留；协议 state 不导入 AgentState。 */
final class ProjectAgentAguiRuntimeContext extends AguiAgentAdapter {
    private ProjectAgentAguiRuntimeContext(Agent agent) {
        super(agent, AguiAdapterConfig.builder().build());
    }

    static RuntimeContext prepare(Agent agent, RunAgentInput serverBoundInput, RuntimeContext trusted) {
        Objects.requireNonNull(trusted, "Trusted run context is required");
        if (serverBoundInput == null) return trusted;
        RuntimeContext protocol = new ProjectAgentAguiRuntimeContext(agent)
            .buildRuntimeContext(serverBoundInput, trusted);
        // 官方 threadId 是协议相关 ID；原状态隔离键仍由 KernelScopeKey 唯一收口。
        return RuntimeContext.builder(protocol).userId(trusted.getUserId())
            .sessionId(trusted.getSessionId()).build();
    }
}
