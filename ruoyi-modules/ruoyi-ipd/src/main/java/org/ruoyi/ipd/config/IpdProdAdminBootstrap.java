package org.ruoyi.ipd.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Date;

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

    /** 首个账号的用户名，需与基座 {@code sys_user.user_name} 一致才能取到菜单树。 */
    @Value("${ipd.bootstrap.admin-username:admin}")
    private String adminUsername;

    /** 首个账号姓名。 */
    @Value("${ipd.bootstrap.admin-name:系统管理员}")
    private String adminName;

    /** 初始口令来源，与 dev 档同一配置键；prod 必须由 {@code IPD_INITIAL_PWD} 注入。 */
    @Value("${ipd.security.initial-password:}")
    private String initialPassword;

    @Override
    public void run(ApplicationArguments args) {
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
