package org.ruoyi.chat.kernel.tool;

import java.util.List;
import java.util.Optional;
import org.ruoyi.service.coding.harness.model.HarnessToolEffect;

/**
 * 工具副作用账本（W3：账本包工具执行外围；矩阵 #14「保留」——任何波次不得删除）。
 *
 * <p>语义沿用自研 {@link HarnessToolEffect} 生命周期（写前意图 = PENDING 标记，
 * 防止不确定副作用被盲目重放）：
 * <ul>
 *   <li>{@link #append}：写前意图，**每次工具调用恰一次**（W3 出条件 ②「恰一个账本写」）；</li>
 *   <li>{@link #settle}：同一 {@code effectId} 条目就地结算（状态转移，非新账本写）——
 *       成功 {@code commit+settle}、失败 {@code abandon}（不可重放安全的不确定态
 *       保持 PENDING 待仲裁，禁止机械重试）。</li>
 * </ul>
 */
public interface KernelToolEffectLedger {

    /** 写前意图追加（恰一账本写）。 */
    HarnessToolEffect append(HarnessToolEffect intent);

    /** 同条目就地结算（effectId 不变；计数不增）。 */
    HarnessToolEffect settle(HarnessToolEffect settled);

    Optional<HarnessToolEffect> find(String effectId);

    /** 全部账本条目（时序）。 */
    List<HarnessToolEffect> entries();

    /** 账本写计数（append 次数；恰一断言缝）。 */
    int writeCount();
}
