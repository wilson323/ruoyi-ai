package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.ipd.agent.kernel.ProjectKnowledgeFragmentHit;
import org.ruoyi.ipd.agent.kernel.ProjectKnowledgeVectorSearch;

import java.util.List;

/**
 * 项目资料检索的产品知识库正文查询。只读，不改附件状态，也不清空来源备注。
 * 嵌入模型和向量库是否已填写，不决定这一行能不能被读到。
 */
@Mapper
public interface ProjectKnowledgeFragmentMapper {

    /**
     * 按标题或正文关键词找出本项目允许查看的片段。
     * 不要求附件已解析，也不要求知识库或片段的嵌入模型、向量库配置为空。
     * 可见范围只保留两条：当前项目自己的库，或未挂到别的项目的系统产品库（user_id=0 且 project_id 为空）。
     * 平台 KnowledgeAccessGate 的读面是「本人拥有或已分享」。产品知识库 user_id=0 且未分享，
     * 套上那条会把系统库藏掉，所以这里不改成 share=1，也不纳入其他租户或 user_id=1。
     * 租户、项目和这条系统库范围都不放宽。
     *
     * @param tenantId 知识表租户，IPD 单企业为 0
     * @param projectId 当前项目
     * @param likeQueries 已转义的 LIKE 模式，每个是一个关键词，不是整句问题
     * @param limit 最多返回行数
     * @return 命中行；同一附件的取舍在 Java 侧完成
     */
    @Select("""
        <script>
        SELECT kf.id AS fragmentId, kf.knowledge_id AS knowledgeId, kf.doc_id AS docId,
        kf.content AS content, kf.embedding_model AS embeddingModel,
        ka.name AS attachName, ka.status AS attachStatus, ka.remark AS sourceRemark
        FROM knowledge_fragment kf
        JOIN knowledge_info ki ON ki.id = kf.knowledge_id
        JOIN knowledge_attach ka ON ka.knowledge_id = ki.id AND ka.doc_id = kf.doc_id
        WHERE ki.tenant_id = #{tenantId}
        AND ((ki.scope_type = 'PROJECT' AND ki.project_id = #{projectId})
        OR (ki.user_id = 0 AND ki.project_id IS NULL))
        AND (
        <foreach collection="likeQueries" item="pattern" separator=" OR ">
        (ka.name LIKE #{pattern} ESCAPE '!' OR kf.content LIKE #{pattern} ESCAPE '!')
        </foreach>
        )
        ORDER BY kf.idx, kf.id
        LIMIT #{limit}
        </script>
        """)
    List<ProjectKnowledgeFragmentHit> search(@Param("tenantId") long tenantId,
                                             @Param("projectId") long projectId,
                                             @Param("likeQueries") List<String> likeQueries,
                                             @Param("limit") int limit);

    /**
     * 列出当前项目允许查看的知识库，供同一检索入口走向量检索。
     * 范围与正文查询相同，不按嵌入模型或向量库是否填写排除。
     * 有人员时向量检索就用这一条范围，不再套归属或 share=1。
     * 没有人员时仍由 KnowledgeAccessGate 拒绝。这里不改 share，也不改 user_id。
     *
     * @param tenantId 知识表租户
     * @param projectId 当前项目
     * @return 范围内的知识库；空列表表示没有可候选的库
     */
    @Select("""
        SELECT ki.id AS knowledgeId, ki.embedding_model AS embeddingModel, ki.vector_model AS vectorModel
        FROM knowledge_info ki
        WHERE ki.tenant_id = #{tenantId}
        AND ((ki.scope_type = 'PROJECT' AND ki.project_id = #{projectId})
        OR (ki.user_id = 0 AND ki.project_id IS NULL))
        """)
    List<ProjectKnowledgeVectorSearch.Scope> listInScope(@Param("tenantId") long tenantId,
                                                         @Param("projectId") long projectId);

    /**
     * 核对向量命中是否仍对应一条有附件名称的片段。
     * 片段或附件已经不在时返回空，调用方不能用知识库 ID 编出处。
     * 片段键必须有值并对上 fid 或主键，不能改挂到同一文档的其他片段。
     *
     * @param knowledgeId 向量查询的知识库
     * @param docId 向量载荷里的文档号
     * @param fragmentKey 向量片段标识，可空
     * @return 当前身份；没有附件或片段已不在时为 null
     */
    @Select("""
        SELECT kf.id AS fragmentId, kf.knowledge_id AS knowledgeId, kf.doc_id AS docId,
        ka.name AS attachName, ka.remark AS sourceRemark
        FROM knowledge_fragment kf
        JOIN knowledge_attach ka ON ka.knowledge_id = kf.knowledge_id AND ka.doc_id = kf.doc_id
        WHERE kf.knowledge_id = #{knowledgeId}
        AND kf.doc_id = #{docId}
        AND ka.name IS NOT NULL AND CHAR_LENGTH(TRIM(ka.name)) > 0
        AND #{fragmentKey} IS NOT NULL AND CHAR_LENGTH(TRIM(#{fragmentKey})) > 0
        AND (
        kf.fid = #{fragmentKey}
        OR CAST(kf.id AS CHAR) = #{fragmentKey}
        )
        LIMIT 1
        """)
    ProjectKnowledgeFragmentHit findLiveFragment(@Param("knowledgeId") long knowledgeId,
                                                 @Param("docId") String docId,
                                                 @Param("fragmentKey") String fragmentKey);
}
