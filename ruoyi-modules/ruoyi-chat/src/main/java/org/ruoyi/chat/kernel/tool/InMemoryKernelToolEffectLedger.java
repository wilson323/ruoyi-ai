package org.ruoyi.chat.kernel.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.ruoyi.service.coding.harness.model.HarnessToolEffect;

/**
 * 进程内工具副作用账本（{@code effectId → HarnessToolEffect}，与自研
 * {@code HarnessRunState.toolEffects} 同构）。
 *
 * <p>持久化后端（HarnessStore / 崩溃恢复 Reconciler 对账）为后续波次接线点，
 * 本实现先提供恰一写/就地结算的账本语义与计数缝（W3 出条件 ②⑤）。
 */
public final class InMemoryKernelToolEffectLedger implements KernelToolEffectLedger {

    private final Map<String, HarnessToolEffect> effects = new LinkedHashMap<>();
    private final AtomicInteger writeCount = new AtomicInteger();

    @Override
    public synchronized HarnessToolEffect append(HarnessToolEffect intent) {
        Objects.requireNonNull(intent, "intent");
        if (effects.containsKey(intent.effectId())) {
            throw new IllegalStateException("duplicate tool effect id: " + intent.effectId());
        }
        effects.put(intent.effectId(), intent);
        writeCount.incrementAndGet();
        return intent;
    }

    @Override
    public synchronized HarnessToolEffect settle(HarnessToolEffect settled) {
        Objects.requireNonNull(settled, "settled");
        if (!effects.containsKey(settled.effectId())) {
            throw new IllegalStateException("unknown tool effect id: " + settled.effectId());
        }
        effects.put(settled.effectId(), settled);
        return settled;
    }

    @Override
    public synchronized Optional<HarnessToolEffect> find(String effectId) {
        return Optional.ofNullable(effects.get(effectId));
    }

    @Override
    public synchronized List<HarnessToolEffect> entries() {
        return new ArrayList<>(effects.values());
    }

    @Override
    public int writeCount() {
        return writeCount.get();
    }
}
