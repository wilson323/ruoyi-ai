package org.ruoyi.ipd.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.websocket.handler.PlusWebSocketHandler;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * IPD WebSocket 端点配置（C1：把平台 /resource/websocket 收编给 IPD token）。
 *
 * <p>为什么 IPD 不复用平台 {@code WebSocketConfig}：
 * <ul>
 *   <li>平台配置由 {@code @ConditionalOnProperty("websocket.enabled")=true} 控制；
 *       IPD 不想影响 platform 域其他模块（chat 的 /chat/ws 独立不受影响，但仍有潜在耦合）</li>
 *   <li>平台拦截器用 {@code LoginHelper.getLoginUser()} 走 platform 域 Sa-Token；
 *       IPD 必须用 {@link IpdHandshakeInterceptor} 走 ipd 域 Sa-Token（loginType="ipd"）</li>
 *   <li>本配置独立开关 {@code ipd.websocket.enabled}，默认 false，零风险共存</li>
 * </ul>
 *
 * <p>路径与平台配置一致：{@code /resource/websocket}（前端 message.ts 注释预期路径）。
 * 若平台同时开启 {@code websocket.enabled=true}，会冲突——本机默认保持 platform 关闭。
 *
 * <p>复用 platform 的 {@link PlusWebSocketHandler}：
 * 它从 {@code session.attributes.loginUser} 取 LoginUser（userId=persons.id，userType="ipd"），
 * 直接挂到 WebSocketSessionHolder，IPD 推送链路 {@code WebSocketUtils.publishMessage} 无需感知差异。
 *
 * @author ruoyi-ipd
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(value = "ipd.websocket.enabled", havingValue = "true")
@EnableConfigurationProperties(IpdWebSocketProperties.class)
public class IpdWebSocketConfig {

    private final IpdWebSocketProperties properties;

    private final PersonMapper personMapper;

    /**
     * 注册端点：/resource/websocket + IPD 握手拦截器。
     */
    @Bean
    public WebSocketConfigurer ipdWebSocketConfigurer(HandshakeInterceptor ipdHandshakeInterceptor,
                                                      WebSocketHandler ipdWebSocketHandler) {
        String path = properties.getPath();
        String origins = properties.getAllowedOrigins();
        log.info("ipd_websocket_endpoint_registered path={} allowedOrigins={}", path, origins);
        return registry -> registry
            .addHandler(ipdWebSocketHandler, path)
            .addInterceptors(ipdHandshakeInterceptor)
            .setAllowedOrigins(origins);
    }

    /**
     * IPD token 握手拦截器：见 {@link IpdHandshakeInterceptor} 注释。
     */
    @Bean
    public HandshakeInterceptor ipdHandshakeInterceptor() {
        return new IpdHandshakeInterceptor(personMapper);
    }

    /**
     * 复用 platform 的 PlusWebSocketHandler（从 session attributes 读 LoginUser）。
     */
    @Bean
    public WebSocketHandler ipdWebSocketHandler() {
        return new PlusWebSocketHandler();
    }

    /**
     * Redis pub/sub 订阅器：跨实例 fan-out。
     */
    @Bean
    public IpdWebSocketTopicListener ipdWebSocketTopicListener() {
        return new IpdWebSocketTopicListener();
    }
}