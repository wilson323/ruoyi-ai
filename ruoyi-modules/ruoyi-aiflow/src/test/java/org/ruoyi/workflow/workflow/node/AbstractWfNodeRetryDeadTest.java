package org.ruoyi.workflow.workflow.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.core.exception.base.BaseException;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.NodeProcessResult;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.data.NodeIOData;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.NODE_PROCESS_STATUS_FAIL;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.NODE_PROCESS_STATUS_SUCCESS;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_FAIL;

/**
 * 表驱动：aiflow 节点「失败 → 退避重试 → DEAD 转人工留痕」语义移植验收
 * （对照源 ruoyi-ipd AiExecutionEngine.finalizeTask/notifyIfDead，补遗 §5-3 / G5 / D6-D8）。
 *
 * <p>脚本数据均取自真实写入路径可能产生的异常/软失败组合：
 * HttpRequestNode.executeHttpRequest 终局 RuntimeException、AbstractWfNode.checkAndGetConfig
 * 的 BaseException、SwitcherNode 评估异常的 error=true 软失败（D8 吞错点）、getMessage()==null 异常。
 */
class AbstractWfNodeRetryDeadTest {

    private static final NodeIOData INPUT = NodeIOData.createByText("input", "用户输入", "hello");

    @AfterEach
    void restoreSleeper() {
        NodeFailurePolicy.sleeper = seconds -> Thread.sleep(seconds * 1000L);
    }

    /** 场景脚本：一次尝试返回 SUCCESS / 软失败 / 抛异常 */
    private enum StepMode { FAIL_EXCEPTION, FAIL_EXCEPTION_NULL_MSG, FAIL_BASE_EXCEPTION, FAIL_SOFT, SUCCEED }

    private static final class ScriptedNode extends AbstractWfNode {
        private final List<StepMode> script;
        private final NodeProcessResult softResult;
        private int calls = 0;

        ScriptedNode(List<StepMode> script, NodeProcessResult softResult) {
            super(new WorkflowComponent(), scriptedDefNode(), new WfState(new User(), new ArrayList<>(List.of(INPUT)),
                    "rt-uuid", 1L, "token", null, 1L), new WfNodeState());
            this.script = script;
            this.softResult = softResult;
        }

        private static WorkflowNode scriptedDefNode() {
            WorkflowNode def = new WorkflowNode();
            def.setId(100L);
            def.setUuid("node-scripted");
            def.setTitle("HTTP请求");
            def.setInputConfig("{\"refInputs\":[],\"userInputs\":[]}");
            def.setNodeConfig("{}");
            return def;
        }

        @Override
        protected NodeProcessResult onProcess() {
            int idx = Math.min(calls, script.size() - 1);
            calls++;
            StepMode mode = script.get(idx);
            switch (mode) {
                case SUCCEED:
                    return NodeProcessResult.builder()
                            .content(new ArrayList<>(List.of(NodeIOData.createByText("output", "输出", "ok"))))
                            .build();
                case FAIL_SOFT:
                    return softResult;
                case FAIL_BASE_EXCEPTION:
                    throw new BaseException("节点配置错误");
                case FAIL_EXCEPTION_NULL_MSG:
                    throw new NullPointerException();
                case FAIL_EXCEPTION:
                default:
                    throw new RuntimeException("HTTP 请求失败，已重试 2 次");
            }
        }
    }

