package org.ruoyi.workflow.workflow.node.switcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.NodeProcessResult;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.ruoyi.workflow.workflow.node.AbstractWfNode;
import org.ruoyi.workflow.workflow.node.NodeFailurePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SwitcherNode → Spring SpEL 迁移契约测试（补遗 §5 优先序第 4 项 / R3 + D2 修复）。
 *
 * <p>断言均为分支决策契约（命中 case / default / 抛错），不是实现现状：
 * 篡改运算符翻译、逻辑组合、兜底语义或错误上下文即红（自证见报告）。
 * 测试数据形态取自设计器真实写入路径可能产生的 SwitcherCase 配置组合
 * （operator 词表 = OperatorEnum 持久化名，见 mock 合法性规约一）。
 */
@Tag("dev")
class SwitcherNodeSpelTest {

    // ---------- 表驱动：OperatorEnum 全部操作符 × 命中 / 不命中 ----------

    static Stream<Arguments> operatorHits() {
        return Stream.of(
                Arguments.of("contains", "流程引擎重构", "引擎"),
                Arguments.of("not contains", "abc", "z"),
                Arguments.of("start with", "hello-world", "hello"),
                Arguments.of("end with", "hello-world", "world"),
                Arguments.of("empty", "   ", "任意"),
                Arguments.of("empty", "", "任意"),
                Arguments.of("not empty", "x", "任意"),
                Arguments.of("not empty", " x ", "任意"),
                Arguments.of("=", "v1.0", "v1.0"),
                Arguments.of("!=", "v1.0", "v2"),
                Arguments.of(">", "10", "9"),
                Arguments.of(">=", "10", "9"),
                Arguments.of("<", "9", "10"),
                Arguments.of("<=", "9", "10")
        );
    }

    static Stream<Arguments> operatorMisses() {
        return Stream.of(
                Arguments.of("contains", "流程引擎重构", "测试"),
                Arguments.of("not contains", "abc", "a"),
                Arguments.of("start with", "hello-world", "world"),
                Arguments.of("end with", "hello-world", "hello"),
                Arguments.of("empty", "x", "任意"),
                Arguments.of("not empty", "   ", "任意"),
                Arguments.of("not empty", "", "任意"),
                Arguments.of("=", "v1.0", "v1"),
                Arguments.of("!=", "v1.0", "v1.0"),
                Arguments.of(">", "9", "10"),
                Arguments.of(">=", "9", "10"),
                Arguments.of("<", "10", "9"),
                Arguments.of("<=", "10", "9")
        );
    }

    @ParameterizedTest(name = "[{index}] operator={0} actual='{1}' expected='{2}' → 命中 case 1")
    @MethodSource("operatorHits")
    @DisplayName("运算符矩阵：命中 → 路由到 case 分支")
    void operatorMatrix_hit(String operator, String actual, String expected) {
        NodeProcessResult result = run(config(singleCase(operator, actual, expected)), List.of(input("score", actual)));
        assertEquals("1", outputValue(result, "matched_case"), "应命中第 1 条 case");
        assertEquals("target-a", outputValue(result, "target_node"));
        assertEquals("target-a", result.getNextNodeUuid());
    }

    @ParameterizedTest(name = "[{index}] operator={0} actual='{1}' expected='{2}' → 不命中走 default")
    @MethodSource("operatorMisses")
    @DisplayName("运算符矩阵：不命中 → 路由到默认分支")
    void operatorMatrix_miss(String operator, String actual, String expected) {
        NodeProcessResult result = run(config(singleCase(operator, actual, expected)), List.of(input("score", actual)));
        assertEquals("default", outputValue(result, "matched_case"), "未命中必须走默认分支");
        assertEquals("target-default", outputValue(result, "target_node"));
    }

    @Test
    @DisplayName("矩阵自证：OperatorEnum 全部 12 操作符都有正反例（防止词表扩了测试不跟）")
    void matrixCoversAllOperators() {
        Set<String> covered = Stream.concat(operatorHits(), operatorMisses())
                .map(args -> (String) args.get()[0]).collect(Collectors.toSet());
        for (OperatorEnum operator : OperatorEnum.values()) {
            assertTrue(covered.contains(operator.getName()), "操作符 " + operator.getName() + " 缺测试覆盖");
        }
    }

