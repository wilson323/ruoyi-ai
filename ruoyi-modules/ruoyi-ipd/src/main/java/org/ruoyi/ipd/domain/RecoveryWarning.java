package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import lombok.experimental.Accessors;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * IPD 90 日回款预警（C1 90 日回款预警，R149 batch2a）。
 *
 * <p>扫描所有上市后未满 90 日的项目：
 * <ul>
 *   <li>回款比例 = SUM(窗口内 receipt_amount - refund_amount) / target_sales_amount</li>
 *   <li>阈值 = {@code system_configs.config_key = 'recovery.warning90d.threshold'}，默认 0.25</li>
 *   <li>低于阈值 → 写入 recovery_warnings（status=PENDING）</li>
 * </ul>
 *
 * <p>幂等：同日 (projectId, warning_date) 已存在则跳过（不重复落库）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "recovery_warnings", autoResultMap = true)
public class RecoveryWarning extends BaseEntity implements SoftDeletable {

    /** 主键（雪花算法） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 项目ID */
    private Long projectId;

    /** 预警日期（扫描当日） */
    private LocalDate warningDate;

    /** 距上市日数（扫描当日 - 上市日） */
    private Integer daysSinceLaunch;

    /** 回款比例（0~1） */
    private BigDecimal recoveryRate;

    /** 触发阈值（默认 0.25） */
    private BigDecimal threshold;

    /** 状态 PENDING|HANDLED|IGNORED */
    private String status;

    /** 租户ID */
    private String tenantId;

    /** 软删除标志（0正常 1已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;

    /** 显式覆盖 Lombok @Accessors(chain=true) 的链式 setter */
    public void setDelFlag(String flag) { this.delFlag = flag; }

    /** PENDING 状态常量 */
    public static final String STATUS_PENDING = "PENDING";
    /** HANDLED 状态常量（已被处理） */
    public static final String STATUS_HANDLED = "HANDLED";
    /** IGNORED 状态常量（豁免） */
    public static final String STATUS_IGNORED = "IGNORED";
}
