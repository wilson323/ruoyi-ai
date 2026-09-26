package org.ruoyi.ipd.seed;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.ActionDef;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R221 矩阵哨兵（spec §2.1 断言 + 统计棘轮）。
 * 矩阵权威 = IPD全阶段AI代理执行闭环设计-20260926.md 附录 A（H5/D40/G24）。
 */
@Tag("dev")
class ActionExecModeMatrixSentinelTest {

    private static final Set<String> EXEC_MODES = Set.of("AI_DIRECT", "AI_GENERATE", "HUMAN_GATE");

    @Test
    void everyActionHasLegalExecMode() {
        for (ActionDef d : ActionCatalog.ALL) {
            assertThat(EXEC_MODES).as("动作 %s execMode 非法: %s", d.code(), d.execMode()).contains(d.execMode());
        }
    }

    @Test
    void gateActionMustBeHumanGate() {
        for (ActionDef d : ActionCatalog.ALL) {
            if (d.gate() != null && !d.gate().isBlank()) {
                assertThat(d.execMode()).as("gate 动作 %s 必须 HUMAN_GATE", d.code()).isEqualTo("HUMAN_GATE");
            }
        }
    }

    @Test
    void matrixCountsMatchApprovedSpec() {
        long h = ActionCatalog.ALL.stream().filter(d -> "HUMAN_GATE".equals(d.execMode())).count();
        long direct = ActionCatalog.ALL.stream().filter(d -> "AI_DIRECT".equals(d.execMode())).count();
        long gen = ActionCatalog.ALL.stream().filter(d -> "AI_GENERATE".equals(d.execMode())).count();
        assertThat(ActionCatalog.ALL).hasSize(69);
        assertThat(h).as("HUMAN_GATE 数须与附录 A 定案一致").isEqualTo(5);
        assertThat(direct).isEqualTo(40);
        assertThat(gen).isEqualTo(24);
    }

    @Test
    void humanGateCodesExactlyFive() {
        List<String> h = ActionCatalog.ALL.stream().filter(d -> "HUMAN_GATE".equals(d.execMode()))
            .map(ActionDef::code).sorted().toList();
        assertThat(h).containsExactly("C11", "D05", "L07", "LC02", "P13");
    }
}
