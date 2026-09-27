package org.ruoyi.workflow.workflow.node;

import lombok.extern.slf4j.Slf4j;

/**
 * 节点失败策略：AiExecutionEngine（ruoyi-ipd，R221）「退避重试 / ≥3 次 DEAD 转人工 / 留痕」
 * 最小语义移植进 aiflow（补遗-20260927 §1 G5、§5 优先序第 3 项）。
 *
 * <p>常量与判定式与源引擎逐一对齐：{@code MAX_ATTEMPTS=3}、
 * {@code BACKOFF_SECONDS={30,120,600}}（30s·2m·10m）、{@code attempt>=MAX_ATTEMPTS} 即 DEAD。
 *
 * <p>不移植的部分（另案）：DB 持久化 next_retry_at 异步重派、outbox 抢占 claim、
 * NotificationService 推送与 AuditLog 留痕（aiflow 无对应依赖，移植即引入跨模块依赖，
 * 触碰零新依赖红线）。本类只做纯计算 + 留痕文案，由 AbstractWfNode 既有失败分支消费，
 * 留痕落 t_workflow_runtime_node.status_remark（既有写入路径）。
 */
@Slf4j
public final class NodeFailurePolicy {

    /** 对齐 AiExecutionEngine.MAX_ATTEMPTS */
    public static final int MAX_ATTEMPTS = 3;
    /** 对齐 AiExecutionEngine.BACKOFF_SECONDS：30s · 2m · 10m */
    public static final long[] BACKOFF_SECONDS = {30L, 120L, 600L};

    /** 退避等待器，仅测试可替换（对齐 AiExecutionEngine.withClock 的可注入先例） */
    static BackoffSleeper sleeper = seconds -> {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试退避等待被中断", e);
        }
    };

    /** 节点执行一次尝试的三种结局：成功 / 可重试失败 / 终局失败（DEAD 转人工） */
    public enum Outcome {
        SUCCESS, RETRY, DEAD
    }

    @FunctionalInterface
    interface BackoffSleeper {
        void sleep(long seconds) throws Exception;
    }

    private NodeFailurePolicy() {
    }

    /** 对齐源引擎：attempt >= MAX_ATTEMPTS 翻 DEAD */
    public static boolean isDead(int attempt) {
        return attempt >= MAX_ATTEMPTS;
    }

    /** 对齐源引擎 finalizeTask：第 attempt 次（1 起）失败后的退避秒数 */
    public static long backoffSeconds(int attempt) {
        int idx = Math.min(Math.max(attempt, 1), BACKOFF_SECONDS.length) - 1;
        return BACKOFF_SECONDS[idx];
    }

    /**
     * 单次尝试结局判定。软失败（NodeProcessResult.error=true，D8/G3 吞错点）
     * 与异常失败共用本决策，杜绝"错误被当正常输出、流程照常 SUCCESS"。
     */
    public static Outcome decide(int attempt, boolean ok, String errorMsg) {
        if (ok) {
            return Outcome.SUCCESS;
        }
        return isDead(attempt) ? Outcome.DEAD : Outcome.RETRY;
    }

    /**
     * DEAD/失败留痕文案，对齐 AiExecutionEngine.notifyIfDead 推送文案
     *（"AI 执行失败转人工: ... 任务已重试 N 次仍失败，请人工接管（原手工路径不受影响）。错误: ..."），
     * 差异仅在 aiflow 侧无通知依赖，落 status_remark 由看板/人工兜底。
     */
    public static String deadTrailMessage(String nodeTitle, int attempt, String errorMsg) {
        return "节点执行失败转人工: " + nodeTitle
                + " 已重试 " + attempt + " 次仍失败，请人工接管（原手工路径不受影响）。错误: "
                + safeError(errorMsg);
    }

    /** 对齐源引擎 truncate(·,512) 防超长 remark */
    public static String safeError(String errorMsg) {
        String s = errorMsg == null ? "unknown" : errorMsg;
        return s.length() <= 512 ? s : s.substring(0, 512);
    }

    static void sleepBeforeRetry(int attempt) {
        long seconds = backoffSeconds(attempt);
        log.warn("[node-retry] 第 {} 次尝试失败，{}s 后重试（上限 {} 次，超限转人工）", attempt, seconds, MAX_ATTEMPTS);
        try {
            sleeper.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试退避等待被中断", e);
        } catch (Exception e) {
            log.warn("[node-retry] 退避等待异常，继续下一尝试", e);
        }
    }

}
