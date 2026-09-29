package org.ruoyi.ipd.copilotkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CopilotKit AG-UI 桥（2026-09-29，多智能体展示补齐）：蜂群/子智能体进度事件工厂 wire 格式单测。
 *
 * <p>逐事件锁定 {@link AgUiEvents} 的 STEP_* 与 SUBAGENT_* 工厂产出与官方 {@code @ag-ui/core@0.0.59}
 * schema 一致：①线格式 {@code type} 值 = 官方 EventType 常量；②必填字段恒在；③可选字段仅非空时出现
 * （官方 schema optional 缺省可解析）；④{@code type} 键恒在首位（LinkedHashMap 稳定序，JSON 可断言）。
 *
 * <p>注：本组词表<b>待真实 swarm 生产者接入</b>（当前 chatStream 为单一 RAG 流），工厂尚无生产调用方，
 * 本单测是 wire 契约的唯一锁定者——接入后生产者须产出与此完全一致的形状，前端 useAgent 订阅方可解析。
 */
@Tag("dev")
@DisplayName("CopilotKit AG-UI 桥：蜂群/子智能体进度事件 wire 格式")
class AgUiEventsSwarmTest {

    /** 抽 LinkedHashMap 的键序列表（断言 type 在首位 + 可选字段缺省不占位）。 */
    private static List<String> keys(Map<String, Object> e) {
        return new ArrayList<>(e.keySet());
    }

    @Test
    @DisplayName("stepStarted：type=STEP_STARTED，stepName 必填，subagentRunId 非空才下发")
    void stepStartedWire() {
        Map<String, Object> withSub = AgUiEvents.stepStarted("市场洞察", "sa-1");
        assertEquals("STEP_STARTED", withSub.get("type"));
        assertEquals("市场洞察", withSub.get("stepName"));
        assertEquals("sa-1", withSub.get("subagentRunId"));
        assertEquals(List.of("type", "stepName", "subagentRunId"), keys(withSub));

        Map<String, Object> noSub = AgUiEvents.stepStarted("市场洞察", null);
        assertEquals(List.of("type", "stepName"), keys(noSub));
        assertFalse(noSub.containsKey("subagentRunId"));
        // 空白串同样视为缺省（官方 optional，避免下发无意义空键）
        assertFalse(AgUiEvents.stepStarted("x", "  ").containsKey("subagentRunId"));
    }

    @Test
    @DisplayName("stepFinished：type=STEP_FINISHED，stepName 必填，subagentRunId 可选")
    void stepFinishedWire() {
        Map<String, Object> e = AgUiEvents.stepFinished("市场洞察", "sa-1");
        assertEquals("STEP_FINISHED", e.get("type"));
        assertEquals("市场洞察", e.get("stepName"));
        assertEquals("sa-1", e.get("subagentRunId"));
        assertEquals(List.of("type", "stepName", "subagentRunId"), keys(e));
        assertEquals(List.of("type", "stepName"), keys(AgUiEvents.stepFinished("市场洞察", null)));
    }

    @Test
    @DisplayName("subagentStarted：type=SUBAGENT_STARTED，subagentRunId+name 必填，description 非空才下发")
    void subagentStartedWire() {
        Map<String, Object> full = AgUiEvents.subagentStarted("sa-1", "痛点访谈员", "负责 JTBD 访谈");
        assertEquals("SUBAGENT_STARTED", full.get("type"));
        assertEquals("sa-1", full.get("subagentRunId"));
        assertEquals("痛点访谈员", full.get("name"));
        assertEquals("负责 JTBD 访谈", full.get("description"));
        assertEquals(List.of("type", "subagentRunId", "name", "description"), keys(full));

        Map<String, Object> noDesc = AgUiEvents.subagentStarted("sa-1", "痛点访谈员", null);
        assertEquals(List.of("type", "subagentRunId", "name"), keys(noDesc));
        assertFalse(noDesc.containsKey("description"));
        // parent* 官方 optional，桥侧首版不产（接入嵌套子智能体时再补），恒不占位
        assertFalse(noDesc.containsKey("parentSubagentRunId"));
        assertFalse(noDesc.containsKey("parentToolCallId"));
        assertFalse(noDesc.containsKey("parentMessageId"));
    }

