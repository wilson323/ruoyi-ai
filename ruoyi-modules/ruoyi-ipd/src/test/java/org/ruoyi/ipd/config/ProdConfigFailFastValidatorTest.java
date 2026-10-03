package org.ruoyi.ipd.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产配置 fail-fast 三场景（校验器 {@link ProdConfigFailFastValidator} + 接线 {@link ProdConfigFailFastRunner}）：
 * <ol>
 *   <li>全部配置齐备 → 校验通过（0 致命 / 0 警告），Runner 放行；</li>
 *   <li>致命项缺失（如 SA_TOKEN_JWT_SECRET_KEY）→ Runner 抛 {@link IllegalStateException}，
 *       异常消息含变量名（运维照着 export 即可修）；</li>
 *   <li>仅警告项缺失（如某三方登录渠道 client-secret）→ 打告警后放行，不抛异常。</li>
 * </ol>
 *
 * <p>用 {@link MockEnvironment} 直喂已解析属性，不起 Spring 容器——校验器读的就是
 * {@code Environment#getProperty}，Mock 行为与真实容器一致。
 * 字面量均为测试夹具探测串，非真实凭证。
 */
@Tag("dev")
@DisplayName("生产配置 fail-fast：全齐通过 / 致命拒绝启动 / 警告放行")
class ProdConfigFailFastValidatorTest {

    private final ProdConfigFailFastValidator validator = new ProdConfigFailFastValidator();

    /** 致命档四件套齐备的基础环境（jwt 密钥 / 主库账密 / 非回环主库地址），警告项另行叠加。 */
    private static MockEnvironment fatalBaselines() {
        return new MockEnvironment()
                .withProperty("sa-token.jwt-secret-key", "unit-test-only-jwt-secret-not-a-real-credential")
                .withProperty("spring.datasource.dynamic.datasource.master.username", "ipd_app_user")
                .withProperty("spring.datasource.dynamic.datasource.master.password", "unit-test-only-db-pass")
                .withProperty("spring.datasource.dynamic.datasource.master.url",
                        "jdbc:mysql://10.10.0.8:3306/ipd?useUnicode=true&characterEncoding=utf8");
    }

    /** 在基础环境上补齐全部无条件警告档键（初始口令 + 16 个三方渠道 + 2 家短信供应商），达到「全部齐备」。 */
    private static MockEnvironment fullyConfigured() {
        MockEnvironment env = fatalBaselines()
                .withProperty("ipd.security.initial-password", "unit-test-only-initial-pwd");
        for (String channel : new String[]{"maxkey", "topiam", "qq", "weibo", "gitee", "dingtalk", "baidu",
                "csdn", "coding", "oschina", "alipay_wallet", "wechat_open", "wechat_mp", "wechat_enterprise",
                "gitlab", "gitea"}) {
            env = env.withProperty("justauth.type." + channel + ".client-secret",
                    "unit-test-only-secret-" + channel);
        }
        for (String node : new String[]{"config1", "config2"}) {
            env = env.withProperty("sms.blends." + node + ".access-key-id", "unit-test-ak-id")
                    .withProperty("sms.blends." + node + ".access-key-secret", "unit-test-ak-secret")
                    .withProperty("sms.blends." + node + ".signature", "unit-test-signature");
        }
        return env;
    }

    private static ProdConfigFailFastRunner runnerFor(MockEnvironment env) {
        return new ProdConfigFailFastRunner(env, new ProdConfigFailFastValidator());
    }

    @Test
    @DisplayName("场景1：致命档+警告档全齐备 → 0 致命 / 0 警告，Runner 放行")
    void fullyConfiguredPasses() {
        MockEnvironment env = fullyConfigured();

        ProdConfigFailFastValidator.Report report = validator.inspect(env);
        assertThat(report.failed()).as("全齐备时不得有致命项").isFalse();
        assertThat(report.fatal()).as("致命清单应为空").isEmpty();
        assertThat(report.warning()).as("警告清单应为空").isEmpty();

        assertThatCode(() -> runnerFor(env).run(new DefaultApplicationArguments()))
                .as("全齐备时 Runner 必须放行，不得抛异常")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("场景2：SA_TOKEN_JWT_SECRET_KEY 缺失 → Runner 拒绝启动，消息含变量名与修法")
    void missingJwtSecretRefusesStartup() {
        MockEnvironment env = fatalBaselines()
                // 其余致命项齐备，只挖掉 jwt 密钥，定位单因
                .withProperty("sa-token.jwt-secret-key", "");

        ProdConfigFailFastValidator.Report report = validator.inspect(env);
        assertThat(report.failed()).as("jwt 密钥为空必须判致命").isTrue();
        assertThat(report.fatal())
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.envVar()).isEqualTo("SA_TOKEN_JWT_SECRET_KEY");
                    assertThat(f.configKey()).isEqualTo("sa-token.jwt-secret-key");
                });

        assertThatThrownBy(() -> runnerFor(env).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SA_TOKEN_JWT_SECRET_KEY")
                .hasMessageContaining("sa-token.jwt-secret-key")
                .hasMessageContaining("拒绝启动");
    }

    @Test
    @DisplayName("场景3：仅 QQ 渠道 client-secret 缺失（致命档全齐）→ 打警告后放行")
    void warningOnlyAllowsStartup() {
        MockEnvironment env = fullyConfigured()
                .withProperty("justauth.type.qq.client-secret", "");

        ProdConfigFailFastValidator.Report report = validator.inspect(env);
        assertThat(report.failed()).as("警告项缺失不得升级为拒绝启动").isFalse();
        assertThat(report.fatal()).isEmpty();
        assertThat(report.warning())
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.envVar()).isEqualTo("JUSTAUTH_QQ_CLIENT_SECRET");
                    assertThat(f.tier()).isEqualTo(ProdConfigFailFastValidator.Tier.WARNING);
                });
        assertThat(fullyConfigured().getProperty("justauth.type.qq.client-secret", "").toUpperCase(Locale.ROOT))
                .as("自检：fullyConfigured 确实提供了该键，场景3 的空串覆盖才有效")
                .isNotEmpty();

        assertThatCode(() -> runnerFor(env).run(new DefaultApplicationArguments()))
                .as("仅警告时 Runner 必须放行")
                .doesNotThrowAnyException();
    }
}
