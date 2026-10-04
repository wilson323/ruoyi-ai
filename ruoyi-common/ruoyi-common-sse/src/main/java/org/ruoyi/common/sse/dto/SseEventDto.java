package org.ruoyi.common.sse.dto;

import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

import java.io.Serial;
import java.io.Serializable;

/**
 * SSE 事件数据传输对象
 * <p>
 * 标准的 SSE 消息格式，支持不同事件类型
 *
 * @author ageerle@163.com
 * @date 2025/03/19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseEventDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 事件类型
     */
    private String event;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 推理内容（深度思考模式）
     */
    private String reasoningContent;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 是否完成
     */
    private Boolean done;

    /** true 时 content 为权威全文，消费者须替换累计正文；缺省仍为增量。 */
    private Boolean replace;

    public static SseEventDto replacement(String content) {
        return SseEventDto.builder().event("content").content(content).replace(true).build();
    }

    /**
     * 创建内容事件
     */
    public static SseEventDto content(String content) {
        return SseEventDto.builder()
            .event("content")
            .content(content)
            .build();
    }

    /**
     * 创建推理内容事件
     */
    public static SseEventDto reasoning(String reasoningContent) {
        return SseEventDto.builder()
            .event("reasoning")
            .reasoningContent(reasoningContent)
            .build();
    }

    /**
     * 创建完成事件
     */
    public static SseEventDto done() {
        return SseEventDto.builder()
            .event("done")
            .done(true)
            .build();
    }

    /**
     * 创建错误事件
     */
    public static SseEventDto error(String error) {
        return SseEventDto.builder()
            .event("error")
            .error(error)
            .build();
    }

    /**
     * 创建 MCP 工具事件
     */
    public static SseEventDto mcpTool(String toolName, String status, String result) {
        return SseEventDto.builder()
            .event("mcp_tool")
            .content(buildMcpJson(toolName, status, result))
            .build();
    }

    private static String buildMcpJson(String toolName, String status, String result) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName != null ? toolName : "");
        payload.put("status", status != null ? status : "");
        payload.put("result", result != null ? result : "");
        return JSONUtil.toJsonStr(payload);
    }
}