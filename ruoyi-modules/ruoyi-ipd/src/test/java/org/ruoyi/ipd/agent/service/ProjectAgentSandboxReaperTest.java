package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 沙箱孤儿容器清扫器测试（2026-10-03 治理登记）。
 *
 * <p>覆盖两块：①孤儿判定纯函数（超窗删 / 未超留 / 脏行跳过 / 启动 ZERO 全删）；
 * ②调度接线（@Component 注册 + @Scheduled 覆盖，R215-GAP-B1「实现完成但入口不可达」同款防线）。
 * 不触真实 docker 子进程（本地 daemon 可用性不影响测试稳定性）。
 */
@Tag("dev")
class ProjectAgentSandboxReaperTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Test
    @DisplayName("超过 TTL 的沙箱容器判为孤儿（含前导斜杠的 inspect 名称格式）")
    void selectsOverTtlContainer() {
        var lines = List.of("/agentscope-sandbox-abc 2026-10-02T04:00:00.123456789Z");
        assertThat(ProjectAgentSandboxReaper.selectOrphans(lines, NOW, Duration.ofHours(24)))
                .containsExactly("agentscope-sandbox-abc");
    }

    @Test
    @DisplayName("未超 TTL 的容器保留（时间窗保护在途 call）")
    void keepsFreshContainer() {
        var lines = List.of("/agentscope-sandbox-fresh 2026-10-03T11:00:00Z");
        assertThat(ProjectAgentSandboxReaper.selectOrphans(lines, NOW, Duration.ofHours(24))).isEmpty();
    }

    @Test
    @DisplayName("脏行、非沙箱前缀与空行跳过，不中断整轮清扫")
    void skipsMalformedLines() {
        var lines = List.of(
                "",
                "   ",
                "/other-container 2026-10-01T00:00:00Z",
                "/agentscope-sandbox-bad not-a-timestamp",
                "/agentscope-sandbox-only-name",
                "/agentscope-sandbox-clockskew 2027-01-01T00:00:00Z");
        assertThat(ProjectAgentSandboxReaper.selectOrphans(lines, NOW, Duration.ofHours(24))).isEmpty();
    }

    @Test
    @DisplayName("启动模式 TTL=ZERO 全部视为孤儿（单副本启动时无在途 call）")
    void zeroTtlSelectsAll() {
        var lines = List.of(
                "/agentscope-sandbox-a 2026-10-03T11:59:59Z",
                "/agentscope-sandbox-b 2026-10-01T00:00:00Z");
        assertThat(ProjectAgentSandboxReaper.selectOrphans(lines, NOW, Duration.ZERO))
                .containsExactlyInAnyOrder("agentscope-sandbox-a", "agentscope-sandbox-b");
    }

    @Test
    @DisplayName("构造器把孤儿 TTL 钳制到至少 1 小时（配置误填 0 不至于秒删新容器）")
    void clampsTtlToAtLeastOneHour() throws Exception {
        var reaper = new ProjectAgentSandboxReaper(true, 0);
        Field ttl = ProjectAgentSandboxReaper.class.getDeclaredField("orphanTtl");
        ttl.setAccessible(true);
        assertThat(ttl.get(reaper)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("调度接线：@Component 注册且 hourlyOrphanSweep 带 @Scheduled（入口可达防线）")
    void schedulerWiringExists() throws Exception {
        assertThat(ProjectAgentSandboxReaper.class.isAnnotationPresent(Component.class)).isTrue();
        var method = ProjectAgentSandboxReaper.class.getMethod("hourlyOrphanSweep");
        assertThat(method.getAnnotation(Scheduled.class))
                .as("清扫器必须有 @Scheduled 方法（IpdSchedulingConfig 已 @EnableScheduling）")
                .isNotNull();
    }
}
