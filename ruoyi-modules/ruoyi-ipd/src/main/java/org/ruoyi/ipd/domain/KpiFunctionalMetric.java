package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import lombok.experimental.Accessors;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;

/**
 * IPD KPI 功能指标量表（A2 P1 期，R148.1 §2.2 方案②）。
 *
 * <p>用途：为 8 项功能指标（4 市场 + 4 研发）提供**人工录入 / 量表版本载体**。
 * 现状缺口：{@code KpiScoreCalculator} 仅有 {@code deviationDays} / {@code windowHitRate}
 * 两个 compute 方法，其余 6 项既无录入入口也无目标值/量表版本，导致「8 项功能指标
 * 仍需有效数据集合/量表/期间、PPM 目标、返工率分母」（DOC-01 §6 U03）无法落地。
 *
 * <p>8 项指标编码（详见 DDL 文件头；与 {@code KpiScoreCalculator} 字段名一一对应）：
 * <ul>
 *   <li>市场：MKT_REQUIREMENT_ACCURACY / MKT_WINDOW_HIT_RATE /
 *       MKT_SCENARIO_COMPETITIVENESS / MKT_COMPETITOR_INTELLIGENCE</li>
 *   <li>研发：RD_LAUNCH_ON_TIME_RATE / RD_QUALITY_DEFECT_RATE /
 *       RD_TECH_INNOVATION / RD_FIRST_PASS_YIELD</li>
 * </ul>
 *
 * <p>语义要点（DOC-01 §4「功能指标不因页面 rawValue 默认 0 而把无数据当 0」）：
 * {@code metricValue} 为空表示**待补充**，不等同 0 分，也不得据此自动出分。
 *
 * <p>R-A2 约束：{@code scaleVersion} 为 VARCHAR(50) **不加 FK**——{@code kpi_rule_snapshots}
 * 当前 0 行，加 FK 与 R139 P0 #3 互锁；待其解决后再补 FK。
 *
 * <p>删除走软删除（G-02：禁物理 DELETE，仅 UPDATE del_flag）；
 * 与 kpi_records / kpi_raw_records 同款，实现 {@link SoftDeletable}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "kpi_functional_metrics", autoResultMap = true)
public class KpiFunctionalMetric extends BaseEntity implements SoftDeletable {

    /** 主键（雪花算法） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 项目 ID */
    private Long projectId;

    /** 指标编码（8 项白名单，见类 Javadoc） */
    private String metricCode;

    /** 期间（如 2026-09 或 上市后 6 个月） */
    private String period;

    /** 指标值（人工录入）；NULL = 待补充，不等同 0 分 */
    private BigDecimal metricValue;

    /** 目标值（如 PPM 目标） */
    private BigDecimal targetValue;

    /** 量表版本（VARCHAR(50)，不加 FK：R-A2 约束） */
    private String scaleVersion;

    /** 备注 */
    private String remark;

    /** 租户 ID（单企业私有部署，无多租户语义；已登记 tenant.excludes） */
    private String tenantId;

    /** 软删除标志（0 正常 1 已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;

    /** 显式覆盖 Lombok @Accessors(chain=true) 的链式 setter（SoftDeletable 契约要求 void） */
    public void setDelFlag(String flag) { this.delFlag = flag; }
}
