package org.ruoyi.ipd.copilotkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CopilotKit AG-UI 桥（2026-09-28）：AG-UI RunAgentInput 反序列化测试。
 * 样本按官方 {@code @ag-ui/core@0.0.59} RunAgentInputSchema 字段形状（含未知字段容错）。
 */
@Tag("dev")
@DisplayName("CopilotKit AG-UI 桥：RunAgentInput JSON 反序列化")
class RunAgentInputJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("官方形状全字段反序列化 + 未知字段忽略 + content parts 数组容错")
    void deserializesOfficialShape() throws Exception {
        String json = """
            {
              "threadId": "thread-1",
              "runId": "run-1",
              "parentRunId": "run-0",
              "state": {"k": 1},
              "messages": [
                {"id": "m1", "role": "system", "content": "sys"},
                {"id": "m2", "role": "user", "content": "hello"},
                {"id": "m3", "role": "assistant", "content": "hi"},
                {"id": "m4", "role": "user", "content": [{"type": "text", "text": "parts"}]},
                {"id": "m5", "role": "user", "content": "next"}
              ],
              "tools": [{"name": "t1", "description": "d", "parameters": {"type": "object"}, "metadata": {"m": 1}}],
              "context": [{"description": "projectId", "value": "42"}],
              "forwardedProps": {"pageContext": "ctx"},
              "resume": [{"id": "r"}],
              "unknownField": {"x": 1}
            }
            """;
        RunAgentInput input = JSON.readValue(json, RunAgentInput.class);

        assertEquals("thread-1", input.threadId());
        assertEquals("run-1", input.runId());
        assertEquals("run-0", input.parentRunId());
        assertEquals(5, input.messages().size());
        assertEquals("user", input.messages().get(1).role());
        assertEquals("hello", input.messages().get(1).content());
        // content parts 数组不炸（Object 承载，桥侧仅消费纯文本串）
        assertNotNull(input.messages().get(3).content());
        assertEquals("t1", input.tools().get(0).name());
        assertEquals("projectId", input.context().get(0).description());
        assertEquals("42", input.context().get(0).value());
        assertEquals("ctx", input.forwardedProps().get("pageContext"));
        assertNotNull(input.resume());
    }

    @Test
    @DisplayName("最小面反序列化（threadId/runId/messages）：可选字段缺省为 null")
    void deserializesMinimalShape() throws Exception {
        String json = """
            {"threadId": "t", "runId": "r",
             "messages": [{"role": "user", "content": "你好"}]}
            """;
        RunAgentInput input = JSON.readValue(json, RunAgentInput.class);
        assertEquals("t", input.threadId());
        assertEquals("r", input.runId());
        assertEquals(1, input.messages().size());
        assertTrue(input.tools() == null || input.tools().isEmpty());
        assertTrue(input.context() == null || input.context().isEmpty());
    }
}
