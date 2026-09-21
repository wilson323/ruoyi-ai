package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import lombok.experimental.Accessors;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * IPD KPI 原始数据录入（A2 KPI P1 期补口，R149 batch2a）。
 *
 * <p>8 项 KPI 原始值落地表（kpi_raw_records）：用于解决现状「窗口命中率有算法、其他 7 项无录入入口」的卡点。
 * <ul>
 *   <li>WINDOW_HIT_RATE — 窗口命中率（量化口径：偏差天数→窗口命中率）</li>
 *   <li>REQUIREMENT_ACCURACY — 需求准确率</li>
 *   <li>SCENE_COMPETITIVENESS — 场景竞争力</li>
 *   <li>PPM_DEFECT_RATE — PPM 缺陷率</li>
 *   <li>RELEASE_FREQUENCY — 发布频率</li>
 *   <li>CHANGE_LEAD_TIME — 变更前置时间</li>
 *   <li>CHANGE_FAILURE_RATE — 变更失败率</li>
 *   <li>MTTR — 平均恢复时间</li>
 * </ul>
 *
 * <p>录入权限：{@code ipd:kpi:raw:create}（GROUP_LEADER / SUPER_ADMIN）。
 * 唯一性：(kpi_type, project_id, record_period) → 同项目同类型同周期一条；重复录入由 service 抛 STATE_CONFLICT。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "kpi_raw_records", autoResultMap = true)
public class KpiRawRecord extends BaseEntity implements SoftDeletable {

    /** 主键（雪花算法） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** KPI 类型枚举（见类 Javadoc） */
    private String kpiType;

    /** 项目ID */
    private Long projectId;

    /** 录入周期（按月，按 MySQL DATE 存每月 1 号） */
    private LocalDate recordPeriod;

    /** 原始值（各 KPI 单位不同，统一 decimal(10,4) 容纳 ppm / 百分比 / 时长 / 频率） */
    private BigDecimal rawValue;

    /** 录入人 */
    private Long recordedBy;

    /** 录入时间（服务端落 now，与 BaseEntity.createTime 区分——后者可能为空，专注业务时间戳） */
    private Date recordedAt;

    /** 租户ID */
    private String tenantId;

    /** 软删除标志（0正常 1已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;

    /** 显式覆盖 Lombok @Accessors(chain=true) 的链式 setter */
    public void setDelFlag(String flag) { this.delFlag = flag; }
}
