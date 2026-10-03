package org.ruoyi.ipd.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.system.domain.vo.MetaVo;
import org.ruoyi.system.domain.vo.RouterVo;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导航收敛：五个常用入口留在侧栏，其余 IPD 子菜单隐藏但仍在返回树里。
 */
@Tag("dev")
class IpdPrimaryNavigationTest {

    @Test
    @DisplayName("常用入口改名并排到前面，招募绩效报表移交和产品空间隐藏但还在")
    void keepsFiveEntriesAndHidesTheRest() {
        RouterVo ipd = root("/ipd", List.of(
            item("projects", "项目空间", false),
            item("bids", "研发招募", false),
            item("product-lines", "产品线空间", false),
            item("products", "产品空间", false),
            item("performance", "协同绩效", false),
            item("documents", "资料库", false),
            item("reports", "报表分析", false),
            item("handover", "项目移交", false),
            item("workbench", "我的工作台", false),
            item("product-catalog", "产品目录", false)
        ));

        IpdPrimaryNavigation.apply(List.of(ipd));

        assertThat(titles(ipd)).containsExactly(
            "我的工作台",
            "产品线",
            "产品目录",
            "我的项目",
            "资料库",
            "研发招募",
            "产品空间",
            "协同绩效",
            "报表分析",
            "项目移交"
        );
        assertThat(hiddenFlags(ipd)).containsExactly(
            false, false, false, false, false,
            true, true, true, true, true
        );
        assertThat(paths(ipd)).contains(
            "bids", "products", "performance", "reports", "handover"
        );
    }

    @Test
    @DisplayName("本来就隐藏的产品目录保持隐藏")
    void doesNotRevealHiddenCatalog() {
        RouterVo ipd = root("ipd", List.of(
            item("workbench", "我的工作台", false),
            item("product-catalog", "产品目录", true)
        ));

        IpdPrimaryNavigation.apply(List.of(ipd));

        RouterVo catalog = ipd.getChildren().get(1);
        assertThat(catalog.getHidden()).isTrue();
        assertThat(catalog.getMeta().getTitle()).isEqualTo("产品目录");
    }

    @Test
    @DisplayName("非 IPD 分组里的同名路径不改")
    void leavesOtherGroupsAlone() {
        RouterVo system = root("/system", List.of(item("projects", "系统项目", false)));

        IpdPrimaryNavigation.apply(List.of(system));

        assertThat(system.getChildren().get(0).getMeta().getTitle()).isEqualTo("系统项目");
        assertThat(system.getChildren().get(0).getHidden()).isFalse();
    }

    @Test
    @DisplayName("空树不抛异常")
    void ignoresNull() {
        IpdPrimaryNavigation.apply(null);
        RouterVo ipd = root("/ipd", null);
        IpdPrimaryNavigation.apply(List.of(ipd));
        assertThat(ipd.getChildren()).isNull();
    }

    private static RouterVo root(String path, List<RouterVo> children) {
        RouterVo router = new RouterVo();
        router.setPath(path);
        router.setChildren(children == null ? null : new ArrayList<>(children));
        return router;
    }

    private static RouterVo item(String path, String title, boolean hidden) {
        RouterVo router = new RouterVo();
        router.setPath(path);
        router.setHidden(hidden);
        router.setMeta(new MetaVo(title, "icon"));
        return router;
    }

    private static List<String> titles(RouterVo ipd) {
        return ipd.getChildren().stream().map(child -> child.getMeta().getTitle()).toList();
    }

    private static List<Boolean> hiddenFlags(RouterVo ipd) {
        return ipd.getChildren().stream().map(child -> Boolean.TRUE.equals(child.getHidden())).toList();
    }

    private static List<String> paths(RouterVo ipd) {
        return ipd.getChildren().stream().map(RouterVo::getPath).toList();
    }
}
