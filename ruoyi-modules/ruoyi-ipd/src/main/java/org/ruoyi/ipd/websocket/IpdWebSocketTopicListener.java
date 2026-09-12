package org.ruoyi.ipd.websocket;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.websocket.holder.WebSocketSessionHolder;
import org.ruoyi.common.websocket.utils.WebSocketUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/**
 * IPD WebSocket Redis pub/sub 订阅监听器（C1：跨实例 fan-out）。
 *
 * <p>工作链路：
 * <ul>
 *   <li>业务方调 {@link WebSocketUtils#publishMessage} 把消息发到 Redis topic
 *       {@code global:websocket}（平台同 key，IPD 不另起 topic——多租户共享基础设施）</li>
 *   <li>本监听器在 Spring 启动后立刻 {@code WebSocketUtils.subscribeMessage} 订阅同一 topic</li>
 *   <li>收到广播后看本进程 {@code WebSocketSessionHolder} 里有没有目标 sessionKey（userId），
 *       有就直推；无就走 Redis pub/sub 转发到其他实例</li>
 *   <li>sessionKey 是 {@code LoginUser.userId}（Long，与 persons.id 同型）——
 *       由 {@link IpdHandshakeInterceptor} 注入；与 platform 域共用同一个 SessionHolder 不冲突
 *       （userType 区分语义）</li>
 * </ul>
 *
 * <p>为什么 IPD 不自己起一个独立 listener：
 * platform {@code WebSocketTopicListener} 已经在 ruoyi-common-websocket 里，且它用的是同一个
 * Redis topic；IPD 只需复用，无需重复订阅一份（多份订阅只会导致消息被消费多次）。
 *
 * @author ruoyi-ipd
 */
@Slf4j
@RequiredArgsConstructor
public class IpdWebSocketTopicListener implements ApplicationRunner, Ordered {

    @Override
    public void run(ApplicationArguments args) {
        // 复用平台 WebSocketUtils.subscribeMessage：IPD 与 platform 共用 global:websocket topic
        WebSocketUtils.subscribeMessage((message) -> {
            log.info("ipd_websocket_topic_received sessionKeys={} payloadLength={}",
                message.getSessionKeys(), payloadLength(message.getMessage()));
            if (CollUtil.isNotEmpty(message.getSessionKeys())) {
                message.getSessionKeys().forEach(key -> {
                    if (WebSocketSessionHolder.existSession(key)) {
                        WebSocketUtils.sendMessage(key, message.getMessage());
                    }
                });
            } else {
                WebSocketSessionHolder.getSessionsAll().forEach(key ->
                    WebSocketUtils.sendMessage(key, message.getMessage()));
            }
        });
        log.info("ipd_websocket_topic_listener_started topic={}", "global:websocket");
    }

    @Override
    public int getOrder() {
        // 早于业务监听器，优先于普通 ApplicationRunner
        return -1;
    }

    private static int payloadLength(String s) {
        return s == null ? 0 : s.length();
    }
}