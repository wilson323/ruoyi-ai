package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * R149 batch2b C3：永久清除审计（R-AC-C3-01：审计永久保留）。
 *
 * <p>实体被永久删除前，先把整行 JSON 序列化写入本表，供事后审计/合规追溯。
 * <p>删除语义：仅 SUPER_ADMIN 可触发；请求必须含 {@code confirmCode=PERMANENT_DELETE_CONFIRMED}。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code originalDataJson}：被删实体的完整 JSON 快照（含 BaseEntity 审计字段）</li>
 *   <li>{@code entityType}：person|project|kpi_record（白名单）；scenario 暂未建模，返回 400</li>
 *   <li>{@code operatorId}：执行永久删除的超管 ID</li>
 *   <li>{@code ipAddress}：客户端 IP（X-Forwarded-For 取首段）</li>
 *   <li>{@code tenantId}：租户隔离</li>
 * </ul>
 *
 * <p>与 {@link AuditLog} 区别：AuditLog 写「事件」（操作类型 + before/after diff）；
 * 本表写「快照」（被删实体整行），合规视角下两者互补——事件用于操作追溯，快照用于数据还原。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "permanent_delete_audit", autoResultMap = true)
public class PermanentDeleteAudit extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作人（persons.id；SUPER_ADMIN） */
    @TableField("operator_id")
    private Long operatorId;

    /** 操作人姓名（冗余存，便于审计展示） */
    @TableField("operator_name")
    private String operatorName;

    /** 实体类型：person|project|kpi_record（白名单） */
    @TableField("entity_type")
    private String entityType;

    /** 被删实体主键 */
    @TableField("entity_id")
    private Long entityId;

    /** 被删实体的完整 JSON 快照 */
    @TableField("original_data_json")
    private String originalDataJson;

    /** 删除时间（冗余 create_time，方便审计按时间排序） */
    @TableField("deleted_at")
    private Date deletedAt;

    /** 客户端 IP（X-Forwarded-For 首段；无则记 "unknown"） */
    @TableField("ip_address")
    private String ipAddress;

    /** 租户 ID */
    @TableField("tenant_id")
    private String tenantId;

    /** 软删除标志（审计本身不允许真删，置 1 时只表示归档） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
