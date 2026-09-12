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
 *     enabled: true                # 总开关（必须 true 才装配 IpdWebSocketConfig）
 *     path: /resource/websocket    # 端点路径
 *     allowed-origins: "*"         # CORS 允许来源（开发期 *，生产应白名单）
 * </pre>
 *
 * @author ruoyi-ipd
 */
@Data
@ConfigurationProperties(prefix = "ipd.websocket")
public class IpdWebSocketProperties {

    /** 端点路径，与前端 {@code message.ts:75} 注释预期一致 */
    private String path = "/resource/websocket";

    /** 允许的跨域来源（开发期用 *，生产请白名单） */
    private String allowedOrigins = "*";
}