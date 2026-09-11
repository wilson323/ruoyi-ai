package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * SOP 模板主表（P1-3.3，BR-IPD-07：每个深管动作绑定一份 SOP）。
 * <p>以 {@link #actionCode} 为版本序列维度（同动作下 {@link #version} 自增）；
 * 新版本发布时旧 PUBLISHED 自动 ARCHIVED（BR-IPD-07：修改后新项目用新版，在研项目保持原版）。
 * 实例化快照走 {@code SopTemplateInstance}（stage_actions.sop_id 绑定当时 PUBLISHED 的模板 id）。
 * <p>删除走 DeletionRequestService + DeleteAuditService（P0-6.2，软删除）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sop_templates", autoResultMap = true)
public class SopTemplate extends BaseEntity implements SoftDeletable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 模板编码（v1 老模型字段，DDL 保留可空列；actionCode 模型下不再作为版本维度） */
    private String templateCode;

    /** 模板名称（v1 老模型字段，DDL 保留可空列） */
    private String templateName;

    /** 模板说明 */
    private String description;

    /** 绑定动作编号（深管 42 动作，如 V10/C12/D11；版本序列维度，DDL NOT NULL + idx_sop_action） */
    private String actionCode;

    /** SOP 标题（去空格 2-128 字，DDL NOT NULL） */
    private String title;

    /** SOP 正文（mediumtext 富文本；版本列表不查询本列，仅详情返回） */
    private String content;

    /** 正文字符数（CHAR_LENGTH(content) 计算列，非表字段；版本列表轻量视图用） */
    @TableField(exist = false)
    private Long contentLen;

    /**
     * 业务版本号（同 action_code 下从 1 开始自增；非乐观锁；
     * 真正的乐观锁由 MyBatis-Plus {@code @Version} 字段独立承担，本字段为业务字段）
     */
    private Long version;

    /** 生效起点 */
    private Date effectiveFrom;

    /** 生效终点（null=当前生效） */
    private Date effectiveTo;

    /** 状态 DRAFT|PUBLISHED|ARCHIVED */
    private String status;

    /** 分类 DEEP_MGMT|LIGHT_MGMT|MIXED */
    private String category;

    /** 创建人 Person ID（与 BaseEntity.createBy 重复冗余以便模板审计检索） */
    private String createdBy;

    /** 软删除标志（0正常 1已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;

    /** 租户ID */
    private String tenantId;

    /** 显式覆盖 Lombok @Accessors(chain=true) 链式 setter 以匹配 SoftDeletable 接口签名 */
    public void setDelFlag(String flag) { this.delFlag = flag; }

    /** 状态枚举字面量（与 DB 字段对齐，避免散落字面量） */
    public static final class Status {
        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String ARCHIVED = "ARCHIVED";
        private Status() {}
    }

    /** 分类枚举字面量 */
    public static final class Category {
        public static final String DEEP_MGMT = "DEEP_MGMT";
        public static final String LIGHT_MGMT = "LIGHT_MGMT";
        public static final String MIXED = "MIXED";
        private Category() {}
    }
}