package org.ruoyi.ipd.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 审计幽灵计数器（P5 测量件，2026-10-03）——**只测量，不改造**。
 *
 * <p>要回答的问题只有一个：「业务事务回滚了、但审计行已经用 {@code REQUIRES_NEW} 提交进库」
 * 这种状态到底多久发生一次。这个频率决定 P5 审计时序改造（{@code appendAfterCommit}）
 * 值不值得做——若接近 0，让 131 处调用点的测试语义全部翻转就不划算。
 *
 * <p><b>为什么能测到</b>：探针在审计 append 发生的那一刻注册到<b>宿主业务事务</b>的
 * {@code TransactionSynchronization} 上——不是注册到 append 自己的独立事务上
 * （那只会回答「审计事务自己有没有回滚」，恒为否）。宿主事务结束时
 * {@code afterCompletion(status)} 明确给出 {@code STATUS_ROLLED_BACK}。
 * 注册时机与切面顺序见 {@link AuditRollbackCounterAspect}。
 *
 * <p><b>开关</b>：{@code ipd.audit.rollback-counter.enabled}，默认 <b>false</b>。
 * 该键未在任何 yml 中声明，走 {@code @Value} 默认值关闭。
 * 关闭时切面 advice 只做一次字段读 + 分支即 {@code proceed()}，不做栈回溯、
 * 不做字符串拼接、不写任何集合。
 *
 * <p><b>本类不引入任何事务行为</b>：纯内存 {@link LongAdder} 计数，无 DB、无
 * {@code REQUIRES_NEW}、无异步。进程重启计数归零——这是测量件不是审计件，故意不做持久化。
 *
 * <p><b>测不到的边界（本计数器是下界，不是精确值）</b>：
 * <ul>
 *   <li>内层 {@code REQUIRES_NEW} 业务子事务回滚、而外层事务提交 → 外层探针只看到
 *       {@code COMMITTED}，该幽灵审计不计数；</li>
 *   <li>无宿主事务的 append（如 Controller 直调且无 {@code @Transactional}）→ 不注册探针，
 *       审计行提交是合法的（没有业务事务可回滚），正确不计数；</li>
 *   <li>绕过 Spring 代理直接 {@code new AuditLogServiceImpl(...)} → 切面看不到，不计数。</li>
 * </ul>
 */
@Slf4j
@Component
public class AuditRollbackCounter {

    /** 开关。默认关闭。 */
    private final boolean enabled;

    /** 观察到「宿主事务内成功写过审计行」的事务数（分母，含提交与回滚）。 */
    private final LongAdder hostTxWithAudit = new LongAdder();

    /** 其中宿主事务回滚的次数 —— 即「幽灵审计」发生次数（分子）。 */
    private final LongAdder hostTxRolledBack = new LongAdder();

    /** 其中宿主事务提交成功的次数（对照组，避免只看分子误判）。 */
    private final LongAdder hostTxCommitted = new LongAdder();

    /** 按审计 action 类型分维度：action → 回滚次数。 */
    private final Map<String, LongAdder> byAction = new ConcurrentHashMap<>();

    /** 按调用方（控制器/服务方法 Class#method）分维度。 */
    private final Map<String, LongAdder> byCaller = new ConcurrentHashMap<>();

    public AuditRollbackCounter(
            @Value("${ipd.audit.rollback-counter.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            log.info("[AUDIT-ROLLBACK-COUNTER] 已开启：将统计「业务回滚但审计已落库」的发生次数");
        }
    }

    /** 开关是否打开。切面 advice 第一行读它，关闭时后续全部短路。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 记一次「宿主事务结束」的结果。由探针在 {@code afterCompletion} 中调用一次，
     * 三个计数器在同一次调用里更新——避免拆成多个方法后分母与分子读到不一致的中间态。
     *
     * @param action     该事务内首次成功写入的审计动作码（可空，退化为 {@code "?"}）
     * @param caller     调用方标识 {@code Class#method}（可空，退化为 {@code "?"}）
     * @param rolledBack 宿主事务是否回滚；true 即「幽灵审计」发生
     */
    public void recordHostTxOutcome(String action, String caller, boolean rolledBack) {
        hostTxWithAudit.increment();
        if (rolledBack) {
            hostTxRolledBack.increment();
            byAction.computeIfAbsent(nullToUnknown(action), k -> new LongAdder()).increment();
            byCaller.computeIfAbsent(nullToUnknown(caller), k -> new LongAdder()).increment();
        } else {
            hostTxCommitted.increment();
        }
    }

    /**
     * 读取当前统计快照（只读，无副作用）。
     *
     * @return 快照；{@code rate} 为 {@code 回滚数 / 观察数}，观察数为 0 时为 0.0
     */
    public AuditRollbackSnapshot snapshot() {
        long observed = hostTxWithAudit.sum();
        long rolledBack = hostTxRolledBack.sum();
        double rate = observed == 0 ? 0.0d : (double) rolledBack / (double) observed;
        return new AuditRollbackSnapshot(
            enabled, observed, rolledBack, hostTxCommitted.sum(), rate,
            freeze(byAction), freeze(byCaller));
    }

    /** 清空计数（仅供测试与人工重置；不暴露带鉴权的写端点，避免测量件变成业务可触发的写接口）。 */
    public void clear() {
        hostTxWithAudit.reset();
        hostTxRolledBack.reset();
        hostTxCommitted.reset();
        byAction.clear();
        byCaller.clear();
    }

    private static Map<String, Long> freeze(Map<String, LongAdder> source) {
        Map<String, Long> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k, v.sum()));
        return copy;
    }

    private static String nullToUnknown(String v) {
        return (v == null || v.isEmpty()) ? "?" : v;
    }

    /**
     * 统计快照。
     *
     * @param enabled           开关状态；false 意味着下面全是 0，<b>不代表真实回滚率为 0</b>
     * @param observedHostTxCount 观察到「宿主事务内成功写过审计」的事务数（分母）
     * @param rolledBackCount     其中回滚的事务数（分子 = 幽灵审计发生次数）
     * @param committedCount      其中提交成功的事务数
     * @param rate                {@code rolledBack / observed}；分母为 0 时 0.0
     * @param byAction            action → 回滚次数
     * @param byCaller            调用方 Class#method → 回滚次数（回答「哪个业务最容易回滚」）
     */
    public record AuditRollbackSnapshot(boolean enabled, long observedHostTxCount, long rolledBackCount,
                                        long committedCount, double rate,
                                        Map<String, Long> byAction, Map<String, Long> byCaller) { }
}