    @Test
    @DisplayName("subagentFinished：type=SUBAGENT_FINISHED，subagentRunId 必填，result/outcome 有值才下发")
    void subagentFinishedWire() {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("type", "success");
        Map<String, Object> full = AgUiEvents.subagentFinished("sa-1", "访谈纪要已生成", outcome);
        assertEquals("SUBAGENT_FINISHED", full.get("type"));
        assertEquals("sa-1", full.get("subagentRunId"));
        assertEquals("访谈纪要已生成", full.get("result"));
        assertEquals(outcome, full.get("outcome"));
        assertEquals(List.of("type", "subagentRunId", "result", "outcome"), keys(full));

        // result/outcome 均缺省 → 只剩 type + subagentRunId（官方两者皆 optional）
        Map<String, Object> bare = AgUiEvents.subagentFinished("sa-1", null, null);
        assertEquals(List.of("type", "subagentRunId"), keys(bare));
        assertFalse(bare.containsKey("result"));
        assertFalse(bare.containsKey("outcome"));
        // 空 outcome Map 视为缺省，不下发空对象
        assertFalse(AgUiEvents.subagentFinished("sa-1", null, new LinkedHashMap<>()).containsKey("outcome"));
    }

    @Test
    @DisplayName("subagentFinished：outcome=suspended 判别联合透传（interruptIds 数组保形）")
    void subagentFinishedSuspendedOutcomeWire() {
        Map<String, Object> suspended = new LinkedHashMap<>();
        suspended.put("type", "suspended");
        suspended.put("interruptIds", List.of("int-1", "int-2"));
        Map<String, Object> e = AgUiEvents.subagentFinished("sa-1", null, suspended);
        Map<?, ?> got = (Map<?, ?>) e.get("outcome");
        assertEquals("suspended", got.get("type"));
        assertEquals(List.of("int-1", "int-2"), got.get("interruptIds"));
    }

    @Test
    @DisplayName("subagentError：type=SUBAGENT_ERROR，subagentRunId+message 必填，code 非空才下发")
    void subagentErrorWire() {
        Map<String, Object> full = AgUiEvents.subagentError("sa-1", "子智能体超时", "50002");
        assertEquals("SUBAGENT_ERROR", full.get("type"));
        assertEquals("sa-1", full.get("subagentRunId"));
        assertEquals("子智能体超时", full.get("message"));
        assertEquals("50002", full.get("code"));
        assertEquals(List.of("type", "subagentRunId", "message", "code"), keys(full));

        Map<String, Object> noCode = AgUiEvents.subagentError("sa-1", "子智能体超时", null);
        assertEquals(List.of("type", "subagentRunId", "message"), keys(noCode));
        assertFalse(noCode.containsKey("code"));
    }

    @Test
    @DisplayName("null 必填字段归一为空串（不产 null 值，与官方 ZodString 解析兼容）")
    void requiredFieldsNullSafe() {
        assertTrue(AgUiEvents.stepStarted(null, null).containsKey("stepName"));
        assertEquals("", AgUiEvents.stepStarted(null, null).get("stepName"));
        assertEquals("", AgUiEvents.subagentStarted(null, null, null).get("subagentRunId"));
        assertEquals("", AgUiEvents.subagentStarted(null, null, null).get("name"));
        assertEquals("", AgUiEvents.subagentError(null, null, null).get("message"));
    }

    @Test
    @DisplayName("词表一致性：5 个 wire 名与 AgUiEventType 枚举 wireName() 逐一相等（防手误漂移）")
    void wireNamesMatchEnum() {
        assertEquals(AgUiEventType.STEP_STARTED.wireName(), AgUiEvents.stepStarted("s", null).get("type"));
        assertEquals(AgUiEventType.STEP_FINISHED.wireName(), AgUiEvents.stepFinished("s", null).get("type"));
        assertEquals(AgUiEventType.SUBAGENT_STARTED.wireName(), AgUiEvents.subagentStarted("a", "n", null).get("type"));
        assertEquals(AgUiEventType.SUBAGENT_FINISHED.wireName(), AgUiEvents.subagentFinished("a", null, null).get("type"));
        assertEquals(AgUiEventType.SUBAGENT_ERROR.wireName(), AgUiEvents.subagentError("a", "m", null).get("type"));
        // 官方 wire 名硬断言（与 @ag-ui/core EventType 常量字面一致，禁发明）
        assertEquals("SUBAGENT_STARTED", AgUiEventType.SUBAGENT_STARTED.wireName());
        assertEquals("SUBAGENT_FINISHED", AgUiEventType.SUBAGENT_FINISHED.wireName());
        assertEquals("SUBAGENT_ERROR", AgUiEventType.SUBAGENT_ERROR.wireName());
        assertEquals("STEP_STARTED", AgUiEventType.STEP_STARTED.wireName());
        assertEquals("STEP_FINISHED", AgUiEventType.STEP_FINISHED.wireName());
    }
}
