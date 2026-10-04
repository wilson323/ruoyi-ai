package org.ruoyi.common.sse.dto;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** mcp_tool 帧必须始终是前端可解析的合法 JSON，无论工具结果里有什么字符。 */
@Tag("dev")
class SseEventDtoMcpToolTest {

    @Test
    void plainValuesKeepTheOriginalKeys() {
        SseEventDto dto = SseEventDto.mcpTool("web_fetch", "allowed", "ok");
        assertEquals("mcp_tool", dto.getEvent());
        JSONObject json = JSONUtil.parseObj(dto.getContent());
        assertEquals("web_fetch", json.getStr("toolName"));
        assertEquals("allowed", json.getStr("status"));
        assertEquals("ok", json.getStr("result"));
    }

    @Test
    void backslashTabCarriageReturnAndQuotesRoundTrip() {
        String nasty = "C:\\temp\\file\t\"quoted\"\r\nline2 \u2028 \u0001";
        SseEventDto dto = SseEventDto.mcpTool("execute", "denied", nasty);
        assertTrue(JSONUtil.isTypeJSONObject(dto.getContent()), "frame content must be valid JSON");
        assertEquals(nasty, JSONUtil.parseObj(dto.getContent()).getStr("result"));
    }

    @Test
    void nullsBecomeEmptyStrings() {
        JSONObject json = JSONUtil.parseObj(SseEventDto.mcpTool(null, null, null).getContent());
        assertEquals("", json.getStr("toolName"));
        assertEquals("", json.getStr("status"));
        assertEquals("", json.getStr("result"));
    }
}
