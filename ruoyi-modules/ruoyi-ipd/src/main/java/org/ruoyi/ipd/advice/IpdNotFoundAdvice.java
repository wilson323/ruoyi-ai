package org.ruoyi.ipd.advice;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.core.constant.HttpStatus;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

/**
 * QA-07 缺口①：IPD 域访问不存在端点必须返回 404 + IPD code0/message 包络。
 *
 * <p>根因：{@link IpdServiceExceptionAdvice} 以 {@code basePackages = "org.ruoyi.ipd.controller"}
 * 限定，而 NoHandlerFoundException / NoResourceFoundException 抛出时不存在命中的 Controller
 * HandlerMethod（无 handler 或静态资源 handler）——advice 的包匹配对 beanType=null / 非 IPD
 * bean 不适用，异常落到基线 GlobalExceptionHandler，返回 HTTP 200 + R 包络（code/msg 字段），
 * 不符合 IPD 404/50001 契约。静态守卫 PermissionAdviceCoverageTest 锁死了 basePackages 写法，
 * 因此本类不放宽原 advice，而是新建无 basePackages 限定的兜底 advice。
 *
 * <p>作用域：仅当请求 URI 以 /api/v1/ 开头（IPD 前端契约域）才转 IPD 包络；其余路径
 * （/chat 等框架既有通道）按异常类型复刻基线 GlobalExceptionHandler 的既有响应（R 包络
 * HTTP 200），保证旧通道契约零变化、不误伤。
 *
 * <p>@Order(HIGHEST + 2)：位于 IpdServiceExceptionAdvice(+1) 之后、基线（无 @Order，最低）
 * 之前；无 basePackages 限定使"无 handler"异常也能命中本类。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RestControllerAdvice
public class IpdNotFoundAdvice {

    static final String IPD_API_PREFIX = "/api/v1/";

    /**
     * DispatcherServlet 无 Controller 命中且静态资源 pattern 也不匹配时抛出
     * （Spring 6.1+ 默认 throwExceptionIfNoHandlerFound=true）。
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Object handleNoHandler(NoHandlerFoundException e, HttpServletRequest request) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            if (isIpdApi(request)) {
                log.warn("[IPD] no handler: {}", request.getRequestURI());
                return ipdNotFound();
            }
            // 非 IPD 域：复刻基线 handleNoHandlerFoundException 契约（R 包络 HTTP 200）
            return R.fail(HttpStatus.NOT_FOUND, "请求地址不存在");
        } finally {
            MDC.remove("traceId");
        }
    }

    /**
     * Spring 6.1+/Boot 3.2 起静态资源链未命中抛出（extends ServletException，基线会被
     * handleServletException 兜成 code=500）。IpdServiceExceptionAdvice 的 Exception 兜底
     * 接不到它（由非 IPD bean 的 ResourceHttpRequestHandler 抛出），必须在此承接。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", ""));
        try {
            if (isIpdApi(request)) {
                log.warn("[IPD] no resource: {}", request.getRequestURI());
                return ipdNotFound();
            }
            // 非 IPD 域：复刻基线 handleServletException 契约（R 包络 HTTP 200 code=500）
            return R.fail("系统异常，请联系管理员");
        } finally {
            MDC.remove("traceId");
        }
    }

    static boolean isIpdApi(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(IPD_API_PREFIX);
    }

    private static ResponseEntity<ApiV1Response<Void>> ipdNotFound() {
        return ResponseEntity.status(ApiV1ErrorCode.NOT_FOUND.getHttpStatus())
            .body(ApiV1Response.fail(ApiV1ErrorCode.NOT_FOUND));
    }
}
