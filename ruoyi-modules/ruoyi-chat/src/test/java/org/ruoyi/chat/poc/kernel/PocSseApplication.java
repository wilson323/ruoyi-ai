package org.ruoyi.chat.poc.kernel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * [PoC G4] SSE 样例最小启动器(测试域,不进生产装配):
 * 只装配 org.ruoyi.chat.poc.kernel 包,剔除数据源自动装配(状态存储经 PocKernelSupport 直连 ipd_poc)。
 *
 * <p>用法: java -cp <test classpath> org.ruoyi.chat.poc.kernel.PocSseApplication [--server.port=18765]
 */
@SpringBootConfiguration
@EnableAutoConfiguration(
        exclude = {DataSourceAutoConfiguration.class},
        excludeName = {
            // ruoyi-common 全量自动装配剔除:PoC 最小启动器不拉 redis/mybatis/sa-token 等基建
            "org.ruoyi.common.core.config.ApplicationConfig",
            "org.ruoyi.common.core.config.ThreadPoolConfig",
            "org.ruoyi.common.core.config.ValidatorConfig",
            "org.ruoyi.common.core.utils.SpringUtils",
            "org.ruoyi.common.doc.config.SpringDocConfig",
            "org.ruoyi.common.encrypt.config.ApiDecryptAutoConfiguration",
            "org.ruoyi.common.encrypt.config.EncryptorAutoConfiguration",
            "org.ruoyi.common.idempotent.config.IdempotentConfig",
            "org.ruoyi.common.job.config.SnailJobConfig",
            "org.ruoyi.common.json.config.JacksonConfig",
            "org.ruoyi.common.log.aspect.LogAspect",
            "org.ruoyi.common.mail.config.MailConfig",
            "org.ruoyi.common.mybatis.config.MybatisPlusConfig",
            "org.ruoyi.common.ratelimiter.config.RateLimiterConfig",
            "org.ruoyi.common.redis.config.CacheConfig",
            "org.ruoyi.common.redis.config.RedisConfig",
            "org.ruoyi.common.satoken.config.SaTokenConfig",
            "org.ruoyi.common.security.config.SecurityConfig",
            "org.ruoyi.common.security.handler.AllUrlHandler",
            "org.ruoyi.common.sms.config.SmsAutoConfiguration",
            "org.ruoyi.common.social.config.SocialAutoConfiguration",
            "org.ruoyi.common.sse.config.SseAutoConfiguration",
            "org.ruoyi.common.tenant.config.TenantConfig",
            "org.ruoyi.common.trace.config.TraceAutoConfiguration",
            "org.ruoyi.common.translation.config.TranslationConfig",
            "org.ruoyi.common.translation.core.impl.DeptNameTranslationImpl",
            "org.ruoyi.common.translation.core.impl.DictTypeTranslationImpl",
            "org.ruoyi.common.translation.core.impl.NicknameTranslationImpl",
            "org.ruoyi.common.translation.core.impl.OssUrlTranslationImpl",
            "org.ruoyi.common.translation.core.impl.UserNameTranslationImpl",
            "org.ruoyi.common.web.config.CaptchaConfig",
            "org.ruoyi.common.web.config.DemoAutoConfiguration",
            "org.ruoyi.common.web.config.FilterConfig",
            "org.ruoyi.common.web.config.I18nConfig",
            "org.ruoyi.common.web.config.ResourcesConfig",
            "org.ruoyi.common.web.config.UndertowConfig",
            "org.ruoyi.common.websocket.config.WebSocketConfig",
            // 第三方自动装配剔除:PoC 最小启动器不连 redis / 不接 mybatis-plus / lock4j / sa-token
            "org.redisson.spring.starter.RedissonAutoConfigurationV2",
            "com.baomidou.lock.spring.boot.autoconfigure.LockAutoConfiguration",
            "com.baomidou.lock.spring.boot.autoconfigure.RedissonLockAutoConfiguration",
            "com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration",
            "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
            "com.baomidou.mybatisplus.autoconfigure.DdlAutoConfiguration",
            "com.baomidou.mybatisplus.autoconfigure.IdentifierGeneratorAutoConfiguration",
            "com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration",
            "com.baomidou.mybatisplus.autoconfigure.MybatisPlusLanguageDriverAutoConfiguration",
            "io.github.linpeilie.mapstruct.MapstructAutoConfiguration",
            "cn.hutool.extra.spring.SpringUtil",
            "cn.dev33.satoken.spring.SaBeanInject",
            "cn.dev33.satoken.spring.SaBeanRegister",
            "cn.dev33.satoken.spring.SaTokenContextRegister",
            "cn.dev33.satoken.spring.apikey.SaApiKeyBeanInject",
            "cn.dev33.satoken.spring.apikey.SaApiKeyBeanRegister",
            "cn.dev33.satoken.spring.oauth2.SaOAuth2BeanInject",
            "cn.dev33.satoken.spring.oauth2.SaOAuth2BeanRegister",
            "cn.dev33.satoken.spring.sign.SaSignBeanInject",
            "cn.dev33.satoken.spring.sign.SaSignBeanRegister",
            "cn.dev33.satoken.spring.sso.SaSsoBeanInject",
            "cn.dev33.satoken.spring.sso.SaSsoBeanRegister",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration"
        })
@ComponentScan(basePackageClasses = PocSseController.class)
public class PocSseApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PocSseApplication.class);
        if (args.length == 0) {
            app.run("--server.port=18765");
        } else {
            app.run(args);
        }
    }
}
