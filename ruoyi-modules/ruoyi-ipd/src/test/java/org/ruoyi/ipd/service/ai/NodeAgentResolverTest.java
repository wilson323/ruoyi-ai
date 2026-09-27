package org.ruoyi.ipd.service.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.service.agent.IAgentService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R236 NodeAgentResolver 单测：命名约定 {@code IPD-<动作码>} 即映射（契约 §1 裁决 B）。
 *
 * <p>锁三条：①**精确等值**匹配——绝不前缀/模糊匹配，否则 {@code IPD-C1} 会拿到 {@code IPD-C11}
 * 的工作指令（这正是禁用 {@code queryList(AgentBo)} 的原因：它对 agentName 用 like）；
 * ②未绑定/空 prompt 一律返回 null 让调用方走**显式声明**的降级路径，不抛异常；
 * ③查询本身失败（agent 表不可达）也不得把异常抛进 outbox 执行链——否则整批任务退避至 DEAD，
 * 而节点智能体只是增强件、不是完成动作的必要条件。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class NodeAgentResolverTest {

    @Mock private IAgentService agentService;
    @InjectMocks private NodeAgentResolver resolver;

    private static AgentVo agent(String name, String prompt) {
        AgentVo vo = new AgentVo();
        vo.setAgentName(name);
        vo.setSystemPrompt(prompt);
        vo.setStatus("0");
        return vo;
    }

    @Test
    void resolvesSystemPromptByNamingConvention() {
        when(agentService.queryEnabledOptions()).thenReturn(List.of(
            agent("IPD-C01", "【角色】你是 C01 节点智能体"),
            agent("IPD-C07", "【角色】你是 C07 节点智能体")));

        assertThat(resolver.systemPromptOf("C07")).isEqualTo("【角色】你是 C07 节点智能体");
    }

    /** 裁决 B 的核心风险：模糊匹配会让 C1 拿到 C11 的指令（跨节点串味，且静默无告警）。 */
    @Test
    void neverPrefixMatchesSiblingCode() {
        when(agentService.queryEnabledOptions()).thenReturn(List.of(agent("IPD-C11", "【角色】Charter 评审备料")));

        assertThat(resolver.systemPromptOf("C1")).isNull();
        assertThat(resolver.systemPromptOf("C11")).isEqualTo("【角色】Charter 评审备料");
    }

    @Test
    void unboundOrBlankPromptYieldsNull() {
        when(agentService.queryEnabledOptions()).thenReturn(List.of(
            agent("IPD-C01", "   "),
            agent("IPD-C02", null)));

        assertThat(resolver.systemPromptOf("C01")).as("空白 prompt 视为未绑定").isNull();
        assertThat(resolver.systemPromptOf("C02")).as("null prompt 视为未绑定").isNull();
        assertThat(resolver.systemPromptOf("C03")).as("目录内无该行视为未绑定").isNull();
    }

    @Test
    void emptyOptionListYieldsNull() {
        when(agentService.queryEnabledOptions()).thenReturn(List.of());

        assertThat(resolver.systemPromptOf("C01")).isNull();
    }

    /** 智能体表不可达不得炸掉 outbox 执行链：降级为确定性路径（红线 5「AI 是快车道不是唯一车道」）。 */
    @Test
    void queryFailureDegradesToNullInsteadOfPropagating() {
        when(agentService.queryEnabledOptions()).thenThrow(new IllegalStateException("agent_info 不可达"));

        assertThatCode(() -> resolver.systemPromptOf("C01")).doesNotThrowAnyException();
        assertThat(resolver.systemPromptOf("C01")).isNull();
    }

    /** 空动作码不查库：避免无意义的 N+1 关联查询开销。 */
    @Test
    void nullOrBlankActionCodeSkipsQuery() {
        assertThat(resolver.systemPromptOf(null)).isNull();
        assertThat(resolver.systemPromptOf("  ")).isNull();
        verify(agentService, never()).queryEnabledOptions();
    }
}
