package org.ruoyi.mapper.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachSource;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachVo;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import java.util.List;
import java.util.Map;

/**
 * 知识库附件Mapper接口
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Mapper
public interface KnowledgeAttachMapper extends BaseMapperPlus<KnowledgeAttach, KnowledgeAttachVo> {

    /**
     * 统计指定知识库下的文档数量
     */
    @Select("SELECT COUNT(*) FROM knowledge_attach WHERE knowledge_id = #{knowledgeId}")
    int countByKnowledgeId(@Param("knowledgeId") Long knowledgeId);

    @Select("<script>SELECT knowledge_id AS knowledgeId, COUNT(*) AS documentCount " +
            "FROM knowledge_attach WHERE knowledge_id IN " +
            "<foreach collection='knowledgeIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY knowledge_id</script>")
    List<Map<String, Object>> countByKnowledgeIds(@Param("knowledgeIds") List<Long> knowledgeIds);

    /**
     * 按文档取一条出处。创建人按字符串映射，非数字不会让检索失败。
     *
     * @param knowledgeId 本次查询绑定的知识库 ID
     * @param docId 文档 ID
     * @return 文件名和创建人原文；没有行时为 null
     */
    @Select("""
        SELECT name, create_by
        FROM knowledge_attach
        WHERE knowledge_id = #{knowledgeId} AND doc_id = #{docId}
        LIMIT 1
        """)
    @Results({
        @Result(column = "name", property = "name"),
        @Result(column = "create_by", property = "createBy")
    })
    KnowledgeAttachSource selectSourceByKnowledgeAndDocId(@Param("knowledgeId") String knowledgeId,
                                                       @Param("docId") String docId);
}
