package org.ruoyi.ipd.agent.kernel;

import lombok.Data;

/**
 * 产品知识库正文检索的一行。向量列为空、附件仍待解析时也能返回。
 */
@Data
public class ProjectKnowledgeFragmentHit {

    /** 片段主键。 */
    private Long fragmentId;
    private Long knowledgeId;
    private String docId;

    /** 片段正文。 */
    private String content;

    /** 片段上的嵌入模型名；空表示还没有向量。 */
    private String embeddingModel;

    /** 附件标题。 */
    private String attachName;

    /** 附件解析状态：0 待解析，1 解析中，2 已解析，3 解析失败。 */
    private Integer attachStatus;

    /** 附件备注里的来源路径。 */
    private String sourceRemark;
}
