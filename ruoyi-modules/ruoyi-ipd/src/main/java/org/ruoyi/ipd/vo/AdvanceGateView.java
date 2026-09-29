package org.ruoyi.ipd.vo;

import java.util.List;

/** 推进门禁视图（§2.8 不变量④）：advanceAllowed=false 时 pendingBlockingCodes 点名待完成阻断动作。 */
public record AdvanceGateView(String nextSubStageCode, Boolean advanceAllowed,
                              List<String> pendingBlockingCodes) {
}
