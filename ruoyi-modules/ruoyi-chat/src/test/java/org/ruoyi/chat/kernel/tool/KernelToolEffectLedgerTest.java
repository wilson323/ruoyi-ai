package org.ruoyi.chat.kernel.tool;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.coding.harness.model.HarnessToolEffect;
import org.ruoyi.service.coding.harness.model.HarnessToolEffectStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具副作用账本生命周期单测（W3 出条件 ②⑤：恰一账本写 + 就地结算不增写）。 */
@Tag("dev")
@DisplayName("W3 工具副作用账本：恰一写 + 就地结算")
class KernelToolEffectLedgerTest {

    private static final String SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    @DisplayName("写前意图恰一次追加；结算为同条目就地状态转移（账本写计数不增）")
    void appendOnceAndSettleInPlace() {
        InMemoryKernelToolEffectLedger ledger = new InMemoryKernelToolEffectLedger();
        HarnessToolEffect intent = HarnessToolEffect.pending("c1", "note_read", SHA, true, 1L);

        ledger.append(intent);
        assertEquals(1, ledger.writeCount(), "每次工具调用恰一个账本写");

        HarnessToolEffect settled = intent.commit("receipt", 2L).settle("tool-result:c1", 3L);
        ledger.settle(settled);

        assertEquals(1, ledger.writeCount(), "就地结算不得新增账本写");
        assertEquals(1, ledger.entries().size(), "同一调用恰一个账本条目");
        assertEquals(HarnessToolEffectStatus.SETTLED,
                ledger.find(intent.effectId()).orElseThrow().status());
    }

    @Test
    @DisplayName("拒绝态写前意图直接以 ABANDONED 落账（结构性无副作用留痕）")
    void deniedIntentIsAbandoned() {
        InMemoryKernelToolEffectLedger ledger = new InMemoryKernelToolEffectLedger();
        HarnessToolEffect intent = HarnessToolEffect
                .pending("c2", "note_save", SHA, true, 1L)
                .abandon("denied before execution", 2L);

        ledger.append(intent);

        assertEquals(1, ledger.writeCount());
        assertEquals(HarnessToolEffectStatus.ABANDONED,
                ledger.find(intent.effectId()).orElseThrow().status());
    }

    @Test
    @DisplayName("不可重放安全的不确定副作用禁止 abandon（挂起待仲裁，禁止机械重试）")
    void uncertainEffectMustNotBeAbandoned() {
        HarnessToolEffect intent = HarnessToolEffect.pending("c3", "note_save", SHA, false, 1L);
        assertTrue(intent.requiresOperatorAdjudication(), "PENDING + !replaySafe = 不确定态待仲裁");
        assertThrows(IllegalStateException.class,
                () -> intent.abandon("should not happen", 2L));
    }

    @Test
    @DisplayName("effectId 重复追加被拒（防账本写重复）")
    void duplicateEffectIdRejected() {
        InMemoryKernelToolEffectLedger ledger = new InMemoryKernelToolEffectLedger();
        HarnessToolEffect intent = HarnessToolEffect.pending("c4", "t", SHA, true, 1L);
        ledger.append(intent);
        assertThrows(IllegalStateException.class, () -> ledger.append(intent));
    }

    @Test
    @DisplayName("结算未知 effectId 被拒（账本条目不可凭空补）")
    void settleUnknownEffectRejected() {
        InMemoryKernelToolEffectLedger ledger = new InMemoryKernelToolEffectLedger();
        HarnessToolEffect orphan = HarnessToolEffect.pending("c5", "t", SHA, true, 1L);
        assertThrows(IllegalStateException.class, () -> ledger.settle(orphan));
        assertEquals(List.of(), ledger.entries());
    }
}
