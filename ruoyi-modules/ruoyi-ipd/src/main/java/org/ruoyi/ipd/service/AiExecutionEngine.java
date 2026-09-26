package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.aiexec.AiActionExecutor;
import org.ruoyi.ipd.service.aiexec.AiExecContext;
import org.ruoyi.ipd.service.aiexec.AiExecResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * R221 AI 执行引擎（spec §1/§3.4）：抢占（条件 UPDATE 乐观守卫，AsyncNotificationDispatcher 先例）
 * → 按 actionCode 路由 executor → 收尾（SUCCEEDED / FAILED 退避 30s·2m·10m / 3 次后 DEAD 转人工）。
 * 串行执行（Global Constraint 5：AiGenerationService Semaphore(3) 全局限流）。
 * 审计收口在本类（AuditEventData 同包可见，Global Constraint 7）。
 */
@Slf4j
@Service
public class AiExecutionEngine {

    /** 系统身份（spec §4.2：AI_SYSTEM_PERSON_ID=0，GateSignScanScheduler SYSTEM_ACTOR 先例） */
    static final IpdActor SYSTEM_ACTOR = new IpdActor(0L, "system", "SYSTEM", null);
    static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_SECONDS = {30L, 120L, 600L};

    private final AiAgentTaskMapper taskMapper;
    private final Map<String, AiActionExecutor> executorByCode;
    private final IAuditLogService auditLogService;
    private final NotificationService notificationService;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-exec-engine");
        t.setDaemon(true);
        return t;
    });
    private Clock clock = Clock.systemDefaultZone();

    /**
     * self 代理：使 {@link #finalizeTask} 的 @Transactional 真正生效——runOne 由池线程以 raw this 调用，
     * 直接 this.finalizeTask() 属 self-invocation 会绕过 Spring 代理导致注解失效（#2）。
     * 单测无 Spring 上下文时 self 为 null，回退 this（mock mapper 不需事务）。
     */
    @Lazy
    @Autowired
    private AiExecutionEngine self;

    public AiExecutionEngine(AiAgentTaskMapper taskMapper, List<AiActionExecutor> executors,
                             IAuditLogService auditLogService, NotificationService notificationService) {
        this.taskMapper = taskMapper;
        this.executorByCode = executors.stream()
            .flatMap(e -> e.supportedActionCodes().stream().map(c -> Map.entry(c, e)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    public void withClock(Clock clock) { this.clock = clock; }
    public void setClock(Clock clock) { this.clock = clock; }

    public void dispatchAsync() {
        try {
            pool.submit(() -> {
                try { dispatchCycle(50); } catch (Exception e) { log.warn("[R221] 派发轮异常（不炸主链）", e); }
            });
        } catch (Exception e) {
            log.warn("[R221] 线程池提交失败，等兜底扫描器", e);
        }
    }

    /** 扫 PENDING + 到期 FAILED → 逐个抢占执行（串行）。返回成功处理数。 */
    public int dispatchCycle(int limit) {
        Date now = Date.from(clock.instant());
        List<AiAgentTask> due = taskMapper.selectList(new LambdaQueryWrapper<AiAgentTask>()
            .and(w -> w.eq(AiAgentTask::getStatus, AiAgentTask.STATUS_PENDING)
                .or(o -> o.eq(AiAgentTask::getStatus, AiAgentTask.STATUS_FAILED)
                    .and(x -> x.isNull(AiAgentTask::getNextRetryAt).or().le(AiAgentTask::getNextRetryAt, now))))
            .orderByAsc(AiAgentTask::getId)
            .last("limit " + Math.max(1, Math.min(limit, 200))));
        int done = 0;
        for (AiAgentTask t : due) {
            try {
                if (claim(t) && runOne(t.getId())) { done++; }
            } catch (Exception e) {
                log.warn("[R221] 单任务失败不阻断本轮: id={}", t.getId(), e);
            }
        }
        return done;
    }

    /** 条件 UPDATE 抢占：PENDING/FAILED → RUNNING（多实例安全，outbox 先例） */
    private boolean claim(AiAgentTask t) {
        int n = taskMapper.update(null, Wrappers.<AiAgentTask>lambdaUpdate()
            .eq(AiAgentTask::getId, t.getId())
            .in(AiAgentTask::getStatus, AiAgentTask.STATUS_PENDING, AiAgentTask.STATUS_FAILED)
            .set(AiAgentTask::getStatus, AiAgentTask.STATUS_RUNNING)
            .set(AiAgentTask::getUpdateTime, Date.from(clock.instant())));
        if (n == 1) {
            // 抢占成功后同步本地对象状态，与刚写库的 RUNNING 一致（防 stale 内存态；
            // 生产 runOne 走 selectById 新读本就是 RUNNING，此行为纯本地一致性防御）。
            t.setStatus(AiAgentTask.STATUS_RUNNING);
        }
        return n == 1;
    }

    boolean runOne(Long taskId) {
        AiAgentTask t = taskMapper.selectById(taskId);
        if (t == null || !AiAgentTask.STATUS_RUNNING.equals(t.getStatus())) { return false; }
        AiActionExecutor executor = executorByCode.get(t.getActionCode());
        AiExecResult result;
        if (executor == null) {
            result = AiExecResult.fail("无已接线执行器: " + t.getActionCode() + "（分批接线期，spec 附录B）");
        } else {
            try {
                result = executor.execute(t, new AiExecContext(SYSTEM_ACTOR, clock));
            } catch (Exception e) {
                log.warn("[R221] 执行器异常: task={} code={}", taskId, t.getActionCode(), e);
                result = AiExecResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        // 收尾恰好一次（经 self 代理使 @Transactional 生效）：无论执行器成功/失败/无执行器，
        // 都只走一次 finalizeTask，杜绝旧实现「publish 抛异常被 runOne catch 触发二次收尾」（#2）。
        (self != null ? self : this).finalizeTask(t, result, null);
        // DEAD 转人工通知放在事务外、尽力而为：通知失败不回滚已落库的 DEAD 状态（#3）。
        notifyIfDead(t);
        return result.ok();
    }

    /** 收尾：成功翻 SUCCEEDED；失败 attempt+1，<3 翻 FAILED 定退避，>=3 翻 DEAD。事务内仅 updateById + audit（原子）。 */
    @Transactional(rollbackFor = Exception.class)
    public void finalizeTask(AiAgentTask t, AiExecResult result, String unused) {
        boolean ok = result != null && result.ok();
        if (ok) {
            t.setStatus(AiAgentTask.STATUS_SUCCEEDED);
            t.setResultSummary(truncate(result.summary(), 1024));
            if (result.aiDocId() != null) { t.setAiDocId(result.aiDocId()); }
            t.setErrorMsg(null);
            t.setNextRetryAt(null);
        } else {
            int attempt = (t.getAttempt() == null ? 0 : t.getAttempt()) + 1;
            t.setAttempt(attempt);
            t.setErrorMsg(truncate(result == null ? "unknown" : result.errorMsg(), 512));
            if (attempt >= MAX_ATTEMPTS) {
                t.setStatus(AiAgentTask.STATUS_DEAD);
            } else {
                t.setStatus(AiAgentTask.STATUS_FAILED);
                t.setNextRetryAt(Date.from(clock.instant().plus(BACKOFF_SECONDS[attempt - 1], ChronoUnit.SECONDS)));
            }
        }
        t.setUpdateBy(0L);
        taskMapper.updateById(t);
        // AI 留痕三件套走 AuditEventData 载荷通道（照 AiGenerationService:168 范式）：
        // aiRole=agent_exec 已在 Task 1 白名单化，requireAiTrail 校验通过。
        auditLogService.append(AuditLog.builder()
            .operatorId(0L).operatorName("0").operatorRole("SYSTEM")
            .action(ok ? "AI_EXEC" : "AI_EXEC_FAILED")
            .entityType("ai_agent_task").entityId(t.getId())
            .beforeData(AuditEventData.json("status", "RUNNING", "attempt", t.getAttempt()))
            .afterData(AuditEventData.json(
                "aiAssisted", true,
                "aiModel", "engine",
                "aiRole", "agent_exec",
                "status", t.getStatus(),
                "summary", t.getResultSummary(),
                "error", t.getErrorMsg(),
                "aiDocId", t.getAiDocId()))
            .reason("R221 AI 代理执行闭环")
            .build());
    }

    /**
     * DEAD 转人工通知（事务外、尽力而为）：
     * 主动触发（EVENT/SCHEDULE）triggeredBy=null 时无接收人，跳过推送由审计+看板兜底，
     * 避免旧实现 publish(null) 触发 doPublish 的 requireArg 抛异常（#3）；推送失败只 WARN 不外泄。
     */
    private void notifyIfDead(AiAgentTask t) {
        if (!AiAgentTask.STATUS_DEAD.equals(t.getStatus())) { return; }
        Long receiver = t.getTriggeredBy();
        if (receiver == null) {
            log.warn("[R221] DEAD 任务无接收人（主动触发 triggeredBy=null），跳过推送，已由审计+看板兜底: task={} code={}",
                t.getId(), t.getActionCode());
            return;
        }
        try {
            notificationService.publish(receiver, "AI_EXEC_DEAD", NotificationService.KIND_ACTION,
                "ai_agent_task", t.getId(),
                "AI 执行失败转人工: " + t.getActionCode(),
                "任务已重试 " + t.getAttempt() + " 次仍失败，请人工接管（原手工路径不受影响）。错误: "
                    + truncate(t.getErrorMsg(), 200),
                null);
        } catch (Exception e) {
            log.warn("[R221] DEAD 通知推送失败（不回滚状态，审计已留痕）: task={}", t.getId(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) { return null; }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
