package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R221 接线对账哨兵（spec §2.1 第三条）：防「矩阵登记了但没人实现」假绿。
 * 豁免名单 = 分批接线期未接码，只减不增（棘轮）；清零后删豁免集。
 */
@Tag("dev")
class ExecutorCoverageSentinelTest {

    /** R232-W14 批次：首切片 4 码 + 对账执行器 K01-K04，与 5 执行器 supportedActionCodes 并集对账 */
    private static final Set<String> WIRED = Set.of("C01", "C08", "P08", "C11",
        "K01", "K02", "K03", "K04");

    /** 豁免：全部 69 码减去已接线 8 码（接线一批删一批，禁止新增） */
    private static final Set<String> EXEMPT = ActionCatalog.ALL.stream()
        .map(ActionDef::code)
        .filter(c -> !WIRED.contains(c))
        .collect(Collectors.toUnmodifiableSet());

    @Test
    void wiredCodesMatchExecutorsUnion() {
        // 构造签名以磁盘现态为准（复审问题4 波后：GatePrep 6 参——+StageActionService 终态守卫）
        Set<String> union = java.util.stream.Stream.of(
                new LightDirectExecutor(null).supportedActionCodes(),
                new DeepDirectExecutor(null, null, null).supportedActionCodes(),
                new GenerateExecutor(null, null, null, null).supportedActionCodes(),
                new GatePrepExecutor(null, null, null, null, null, null).supportedActionCodes(),
                new KpiSharedReconcileExecutor(null, null, null, null).supportedActionCodes())
            .flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
        assertThat(union).isEqualTo(WIRED);
    }

    /** 遗留 MINOR#2 行为锁（真实现非 mock）：可自动派发集 = AI 档 ∧ 已接线 ∧ supportsSchedule
     * → C08（填表族）/C11（HUMAN_GATE）/K01-K04（对账 supportsSchedule=false，防调度每日堆台账）被排除，只剩 C01/P08 */
    @Test
    void scheduleWiredCodesExcludeFillTableAndHumanGate() {
        org.ruoyi.ipd.service.AiExecutionEngine engine = new org.ruoyi.ipd.service.AiExecutionEngine(
            null,
            List.of(new LightDirectExecutor(null), new DeepDirectExecutor(null, null, null),
                new GenerateExecutor(null, null, null, null),
                new GatePrepExecutor(null, null, null, null, null, null),
                new KpiSharedReconcileExecutor(null, null, null, null)),
            null, null);
        assertThat(engine.wiredActionCodes()).containsExactlyInAnyOrder(
            "C01", "C08", "P08", "C11", "K01", "K02", "K03", "K04");
        assertThat(engine.scheduleWiredActionCodes()).containsExactlyInAnyOrder("C01", "P08");
    }

    @Test
    void everyAiActionIsWiredOrExplicitlyExempt() {
        List<String> uncovered = ActionCatalog.ALL.stream()
            .filter(d -> !"HUMAN_GATE".equals(d.execMode()))
            .map(ActionDef::code)
            .filter(c -> !WIRED.contains(c) && !EXEMPT.contains(c))
            .toList();
        assertThat(uncovered).as("AI 档动作既未接线也未豁免（静默漏）").isEmpty();
    }

    @Test
    void exemptionRatchetOnlyShrinks() {
        // 棘轮基线：R232-W14 批次后豁免数 = 69 - 8 = 61；接线批次推进时同步递减，禁止回调大
        assertThat(EXEMPT).hasSize(61);
    }
}
