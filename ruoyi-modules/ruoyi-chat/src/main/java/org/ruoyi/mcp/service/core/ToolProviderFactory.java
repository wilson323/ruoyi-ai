package org.ruoyi.mcp.service.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** 原生工具装配入口；调用方负责关闭返回的运行会话。 */
@Service
@RequiredArgsConstructor
public class ToolProviderFactory {
    private final BuiltinToolRegistry builtinToolRegistry;
    private final AgentScopeMcpToolProviderService agentScopeMcpToolProviderService;

    public AgentScopeMcpToolProviderService.ToolSession createAllEnabledMcpSession() {
        return agentScopeMcpToolProviderService.createAllEnabledSession();
    }

    public List<Object> getAllBuiltinToolObjects() {
        return builtinToolRegistry.getAllBuiltinToolObjects();
    }
}
