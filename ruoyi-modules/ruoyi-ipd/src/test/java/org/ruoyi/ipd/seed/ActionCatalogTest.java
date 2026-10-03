package org.ruoyi.ipd.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.ActionDef;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 67 动作目录一致性断言（动作清单 v3 + 文首「v4 退役标注」）：
 * 总数 67 = 深管 40 / 轻管 27；阻断 36 / 非阻断 31；
 * 阶段分布 12/13/11/12/8/7/4；Gate 关联 5；编码唯一；B 级阻断集 10 项。
 * （v3 原文口径 69 = 概念 12/计划 13/开发 11/验证 12/发布 8/生命周期 9/KPI 归集 4；
 *  LC01 / LC03 已于 2026-10-03 随回款台账 / 奖金池退役，生命周期 9 → 7）
 */
@Tag("dev")
class ActionCatalogTest {

    @Test
    @DisplayName("总数 67 = 深管 40 / 轻管 27；阻断 36 / 非阻断 31")
    void totals() {
        assertThat(ActionCatalog.ALL).hasSize(67);
        assertThat(ActionCatalog.ALL.stream().filter(a -> "DEEP".equals(a.depth())).count()).isEqualTo(40);
        assertThat(ActionCatalog.ALL.stream().filter(a -> "LIGHT".equals(a.depth())).count()).isEqualTo(27);
        assertThat(ActionCatalog.ALL.stream().filter(ActionDef::blocking).count()).isEqualTo(36);
        assertThat(ActionCatalog.ALL.stream().filter(a -> !a.blocking()).count()).isEqualTo(31);
    }

    @Test
    @DisplayName("阶段分布 12/13/11/12/8/7/4（LC 含 K4）")
    void stageDistribution() {
        Map<String, Long> byStage = ActionCatalog.ALL.stream()
            .collect(Collectors.groupingBy(ActionDef::stage, Collectors.counting()));
        assertThat(byStage.get("CONCEPT")).isEqualTo(12);
        assertThat(byStage.get("PLAN")).isEqualTo(13);
        assertThat(byStage.get("DEV")).isEqualTo(11);
        assertThat(byStage.get("VALID")).isEqualTo(12);
        assertThat(byStage.get("LAUNCH")).isEqualTo(8);
        assertThat(byStage.get("LIFECYCLE")).isEqualTo(11); // 7 LC + 4 KPI 归集
    }

