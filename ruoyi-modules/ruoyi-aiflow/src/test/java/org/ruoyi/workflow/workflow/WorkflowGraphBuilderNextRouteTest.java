package org.ruoyi.workflow.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件边路由空值防护契约（D2 修复）：WorkflowGraphBuilder 此前
 * {@code state.data().get("next").toString()} 对缺失 next 直接 NPE，覆盖上游真实错误。
 * 契约：next 存在返回其字符串形式；缺失/空白/空 state 报带节点标识的明确错误（非 NPE）。
 */
@Tag("dev")
class WorkflowGraphBuilderNextRouteTest {

    @Test
    @DisplayName("next 存在 → 返回路由值（WorkflowEngine 从 NodeProcessResult.nextNodeUuid 写入）")
    void nextPresent_returnsRoute() {
        Map<String, Object> data = new HashMap<>();
        data.put("next", "node-target");
        assertEquals("node-target", WorkflowGraphBuilder.resolveNextRoute(data, "switch-1"));
    }

    @Test
    @DisplayName("next 为非字符串值 → 取字符串形式路由")
    void nextNonString_usesToString() {
        Map<String, Object> data = new HashMap<>();
        data.put("next", 7);
        assertEquals("7", WorkflowGraphBuilder.resolveNextRoute(data, "switch-1"));
    }

    @Test
    @DisplayName("next 缺失 → 明确错误（含节点标识与缺失键说明），而不是 NPE")
    void nextMissing_throwsClearErrorNotNpe() {
        Map<String, Object> data = new HashMap<>();
        data.put("other", "x");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> WorkflowGraphBuilder.resolveNextRoute(data, "switch-1"));
        assertTrue(thrown.getMessage().contains("switch-1"), "错误必须指出哪个节点的条件边: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("next"), "错误必须指出缺失的路由键: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("other"), "错误带上可用 state 键便于排查: " + thrown.getMessage());
    }

    @Test
    @DisplayName("next 为 null / 空白 → 同样报明确错误")
    void nextNullOrBlank_throwsClearError() {
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("next", null);
        assertThrows(IllegalStateException.class,
                () -> WorkflowGraphBuilder.resolveNextRoute(nullValue, "switch-1"));
        Map<String, Object> blankValue = new HashMap<>();
        blankValue.put("next", "  ");
        assertThrows(IllegalStateException.class,
                () -> WorkflowGraphBuilder.resolveNextRoute(blankValue, "switch-1"));
    }

    @Test
    @DisplayName("state data 为 null → 明确错误（防 NPE 覆盖）")
    void nullStateData_throwsClearErrorNotNpe() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> WorkflowGraphBuilder.resolveNextRoute(null, "switch-1"));
        assertTrue(thrown.getMessage().contains("switch-1"), thrown.getMessage());
    }
}
