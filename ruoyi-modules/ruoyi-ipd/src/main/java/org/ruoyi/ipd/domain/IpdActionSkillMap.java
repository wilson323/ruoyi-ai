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
 * IPD 动作 × 小阶段 × pm-skill 映射（不变量①：(action_code, project_id) 唯一归属，2026-10-06 起含项目维度）。
 * <p>project_id=0 为全局默认绑定，&gt;0 为项目级绑定；运行装配时项目级优先，无项目级绑定回退全局默认。
 * <p>只读映射：唯一写者 = owner 审核后的 DDL/seed（写入即拍板，不随运行自动晋升）。故意不存动作名称——
 * 名称 SSOT 在 ActionCatalog（编译期）与 stage_actions.action_name（实例快照），对名称漂移免疫（§6.2.3）。
 * <p>skill_names 为 JSON 数组文本（存储列 json 强制合法）；NULL = §3 未定稿，不算绑定、不拦截回退。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName(value = "ipd_action_skill_map", autoResultMap = true)
public class IpdActionSkillMap extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 动作编码（69 真动作，uk_map_action_code；A01/A02/A1/A2 由 CHECK 排除） */
    private String actionCode;
    /** 归属小阶段（→ ipd_sub_stage.code） */
    private String subStageCode;
    /** 项目维度：0=全局默认绑定；>0=项目级绑定（uk_map_action_project 第二键） */
    private Long projectId;
    /** pm-skills 技能/命令 JSON 数组文本；NULL=待 §3 定稿后补齐 */
    private String skillNames;
    /** 小阶段内动作排序（从 1 连续） */
    private Integer sortOrder;
    private String remark;

    /** 租户ID（单企业部署恒 000000；表在 tenant.excludes，值由 seed SQL 显式写入） */
    private String tenantId;

    /** 软删除标志（0正常 1已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