    @Test
    @DisplayName("编码唯一 + 前缀合法 + 主责/深度白名单")
    void codesAndEnums() {
        Set<String> codes = ActionCatalog.ALL.stream().map(ActionDef::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(67);
        for (ActionDef a : ActionCatalog.ALL) {
            assertThat(a.code()).matches("[A-Z]{1,2}[0-9]{2}"); // C01 1字母 / LC01 2字母
            assertThat(Set.of("MARKET_PM", "RD_PM", "BOTH", "GROUP_LEADER")).contains(a.ownerRole());
            assertThat(Set.of("DEEP", "LIGHT")).contains(a.depth());
            assertThat(Set.of("ALL", "HW", "SW", "SOL", "OVERSEAS", "BIOCV")).contains(a.applicable());
        }
    }

    @Test
    @DisplayName("Gate 关联恰好 5 项：C11-G1 / P13-G2 / D05-G3 / L07-G4 / LC02-G5")
    void gateBinding() {
        List<ActionDef> gated = ActionCatalog.ALL.stream()
            .filter(a -> !a.gate().isEmpty()).toList();
        assertThat(gated).extracting(ActionDef::code)
            .containsExactlyInAnyOrder("C11", "P13", "D05", "L07", "LC02");
        assertThat(gated).extracting(ActionDef::gate)
            .containsExactlyInAnyOrder("G1", "G2", "G3", "G4", "G5");
        // 五 Gate 均为阻断深管评审动作
        assertThat(gated).allMatch(a -> a.blocking() && "DEEP".equals(a.depth()));
    }

    @Test
    @DisplayName("B 级阻断集 = 10 项（Gate 7 项必做 + P10/V02 认证 + C12 生物特征）")
    void bLevelSet() {
        assertThat(ActionCatalog.B_LEVEL_BLOCKING_CODES).hasSize(10);
        for (String code : ActionCatalog.B_LEVEL_BLOCKING_CODES) {
            assertThat(ActionCatalog.byCode(code)).as("B 级动作存在: " + code).isNotNull();
        }
        // C12 全等级阻断（法律红线）；P10/V02 轻管但保留阻断性
        assertThat(ActionCatalog.byCode("C12").blocking()).isTrue();
        assertThat(ActionCatalog.byCode("P10").depth()).isEqualTo("LIGHT");
        assertThat(ActionCatalog.byCode("P10").blocking()).isTrue();
        assertThat(ActionCatalog.byCode("V02").blocking()).isTrue();
        // 例外三：D11 轻管但必登记 FAR/FRR 数值
        assertThat(ActionCatalog.byCode("D11").valueFields()).isEqualTo("FAR,FRR");
        // 例外二：L08 录入上市日期（BR-IPD-08 起算原点）
        assertThat(ActionCatalog.byCode("L08").valueFields()).isEqualTo("LAUNCH_DATE");
    }

    @Test
    @DisplayName("V11 条件阻断语义：默认非阻断，解决方案模板下升级（P1-4/P1-5 消费）")
    void v11Conditional() {
        ActionDef v11 = ActionCatalog.byCode("V11");
        assertThat(v11.depth()).isEqualTo("LIGHT");
        assertThat(v11.applicable()).isEqualTo("SOL");
        assertThat(v11.blocking()).isFalse();
    }
    /**
     * 回归：省略 {@code actionCode} 的创建请求曾在生产 500。
     *
     * <p>链路：{@code ProjectAgentRunService#loadProjectFacts → ActionCatalog.docTypeOf(null)
     * → resolveCode(null) → ALIASES.getOrDefault(null, null)}。
     * {@code ALIASES} 由 {@code Map.of} 构造，其 {@code MapN.probe} 会对 null 调
     * {@code hashCode()} 抛 NPE；而 {@code actionCode} 在 {@code AgentRunCreateReq} 契约上可空。
     *
     * <p>2026-10-02 22:43 真人员旅程验收实测：不带 actionCode → {@code code=90001}；
     * 带 actionCode=C01 → {@code code=0} 且 run SUCCEEDED。
     */
    @Test
    @DisplayName("空动作码：resolveCode/docTypeOf 返回 null 而非 NPE（省略 actionCode 的创建请求 500 回归）")
    void nullActionCodeIsNotAnNpe() {
        assertThat(ActionCatalog.resolveCode(null)).isNull();
        assertThat(ActionCatalog.docTypeOf(null)).isNull();
        // 非空路径不得回退：别名归一与 docType 归类都保持原语义
        assertThat(ActionCatalog.resolveCode("Z01")).isEqualTo("D11");
        assertThat(ActionCatalog.resolveCode("C01")).isEqualTo("C01");
        assertThat(ActionCatalog.docTypeOf("C01")).isEqualTo("MARKET_RESEARCH");
    }

    @Test
    @DisplayName("Z 系别名归一：Z01-05 解析为 D11/V10/C12/V11/V12（主 Prompt v3 L513-517）")
    void aliasResolution() {
        assertThat(ActionCatalog.ALIASES).hasSize(5);
        assertThat(ActionCatalog.resolveCode("Z01")).isEqualTo("D11");
        assertThat(ActionCatalog.resolveCode("Z03")).isEqualTo("C12");
        assertThat(ActionCatalog.resolveCode("C01")).isEqualTo("C01"); // 非别名原样
        // 别名对齐目标动作均在目录内且特性一致：D11 须登记 FAR/FRR
        assertThat(ActionCatalog.byCode("D11").valueFields()).contains("FAR", "FRR");
    }
}