package org.ruoyi.chat.kernel;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 四维隔离(project × user × agent × session) → AgentScope {@link RuntimeContext} 的唯一收口。
 *
 * <p>键式(方案 §2.1;生产实际走 Redis 而非 MySQL,见下方 store 说明):
 * <ul>
 *   <li>复合 userId = {@code p{projectId}:u{userId}}(项目维度为原生缺失维度,经 userId 前缀补齐)
 *   <li>复合 sessionId = {@code a{agentId}:s{sessionId}}(AgentStateStore 只有 (userId, sessionId)
 *       二元寻址、无 agent 列,agent 维度折入 session 段;HarnessAgent.name 目录分桶为其文件侧对应)
 *   <li>{@link Scope#slotId()} = {@code 复合userId + ":" + 复合sessionId}
 *       = {@code p{projectId}:u{userId}:a{agentId}:s{sessionId}},四维全收口于一个字符串
 * </ul>
 *
 * <p><b>store 事实(2026-10-02 字节码实证修正)</b>:生产用
 * {@code AgentScopeRedisStateStores.create(RedissonClient, keyPrefix)} →
 * 官方 {@code io.agentscope.extensions.redis.state.RedisAgentStateStore},<b>不是</b>
 * {@code MysqlAgentStateStore}(后者本仓仅测试代码 import)。
 * 官方 {@code RedisAgentStateStore} 的 Redis 键结构经 {@code javap -c} 实证为:
 * <pre>
 *   private static String slotId(String user, String session) {   // 配方 "\u0001/\u0001"
 *       return normalizeUser(user) + "/" + session;
 *   }
 *   private String getStateKey(String slot, String stateKey) {    // 配方 "\u0001\u0001:\u0001"
 *       return keyPrefix + slot + ":" + stateKey;
 *   }
 *   // = {keyPrefix}{normalizeUser(user)}/{sessionId}:{stateKey}
 * </pre>
 * 代入本仓复合键后形如
 * {@code agentscope:session:pP1:u900103/aemp-a1:sS1:agent_state}:
 * <b>userId 是 {@code /} 之前的独立一段</b>(空 userId 归一化为
 * {@code __anon__}),并非「由 sessionId 捎带」。故四维里
 * <b>project 与 user 落在 user 段、agent 与 session 落在 sessionId 段,四维全部直接进键</b>。
 *
 * <p><b>历史错误(2026-10-02 前注释,已由字节码推翻)</b>:曾记载键为
 * {@code {prefix}{sessionId}:{stateKey}}、userId「不落键」,并据此推出
 * 「绕过 {@link #of} 会丢 user/project/agent 三维」。该结论不成立——
 * 绕过 {@link #of} 只会丢 <b>project</b>(在 user 段内)与 <b>agent</b>(在 session 段内);
 * <b>user 维由 {@code user} 形参独立承载,不会丢</b>。修正后本仓隔离强度<b>强于</b>原注释所述。
 * 但「业务代码禁止手工拼键、一律经 {@link #of}」的纪律<b>依然必要</b>:
 * 手工拼接仍可污染 user 段与 session 段,绕过 {@code validateSegment} 的注入拒绝。
 *
 * <p>fail-closed 校验:任何原始段含 {@code ':'} 或 {@code ".."} 直接拒绝(防复合 key 注入伪造别桶地址);
 * userId 允许为空 → 降级 SESSION 语义(与 IsolationScope.USER 空 userId 降级 SESSION 对齐,
 * user 段坍缩为 {@code __anon__} 命名空间,会话维度仍硬隔离)。
 *
 * <p>业务代码禁止手工拼键,一律经 {@link #of}。
 */
public final class KernelScopeKey {

    /**
     * 空 userId 降级命名空间。
     *
     * <p>与官方 {@code RedisAgentStateStore.ANON_USER = "__anon__"}
     * （{@code RedisAgentStateStore.java:439-442}）取值一致,属**有意对齐**:
     * 官方空 userId 时也降级到同一桶,本仓保持相同语义以免两套行为分叉。
     * 注意:这意味着空 userId 的调用方在本仓与官方**共享** {@code __anon__} 桶——
     * 多租户场景下调用方必须保证 userId 非空,不能依赖该降级做隔离。
     */
    public static final String ANONYMOUS_USER_SEGMENT = "__anon__";

    private KernelScopeKey() {}

    /** 收口产物:传给 RuntimeContext 的 (复合 userId, 复合 sessionId) 二元组。 */
    public record Scope(String userId, String sessionId) {

        /** 直接产出带四维收口键的 {@link RuntimeContext}。 */
        public RuntimeContext toRuntimeContext() {
            return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        }

        /**
         * 四维收口 slotId(复合 userId + ":" + 复合 sessionId)。
         *
         * <p>用途:turn gate 串行化键({@code TURN_GATE.acquire(scope.slotId())})与测试/回读比对。
         * <b>不是</b>本仓任何 store 的落库键——落库键由 {@link #userId()} 与 {@link #sessionId()}
         * 两个形参分别交给官方 store 合成(见类注释的键结构实证)。
         *
         * <p><b>命名注意</b>:官方 {@code RedisAgentStateStore} 也有一个私有静态
         * {@code slotId(String user, String session)},且它<b>正是落库键的第一段拼接来源</b>;
         * 本方法是<b>同名不同物</b>(本仓用 {@code ':'} 分隔且含四维,官方用 {@code '/'} 分隔且只有两维)。
         * 读官方源码或本仓代码时务必确认是哪一个,避免语义串台。
         */
        public String slotId() {
            return userId + ":" + sessionId;
        }
    }

    /**
     * 四维 → 收口键。
     *
     * @param projectId 项目/组织维度(必填,禁含 ':' 与 "..")
     * @param userId 用户维度(可空=null/blank → 降级 SESSION;禁含 ':' 与 "..")
     * @param agentId 数字员工维度(必填,禁含 ':' 与 "..")
     * @param sessionId 会话维度(必填,禁含 ':' 与 "..")
     * @return 复合键二元组
     * @throws IllegalArgumentException 任一段非法或注入特征命中(fail-closed)
     */
    public static Scope of(String projectId, String userId, String agentId, String sessionId) {
        validateSegment("projectId", projectId, true);
        validateSegment("agentId", agentId, true);
        validateSegment("sessionId", sessionId, true);
        String userSegment = (userId == null || userId.isBlank()) ? ANONYMOUS_USER_SEGMENT : userId;
        if (!ANONYMOUS_USER_SEGMENT.equals(userSegment)) {
            validateSegment("userId", userSegment, true);
        }
        String compositeUserId = "p" + projectId + ":u" + userSegment;
        String compositeSessionId = "a" + agentId + ":s" + sessionId;
        return new Scope(compositeUserId, compositeSessionId);
    }

    private static void validateSegment(String name, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("[KernelScopeKey] " + name + " must not be blank");
            }
            return;
        }
        if (value.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "[KernelScopeKey] " + name + " must not contain ':' (composite key injection)");
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException(
                    "[KernelScopeKey] " + name + " must not contain '..' (path traversal injection)");
        }
    }
}
