package org.ruoyi.ipd.config;

import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 生产环境必需配置校验器（治本：把「静默降级」变成「快速失败」）。
 *
 * <p><b>为什么存在</b>：{@code application-prod.yml} 里所有凭证占位符都写成 {@code ${VAR:}}（带空默认值），
 * 形式上像「缺失即失败」，实际零个会失败。而 {@code scripts/start.sh:92} 用 {@code exec env -i} 清空环境，
 * 白名单只有 5 个变量。两者叠加的结果不是启动报错，而是<b>服务照常启动、照常连上本机默认库，
 * 但 JWT 签名密钥为空、数据库账号密码为空</b>——安全性和可用性一起塌，零报错。
 *
 * <p><b>判定依据</b>（不是猜的，逐条对过字节）：
 * <ul>
 *   <li>{@code sa-token.jwt-secret-key} —— 被 {@code SaTokenConfig#getStpLogicJwt} 的
 *       {@code StpLogicJwtForSimple}（sa-token-jwt）消费，密钥为空则签发/校验链路失效。</li>
 *   <li>{@code spring.datasource.dynamic.datasource.master.*} —— 动态数据源 master，
 *       {@code strict: true}，账号密码空则连接直接失败；url 指向回环地址说明环境变量没注进来、
 *       静默吃了 {@code :jdbc:mysql://127.0.0.1:3306/ruoyi-ai} 兜底。</li>
 *   <li>{@code api-decrypt.*} —— 父 {@code application.yml} 里 {@code enabled: false}，
 *       所以只在「开了才致命」这一档；注释宣称的「留空即启动失败」实际由
 *       {@code RsaEncryptor#encrypt} 的空私钥检查兜底，是运行时不是启动时。</li>
 *   <li>{@code justauth.type.*.client-secret}、{@code sms.blends.*}、{@code mail.*}、
 *       {@code snail-job.token}、{@code ipd.hr.*} —— 全部有 enabled 开关或属可选集成，
 *       缺失只让对应功能不可用，不影响服务存活，归警告档。</li>
 *   <li><b>关于 OSS</b>：本仓生产 OSS 凭据<b>不走环境变量</b>——{@code SystemApplicationRunner} 调
 *       {@code ISysOssConfigService.init()}，配置存在 {@code sys_oss_config} 表里。
 *       所以「生产 OSS 凭据缺失」没有可校验的环境变量，故本类不含 OSS 规则（详见报告）。</li>
 * </ul>
 *
 * <p><b>只读、不改配置</b>：本类只读 {@link Environment} 的已解析值，不写回任何属性；
 * 是否拒绝启动由 {@link ProdConfigFailFastRunner} 决定。
 */
public final class ProdConfigFailFastValidator {

    /** 档位：致命档 = 缺了必须拒绝启动；警告档 = 缺了启动但要打醒目告警。 */
    public enum Tier {
        /** 缺了必须拒绝启动。 */
        FATAL,
        /** 缺了仍启动，但对应功能不可用，打醒目告警。 */
        WARNING
    }

    /**
     * 一条校验结论。
     *
     * @param configKey   实际配置键（用于定位 Spring 属性）
     * @param envVar      对应的环境变量名（运维真正要 export 的东西）
     * @param tier        档位
     * @param consequence 缺失后的具体后果，报错信息里逐条展示
     */
    public record Finding(Tier tier, String configKey, String envVar, String consequence) {
    }

    /**
     * 一次校验的完整结论。
     *
     * @param fatal   致命档清单，非空即拒绝启动
     * @param warning 警告档清单，仅告警
     */
    public record Report(List<Finding> fatal, List<Finding> warning) {

        public boolean failed() {
            return !fatal.isEmpty();
        }
    }

    /**
     * 一条「空值即报错」规则。
     *
     * @param configKey   配置键
     * @param envVar      环境变量名
     * @param triggerKey  开关配置键；为 {@code null} 表示无条件生效；开关为 true 时才校验
     * @param consequence 缺失后果
     * @param tier        档位
     */
    private record Rule(String configKey, String envVar, String triggerKey, String consequence, Tier tier) {
    }

    private static final String DS_MASTER = "spring.datasource.dynamic.datasource.master.";

    /**
     * 致命档 + 警告档全部规则。判定依据见类注释。
     */
    private static final List<Rule> RULES = buildRules();

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();

        // ---- 致命档：无条件生效，prod 缺了就必须拒绝启动 ----

        rules.add(new Rule("sa-token.jwt-secret-key", "SA_TOKEN_JWT_SECRET_KEY", null,
                "JWT 签发密钥为空：Sa-Token 的 StpLogicJwtForSimple 签不出可用令牌，"
                        + "已登录会话全部失效，且任何人都能伪造 FULL 身份令牌越权",
                Tier.FATAL));

        rules.add(new Rule(DS_MASTER + "username", "SPRING_DATASOURCE_USERNAME", null,
                "主库账号为空：动态数据源 strict=true，连接直接认证失败，所有读接口 500",
                Tier.FATAL));

        rules.add(new Rule(DS_MASTER + "password", "SPRING_DATASOURCE_PASSWORD", null,
                "主库口令为空：同上，连接认证失败，所有读接口 500",
                Tier.FATAL));

        // ---- 致命档：接口加密开启时才致命（enabled 默认 false，未开不校验） ----

        rules.add(new Rule("api-decrypt.privateKey", "API_DECRYPT_PRIVATE_KEY", "api-decrypt.enabled",
                "已开启 api-decrypt.enabled 但私钥为空：请求解密在收到第一个加密包时报错，"
                        + "整个加密接口面不可用（application-prod.yml:298 注释宣称「启动即失败」与实现不符，"
                        + "实际是运行时由 RsaEncryptor 的空私钥检查兜底）",
                Tier.FATAL));

        rules.add(new Rule("api-decrypt.publicKey", "API_DECRYPT_PUBLIC_KEY", "api-decrypt.enabled",
                "已开启 api-decrypt.enabled 但响应加密公钥为空：响应无法加密，客户端解密失败",
                Tier.FATAL));

        // ---- 警告档：可选集成，缺了只降级 ----

        rules.add(new Rule("ipd.security.initial-password", "IPD_INITIAL_PWD", null,
                "初始口令为空。注：当前 prod 下本项无消费方（只有 @Profile(\"dev\") 的 "
                        + "IpdMockDataInitializer / IpdZkScenarioInitializer 读它），"
                        + "所以 prod 缺它不会让服务起不来；但 application-prod.yml:292 注释"
                        + "宣称「缺失则启动 fail-fast」是失实的，请一并修正注释",
                Tier.WARNING));

        rules.add(new Rule("mail.user", "MAIL_USER", "mail.enabled",
                "已开启 mail.enabled 但发件账号为空：所有邮件通知发不出去，"
                        + "IPD 待办/评审到期提醒会静默丢失",
                Tier.WARNING));

        rules.add(new Rule("mail.pass", "MAIL_PASSWORD", "mail.enabled",
                "已开启 mail.enabled 但邮箱授权码为空：SMTP 认证失败，邮件通知全部发不出去",
                Tier.WARNING));

        rules.add(new Rule("snail-job.token", "SNAIL_JOB_TOKEN", "snail-job.enabled",
                "已开启 snail-job.enabled 但接入令牌为空：任务调度客户端注册失败，分布式定时任务不执行",
                Tier.WARNING));

        rules.add(new Rule("spring.boot.admin.client.password", "MONITOR_PASSWORD",
                "spring.boot.admin.client.enabled",
                "已开启监控客户端但口令为空：Spring Boot Admin 注册鉴权失败，监控台看不到本实例",
                Tier.WARNING));

        rules.add(new Rule("ipd.hr.app-id", "IPD_HR_APP_ID", "ipd.hr.enabled",
                "已开启 ipd.hr.enabled 但 appId 为空：HR 人员同步调用失败，"
                        + "组织架构/人员数据不再自动更新（GA-HR-Sync 真源断链）",
                Tier.WARNING));

        rules.add(new Rule("ipd.hr.secret-key", "IPD_HR_SECRET_KEY", "ipd.hr.enabled",
                "已开启 ipd.hr.enabled 但 secretKey 为空：同上，HR 真源认证失败",
                Tier.WARNING));

        rules.add(new Rule("ipd.hr.base-url", "IPD_HR_BASE_URL", "ipd.hr.enabled",
                "已开启 ipd.hr.enabled 但接口地址为空：HR 同步无目标地址",
                Tier.WARNING));

        // 16 个第三方登录渠道：逐个列，全空时前端「三方登录」按钮整体不可用
        for (String channel : new String[]{"maxkey", "topiam", "qq", "weibo", "gitee", "dingtalk", "baidu",
                "csdn", "coding", "oschina", "alipay_wallet", "wechat_open", "wechat_mp", "wechat_enterprise",
                "gitlab", "gitea"}) {
            rules.add(new Rule("justauth.type." + channel + ".client-secret",
                    "JUSTAUTH_" + channel.toUpperCase(Locale.ROOT) + "_CLIENT_SECRET", null,
                    "第三方登录渠道 " + channel + " 的 client-secret 为空：该渠道登录按钮不可用"
                            + "（其余渠道不受影响）",
                    Tier.WARNING));
        }

        // 短信两家供应商
        for (String[] supplier : new String[][]{
                {"config1", "alibaba", "SMS_ALIBABA"},
                {"config2", "tencent", "SMS_TENCENT"}}) {
            String node = supplier[0];
            String name = supplier[1];
            String prefix = supplier[2];
            rules.add(new Rule("sms.blends." + node + ".access-key-id", prefix + "_ACCESS_KEY_ID", null,
                    "短信供应商 " + name + " 的 access-key-id 为空：该供应商短信发不出去", Tier.WARNING));
            rules.add(new Rule("sms.blends." + node + ".access-key-secret", prefix + "_ACCESS_KEY_SECRET", null,
                    "短信供应商 " + name + " 的 access-key-secret 为空：该供应商短信发不出去", Tier.WARNING));
            rules.add(new Rule("sms.blends." + node + ".signature", prefix + "_SIGNATURE", null,
                    "短信供应商 " + name + " 的签名未配置：该供应商短信发不出去", Tier.WARNING));
        }

        return List.copyOf(rules);
    }

    /**
     * 消费一段已解析的 Spring 配置，产出致命档 + 警告档清单。
     *
     * <p>本方法不读进程环境变量，读的是 Spring 解析后的属性值——这样 env 注入、
     * JVM {@code -D} 注入、配置文件直写三条注入路径都会被覆盖到，且与运行期实际生效值一致。
     *
     * @param env 已完成 profile 与占位符解析的 Spring 环境
     * @return 校验结论；{@link Report#failed()} 为 true 表示必须拒绝启动
     */
    public Report inspect(Environment env) {
        List<Finding> fatal = new ArrayList<>();
        List<Finding> warning = new ArrayList<>();

        for (Rule rule : RULES) {
            if (rule.triggerKey() != null && !isEnabled(env, rule.triggerKey())) {
                continue;
            }
            if (isBlank(env.getProperty(rule.configKey()))) {
                Finding finding = new Finding(rule.tier(), rule.configKey(), rule.envVar(), rule.consequence());
                (rule.tier() == Tier.FATAL ? fatal : warning).add(finding);
            }
        }

        inspectDatasourceUrl(env, fatal, warning);
        inspectUploadPath(env, warning);

        return new Report(List.copyOf(fatal), List.copyOf(warning));
    }

    /**
     * 数据库地址专项：能解析出 url，但 url 指向回环地址 = 环境变量没注进来，
     * 静默吃了 {@code application-prod.yml:68} 的本地兜底。这种情况连不上就被发现不了，
     * 因为本机确实有库——所以按「连错库」这个真实后果来定档。
     */
    private void inspectDatasourceUrl(Environment env, List<Finding> fatal, List<Finding> warning) {
        String url = env.getProperty(DS_MASTER + "url");
        if (isBlank(url)) {
            fatal.add(new Finding(Tier.FATAL, DS_MASTER + "url",
                    "SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL",
                    "主库地址为空：动态数据源无法初始化，服务启动即失败"));
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        boolean loopback = lower.contains("127.0.0.1") || lower.contains("localhost") || lower.contains("//:3306");
        if (loopback) {
            fatal.add(new Finding(Tier.FATAL, DS_MASTER + "url",
                    "SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL",
                    "主库地址落在回环地址（" + url + "）：说明部署环境的真实库地址没注进来，"
                            + "服务静默连上了本机 3306 的 ruoyi-ai 库。scripts/start.sh:92 的 env -i "
                            + "只白名单了 5 个变量，本项被清空后正是这个静默兜底——"
                            + "服务能启动、能连库，但连的是错的库"));
        }
    }

    /**
     * 上传目录专项：{@code application-prod.yml:24,105} 的 {@code sys.upload.path} 仍是
     * {@code D:\\DownLoad}。这不是缺失，是「照抄了 Windows 开发机路径」。
     * 在 Linux 容器上会创建一个名字字面量就叫 {@code D:\DownLoad} 的目录，功能看起来是通的。
     */
    private void inspectUploadPath(Environment env, List<Finding> warning) {
        String path = env.getProperty("sys.upload.path");
        if (path == null || path.isBlank()) {
            return;
        }
        if (path.contains("\\") || path.matches("^[A-Za-z]:.*")) {
            warning.add(new Finding(Tier.WARNING, "sys.upload.path", "（无环境变量，yml 硬编码）",
                    "上传目录是 Windows 路径 \"" + path + "\"：在 Linux 容器上会创建一个名字字面量"
                            + "就叫 " + path + " 的目录，磁盘写满或权限异常时才会暴露。"
                            + "请改为容器内绝对路径（如 /data/upload）"));
        }
    }

    private static boolean isEnabled(Environment env, String key) {
        return env.getProperty(key, Boolean.class, Boolean.FALSE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 把结论渲染成可直接照着改的多行消息。
     *
     * @param report 校验结论
     * @return 人类可读的清单文本
     */
    public static String render(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("╔══════════════════════════════════════════════════════════════════════╗").append(System.lineSeparator());
        sb.append("║ 生产环境必需配置校验失败：已拒绝启动（prod profile）").append(System.lineSeparator());
        sb.append("╚══════════════════════════════════════════════════════════════════════╝").append(System.lineSeparator());
        if (!report.fatal().isEmpty()) {
            sb.append("【致命】共 ").append(report.fatal().size()).append(" 项，缺失任一即服务不可用或鉴权失效：")
                    .append(System.lineSeparator());
            appendFindings(sb, report.fatal(), "✗");
        }
        if (!report.warning().isEmpty()) {
            sb.append("【警告】共 ").append(report.warning().size()).append(" 项，服务能启动但对应功能不可用：")
                    .append(System.lineSeparator());
            appendFindings(sb, report.warning(), "!");
        }
        sb.append("处理方式：在启动进程的环境里 export 对应变量（注意 scripts/start.sh:92 的 exec env -i ")
                .append("只保留 5 个变量，export 后仍需加进该脚本的 PRESERVED_ENV 白名单），然后重启。")
                .append(System.lineSeparator());
        return sb.toString();
    }

    private static void appendFindings(StringBuilder sb, List<Finding> findings, String mark) {
        int i = 0;
        for (Finding finding : findings) {
            sb.append("  ").append(++i).append(". [").append(mark).append("] ").append(finding.envVar())
                    .append(System.lineSeparator());
            sb.append("       配置键: ").append(finding.configKey()).append(System.lineSeparator());
            sb.append("       后果  : ").append(finding.consequence()).append(System.lineSeparator());
        }
    }
}
