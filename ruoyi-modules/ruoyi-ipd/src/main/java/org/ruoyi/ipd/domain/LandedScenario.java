package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import lombok.experimental.Accessors;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * IPD 落地场景登记（A4 落地场景登记，R149 batch2a）。
 *
 * <p>PM/组长在项目落地后录入实际产生的场景：scenario_code（场景编码）+ scenario_name（场景名）
 * + landed_date（落地日期）+ landed_amount（落地金额，可选）+ remark（备注）。
 *
 * <p>用户拍板简化（2026-09-20）：
 * <ul>
 *   <li>仅做登记界面 + 导入入口，不做双认定验证（不做销售报备 JOIN / 交付验收 JOIN）</li>
 *   <li>权限：MARKET_PM / RD_PM / GROUP_LEADER / SUPER_ADMIN 可写；内部全员可读</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "landed_scenarios", autoResultMap = true)
public class LandedScenario extends BaseEntity implements SoftDeletable {

    /** 主键（雪花算法） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 项目ID */
    private Long projectId;

    /** 场景编码（业务唯一标识） */
    private String scenarioCode;

    /** 场景名 */
    private String scenarioName;

    /** 落地日期 */
    private LocalDate landedDate;

    /** 落地金额（可空，decimal(14,2) 容纳百万级场景金额） */
    private BigDecimal landedAmount;

    /** 录入人 */
    private Long recordedBy;

    /** 备注 */
    private String remark;

    /** 租户ID */
    private String tenantId;

    /** 软删除标志（0正常 1已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;

    /** 显式覆盖 Lombok @Accessors(chain=true) 的链式 setter */
    public void setDelFlag(String flag) { this.delFlag = flag; }
}
