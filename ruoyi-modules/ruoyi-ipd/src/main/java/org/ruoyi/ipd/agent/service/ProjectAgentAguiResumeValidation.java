package org.ruoyi.ipd.agent.service;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 校验浏览器响应与服务端持久化中断的关联；不授予业务权限，也不修改检查点。 */
public final class ProjectAgentAguiResumeValidation {
    private ProjectAgentAguiResumeValidation() {}

    /** binding 必须来自锁定后的服务端运行与检查点，不能由请求体构造。 */
    public record Binding(String threadId, String runId, String ownerPersonId,
                          long epoch, long checkpointVersion,
                          Map<String, AguiEvent.Interrupt> pending) {
        public Binding {
            Objects.requireNonNull(threadId);
            Objects.requireNonNull(runId);
            Objects.requireNonNull(ownerPersonId);
            pending = Map.copyOf(pending);
        }
    }

    /** 调用前仍须重新验证 Person 会话、项目权限与业务审批，调用后须 CAS 消费检查点。 */
    public static List<AguiResume> validate(RunAgentInput input, Binding binding,
                                          String trustedPersonId, long expectedEpoch,
                                          long expectedCheckpointVersion, Instant now) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(binding);
        Objects.requireNonNull(now);
        if (!binding.ownerPersonId().equals(trustedPersonId)
                || !binding.threadId().equals(input.getThreadId())
                || !binding.runId().equals(input.getRunId())
                || binding.epoch() != expectedEpoch
                || binding.checkpointVersion() != expectedCheckpointVersion) {
            throw new IllegalArgumentException("恢复响应与当前运行检查点不匹配");
        }
        if (binding.pending().isEmpty() || input.getResume().isEmpty()) {
            throw new IllegalArgumentException("当前运行没有可恢复的中断响应");
        }
        var answered = new HashSet<String>();
        for (AguiResume response : input.getResume()) {
            if (response == null || !answered.add(response.getInterruptId())) {
                throw new IllegalArgumentException("中断响应重复或为空");
            }
            AguiEvent.Interrupt interrupt = binding.pending().get(response.getInterruptId());
            if (interrupt == null || !interrupt.id().equals(response.getInterruptId())) {
                throw new IllegalArgumentException("未知中断响应");
            }
            if (!response.isResolved() && !response.isCancelled()) {
                throw new IllegalArgumentException("中断响应状态无效");
            }
            if (response.isCancelled() && response.getPayload() != null) {
                throw new IllegalArgumentException("取消响应不得携带结果");
            }
            if (interrupt.expiresAt() != null
                    && !Instant.parse(interrupt.expiresAt()).isAfter(now)) {
                throw new IllegalArgumentException("中断已过期");
            }
        }
        if (!answered.equals(binding.pending().keySet())) {
            throw new IllegalArgumentException("须完整回答当前检查点的所有中断");
        }
        return List.copyOf(input.getResume());
    }
}
