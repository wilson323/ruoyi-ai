package org.ruoyi.workflow.workflow.node.switcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.NodeProcessResult;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.ruoyi.workflow.workflow.node.NodeFailurePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原始 spel 表达式（SwitcherCase.spel 可选字段）与沙箱契约测试（D2 错误路径 + 逃逸面）。
 *
 * <p>沙箱契约（SimpleEvaluationContext.forReadOnlyDataBinding + withInstanceMethods）：
 * 禁 T() 类型引用、禁构造器、禁 bean 引用、禁 class/classLoader 反射——表达式逃逸必须
 * 抛 SwitcherEvaluationException.Fatal（带节点/case/表达式上下文，不可重试）而非静默求值。
 */
@Tag("dev")
class SwitcherSpelSandboxTest {

    // ---------- 原始 spel 正常路径 ----------

    @Test
    @DisplayName("spel：#vars 字符串变量比较命中/不命中")
    void spelStringVars() {
        assertEquals("1", run(spelCase("case-spel", "#vars['score'] == '95'"), Map.of("score", "95")));
        assertEquals("default", run(spelCase("case-spel", "#vars['score'] == '95'"), Map.of("score", "90")));
        assertEquals("1", run(spelCase("case-spel", "#score != 'x' and #vars['grade'].startsWith('A')"),
                Map.of("score", "95", "grade", "A+")));
    }

    @Test
    @DisplayName("spel：#nums 数值变量做数值比较（String 与数值字面量比较是类型错，须走 #nums）")
    void spelNumericVars() {
        assertEquals("1", run(spelCase("case-spel", "#nums['score'] > 90"), Map.of("score", "95")));
        assertEquals("default", run(spelCase("case-spel", "#nums['score'] > 90"), Map.of("score", "85")));
        // BigDecimal compareTo 语义：1.50 与 1.5 相等
        assertEquals("1", run(spelCase("case-spel", "#nums['price'] >= 1.5"), Map.of("price", "1.50")));
    }

    @Test
    @DisplayName("spel：非空时优先于 conditions（新增可选字段语义）")
    void spelTakesPrecedenceOverConditions() {
        SwitcherCase switcherCase = spelCase("case-spel", "#vars['score'] == '0'");
        switcherCase.setOperator("and");
        SwitcherCase.Condition alwaysTrue = new SwitcherCase.Condition();
        alwaysTrue.setUuid("cond-1");
        alwaysTrue.setNodeUuid("node-src");
        alwaysTrue.setNodeParamName("score");
        alwaysTrue.setOperator("=");
        alwaysTrue.setValue("95");
        switcherCase.setConditions(List.of(alwaysTrue));
        assertEquals("default", run(switcherCase, Map.of("score", "95")), "spel=false 必须压过 conditions=true");
    }

    // ---------- D2 错误路径：表达式错 / 变量缺失 / 类型不匹配等 ----------

    @Test
    @DisplayName("D2：表达式语法错误 → 带节点/case/表达式上下文的不可重试错误")
    void syntaxError_fatalWithContext() {
        SwitcherEvaluationException thrown = assertThrows(SwitcherEvaluationException.class,
                () -> run(spelCase("case-spel-bad", "#vars['score'] =="), Map.of("score", "95")));
        assertFatalWithContext(thrown, "case-spel-bad", "#vars['score'] ==");
        assertTrue(thrown.getMessage().contains("语法错误"), thrown.getMessage());
    }

    @Test
    @DisplayName("D2：引用未提供变量 → 不可重试错误（SpEL 默认把缺失变量静默当 null，必须拦成错误）")
    void missingVariable_fatalWithContext() {
        SwitcherEvaluationException thrown = assertThrows(SwitcherEvaluationException.class,
                () -> run(spelCase("case-spel-missing", "#nope == 'x'"), Map.of("score", "95")));
        assertFatalWithContext(thrown, "case-spel-missing", "#nope == 'x'");
        assertTrue(thrown.getMessage().contains("#nope") && thrown.getMessage().contains("未提供的变量"),
                thrown.getMessage());
    }

    @Test
    @DisplayName("D2：类型不匹配（String 与数值比较）→ 不可重试错误")
    void typeMismatch_fatalWithContext() {
        SwitcherEvaluationException thrown = assertThrows(SwitcherEvaluationException.class,
                () -> run(spelCase("case-spel-type", "#score > 5"), Map.of("score", "abc")));
        assertFatalWithContext(thrown, "case-spel-type", "#score > 5");
    }

