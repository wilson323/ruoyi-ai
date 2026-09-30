package org.ruoyi.ipd.agent.domain;

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
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * 运行事件行（ipd_agent_run_event）。唯一键 (run_id, seq) 保证同序号只落一次，
 * 重复写入被数据库拒绝并由 store 视为去重成功。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ipd_agent_run_event", autoResultMap = true)
public class IpdAgentRunEvent extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String tenantId;
    private Long runId;
    /** 运行内单调递增序号（从 1 开始）。 */
    private Long seq;
    /** AgentEventType 名称。 */
    private String eventType;
    /** 事件载荷 JSON 对象。 */
    private String payload;
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
