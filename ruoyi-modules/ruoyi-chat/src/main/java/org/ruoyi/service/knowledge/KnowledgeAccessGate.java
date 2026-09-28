package org.ruoyi.service.knowledge;

/**
 * 知识库检索访问门（B0 安全前置切片）。
 * <p>
 * 所有知识库ID（kid）进入检索上下文（RAG 增强、向量查询、检索测试）之前，
 * 必须先经过本门校验，杜绝客户端任意直传 kid 越权检索他人私有知识库。
 *
 * @author ruoyi
 * @date 2026-09-28
 */
public interface KnowledgeAccessGate {

    /**
     * 校验当前会话对指定知识库的检索可见性。
     * <p>
     * 单参变体：HTTP 线程专用，内部通过 LoginHelper 读取当前会话身份（Sa-Token ThreadLocal）。
     * WebSocket 消息处理线程无该 ThreadLocal，取值恒 null 会被判「未登录」全拒，
     * 此类非 HTTP 线程必须改用 {@link #checkRetrievalAccess(Long, Long)} 显式传身份。
     * <p>
     * 拒绝语义：不可见即抛业务异常（非静默忽略、非返回 null）。
     */
    void checkRetrievalAccess(Long kid);

    /**
     * 显式身份变体：供非 HTTP 线程（如 WebSocket 消息线程）传入握手期已校验的 userId。
     * <p>
     * 调用方负责该 userId 已经验证（如 mp-chat ws 握手由 MpChatHandshakeInterceptor
     * 验 token 后写入 session attributes，消息线程从 attributes 取出传入），
     * 本变体不在内部读取会话上下文。
     * <p>
     * 拒绝语义与单参变体一致：不可见即抛业务异常；userId 为 null 亦拒绝。
     */
    void checkRetrievalAccess(Long kid, Long userId);
}
