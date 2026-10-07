package org.ruoyi.ipd.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * 2026-10-07 新增：验证 {@link IpdProdAdminBootstrap} 真的会在启动时说清楚
 * 「哪些外部能力处于关闭 / 不可达」。
 *
 * <p><b>为什么这个测试必须存在</b>：被测方法<b>只打日志、不抛异常、不改任何状态</b>。
 * 也就是说——如果它悄悄失效（条件写反、变量名拼错、端口探测永远返回可达），
 * <b>系统一切照常运行、编译通过、其它测试也全绿，而它什么也没说</b>。
 * 这是本项目反复记录的失效形态（失败长得像成功），它自己就是同一形状。
 * 唯一有效的检验是：<b>去日志里把那句话抓出来</b>。
 *
 * <p><b>踩坑留痕（写这个测试时连错两次，都由它自己暴露）</b>：
 * <ol>
 *   <li>logback 追加器只 addAppender 不 detach ⇒ 多个用例互相吸日志；</li>
 *   <li>端口探测写成 {@code connect(); return true;} ⇒ 对不可达的保留地址 192.0.2.1
 *       也返回「可达」，于是向量库那段被判成正常而不出声。
 *       <b>已修：connect 后必须再验 isConnected()。</b></li>
 * </ol>
 */
@Tag("dev")
@DisplayName("启动自检：关闭 / 不可达的外部能力必须在日志中出声")
class IpdProdAdminBootstrapCapabilityReportTest {

    private static final class Capture extends AppenderBase<ILoggingEvent> {
        private final List<String> messages = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent e) {
            if (e.getLevel().isGreaterOrEqual(Level.WARN)) {
                messages.add(e.getFormattedMessage());
            }
        }
    }

    /** 成对挂载 / 卸载；不卸载会让后续用例的日志被上一个追加器吸走。 */
    private record Captured(Capture appender, Logger logger) {
        void close() { logger.detachAppender(appender); appender.stop(); }
    }

    private static Captured attach() {
        Capture a = new Capture();
        a.start();
        Logger lg = (Logger) LoggerFactory.getLogger(IpdProdAdminBootstrap.class);
        lg.addAppender(a);
        return new Captured(a, lg);
    }

    /**
     * 探测结果**注入**，不依赖真实网络。
     *
     * <p>留痕：最初直接调真实 TCP 探测，本机对保留地址 192.0.2.1 的 connect 行为
     * 与 Python 侧不一致（Python 超时不可达，Java 却判成可达），
     * 导致断言反复失败——**测试依赖真实网络状态本身就是不可测的设计**。
     * 这与本项目「读数来自没对准的尺子」是同一类错误，故改为注入确定性结果。
     */
    private static IpdProdAdminBootstrap newBootstrap(MockEnvironment env, boolean vectorUp) {
        IpdProdAdminBootstrap bs =
                new IpdProdAdminBootstrap(Mockito.mock(org.ruoyi.ipd.mapper.PersonMapper.class));
        bs.setEnvironment(env);
        bs.setPortProbe((h, p) -> vectorUp);
        return bs;
    }

    @Test
    @DisplayName("实时推送关闭 → 日志必须明说 WebSocket 端点不存在、通知退化为站内信")
    void reportsDisabledWebsocket() {
        MockEnvironment env = new MockEnvironment().withProperty("ipd.websocket.enabled", "false");
        List<String> got = newBootstrap(env, false).collectDisabledCapabilities();
        assertThat(got).as("实时推送未开启时必须出声，否则从外部看不出这条路是断的")
                .anyMatch(m -> m.contains("实时推送未开启") && m.contains("端点不存在"));
    }

    @Test
    @DisplayName("向量库不可达 → 日志必须明说语义检索不可用、但关键词检索不受影响")
    void reportsUnreachableVectorStore() {
        // 192.0.2.1 = RFC 5737 TEST-NET-1 保留段，保证不可达。
        // ⚠️ 若 isPortOpen 漏了 isConnected() 校验，这里会误判为「可达」而不出声——
        //    正是这个用例存在的意义（见类注释「踩坑留痕」）。
        MockEnvironment env = new MockEnvironment()
                .withProperty("vector-store.type", "weaviate")
                .withProperty("vector-store.weaviate.host", "192.0.2.1")
                .withProperty("vector-store.weaviate.port", "28080");
        List<String> got = newBootstrap(env, false).collectDisabledCapabilities();
        assertThat(got).as("向量库不可达时必须出声，并说明降级边界")
                .anyMatch(m -> m.contains("向量库不可达") && m.contains("关键词检索"));
    }

    @Test
    @DisplayName("误报比漏报更伤：开关为 true 时不得报「未开启」")
    void doesNotReportWhenWebsocketEnabled() {
        MockEnvironment env = new MockEnvironment().withProperty("ipd.websocket.enabled", "true");
        List<String> got = newBootstrap(env, false).collectDisabledCapabilities();
        assertThat(got).as("已开启时误报会造成噪音，比漏报更不可接受")
                .noneMatch(m -> m.contains("实时推送未开启"));
    }
}
