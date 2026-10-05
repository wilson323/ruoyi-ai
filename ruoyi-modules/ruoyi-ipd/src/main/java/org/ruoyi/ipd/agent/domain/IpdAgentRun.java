package org.ruoyi.ipd.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

import java.util.Date;

/**
 * 项目智能体运行行（ipd_agent_run，DDL 见
 * {@code docs/script/sql/update/2026-09-30-ipd-project-agent-ddl.sql}，待授权 apply）。
 *
 * <p>业务完成权威仍是 {@code ai_agent_tasks}；本行只记录一次智能体运行的配置快照、
 * 状态机与事件归属，{@link #taskId} 可空关联（W1 不向 ai_agent_tasks 写入）。
 * 用户输入只存 SHA-256 摘要与字符数（审计规约 L0-5），不落原文。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ipd_agent_run", autoResultMap = true)
public class IpdAgentRun extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    /** 服务端重读 Person 得到的可信租户（非请求参数）。 */
    private String tenantId;
    private Long projectId;
    /** 发起人 Person ID（身份来自会话）。 */
    private Long personId;
    /** 固定 ipd_project_agent。 */
    private String agentId;
    /** AgentRunStatus 名称。 */
    private String status;
    private String actionCode;
    private String capabilityPackCode;
    private String capabilityPackVersion;
    private Long modelConfigId;
    /** 创建时冻结的配置快照 JSON（能力包/模型/Skill 名与 sha256/工具 ID）。 */
    private String configSnapshot;
    private String idempotencyKey;
    /** 规范化请求摘要：同幂等键不同请求体判冲突。 */
    private String requestDigest;
    /** 用户输入 SHA-256（不存原文）。 */
    private String inputDigest;
    private Integer inputChars;
    /** 可空：W2 接入 ai_agent_tasks 后回填。 */
    private Long taskId;
    private String errorCode;
    private Date startedAt;
    private Date finishedAt;
    @Version
    private Integer version;
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
