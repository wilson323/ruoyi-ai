package org.ruoyi.domain.entity.knowledge;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 知识库对象 knowledge_info
 *
 * @author ageerle
 * @date 2025-12-17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_info")
public class KnowledgeInfo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 是否公开知识库（0 否 1是）。
     * <p>
     * B1 起降级为后端派生只读镜像（最佳实践 §8.1 规则 3）：
     * share = (sensitivity == PUBLIC ? 1 : 0)，唯一写点
     * KnowledgeInfoServiceImpl#insertByBo/updateByBo，客户端传值一律被覆盖。
     * <p>
     * P2-1 顺手修（B1 Validator）：显式钉死 UPDATE 策略 NOT_NULL——
     * 实体置 null 不参与 UPDATE SET（与「派生镜像不回写清空」语义一致），
     * 不再隐式依赖全局 field-strategy 默认值（yml 改全局策略会静默翻车）。
     */
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private Long share;

    // ========== B1：三层作用域 × 四维归属 × 敏感级（镜像 Part A 列，驼峰自动映射 snake_case 列名） ==========

    /**
     * 作用域：GLOBAL全局|GROUP产品线|PROJECT项目|PERSON个人|AGENT数字员工（列默认 PERSON）
     */
    private String scopeType;

    /**
     * 归属产品组（scope_type=GROUP/PROJECT 时使用；对齐 product_groups.id）
     */
    private Long groupId;

    /**
     * 归属项目（scope_type=PROJECT 时使用；对齐 projects.id）
     */
    private Long projectId;

    /**
     * 归属数字员工（scope_type=AGENT 时使用；对齐 agent_info.id）
     */
    private Long ownerAgentId;

    /**
     * 敏感级：PUBLIC公开|INTERNAL内部|SECRET机密（列默认 INTERNAL）。
     * SECRET 禁止由自动规则产生，升密仅人审显式改（§8.1 规则 1）。
     */
    private String sensitivity;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 知识分隔符
     */
    @TableField(value = "`separator`")
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
