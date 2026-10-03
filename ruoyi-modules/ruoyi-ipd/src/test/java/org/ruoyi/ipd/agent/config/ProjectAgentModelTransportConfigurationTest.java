package org.ruoyi.ipd.agent.config;

import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 官方传输层流空闲期限的装配合同。
 *
 * <p>run 2106378468009717761 的根因就在这个默认值上，因此除了断言「我们装成了 120 秒」，
 * 还要钉住「官方默认确实是 5 分钟」——否则将来 SDK 改了默认值，整条根因叙述会静默失真。
 *
 * <p>注意：{@code JdkHttpTransport} 没有公开的 {@code getConfig()}（只有 {@code OkHttpTransport} 有），
 * 且 {@code (HttpTransportConfig)} 构造器是包级私有。所以断言针对<b>我们自己构造并传进去的 config 对象</b>，
 * 而不是试图从 transport 上读回——后者只能用反射，那正是本仓禁止的手段。
 */
@Tag("dev")
class ProjectAgentModelTransportConfigurationTest {

    @Test
    @DisplayName("官方默认流空闲期限是 5 分钟——这正是挂死时传输层毫无感知的原因")
    void sdkDefaultStreamIdleTimeoutIsFiveMinutes() {
        assertThat(HttpTransportConfig.defaults().getStreamIdleTimeout())
            .as("若此断言变化，run 2106378468009717761 的根因叙述必须同步修订")
            .isEqualTo(HttpTransportConfig.DEFAULT_READ_TIMEOUT)
            .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("装配后默认传输层被换成我们持有的那个实例")
    void configuredTransportBecomesTheSdkDefault() {
        var transport = new ProjectAgentModelTransportConfiguration().projectAgentModelTransport();
        try {
            assertThat(HttpTransportFactory.getDefault())
                .as("OpenAIChatModel.Builder 在未显式指定传输层时走 getDefault()，这里必须是我们装的那个")
                .isSameAs(transport);
        } finally {
            HttpTransportFactory.shutdown();
        }
    }

    @Test
    @DisplayName("只收窄流空闲期限，其它期限一律沿用官方默认")
    void onlyStreamIdleTimeoutIsNarrowed() {
        var defaults = HttpTransportConfig.defaults();
        var config = HttpTransportConfig.builder()
            .streamIdleTimeout(ProjectAgentModelTransportConfiguration.STREAM_IDLE_TIMEOUT)
            .build();
        assertThat(config.getStreamIdleTimeout())
            .isLessThan(HttpTransportConfig.DEFAULT_STREAM_IDLE_TIMEOUT)
            // 实测最长健康后台调用 14 秒，留 8 倍以上余量，避免把真实但较慢的推理误杀。
            .isGreaterThan(Duration.ofSeconds(14).multipliedBy(8));
        assertThat(config.getConnectTimeout()).isEqualTo(defaults.getConnectTimeout());
        assertThat(config.getWriteTimeout()).isEqualTo(defaults.getWriteTimeout());
        assertThat(config.getResponseTimeout()).isEqualTo(defaults.getResponseTimeout());
        assertThat(config.getReadTimeout()).isEqualTo(defaults.getReadTimeout());
    }

    @Test
    @DisplayName("公开 builder 路径可用：不依赖包级私有构造器")
    void transportIsBuiltThroughThePublicBuilder() {
        // javap 实测：JdkHttpTransport 的 (HttpTransportConfig) 构造器是包级私有，
        // 公开入口只有无参与 (HttpClient, HttpTransportConfig)；builder 是唯一既公开又带 config 的路径。
        var transport = JdkHttpTransport.builder()
            .config(HttpTransportConfig.builder()
                .streamIdleTimeout(ProjectAgentModelTransportConfiguration.STREAM_IDLE_TIMEOUT).build())
            .build();
        assertThat(transport).isNotNull();
        transport.close();
    }
}
