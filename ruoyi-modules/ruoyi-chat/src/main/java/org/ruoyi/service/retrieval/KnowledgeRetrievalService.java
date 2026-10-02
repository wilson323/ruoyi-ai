package org.ruoyi.service.retrieval;

import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;

import java.util.List;

/**
 * 知识库检索服务接口
 * 整合粗召回（向量检索/关键词检索）和重排序流程
 *
 * @author yang
 * @date 2026-04-19
 */
public interface KnowledgeRetrievalService {

    /**
     * 执行知识库检索，返回文本内容
     * 流程：向量粗召回 -> 重排序（可选） -> 返回结果
     *
     * @param queryVectorBo 查询参数
     * @return 文本内容列表
     */
    List<String> retrieveTexts(QueryVectorBo queryVectorBo);

    /**
     * 执行知识库检索，返回详细结果对象（包含分数、文档ID等）
     * 支持混合检索和重排序
     *
     * @param queryVectorBo 查询参数
     * @return 检索结果列表
     */
    List<KnowledgeRetrievalVo> retrieve(QueryVectorBo queryVectorBo);

    void invalidateKnowledge(String kid);

    /**
     * 显式身份变体（B2 检索接线）：供非 HTTP 线程（@Async 线程等）
     * 传入已验证的 userId，检索入口据此经
     * KnowledgeAccessGate#retrievalAccessProfile(Long) 装配访问过滤参数。
     * 语义与 {@link #retrieve(QueryVectorBo)} 完全一致，仅身份来源不同；
     * userId 为 null 时按匿名（anon 最严档）处理，不抛错。
     *
     * @param queryVectorBo 查询参数
     * @param userId        已验证的会话用户 ID（null=匿名）
     * @return 检索结果列表
     */
    List<KnowledgeRetrievalVo> retrieve(QueryVectorBo queryVectorBo, Long userId);
}