    @Test
    @DisplayName("D2：表达式结果非布尔 → 不可重试错误")
    void nonBooleanResult_fatalWithContext() {
        SwitcherEvaluationException thrown = assertThrows(SwitcherEvaluationException.class,
                () -> run(spelCase("case-spel-nonbool", "#score"), Map.of("score", "95")));
        assertFatalWithContext(thrown, "case-spel-nonbool", "#score");
        assertTrue(thrown.getMessage().contains("布尔"), thrown.getMessage());
    }

    // ---------- 沙箱逃逸面（契约：全部拒绝）----------

    @ParameterizedTest(name = "[{index}] 逃逸表达式被拦截: {0}")
    @ValueSource(strings = {
            "T(java.lang.Runtime).getRuntime().exec('x')",
            "new java.lang.ProcessBuilder('x').start()",
            "@someBean.hello()",
            "#vars.class",
            "#vars.getClass()",
            "#score.getClass().getClassLoader()",
            "T(java.lang.String).valueOf('x')"
    })
    @DisplayName("沙箱契约：类型引用/构造器/bean 引用/反射逃逸全部拦成不可重试错误")
    void sandboxEscapes_areRejected(String expression) {
        SwitcherEvaluationException thrown = assertThrows(SwitcherEvaluationException.class,
                () -> run(spelCase("case-escape", expression), Map.of("score", "95")),
                "逃逸表达式必须被拦截: " + expression);
        assertFatalWithContext(thrown, "case-escape", expression);
    }

    @Test
    @DisplayName("沙箱契约：#vars 为不可变 Map（写入抛错，数据面只读）")
    void varsMap_isReadOnly() {
        assertThrows(SwitcherEvaluationException.class,
                () -> run(spelCase("case-put", "#vars.put('x','y') == null"), Map.of("score", "95")));
    }

    // ==================== 夹具 ====================

    private void assertFatalWithContext(SwitcherEvaluationException thrown, String caseUuid, String expression) {
        assertTrue(thrown instanceof NodeFailurePolicy.NonRetryable,
                "确定性错误必须标记不可重试: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("switch-1"), "必须带 switcher 节点 uuid: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(caseUuid), "必须带 case uuid: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(expression), "必须带表达式原文: " + thrown.getMessage());
    }

    private static SwitcherCase spelCase(String caseUuid, String expression) {
        SwitcherCase switcherCase = new SwitcherCase();
        switcherCase.setUuid(caseUuid);
        switcherCase.setSpel(expression);
        switcherCase.setTargetNodeUuid("target-a");
        return switcherCase;
    }

    /** 跑一条 case，返回 matched_case 输出值（"1" 或 "default"） */
    private static String run(SwitcherCase switcherCase, Map<String, String> inputs) {
        SwitcherNodeConfig config = new SwitcherNodeConfig();
        config.setCases(List.of(switcherCase));
        config.setDefaultTargetNodeUuid("target-default");
        WorkflowNode def = new WorkflowNode();
        def.setId(100L);
        def.setUuid("switch-1");
        def.setTitle("条件分支");
        def.setInputConfig("{\"refInputs\":[],\"userInputs\":[]}");
        def.setNodeConfig("{}");
        TestSwitcherNode node = new TestSwitcherNode(def, config,
                new WfState(new User(), new ArrayList<>(), "rt-uuid", 1L, "token", null, 1L));
        inputs.forEach((name, value) -> node.getState().getInputs().add(NodeIOData.createByText(name, name, value)));
        NodeProcessResult result = node.onProcess();
        return result.getContent().stream()
                .filter(item -> "matched_case".equals(item.getName()))
                .map(NodeIOData::valueToString)
                .findFirst().orElse(null);
    }

    private static final class TestSwitcherNode extends SwitcherNode {
        private final SwitcherNodeConfig config;

        TestSwitcherNode(WorkflowNode def, SwitcherNodeConfig config, WfState wfState) {
            super(new WorkflowComponent(), def, wfState, new WfNodeState());
            this.config = config;
        }

        @Override
        protected SwitcherNodeConfig loadConfig() {
            return config;
        }

        @Override
        protected WorkflowNode lookupWorkflowNode(String nodeUuid) {
            return null;
        }
    }
}
