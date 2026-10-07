package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 秘塔搜索挂载测试。
 *
 * <p>分两段，第二段是关键：
 * <ol>
 *   <li><b>实网段</b>——真连秘塔，真调一次 {@code metaso_web_search}，确认模型侧拿得到真结果。
 *       没有 {@code METASO_MCP_API_KEY} 时跳过，所以普通 CI 不会因缺密钥而红。</li>
 *   <li><b>不依赖网络的负向段</b>——密钥为空时必须<b>一个字都不注册</b>。这条永远跑。
 *       它防的是「装了个空壳工具，看着能用、一调用就炸」，比正向段更该被看见。</li>
 * </ol>
 */
@Tag("dev")
class ProjectAgentMetasoSearchTest {

    private static Toolkit emptyToolkit() {
        return new Toolkit(ToolkitConfig.builder().parallel(false).build());
    }

    @Test
    void blankApiKeyRegistersNothing() {
        Toolkit toolkit = emptyToolkit();
        assertThat(ProjectAgentMetasoSearch.bind(toolkit, null).registered())
                .as("密钥为空时不得注册任何工具")
                .isEmpty();
        assertThat(ProjectAgentMetasoSearch.bind(toolkit, "   ").registered())
                .as("密钥为空白时不得注册任何工具")
                .isEmpty();
        assertThat(toolkit.getToolNames())
                .as("工具箱必须保持干净，不留空壳条目")
                .doesNotContainAnyElementsOf(ProjectAgentMetasoSearch.TOOL_IDS);
    }

    @Test
    void blankApiKeyIsReportedAsUnavailable() {
        assertThat(ProjectAgentMetasoSearch.available(null)).isFalse();
        assertThat(ProjectAgentMetasoSearch.available(" ")).isFalse();
        assertThat(ProjectAgentMetasoSearch.available("mk-real")).isTrue();
    }

    @Test
    void liveSearchReturnsRealResults() {
        String key = ProjectAgentMetasoSearch.apiKeyFromEnvironment();
        assumeTrue(key != null && !key.isBlank(), "未设置 METASO_MCP_API_KEY，跳过实网段");

        Toolkit toolkit = emptyToolkit();
        ProjectAgentMetasoSearch.Bound bound = ProjectAgentMetasoSearch.bind(toolkit, key);

        assertThat(bound.registered())
                .as("调研能力必须真的挂上，否则智能体等于在无网状态下跑")
                .contains(ProjectAgentMetasoSearch.WEB_SEARCH);
        assertThat(toolkit.getToolNames())
                .as("工具必须出现在智能体的工具箱里，模型才看得见")
                .contains(ProjectAgentMetasoSearch.WEB_SEARCH);
        assertThat(bound.client()).as("挂载成功必须持有客户端，否则泄漏并发许可").isNotNull();
        try {
            var result = bound.client().callTool(ProjectAgentMetasoSearch.WEB_SEARCH,
                    ProjectAgentMetasoSearch.defaultSearchArguments("集成产品开发 IPD", 2))
                    .block(java.time.Duration.ofSeconds(40));
            assertThat(result).as("搜索调用必须返回结果").isNotNull();
            // 服务端不返回 isError 字段，SDK 该字段缺失时为 null；只能断言「不是 true」。
            assertThat(Boolean.TRUE.equals(result.isError()))
                    .as("搜索不应报错，实际内容=%s", result.content())
                    .isFalse();
            assertThat(result.content().toString())
                    .as("结果里应当真有网页条目（link/title 字段），不是空壳")
                    .contains("link");
        } finally {
            bound.client().close();
        }
    }
}
