package org.ruoyi.ipd.service;

import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境 {@link NotificationChannel} 占位实现（仅服务遗留单通道链路）。
 *
 * <p><b>它在哪条链路上（2026-10-03 逐文件核对，勿再按「prod 通知空实现」理解本类）</b>：
 * {@link NotificationChannel} 全仓只被 {@link NotificationService} 注入一处，消费点只有两个——
 * <ol>
 *   <li>{@code doPublish} 取 {@link #code()} 写入 {@code notification_events.channel} 列（纯留痕）；</li>
 *   <li>{@code dispatchPending(int)} 逐条 {@link #send}；该方法的唯一生产调用方是人工运维端点
 *       {@code POST /api/v1/notifications/dispatch-pending}，不被任何调度驱动。</li>
 * </ol>
 *
 * <p><b>定时投递链路不经过本类</b>：{@code NotificationOutboxScanner}（每 30s）→
 * {@code AsyncNotificationDispatcher} → {@code org.ruoyi.ipd.service.channel.*ChannelHandler}
 * （INBOX / WEBSOCKET / EMAIL，均为无 profile 限制的普通 {@code @Component}，prod 同样装配）。
 * 因此 prod 下「收不到通知」不由本类造成：收件箱 {@code GET /api/v1/notifications} 直接查
 * {@code notification_events}（只按 receiver_id 过滤，不筛 delivery_status），publish 落库即可见。
 *
 * <p>prod 下真正未接线的是<b>对外投递</b>：{@code ipd.notification.email.enabled} 默认 false
 * （{@code EmailChannelHandler} 退化为日志），{@code websocket.enabled} 默认 false（无实时推送，
 * 离线走 offline-fallback-to-inbox 记 SENT，由收件箱兜底）。属功能缺口，非阻断。
 *
 * <p><b>本类的边界</b>：{@link #send} 正常返回（不抛异常），故人工触发 dispatch-pending 时事件
 * 仍会被翻成 SENT——本类只保证「不静默」，不保证「不假发送」；调用方需自行知情。
 *
 * <p>与 {@link MockNotificationChannel} 的区别：
 * <ul>
 *   <li>Mock（dev）：code=MOCK，INFO 日志，用于开发调试；</li>
 *   <li>NoOp（prod）：code=NOOP，WARN 日志，显式标记未配置。</li>
 * </ul>
 *
 * <p>若要让遗留单通道链路在 prod 也能真实投递，则新增一个 {@code @Profile("prod")} 的
 * {@link NotificationChannel} 实现（内部委派给既有 ChannelHandler）并移除此类；
 * 对外投递开关的生产侧启动校验应加在 {@code ProdConfigFailFastValidator}，不在本类。
 */
@Slf4j
@Component
@Profile("prod")
public class NoOpNotificationChannel implements NotificationChannel {

    public static final String CODE = "NOOP";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void send(NotificationEvent event) {
        log.warn("[NOOP] 遗留单通道链路未配置真实通道，本次未真实发送（事件随后仍会被翻 SENT）；"
                + "定时链路走 NotificationChannelHandler，不受影响——receiver={} kind={} type={} source={}:{} title={}",
            event.getReceiverId(), event.getKind(), event.getEventType(),
            event.getSourceType(), event.getSourceId(), event.getTitle());
    }
}
