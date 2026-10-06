package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.service.IpdCopilotAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.OTHER_PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.TENANT;

/**
 * 能力查询（合同 #1）：开关关闭仍 200 语义返回目录但 pack.available=false 且原因含
 * {@code ipd.project-agent.enabled=false}；开启时按 Skill/工具/模型如实投影。
 */
@Tag("dev")
class ProjectAgentCapabilityServiceTest {

    @Test
    @DisplayName("开关关闭：返回完整目录，全部 pack available=false，返回明确不可用原因")
    void disabledReturnsUnavailablePacksWithReason() {
        IpdCopilotAccess access = visibleAccess();
        ProjectAgentCapabilityService service = service(false, access);

        ProjectAgentViews.Capabilities caps = service.capabilities(ACTOR, PROJECT_ID);

        assertThat(caps.packs()).isNotEmpty();
        assertThat(caps.packs()).allSatisfy(p -> {
            assertThat(p.available()).isFalse();
            assertThat(p.unavailableReason()).contains(org.ruoyi.ipd.agent.ProjectAgentConstants.REASON_DISABLED);
            assertThat(p.unavailableReason()).isEqualTo(ProjectAgentConstants.REASON_DISABLED);
        });
        assertThat(caps.models()).isNotEmpty();
        assertThat(caps.packs().stream().anyMatch(p -> "market-research".equals(p.code()))).isTrue();
        verify(access).requireVisible(ACTOR, PROJECT_ID);
    }

    @Test
    @DisplayName("开关开启：必备 Skill/工具齐全且有可用模型时 pack available=true")
    void enabledPackAvailableWhenCatalogReady() {
        ProjectAgentViews.Capabilities caps = service(true, visibleAccess()).capabilities(ACTOR, PROJECT_ID);

        ProjectAgentViews.Pack market = caps.packs().stream()
            .filter(p -> "market-research".equals(p.code())).findFirst().orElseThrow();
        assertThat(market.available()).isTrue();
        assertThat(market.unavailableReason()).isNull();
        assertThat(market.skills()).anySatisfy(s -> assertThat(s.available()).isTrue());
        assertThat(market.tools()).anySatisfy(t ->
            assertThat(t.id()).isEqualTo(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH));
        assertThat(caps.models()).anySatisfy(m -> assertThat(m.available()).isTrue());
    }

    @Test
    @DisplayName("跨项目：NOT_FOUND，不返回目录")
    void invisibleProjectRejected() {
        IpdCopilotAccess access = mock(IpdCopilotAccess.class);
        when(access.requireVisible(any(), eq(OTHER_PROJECT_ID)))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"));
        ProjectAgentCapabilityService service = service(true, access);

        assertThatThrownBy(() -> service.capabilities(ACTOR, OTHER_PROJECT_ID))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("projectId 空：PARAM_INVALID，不触访问守卫")
    void nullProjectIdRejected() {
        IpdCopilotAccess access = mock(IpdCopilotAccess.class);
        assertThatThrownBy(() -> service(true, access).capabilities(ACTOR, null))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verifyNoInteractions(access);
    }

    @Test
    @DisplayName("基础查询未配置逐工具返回，已有知识包仍可用且保留完整工具目录")
    void missingNativeProviderDoesNotDisableBusinessPack() {
        var manifest = AgentTestFixtures.manifest();
        var tools = new ProjectAgentToolCatalog(manifest, id ->
            new org.ruoyi.ipd.agent.catalog.ProjectAgentNativeToolCatalog.Readiness(
                !"web_search".equals(id), "查询服务尚未配置"));
        var service = new ProjectAgentCapabilityService(true, visibleAccess(), manifest,
            AgentTestFixtures.skillCatalog(manifest), tools, AgentTestFixtures.modelCatalog());
        var market = service.capabilities(ACTOR, PROJECT_ID).packs().stream()
            .filter(pack -> "market-research".equals(pack.code())).findFirst().orElseThrow();
        assertThat(market.available()).isTrue();
        assertThat(market.tools().stream().map(ProjectAgentViews.Tool::id).toList())
            .containsAll(ProjectAgentToolCatalog.nativeToolIds()).doesNotHaveDuplicates();
        assertThat(market.tools()).anySatisfy(tool -> {
            assertThat(tool.id()).isEqualTo("web_search");
            assertThat(tool.available()).isFalse();
            assertThat(tool.reason()).isEqualTo("查询服务尚未配置");
        });
    }

    @Test
    @DisplayName("渲染引擎未就绪：包仍可用（按需可选工具），render 工具行如实不可用")
    void unavailableHtmlRendererDoesNotDisablePack() {
        var manifest = AgentTestFixtures.manifest();
        var tools = new ProjectAgentToolCatalog(manifest, id ->
            new org.ruoyi.ipd.agent.catalog.ProjectAgentNativeToolCatalog.Readiness(true, null));
        var service = new ProjectAgentCapabilityService(true, visibleAccess(), manifest,
            AgentTestFixtures.skillCatalog(manifest), tools, AgentTestFixtures.modelCatalog());
        var market = service.capabilities(ACTOR, PROJECT_ID).packs().stream()
            .filter(pack -> "market-research".equals(pack.code())).findFirst().orElseThrow();
        assertThat(market.available()).as("渲染工具不拖垮整包").isTrue();
        assertThat(market.tools()).anySatisfy(tool -> {
            assertThat(tool.id()).isEqualTo(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
            assertThat(tool.available()).isFalse();
            assertThat(tool.reason()).isEqualTo("渲染引擎未装配");
        });
    }

    private static IpdCopilotAccess visibleAccess() {
        IpdCopilotAccess access = mock(IpdCopilotAccess.class);
        when(access.requireVisible(any(), eq(PROJECT_ID))).thenReturn(TENANT);
        return access;
    }

    private static ProjectAgentCapabilityService service(boolean enabled, IpdCopilotAccess access) {
        var manifest = AgentTestFixtures.manifest();
        return new ProjectAgentCapabilityService(enabled, access, manifest,
            AgentTestFixtures.skillCatalog(manifest), new ProjectAgentToolCatalog(manifest),
            AgentTestFixtures.modelCatalog());
    }
}
