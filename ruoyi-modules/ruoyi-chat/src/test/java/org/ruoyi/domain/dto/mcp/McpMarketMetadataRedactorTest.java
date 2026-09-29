package org.ruoyi.domain.dto.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 市场工具元数据脱敏投影回归门禁（Track E2-BE-1）：
 * ① 白名单展示键（标量/标量数组）保留；② 命名否决层先于白名单生效；
 * ③ 嵌套对象/非法 JSON 一律空视图（不带病出参）。
 */
@Tag("dev")
class McpMarketMetadataRedactorTest {

    @Test
    void whitelistedScalarsAndScalarArraysSurvive() {
        Map<String, Object> view = McpMarketMetadataRedactor.redact(
            "{\"description\":\"文件工具\",\"license\":\"MIT\",\"tags\":[\"fs\",\"doc\"],\"installCount\":42}");
        assertEquals("文件工具", view.get("description"));
        assertEquals("MIT", view.get("license"));
        assertEquals(List.of("fs", "doc"), view.get("tags"));
        assertFalse(view.containsKey("installCount"), "白名单外的键必须丢弃");
    }

    @Test
    void sensitiveKeysAreDroppedEvenWhenWhitelistedByNameCollision() {
        Map<String, Object> view = McpMarketMetadataRedactor.redact(
            "{\"apikey\":\"sk-live\",\"Authorization\":\"Bearer x\",\"token\":\"t\",\"description\":\"ok\"}");
        assertTrue(view.isEmpty() || view.keySet().equals(java.util.Set.of("description")),
            "命名否决层必须先于白名单生效");
        assertEquals("ok", view.get("description"));
    }

    @Test
    void namingVetoTakesPrecedenceOverWhitelist() {
        // author 在 DISPLAY_KEYS 白名单内，但键名命中 (auth) 命名否决 → 仍必须丢弃。
        // 这是「先否决、再白名单」分层契约的规范碰撞样本；若产品要求展示 author，
        // 应收窄 SENSITIVE_KEY 正则（单点修改）而非绕过否决层。
        Map<String, Object> view = McpMarketMetadataRedactor.redact(
            "{\"author\":\"someone\",\"description\":\"ok\"}");
        assertFalse(view.containsKey("author"), "白名单键撞命名否决仍须丢弃");
        assertEquals("ok", view.get("description"));
    }

    @Test
    void nestedObjectsAndInvalidJsonYieldEmptyView() {
        assertTrue(McpMarketMetadataRedactor.redact("{\"config\":{\"password\":\"p\"}}").isEmpty());
        assertTrue(McpMarketMetadataRedactor.redact("{oops").isEmpty());
        assertTrue(McpMarketMetadataRedactor.redact(null).isEmpty());
    }

    @Test
    void credentialsInsideAllowedTextAndArraysAreDropped() {
        Map<String, Object> view = McpMarketMetadataRedactor.redact(
            "{\"description\":\"use Bearer abc123\",\"readme\":\"api_key=hidden\","
                + "\"tags\":[\"safe\",\"sk-abcdefghijklmnop\"],\"license\":\"MIT\"}");
        assertFalse(view.containsKey("description"));
        assertFalse(view.containsKey("readme"));
        assertFalse(view.containsKey("tags"));
        assertEquals("MIT", view.get("license"));
    }
}
