package org.ruoyi.ipd.agent.service;

import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentEventType;

import java.util.Date;

/**
 * 事件行构造（运行句柄与“PENDING 直接取消”两个写入口共用，保证字段口径一致）。
 */
final class ProjectAgentRunEvents {

    private ProjectAgentRunEvents() {
    }

    /**
     * 构造事件行（操作人显式绑定为发起人，不依赖异步线程的登录上下文）。
     *
     * @param runId 运行 ID
     * @param tenantId 租户
     * @param personId 发起人
     * @param seq 序号
     * @param type 类型
     * @param payloadJson 载荷 JSON
     * @param at 时间
     * @return 事件行
     */
    static IpdAgentRunEvent of(Long runId, String tenantId, Long personId, long seq, AgentEventType type,
                               String payloadJson, Date at) {
        IpdAgentRunEvent event = IpdAgentRunEvent.builder()
            .runId(runId)
            .tenantId(tenantId)
            .seq(seq)
            .eventType(type.name())
            .payload(payloadJson)
            .delFlag("0")
            .build();
        event.setCreateTime(at);
        event.setCreateBy(personId);
        event.setUpdateBy(personId);
        return event;
    }
}
