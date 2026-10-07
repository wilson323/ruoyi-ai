package org.ruoyi.ipd.agent.kernel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.ruoyi.domain.entity.mcp.McpTool;
import org.ruoyi.enums.McpToolStatus;
import org.ruoyi.mapper.mcp.McpToolMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 让秘塔搜索的密钥能在系统页面（MCP 工具配置）里改，而不是只能靠环境变量。
 *
 * <p>为什么走这张表：项目里已经有 {@code mcp_tool_info} + {@code /mcp/tool}
 * 的增删改查，工具地址、请求头、启停都在页面上维护。密钥再单独开一个环境变量，
 * 就成了「一处能改、一处不能改」的双轨——运维改错地方还不报错。
 * 所以这里直接读那行工具的 {@code config_json.headers.Authorization}。
 *
 * <p>三条约定：
 * <ul>
 *   <li><b>每次运行现取</b>，不缓存。管理员在页面上改完密钥，下一次运行即生效，不必重启后端。
 *       缓存会让「页面显示已改、实际还是旧密钥」这类问题极难排查。</li>
 *   <li><b>找不到就回落到环境变量</b>，两者都没有则不挂载工具（不装空壳）。</li>
 *   <li><b>只认启用状态且类型为 REMOTE 的那一行</b>。页面把工具停掉，等于明确表达
 *       「这条能力现在不要」，必须真的消失，不能退回去继续用环境变量。</li>
 * </ul>
 *
 * <p>本类只负责把密钥取出来，<b>不打印、不返回给调用方日志</b>；失败一律降级为 null。
 */
@Component
public class ProjectAgentMetasoSearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgentMetasoSearchConfig.class);
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";

    private final McpToolMapper mcpToolMapper;

    public ProjectAgentMetasoSearchConfig(McpToolMapper mcpToolMapper) {
        this.mcpToolMapper = mcpToolMapper;
        ProjectAgentMetasoSearch.configure(this::loadApiKeyFromToolConfig);
    }

    /**
     * 从系统页面登记的那条工具里读密钥。读不到（表空 / 被停用 / JSON 坏了 / 没写头）
     * 一律返回 null——由调用方决定回落还是放弃，绝不在这里抛异常打断智能体启动。
     */
    String loadApiKeyFromToolConfig() {
        try {
            List<McpTool> rows = mcpToolMapper.selectList(
                new LambdaQueryWrapper<McpTool>()
                    .eq(McpTool::getName, ProjectAgentMetasoSearch.TOOL_NAME)
                    .eq(McpTool::getType, "REMOTE")
                    .eq(McpTool::getStatus, McpToolStatus.ENABLED.getValue()));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            String configJson = rows.get(0).getConfigJson();
            if (configJson == null || configJson.isBlank()) {
                return null;
            }
            var header = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(configJson).path("headers").path(AUTHORIZATION).asText("");
            String key = header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : header.trim();
            if (key.isBlank() || key.startsWith("${")) {
                // ${...} 是未注入密钥时的占位符，当作没配，不要拿它去请求。
                return null;
            }
            return key;
        } catch (Exception failure) {
            log.warn("metaso_search operation=LOAD_CONFIG status=FAILED errorType={}",
                    failure.getClass().getSimpleName());
            return null;
        }
    }
}
