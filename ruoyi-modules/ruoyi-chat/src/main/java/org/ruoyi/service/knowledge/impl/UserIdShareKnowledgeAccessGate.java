package org.ruoyi.service.knowledge.impl;

import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.springframework.stereotype.Component;

/**
 * 知识库检索访问门 B0 默认实现（最小判据：userId 归属 / share 公开）。
 * <p>
 * B0 判据仅使用现有字段：kid 非空 && 库存在 &&（库归属当前会话用户 || share=1 公开库）。
 * 真实库写入路径（Controller#add）均从会话派生 userId，库存在则 userId 非空。
 * <p>
 * 两个变体的分工：单参 {@link #checkRetrievalAccess(Long)} 供 HTTP 线程（内部读会话）；
 * 双参 {@link #checkRetrievalAccess(Long, Long)} 供非 HTTP 线程显式传身份
 * （调用方负责身份已验，如 ws 握手验 token 后放入 session attributes）。
 * <p>
 * B1/B2 接入 sensitivity/scope_type 列后，由 IPD 侧提供更强实现替换本默认实现：
 * 接口定义在 ruoyi-chat、依赖方向 ruoyi-ipd → ruoyi-chat 已验证，
 * 未来 ipd 模块实现 {@link KnowledgeAccessGate} 并以更高优先级注入即可覆盖本 Bean。
 *
 * @author ruoyi
 * @date 2026-09-28
 */
@Component
@RequiredArgsConstructor
public class UserIdShareKnowledgeAccessGate implements KnowledgeAccessGate {

    private final IKnowledgeInfoService knowledgeInfoService;

    /**
     * HTTP 线程变体：从 Sa-Token 会话取身份后委托双参判据。
     */
    @Override
    public void checkRetrievalAccess(Long kid) {
        // 未登录或会话上下文不可用（LoginHelper.getUserId() 返回 null）一律拒绝，且不触库
        checkRetrievalAccess(kid, LoginHelper.getUserId());
    }

    /**
     * 显式身份变体：非 HTTP 线程（如 ws 消息线程）传入握手期已校验的 userId。
     * 判据与单参变体完全同源：kid 非空 && userId 非空 && 库存在 &&（归属 || share=1）。
     */
    @Override
    public void checkRetrievalAccess(Long kid, Long userId) {
        if (kid == null) {
            throw new ServiceException("知识库ID为空，无权访问该知识库");
        }
        if (userId == null) {
            throw new ServiceException("未登录或会话已失效，无权访问该知识库 kid=" + kid);
        }
        KnowledgeInfoVo knowledgeInfo = knowledgeInfoService.queryById(kid);
        if (knowledgeInfo == null) {
            throw new ServiceException("知识库不存在，无权访问该知识库 kid=" + kid);
        }
        boolean owned = knowledgeInfo.getUserId() != null && knowledgeInfo.getUserId().equals(userId);
        boolean shared = Long.valueOf(1L).equals(knowledgeInfo.getShare());
        if (!owned && !shared) {
            throw new ServiceException("无权访问该知识库 kid=" + kid);
        }
    }
}
