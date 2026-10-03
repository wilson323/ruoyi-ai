package org.ruoyi.ipd.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPageParamGuardInterceptor;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SEC 兜底：/api/v1/** 业务接口统一走 IPD 会话 + 注解鉴权。
 * <p>
 * 基线 SecurityConfig 已 exclude /api/v1/**，故此处自行：
 * <ol>
 *   <li>登录校验（StpLogic "ipd"）</li>
 *   <li>SaInterceptor 注解鉴权（@SaCheckPermission type=ipd）</li>
 * </ol>
 * 公开入口豁免：
 * <ul>
 *   <li>/api/v1/auth/login——认证入口</li>
 *   <li>/api/v1/auth/wecom/qr-login——P0-7.4 企微 Mock 扫码登录入口（mock=true 走未绑定同错误信息）</li>
 *   <li>/api/v1/public/**——需求门户公开端点（P4-1.1 游客 submit/products/trace）</li>
 * </ul>
 */
@Configuration
public class IpdWebSecurityConfig implements WebMvcConfigurer {

    private final IpdAuthSession session;

    public IpdWebSecurityConfig(IpdAuthSession session) {
        this.session = session;
    }

    /** SSE 完成会由容器跨线程 ASYNC 派发；仍执行原登录和注解权限检查。 */
    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> ipdAsyncSaTokenContext() {
        // 独立实例仅补 ASYNC；不接管官方 REQUEST bean 的默认注册。
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
            new cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet());
        registration.setName("ipdAsyncSaTokenContext");
        registration.addUrlPatterns("/api/v1/*");
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.ASYNC);
        registration.setAsyncSupported(true);
        registration.setOrder(cn.dev33.satoken.util.SaTokenConsts.SA_TOKEN_CONTEXT_FILTER_ORDER);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                try {
                    session.currentPerson();
                } catch (NotLoginException e) {
                    throw new IpdPermissionException(401, ApiV1ErrorCode.UNAUTHORIZED);
                }
                return true;
            }
        }).addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/wecom/qr-login", "/api/v1/public/**", "/api/v1/resource/**")
            .order(Ordered.HIGHEST_PRECEDENCE);

        // 注解鉴权：依赖上一层已完成 ipd 登录；type=ipd 的 @SaCheckPermission 在此生效
        registry.addInterceptor(new SaInterceptor().isAnnotation(true))
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/wecom/qr-login", "/api/v1/public/**", "/api/v1/resource/**")
            .order(Ordered.HIGHEST_PRECEDENCE + 1);

        // P1-3（D3 §2.1 真红，2026-09-29）：分页入参统一校验（B-2 契约：pageNum|pageNo ≥ 1、
        // pageSize 1~200，超限 400/10001）。不 exclude 任何路径：公开端点同样受成本放大防护。
        registry.addInterceptor(new IpdPageParamGuardInterceptor())
            .addPathPatterns("/api/v1/**")
            .order(Ordered.HIGHEST_PRECEDENCE + 2);
    }
}
