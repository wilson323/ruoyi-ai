package org.ruoyi.workflow.workflow.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.NodeProcessResult;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.node.switcher.SwitcherCase;
import org.ruoyi.workflow.workflow.node.switcher.SwitcherEvaluationException;
import org.ruoyi.workflow.workflow.node.switcher.SwitcherNode;
import org.ruoyi.workflow.workflow.node.switcher.SwitcherNodeConfig;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switcher 失败语义区分契约（补遗 §5-3 重试语义 + R3/D2 之后的区分）：
 * <ul>
 *   <li>确定性错误（表达式语法/配置错误，SwitcherEvaluationException.Fatal 带 NonRetryable 标记）
 *       → 不可重试，立即失败，不退避占线程（否则 3 次 × 30/120/600s 白等 ~15 分钟）；</li>
 *   <li>瞬时性错误（取值时 DB 故障）→ 走 AbstractWfNode 有界重试，3 次后 DEAD 落 status_remark。</li>
 * </ul>
 * 退避经 {@link NodeFailurePolicy#sleeper} 注入记录，测试不真 sleep。
 */
@Tag("dev")
class SwitcherNodeFailureSemanticsTest {

    @AfterEach
    void restoreSleeper() {
        NodeFailurePolicy.sleeper = seconds -> Thread.sleep(seconds * 1000L);
    }

    @Test
    @DisplayName("确定性错误（未知运算符配置错）→ 1 次即失败、零退避、留痕带上下文")
    void deterministicError_failsFastWithoutRetry() {
        List<Long> sleeps = new ArrayList<>();
        NodeFailurePolicy.sleeper = sleeps::add;
        SwitcherCase badCase = caseWith(cond("node-src", "score", "roughly", "95"));
        badCase.setUuid("case-bad-op");
        CountingSwitcherNode node = new CountingSwitcherNode(config(badCase), false);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> node.process(null, null));

        assertEquals(1, node.attempts, "确定性配置错误禁止重试（§5-3 有界重试不适用于配置/表达式错误）");
        assertTrue(sleeps.isEmpty(), "不可重试错误不得退避等待: " + sleeps);
        assertEquals(NODE_PROCESS_STATUS_FAIL, node.getState().getProcessStatus());
        String remark = node.getState().getProcessStatusRemark();
        assertTrue(remark.contains("未知运算符"), "留痕必须带真实错误（D6 不丢 cause）: " + remark);
        assertTrue(remark.contains("case-bad-op"), "留痕必须带 case 上下文（D2）: " + remark);
        assertInstanceOf(SwitcherEvaluationException.class, thrown.getCause(), "原异常链保留");
        assertTrue(NodeFailurePolicy.isNonRetryable(thrown.getCause()), "必须带不可重试标记");
    }

    @Test
    @DisplayName("确定性错误（spel 表达式变量缺失）→ 同样 1 次即失败、零退避")
    void deterministicSpelError_failsFastWithoutRetry() {
        List<Long> sleeps = new ArrayList<>();
        NodeFailurePolicy.sleeper = sleeps::add;
        SwitcherCase badCase = new SwitcherCase();
        badCase.setUuid("case-spel-missing");
        badCase.setSpel("#nope == 'x'");
        badCase.setTargetNodeUuid("target-a");
        CountingSwitcherNode node = new CountingSwitcherNode(config(badCase), false);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> node.process(null, null));

        assertEquals(1, node.attempts);
        assertTrue(sleeps.isEmpty());
        assertTrue(NodeFailurePolicy.isNonRetryable(thrown.getCause()));
        assertTrue(thrown.getCause().getMessage().contains("case-spel-missing"), thrown.getCause().getMessage());
    }

    @Test
    @DisplayName("瞬时性错误（取值 DB 故障）→ 有界重试 3 次后 DEAD，退避序列 30/120，留痕带 case 上下文")
    void transientError_retriesToDeadWithBackoff() {
        List<Long> sleeps = new ArrayList<>();
        NodeFailurePolicy.sleeper = sleeps::add;
        SwitcherCase switcherCase = caseWith(cond("node-src", "score", ">", "90"));
        switcherCase.setUuid("case-transient");
        CountingSwitcherNode node = new CountingSwitcherNode(config(switcherCase), true);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> node.process(null, null));

        assertEquals(3, node.attempts, "瞬时性错误走 §5-3 有界重试（MAX_ATTEMPTS=3）");
        assertEquals(List.of(30L, 120L), sleeps, "退避序列对齐 BACKOFF_SECONDS[0..1]，DEAD 后不再退避");
        String remark = node.getState().getProcessStatusRemark();
        assertTrue(remark.contains("已重试 3 次仍失败，请人工接管"), remark);
        assertTrue(remark.contains("case-transient"), "DEAD 留痕必须带 case 上下文（D2）: " + remark);
        assertFalse(NodeFailurePolicy.isNonRetryable(thrown.getCause()),
                "瞬时性错误不得标记不可重试（否则抖动直接打死流程）");
        assertInstanceOf(SwitcherEvaluationException.class, thrown.getCause());
    }

    // ==================== 夹具 ====================

    /** 统计 onProcess 尝试次数的 SwitcherNode：loadConfig 每次尝试调用一次 */
    private static final class CountingSwitcherNode extends SwitcherNode {
        private final SwitcherNodeConfig config;
        private final boolean lookupFails;
        int attempts;

        CountingSwitcherNode(SwitcherNodeConfig config, boolean lookupFails) {
            super(new WorkflowComponent(), defNode(), new WfState(new User(), new ArrayList<>(),
                    "rt-uuid", 1L, "token", null, 1L), new WfNodeState());
            this.config = config;
            this.lookupFails = lookupFails;
        }

        @Override
        protected SwitcherNodeConfig loadConfig() {
            attempts++;
            return config;
        }

        @Override
        protected WorkflowNode lookupWorkflowNode(String nodeUuid) {
            if (lookupFails) {
                throw new IllegalStateException("DB connection reset");
            }
            return null;
        }
    }

    private static WorkflowNode defNode() {
        WorkflowNode def = new WorkflowNode();
        def.setId(100L);
        def.setUuid("switch-1");
        def.setTitle("条件分支");
        def.setInputConfig("{\"refInputs\":[],\"userInputs\":[]}");
        def.setNodeConfig("{}");
        return def;
    }

    private static SwitcherCase.Condition cond(String nodeUuid, String param, String operator, String value) {
        SwitcherCase.Condition condition = new SwitcherCase.Condition();
        condition.setUuid("cond-1");
        condition.setNodeUuid(nodeUuid);
        condition.setNodeParamName(param);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    private static SwitcherCase caseWith(SwitcherCase.Condition condition) {
        SwitcherCase switcherCase = new SwitcherCase();
        switcherCase.setUuid("case-1");
        switcherCase.setOperator("and");
        switcherCase.setConditions(List.of(condition));
        switcherCase.setTargetNodeUuid("target-a");
        return switcherCase;
    }

    private static SwitcherNodeConfig config(SwitcherCase... cases) {
        SwitcherNodeConfig config = new SwitcherNodeConfig();
        config.setCases(List.of(cases));
        config.setDefaultTargetNodeUuid("target-default");
        return config;
    }

    private static final int NODE_PROCESS_STATUS_FAIL = org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.NODE_PROCESS_STATUS_FAIL;
}
