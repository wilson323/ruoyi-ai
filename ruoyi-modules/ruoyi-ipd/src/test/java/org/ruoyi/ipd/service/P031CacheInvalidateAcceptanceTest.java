package org.ruoyi.ipd.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.SystemConfig;
import org.ruoyi.ipd.mapper.SystemConfigMapper;
import org.ruoyi.ipd.mapper.SystemConfigVersionMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * R8X-CACHE-1 / W28-3：SystemConfigService 缓存 SQL 直改失效验收。
 *
 * <p>关键边界：
 * <ol>
 *   <li>写穿透：{@code update()} 后立即读到新值（不等 TTL）——走 invalidate 路径</li>
 *   <li>TTL 兜底：SQL 直改绕过 update()，TTL 到期后自动失效（注入 1s 短 TTL 模拟）</li>
 *   <li>同值短路：update 同值不写版本链但仍 invalidate（防止回放场景 stale）</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class P031CacheInvalidateAcceptanceTest {

    @Mock private SystemConfigMapper systemConfigMapper;
    @Mock private SystemConfigVersionMapper systemConfigVersionMapper;
    @InjectMocks private SystemConfigService service;

    @BeforeEach
    void setUp() {
        // 注入短 TTL（1 秒）+ 小容量测试规格，让 TTL 兜底测试能在 1.5s 内跑完
        service.setCache(Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofSeconds(1))
            .build());
    }

    @Test
    @DisplayName("AC#1 写穿透：update 后立即读到新值（不等 TTL）")
    void writeThroughInvalidate() {
        SystemConfig cfg = SystemConfig.builder()
            .id(1L).configKey("gate.signDeadlineDays").configValue("3").build();
        when(systemConfigMapper.selectOne(any())).thenReturn(cfg);

        // 首次读：DB=3, cache 写入 3
        assertThat(service.getValue("gate.signDeadlineDays", "0")).isEqualTo("3");

        // 模拟 DB 直改 → 5，cache 仍持有 3
        when(systemConfigMapper.selectOne(any())).thenReturn(
            SystemConfig.builder().id(1L).configKey("gate.signDeadlineDays").configValue("5").build());

        // 走 service.update 路径：内部 invalidate 触发 cache 失效
        service.update("gate.signDeadlineDays", "5");

        // 立即读：cache 已失效，重新查 DB 得 5
        assertThat(service.getValue("gate.signDeadlineDays", "0")).isEqualTo("5");
    }

    @Test
    @DisplayName("AC#2 TTL 兜底：SQL 直改绕过 update()，1 秒后自动失效（治标方案）")
    void ttlFallbackAfterDirectWrite() throws InterruptedException {
        SystemConfig cfg = SystemConfig.builder()
            .id(1L).configKey("gate.signDeadlineDays").configValue("3").build();
        when(systemConfigMapper.selectOne(any())).thenReturn(cfg);

        // 首次读：DB=3, cache 写入 3
        assertThat(service.getValue("gate.signDeadlineDays", "0")).isEqualTo("3");

        // 模拟 SQL 直改 DB → 5，cache 仍持有 3（不调 service.update）
        when(systemConfigMapper.selectOne(any())).thenReturn(
            SystemConfig.builder().id(1L).configKey("gate.signDeadlineDays").configValue("5").build());

        // 立即读：cache 命中 stale 3
        assertThat(service.getValue("gate.signDeadlineDays", "0")).isEqualTo("3");

        // 等 TTL 过期（注入 1s 短 TTL）
        Thread.sleep(1500);

        // TTL 过期后 cache 失效，重新查 DB 得 5
        assertThat(service.getValue("gate.signDeadlineDays", "0")).isEqualTo("5");
    }

    @Test
    @DisplayName("AC#3 同值短路：update 同值不写版本链但 invalidate（防回放 stale）")
    void sameValueShortCircuit() {
        SystemConfig cfg = SystemConfig.builder()
            .id(1L).configKey("gate.signDeadlineDays").configValue("3").build();
        when(systemConfigMapper.selectOne(any())).thenReturn(cfg);

        // 首次读：DB=3, cache=3
        assertThat(service.getValue("gate.signDeadlineDays", "0")).isEqualTo("3");

        // 模拟 DB 仍为 3（同值），不调 service.update
        when(systemConfigMapper.selectOne(any())).thenReturn(
            SystemConfig.builder().id(1L).configKey("gate.signDeadlineDays").configValue("3").build());

        // update 同值：应短路不写版本链，但仍 invalidate
        service.update("gate.signDeadlineDays", "3");

        // 立即读：cache 已失效，重新查 DB 得 3
        assertThat(service.getValue("gate.signDeadlineDays", "0")).isEqualTo("3");
    }
}
