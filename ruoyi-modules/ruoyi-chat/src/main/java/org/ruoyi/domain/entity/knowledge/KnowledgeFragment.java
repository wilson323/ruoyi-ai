package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 知识片段对象 knowledge_fragment
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_fragment")
public class KnowledgeFragment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 向量库片段ID（与向量库中的 fid 元数据对应，用于向量定位与混合检索融合）
     */
    private String fid;

    /**
     * 文档ID-用于关联文本块信息
     */
    private String docId;

    /**
     * 片段索引下标
     */
    private Integer idx;

    /**
     * 文档内容
     */
    private String content;

    /**
     * 备注
     */
    private String remark;

    /**
     * 知识库ID
     */
    private Long knowledgeId;

    // ========== B1：向量版本三元组（镜像 Part A 列；唯一生产者 KnowledgeAttachServiceImpl#parse） ==========

    /**
     * 本片段实际使用的 embedding 模型名（写入时快照，非库级引用）
     */
    private String embeddingModel;

    /**
     * 本片段向量维度（与 embedding_model 联合判桶；取嵌入实测值，非模型配置值）
     */
    private Integer embeddingDim;

    /**
     * 本片段最近一次成功嵌入时间（增量重嵌入水位线）
     */
    private Date embeddedAt;


}
