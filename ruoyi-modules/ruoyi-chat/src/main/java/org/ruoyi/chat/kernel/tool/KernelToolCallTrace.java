package org.ruoyi.chat.kernel.tool;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具调用裁决事件账（trace）。每个工具调用恰追加一个
 * {@link KernelToolCallDecision}（append-once，恰一裁决事件断言缝）。
 *
 * <p>本账只做事件留痕与计数，不含裁决逻辑；裁决唯一源是 {@code ToolPolicyEngine}。
 * W3 出条件 ②：同一工具调用 trace 恰一个裁决事件 + 一个账本写。
 */
public final class KernelToolCallTrace {

    private final List<KernelToolCallDecision> events = new CopyOnWriteArrayList<>();

    /** 追加一个裁决事件（每次工具调用恰一次）。 */
    public void append(KernelToolCallDecision event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /** 全部裁决事件（时序）。 */
    public List<KernelToolCallDecision> events() {
        return List.copyOf(events);
    }

    /** 裁决事件总数（恰一断言缝）。 */
    public int count() {
        return events.size();
    }

    /** 指定工具名的裁决事件数。 */
    public int countFor(String toolName) {
        return (int) events.stream().filter(e -> e.toolName().equals(toolName)).count();
    }
}