    /** 表驱动入口：按脚本跑一次 process，返回 [节点, 退避序列] */
    private static Object[] runScript(List<StepMode> script, NodeProcessResult softResult) {
        List<Long> sleeps = new ArrayList<>();
        NodeFailurePolicy.sleeper = sleeps::add;
        ScriptedNode node = new ScriptedNode(script, softResult);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> node.process(null, null));
        return new Object[]{node, sleeps, thrown};
    }

    // ---------- 断点 1：无退避重试（G5）→ 移植后按 30s/2m/10m 退避 ----------

    @Test
    void transientFailureThenSuccess_onThirdAttempt() {
        List<Long> sleeps = new ArrayList<>();
        NodeFailurePolicy.sleeper = sleeps::add;
        ScriptedNode node = new ScriptedNode(
                List.of(StepMode.FAIL_EXCEPTION, StepMode.FAIL_EXCEPTION, StepMode.SUCCEED), null);
        NodeProcessResult result = node.process(null, null);

        assertEquals(3, node.calls, "应恰好尝试 3 次（对齐 MAX_ATTEMPTS=3）");
        assertEquals(List.of(30L, 120L), sleeps, "退避序列必须对齐 AiExecutionEngine.BACKOFF_SECONDS[0..1]");
        assertEquals(NODE_PROCESS_STATUS_SUCCESS, node.getState().getProcessStatus());
        assertFalse(result.isError());
    }

    // ---------- 断点 2：无 DEAD 转人工语义 → 第 3 次失败 DEAD + 留痕 ----------

    @Test
    void persistentFailure_reachesDeadAndLeavesManualHandoffTrail() {
        Object[] r = runScript(List.of(StepMode.FAIL_EXCEPTION, StepMode.FAIL_EXCEPTION, StepMode.FAIL_EXCEPTION), null);
        ScriptedNode node = (ScriptedNode) r[0];
        @SuppressWarnings("unchecked") List<Long> sleeps = (List<Long>) r[1];
        RuntimeException thrown = (RuntimeException) r[2];

        assertEquals(3, node.calls);
        assertEquals(List.of(30L, 120L), sleeps, "DEAD 后不再退避（第 3 次直接终局）");
        assertEquals(NODE_PROCESS_STATUS_FAIL, node.getState().getProcessStatus(),
                "终局失败必须把节点实例置 FAIL（留痕经 outputConsumer 落 t_workflow_runtime_node.status_remark）");
        String remark = node.getState().getProcessStatusRemark();
        assertTrue(remark.contains("已重试 3 次仍失败，请人工接管（原手工路径不受影响）"), "DEAD 留痕文案对齐源引擎 notifyIfDead: " + remark);
        assertTrue(remark.contains("HTTP 请求失败，已重试 2 次"), "留痕必须携带原始错误，不许丢 cause（D6）: " + remark);
        assertNotNull(thrown.getCause(), "RuntimeException 必须保留原异常链");
        assertInstanceOf(RuntimeException.class, thrown.getCause());
        assertEquals(WORKFLOW_PROCESS_STATUS_FAIL, node.getWfState().getProcessStatus());
    }

    // ---------- 断点 3：D8 软失败被吞（error=true 无消费端）→ 现按失败重试并进 DEAD ----------

    @Test
    void softErrorResult_noLongerSwallowed_goesThroughRetryToDead() {
        NodeProcessResult soft = NodeProcessResult.builder()
                .content(new ArrayList<>(List.of(NodeIOData.createByText("error", "switcher", "bad"))))
                .error(true)
                .message("条件分支节点错误: 表达式解析失败")
                .build();
        Object[] r = runScript(List.of(StepMode.FAIL_SOFT, StepMode.FAIL_SOFT, StepMode.FAIL_SOFT), soft);
        ScriptedNode node = (ScriptedNode) r[0];
        RuntimeException thrown = (RuntimeException) r[2];

        assertEquals(3, node.calls, "软失败（error=true）应进入重试循环而不是直接 SUCCESS");
        String remark = node.getState().getProcessStatusRemark();
        assertTrue(remark.contains("节点执行失败转人工"), remark);
        assertTrue(remark.contains("条件分支节点错误"), "软失败 message 不许被丢成 process error:null（D8）: " + remark);
        assertInstanceOf(BaseException.class, thrown, "纯软失败终局以 BaseException 抛出（无原异常可包）");
    }

    // ---------- 断点 4：errorMsg==null 防御（对齐源引擎 "unknown" 兜底）----------

    @Test
    void exceptionWithNullMessage_usesClassNameAndUnknownFallback() {
        Object[] r = runScript(
                List.of(StepMode.FAIL_EXCEPTION_NULL_MSG, StepMode.FAIL_EXCEPTION_NULL_MSG, StepMode.FAIL_EXCEPTION_NULL_MSG), null);
        ScriptedNode node = (ScriptedNode) r[0];
        RuntimeException thrown = (RuntimeException) r[2];

        assertTrue(thrown.getCause() instanceof NullPointerException);
        String remark = node.getState().getProcessStatusRemark();
        assertTrue(remark.contains("NullPointerException"), "message==null 时取类名（对齐源引擎）: " + remark);
        assertTrue(remark.contains("错误: NullPointerException"), remark);
    }

    // ---------- 断点 5：配置类失败（BaseException 真实路径）同样走满重试 ----------

    @Test
    void baseExceptionFailure_alsoRetriesToDead() {
        Object[] r = runScript(
                List.of(StepMode.FAIL_BASE_EXCEPTION, StepMode.FAIL_BASE_EXCEPTION, StepMode.FAIL_BASE_EXCEPTION), null);
        ScriptedNode node = (ScriptedNode) r[0];
        @SuppressWarnings("unchecked") List<Long> sleeps = (List<Long>) r[1];

        assertEquals(3, node.calls);
        assertEquals(List.of(30L, 120L), sleeps);
        assertTrue(node.getState().getProcessStatusRemark().contains("节点配置错误"));
    }

    // ---------- 成功路径零回归：不重试、不留痕、状态 SUCCESS ----------

    @Test
    void successPath_untouched() {
        List<Long> sleeps = new ArrayList<>();
        NodeFailurePolicy.sleeper = sleeps::add;
        ScriptedNode node = new ScriptedNode(List.of(StepMode.SUCCEED), null);
        NodeProcessResult result = node.process(null, null);

        assertEquals(1, node.calls);
        assertTrue(sleeps.isEmpty());
        assertEquals(NODE_PROCESS_STATUS_SUCCESS, node.getState().getProcessStatus());
        assertEquals("", node.getState().getProcessStatusRemark());
        assertTrue(node.getWfState().getCompletedNodes().contains(node), "成功节点仍须入 completedNodes（原语义）");
        assertFalse(result.getContent().isEmpty());
    }

    // ---------- 策略常量与源引擎逐一对照（哨兵）----------

    @Test
    void policyConstantsMirrorAiExecutionEngine() {
        assertEquals(3, NodeFailurePolicy.MAX_ATTEMPTS);
        assertEquals(3, NodeFailurePolicy.BACKOFF_SECONDS.length);
        assertEquals(30L, NodeFailurePolicy.backoffSeconds(1));
        assertEquals(120L, NodeFailurePolicy.backoffSeconds(2));
        assertEquals(600L, NodeFailurePolicy.backoffSeconds(3));
        assertFalse(NodeFailurePolicy.isDead(2));
        assertTrue(NodeFailurePolicy.isDead(3));
        assertEquals(NodeFailurePolicy.Outcome.DEAD, NodeFailurePolicy.decide(3, false, "x"));
        assertEquals(NodeFailurePolicy.Outcome.RETRY, NodeFailurePolicy.decide(1, false, "x"));
        assertEquals(NodeFailurePolicy.Outcome.SUCCESS, NodeFailurePolicy.decide(1, true, null));
        assertEquals("unknown", NodeFailurePolicy.safeError(null));
        String msg = NodeFailurePolicy.deadTrailMessage("HTTP请求", 3, "boom");
        assertTrue(msg.contains("HTTP请求") && msg.contains("已重试 3 次") && msg.contains("错误: boom"), msg);
        assertEquals(512, NodeFailurePolicy.safeError("x".repeat(900)).length(), "留痕截断对齐源引擎 truncate(512)");
    }
}
