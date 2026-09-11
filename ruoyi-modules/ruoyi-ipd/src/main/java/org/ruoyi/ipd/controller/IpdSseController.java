package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * IPD 业务 SSE 接入（任务 2 收口，2026-09-10）。
 *
 * <p>不复用 ruoyi-common-sse 的 SseController：
 * <ul>
 *   <li>后者走默认 sa-token 登录态（loginType=""），认不出 IPD Person 的 JWT</li>
 *   <li>IPD 业务会话用 StpLogicJwtForSimple("ipd")（IpdAuthSession）</li>
 *   <li>EventSource 浏览器 API 不支持自定义 header，token 只能走 URL query</li>
 * </ul>
 *
 * <p>端点：{@code GET /api/v1/resource/sse?clientid={id}&Authorization=Bearer {token}}
 * <p>前端：整合仓 {@code apps/web-antd/src/utils/message.ts} URL 改成 {@code ${apiURL}/v1/resource/sse}，token 源切到 {@code useIpdAuthStore().accessToken}
 *
 * <p><b>关键 sa-token 陷阱</b>（已实证，2026-09-10）：
 * <ul>
 *   <li>{@code isLogin(String token)} 实际是 {@code isLogin(Object loginId)} —— 把 token 字符串当 loginId 查在线，<b>永远返回 false</b></li>
 *   <li>{@code getLoginIdAsLong()} 不带参数版本依赖 sa-token 上下文（从 header 读 token），EventSource 没 header 会抛异常</li>
 *   <li>正确做法：{@code getLoginIdByToken(String tokenValue)} → 查 sa-token dao（redis）的 token→loginId 映射；返回 null = token 无效/过期/被踢</li>
 * </ul>
 */
@Slf4j
@SaIgnore
@RestController
@RequestMapping("/api/v1/resource")
@RequiredArgsConstructor
public class IpdSseController {

    /** 与 IpdAuthSession.LOGIN_TYPE 对齐：IPD 业务会话走独立 loginType */
    private static final StpLogicJwtForSimple IPD_LOGIC = new StpLogicJwtForSimple("ipd");

    private final SseEmitterManager sseEmitterManager;

    /**
     * 建立 IPD 业务 SSE 连接。
     *
     * <p>2026-09-11：认证失败从「return null（200 + 空体、无 Content-Type）」改为 401——
     * 浏览器 EventSource 对无 Content-Type 的空 200 按默认 text/plain 解析，报
     * 「MIME type ("text/plain") is not "text/event-stream"」并盲目重试；401 让其直接走 error，
     * 对脚本调用方语义也正确（curl -i 可见）。
     *
     * @param clientid      客户端 UUID（前端 useAppConfig 注入）
     * @param authorization Bearer token，从 URL query 取（EventSource 不支持 header）
     * @return 200 + SseEmitter；未带 token 或 token 无效/过期 401；服务端异常 500
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connect(
        @RequestParam(value = "clientid", required = false) String clientid,
        @RequestParam(value = "Authorization", required = false) String authorization) {
        String token = stripBearer(authorization);
        if (token == null || token.isBlank()) {
            log.warn("ipd_sse_connect status=REJECTED reason=NO_TOKEN clientid={}", clientid);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            Object loginIdObj = IPD_LOGIC.getLoginIdByToken(token);
            if (loginIdObj == null) {
                log.warn("ipd_sse_connect status=REJECTED reason=TOKEN_INVALID_OR_EXPIRED clientid={}", clientid);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            long userId = Long.parseLong(loginIdObj.toString());
            log.info("ipd_sse_connect status=ACCEPTED userId={} clientid={}", userId, clientid);
            return ResponseEntity.ok(sseEmitterManager.connect(userId, token));
        } catch (Exception e) {
            log.error("ipd_sse_connect status=FAILED errorType={}", e.getClass().getName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static String stripBearer(String authorization) {
        if (authorization == null) return null;
        String trimmed = authorization.trim();
        return trimmed.startsWith("Bearer ") ? trimmed.substring(7) : trimmed;
    }
}
