package org.ruoyi.ipd.workbench.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * 我发起 / 我审批聚合任务卡（R27 P0-6：Workbench 路径 myInitiated + myPendingApprovals）。
 *
 * <p>三种业务单据（deletion_requests / coefficient_change_requests / launch_date_change_requests）
 * + 阶段动作（stage_actions）的统一视图卡。WorkbenchService.myInitiated / myPendingApprovals 返回该类型，
 * 前端按 taskType 渲染「我发起的 / 待我审批」两个分组。
 *
 * <p>本类是查询结果 VO 投影：保留 Domain 形态（@TableName + Serializable）以便未来若需要做单表持久化时
 * 直接复用；当前业务是三表 + 阶段动作的聚合视图，实际写入仍走各业务单据表，不写本表。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code id}：业务单据/动作主键（sourceId 同值）</li>
 *   <li>{@code taskType}：DELETION_REQUEST / COEFFICIENT_CHANGE / LAUNCH_DATE_CHANGE / STAGE_ACTION</li>
 *   <li>{@code sourceId}：业务单据/动作主键（同 id）</li>
 *   <li>{@code sourceTable}：deletion_requests / coefficient_change_requests / launch_date_change_requests / stage_actions</li>
 *   <li>{@code title}：业务单据/动作的展示标题</li>
 *   <li>{@code status}：业务单据/动作的状态</li>
 *   <li>{@code initiatorId}：发起人 personId（myInitiated 模式）</li>
 *   <li>{@code approverId}：当前待审批人 personId（myPendingApprovals 模式）</li>
 *   <li>{@code createdAt}：业务单据/动作的创建时间</li>
 * </ul>
 *
 * <p>@author R177-A2 agent 2026-09-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName(value = "my_initiated_task_view", autoResultMap = true)
public class MyInitiatedTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 视图卡 ID（与 sourceId 一致；本字段仅供 MyBatis Plus 元数据需要，无物理表） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 任务类型：DELETION_REQUEST / COEFFICIENT_CHANGE / LAUNCH_DATE_CHANGE / STAGE_ACTION */
    @TableField("task_type")
    private String taskType;

    /** 业务单据/动作的主键（同 id） */
    @TableField("source_id")
    private Long sourceId;

    /** 来源表名 */
    @TableField("source_table")
    private String sourceTable;

    /** 展示标题（业务单据的标题/动作的名称） */
    @TableField("title")
    private String title;

    /** 业务单据/动作的状态 */
    @TableField("status")
    private String status;

    /** 发起人 personId（myInitiated 模式=当前查询人；myPendingApprovals 模式=单据的发起人） */
    @TableField("initiator_id")
    private Long initiatorId;

    /** 当前待审批人 personId（仅 myPendingApprovals 模式填值） */
    @TableField("approver_id")
    private Long approverId;

    /** 业务单据/动作的创建时间 */
    @TableField("created_at")
    private Date createdAt;
}
