package org.ruoyi.ipd.advice;

import cn.dev33.satoken.exception.NotLoginException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.service.IpdAuthInputException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.UUID;

/**
 * API-01 补漏：基线 GlobalExceptionHandler 在 IPD 路径返回裸 R（HTTP 200 + code=500），
 * 本类以 HIGHEST+1 优先级接管 IPD controller 包，统一转为 ApiV1Response。
 * IpdPermissionExceptionHandler 以 HIGHEST 优先级先接权限拒绝；此类兜底其余异常。
 * 例外：权限三型（IpdPermissionException / NotPermissionException / NotRoleException）
 * 由 IpdPermissionExceptionHandler 独占，不得在本类重复注册（详见下方注释）。
 *
 * <p>P0.7 traceId 串联：每个 exception handler 在方法入口 put MDC、return 前 remove，
 * 保证 ApiV1Response.traceId 字段始终有值（即便 TraceIdFilter 未触发）。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice(basePackages = "org.ruoyi.ipd.controller")
public class IpdServiceExceptionAdvice {

    @ExceptionHandler(IpdBusinessException.class)
    public ResponseEntity<ApiV1Response<Void>> handleIpdBusiness(IpdBusinessException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            ApiV1ErrorCode mapped = e.getErrorCode() != null ? e.getErrorCode() : ApiV1ErrorCode.INTERNAL_ERROR;
            log.warn("[IPD] business exception: code={} msg={}", mapped.getCode(), e.getMessage());
            return ResponseEntity.status(mapped.getHttpStatus())
                .body(ApiV1Response.fail(mapped, e.getMessage()));
        } finally {
            MDC.remove("traceId");
        }
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiV1Response<Void>> handleServiceException(ServiceException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            Integer code = e.getCode();
            ApiV1ErrorCode mapped = (code != null)
                ? ApiV1ErrorCode.fromCode(code)
                : ApiV1ErrorCode.PARAM_INVALID;
            log.warn("[IPD] service exception: code={} msg={}", mapped.getCode(), e.getMessage());
            return ResponseEntity.status(mapped.getHttpStatus())
                .body(ApiV1Response.fail(mapped, e.getMessage()));
        } finally {
            MDC.remove("traceId");
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiV1Response<Void>> handleValidation(MethodArgumentNotValidException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            String msg = e.getBindingResult().getAllErrors().stream()
                .map(org.springframework.context.support.DefaultMessageSourceResolvable::getDefaultMessage)
                .findFirst().orElse("参数校验失败");
            log.warn("[IPD] validation failed: {}", msg);
            return ResponseEntity.status(ApiV1ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiV1Response.fail(ApiV1ErrorCode.PARAM_INVALID, msg));
        } finally {
            MDC.remove("traceId");
        }
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiV1Response<Void>> handleNotFound(NoHandlerFoundException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            log.warn("[IPD] not found: {}", e.getRequestURL());
            return ResponseEntity.status(ApiV1ErrorCode.NOT_FOUND.getHttpStatus())
                .body(ApiV1Response.fail(ApiV1ErrorCode.NOT_FOUND, "资源不存在"));
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * P0-14：Sa-Token 未登录异常 → 401/20001 IPD 包络。
     * 修复前：无 handler，被下方 {@code Exception.class} 兜底成 500/90001，
     * 前端收到「系统内部错误」而非「登录已失效」，无法触发重登录引导。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiV1Response<Void>> handleNotLogin(NotLoginException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            log.warn("[IPD] not login: type={}", e.getType());
            return ResponseEntity.status(ApiV1ErrorCode.UNAUTHORIZED.getHttpStatus())
                .body(ApiV1Response.fail(ApiV1ErrorCode.UNAUTHORIZED));
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * P0-13：ResponseStatusException → 保留 HTTP 语义映射到最近业务码。
     * 修复前：DemandController 等 14 处 {@code throw new ResponseStatusException(BAD_REQUEST/NOT_FOUND,...)}
     * 被下方 {@code Exception.class} 兜底成 500/90001，参数错/资源不存在语义被压制。
     * reason 为开发者写的业务描述（如「市场PM不存在」），透传给前端展示。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiV1Response<Void>> handleResponseStatus(ResponseStatusException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            int http = e.getStatusCode().value();
            ApiV1ErrorCode mapped = switch (http) {
                case 400 -> ApiV1ErrorCode.PARAM_INVALID;
                case 401 -> ApiV1ErrorCode.UNAUTHORIZED;
                case 403 -> ApiV1ErrorCode.FORBIDDEN;
                case 404 -> ApiV1ErrorCode.NOT_FOUND;
                case 409 -> ApiV1ErrorCode.STATE_CONFLICT;
                case 413 -> ApiV1ErrorCode.ATTACHMENT_TOO_LARGE;
                case 429 -> ApiV1ErrorCode.RATE_LIMITED;
                default -> ApiV1ErrorCode.INTERNAL_ERROR;
            };
            HttpStatus resolved = HttpStatus.resolve(http);
            String reason = e.getReason() != null && !e.getReason().isBlank()
                ? e.getReason()
                : (resolved != null ? resolved.getReasonPhrase() : mapped.getMessage());
            log.warn("[IPD] response status exception: http={} mapped={}" , http, mapped.getCode());
            return ResponseEntity.status(mapped.getHttpStatus())
                .body(ApiV1Response.fail(mapped, reason));
        } finally {
            MDC.remove("traceId");
        }
    }

    @ExceptionHandler(IpdAuthInputException.class)
    public ResponseEntity<ApiV1Response<Void>> handleIpdAuthInput(IpdAuthInputException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            // 认证输入错误（原密码不符/密码强度不足等）属 4xx 参数/凭据问题，不得落入兜底 500。
            log.warn("[IPD] auth input rejected: {}", e.getMessage());
            return ResponseEntity.status(ApiV1ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiV1Response.fail(ApiV1ErrorCode.PARAM_INVALID, e.getMessage()));
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * R28.5 防线 3：配置冲突类异常不走 90001 兜底，返回 503 + code=90002 + traceId，
     * 让运维从日志 traceId 立即定位（替代「系统内部错误」误导）。
     *
     * 背景：此前 ApplicationConfig 实现了 Spring 的 AsyncConfigurer 接口，与 Spring Boot 默认
     * 的 applicationTaskExecutorAsyncConfigurer 多 Bean 冲突 → Only one AsyncConfigurer may exist
     * 被下方 handleUnexpected 静默兜底成 code:90001，前端无法区分用户错 vs 系统错。
     * 改造后：日志带完整堆栈 + traceId 透传响应体，运维毫秒级定位。
     *
     * 覆盖范围：IllegalStateException + 6 个 Spring 配置/Bean 异常；
     * 均属"容器内部状态异常，非用户操作可解决"，前端提示「系统配置异常」即可。
     *
     * 作用域：仅覆盖 IPD controller 包；GlobalExceptionHandler 不在本会话范围。
     * 规约见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md §六。
     */
    @ExceptionHandler({
        IllegalStateException.class,
        org.springframework.beans.factory.support.BeanDefinitionOverrideException.class,
        org.springframework.beans.factory.NoUniqueBeanDefinitionException.class,
        org.springframework.beans.factory.BeanCreationException.class,
        org.springframework.beans.BeanInstantiationException.class,
        org.springframework.beans.FatalBeanException.class,
        org.springframework.context.ApplicationContextException.class
    })
    public ResponseEntity<ApiV1Response<Void>> handleIllegalState(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId", traceId);
        try {
            log.error("[IPD] CONFIG_CONFLICT (R28.5) traceId={} type={} msg={}",
                traceId, e.getClass().getSimpleName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiV1Response.fail(90002,
                    "系统配置异常，请联系管理员（traceId=" + traceId + "）",
                    traceId));
        } finally {
            MDC.remove("traceId");
        }
    }

    // 权限三型（IpdPermissionException / NotPermissionException / NotRoleException）不在此处理：
    // 它们由 IpdPermissionExceptionHandler（@Order(HIGHEST_PRECEDENCE)，basePackages 覆盖
    // org.ruoyi.ipd.controller 及其子包）独占。本类是 @Order(HIGHEST + 1)，一旦在此重复注册
    // 即成生产不可达的死代码：缺陷B 时代该 handler 用 assignableTypes 只覆盖 7 个控制器，兜底有必要；
    // R8-P1-B 改 basePackages 后 14 个 controller 已全部覆盖，该前提已消失。
    // 回归探针见 DefectBAdviceAcceptanceTest（已改为同时注册两个 advice，走生产真实链）。
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiV1Response<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            // DEF-2：请求体缺失/不可读属客户端错误，应 400/10001 而非落 500。
            log.warn("[IPD] request body not readable: {}", e.getMessage());
            return ResponseEntity.status(ApiV1ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiV1Response.fail(ApiV1ErrorCode.PARAM_INVALID, "请求体缺失或格式错误"));
        } finally {
            MDC.remove("traceId");
        }
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiV1Response<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            log.warn("[IPD] missing request parameter: {}", e.getParameterName());
            return ResponseEntity.status(ApiV1ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiV1Response.fail(ApiV1ErrorCode.PARAM_INVALID, "缺少必需参数: " + e.getParameterName()));
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * SSE/异步长连接客户端断开（AsyncRequestNotUsableException）：连接生命周期事件，非业务异常。
     * 修复前：落入下方 handleUnexpected 兜底 → 试图序列化 ApiV1Response JSON，但响应
     * Content-Type 已是 text/event-stream → HttpMessageNotWritableException 二次异常
     * （2026-09-11 实测于 IpdSseController 客户端断开+心跳竞态，日志双 ERROR 噪音）。
     * 此处静默返回 void：response 已不可写，Spring 不会再尝试序列化任何响应体。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.debug("[IPD] async request not usable (client disconnected): {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiV1Response<Void>> handleUnexpected(Exception e) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            log.error("[IPD] unexpected exception", e);
            return ResponseEntity.status(ApiV1ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiV1Response.fail(ApiV1ErrorCode.INTERNAL_ERROR));
        } finally {
            MDC.remove("traceId");
        }
    }
}
