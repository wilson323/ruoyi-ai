package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 文档向量化切片（AI-STRAT-1，2026-09-11）。
 * <p>ai_documents 审核通过（REVIEWED）即异步向量化：content 按固定窗口切片，每片存
 * embedding 向量（JSON float 数组）。生成时同项目（含同 embed_model）余弦 top-K 检索
 * 注入上下文。量级小不引入向量库（平台 weaviate 服务 AI 管理平台，两底座职责分离）。
 * <p>向量空间一致性锚：{@code embedModel} 参与检索过滤——换 embedding 模型后旧向量
 * 自动退出检索（不可比），重新 review 触发向量化重建。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_doc_embeddings")
public class AiDocEmbedding implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** ai_documents.id（版本链节点） */
    @TableField("doc_id")
    private Long docId;

    /** 项目ID（检索范围锚） */
    @TableField("project_id")
    private Long projectId;

    /** 文档类型快照（PRD/MRD…，注入上下文标注来源用） */
    @TableField("doc_type")
    private String docType;

    /** 文档标题快照 */
    @TableField("title")
    private String title;

    /** 切片序号（0 起） */
    @TableField("chunk_seq")
    private Integer chunkSeq;

    /** 切片原文（检索命中后注入上下文的片段） */
    @TableField("chunk_text")
    private String chunkText;

    /** embedding 模型名（向量空间一致性锚：换模型旧向量不可比） */
    @TableField("embed_model")
    private String embedModel;

    /** 向量（JSON float 数组；Java 余弦计算） */
    @TableField("vector_json")
    private String vectorJson;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("create_time")
    private Date createTime;
}
