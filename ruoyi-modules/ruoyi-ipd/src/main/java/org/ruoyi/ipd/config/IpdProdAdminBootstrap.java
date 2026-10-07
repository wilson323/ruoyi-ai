package org.ruoyi.ipd.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 生产环境「第一个人」引导（2026-10-03 补）。
 *
 * <p><b>为什么需要这个类</b>：{@code IpdAuthService.login} 只查 {@code persons} 表，而全仓写入
 * {@code persons} 的初始化器（{@code IpdMockDataInitializer}、{@code IpdZkScenarioInitializer}）
 * 全部标了 {@code @Profile("dev")}，没有任何一条 SQL 往 {@code persons} 写行。于是换 prod 档 +
 * 全新库启动时 {@code persons} 是空的 —— <b>公司里没有一个人能登录</b>，登录接口只会返回
 * 「用户名或密码错误」，且这个失败与「密码打错了」在日志里长得一模一样。
 *
 * <p><b>为什么落点是 {@code persons} 而不是 {@code sys_user}</b>：平台基座脚本
 * {@code docs/script/sql/ruoyi-ai.sql} 已经建好 {@code sys_user} 的 {@code admin}（user_id=1）
 * 并绑定了超管角色；{@code MenuController.getRouters} 是按 {@code persons.username} 反查
 * {@code sys_user.user_name} 取菜单树的，而 {@code SysMenuServiceImpl.selectMenuTreeByUserId}
 * 对超管 id 走「返回全部菜单」的旁路。因此只要让 {@code persons} 里存在一条
 * {@code username} = 基座 admin 的记录，登录与菜单<b>同时</b>成立，无需另建账号体系。
 *
 * <p><b>三重安全约束</b>：
 * <ol>
 *   <li><b>默认关闭</b>——{@code ipd.bootstrap.enabled} 缺省 {@code false}，不设这个开关本类
 *       什么都不做。生产是否引导第一个人由部署者显式决定，不由代码替他们决定；</li>
 *   <li><b>只在空库生效</b>——{@code persons} 只要已有任意一行就整体跳过，因此重复启动、
 *       已有数据的库都不会被改写，也不可能覆盖真实人员数据；</li>
 *   <li><b>口令只从环境变量来</b>——复用既有 {@code ipd.security.initial-password}
 *       （prod 由 {@code IPD_INITIAL_PWD} 注入）。本类不含任何口令字面量；该值为空时
 *       <b>拒绝创建并报错</b>，绝不落一个「默认口令」的账号进生产库。</li>
 * </ol>
 *
 * <p>创建出来的账号一律 {@code must_change_pwd='1'}，首次登录即被要求改密。
 *
 * <p>租户：{@code persons} 已登记在本仓 {@code tenant.excludes}，启动期无租户上下文也不会被过滤吞掉。
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class IpdProdAdminBootstrap implements ApplicationRunner {

    private final PersonMapper personMapper;

    /** 引导开关，缺省关闭。 */
    @Value("${ipd.bootstrap.enabled:false}")
    private boolean enabled;

    /**
     * 2026-10-07 新增：把「当前处于关闭/降级状态的外部能力」在启动时明确报出来。
     *
     * <p>为什么需要（这是本项目反复吃的一个亏的通用解法）：
     * 能力靠 {@code @ConditionalOnProperty} 或带默认值的配置控制时，条件不满足会导致
     * <b>Bean 整个不装配、端点不存在、而启动零提示</b>。从外部完全看不出「这条路是断的」，
     * 只会当成「本来就没消息」——即本仓反复记录的「失败长得像成功」。
     *
     * <p>这里<b>只做可见性，不改任何默认值</b>：开不开是产品取舍，不是 bug。
     * 但「没开」这件事必须让人看见，否则无法排障。
     */
    void reportDisabledCapabilities() {
        for (String msg : collectDisabledCapabilities()) {
            log.warn("[IPD][Capability] {}", msg);
        }
    }


    /**
     * 2026-10-07：把「探测结论」与「打日志」分离。
     *
     * <p>为什么要分离：原来只有 {@code log.warn} 这一条出口，测试只能去挂 logback 追加器抓日志——
     * 而那引入了两个与被测逻辑无关的脆弱点（追加器跨用例污染、日志级别过滤），
     * 本次写测试时因此连错两次。**判断本身不依赖日志系统，才是可测的。**
     *
     * <p>返回的是「需要告知运维的降级/关闭项」清单；{@code reportDisabledCapabilities()}
     * 只负责把它逐条 warn 出来。
     */
    List<String> collectDisabledCapabilities() {
        List<String> out = new ArrayList<>();
        if (env == null) {
            return out;
        }
        String ws = env.getProperty("ipd.websocket.enabled");
        if (!"true".equalsIgnoreCase(ws)) {
            out.add("实时推送未开启（ipd.websocket.enabled=" + ws + "）：WebSocket 端点不存在，"
                    + "通知将退化为站内信；需要实时推送请设 IPD_WEBSOCKET_ENABLED=true。");
        }
        String vsType = env.getProperty("vector-store.type", "weaviate");
        String vsHost = env.getProperty("vector-store." + resolveHostKey(vsType),
                env.getProperty("vector-store.weaviate.host", "127.0.0.1"));
        if (!isPortOpen(vsHost, resolvePort(vsType))) {
            out.add("向量库不可达（type=" + vsType + ", host=" + vsHost + "）：语义/向量检索不可用；"
                    + "关键词检索不依赖向量库仍可用；上传解析会自动降级为仅写 MySQL 切片。");
        }
        return out;
    }

    private static String resolveHostKey(String type) {
        return "qdrant".equalsIgnoreCase(type) ? "qdrant.host" : type.toLowerCase() + ".host";
    }

    private int resolvePort(String type) {
        if ("qdrant".equalsIgnoreCase(type)) {
            return Integer.parseInt(env.getProperty("vector-store.qdrant.port", "6333"));
        }
        if ("milvus".equalsIgnoreCase(type)) {
            return Integer.parseInt(env.getProperty("vector-store.milvus.port", "19530"));
        }
        // weaviate 容器内端口 8080，宿主机映射按本仓 compose 为 28080
        return Integer.parseInt(env.getProperty("vector-store.weaviate.port", "28080"));
    }

    /** 极轻量 TCP 探测；不可用时返回 false，绝不抛异常影响启动。 */
    /**
     * 2026-10-07 修正第二版：改用 {@link InetAddress#isReachable} 语义上仍不稳，
     * 故直接采用「先 connect，再验 {@code getInputStream} 能真正建链」的判据。
     *
     * <p>为什么不能用 {@code connect(...)} 的返回值：实测在本机对不可达保留地址
     * {@code 192.0.2.1} connect 返回后 {@code isConnected()} 仍可能为 true
     * （取决于 JDK 对已排队 SYN 的处理），会把它误判成「可达」⇒ 降级警告不发出
     * ⇒ 又一次「失败长得像成功」。**判据必须能区分「真的建了链」和「只是尝试过」。**
     */
    /** 端口探测函数；测试可注入确定性结果，避免依赖真实网络状态。 */
    interface PortProbe {
        boolean isOpen(String host, int port);
    }

    private volatile PortProbe portProbe = IpdProdAdminBootstrap::probeRealPort;

    /** 测试注入点；不设则走真实 TCP 探测。 */
    void setPortProbe(PortProbe probe) {
        this.portProbe = probe == null ? IpdProdAdminBootstrap::probeRealPort : probe;
    }

    private boolean isPortOpen(String host, int port) {
        try {
            return portProbe.isOpen(host, port);
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean probeRealPort(String host, int port) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(host, port), 300);
            // 双保险：既看连接状态，也真正取一次流以确认链路已建立。
            s.setSoTimeout(300);
            s.getInputStream();
            // 2026-10-07：connect 返回后**必须**再验 isConnected。
            // 抓 bug 时实测：对不可达保留地址 192.0.2.1，connect 返回后 isConnected 仍为
            // false，而只写 `connect(); return true;` 会把它误判成「可达」。
            return s.isConnected() && !s.isClosed();
        } catch (Exception ex) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) { /* 关闭失败不影响判定 */ }
        }
    }

    /** 首个账号的用户名，需与基座 {@code sys_user.user_name} 一致才能取到菜单树。 */
    @Value("${ipd.bootstrap.admin-username:admin}")
    /**
     * 供 reportDisabledCapabilities() 读取当前开关状态。
     * 刻意**不加 final**：加了会让 @RequiredArgsConstructor 多出一个参数，
     * 破坏既有的 IpdProdAdminBootstrapTest（它按单参构造）。故改为可选注入。
     */
    private Environment env;

    /** 测试用注入点；生产环境由 Spring 字段注入兜底（为 null 时该段自检跳过）。 */
    @Autowired(required = false)
    void setEnvironment(Environment env) {
        this.env = env;
    }
    private String adminUsername;

    /** 首个账号姓名。 */
    @Value("${ipd.bootstrap.admin-name:系统管理员}")
    private String adminName;

    /** 初始口令来源，与 dev 档同一配置键；prod 必须由 {@code IPD_INITIAL_PWD} 注入。 */
    @Value("${ipd.security.initial-password:}")
    private String initialPassword;

    @Override
    public void run(ApplicationArguments args) {
        // 2026-10-07 修（根因修复 2/4）：**把「没开的外部依赖」在启动时说清楚**。
        //
        // 病根：这些能力靠 @ConditionalOnProperty / 配置默认值控制，
        // 条件不满足时**整个 Bean 不装配、端点不存在、且启动零提示**——
        // 从外面看不出「这条路是断的」，只会以为「没消息而已」。
        // 本仓 2026-10-07 实测两例：
        //   · ipd.websocket.enabled 默认 false ⇒ WebSocket 端点整个不存在，
        //     通知静默退化为站内信，这条实时推送的路是断的而无任何提示；
        //   · vector-store 默认 weaviate@28080，而该进程未启动
        //     ⇒ 解析在写向量库那步失败 ⇒ 切片也写不进 MySQL（已另处修复为降级不阻断）。
        //
        // 只做「可见」，不改任何默认值——开不开是产品取舍，不是 bug。
        reportDisabledCapabilities();

        if (!enabled) {
            log.info("[IPD][Bootstrap] 未开启（ipd.bootstrap.enabled=false），跳过首个账号引导。"
                + "如需在全新库创建第一个可登录账号，请显式设置该开关与 IPD_INITIAL_PWD。");
            return;
        }

        long existing = personMapper.selectCount(new LambdaQueryWrapper<>());
        if (existing > 0) {
            log.info("[IPD][Bootstrap] persons 已有 {} 行，跳过引导（本类只在空库生效，不改写任何既有数据）。", existing);
            return;
        }

        if (initialPassword == null || initialPassword.isBlank()) {
            // 明确失败而不是造一个默认口令账号：静默给生产库留一个可猜口令的超管，
            // 比启动失败危险得多。
            throw new IllegalStateException(
                "[IPD][Bootstrap] 已开启首个账号引导，但 ipd.security.initial-password 为空。"
                    + "请注入环境变量 IPD_INITIAL_PWD 后重启；本类不会使用任何默认口令。");
        }

        Person admin = Person.builder()
            .name(adminName)
            .employeeNo("IPD-BOOTSTRAP-1")
            .personType("SUPER_ADMIN")
            .groupId(null)
            .levelSource("BOOTSTRAP")
            .accountStatus("ACTIVE")
            .employmentStatus("ACTIVE")
            .username(adminUsername)
            // 与 IpdMockDataInitializer 同口径：bcrypt cost 10，源码不含口令字面量。
            .passwordHash(BCrypt.hashpw(initialPassword, BCrypt.gensalt(10)))
            .mustChangePwd("1")
            .remark("生产首个账号，由 IpdProdAdminBootstrap 引导创建，请首次登录后立即改密。")
            .build();
        admin.setCreateTime(new Date());
        personMapper.insert(admin);

        log.warn("[IPD][Bootstrap] 已在空库创建首个账号 username={} personId={}，"
            + "mustChangePwd=1。请首次登录后立即改密。", adminUsername, admin.getId());

        // 菜单树按 persons.username → sys_user.user_name 反查。基座脚本会建 admin，
        // 但若目标库没跑基座脚本，就会出现「登录能过、侧边栏全空」——这种半通状态
        // 极难排查，所以在这里直接喊出来。不代建平台账号：sys_user 的建号与角色绑定
        // 属平台 RBAC，不由业务初始化器代劳。
        log.warn("[IPD][Bootstrap] 请确认平台表 sys_user 中存在 user_name='{}' 且已绑定超管角色。"
            + "若不存在，该账号能登录但侧边栏为空（菜单树按 persons.username 反查 sys_user）。"
            + "基座脚本 docs/script/sql/ruoyi-ai.sql 默认创建该账号。", adminUsername);
    }
}
