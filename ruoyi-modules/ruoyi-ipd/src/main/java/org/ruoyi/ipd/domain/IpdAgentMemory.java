package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * IPD 智能体长期记忆候选区（官方 {@code LongTermMemory.record()} 的落库载体）。
 *
 * <p>DDL: {@code docs/script/sql/update/20261002-ipd-agent-memory.sql}；
 * 租户按 {@code project_id} 隔离，登记进 {@code tenant.excludes}（记录走异步调度，无会话上下文）。
 *
 * <p><b>权威性红线</b>：本表<b>不是业务权威</b>。依据 docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md §2 规则3「记忆不得自动成为业务权威」：
 * 召回文本一律显式标注为「非权威个人工作笔记」；IPD 的权限、动作审批、文档审核与 Gate 链路
 * <b>一律不查本表</b>。{@code status=PROMOTED} 只表示该条已并入权威知识库，
 * 它的作用是让召回文本能区分「已沉淀」与「仍是草稿」，而不是让记忆获得审批权。
 *
 * <p>作用域为 {@code projectId + personId}（个人记忆，不同人之间不互相召回）。
 * {@code runId} 仅供追溯，不参与隔离——它进键会让「同一人的第二次会话记不住第一次」而使记忆失效。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ipd_agent_memory", autoResultMap = true)
public class IpdAgentMemory extends BaseEntity {

    /** 候选：可召回，但召回文本带非权威标注。 */
    public static final String STATUS_CANDIDATE = "0";
    /** 已晋升并入权威知识库。 */
    public static final String STATUS_PROMOTED = "1";
    /** 废弃：不再召回。 */
    public static final String STATUS_DISCARDED = "2";

    public static final String KIND_PREFERENCE = "PREFERENCE";
    public static final String KIND_FACT = "FACT";
    public static final String KIND_OBSERVATION = "OBSERVATION";

    /** 召回条数上限：记忆是便利层，不是上下文主体，不得挤占业务事实。 */
    public static final int RECALL_LIMIT = 8;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;
    private Long personId;

    /** 来源运行，仅追溯；不参与隔离。 */
    private Long runId;

    private String kind;

    /** 记忆正文（已脱敏、非权威）。 */
    private String content;

    /** 来源内容 SHA-256，作用域内去重与幂等。 */
    private String sourceDigest;

    private String status;

    /** 框架租户列（手写 SQL 引用；租户隔离按 project_id，登记 tenant.excludes，此处恒 null）。 */
    private String tenantId;

    /** 逻辑删除标记（手写 SQL 引用；插入时未显式设值即 null，库默认 '0'）。 */
    private String delFlag;

    /** 备注（手写 SQL 引用，恒 null）。 */
    private String remark;

    /** 该条是否为可召回态（候选与已晋升都可召回，废弃不可）。 */
    public boolean isRecallable() {
        return STATUS_CANDIDATE.equals(status) || STATUS_PROMOTED.equals(status);
    }
}
