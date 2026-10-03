package org.ruoyi.ipd.agent.config;

import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 官方模型传输层的流空闲期限装配。
 *
 * <p><b>为什么需要它</b>：run 2106378468009717761 查明，记忆抽取的模型流挂死整整 30 秒。
 * 上层之所以能在 30 秒发现，是因为本仓自己加了空闲期限；<b>官方传输层对此毫无感知</b>——
 * javap 实测 {@code agentscope-core-2.0.3} 的 {@code HttpTransportConfig} 静态初始化：
 * {@code DEFAULT_STREAM_IDLE_TIMEOUT = DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5)}。
 * 也就是说传输层要静默满 5 分钟才会反应，而记忆抽取 30 秒就已被上层掐断，
 * 于是「真实原因」永远不会从传输层浮上来，只能表现为上层那个泛化的超时。
 *
 * <p><b>取值依据</b>：本仓实测 9 次健康后台模型调用耗时 3/3/4/5/5/5/9/10/10 秒，
 * 换包后另有 3/9/14、5/7/11、3/8/10 秒，最长 14 秒。取 120 秒留 8 倍以上余量。
 * <b>不取更小的值</b>：调低会同时提高「真实但较慢的推理被误杀」的概率，而误杀主运行的代价
 * 远高于晚一点发现挂死——5 分钟是默认值，120 秒已经把它降到四分之一。
 *
 * <p><b>为什么用公开扩展点</b>：{@link HttpTransportFactory#setDefault} 是官方公开静态方法，
 * {@code OpenAIChatModel.Builder.resolveTransport()} 在未显式指定传输层时正是走
 * {@code HttpTransportFactory.getDefault()}（javap 字节码确认）。因此启动时装一次即对全部
 * OpenAI 兼容端点（MiniMax / DeepSeek / GLM / Kimi）生效，无需升级 SDK、不用私有反射、
 * 不关闭任何官方能力、不做全局串行化，也不新建第二套模型客户端。
 *
 * <p><b>时序要求</b>：必须在任何 {@code ModelRegistry.resolve(...)} 之前完成。模型与传输层都被
 * SDK 静态缓存，事后设置不会替换已被缓存的实例。用 {@code @Bean} 的 eager 单例在容器启动期完成，
 * 早于任何 HTTP 请求进入。
 */
@Configuration
public class ProjectAgentModelTransportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgentModelTransportConfiguration.class);

    /** 流空闲期限。与上面的实测分布对齐，见类注释。 */
    static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 装一次即全局生效的默认传输层。
     *
     * <p>返回的 {@link HttpTransport} 不作为 Spring bean 暴露给业务注入——它由 SDK 静态持有与关闭，
     * 业务侧拿到的仍是 {@code ModelRegistry} 解析出的模型，不新增第二条模型装配路径。
     */
    @Bean
    public HttpTransport projectAgentModelTransport() {
        HttpTransportConfig config = HttpTransportConfig.builder()
            .streamIdleTimeout(STREAM_IDLE_TIMEOUT)
            .build();
        // javap 实测：JdkHttpTransport 的 (HttpTransportConfig) 构造器是包级私有，
        // 公开入口只有无参与 (HttpClient, HttpTransportConfig) 两个；builder 是官方公开路径。
        HttpTransport transport = JdkHttpTransport.builder().config(config).build();
        HttpTransportFactory.setDefault(transport);
        log.info("project_agent operation=MODEL_TRANSPORT status=CONFIGURED streamIdleTimeout={} default={}",
            STREAM_IDLE_TIMEOUT, HttpTransportConfig.defaults().getStreamIdleTimeout());
        return transport;
    }
}
