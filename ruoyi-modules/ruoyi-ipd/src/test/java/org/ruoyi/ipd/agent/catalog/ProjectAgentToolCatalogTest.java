package org.ruoyi.ipd.agent.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.coding.harness.tool.ToolCapability;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具目录：render_html_page 的 W1 定向放行跟随渲染引擎 readiness；manifest 与
 * DESCRIPTORS 的登记一致（WRITE 语义如实、7 包全覆盖）；未实现工具 fail-closed。
 */
@Tag("dev")
class ProjectAgentToolCatalogTest {

    final CapabilityManifest builtin =
        CapabilityManifest.load(CapabilityManifest.DEFAULT_RESOURCE, new ObjectMapper());

    private ProjectAgentToolCatalog catalog(ProjectAgentNativeToolCatalog.Readiness readiness) {
        return new ProjectAgentToolCatalog(builtin, ProjectAgentNativeToolCatalog.unverified(), () -> readiness);
    }

    @Test
    @DisplayName("render_html_page 状态由引擎 readiness 投影：就绪可用，不可用带明确原因")
    void renderToolStatusFollowsEngineReadiness() {
        var ready = catalog(new ProjectAgentNativeToolCatalog.Readiness(true, null))
            .status(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
        assertThat(ready.readOnly()).isFalse();
        assertThat(ready.available()).isTrue();
        assertThat(ready.reason()).isNull();

        var broken = catalog(new ProjectAgentNativeToolCatalog.Readiness(false, "Node 运行环境不可用（node）"))
            .status(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
        assertThat(broken.readOnly()).isFalse();
        assertThat(broken.available()).isFalse();
        assertThat(broken.reason()).isEqualTo("Node 运行环境不可用（node）");
    }

    @Test
    @DisplayName("渲染引擎未装配时目录明确不可用，不给 W1 兜底文案")
    void missingRendererAssemblyIsExplicitlyUnavailable() {
        var unready = new ProjectAgentToolCatalog(builtin, ProjectAgentNativeToolCatalog.unverified(), () -> null)
            .status(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
        assertThat(unready.available()).isFalse();
        assertThat(unready.reason()).isEqualTo("渲染引擎未装配");
    }

    @Test
    @DisplayName("manifest 与 DESCRIPTORS 一致：WRITE 如实声明且不带 DESTRUCTIVE/EXECUTE/NETWORK")
    void renderToolManifestAndDescriptorAgreeOnWriteSemantics() {
        var entry = builtin.tool(ProjectAgentToolCatalog.HTML_PAGE_RENDER).orElseThrow();
        assertThat(entry.readOnly()).isFalse();
        var descriptor = ProjectAgentToolCatalog.descriptor(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.hasCapability(ToolCapability.WRITE)).isTrue();
        assertThat(descriptor.hasCapability(ToolCapability.DESTRUCTIVE)).isFalse();
        assertThat(descriptor.hasCapability(ToolCapability.EXECUTE)).isFalse();
        assertThat(descriptor.hasCapability(ToolCapability.NETWORK)).isFalse();
    }

    @Test
    @DisplayName("7 个能力包全部登记渲染工具与 answer-me 技能（DB 种子同源前置）")
    void allSevenPacksCarryRenderToolAndAnswerMeSkill() {
        assertThat(builtin.packs()).hasSize(7);
        for (var pack : builtin.packs()) {
            assertThat(pack.tools()).as("包 %s 应含渲染工具", pack.code())
                .contains(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
            assertThat(pack.skills()).as("包 %s 应含 answer-me 技能", pack.code())
                .contains("answer-me-with-html-ipd");
        }
        var skill = builtin.skill("answer-me-with-html-ipd").orElseThrow();
        assertThat(skill.sha256()).matches("[a-f0-9]{64}");
        assertThat(skill.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("statuses() 只出现一个渲染条目；既有只读工具不受影响")
    void statusesExposeRenderToolOnceAndKeepReadonlyToolsReadable() {
        var statuses = catalog(new ProjectAgentNativeToolCatalog.Readiness(false, "探针失败"))
            .statuses();
        var render = statuses.stream()
            .filter(s -> ProjectAgentToolCatalog.HTML_PAGE_RENDER.equals(s.id())).toList();
        assertThat(render).hasSize(1);
        assertThat(render.get(0).available()).isFalse();
        var knowledge = statuses.stream()
            .filter(s -> ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH.equals(s.id()))
            .findFirst().orElseThrow();
        assertThat(knowledge.readOnly()).isTrue();
        assertThat(knowledge.available()).isTrue();
    }

    @Test
    @DisplayName("清单登记但未实现的工具 fail-closed：不可用且原因明确")
    void unimplementedManifestToolStaysUnavailable() {
        var ghost = new CapabilityManifest(1, List.of(),
            List.of(new CapabilityManifest.ToolEntry("ghost_tool", "幽灵工具", false)), List.of());
        var status = new ProjectAgentToolCatalog(ghost, ProjectAgentNativeToolCatalog.unverified(), () -> null)
            .status("ghost_tool");
        assertThat(status.available()).isFalse();
        assertThat(status.reason()).isEqualTo("工具实现未交付");

        var unknown = catalog(new ProjectAgentNativeToolCatalog.Readiness(true, null)).status("no_such_tool");
        assertThat(unknown.available()).isFalse();
        assertThat(unknown.reason()).isEqualTo("工具未在内置清单登记");
    }
}
