package org.ruoyi.mcp.service.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.domain.entity.mcp.McpTool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 秘塔搜索 MCP（{@code https://metaso.cn/api/mcp}）实网连通性探针。
 *
 * <p>为什么必须是「实网」而不是打桩：本仓的 REMOTE 工具装配走
 * {@link AgentScopeMcpToolProviderService#openClient}，其中 REMOTE 分支会按
 * {@code transport} 字段在 {@code HttpClientStreamableHttpTransport} 与
 * {@code HttpClientSseClientTransport} 之间二选一。秘塔端点实测返回的是
 * {@code Content-Type: application/json} 的裸 JSON-RPC（不是 SSE 流），
 * 因此「Java SDK 的 Streamable HTTP 传输层能否吃下这种响应」是本探针要回答的
 * 唯一问题——打桩回答不了，因为打桩把传输层整个换掉了。
 *
 * <p>密钥从环境变量 {@code METASO_MCP_API_KEY} 读取，<b>不落库、不进仓库</b>；
 * 未设置时整类跳过（assumption），因此普通 CI 不会因缺密钥而红。
 *
 * <p>运行方式：
 * <pre>
 * METASO_MCP_API_KEY=mk-xxxx bash scripts/mvn-locked.sh test \
 *   -Dtest=MetasoSearchMcpLiveProbeTest -pl ruoyi-modules/ruoyi-chat
 * </pre>
 */
@Tag("dev")
class MetasoSearchMcpLiveProbeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void metasoSearchMcpIsReachableThroughProductionTransport() throws Exception {
        String apiKey = System.getenv("METASO_MCP_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "未设置 METASO_MCP_API_KEY，跳过实网探针");

        McpTool tool = new McpTool();
        tool.setId(17L);
        tool.setName("metaso_search");
        tool.setType("REMOTE");
        tool.setStatus("ENABLED");
        tool.setConfigJson(new ObjectMapper().writeValueAsString(java.util.Map.of(
                "baseUrl", "https://metaso.cn/api/mcp",
                "transport", "JSON_RPC",
                "headers", java.util.Map.of("Authorization", "Bearer " + apiKey))));

        var service = new AgentScopeMcpToolProviderService(
                mock(org.ruoyi.mapper.mcp.McpToolMapper.class), new ObjectMapper(),
                mock(BuiltinToolRegistry.class));

        McpClientWrapper client = service.openClient(tool);
        try {
            client.initialize().block(TIMEOUT);
            List<McpSchema.Tool> tools = client.listTools().block(TIMEOUT);
            assertThat(tools)
                    .as("秘塔 MCP 应当至少暴露一个可调用工具")
                    .isNotNull()
                    .isNotEmpty();
            assertThat(tools.stream().map(McpSchema.Tool::name))
                    .as("联网搜索工具名必须可发现，否则模型无从调用")
                    .contains("metaso_web_search");

            // 只测 tools/list 不够：那证明「连得上」，不证明「调得动」。
            // 真打一次搜索，模型侧拿到的才是真结果。
            var result = client.callTool("metaso_web_search",
                    java.util.Map.of("q", "IPD 集成产品开发", "size", 2)).block(TIMEOUT);
            assertThat(result).as("搜索工具必须返回结果").isNotNull();
            // 秘塔返回体里根本没有 isError 字段（实测顶层只有 content 与 error:null），
            // SDK 的 CallToolResult.isError() 是装箱 Boolean，缺失时为 null 而非 false。
            // 所以只能断言「不是 true」，不能直接 isFalse()——那会把 null 判成失败。
            assertThat(Boolean.TRUE.equals(result.isError()))
                    .as("搜索调用不应报错，返回内容=%s", result.content())
                    .isFalse();
            assertThat(result.content())
                    .as("搜索结果不能是空内容")
                    .isNotEmpty();
            assertThat(result.content().get(0).toString())
                    .as("搜索结果正文里应当真的有网页条目，而不是空壳")
                    .contains("link");
        } finally {
            client.close();
        }
    }
}
