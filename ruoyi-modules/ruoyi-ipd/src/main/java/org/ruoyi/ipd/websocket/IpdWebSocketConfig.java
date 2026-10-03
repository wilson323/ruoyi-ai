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
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
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
 * <p>路径：{@code /api/v1/resource/websocket}（{@link IpdWebSocketProperties} 默认值，
 * 与 IPD 业务端点前缀及前端 vite 代理规则一致）。平台 WS 是 {@code /resource/websocket}
 * （不带 /api/v1），两者路径隔离。**别开平台键来开 IPD**：{@code websocket.enabled}
 * 只控制平台端点；本配置只认 {@code ipd.websocket.enabled}（见父 application.yml
 * 的 ipd.websocket 段，2026-10-03 显式补齐——此前任何 yml 都没有该键，端点从不注册）。
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
     * 注册端点：/api/v1/resource/websocket + IPD 握手拦截器。
     *
     * <p>allowedOrigins 为空白时**不调用** setAllowedOrigins —— 2026-10-03 修正：
     * 旧实现把空串原样传给 {@code setAllowedOrigins("")}，等于登记一个永不匹配的
     * origin，开启后所有浏览器握手（WS 握手必带 Origin 头）一律 403。空白 → 不配置
     * → Spring 默认仅同源可握手（与平台 SEC-LOW-4 的「空=同源」口径一致）。
     * 非空时按逗号拆分（配置写 {@code https://a,https://b}）。
     */
    @Bean
    public WebSocketConfigurer ipdWebSocketConfigurer(HandshakeInterceptor ipdHandshakeInterceptor,
                                                      WebSocketHandler ipdWebSocketHandler) {
        String path = properties.getPath();
        String origins = properties.getAllowedOrigins();
        log.info("ipd_websocket_endpoint_registered path={} allowedOrigins={}", path, origins);
        return registry -> {
            WebSocketHandlerRegistration registration = registry
                .addHandler(ipdWebSocketHandler, path)
                .addInterceptors(ipdHandshakeInterceptor);
            if (origins != null && !origins.isBlank()) {
                registration.setAllowedOrigins(java.util.Arrays.stream(origins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new));
            }
        };
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