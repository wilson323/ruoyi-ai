package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.PersonSyncJob;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.PersonSyncJobMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 人员同步服务（P2-2.3；AC-USER-11/12；BR-USER-04）。
 *
 * <p>口径：
 * <ul>
 *   <li>AC-USER-11：同步任务三态（PENDING/SUCCESS/FAILED）+ 失败原因记录；
 *       重试次数+下次重试时间（指数退避，最多 3 次）</li>
 *   <li>AC-USER-12：异常项批量回补（admin trigger）— 重试 PENDING/FAILED 中所有未超 maxAttempts 的任务</li>
 * </ul>
 *
 * <p>本卡为 mock 同步（HR API 模拟），接入 HR API 真源在 P2-2.2。同步处理器按 personNo 主键 upsert，
 * 异常分两类：TRANSIENT（网络/超时）→ 重试；PERMANENT（校验失败）→ 不重试。Mock 模式下随机注入 TRANSIENT 错误用于测试重试链。
 *
 * <p>线程安全：内存缓存（ConcurrentHashMap）按 syncJobId 索引，同时 write-through 台账表
 * person_sync_jobs（重启后按 jobId/复合幂等键惰性回读，任务不丢）；每个任务独立 retry 计数。
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class PersonSyncService {

    /** 任务状态。 */
    public enum JobStatus { PENDING, SUCCESS, FAILED, RETRYING }

    /** 异常分类。 */
    public enum FailureKind { TRANSIENT, PERMANENT }

    /** 同步任务视图（HTTP 出参）。 */
    public record SyncJobView(String jobId, String employeeNo, JobStatus status,
                              int attempts, int maxAttempts, String failureKind,
                              String failureReason, Instant nextRetryAt, Instant createdAt,
                              Instant updatedAt) { }

    /** 批量回补结果视图。 */
    public record BatchRetryResult(int retried, int succeeded, int failed, int skipped) { }

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(2);

    private final PersonMapper personMapper;
    private final AuditLogService auditLogService;

    /** 任务台账 Mapper（生产注入；单测两参构造传 null → 纯内存模式）。 */
    private final PersonSyncJobMapper jobMapper;

    /** 任务表：jobId → SyncJob（写穿缓存；重启后台账惰性回读补齐）。 */
    private final ConcurrentHashMap<String, SyncJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong jobSeq = new AtomicLong(0);

    /** 两参构造：单测兼容入口（jobMapper=null，纯内存模式；SEC 场景测试依赖此形态）。 */
    public PersonSyncService(PersonMapper personMapper, AuditLogService auditLogService) {
        this(personMapper, auditLogService, null);
    }

    /** Spring 主构造器（多构造器必须显式标注 @Autowired，否则启动失败——R29 实测教训）。 */
    @Autowired
    public PersonSyncService(PersonMapper personMapper, AuditLogService auditLogService,
                             PersonSyncJobMapper jobMapper) {
        this.personMapper = personMapper;
        this.auditLogService = auditLogService;
        this.jobMapper = jobMapper;
    }

    /**
     * 同步处理器注入点（test 时可替换为 lambda；生产在 P2-2.2 真源切换时由 HR 适配器注入）。
     *
     * <p>SEC 闭环：无默认实现 —— 早期版本提供的 {@code defaultProcess} 直接 {@code personMapper.insert}
     * 新人，绕过 groupId/HR 授权即把 account_status 置 ACTIVE，是 P2-2.3 上线初期的默认 bypass。
     * 删除后 {@link #attempt} 在 {@code processor==null} 时显式抛 {@link IllegalStateException}，
     * 拒绝任何「未配置就误开绿灯」的路径。dev 环境由 MockHrAdapter（@Profile("dev")）注入；
     * 生产在 P2-2.2 真源切换时由真 HR 适配器经 {@code @Autowired(required=false)} 按类型自动装配注入。
     */
    @Autowired(required = false)
    private volatile SyncProcessor processor;

    /**
     * 提交一个同步任务（幂等键 = (operatorId, groupId, idempotencyKey) 复合键；同复合键重放返原 jobId）。
     *
     * <p>P2-2.1 复用：若同 (operatorId, groupId, idempotencyKey) 已存在 ⇒ 直接返原 jobId 不创建新任务。
     * 不同 operator 或 group ⇒ 即使原始 idempotencyKey 相同也创建独立任务（隔离不同主体的命名空间，
     * 防止「admin 用同 key 重放覆盖组长重试进度」之类的串号）。
     */
    public SyncJob submit(String employeeNo, String idempotencyKey, IpdActor operator) {
        SyncJob replay = findIdempotentReplay(idempotencyKey, operator);
        if (replay != null) {
            log.info("P2-2.1 idempotent replay: key={} operatorId={} groupId={} → jobId={}",
                maskKey(idempotencyKey), operator.id(), operator.groupId(), replay.jobId);
            return replay;
        }
        String jobId = "sync-" + UUID.randomUUID().toString().substring(0, 8) + "-" + jobSeq.incrementAndGet();
        SyncJob job = new SyncJob(jobId, employeeNo, idempotencyKey,
            JobStatus.PENDING, 0, DEFAULT_MAX_ATTEMPTS, null, null,
            Instant.now(), Instant.now(), operator.id(), operator.groupId());
        jobs.put(jobId, job);
        insertRow(job);
        auditLogService.append(AuditLog.builder()
            .entityType("person_sync_jobs")
            .action("SUBMIT")
            .operatorId(operator.id())
            .beforeData(null)
            .afterData(AuditEventData.json(
                "jobId", jobId,
                "employeeNo", employeeNo,
                "idempotencyKey", idempotencyKey == null ? "null" : maskKey(idempotencyKey),
                "operatorId", operator.id() == null ? "null" : operator.id(),
                "groupId", operator.groupId() == null ? "null" : operator.groupId()))
            .build());
        // 立即同步尝试（mock 同步为同步路径；真源切换为异步 @Async）
        attempt(job);
        return job;
    }

    /**
     * 单任务手动重试（AC-USER-12；用于 admin 详情页"立即重试"按钮）。
     *
     * <p>守卫：status == FAILED && attempts < maxAttempts；否则返 422。
     */
    public SyncJob retry(String jobId, IpdActor operator) {
        SyncJob job = require(jobId);
        if (job.status != JobStatus.FAILED) {
            throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT,
                "仅 FAILED 任务可手动重试: " + job.status);
        }
        if (job.attempts >= job.maxAttempts) {
            throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT,
                "已达最大重试次数: " + job.maxAttempts);
        }
        attempt(job);
        auditLogService.append(AuditLog.builder()
            .entityType("person_sync_jobs")
            .action("MANUAL_RETRY")
            .operatorId(operator.id())
            .afterData(AuditEventData.json(
                "jobId", jobId,
                "attempts", job.attempts))
            .build());
        return job;
    }

    /**
     * 批量回补（P2-2.3 核心）：重试所有 PENDING/FAILED 中未超 maxAttempts 的任务。
     *
     * <p>跳过规则：status == SUCCESS（已完成） / status == RETRYING（其他流正在重试） / attempts >= maxAttempts（已耗尽）。
     *
     * @return 统计（重试数/成功数/失败数/跳过数）
     */
    public BatchRetryResult retryAll(IpdActor operator) {
        int retried = 0, succeeded = 0, failed = 0, skipped = 0;
        for (SyncJob job : listAll()) {
            if (job.status == JobStatus.SUCCESS || job.status == JobStatus.RETRYING) {
                skipped++;
                continue;
            }
            if (job.attempts >= job.maxAttempts) {
                skipped++;
                continue;
            }
            retried++;
            attempt(job);
            if (job.status == JobStatus.SUCCESS) {
                succeeded++;
            } else if (job.status == JobStatus.FAILED) {
                failed++;
            }
        }
        auditLogService.append(AuditLog.builder()
            .entityType("person_sync_jobs")
            .action("BATCH_RETRY")
            .operatorId(operator.id())
            .afterData(AuditEventData.json(
                "retried", retried,
                "succeeded", succeeded,
                "failed", failed,
                "skipped", skipped))
            .build());
        return new BatchRetryResult(retried, succeeded, failed, skipped);
    }

    /** 查询任务详情。 */
    public SyncJob get(String jobId) {
        return require(jobId);
    }

    /** 列出所有任务（按 createdAt 倒序；DB 模式先惰性回读台账补齐重启后缺失的缓存）。 */
    public List<SyncJob> listAll() {
        if (jobMapper != null) {
            for (PersonSyncJob row : jobMapper.selectList(new LambdaQueryWrapper<PersonSyncJob>()
                .orderByDesc(PersonSyncJob::getCreatedAt))) {
                jobs.computeIfAbsent(row.getJobId(), k -> fromRow(row));
            }
        }
        List<SyncJob> all = new ArrayList<>(jobs.values());
        all.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return Collections.unmodifiableList(all);
    }

    /** 列出指定状态的异常项（admin 异常项视图）。 */
    public List<SyncJob> listAbnormal() {
        return listAll().stream()
            .filter(j -> j.status == JobStatus.FAILED)
            .toList();
    }

    /**
     * 测试注入点：替换同步处理器（仅用于单测）。
     *
     * <p>package-private（无 {@code public}）+ Javadoc {@code @VisibleForTesting} 约定：
     * 不暴露给 Controller/Service 调用方，避免运行时任意覆盖处理路径。生产路径必须由 P2-2.2 的
     * HR 适配器配置 bean 注入。
     */
    // VisibleForTesting: 同包单测可见，禁止 Controller/Service 通过反射调用
    void setProcessor(SyncProcessor processor) {
        this.processor = processor;
    }

    /**
     * 单次同步尝试：递增 attempts + 处理 + 更新状态。
     *
     * <p>SEC 闭环：{@code synchronized(job)} 串行化同一任务上的所有重试入口（submit/retry/retryAll
     * 三入口并发调用同一 job），避免「concurrent retryAll + manual retry」同时进入 attempt 导致：
     * (1) attempts 双增、状态双写；(2) 同一 job 在两次并发 attempt 末尾各自写一条 MANUAL_RETRY /
     * SUBMIT 之外的审计；(3) processor 抛 SyncFailureException 后 catch 块被另一 attempt 的
     * 写入覆盖。锁粒度 = 单 job，不阻塞其他 job 并发处理。
     */
    private void attempt(SyncJob job) {
        synchronized (job) {
            if (processor == null) {
                // HIGH defaultProcess-bypass-validation 闭环：未配置 processor 拒绝静默直插。
                // 早期 defaultProcess 直走 personMapper.insert + accountStatus=ACTIVE 绕过 groupId/HR
                // 授权，是 P2-2.3 上线初期的默认 bypass；现在强制显式注入。
                throw new IllegalStateException(
                    "PersonSyncService.processor 未配置；请由 P2-2.2 HR 适配器配置 bean 注入（或单测 setProcessor）");
            }
            job.attempts++;
            job.updatedAt = Instant.now();
            try {
                processor.process(job);
                job.status = JobStatus.SUCCESS;
                job.failureKind = null;
                job.failureReason = null;
                job.nextRetryAt = null;
                log.info("P2-2.3 sync OK: jobId={} attempts={}", job.jobId, job.attempts);
            } catch (SyncFailureException ex) {
                job.failureKind = ex.kind;
                job.failureReason = ex.getMessage();
                if (ex.kind == FailureKind.PERMANENT || job.attempts >= job.maxAttempts) {
                    job.status = JobStatus.FAILED;
                    job.nextRetryAt = null;
                } else {
                    // TRANSIENT + 还有重试余量 ⇒ 进 RETRYING 态并设下次重试时间（指数退避）
                    job.status = JobStatus.RETRYING;
                    job.nextRetryAt = Instant.now().plus(BASE_BACKOFF.multipliedBy(1L << (job.attempts - 1)));
                }
                log.warn("P2-2.3 sync FAIL: jobId={} kind={} reason={} nextRetry={}",
                    job.jobId, ex.kind, ex.getMessage(), job.nextRetryAt);
            }
            persistRow(job);
        }
    }

    // ============================================================
    // 台账落库（P2-2.3 落库改造；jobMapper==null 时为单测内存模式，全部跳过）
    // ============================================================

    /** P2-2.1 幂等重放查找：先扫内存缓存，未命中且台账在位时按复合键查库并回填缓存。 */
    private SyncJob findIdempotentReplay(String idempotencyKey, IpdActor operator) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        for (SyncJob existing : jobs.values()) {
            if (idempotencyKey.equals(existing.idempotencyKey)
                && java.util.Objects.equals(existing.operatorId, operator.id())
                && java.util.Objects.equals(existing.groupId, operator.groupId())) {
                return existing;
            }
        }
        if (jobMapper == null) {
            return null;
        }
        PersonSyncJob row = jobMapper.selectOne(new LambdaQueryWrapper<PersonSyncJob>()
            .eq(PersonSyncJob::getIdempotencyKey, idempotencyKey)
            .eq(PersonSyncJob::getOperatorId, operator.id())
            .eq(operator.groupId() != null, PersonSyncJob::getGroupId, operator.groupId())
            .isNull(operator.groupId() == null, PersonSyncJob::getGroupId)
            .last("LIMIT 1"));
        if (row == null) {
            return null;
        }
        SyncJob loaded = fromRow(row);
        jobs.put(loaded.jobId, loaded);
        return loaded;
    }

    /** submit 时写入台账（write-through 初始行）。 */
    private void insertRow(SyncJob job) {
        if (jobMapper == null) {
            return;
        }
        jobMapper.insert(toRow(job));
    }

    /** attempt 状态变更后回写台账（显式 set 全字段，覆盖 failure 字段清空的语义）。 */
    private void persistRow(SyncJob job) {
        if (jobMapper == null) {
            return;
        }
        jobMapper.update(null, new LambdaUpdateWrapper<PersonSyncJob>()
            .eq(PersonSyncJob::getJobId, job.jobId)
            .set(PersonSyncJob::getStatus, job.status.name())
            .set(PersonSyncJob::getAttempts, job.attempts)
            .set(PersonSyncJob::getFailureKind, job.failureKind == null ? null : job.failureKind.name())
            .set(PersonSyncJob::getFailureReason, job.failureReason)
            .set(PersonSyncJob::getNextRetryAt, job.nextRetryAt == null ? null : Date.from(job.nextRetryAt))
            .set(PersonSyncJob::getUpdatedAt, Date.from(job.updatedAt)));
    }

    private PersonSyncJob toRow(SyncJob j) {
        return PersonSyncJob.builder()
            .jobId(j.jobId)
            .employeeNo(j.employeeNo)
            .idempotencyKey(j.idempotencyKey)
            .operatorId(j.operatorId)
            .groupId(j.groupId)
            .status(j.status.name())
            .attempts(j.attempts)
            .maxAttempts(j.maxAttempts)
            .failureKind(j.failureKind == null ? null : j.failureKind.name())
            .failureReason(j.failureReason)
            .nextRetryAt(j.nextRetryAt == null ? null : Date.from(j.nextRetryAt))
            .createdAt(Date.from(j.createdAt))
            .updatedAt(Date.from(j.updatedAt))
            .build();
    }

    private SyncJob fromRow(PersonSyncJob r) {
        return new SyncJob(r.getJobId(), r.getEmployeeNo(), r.getIdempotencyKey(),
            JobStatus.valueOf(r.getStatus()), r.getAttempts(), r.getMaxAttempts(),
            r.getFailureKind() == null ? null : FailureKind.valueOf(r.getFailureKind()),
            r.getFailureReason(),
            r.getCreatedAt() == null ? Instant.now() : r.getCreatedAt().toInstant(),
            r.getUpdatedAt() == null ? Instant.now() : r.getUpdatedAt().toInstant(),
            r.getOperatorId(), r.getGroupId());
    }

    private SyncJob require(String jobId) {
        SyncJob job = jobs.get(jobId);
        if (job == null && jobMapper != null) {
            // 重启后内存缓存为空：按 jobId 从台账惰性回读并回填缓存
            PersonSyncJob row = jobMapper.selectOne(new LambdaQueryWrapper<PersonSyncJob>()
                .eq(PersonSyncJob::getJobId, jobId)
                .last("LIMIT 1"));
            if (row != null) {
                job = fromRow(row);
                jobs.put(job.jobId, job);
            }
        }
        if (job == null) {
            throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.NOT_FOUND, "同步任务不存在: " + jobId);
        }
        return job;
    }

    /**
     * 脱敏：幂等键在日志/审计中只显示前 16 字符 + ...，避免整段业务数据（含可逆 token 痕迹）
     * 进入可观测链路。null/空串原样透传。
     */
    private static String maskKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        return key.length() <= 16 ? key : key.substring(0, 16) + "...";
    }

    /** 任务内部结构（线程安全字段均为 final/Atomic）。 */
    public static final class SyncJob {
        public final String jobId;
        public final String employeeNo;
        public final String idempotencyKey;
        /** 提交者 ID（与 groupId 共同构成复合幂等键，隔离不同主体的命名空间）。 */
        public final Long operatorId;
        /** 提交者所属组 ID（admin=NULL；组长=组长组 ID；同 admin 跨组也会被隔离）。 */
        public final Long groupId;
        public volatile JobStatus status;
        public volatile int attempts;
        public final int maxAttempts;
        public volatile FailureKind failureKind;
        public volatile String failureReason;
        public volatile Instant nextRetryAt;
        public final Instant createdAt;
        public volatile Instant updatedAt;

        SyncJob(String jobId, String employeeNo, String idempotencyKey,
                JobStatus status, int attempts, int maxAttempts,
                FailureKind failureKind, String failureReason,
                Instant createdAt, Instant updatedAt,
                Long operatorId, Long groupId) {
            this.jobId = jobId;
            this.employeeNo = employeeNo;
            this.idempotencyKey = idempotencyKey;
            this.operatorId = operatorId;
            this.groupId = groupId;
            this.status = status;
            this.attempts = attempts;
            this.maxAttempts = maxAttempts;
            this.failureKind = failureKind;
            this.failureReason = failureReason;
            this.nextRetryAt = null;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    /** 同步处理器接口（test 用 lambda 注入失败）。 */
    @FunctionalInterface
    public interface SyncProcessor {
        void process(SyncJob job);
    }

    /** 同步失败异常（自定义；外层捕获后置状态）。 */
    public static class SyncFailureException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final FailureKind kind;
        public SyncFailureException(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    /** 视图转 SyncJobView。 */
    public static SyncJobView toView(SyncJob j) {
        return new SyncJobView(j.jobId, j.employeeNo, j.status, j.attempts, j.maxAttempts,
            j.failureKind == null ? null : j.failureKind.name(), j.failureReason,
            j.nextRetryAt, j.createdAt, j.updatedAt);
    }
}
