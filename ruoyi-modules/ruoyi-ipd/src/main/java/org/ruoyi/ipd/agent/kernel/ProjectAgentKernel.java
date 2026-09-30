package org.ruoyi.ipd.agent.kernel;

import reactor.core.Disposable;

/**
 * 项目智能体执行内核端口。生产实现 {@link AgentScopeProjectAgentKernel} 仅在
 * {@code ipd.project-agent.enabled=true} 时装配；开关关闭时无实现 Bean，运行接口拒绝。
 */
public interface ProjectAgentKernel {

    /**
     * 异步执行一次运行，事件经 sink 回调；任何装配/执行异常都转 {@code onError}，不裸抛。
     *
     * @param spec 冻结的运行输入
     * @param sink 事件出口
     * @return 可取消句柄（dispose 即停止上游模型流）
     */
    Disposable execute(ProjectAgentRunSpec spec, ProjectAgentEventSink sink);
}
