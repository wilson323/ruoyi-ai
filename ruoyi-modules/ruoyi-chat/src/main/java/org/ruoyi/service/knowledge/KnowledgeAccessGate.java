package org.ruoyi.service.knowledge;

/**
 * 知识库检索访问门（B0 安全前置切片）。
 * <p>
 * 所有知识库ID（kid）进入检索上下文（RAG 增强、向量查询、检索测试）之前，
 * 必须先经过本门校验，杜绝客户端任意直传 kid 越权检索他人私有知识库。
 * <p>
 * 2026-09-28 B0 审计四口收敛轮：新增管理面判据 {@link #assertManageable(Long)}。
 * 端口自此承载两套判据，仍是 kid 归属校验的单一端口（禁止调用方逐点手写 ownership 判断）：
 * <ul>
 *   <li>读面（检索可见 / 元数据读取）：owned OR share=1——公开库对租户内可见；</li>
 *   <li>管理面（改属性 / 删除 / 注入文档）：仅 owned——share=1 公开不授予写权
 *       （share 从未构成安全边界，见最佳实践 §8.1；升密、改属性、清库均属管理动作）。</li>
 * </ul>
 *
 * @author ruoyi
 * @date 2026-09-28
 */
public interface KnowledgeAccessGate {

    /**
     * 校验当前会话对指定知识库的检索可见性（读面判据：owned OR share=1）。
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

    /**
     * 校验当前会话对指定知识库的管理权（管理面判据：仅 owned，share=1 公开不授予写权）。
     * <p>
     * 适用收敛口：库属性修改（edit）、库删除（remove）、向库注入文档（upload/reparse）
     * 等 ownership 面——公开库他人可见可检索，但不可以改配置、删库、写内容
     * （否则 edit 翻 share=1 即可击穿 {@link #checkRetrievalAccess} 的 share 半边，
     * B0 审计破坏面 C 的提权链）。
     * <p>
     * admin 豁免：实现层按仓内既有惯例（LoginHelper.isSuperAdmin，SUPER_ADMIN_ID 等值判定）
     * 对超级管理员放行，普通角色不豁免。单参变体供 HTTP 线程（内部读会话）；
     * 非 HTTP 线程必须改用 {@link #assertManageable(Long, Long)} 显式传身份。
     * <p>
     * 拒绝语义与 checkRetrievalAccess 一致：不可管理即抛业务异常（非静默忽略）；
     * kid 为 null、userId 为 null、库不存在均拒绝。
     */
    void assertManageable(Long kid);

    /**
     * 显式身份变体：供非 HTTP 线程传入已验证的 userId 做管理面判据。
     * <p>
     * 判据与单参变体完全同源（仅 owned，superadmin 豁免），不在内部读取会话上下文；
     * 拒绝语义：不可管理即抛业务异常，userId 为 null 亦拒绝。
     */
    void assertManageable(Long kid, Long userId);
}