    // ---------- 数值/兜底语义契约（存量手写引擎逐位语义）----------

    static Stream<Arguments> legacySemantics() {
        return Stream.of(
                // BigDecimal.compareTo 语义：1.0 与 1.00 相等，>= 命中、> 不命中
                Arguments.of(">=", "1.0", "1.00", true),
                Arguments.of(">", "1.0", "1.00", false),
                // 数值两侧去空白后解析（存量 trim）
                Arguments.of(">", " 10 ", "9", true),
                // 数值解析失败 → 条件恒 false（存量 NumberFormatException 兜底）
                Arguments.of(">", "abc", "9", false),
                Arguments.of("<=", "9", "abc", false),
                // 字符串 = 是 equals（"1.0" != "1.00"），与数值比较语义并存
                Arguments.of("=", "1.0", "1.00", false),
                // 大数不丢精度（BigDecimal 而非 double）
                Arguments.of(">", "9007199254740993", "9007199254740992", true)
        );
    }

    @ParameterizedTest(name = "[{index}] {0} '{1}' vs '{2}' → 命中={3}")
    @MethodSource("legacySemantics")
    @DisplayName("存量语义逐位契约：BigDecimal 比较/空白容错/解析失败恒 false")
    void legacySemanticsPreserved(String operator, String actual, String expected, boolean hit) {
        NodeProcessResult result = run(config(singleCase(operator, actual, expected)), List.of(input("score", actual)));
        assertEquals(hit ? "1" : "default", outputValue(result, "matched_case"));
    }

    // ---------- LogicOperatorEnum 全组合 ----------

