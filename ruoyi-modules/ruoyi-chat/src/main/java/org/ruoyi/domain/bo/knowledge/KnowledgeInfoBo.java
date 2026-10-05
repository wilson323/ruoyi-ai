package org.ruoyi.domain.bo.knowledge;

import org.ruoyi.common.core.validate.AddGroup;
import org.ruoyi.common.core.validate.EditGroup;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.*;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;

/**
 * 知识库业务对象 knowledge_info
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = KnowledgeInfo.class, reverseConvertGenerate = false)
public class KnowledgeInfoBo extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = { EditGroup.class })
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 知识库名称
     */
    @NotBlank(message = "知识库名称不能为空", groups = { AddGroup.class, EditGroup.class })
    private String name;

    /**
     * 是否公开知识库（0 否 1是）——派生只读镜像，后端勿信前端值（B1 §8.1 规则 3）。
     * 写入时由 sensitivity 派生（PUBLIC→1，其余→0），客户端传值一律被
     * KnowledgeInfoServiceImpl#insertByBo/updateByBo 覆盖/清空。
     */
    private Long share;

    // ========== B1 新增：三层作用域 × 四维归属 × 敏感级（镜像 Part A 列） ==========

    /**
     * 作用域：GLOBAL|GROUP|PROJECT|PERSON|AGENT
     */
    private String scopeType;

    /**
     * 归属产品组
     */
    private Long groupId;

    /**
     * 归属项目
     */
    private Long projectId;

    /**
     * 归属数字员工
     */
    private Long ownerAgentId;

    /**
     * 敏感级：PUBLIC|INTERNAL|SECRET（null 时新增默认 INTERNAL；SECRET 仅人审显式设置）
     */
    private String sensitivity;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 知识分隔符
     */
    private String separator;

    /**
     * 重叠字符数
     */
    private Long overlapChar;

    /**
     * 知识库中检索的条数
     */
    private Long retrieveLimit;

    /**
     * 相似度阈值
     */
    private Double similarityThreshold;

    /**
     * 文本块大小
     */
    private Long textBlockSize;

    /**
     * 向量库
     */
    private String vectorModel;

    /**
     * 向量模型
     */
    private String embeddingModel;

    /**
     * 是否启用重排序（0 否 1是）
     */
    private Integer enableRerank;

    /**
     * 重排序模型名称
     */
    private String rerankModel;

    /**
     * 重排序后返回的文档数量
     */
    private Integer rerankTopN;

    /**
     * 重排序相关性分数阈值
     */
    private Double rerankScoreThreshold;


    /**
     * 是否启用混合检索（0 否 1是）
     */
    private Integer enableHybrid;

    /**
     * 混合检索权重 (0.0-1.0)
     */
    private Double hybridAlpha;

    /**
     * 备注
     */
    private String remark;

}
