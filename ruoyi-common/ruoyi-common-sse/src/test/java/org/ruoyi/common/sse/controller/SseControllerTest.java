package org.ruoyi.common.sse.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * SseController 认证失败响应形态回归（2026-09-11）。
 *
 * <p>背景：认证失败曾 return null → Spring 写 200 + 空体（无 Content-Type），
 * 浏览器 EventSource 按默认 text/plain 解析，报
 * 「MIME type ("text/plain") is not "text/event-stream"」并盲目重连；
 * 修复后认证失败必须返回语义化 401。
 *
 * <p>默认 sse.path = {@code /resource/sse}（见 {@code application.yml}），与 IPD 业务的
 * {@code /api/v1/resource/sse}（IpdSseController）不冲突——本类服务使用默认 sa-token
 * 登录态的非 IPD 业务（如 RuoYi 平台通知前端）。
 *
 * <p>不用 MockMvc（standalone 不解析 ${sse.path} 占位符且依赖 Spring 容器加载
 * PathMatcher），直接调用 controller 方法验证 ResponseEntity 状态码——契约本身
 * 不依赖 HTTP 协议。
 */
@Tag("dev")
class SseControllerTest {

    @Test
    void connectWithoutLoginRejectsWith401NotSilent200() {
        // StpUtil 是静态工具，用 MockedStatic 控制 isLogin() 返回值。
        try (MockedStatic<StpUtil> mocked = Mockito.mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::isLogin).thenReturn(false);
            SseController controller = new SseController(mock(SseEmitterManager.class));
            ResponseEntity<SseEmitter> result = controller.connect();
            assertEquals(401, result.getStatusCode().value());
        }
    }
}