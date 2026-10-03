package org.ruoyi.common.web.config;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import org.ruoyi.common.core.utils.ObjectUtils;
import org.ruoyi.common.web.handler.GlobalExceptionHandler;
import org.ruoyi.common.web.interceptor.PlusWebInvokeTimeInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Date;

/**
 * 通用配置
 *
 * @author Lion Li
 */
@AutoConfiguration
public class ResourcesConfig implements WebMvcConfigurer {

    /**
     * 允许跨域的来源清单，逗号分隔。
     * <p>
     * 留空（默认）= 不放开任何跨域来源，浏览器侧只允许同源访问；生产为同源部署
     * （前端由 nginx 反代 /api），无需放开。确需跨域时由部署方显式列举具体来源。
     */
    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    /**
     * 是否允许跨域请求携带浏览器凭证（Cookie / 客户端证书）。
     * <p>
     * 本系统鉴权走 {@code Authorization: Bearer} 请求头，不读 Cookie
     * （见 {@code common-satoken.yml} 的 {@code is-read-cookie: false}），故默认关闭。
     */
    @Value("${cors.allow-credentials:false}")
    private boolean allowCredentials;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 全局访问性能拦截
        registry.addInterceptor(new PlusWebInvokeTimeInterceptor());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // 全局日期格式转换配置
        registry.addConverter(String.class, Date.class, source -> {
            DateTime parse = DateUtil.parse(source);
            if (ObjectUtils.isNull(parse)) {
                return null;
            }
            return parse.toJdkDate();
        });
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
    }

    /**
     * 跨域配置
     * <p>
     * 来源清单取自 {@code cors.allowed-origins}（逗号分隔），默认留空 = 不放开任何跨域来源。
     * 同源请求不受影响（浏览器同源请求的 Origin 与自身一致，不走跨域分支）。
     * 禁止回退到 {@code "*"} 通配：那会让任意第三方站点在受害者浏览器里读取本系统全部接口响应。
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        String origins = allowedOrigins == null ? "" : allowedOrigins.trim();
        if (!origins.isEmpty()) {
            // 逐个登记显式来源；空白项忽略，避免 "a.com,,b.com" 这类写法放进空串
            for (String origin : origins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) {
                    config.addAllowedOriginPattern(trimmed);
                }
            }
            // 只有存在显式来源清单时才允许携带凭证
            config.setAllowCredentials(allowCredentials);
        }
        // 设置访问源请求头
        config.addAllowedHeader("*");
        // 设置访问源请求方法
        config.addAllowedMethod("*");
        // 有效期 1800秒
        config.setMaxAge(1800L);
        // 添加映射路径，拦截一切请求
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        // 返回新的CorsFilter
        return new CorsFilter(source);
    }

    /**
     * 全局异常处理器
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
