package org.ruoyi.workflow.workflow.checkpoint;

import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.workflow.workflow.WfNodeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：真实 langgraph4j {@link CompiledGraph} + {@link JdbcCheckpointSaver} + mock mapper
 * （行形态对齐真库，见 {@link FakeCheckpointDb}），验证「进程重启后从 DB checkpoint 断点续跑」闭环：
 * <pre>
 *   run1（GraphArgs）：START → nodeA（完成，落 checkpoint(nodeId=A,next=B)）→ nodeB 抛错 = 进程中断
 *   run2（全新 saver/全新 CompiledGraph，仅 DB 有状态，GraphInput.resume()）：→ nodeB → nodeC → END
 * </pre>
 * 语义钉死：①断点指向未完成节点 nodeB；②续跑不重跑已完成的 nodeA；③首跑 GraphArgs 写入的 state
 * 经 DB checkpoint 序列化往返后在续跑节点可见（state 往返保真）；④无 checkpoint 时 resume 不能凭空续跑。
 * <p>
 * 状态序列化器与生产 {@code WorkflowGraphBuilder} 一致（ObjectStreamStateSerializer + WfNodeState）。
 */
@Tag("dev")
@DisplayName("R31 checkpoint 落库断点续跑集成（真实 CompiledGraph + mock mapper）")
class WorkflowCheckpointResumeIntegrationTest {

    private static final String THREAD = "rt-uuid-resume-0001";

    @BeforeAll
    static void initMpLambdaCache() {
        // LambdaQueryWrapper 按 lambda 解析列名需要 TableInfo 预热（真库运行期由 mapper 扫描注册）；
        // 不预热时本类单独跑会 MybatisPlusException: can not find lambda cache（顺序假绿坑）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), org.ruoyi.workflow.entity.WorkflowCheckpoint.class);
    }

    /** 节点动作记账 + 首跑 nodeB 抛错开关 + nodeC 读到的断点 state 值 */
    private static final class NodeProbe {
        final List<String> executed = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean failNextB = new AtomicBoolean(true);
        final Map<String, Object> seenInC = Collections.synchronizedMap(new HashMap<>());
    }

    @Test
    void resumeAfterProcessRestartContinuesFromBreakpointWithoutRerun() throws Exception {
        FakeCheckpointDb db = new FakeCheckpointDb();
        NodeProbe probe = new NodeProbe();
        RunnableConfig config = RunnableConfig.builder().threadId(THREAD).build();

        // ---- 首跑：A 成功落 checkpoint(nodeId=A, nextNodeId=B)，B 抛错模拟进程中断 ----
        JdbcCheckpointSaver saver1 = new JdbcCheckpointSaver(db.mapper());
        CompiledGraph<WfNodeState> app1 = compileGraph(saver1, probe);
        assertThrows(Exception.class,
                () -> consume(app1.stream(GraphInput.args(Map.of("in", "v")), config)));
        assertEquals(List.of("A", "B"), probe.executed);

        // 断点已落库：最新 checkpoint 指向未完成的 nodeB（栈序最新在头 = get=peek）
        Checkpoint latest = saver1.get(config).orElseThrow();
        assertEquals("nodeA", latest.getNodeId());
        assertEquals("nodeB", latest.getNextNodeId());
        assertTrue(db.rows().size() >= 2, "至少 START + nodeA 两个 checkpoint 落库");
        assertTrue(db.rows().stream().allMatch(row -> THREAD.equals(row.getThreadId())),
                "checkpoint 行必须挂在 runtime 实例 uuid 的 thread_id 上");

        // ---- 进程重启：全新 saver / 全新 CompiledGraph，仅 DB checkpoint 是状态 ----
        JdbcCheckpointSaver saver2 = new JdbcCheckpointSaver(db.mapper());
        CompiledGraph<WfNodeState> app2 = compileGraph(saver2, probe);
        consume(app2.stream(GraphInput.resume(), config));

        // nodeA 不重跑（全跑只执行一次），nodeB 从断点续跑成功，nodeC 完成
        assertEquals(List.of("A", "B", "B", "C"), probe.executed);
        // state 往返保真：首跑 GraphArgs 的 in=v 经 DB checkpoint 传到续跑的 nodeC
        assertEquals("v", probe.seenInC.get("in"));
    }

    @Test
    void resumeWithoutPersistedCheckpointFailsInsteadOfSilentRestart() throws Exception {
        FakeCheckpointDb db = new FakeCheckpointDb();
        NodeProbe probe = new NodeProbe();
        RunnableConfig config = RunnableConfig.builder().threadId(THREAD).build();

        JdbcCheckpointSaver saver = new JdbcCheckpointSaver(db.mapper());
        CompiledGraph<WfNodeState> app = compileGraph(saver, probe);

        // DB 无 checkpoint 时 resume 直接失败（Resume request without a valid checkpoint），
        // 不允许静默从 START 重跑造成重复副作用
        assertThrows(Exception.class, () -> consume(app.stream(GraphInput.resume(), config)));
        assertTrue(probe.executed.isEmpty(), "无断点时任何节点都不应执行");
    }

    /** 与生产 WorkflowGraphBuilder 同款状态序列化器；A→B→C 直线图，节点 id 为固定值。 */
    private static CompiledGraph<WfNodeState> compileGraph(JdbcCheckpointSaver saver, NodeProbe probe) throws Exception {
        StateGraph<WfNodeState> stateGraph = new StateGraph<>(new ObjectStreamStateSerializer<>(WfNodeState::new));
        stateGraph.addNode("nodeA", AsyncNodeAction.node_async((WfNodeState state) -> {
            probe.executed.add("A");
            return Map.of();
        }));
        stateGraph.addNode("nodeB", AsyncNodeAction.node_async((WfNodeState state) -> {
            probe.executed.add("B");
            if (probe.failNextB.getAndSet(false)) {
                throw new IllegalStateException("simulated process crash");
            }
            return Map.of();
        }));
        stateGraph.addNode("nodeC", AsyncNodeAction.node_async((WfNodeState state) -> {
            probe.executed.add("C");
            probe.seenInC.put("in", state.data().get("in"));
            return Map.of();
        }));
        stateGraph.addEdge(StateGraph.START, "nodeA");
        stateGraph.addEdge("nodeA", "nodeB");
        stateGraph.addEdge("nodeB", "nodeC");
        stateGraph.addEdge("nodeC", StateGraph.END);
        return stateGraph.compile(CompileConfig.builder().checkpointSaver(saver).build());
    }

    private static void consume(AsyncGenerator<?> outputs) {
        for (Object ignored : outputs) {
            // 只消费到结束/抛错
        }
    }
}
