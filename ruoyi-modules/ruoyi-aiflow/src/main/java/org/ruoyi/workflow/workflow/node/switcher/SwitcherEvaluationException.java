package org.ruoyi.workflow.workflow.node.switcher;

import org.ruoyi.workflow.workflow.node.NodeFailurePolicy;

/**
 * Switcher 条件评估错误（D2 修复）：携带「哪个 switcher 节点、哪条 case、什么表达式/操作数」
 * 上下文，禁止吞成 error 输出或退化成 NPE。
 *
 * <p>失败语义（补遗 §5-3 之后的区分，2026-09-27）：
 * <ul>
 *   <li>本类（父类）：瞬时性错误（如取值时 DB/IO 故障），包装后抛出，走 AbstractWfNode
 *       有界重试（MAX_ATTEMPTS=3，退避 30/120/600 秒）；</li>
 *   <li>{@link Fatal}：确定性错误（表达式语法/翻译/求值错误、未知运算符等配置错误），
 *       重试 3 次也不会变好，实现 {@link NodeFailurePolicy.NonRetryable} 标记，
 *       AbstractWfNode 对其立即终局失败，不再退避占线程。</li>
 * </ul>
 */
public class SwitcherEvaluationException extends RuntimeException {

    public SwitcherEvaluationException(String message) {
        super(message);
    }

    public SwitcherEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 确定性错误（配置/表达式类）：不可重试，立即失败。
     */
    public static class Fatal extends SwitcherEvaluationException implements NodeFailurePolicy.NonRetryable {

        public Fatal(String message) {
            super(message);
        }

        public Fatal(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
