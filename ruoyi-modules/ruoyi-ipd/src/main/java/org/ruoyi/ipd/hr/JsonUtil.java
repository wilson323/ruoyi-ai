package org.ruoyi.ipd.hr;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 内部 JSON 序列化/反序列化辅助（包装 Hutool + Jackson）。 */
final class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonUtil() { }
    static String toJsonString(Object o) {
        return JSONUtil.toJsonStr(o);
    }
    /** Hutool 解析失败时降级用 Jackson（HR 响应根节点是 JsonNode）。 */
    static JsonNode parseTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException("JsonUtil.parseTree 解析失败: " + e.getMessage(), e);
        }
    }
}
