package org.ruoyi.domain.dto.mcp;

import org.ruoyi.domain.entity.mcp.McpMarketTool;

import java.util.Date;
import java.util.Map;

/**
 * Public market-tool projection. Raw provider metadata is deliberately excluded;
 * only the redacted {@link #metadataView} is exposed (Track E2-BE-1).
 */
public record McpMarketToolListItem(
    Long id,
    Long marketId,
    String toolName,
    String toolDescription,
    String toolVersion,
    Boolean isLoaded,
    Long localToolId,
    Date createTime,
    Date updateTime,
    Map<String, Object> metadataView
) {

    public static McpMarketToolListItem from(McpMarketTool tool) {
        return new McpMarketToolListItem(tool.getId(), tool.getMarketId(), tool.getToolName(),
            tool.getToolDescription(), tool.getToolVersion(), tool.getIsLoaded(),
            tool.getLocalToolId(), tool.getCreateTime(), tool.getUpdateTime(),
            McpMarketMetadataRedactor.redact(tool.getToolMetadata()));
    }
}
