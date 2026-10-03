package org.ruoyi.ipd.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产配置 fail-fast 的「接线」守卫（2026-10-03 补，P0）。
 *
 * <p><b>为什么单独立一个测试类</b>：{@link ProdConfigFailFastValidatorTest} 用
 * {@code new ProdConfigFailFastValidator()} 直喂 {@code MockEnvironment}，验的是「规则算得对不对」，
 * 天然绕过容器装配。于是「校验器没有任何 {@code @Bean} 生产它、Runner 的构造器注入必然失败」
 * 这条断线一直没被任何测试发现：{@code SPRING_PROFILES_ACTIVE=prod} 时容器报的是 Bean 装配错误，
 * 而不是「你少配了哪个环境变量」——整套 fail-fast 守卫在真实 prod 启动里从未跑到过判定逻辑。
 *
 * <p>本类只回答一个问题：<b>prod profile 切片能不能把 Runner 装配起来</b>。
 * 用 {@link ApplicationContextRunner} 只加载两个类，不起整个应用上下文，也不触发
 * {@code ApplicationRunner} 的启动回调；因此额外喂入「全齐备」属性，避免万一回调真的被执行时
 * 因缺配置而误红、把「接线断了」和「配置少了」两种红混在一起。
 *
 * <p>字面量均为测试夹具探测串，非真实凭证。
 */
@Tag("dev")
@DisplayName("生产配置 fail-fast 接线：prod 切片必须装配出 Validator 与 Runner")
class ProdConfigFailFastWiringTest {

    /** 致命档齐备 + 无条件警告档（初始口令 / 16 渠道 / 2 短信商）齐备，达成「全齐备」。 */
    private static String[] prodBaselineProperties() {
        List<String> props = new ArrayList<>(List.of(
                "sa-token.jwt-secret-key=unit-test-only-jwt-secret-not-a-real-credential",
                "spring.datasource.dynamic.datasource.master.username=ipd_app_user",
                "spring.datasource.dynamic.datasource.master.password=unit-test-only-db-pass",
                "spring.datasource.dynamic.datasource.master.url=jdbc:mysql://10.10.0.8:3306/ipd",
                "ipd.security.initial-password=unit-test-only-initial-pwd"));
        for (String channel : new String[]{"maxkey", "topiam", "qq", "weibo", "gitee", "dingtalk", "baidu",
                "csdn", "coding", "oschina", "alipay_wallet", "wechat_open", "wechat_mp", "wechat_enterprise",
                "gitlab", "gitea"}) {
            props.add("justauth.type." + channel + ".client-secret=unit-test-only-secret-" + channel);
        }
        for (String node : new String[]{"config1", "config2"}) {
            props.add("sms.blends." + node + ".access-key-id=unit-test-ak-id");
            props.add("sms.blends." + node + ".access-key-secret=unit-test-ak-secret");
            props.add("sms.blends." + node + ".signature=unit-test-signature");
        }
        return props.toArray(String[]::new);
    }

    /** prod profile 切片：只装配接线类 + Runner，不加载整个应用上下文。 */
    private static ApplicationContextRunner prodSlice() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProdConfigFailFastConfig.class, ProdConfigFailFastRunner.class)
                .withPropertyValues(prodBaselineProperties())
                .withPropertyValues("spring.profiles.active=prod");
    }

    @Test
    @DisplayName("① prod 切片上下文装配成功，且容器内存在 ProdConfigFailFastValidator 类型的 Bean")
    void prodSliceRegistersValidatorBean() {
        prodSlice().run(context -> {
            assertThat(context).as("prod 切片必须装配成功（断线时这里报 NoSuchBeanDefinitionException）")
                    .hasNotFailed();
            assertThat(context).as("校验器必须由 @Bean 生产，否则 Runner 的构造器注入无处可寻")
                    .hasSingleBean(ProdConfigFailFastValidator.class);
        });
    }

    @Test
    @DisplayName("② prod 切片能真正创建 ProdConfigFailFastRunner（构造器注入的依赖已解析）")
    void prodSliceCanCreateRunner() {
        prodSlice().run(context -> {
            assertThat(context).hasNotFailed();
            ProdConfigFailFastRunner runner = context.getBean(ProdConfigFailFastRunner.class);
            assertThat(runner).as("Runner 必须能被容器创建出来，而不是只登记了 BeanDefinition").isNotNull();
        });
    }

    @Test
    @DisplayName("③ 非 prod 切片：Runner 因 @Profile(\"prod\") 不注册，校验器仍常驻（接线与 profile 解耦）")
    void nonProdSliceSkipsRunnerButKeepsValidator() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProdConfigFailFastConfig.class, ProdConfigFailFastRunner.class)
                .withPropertyValues(prodBaselineProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("校验器无状态、无副作用，profile 无关地常驻容器")
                            .hasSingleBean(ProdConfigFailFastValidator.class);
                    assertThat(context).as("Runner 只在 prod 生效，非 prod 切片不得注册")
                            .doesNotHaveBean(ProdConfigFailFastRunner.class);
                });
    }
}
