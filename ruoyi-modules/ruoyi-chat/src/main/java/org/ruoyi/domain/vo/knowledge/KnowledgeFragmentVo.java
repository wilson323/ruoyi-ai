package org.ruoyi.domain.vo.knowledge;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;

import java.io.Serial;
import java.io.Serializable;


/**
 * 知识片段视图对象 knowledge_fragment
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = KnowledgeFragment.class)
public class KnowledgeFragmentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @ExcelProperty(value = "主键")
    private Long id;

    /**
     * 向量库片段ID
     */
    private String fid;

    /**
     * 文档ID-用于关联文本块信息
     */
    private String docId;

    /**
     * 片段索引下标
     */
    @ExcelProperty(value = "片段索引下标")
    private Integer idx;

    /**
     * 文档内容
     */
    @ExcelProperty(value = "文档内容")
    private String content;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

    /**
     * 知识库ID
     */
    private Long knowledgeId;

    // ========== B1：向量版本三元组（镜像 Part A 列；Bo 不加——片段列是嵌入产物，非筛选入参） ==========

    /**
     * 本片段实际使用的 embedding 模型名
     */
    private String embeddingModel;

    /**
     * 本片段向量维度
     */
    private Integer embeddingDim;

    /**
     * 本片段最近一次成功嵌入时间
     */
    private java.util.Date embeddedAt;


}
