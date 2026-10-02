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
 * <p><b>store 事实(2026-10-02 实读修正)</b>:生产用
 * {@code AgentScopeRedisStateStores.create(RedissonClient, keyPrefix)} →
 * 官方 {@code io.agentscope.extensions.redis.state.RedisAgentStateStore},<b>不是</b>
 * {@code MysqlAgentStateStore}(后者本仓仅测试代码 import;注意别与
 * {@code MysqlDistributedStore} 混淆——被 {@code @Deprecated(since="2.1", forRemoval=true)}
 * 标注的是 <b>DistributedStore 那个</b>,{@code MysqlAgentStateStore} 本身无类级弃用注解)。
 * 官方 {@code RedisAgentStateStore} 的 Redis key 结构是
 * {@code {prefix}{sessionId}:{stateKey}}——<b>其 key 本身不含 userId</b>,
 * userId 维度由调用方传入的 sessionId 参数承载。故四维隔离的落点
 * <b>取决于调用方传进去的复合 sessionId 字符串</b>,这也是 fail-closed 校验必须前移的原因:
 * 一旦某个调用点绕过 {@link #of} 直接传原始 sessionId,user/project/agent 三维会静默丢失。
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
         * <b>不是</b>任何 store 的落库键——官方 {@code RedisAgentStateStore} 的 Redis key 是
         * {@code {prefix}{sessionId}:{stateKey}},不含 userId 段;本方法不参与持久化键构成。
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
