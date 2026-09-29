package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.DocFragmentCountVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.enums.KnowledgeSensitivity;

import java.util.List;

/**
 * 知识片段Mapper接口
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Mapper
public interface KnowledgeFragmentMapper extends BaseMapperPlus<KnowledgeFragment, KnowledgeFragmentVo> {

    /**
     * 批量统计各文档的分块数（强类型接收，避免 Map key 大小写问题）
     *
     * @param docIds 文档 ID 列表
     * @return 每个 docId 对应的分块数列表
     */
    @Select("<script>" +
            "SELECT doc_id AS docId, COUNT(*) AS fragmentCount " +
            "FROM knowledge_fragment " +
            "WHERE doc_id IN " +
            "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY doc_id" +
            "</script>")
    List<DocFragmentCountVo> selectFragmentCountByDocIds(@Param("docIds") List<String> docIds);
    /**
     * 关键词（MySQL FULLTEXT）粗召回通道。
     * <p>
     * B1 四刀之一：JOIN knowledge_info 追加敏感级谓词，与向量通道 payload filter 上下一致
     * （实施方案 §3.1——否则混合检索 RRF 的关键词路成为过滤旁路）。敏感级为库级列
     * （knowledge_fragment 无该列），故必须 JOIN；sensitivityAllowed 为 null 时不加谓词
     * （与向量侧 buildAccessWhereFilter 空参数语义对称，B1 运行态零行为变化）。
     * 谓词为「允许值集合」语义：ki.sensitivity IN (集合)——集合由
     * {@link KnowledgeSensitivity#allowedValuesUpTo} 单源展开（禁字符串序比较：
     * 字典序 INTERNAL &lt; PUBLIC &lt; SECRET 与敏感级升序 PUBLIC &lt; INTERNAL &lt; SECRET
     * 不一致，序比较曾致越权放大/误杀，Validator P0）。
     */
    @Select("<script>" +
            "SELECT kf.id, kf.fid, kf.doc_id AS docId, kf.content, kf.idx, kf.knowledge_id AS knowledgeId " +
            "FROM knowledge_fragment kf " +
            "JOIN knowledge_info ki ON ki.id = kf.knowledge_id " +
            "WHERE kf.knowledge_id = #{knowledgeId} " +
            "AND MATCH (kf.content) AGAINST (#{query} IN NATURAL LANGUAGE MODE) " +
            "<if test='sensitivityAllowed != null and sensitivityAllowed.size() > 0'>" +
            "AND ki.sensitivity IN " +
            "<foreach collection='sensitivityAllowed' item='sv' open='(' separator=',' close=')'>#{sv}</foreach> " +
            "</if>" +
            "ORDER BY MATCH (kf.content) AGAINST (#{query} IN NATURAL LANGUAGE MODE) DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<KnowledgeFragmentVo> searchByKeyword(@Param("knowledgeId") Long knowledgeId,
                                              @Param("query") String query,
                                              @Param("limit") Integer limit,
                                              @Param("sensitivityAllowed") List<String> sensitivityAllowed);

    /**
     * 兼容重载：以「敏感级上限」形态调用（既有调用方 KnowledgeRetrievalServiceImpl 透传
     * QueryVectorBo#maxSensitivity 的 String 值，签名不变零改动）。
     * 上限在此经 {@link KnowledgeSensitivity#allowedNamesUpTo} 单源展开为允许值集合后
     * 委托集合形态主方法——两侧消费端（Weaviate ContainsAny / MySQL IN）共用同一展开，
     * 无双轨。null/空白 = 不启用谓词（空态语义）；非空非法值由展开方法快速失败。
     */
    default List<KnowledgeFragmentVo> searchByKeyword(Long knowledgeId, String query, Integer limit,
                                                      String maxSensitivity) {
        List<String> sensitivityAllowed = (maxSensitivity == null || maxSensitivity.isBlank())
            ? null : KnowledgeSensitivity.allowedNamesUpTo(maxSensitivity);
        return searchByKeyword(knowledgeId, query, limit, sensitivityAllowed);
    }
}
