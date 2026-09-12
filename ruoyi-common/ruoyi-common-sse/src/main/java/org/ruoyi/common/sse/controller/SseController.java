package org.ruoyi.common.sse.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 控制器
 *
 * @author Lion Li
 */
@RestController
@ConditionalOnProperty(value = "sse.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SseController implements DisposableBean {

    private final SseEmitterManager sseEmitterManager;

    /**
     * 建立 SSE 连接
     *
     * <p>2026-09-11：认证失败从「return null（200 + 空体、无 Content-Type）」改为 401——
     * 浏览器 EventSource 对无 Content-Type 的空 200 按默认 text/plain 解析，报
     * 「MIME type ("text/plain") is not "text/event-stream"」并盲目重连；401 让其直接走
     * error 事件，curl -i 可见语义化状态码。
     *
     * <p>注：本类走默认 sa-token 登录态（loginType=""），认不出 IPD Person 的 JWT；IPD 业务
     * 走 {@code IpdSseController}（{@code /api/v1/resource/sse}）。本类仅服务使用默认登录态的
     * 非 IPD 业务（如 RuoYi 平台通知）。
     */
    @GetMapping(value = "${sse.path}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connect() {
        if (!StpUtil.isLogin()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String tokenValue = StpUtil.getTokenValue();
        Long userId = LoginHelper.getUserId();
        return ResponseEntity.ok(sseEmitterManager.connect(userId, tokenValue));
    }

    /**
     * 关闭 SSE 连接
     */
    @SaIgnore
    @GetMapping(value = "${sse.path}/close")
    public R<Void> close() {
        String tokenValue = StpUtil.getTokenValue();
        Long userId = LoginHelper.getUserId();
        sseEmitterManager.disconnect(userId, tokenValue);
        return R.ok();
    }

    // 以下为demo仅供参考 禁止使用 请在业务逻辑中使用工具发送而不是用接口发送
//    /**
//     * 向特定用户发送消息
//     *
//     * @param userId 目标用户的 ID
//     * @param msg    要发送的消息内容
//     */
//    @GetMapping(value = "${sse.path}/send")
//    public R<Void> send(Long userId, String msg) {
//        SseMessageDto dto = new SseMessageDto();
//        dto.setUserIds(List.of(userId));
//        dto.setMessage(msg);
//        sseEmitterManager.publishMessage(dto);
//        return R.ok();
//    }
//
//    /**
//     * 向所有用户发送消息
//     *
//     * @param msg 要发送的消息内容
//     */
//    @GetMapping(value = "${sse.path}/sendAll")
//    public R<Void> send(String msg) {
//        sseEmitterManager.publishAll(msg);
//        return R.ok();
//    }

    /**
     * 清理资源。此方法目前不执行任何操作，但避免因未实现而导致错误
     */
    @Override
    public void destroy() throws Exception {
        // 销毁时不需要做什么 此方法避免无用操作报错
    }

}
