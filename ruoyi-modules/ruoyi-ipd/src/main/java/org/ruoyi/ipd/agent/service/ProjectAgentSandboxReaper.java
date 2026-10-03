package org.ruoyi.ipd.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * AgentScope 沙箱孤儿容器清扫器（2026-10-03 治理登记）。
 *
 * <p>背景：官方 Docker 沙箱在每次 call 的 release 阶段执行 {@code docker stop} + {@code docker rm --force}，
 * 但 release 失败仅告警不重试、进程崩溃时 release 根本不执行，且官方无孤儿容器清扫——
 * 2026-10-03 实测本机残留 33 个 Up 状态 {@code agentscope-sandbox-*} 容器而活跃 run 为 0。
 *
 * <p>清扫语义：
 * <ul>
 *   <li><b>启动清扫（全量）</b>：单副本部署口径下本进程刚启动不可能持有在途 call，
 *       所有沙箱容器均属历史进程遗留孤儿，直接全删。</li>
 *   <li><b>周期清扫（超龄）</b>：每小时 :23 只删创建时间超过孤儿 TTL（默认 24h）的容器。
 *       容器名内嵌的是 SDK 随机 sessionId，无法反查归属 run，故靠时间窗保护在途 call——
 *       24h 远大于单 run 的 deadline 上限，属保守窗口。</li>
 * </ul>
 *
 * <p>Docker CLI 不可用（本地无 daemon / 无 docker 二进制）时只告警跳过，不阻断启动。
 * 多副本部署前须先完成分布式互斥与副本间清扫协调，见
 * {@code docs/ipd-系统说明/沙箱治理-孤儿容器清扫与聊天互斥补齐-20261003.md}。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：每小时 :23 本 job；无手动兜底端点（启动即全量清扫为兜底）。
 */
@Slf4j
@Component
public class ProjectAgentSandboxReaper implements ApplicationRunner, Ordered {

    /** 官方 SDK 容器名前缀（DockerSandbox: agentscope-sandbox-&lt;sessionId&gt;）。 */
    static final String CONTAINER_PREFIX = "agentscope-sandbox-";

    private static final int LIST_TIMEOUT_SECONDS = 30;
    private static final int REMOVE_TIMEOUT_SECONDS = 30;
    private static final int INSPECT_BATCH = 100;

    private final boolean enabled;
    private final Duration orphanTtl;

    public ProjectAgentSandboxReaper(
            @Value("${ipd.agent.sandbox-reaper.enabled:true}") boolean enabled,
            @Value("${ipd.agent.sandbox-reaper.orphan-ttl-hours:24}") long orphanTtlHours) {
        this.enabled = enabled;
        this.orphanTtl = Duration.ofHours(Math.max(1, orphanTtlHours));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** 启动即全量清扫历史进程遗留的沙箱容器（单副本口径，见类注释）。 */
    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("ProjectAgentSandboxReaper: disabled by ipd.agent.sandbox-reaper.enabled=false");
            return;
        }
        int removed = sweep(Duration.ZERO);
        log.info("ProjectAgentSandboxReaper: startup sweep removed={} sandbox containers", removed);
    }

    /** 每小时 :23 清扫超过孤儿 TTL 的沙箱容器（超龄判定，不碰新容器）。 */
    @Scheduled(cron = "0 23 * * * ?")
    public void hourlyOrphanSweep() {
        if (!enabled) {
            return;
        }
        int removed = sweep(orphanTtl);
        if (removed > 0) {
            log.info("ProjectAgentSandboxReaper: hourly sweep removed={} orphan containers (ttl={})",
                    removed, orphanTtl);
        }
    }

    /**
     * 执行一轮清扫：列出全部沙箱容器 → 按 TTL 判定孤儿 → 逐个 {@code docker rm -f}。
     *
     * @param ttl 孤儿判定窗口；{@link Duration#ZERO} 表示全部视为孤儿（启动模式）
     * @return 实际删除的容器数
     */
    int sweep(Duration ttl) {
        List<String> ids = runDocker(LIST_TIMEOUT_SECONDS,
                List.of("ps", "-a", "--filter", "name=" + CONTAINER_PREFIX, "--format", "{{.ID}}"));
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> orphans = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += INSPECT_BATCH) {
            List<String> argv = new ArrayList<>(List.of("inspect", "--format", "{{.Name}} {{.Created}}"));
            argv.addAll(ids.subList(from, Math.min(from + INSPECT_BATCH, ids.size())));
            List<String> batch = runDocker(LIST_TIMEOUT_SECONDS, argv);
            if (batch != null) {
                orphans.addAll(selectOrphans(batch, Instant.now(), ttl));
            }
        }
        int removed = 0;
        for (String name : orphans) {
            if (runDocker(REMOVE_TIMEOUT_SECONDS, List.of("rm", "-f", name)) != null) {
                removed++;
            } else {
                log.warn("ProjectAgentSandboxReaper: failed to remove sandbox container {}", name);
            }
        }
        return removed;
    }

    /**
     * 从 {@code docker inspect --format '{{.Name}} {{.Created}}'} 输出行中选出孤儿容器名。
     * 行格式 {@code /agentscope-sandbox-xxx 2026-10-03T04:53:44.123456789Z}；
     * 非沙箱前缀、解析失败或空行直接跳过（不因单行脏数据中断整轮清扫）。
     */
    static List<String> selectOrphans(List<String> inspectLines, Instant now, Duration ttl) {
        List<String> orphans = new ArrayList<>();
        for (String line : inspectLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length != 2) {
                continue;
            }
            String name = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
            if (!name.startsWith(CONTAINER_PREFIX)) {
                continue;
            }
            Instant created;
            try {
                created = Instant.parse(parts[1].trim());
            } catch (RuntimeException malformed) {
                continue;
            }
            if (Duration.between(created, now).compareTo(ttl) >= 0) {
                orphans.add(name);
            }
        }
        return orphans;
    }

    /**
     * 运行 docker 子进程并按行读 stdout；可执行缺失、非零退出、超时或中断都返回 {@code null}（失败标记）。
     * {@code rm} 成功时输出为空列表（退出码 0），故用 null/非 null 区分失败与成功，而非空列表判断。
     */
    private static List<String> runDocker(int timeoutSeconds, List<String> argv) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(argv);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ProjectAgentSandboxReaper: docker {} timed out", argv.get(0));
                return null;
            }
            return process.exitValue() == 0 ? lines : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            // docker CLI 缺失或 daemon 不可用：本地无沙箱场景属正常态，告警一次即可。
            log.warn("ProjectAgentSandboxReaper: docker {} unavailable: {}",
                    argv.get(0).toLowerCase(Locale.ROOT), unavailable.getMessage());
            return null;
        }
    }
}
