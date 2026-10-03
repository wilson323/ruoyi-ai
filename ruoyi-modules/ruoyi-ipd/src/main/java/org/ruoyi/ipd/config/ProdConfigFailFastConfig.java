package org.ruoyi.ipd.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生产配置 fail-fast 校验器的 Bean 接线（2026-10-03 补，P0）。
 *
 * <p><b>为什么需要这个类</b>：{@link ProdConfigFailFastRunner} 是 {@code @RequiredArgsConstructor}
 * 构造器注入 {@link ProdConfigFailFastValidator}，而校验器类上原本零 Spring 注解、全仓也没有任何
 * {@code @Bean} 生产它。结果是 {@code SPRING_PROFILES_ACTIVE=prod} 时容器在装配阶段就抛
 * {@code NoSuchBeanDefinitionException}——报的是「Bean 找不到」而不是「你少配了哪个环境变量」，
 * 这套「生产配置快速失败」守卫从未在真实 prod 启动里跑到过判定逻辑。本类只补接线，不改任何
 * 校验规则与判定阈值（规则仍在 {@link ProdConfigFailFastValidator}，逐字未动）。
 *
 * <p><b>为什么选 {@code @Configuration}+{@code @Bean}，而不是给校验器加 {@code @Component}</b>：
 * 与本包既有先例一致——{@link IpdSaTokenBridgeConfig}、{@link IpdKnowledgeAccessConfig} 都是
 * 「纯逻辑类 + {@code @Configuration} 里 new 出来注册」。{@link ProdConfigFailFastValidator}
 * 是无依赖、无副作用的 {@code final} 纯值类，单元测试直接 {@code new} 使用；让它保持不引
 * Spring 注解，规则与容器接线才能各自独立演进，也避免「测试里 new、容器里却没人生产」这类
 * 断线再次被绕过。
 *
 * <p><b>为什么不加 {@code @Profile("prod")}</b>：校验器无状态、无副作用，常驻容器零成本；
 * 而给它加 profile 限制会与消费方 {@link ProdConfigFailFastRunner} 的 profile 表达式形成隐式耦合
 * （改一处漏一处就复现同一类断线）。「要不要拒绝启动」始终只由 Runner 决定。
 */
@Configuration
public class ProdConfigFailFastConfig {

    /**
     * 注册生产配置校验器，供 {@link ProdConfigFailFastRunner} 构造器注入。
     *
     * @return 无状态校验器实例
     */
    @Bean
    public ProdConfigFailFastValidator prodConfigFailFastValidator() {
        return new ProdConfigFailFastValidator();
    }
}
