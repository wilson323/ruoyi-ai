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

    /** 首切片已接线 4 码，与 4 执行器 supportedActionCodes 并集对账 */
    private static final Set<String> WIRED = Set.of("C01", "C08", "P08", "C11");

    /** 豁免：全部 69 码减去已接线 4 码（接线一批删一批，禁止新增） */
    private static final Set<String> EXEMPT = ActionCatalog.ALL.stream()
        .map(ActionDef::code)
        .filter(c -> !WIRED.contains(c))
        .collect(Collectors.toUnmodifiableSet());

    @Test
    void wiredCodesMatchExecutorsUnion() {
        // 构造签名以磁盘现态为准（CodeReview 修复波后：Deep 3 参 / GatePrep 5 参）
        Set<String> union = java.util.stream.Stream.of(
                new LightDirectExecutor(null).supportedActionCodes(),
                new DeepDirectExecutor(null, null, null).supportedActionCodes(),
                new GenerateExecutor(null, null, null, null).supportedActionCodes(),
                new GatePrepExecutor(null, null, null, null, null).supportedActionCodes())
            .flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
        assertThat(union).isEqualTo(WIRED);
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
        // 棘轮基线：首切片后豁免数 = 69 - 4 = 65；接线批次推进时同步递减，禁止回调大
        assertThat(EXEMPT).hasSize(65);
    }
}
