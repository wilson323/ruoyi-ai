package org.ruoyi.workflow.workflow.checkpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.ruoyi.workflow.workflow.data.NodeIODataBoolContent;
import org.ruoyi.workflow.workflow.data.NodeIODataFilesContent;
import org.ruoyi.workflow.workflow.data.NodeIODataNumberContent;
import org.ruoyi.workflow.workflow.data.NodeIODataOptionsContent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 序列化往返测试：state Map（含 NodeIOData 等真实业务对象）经生产序列化路径
 * （{@link JdbcCheckpointSaver#serializeCheckpoint}/{@code deserializeCheckpoint}，
 * 内核为版本化 ObjectStream 业务载荷）往返后等价。
 * <p>
 * 抉择依据：plain_text 包 JacksonCheckpointListSerializer 对 NodeIODataContent 多态字段
 * （抽象泛型基类、无 @JsonTypeInfo）往返会丢子类型/反解失败，故选 ObjectStream 二进制 + Base64。
 * 样本取自真实 state Map 形态（WfNodeState data 的元数据键 + 节点输入输出 NodeIOData）。
 */
@Tag("dev")
@DisplayName("R31 checkpoint state 序列化往返保真（NodeIOData 全家桶，生产 serde 路径）")
class CheckpointStateSerializationRoundTripTest {

    enum SerdeCase {
        /** 纯元数据 state（工作流 state 常规形态：name/next 等 String 键值） */
        WF_METADATA,
        /** 单个 NodeIOData（文本内容） */
        NODE_IO_TEXT,
        /** NodeIOData 全内容类型：text/number/bool/files/options（多态 content 子类保真） */
        NODE_IO_ALL_CONTENT_TYPES,
        /** 嵌套 Map + List 的复合 state */
        NESTED_COLLECTIONS,
        /** 贴近 WfNodeState data 的全量 state：元数据 + inputs/outputs NodeIOData 列表 */
        FULL_WF_STATE
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SerdeCase.class)
    void roundTrip(SerdeCase scenario) throws Exception {
        Map<String, Object> state = switch (scenario) {
            case WF_METADATA -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", "开始节点");
                m.put("next", "node-uuid-2");
                m.put("attempt", 3);
                m.put("ok", Boolean.TRUE);
                yield m;
            }
            case NODE_IO_TEXT -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("input", NodeIOData.createByText("input", "用户输入", "你好"));
                yield m;
            }
            case NODE_IO_ALL_CONTENT_TYPES -> {
                Map<String, Object> m = new LinkedHashMap<>();
                List<NodeIOData> outputs = new ArrayList<>();
                outputs.add(NodeIOData.createByText("txt", "文本", "hello"));
                outputs.add(NodeIOData.createByNumber("num", "数字", 3.14d));
                outputs.add(NodeIOData.createByBool("bool", "布尔", Boolean.TRUE));
                outputs.add(NodeIOData.createByFiles("files", "文件", List.of("https://a.com/a.xlsx")));
                Map<String, Object> options = new LinkedHashMap<>();
                options.put("selectedA", "选项A");
                options.put("count", 2);
                outputs.add(NodeIOData.createByOptions("opts", "选项", options));
                m.put("outputs", outputs);
                yield m;
            }
            case NESTED_COLLECTIONS -> {
                Map<String, Object> inner = new LinkedHashMap<>();
                inner.put("k1", "v1");
                inner.put("k2", List.of("a", "b"));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("map", inner);
                m.put("list", new ArrayList<>(List.of("x", 1, Boolean.FALSE)));
                yield m;
            }
            case FULL_WF_STATE -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", "LLM节点");
                m.put("next", "end-node-uuid");
                m.put("inputs", List.of(NodeIOData.createByText("input", "用户输入", "续跑输入")));
                m.put("outputs", List.of(
                        NodeIOData.createByText("output", "输出", "续跑输出"),
                        NodeIOData.createByNumber("score", "评分", 99.5d)));
                yield m;
            }
        };

        String nodeId = "node-uuid-1";
        String nextNodeId = "node-uuid-2";
        WorkflowCheckpointState original = WorkflowCheckpointState.builder()
                .id("f3b6a1a2-0000-4000-8000-000000000001")
                .nodeId(nodeId).nextNodeId(nextNodeId).state(state).build();

        // 往返：WorkflowCheckpointState → Base64（state_json 落库形态）→ WorkflowCheckpointState
        String stateJson = JdbcCheckpointSaver.serializeCheckpoint(original);
        assertNotNull(stateJson);
        WorkflowCheckpointState restored = JdbcCheckpointSaver.deserializeCheckpoint(stateJson);

        assertEquals(original.getId(), restored.getId());
        assertEquals(nodeId, restored.getNodeId());
        assertEquals(nextNodeId, restored.getNextNodeId());
        assertEquals(original.getState(), restored.getState(), "state Map 往返必须等价（含 NodeIOData 子类）");

        if (scenario == SerdeCase.NODE_IO_ALL_CONTENT_TYPES) {
            // 多态 content 子类保真（Jackson 路径的死穴），逐类钉死
            @SuppressWarnings("unchecked")
            List<NodeIOData> outputs = (List<NodeIOData>) restored.getState().get("outputs");
            assertInstanceOf(NodeIODataBoolContent.class, outputs.get(2).getContent());
            assertInstanceOf(NodeIODataFilesContent.class, outputs.get(3).getContent());
            assertInstanceOf(NodeIODataOptionsContent.class, outputs.get(4).getContent());
            assertInstanceOf(NodeIODataNumberContent.class, outputs.get(1).getContent());
        }
    }
}
