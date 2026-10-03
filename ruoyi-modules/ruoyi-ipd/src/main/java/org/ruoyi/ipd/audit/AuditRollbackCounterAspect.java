package org.ruoyi.ipd.audit;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.ruoyi.ipd.domain.AuditLog;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审计回滚率测量切面（P5 测量件，2026-10-03）——**只测量，不改造**。
 *
 * <p><b>它测什么</b>：在 {@code AuditLogServiceImpl.append(...)} 落库的那一刻，
 * 往<b>宿主业务事务</b>上挂一个探针。宿主事务结束时若回滚，说明这次审计行
 * （已用 {@code REQUIRES_NEW} 独立提交、不可撤销）描述的是一件<b>从未发生</b>的业务，
 * 且哈希链已被它推进。发生频率决定 P5 改造是否值得。
 *
 * <p><b>为什么 {@code @Order} 必须是 {@code HIGHEST_PRECEDENCE + 100}</b>：
 * {@code append} 上的 {@code @Transactional(REQUIRES_NEW)} 由 Spring
 * {@code TransactionInterceptor} 织入，其默认 order 是 {@link Ordered#LOWEST_PRECEDENCE}。
 * 本切面 order 比它小 ⇒ advice 在<b>外层</b>，进入时 {@code REQUIRES_NEW} 尚未挂起宿主事务，
 * 此时 {@code TransactionSynchronizationManager} 里的同步表<b>就是宿主业务事务的</b>。
 * 若顺序反了，探针会挂到审计自己的独立事务上，而那个事务恒提交——测出来的永远是 0，
 * 是「假绿」而不是「回滚率为零」。这个顺序是本测量件唯一的正确性前提。
 *
 * <p><b>同一宿主事务只挂一个探针</b>：一次业务里写多条审计很常见，
 * 重复挂探针会把一次回滚计成多次。判据是「{@code TransactionSynchronizationManager
 * .getSynchronizations()} 返回的列表里是否已有本切面挂的探针」——
 * 该列表是当前线程所绑事务的注册表，随事务开始建立、结束清除，
 * {@code REQUIRES_NEW} 挂起/恢复的正是同一份，所以既不会重复注册，
 * 也不会在连接池线程复用时读到上一个事务的残留探针。
 *
 * <p><b>关闭时的开销</b>：advice 第一行读一个 {@code final boolean} 字段，
 * 为 false 直接 {@code proceed()}。不取栈、不拼字符串、不写任何集合。
 *
 * <p><b>不改变任何行为</b>：本切面不吞异常、不改返回值、不开事务、不加锁。
 * {@code proceed()} 的结果原样返回，异常原样抛出。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AuditRollbackCounterAspect {

    /** 调用方回溯时跳过的框架包（避免把 Spring 代理 / 切面自身当成业务调用方）。 */
    private static final String INFRA_PACKAGE = "org.springframework.";

    private static final String SELF_PACKAGE = "org.ruoyi.ipd.audit.";

    private final AuditRollbackCounter counter;

    public AuditRollbackCounterAspect(AuditRollbackCounter counter) {
        this.counter = counter;
    }

    /**
     * 拦截审计写入的三个形态（{@code append(AuditLog)} 与两个便捷重载）。
     * 便捷重载虽自调用 {@code this.append(AuditLog)}（不经代理），但重载自身入口即被本切面拦到，
     * 探针照样挂上——三个入口都覆盖，不需要动 {@code AuditLogServiceImpl}。
     */
    @Around("execution(* org.ruoyi.ipd.service.IAuditLogService.append(..))")
    public Object aroundAppend(ProceedingJoinPoint pjp) throws Throwable {
        // 关闭：一次字段读 + 分支，除此之外本 advice 什么都不做
        if (!counter.isEnabled()) {
            return pjp.proceed();
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无宿主事务 ⇒ 审计行提交是合法的（没有业务事务可回滚），不构成幽灵审计
            return pjp.proceed();
        }

        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        HostTxProbe probe = findProbe(registered);
        if (probe == null) {
            probe = new HostTxProbe(extractAction(pjp), resolveCaller(), counter);
            TransactionSynchronizationManager.registerSynchronization(probe);
        }

        try {
            Object result = pjp.proceed();
            // append 正常返回 = 审计行已提交，此后才算「本事务内确实写过审计行」
            probe.recordAppendSucceeded();
            return result;
        } catch (Throwable t) {
            // 不下调已有计数：同事务内前几次 append 可能已成功落库，
            // 那几条审计行照样会随宿主回滚成为幽灵记录。
            throw t;
        }
    }

    /** 在本事务已注册的同步里找本切面挂的探针（按类型判断）。 */
    private static HostTxProbe findProbe(List<TransactionSynchronization> registered) {
        for (TransactionSynchronization s : registered) {
            if (s instanceof HostTxProbe p) {
                return p;
            }
        }
        return null;
    }

    /**
     * 从 append 实参里取审计动作码。
     * <ul>
     *   <li>{@code append(AuditLog)}：{@code args[0].getAction()}</li>
     *   <li>{@code append(IpdActor, String action, ...)} / {@code append(Long, String action, ...)}：
     *       {@code args[1]}</li>
     * </ul>
     * 拿不到时退化为 {@code "?"}（计数器侧统一兜底），不抛异常——测量件不得影响业务。
     */
    private static String extractAction(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) {
            return "?";
        }
        if (args[0] instanceof AuditLog draft) {
            return draft.getAction();
        }
        if (args.length > 1 && args[1] instanceof String action) {
            return action;
        }
        return "?";
    }

    /**
     * 回溯调用方 {@code Class#method}，用于「哪个业务最容易回滚」这一维度。
     *
     * <p>只在开关打开时执行（关闭路径到不了这里）。取栈上第一个 {@code org.ruoyi.ipd.*}
     * 的业务帧，跳过本切面所在包与 Spring 包；找不到就退化为 {@code "?"}。
     *
     * <p>注意 {@link StackWalker#walk} 的流<b>只能消费一次</b>，所以类名与方法名必须在
     * 同一次遍历里配对取到，不能先 {@code findFirst} 类、再回头找方法名（那会抛
     * {@code IllegalStateException: stream has already been operated upon or closed}）。
     * 这里用一个两字段的游标一次遍历成型。
     */
    private static String resolveCaller() {
        CallerCursor cursor = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
            .walk(frames -> {
                CallerCursor found = new CallerCursor();
                frames.forEach(frame -> {
                    if (found.found) {
                        return; // 找到第一帧即停
                    }
                    Class<?> c = frame.getDeclaringClass();
                    String n = c.getName();
                    if (n.startsWith("org.ruoyi.ipd")
                        && !n.startsWith(SELF_PACKAGE)
                        && !n.startsWith(INFRA_PACKAGE)) {
                        found.found = true;
                        found.caller = c.getSimpleName() + "#" + frame.getMethodName();
                    }
                });
                return found;
            });
        return cursor.caller;
    }

    /** 单次栈遍历的游标：同时承载「是否命中」与「命中帧的 Class#method」。 */
    private static final class CallerCursor {
        private boolean found;
        private String caller = "?";
    }

    /**
     * 挂在宿主业务事务上的回滚探针。
     *
     * <p>{@code succeededAppends} 统计本事务内<b>成功落库</b>的审计 append 次数。
     * 只在 append 正常返回后自增：append 自己失败时审计行没进库，宿主事务随后回滚
     * <b>不构成</b>幽灵审计（这也是 §3 盲区表之外的一类必须正确排除的情况——
     * 审计落库失败率不低，把它算成幽灵审计会虚高分子）。
     *
     * <p>失败<b>不</b>下调计数：同事务内前几次 append 若已成功，那几条审计行照样会
     * 随宿主回滚留在库里成为幽灵记录。用「成功次数 &gt; 0」而非布尔量即为此。
     *
     * <p>{@code afterCompletion} 在同线程回调（同步事务管理器语义），此处只读 final 字段与
     * 一个原子计数，不做任何可能抛异常的操作——回调里抛异常会被 Spring 吞掉，
     * 那会让计数静默失真，所以这里只做计数，并把异常兜在 try 里记日志。
     */
    static final class HostTxProbe implements TransactionSynchronization {

        private final String action;
        private final String caller;
        private final AuditRollbackCounter counter;
        private final AtomicInteger succeededAppends = new AtomicInteger();

        HostTxProbe(String action, String caller, AuditRollbackCounter counter) {
            this.action = action;
            this.caller = caller;
            this.counter = counter;
        }

        void recordAppendSucceeded() {
            succeededAppends.incrementAndGet();
        }

        @Override
        public void afterCompletion(int status) {
            if (succeededAppends.get() == 0) {
                return; // 本事务内没有成功落库的审计行，回滚不产生幽灵审计
            }
            boolean rolledBack = status == STATUS_ROLLED_BACK;
            try {
                counter.recordHostTxOutcome(action, caller, rolledBack);
            } catch (RuntimeException e) {
                // 测量件绝不能影响业务：计数失败只记日志，不影响已结束的事务
                log.warn("[AUDIT-ROLLBACK-COUNTER] 记录失败 action={} caller={}", action, caller, e);
            }
        }

        @Override
        public int getOrder() {
            // 任意次序均可：本探针只读状态、不参与事务决策
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
