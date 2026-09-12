package org.ruoyi.ipd.websocket;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IPD WebSocket 配置项（C1）。
 *
 * <p>绑定的属性前缀：{@code ipd.websocket}
 * <pre>
 * ipd:
 *   websocket:
 *     enabled: true                          # 总开关（必须 true 才装配 IpdWebSocketConfig）
 *     path: /api/v1/resource/websocket       # 端点路径（带 /api/v1 前缀，对齐 IPD 端点规范）
 *     allowed-origins: "*"                   # CORS 允许来源（开发期 *，生产应白名单）
 * </pre>
 *
 * @author ruoyi-ipd
 */
@Data
@ConfigurationProperties(prefix = "ipd.websocket")
public class IpdWebSocketProperties {

    /**
     * 端点路径。
     *
     * <p>为什么带 {@code /api/v1} 前缀（2026-09-11 决策）：
     * <ul>
     *   <li>IPD 全部业务端点都是 {@code /api/v1/**}，WS 路径与之一致</li>
     *   <li>前端 vite 代理规则「{@code /api/v1} 开头不剥离、其余剥离 {@code /api}」——
     *       拼 {@code /api/v1/resource/websocket} 直接命中后端同路径，零代理改动</li>
     *   <li>平台 WS 路径为 {@code /resource/websocket}（不带 /api/v1），两者隔离不冲突</li>
     * </ul>
     */
    private String path = "/api/v1/resource/websocket";

    /** 允许的跨域来源（开发期用 *，生产请白名单） */
    private String allowedOrigins = "*";
}