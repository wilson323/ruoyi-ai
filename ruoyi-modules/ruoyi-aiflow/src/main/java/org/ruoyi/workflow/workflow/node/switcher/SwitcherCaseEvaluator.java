package org.ruoyi.workflow.workflow.node.switcher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Switcher 条件引擎：把存量 SwitcherCase（operator/operand 组合）翻译成 Spring SpEL 表达式后
 * 在沙箱中求值（补遗 §1.3 R3：替换 437 行手写条件引擎；零新依赖，spring-expression 随 Boot 在库）。
 *
 * <p><b>沙箱</b>：{@link SimpleEvaluationContext#forReadOnlyDataBinding()} + {@code withInstanceMethods()}。
 * 禁类型引用 {@code T()}、禁构造器 {@code new}、禁 bean 引用 {@code @}，DataBinding 解析器拦截
 * {@code class}/{@code classLoader} 反射逃逸（Spring 6.2 已实证，见 SwitcherSpelSandboxTest）；
 * 只暴露已解析变量的只读访问，{@code #vars} 为不可变 Map。
 *
 * <p><b>翻译对照</b>（分支决策语义与手写引擎逐位一致，含空值/解析失败的存量兜底）：
 * <pre>
 * contains      → #a.contains(#e)           not contains → !#a.contains(#e)
 * start with    → #a.startsWith(#e)         end with     → #a.endsWith(#e)
 * empty         → #a.trim().isEmpty()       not empty    → !#a.trim().isEmpty()
 *   （非空串上 trim().isEmpty() 与 StringUtils.isBlank 严格等价：同为剔除 ≤' ' 字符）
 * = / !=        → #a == #e / #a != #e       （SpEL == 即 equals，字符串比较与存量一致）
 * &gt; &gt;= &lt; &lt;=  → #a &gt; #e 等（BigDecimal 绑定，SpEL 数值比较即 compareTo：
 *                  "1.0" 与 "1.00" 相等、10 &gt; 9，与存量 new BigDecimal(trim) 一致）
 * </pre>
 * 数值操作数按存量语义预转换：{@code new BigDecimal(trim)} 解析失败 → 该条件恒 false
 * （对应手写引擎 NumberFormatException 兜底）。case.operator 仅 "and"（不分大小写）为 AND，
 * 其余（含 null）为 OR；空 conditions 恒 false（存量跳过语义）。
 *
 * <p><b>原始 spel 表达式</b>（SwitcherCase.spel 新增可选字段，旧配置零迁移）：直接沙箱求值，
 * 变量为各输入参数（#名称，字符串）与只读 Map {@code #vars}（字符串）/{@code #nums}（可解析
 * 数值项的 BigDecimal）；表达式语法/求值错误、引用未提供变量、结果非布尔 →
 * {@link SwitcherEvaluationException.Fatal}（确定性错误，不可重试）。
 */
@Slf4j
public final class SwitcherCaseEvaluator {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private SwitcherCaseEvaluator() {
    }

    /** 取值器：把条件引用（node_uuid + node_param_name）解析为实际值，解析过程可在节点侧带 DB/IO */
    @FunctionalInterface
    public interface ValueResolver {
        String resolve(SwitcherCase.Condition condition);
    }

    /**
     * 存量条件（conditions + operator + value 组合）求值。
     *
     * @param caseContext 错误上下文（哪个 switcher 节点、哪条 case），随错误抛出
     */
    public static boolean evaluateLegacyCase(SwitcherCase switcherCase, String caseContext, ValueResolver resolver) {
        List<SwitcherCase.Condition> conditions = switcherCase.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            log.warn("{} 没有条件，跳过", caseContext);
            return false;
        }
        boolean isAnd = "and".equalsIgnoreCase(switcherCase.getOperator());
        StringBuilder expr = new StringBuilder();
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (int i = 0; i < conditions.size(); i++) {
            SwitcherCase.Condition condition = conditions.get(i);
            String actual = resolver.resolve(condition);
            actual = actual == null ? "" : actual;
            String expected = condition.getValue() == null ? "" : condition.getValue();
            OperatorEnum operator = OperatorEnum.getByName(condition.getOperator());
            if (operator == null) {
                throw new SwitcherEvaluationException.Fatal(caseContext
                        + " 评估失败：未知运算符 operator='" + condition.getOperator()
                        + "'（配置错误，不可重试）；操作数 actual='" + actual + "', expected='" + expected + "'");
            }
            String actualVar = "#a" + i;
            String expectedVar = "#e" + i;
            expr.append('(').append(translate(operator, actual, expected, actualVar, expectedVar, bindings))
                    .append(')').append(isAnd ? " and " : " or ");
        }
        expr.setLength(expr.length() - (isAnd ? 5 : 4));
        return evaluateBoolean(expr.toString(), bindings, caseContext,
                "operator='" + switcherCase.getOperator() + "'");
    }

    /**
     * 原始 spel 表达式（新增可选字段）求值。只读变量面：
     * <ul>
     *   <li>{@code #名称} / {@code #vars['名称']} → 字符串值（字符串比较语义）；</li>
     *   <li>{@code #nums['名称']} → 可解析为数字的值的 BigDecimal（数值比较语义，
     *       SpEL 对 String 与数值字面量比较直接报类型错，数值场景须走 #nums）。</li>
     * </ul>
     * 表达式语法/求值错误、引用未提供变量、结果非布尔 → Fatal（确定性错误，不可重试）。
     */
    public static boolean evaluateSpel(String expression, String caseContext, Map<String, String> vars) {
        Map<String, String> provided = vars == null ? Map.of() : vars;
        Expression parsed;
        try {
            parsed = PARSER.parseExpression(expression);
        } catch (Exception e) {
            throw new SwitcherEvaluationException.Fatal(caseContext
                    + " 评估失败：表达式语法错误，表达式='" + expression + "'；原因: " + e.getMessage(), e);
        }
        List<String> missing = missingVariables(parsed, provided);
        if (!missing.isEmpty()) {
            throw new SwitcherEvaluationException.Fatal(caseContext
                    + " 评估失败：表达式引用了未提供的变量 " + missing
                    + "，表达式='" + expression + "'；可用变量=" + provided.keySet() + "（含 #vars/#nums）");
        }
        Map<String, Object> bindings = new LinkedHashMap<>(provided);
        bindings.put("vars", Collections.unmodifiableMap(new LinkedHashMap<>(provided)));
        Map<String, BigDecimal> nums = new LinkedHashMap<>();
        provided.forEach((name, value) -> {
            BigDecimal number = toNumber(value);
            if (number != null) {
                nums.put(name, number);
            }
        });
        bindings.put("nums", Collections.unmodifiableMap(nums));
        return evaluateBoolean(expression, bindings, caseContext, "表达式='" + expression + "'");
    }

    /** 单个条件 → SpEL 子表达式；数值操作数预绑定 BigDecimal（存量 compareTo 语义） */
    private static String translate(OperatorEnum operator, String actual, String expected,
                                    String actualVar, String expectedVar, Map<String, Object> bindings) {
        switch (operator) {
            case EMPTY:
            case NOT_EMPTY:
                // 存量 isBlank/isNotBlank 只看实际值，与期望值无关
                bindings.put(actualVar.substring(1), actual);
                return (operator == OperatorEnum.EMPTY ? "" : "!") + actualVar + ".trim().isEmpty()";
            case GREATER:
            case GREATER_OR_EQUAL:
            case LESS:
            case LESS_OR_EQUAL:
                BigDecimal actualNum = toNumber(actual);
                BigDecimal expectedNum = toNumber(expected);
                if (actualNum == null || expectedNum == null) {
                    // 存量语义：数值解析失败 → 条件恒 false
                    return "false";
                }
                bindings.put(actualVar.substring(1), actualNum);
                bindings.put(expectedVar.substring(1), expectedNum);
                return actualVar + relationalSymbol(operator) + expectedVar;
            default:
                bindings.put(actualVar.substring(1), actual);
                bindings.put(expectedVar.substring(1), expected);
                switch (operator) {
                    case CONTAINS:
                        return actualVar + ".contains(" + expectedVar + ")";
                    case NOT_CONTAINS:
                        return "!" + actualVar + ".contains(" + expectedVar + ")";
                    case START_WITH:
                        return actualVar + ".startsWith(" + expectedVar + ")";
                    case END_WITH:
                        return actualVar + ".endsWith(" + expectedVar + ")";
                    case EQUAL:
                        return actualVar + " == " + expectedVar;
                    case NOT_EQUAL:
                        return actualVar + " != " + expectedVar;
                    default:
                        throw new IllegalStateException("未覆盖的运算符: " + operator);
                }
        }
    }

    private static String relationalSymbol(OperatorEnum operator) {
        switch (operator) {
            case GREATER:
                return " > ";
            case GREATER_OR_EQUAL:
                return " >= ";
            case LESS:
                return " < ";
            default:
                return " <= ";
        }
    }

    private static BigDecimal toNumber(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 沙箱求值并强制布尔结果；语法/求值/类型错误 → Fatal（确定性，不可重试） */
    private static boolean evaluateBoolean(String expr, Map<String, Object> bindings,
                                           String caseContext, String detail) {
        SimpleEvaluationContext.Builder builder = SimpleEvaluationContext.forReadOnlyDataBinding().withInstanceMethods();
        SimpleEvaluationContext context = builder.build();
        bindings.forEach(context::setVariable);
        Object result;
        try {
            result = PARSER.parseExpression(expr).getValue(context);
        } catch (SwitcherEvaluationException.Fatal fatal) {
            throw fatal;
        } catch (Exception e) {
            throw new SwitcherEvaluationException.Fatal(caseContext
                    + " 评估失败：表达式求值错误，" + detail + "；原因: " + e.getMessage(), e);
        }
        if (!(result instanceof Boolean)) {
            throw new SwitcherEvaluationException.Fatal(caseContext
                    + " 评估失败：表达式结果不是布尔值（实际 " + (result == null ? "null" : result.getClass().getSimpleName())
                    + "），" + detail);
        }
        return (Boolean) result;
    }

    /** 表达式 AST 中引用的 #变量 是否都已提供（SpEL 对缺失变量静默按 null 求值，须显式拦成错误） */
    private static List<String> missingVariables(Expression parsed, Map<String, String> provided) {
        List<String> missing = new ArrayList<>();
        if (parsed instanceof SpelExpression spelExpression) {
            collectMissing(spelExpression.getAST(), provided, missing);
        }
        return missing;
    }

    private static void collectMissing(SpelNode node, Map<String, String> provided, List<String> missing) {
        if (node == null) {
            return;
        }
        if (node instanceof VariableReference) {
            String name = node.toStringAST();
            name = name.startsWith("#") ? name.substring(1) : name;
            if (!provided.containsKey(name) && !"vars".equals(name) && !"nums".equals(name)) {
                missing.add("#" + name);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectMissing(node.getChild(i), provided, missing);
        }
    }
}