    @Test
    @DisplayName("AND：两个条件都真才命中")
    void logicAnd_allTrue_hits() {
        SwitcherCase switcherCase = multiConditionCase("and",
                cond("n1", "a", "=", "x"), cond("n1", "b", "=", "y"));
        NodeProcessResult result = run(config(switcherCase), List.of(input("a", "x"), input("b", "y")));
        assertEquals("1", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("AND：任一条件假 → 不命中")
    void logicAnd_oneFalse_misses() {
        SwitcherCase switcherCase = multiConditionCase("and",
                cond("n1", "a", "=", "x"), cond("n1", "b", "=", "y"));
        NodeProcessResult result = run(config(switcherCase), List.of(input("a", "x"), input("b", "WRONG")));
        assertEquals("default", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("OR：任一条件真即命中")
    void logicOr_oneTrue_hits() {
        SwitcherCase switcherCase = multiConditionCase("or",
                cond("n1", "a", "=", "x"), cond("n1", "b", "=", "y"));
        NodeProcessResult result = run(config(switcherCase), List.of(input("a", "x"), input("b", "WRONG")));
        assertEquals("1", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("OR：全假 → 不命中")
    void logicOr_allFalse_misses() {
        SwitcherCase switcherCase = multiConditionCase("or",
                cond("n1", "a", "=", "x"), cond("n1", "b", "=", "y"));
        NodeProcessResult result = run(config(switcherCase), List.of(input("a", "WRONG"), input("b", "WRONG")));
        assertEquals("default", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("AND 不分大小写（'And'/'AND' 与 'and' 同义）")
    void logicAnd_caseInsensitive() {
        for (String op : List.of("and", "And", "AND")) {
            SwitcherCase switcherCase = multiConditionCase(op,
                    cond("n1", "a", "=", "x"), cond("n1", "b", "=", "y"));
            NodeProcessResult result = run(config(switcherCase), List.of(input("a", "x"), input("b", "WRONG")));
            assertEquals("default", outputValue(result, "matched_case"), "operator=" + op + " 应为 AND 语义");
        }
    }

    @Test
    @DisplayName("case.operator 为 null/非 and → OR 语义（存量 isAnd 判定）")
    void logicNullOrOther_isOr() {
        for (String op : java.util.Arrays.asList(null, "or", "xor")) {
            SwitcherCase switcherCase = multiConditionCase(op,
                    cond("n1", "a", "=", "x"), cond("n1", "b", "=", "y"));
            NodeProcessResult result = run(config(switcherCase), List.of(input("a", "x"), input("b", "WRONG")));
            assertEquals("1", outputValue(result, "matched_case"), "operator=" + op + " 应为 OR 语义");
        }
    }

    // ---------- default 分支 / case 遍历契约 ----------

    @Test
    @DisplayName("空 case 列表 → default")
    void emptyCases_goesDefault() {
        SwitcherNodeConfig cfg = new SwitcherNodeConfig();
        cfg.setCases(List.of());
        cfg.setDefaultTargetNodeUuid("target-default");
        NodeProcessResult result = run(cfg, List.of(input("a", "x")));
        assertEquals("default", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("case 无条件（conditions 空）→ 跳过（存量恒 false 语义）")
    void emptyConditions_skipped() {
        SwitcherCase noConditions = new SwitcherCase();
        noConditions.setUuid("case-empty");
        noConditions.setOperator("and");
        noConditions.setConditions(List.of());
        noConditions.setTargetNodeUuid("target-a");
        NodeProcessResult result = run(config(noConditions), List.of(input("a", "x")));
        assertEquals("default", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("多 case 顺序求值：第一个命中者胜出")
    void firstMatchWins() {
        SwitcherCase first = multiConditionCase("and", cond("n1", "a", "=", "x"));
        first.setUuid("case-first");
        first.setTargetNodeUuid("target-first");
        SwitcherCase second = multiConditionCase("and", cond("n1", "a", "=", "x"));
        second.setUuid("case-second");
        second.setTargetNodeUuid("target-second");
        NodeProcessResult result = run(config(first, second), List.of(input("a", "x")));
        assertEquals("case-first", outputValue(result, "case_uuid"));
        assertEquals("target-first", result.getNextNodeUuid());
    }

    @Test
    @DisplayName("命中但 target_node_uuid 为空 → 跳过继续评估后续 case（存量语义）")
    void matchedBlankTarget_fallsThroughToNextCase() {
        SwitcherCase blankTarget = multiConditionCase("and", cond("n1", "a", "=", "x"));
        blankTarget.setUuid("case-blank");
        blankTarget.setTargetNodeUuid(" ");
        SwitcherCase second = multiConditionCase("and", cond("n1", "a", "=", "x"));
        second.setUuid("case-second");
        second.setTargetNodeUuid("target-second");
        NodeProcessResult result = run(config(blankTarget, second), List.of(input("a", "x")));
        assertEquals("case-second", outputValue(result, "case_uuid"));
    }

    @Test
    @DisplayName("全部命中均为空 target → 落 default")
    void matchedBlankTarget_fallsToDefault() {
        SwitcherCase blankTarget = multiConditionCase("and", cond("n1", "a", "=", "x"));
        blankTarget.setUuid("case-blank");
        blankTarget.setTargetNodeUuid(null);
        NodeProcessResult result = run(config(blankTarget), List.of(input("a", "x")));
        assertEquals("default", outputValue(result, "matched_case"));
    }

    // ---------- 输出契约（WorkflowEngine "next" 路由键 + 下游 matched_case/case_uuid/target_node）----------

    @Test
    @DisplayName("命中输出契约：透传非 input 参数 + matched_case/case_uuid/target_node")
    void outputContract_onMatch() {
        SwitcherCase switcherCase = multiConditionCase("and", cond("n1", "a", "=", "x"));
        NodeProcessResult result = run(config(switcherCase),
                List.of(input("a", "x"), NodeIOData.createByText("output", "输出", "下游文本")));
        List<String> names = result.getContent().stream().map(NodeIOData::getName).toList();
        assertTrue(names.contains("output"), "output 参数必须透传");
        assertFalse(names.contains("input"), "input 参数不透传（与 output 冗余，存量过滤契约）");
        assertEquals("1", outputValue(result, "matched_case"));
        assertEquals("case-1", outputValue(result, "case_uuid"));
        assertEquals("target-a", outputValue(result, "target_node"));
    }

    @Test
    @DisplayName("命中输出契约：无 output 时从 input 派生 output 供下游使用")
    void outputContract_derivesOutputFromInput() {
        SwitcherCase switcherCase = multiConditionCase("and", cond("n1", "a", "=", "x"));
        NodeProcessResult result = run(config(switcherCase),
                List.of(input("a", "x"), NodeIOData.createByText("input", "用户输入", "hello")));
        assertEquals("hello", outputValue(result, "output"));
    }

    @Test
    @DisplayName("default 输出契约：matched_case=default 且无 case_uuid")
    void outputContract_onDefault() {
        NodeProcessResult result = run(config(singleCase("=", "v1.0", "v1")), List.of(input("score", "v1.0")));
        assertEquals("default", outputValue(result, "matched_case"));
        assertEquals(null, outputValue(result, "case_uuid"));
        assertEquals("target-default", outputValue(result, "target_node"));
    }

    // ---------- 取值解析存量语义 ----------

    @Test
    @DisplayName("取值：按参数名从当前输入取值")
    void valueResolvedFromInputs() {
        SwitcherCase switcherCase = multiConditionCase("and", cond("node-src", "score", "=", "95"));
        NodeProcessResult result = run(config(switcherCase), List.of(input("score", "95")));
        assertEquals("1", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("取值：缺失参数按存量语义空串兜底（'=' '' 命中），不报错——旧流程零迁移")
    void missingValue_emptyFallbackCompat() {
        SwitcherCase switcherCase = multiConditionCase("and", cond("node-src", "not-exists", "=", ""));
        NodeProcessResult result = run(config(switcherCase), List.of(input("score", "95")));
        assertEquals("1", outputValue(result, "matched_case"), "存量缺失值→空串兜底语义必须保持");
    }

    @Test
    @DisplayName("取值：找 output 未命中回退 input（存量特殊处理）")
    void outputFallsBackToInput() {
        SwitcherCase switcherCase = multiConditionCase("and", cond("node-src", "output", "=", "hello"));
        NodeProcessResult result = run(config(switcherCase),
                List.of(NodeIOData.createByText("input", "用户输入", "hello")));
        assertEquals("1", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("取值：无同名输入时从指定节点（node_uuid）历史输出取参数")
    void valueResolvedFromNodeOutputs() {
        WfState wfState = wfState();
        AbstractWfNode upstream = stubCompletedNode(wfState, "node-src");
        upstream.getState().getOutputs().add(NodeIOData.createByText("score", "得分", "95"));
        SwitcherCase switcherCase = multiConditionCase("and", cond("node-src", "score", ">", "90"));
        TestSwitcherNode node = new TestSwitcherNode(defNode(), wfState, config(switcherCase), Map.of());
        node.getState().getInputs().add(input("other", "不相关的输入"));
        NodeProcessResult result = node.onProcess();
        assertEquals("1", outputValue(result, "matched_case"));
    }

    @Test
    @DisplayName("取值优先级：当前输入按名优先于节点历史输出（存量 getValueFromInputs 顺序，零迁移契约）")
    void inputByNameWinsOverNodeOutputs() {
        WfState wfState = wfState();
        AbstractWfNode upstream = stubCompletedNode(wfState, "node-src");
        upstream.getState().getOutputs().add(NodeIOData.createByText("score", "得分", "95"));
        SwitcherCase switcherCase = multiConditionCase("and", cond("node-src", "score", ">", "90"));
        TestSwitcherNode node = new TestSwitcherNode(defNode(), wfState, config(switcherCase), Map.of());
        node.getState().getInputs().add(input("score", "不是这个值"));
        NodeProcessResult result = node.onProcess();
        assertEquals("default", outputValue(result, "matched_case"),
                "存量语义：同名输入优先于节点历史输出，'不是这个值' 数值比较不命中");
    }

    // ---------- D2 错误路径（存量配置错误）----------

    @Test
    @DisplayName("D2：未知运算符是确定性配置错误——带节点/case/操作数上下文抛出且不可重试")
    void unknownOperator_failsWithContextAndNonRetryable() {
        SwitcherCase switcherCase = multiConditionCase("and", cond("node-src", "score", "roughly", "95"));
        switcherCase.setUuid("case-bad-op");
        SwitcherEvaluationException thrown = assertThrows(SwitcherEvaluationException.class,
                () -> run(config(switcherCase), List.of(input("score", "95"))));
        assertTrue(thrown instanceof NodeFailurePolicy.NonRetryable, "配置错误必须标记不可重试");
        assertTrue(thrown.getMessage().contains("switch-1"), "必须带 switcher 节点 uuid: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("case-bad-op"), "必须带 case uuid: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("roughly"), "必须带未知运算符原文: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("95"), "必须带操作数: " + thrown.getMessage());
    }

    @Test
    @DisplayName("D2：评估异常不再吞成 error 输出——抛出而非 NodeProcessResult.error=true")
    void evaluationError_throwsInsteadOfSoftErrorOutput() {
        SwitcherCase switcherCase = multiConditionCase("and", cond("node-src", "score", "???bad", "95"));
        assertThrows(SwitcherEvaluationException.class,
                () -> run(config(switcherCase), List.of(input("score", "95"))));
    }

    // ==================== 测试夹具 ====================

    /** 只覆写接缝的被测节点：配置直供、节点定义查询免 DB（数据形态=设计器真实写入组合） */
    private static final class TestSwitcherNode extends SwitcherNode {
        private final SwitcherNodeConfig config;
        private final Map<String, WorkflowNode> nodeTable;

        TestSwitcherNode(WorkflowNode def, WfState wfState, SwitcherNodeConfig config, Map<String, WorkflowNode> nodeTable) {
            super(new WorkflowComponent(), def, wfState, new WfNodeState());
            this.config = config;
            this.nodeTable = nodeTable;
        }

        @Override
        protected SwitcherNodeConfig loadConfig() {
            return config;
        }

        @Override
        protected WorkflowNode lookupWorkflowNode(String nodeUuid) {
            return nodeTable.get(nodeUuid);
        }
    }

    private static NodeProcessResult run(SwitcherNodeConfig config, List<NodeIOData> inputs) {
        TestSwitcherNode node = new TestSwitcherNode(defNode(), wfState(), config, Map.of());
        node.getState().getInputs().addAll(inputs);
        return node.onProcess();
    }

    private static String outputValue(NodeProcessResult result, String name) {
        return result.getContent().stream()
                .filter(item -> name.equals(item.getName()))
                .map(NodeIOData::valueToString)
                .findFirst().orElse(null);
    }

    private static NodeIOData input(String name, String value) {
        return NodeIOData.createByText(name, name, value);
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

    private static WfState wfState() {
        return new WfState(new User(), new ArrayList<>(), "rt-uuid", 1L, "token", null, 1L);
    }

    private static AbstractWfNode stubCompletedNode(WfState wfState, String uuid) {
        WorkflowNode def = new WorkflowNode();
        def.setId(200L);
        def.setUuid(uuid);
        def.setTitle("上游节点");
        def.setInputConfig("{\"refInputs\":[],\"userInputs\":[]}");
        def.setNodeConfig("{}");
        AbstractWfNode stub = new AbstractWfNode(new WorkflowComponent(), def, wfState, new WfNodeState()) {
            @Override
            protected NodeProcessResult onProcess() {
                return null;
            }
        };
        wfState.getCompletedNodes().add(stub);
        return stub;
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

    private static SwitcherCase singleCase(String operator, String actualIgnored, String expected) {
        return multiConditionCase("and", cond("node-src", "score", operator, expected));
    }

    private static SwitcherCase multiConditionCase(String caseOperator, SwitcherCase.Condition... conditions) {
        SwitcherCase switcherCase = new SwitcherCase();
        switcherCase.setUuid("case-1");
        switcherCase.setOperator(caseOperator);
        switcherCase.setConditions(List.of(conditions));
        switcherCase.setTargetNodeUuid("target-a");
        return switcherCase;
    }

    private static SwitcherNodeConfig config(SwitcherCase... cases) {
        SwitcherNodeConfig config = new SwitcherNodeConfig();
        config.setCases(List.of(cases));
        config.setDefaultTargetNodeUuid("target-default");
        return config;
    }
}
