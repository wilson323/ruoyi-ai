package org.ruoyi.ipd.menu;

import org.ruoyi.system.domain.vo.MetaVo;
import org.ruoyi.system.domain.vo.RouterVo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 IPD 分组侧栏收成五个常用入口。
 *
 * <p>只改 {@code /api/v1/system/menu/getRouters} 的返回树。不删路由节点，
 * 不改 {@code sys_menu} / {@code sys_role_menu}。已经隐藏的入口保持隐藏，
 * 避免把产品目录等权限扩给本来没有这项菜单的人。</p>
 */
public final class IpdPrimaryNavigation {

    /** 侧栏顺序：我的工作台、产品线、产品目录、我的项目、资料库。 */
    public static final List<String> PRIMARY_PATHS = List.of(
        "workbench",
        "product-lines",
        "product-catalog",
        "projects",
        "documents"
    );

    private static final Map<String, String> TITLES = Map.of(
        "workbench", "我的工作台",
        "product-lines", "产品线",
        "product-catalog", "产品目录",
        "projects", "我的项目",
        "documents", "资料库"
    );

    private IpdPrimaryNavigation() {
    }

    /**
     * 收敛 IPD 根下的直接子菜单。
     *
     * @param routers getRouters 即将返回的路由树，允许 null
     */
    public static void apply(List<RouterVo> routers) {
        if (routers == null) {
            return;
        }
        for (RouterVo router : routers) {
            if (router != null && isIpdRoot(router.getPath())) {
                converge(router.getChildren());
            }
        }
    }

    /**
     * 判断是不是 IPD 菜单根。
     *
     * @param path 路由 path，可能是 ipd 或 /ipd
     * @return 是 IPD 根时为 true
     */
    static boolean isIpdRoot(String path) {
        return "ipd".equals(path) || "/ipd".equals(path);
    }

    /**
     * 取路径最后一段，用来对齐 sys_menu.path。
     *
     * @param path 子路由 path
     * @return 最后一段；空路径返回空串
     */
    static String pathKey(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String trimmed = path.charAt(0) == '/' ? path.substring(1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    private static void converge(List<RouterVo> children) {
        if (children == null || children.isEmpty()) {
            return;
        }
        for (RouterVo child : children) {
            if (child == null) {
                continue;
            }
            String key = pathKey(child.getPath());
            if (PRIMARY_PATHS.contains(key)) {
                rename(child, TITLES.get(key));
            } else {
                child.setHidden(true);
            }
        }
        List<RouterVo> ordered = new ArrayList<>();
        for (String key : PRIMARY_PATHS) {
            for (RouterVo child : children) {
                if (child != null && key.equals(pathKey(child.getPath()))) {
                    ordered.add(child);
                }
            }
        }
        for (RouterVo child : children) {
            if (child != null && !PRIMARY_PATHS.contains(pathKey(child.getPath()))) {
                ordered.add(child);
            }
        }
        children.clear();
        children.addAll(ordered);
    }

    private static void rename(RouterVo child, String title) {
        if (child.getMeta() == null) {
            child.setMeta(new MetaVo(title, child.getPath()));
            return;
        }
        child.getMeta().setTitle(title);
    }
}
