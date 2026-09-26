package org.ruoyi.ipd.domain;

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
 * R221 AI 代理执行任务行（outbox 范式，持久化事实源）。
 * DDL: docs/script/sql/update/20260926-ai-agent-tasks.sql；租户豁免已登记 tenant.excludes。
 * <p>状态机 PENDING→RUNNING→SUCCEEDED/FAILED/DEAD；抢占用条件 UPDATE + @Version 乐观锁。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ai_agent_tasks", autoResultMap = true)
public class AiAgentTask extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEAD = "DEAD";

    public static final String TRIGGER_PASSIVE = "PASSIVE";
    public static final String TRIGGER_CHAT = "CHAT";
    public static final String TRIGGER_EVENT = "EVENT";
    public static final String TRIGGER_SCHEDULE = "SCHEDULE";

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    private String actionCode;
    private Long stageActionId;
    private String triggerType;
    /** 触发时矩阵快照，防目录变更语义漂移 */
    private String execMode;
    private String status;
    private String dedupKey;
    private Integer attempt;
    private Date nextRetryAt;
    /** 入参指纹（不存 prompt/敏感原文，L0-5 审计规约） */
    private String inputDigest;
    /** CHAT 触发的结构化填表载荷（spec §3.5），JSON 字符串 */
    private String fillPayload;
    private String resultSummary;
    private Long aiDocId;
    private String errorMsg;
    /** 被动/对话触发的真人 ID；主动触发 NULL */
    private Long triggeredBy;
    @Version
    private Integer version;
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
