package org.ruoyi.ipd.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.service.agent.IAgentService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R236 生命周期节点智能体解析器：按**命名约定** {@code IPD-<动作码>} 从 {@code agent_info}
 * 取该节点的工作指令（system_prompt）。约定即映射——不新建映射表、不新建 system_configs 键、
 * 不新增 IAgentService 查询方法（复用既有 {@link IAgentService#queryEnabledOptions()}）。
 *
 * <p><b>为何不走 ruoyi-chat 的智能体运行时</b>（契约 §1 裁决 A / §3 D2）：
 * {@code ChatServiceFacade#chat(ChatRequest, StreamingChatResponseHandler)} 完全不解析 agentId、
 * 无登录态时 {@code LoginHelper.getExtra} 静默返回 null 会写出 user_id=null 的脏 chat_messages；
 * 真正的 agent 路径 {@code handleAgentChat} 装配的是 supervisor 多子 Agent、无条件挂
 * {@code ExecuteSqlQueryTool}、磁盘 skills 已被禁用。走它 = 65 个业务节点绕过本模块
 * {@code AiGenerationService.generate()} 的 7 道治理（SSRF 前置 / 预算预检 / 限流 / RAG /
 * 重试 / 落库 / 审计）= AGENTS.md 禁止的双轨。故本类**只取配置**，执行统一走 generate()。
 *
 * <p><b>不自建缓存</b>：{@code SystemConfigServiceImpl} 已有 Caffeine(500/5min)+写穿透失效，
 * PERF-02 明令「配置变更立即生效，不允许 TTL 窗口」；本解析发生在 R221 outbox 任务执行时
 * （频次 = 每任务一次，远低于同任务内 LLM 调用开销），自建 ConcurrentHashMap 只会制造 stale bug。
 *
 * <p><b>已知成本</b>：{@code queryEnabledOptions()} 的 toVo 每行触发模型/MCP/知识库关联查询（N+1），
 * 且 {@code orderByDesc(updateTime, id)} 决定重名时取第一条。**禁用** {@code queryList(AgentBo)}
 * 做精确解析——它对 agentName 用 like，{@code IPD-C1} 会命中 {@code IPD-C11}。
 *
 * <p><b>已知边界</b>：该方法同时服务 {@code /agent/agent/agentOptions}（用户端聊天页下拉）。
 * 现查前端 {@code agentEnabledOptions()} 只有定义无调用点，故当前不污染 UI；若后续接上聊天页
 * 下拉，须按 {@link #NAME_PREFIX} 过滤。
 *
 * @see <a href="../../../../../../../../../docs/ipd-系统说明/R236-生命周期节点智能体接线设计-20260927.md">R236 接线设计契约</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeAgentResolver {

    /** 节点智能体命名约定前缀；种子 SQL 与本类是唯一两处持有者，由哨兵测试对账。 */
    public static final String NAME_PREFIX = "IPD-";

    private final IAgentService agentService;

    /**
     * 解析动作码对应节点智能体的工作指令。
     *
     * @param actionCode 动作码（如 {@code C07}）
     * @return system_prompt；**未绑定 / 已停用 / 指令空白 / 查询异常** 一律返回 {@code null}，
     *         由调用方降级为确定性行为并 {@code log.warn}（「AI 是快车道不是唯一车道」，绝不伪造产物）。
     *         本方法对「未绑定」不记日志——那是种子未 apply 的预期态，由调用方带 taskId 上下文记。
     */
    public String systemPromptOf(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            return null;
        }
        String expected = NAME_PREFIX + actionCode;
        List<AgentVo> enabled;
        try {
            enabled = agentService.queryEnabledOptions();
        } catch (RuntimeException ex) {
            // 基础设施故障（DB/上下文不可用）与「未绑定」必须可区分：前者记 warn 便于排障
            log.warn("R236 节点智能体查询失败（{}），降级为确定性路径: {}", expected, ex.getMessage());
            return null;
        }
        if (enabled == null || enabled.isEmpty()) {
            return null;
        }
        for (AgentVo vo : enabled) {
            if (vo != null && expected.equals(vo.getAgentName())) {
                String prompt = vo.getSystemPrompt();
                return prompt == null || prompt.isBlank() ? null : prompt;
            }
        }
        return null;
    }
}
