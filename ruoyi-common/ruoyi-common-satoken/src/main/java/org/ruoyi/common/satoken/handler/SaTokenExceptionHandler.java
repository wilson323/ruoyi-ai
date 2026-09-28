package org.ruoyi.common.satoken.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.hutool.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.domain.R;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.regex.Pattern;

/**
 * SaToken异常处理器
 *
 * @author Lion Li
 */
@Slf4j
@RestControllerAdvice
public class SaTokenExceptionHandler {

    private static final Pattern CREDENTIAL_PATH_SEGMENT = Pattern.compile(
        "(?i)(/(?:token(?:[-_]?id)?|access[-_]?token|refresh[-_]?token|api[-_]?key|"
            + "monitor/online(?:/myself)?|myself)/)([^/?#;\\s]+)");

    /** NotLoginException 的 message 会拼 token 值（如 "客户端ID与Token不匹配：<JWT>"），日志前必须刮除。 */
    private static final Pattern JWT_VALUE = Pattern.compile(
        "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");

    /**
     * 权限码异常
     */
    @ExceptionHandler(NotPermissionException.class)
    public R<Void> handleNotPermissionException(NotPermissionException e, HttpServletRequest request) {
        log.error("authorization_failed category=PERMISSION method={} path={} exceptionType={}",
            request.getMethod(), safePath(request), e.getClass().getName());
        return R.fail(HttpStatus.HTTP_FORBIDDEN, "没有访问权限，请联系管理员授权");
    }

    /**
     * 角色权限异常
     */
    @ExceptionHandler(NotRoleException.class)
    public R<Void> handleNotRoleException(NotRoleException e, HttpServletRequest request) {
        log.error("authorization_failed category=ROLE method={} path={} exceptionType={}",
            request.getMethod(), safePath(request), e.getClass().getName());
        return R.fail(HttpStatus.HTTP_FORBIDDEN, "没有访问权限，请联系管理员授权");
    }

    /**
     * 认证失败。
     * <p>日志必须带原始 type/message（如 "-100 客户端ID与Token不匹配"）——历史教训（2026-09-28
     * clientid-contract）：统一文案曾把明确的契约错位伪装成未登录，逼人猜契约；
     * 响应体保持统一文案（不泄露内部细节），诊断细节只进日志。
     */
    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLoginException(NotLoginException e, HttpServletRequest request) {
        log.error("authorization_failed category=NOT_LOGIN method={} path={} exceptionType={} type={} detail={}",
            request.getMethod(), safePath(request), e.getClass().getName(), e.getType(), safeDetail(e.getMessage()));
        return R.fail(HttpStatus.HTTP_UNAUTHORIZED, "认证失败，无法访问系统资源");
    }

    /** 诊断细节只留语义（如 "客户端ID与Token不匹配"），JWT/token 值一律刮除后记录，日志不得落凭据。 */
    private static String safeDetail(String message) {
        return message == null ? null : JWT_VALUE.matcher(message).replaceAll("[REDACTED-JWT]");
    }

    private static String safePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        return CREDENTIAL_PATH_SEGMENT.matcher(path).replaceAll("$1[REDACTED]");
    }

}
