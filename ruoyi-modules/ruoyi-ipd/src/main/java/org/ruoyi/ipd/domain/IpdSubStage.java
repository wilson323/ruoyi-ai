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

/**
 * IPD 小阶段目录（22 行元数据，主计划 §2 SSOT 落库）。
 * <p>只读目录：唯一写者 = owner DDL seed（docs/script/sql/update/2026-09-28-ipd-sub-stage-skill-map-ddl.sql，
 * 待 owner apply），应用侧不开写端点（防多写者）。字段口径见 §6-数据模型与DDL.md §6.2.1。
 * <p>tenant_id 列不在实体映射（表已登记 tenant.excludes，插入走 DDL 默认 '000000'；与 StageAction 同款）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName(value = "ipd_sub_stage", autoResultMap = true)
public class IpdSubStage extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 小阶段编码（CONCEPT-S1..KPI-S1，全局唯一，uk_sub_stage_code） */
    private String code;
    private String name;
    /** 所属大阶段 CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE|KPI（KPI=常驻跨阶段伪阶段） */
    private String stageCode;
    /** 阶段内排序（六阶段从 1 连续；KPI-S1=99 常驻豁免） */
    private Integer sortOrder;
    /** 是否承载大阶段 Gate（1=是） */
    private String isGate;
    /** Gate 编码 G1..G5（is_gate=1 必填，全局唯一） */
    private String gateCode;
    /** pm-skills 插件级引导提示（skill/command 级待 §3 定稿后 UPDATE） */
    private String skillHint;
    /** 常驻小阶段（1=跨阶段 KPI 归集，不参与顺序推进门禁） */
    private String isResident;
    /** 主导角色 MARKET_PM|RD_PM|BOTH|GROUP_LEADER */
    private String ownerRole;
    private String remark;

    /** 软删除标志（0正常 1已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
