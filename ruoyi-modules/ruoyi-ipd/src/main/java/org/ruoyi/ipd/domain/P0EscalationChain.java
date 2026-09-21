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
 * R149 batch2b C4：P0 升级链（AC-C4 决策：同一个 P0 连续两次都没升级，就要升级到双方组长）。
 *
 * <p>每条记录对应一个 (projectId, p0EventId) 的「升级计数」：
 * <ul>
 *   <li>{@code escalationCount}：连续未升级次数（每次 P0 超期未升级 +1）</li>
 *   <li>{@code lastEscalationAt}：最近一次「未升级」时间</li>
 *   <li>{@code nextThresholdAt}：预计下次超期阈值（基于 lastEscalationAt + 阈值间隔）</li>
 *   <li>{@code status}：PENDING（未到阈值）/ESCALATED（已触发升级）/RESOLVED（已解决停计）</li>
 * </ul>
 *
 * <p>触发逻辑：{@code P0EscalationService.checkEscalation()} 扫描 {@code escalationCount >= 2} 的记录，
 * 给双方组长发通知（receiver_role=BOTH_LEADERS 标在 content 中，通知按 persons.person_type=GROUP_LEADER 各自投递）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "p0_escalation_chain", autoResultMap = true)
public class P0EscalationChain extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 项目 ID */
    @TableField("project_id")
    private Long projectId;

    /** P0 事件 ID（业务侧唯一，如 risk_event.id / issue_event.id） */
    @TableField("p0_event_id")
    private Long p0EventId;

    /** 连续未升级次数（每次 P0 超期未升级 +1） */
    @TableField("escalation_count")
    private Integer escalationCount;

    /** 最近一次「未升级」时间（用于审计追溯） */
    @TableField("last_escalation_at")
    private Date lastEscalationAt;

    /** 预计下次超期阈值（基于 last_escalation_at + 阈值间隔；用于调度扫描） */
    @TableField("next_threshold_at")
    private Date nextThresholdAt;

    /** PENDING|ESCALATED|RESOLVED */
    private String status;

    /** 备注（最后一次升级的原因 / 处置结果） */
    private String remark;

    @TableField("tenant_id")
    private String tenantId;

    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
