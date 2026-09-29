package org.ruoyi.chat.kernel;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 四维隔离(project × user × agent × session) → AgentScope {@link RuntimeContext} 的唯一收口。
 *
 * <p>键式(方案 §2.1,以实测 MysqlAgentStateStore.slotId = {@code userId + ":" + sessionId} 为准):
 * <ul>
 *   <li>复合 userId = {@code p{projectId}:u{userId}}(项目维度为原生缺失维度,经 userId 前缀补齐)
 *   <li>复合 sessionId = {@code a{agentId}:s{sessionId}}(AgentStateStore 只有 (userId, sessionId)
 *       二元寻址、无 agent 列,agent 维度折入 session 段;HarnessAgent.name 目录分桶为其文件侧对应)
 *   <li>最终落库 slotId = {@code p{projectId}:u{userId}:a{agentId}:s{sessionId}},四维全收口于一列
 * </ul>
 *
 * <p>fail-closed 校验:任何原始段含 {@code ':'} 或 {@code ".."} 直接拒绝(防复合 key 注入伪造别桶地址);
 * userId 允许为空 → 降级 SESSION 语义(与 IsolationScope.USER 空 userId 降级 SESSION 对齐,
 * user 段坍缩为 {@code __anon__} 命名空间,会话维度仍硬隔离)。
 *
 * <p>业务代码禁止手工拼键,一律经 {@link #of}。
 */
public final class KernelScopeKey {

    /** 空 userId 降级命名空间(对齐 MysqlAgentStateStore.ANON_USER 语义)。 */
    public static final String ANONYMOUS_USER_SEGMENT = "__anon__";

    private KernelScopeKey() {}

    /** 收口产物:传给 RuntimeContext 的 (复合 userId, 复合 sessionId) 二元组。 */
    public record Scope(String userId, String sessionId) {

        /** 直接产出带四维收口键的 {@link RuntimeContext}。 */
        public RuntimeContext toRuntimeContext() {
            return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        }

        /** MysqlAgentStateStore 落库 slotId(userId + ":" + sessionId),仅测试/回读 SQL 用。 */
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
