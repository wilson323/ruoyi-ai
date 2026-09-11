package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.system.domain.SysMenu;
import org.ruoyi.system.domain.vo.RouterVo;
import org.ruoyi.system.domain.vo.SysUserVo;
import org.ruoyi.system.service.ISysMenuService;
import org.ruoyi.system.service.ISysUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IPD 业务菜单接口（禁止双轨，2026-09-10）。
 *
 * <p>接受 IPD 业务 sa-token（type=ipd），不复用平台 sys_user 会话。
 * 复用基线 {@link ISysMenuService} 拿前端路由结构，但用户身份走
 * {@link IpdAuthSession#currentPerson()}，按 persons.username 关联到 sys_user.user_id
 * 后再查菜单树。</p>
 *
 * <p>前端 vite 代理规则（apps/web-antd/vite.config.mts）：
 * <ul>
 *   <li>/api/v1/** 保留前缀转发到 16039（命中本控制器）</li>
 *   <li>/api/** 其它吞掉 /api 前缀转发到 16039（命中兄弟会话 SysMenuController）</li>
 * </ul>
 * 因此前端必须调 /api/v1/system/menu/getRouters 才能命中本接口；
 * 兄弟会话 /system/menu/getRouters 走平台 sa-token（默认 loginType=""），
 * IPD 业务 token（type=ipd）调它会被 SaToken 拦截器挡掉（401 客户端 ID 与 Token 不匹配）。
 * </p>
 *
 * @author IPD 整合收口
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/system/menu")
public class IpdMenuController {

    private final IpdAuthSession ipdAuthSession;
    private final ISysMenuService menuService;
    private final ISysUserService sysUserService;

    /**
     * 获取当前 IPD 用户的路由（菜单）树。
     *
     * <p>流程：IPD token → {@code currentPerson()} → persons.username →
     * sys_user.user_id → {@code selectMenuTreeByUserId(uid)} →
     * {@code buildMenus} → RouterVo 列表。</p>
     *
     * @return 前端路由列表（ApiV1Response，code=0 包络，禁用框架 code200）
     */
    @SaCheckLogin(type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/getRouters")
    public ApiV1Response<List<RouterVo>> getRouters() {
        Person person = ipdAuthSession.currentPerson();
        SysUserVo sysUser = sysUserService.selectUserByUserName(person.getUsername());
        if (sysUser == null) {
            // persons.username 没在 sys_user.username 找到 — 兄弟会话 PersonSyncJob 未跑过该人员
            // 返回空路由，让前端 layouts/ipd.vue 走零菜单态（accessMode=backend 真生效的硬约束）
            log.warn("ipd_menu_getRouters status=EMPTY reason=SYS_USER_NOT_FOUND personId={} username={}",
                person.getId(), person.getUsername());
            return ApiV1Response.ok(List.of());
        }
        Long userId = sysUser.getUserId();
        List<SysMenu> menus = menuService.selectMenuTreeByUserId(userId);
        List<RouterVo> routers = menuService.buildMenus(menus);
        // 单企业非 SaaS（owner 2026-09-06 决策）：租户管理菜单不展示。
        // 决策实现点 2026-09-10 由前端 generatePlatformAccess 过滤迁到本处（单一事实源，禁止双轨）：
        // 后端不返回 → 前端注册不了、侧边栏不显示、URL 直访 404、验证清单不含，五处自动一致。
        // 只裁本 IPD 端点的返回值，不改 sys_menu/sys_role_menu RBAC 数据；
        // 平台通道 /system/menu/getRouters 原样不动（仅前端过滤时它仍可返回）。
        routers.removeIf(router -> "tenant".equals(router.getPath()) || "/tenant".equals(router.getPath()));
        // IPD 前端未迁入 URL 管理页（system/url/index），菜单树若原样返回，
        // 前端 packages/utils/src/helpers/generate-routes-backend.ts 每次生成路由都会打
        // 「未找到对应组件」告警。与 tenant 同策：只裁本端点返回值（单一事实源）。
        // 该菜单嵌在「系统管理」目录 children 中，必须递归下钻而非只扫顶层。
        removeNestedByComponent(routers, "system/url/index");
        log.info("ipd_menu_getRouters status=OK personId={} sysUserId={} username={} menus={} routers={}",
            person.getId(), userId, person.getUsername(), menus.size(), routers.size());
        return ApiV1Response.ok(routers);
    }

    /**
     * 递归裁剪指定 component 的路由节点（含任意层级子路由）。
     *
     * @param routers   待裁剪的路由树（原地修改）
     * @param component 目标组件地址，如 system/url/index
     */
    private static void removeNestedByComponent(List<RouterVo> routers, String component) {
        routers.removeIf(router -> component.equals(router.getComponent()));
        for (RouterVo router : routers) {
            if (router.getChildren() != null && !router.getChildren().isEmpty()) {
                removeNestedByComponent(router.getChildren(), component);
            }
        }
    }
}
