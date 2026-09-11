package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 人员同步任务台账（P2-2.3 落库；AC-USER-11/12）。
 *
 * <p>此前 PersonSyncService 任务表为内存 ConcurrentHashMap，后端重启即丢全部同步任务。
 * 本实体承接落库：submit 时写入，attempt 终态变更时回写（write-through），重启后按 jobId
 * 惰性回读到服务内存缓存。业务字段与 {@code PersonSyncService.SyncJob} 一一对应。
 *
 * <p>注意：tenant_id / del_flag 列由 DB 默认值兜底（单企业私有部署无多租户语义，已登记
 * tenant.excludes），实体不映射，避免 MyBatis-Plus 全列 SELECT 依赖非业务列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "person_sync_jobs")
public class PersonSyncJob {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("job_id")
    private String jobId;

    @TableField("employee_no")
    private String employeeNo;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("operator_id")
    private Long operatorId;

    @TableField("group_id")
    private Long groupId;

    /** PENDING|SUCCESS|FAILED|RETRYING（对齐 PersonSyncService.JobStatus） */
    @TableField("status")
    private String status;

    @TableField("attempts")
    private Integer attempts;

    @TableField("max_attempts")
    private Integer maxAttempts;

    /** TRANSIENT|PERMANENT（对齐 PersonSyncService.FailureKind） */
    @TableField("failure_kind")
    private String failureKind;

    @TableField("failure_reason")
    private String failureReason;

    @TableField("next_retry_at")
    private Date nextRetryAt;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
