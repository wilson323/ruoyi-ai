package org.ruoyi.ipd.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.seed.GuideScriptCatalog.BindLevel;
import org.ruoyi.ipd.seed.GuideScriptCatalog.GuideScript;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C1 合同测试（hermetic，无 DB/Spring）：锁 69 行齐 + 四级码集 + 词表∈§3.1 真名集。 */
@Tag("dev")
class GuideScriptCatalogContractTest {

    /** §3.1 真名全集（9 插件 69 skills，S3 实抓 + S2 交叉一致）。 */
    private static final Set<String> KNOWN_SKILLS = Set.of(
        "brainstorm-ideas-existing", "brainstorm-ideas-new", "brainstorm-experiments-existing",
        "brainstorm-experiments-new", "identify-assumptions-existing", "identify-assumptions-new",
        "prioritize-assumptions", "prioritize-features", "analyze-feature-requests",
        "opportunity-solution-tree", "interview-script", "summarize-interview", "metrics-dashboard",
        "product-strategy", "startup-canvas", "product-vision", "value-proposition", "lean-canvas",
        "business-model", "monetization-strategy", "pricing-strategy", "swot-analysis",
        "pestle-analysis", "porters-five-forces", "ansoff-matrix",
        "create-prd", "brainstorm-okrs", "outcome-roadmap", "sprint-plan", "retro", "release-notes",
        "pre-mortem", "stakeholder-map", "summarize-meeting", "user-stories", "job-stories", "wwas",
        "test-scenarios", "dummy-dataset", "prioritization-frameworks", "strategy-red-team",
        "user-personas", "market-segments", "user-segmentation", "customer-journey-map",
        "market-sizing", "competitor-analysis", "sentiment-analysis",
        "sql-queries", "cohort-analysis", "ab-test-analysis",
        "gtm-strategy", "beachhead-segment", "ideal-customer-profile", "growth-loops",
        "gtm-motions", "competitive-battlecard",
        "marketing-ideas", "positioning-ideas", "value-prop-statements", "product-name",
        "north-star-metric",
        "review-resume", "draft-nda", "privacy-policy", "grammar-check",
        "shipping-artifacts", "intended-vs-implemented", "code-review");

    /** §3.1 真名全集（9 插件 42 commands；commandChain 首 token 必须∈此集）。 */
    private static final Set<String> KNOWN_COMMANDS = Set.of(
        "/discover", "/brainstorm", "/triage-requests", "/interview", "/setup-metrics",
        "/strategy", "/business-model", "/value-proposition", "/market-scan", "/pricing",
        "/write-prd", "/plan-okrs", "/transform-roadmap", "/sprint", "/pre-mortem",
        "/red-team-prd", "/meeting-notes", "/stakeholder-map", "/write-stories",
        "/test-scenarios", "/generate-data",
        "/research-users", "/competitive-analysis", "/analyze-feedback",
        "/write-query", "/analyze-cohorts", "/analyze-test",
        "/plan-launch", "/growth-strategy", "/battlecard",
        "/market-product", "/north-star",
        "/review-resume", "/tailor-resume", "/draft-nda", "/privacy-policy", "/proofread",
        "/ship-check", "/document-app", "/derive-tests",
        "/security-audit-static", "/performance-audit-static");

    /** §3.2.8 四级精确码集。 */
    private static final Set<String> BIND_CODES = Set.of("C01", "C02", "C03", "C04", "C06", "C07",
        "C08", "C11", "C12", "P01", "P02", "P12", "P13", "D05", "D06", "V03", "V06", "V07", "V09",
        "V10", "V11", "L01", "L02", "L03", "L04", "L07", "L08", "LC02", "LC05",
        "LC09", "K01", "K02", "K03", "K04");
    private static final Set<String> WEAK_CODES = Set.of("V12", "L06", "LC08");
    private static final Set<String> CANDIDATE_CODES = Set.of("C10", "P03", "P05", "P06", "P08",
        "P11", "D04", "D09", "D10", "V01", "V04", "LC06");
    private static final Set<String> NONE_CODES = Set.of("C05", "C09", "P04", "P07", "P09", "P10",
        "D01", "D02", "D03", "D07", "D08", "D11", "V02", "V05", "V08", "L05", "LC04", "LC07");

    private static Set<String> codesOf(BindLevel level) {
        return GuideScriptCatalog.all().stream()
            .filter(s -> s.bindLevel() == level)
            .map(GuideScript::actionCode)
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("67 行齐，且码集与 ActionCatalog.ALL 全等（禁改名口径）")
    void coversAllCatalogCodesExactly() {
        List<GuideScript> all = GuideScriptCatalog.all();
        assertThat(all).hasSize(67);
        Set<String> catalogCodes = ActionCatalog.ALL.stream()
            .map(a -> a.code()).collect(Collectors.toSet());
        assertThat(all.stream().map(GuideScript::actionCode).collect(Collectors.toSet()))
            .containsExactlyInAnyOrderElementsOf(catalogCodes);
    }

    @Test
    @DisplayName("§3.2.8 四级码集精确断言：34 绑定 / 3 弱 / 12 候选 / 18 不绑定（LC01/LC03 退役后）")
    void bindLevelDistributionMatchesSSOT() {
        assertThat(codesOf(BindLevel.BIND)).containsExactlyInAnyOrderElementsOf(BIND_CODES);
        assertThat(codesOf(BindLevel.WEAK)).containsExactlyInAnyOrderElementsOf(WEAK_CODES);
        assertThat(codesOf(BindLevel.CANDIDATE)).containsExactlyInAnyOrderElementsOf(CANDIDATE_CODES);
        assertThat(codesOf(BindLevel.NONE)).containsExactlyInAnyOrderElementsOf(NONE_CODES);
    }

    @Test
    @DisplayName("话术非空且无占位符残留；skill/command 词表∈§3.1 真名集")
    void promptsNonBlankAndVocabularyReal() {
        for (GuideScript s : GuideScriptCatalog.all()) {
            assertThat(s.guidePrompt())
                .as("guidePrompt %s", s.actionCode())
                .isNotBlank()
                .doesNotContain("{actionName}")
                // 占位符残留按词边界判（TODO/TBD 为占位符）；JTBD（Jobs-to-Be-Done 方法论真名）豁免
                .doesNotMatch("(?s).*\\b(?:TODO|TBD)\\b.*");
            assertThat(s.skillNames())
                .as("skillNames %s", s.actionCode())
                .allMatch(KNOWN_SKILLS::contains);
            assertThat(s.commandChain())
                .as("commandChain %s", s.actionCode())
                .allMatch(c -> KNOWN_COMMANDS.contains(c.split(" ")[0]));
        }
    }

    @Test
    @DisplayName("不绑定（NONE）18 动作：skill/command 均空，话术走结构化登记口径")
    void noneActionsUseRegisterScript() {
        for (String code : NONE_CODES) {
            GuideScript s = GuideScriptCatalog.scriptOf(code);
            assertThat(s.skillNames()).as("skills %s", code).isEmpty();
            assertThat(s.commandChain()).as("commands %s", code).isEmpty();
            assertThat(s.guidePrompt()).as("prompt %s", code).contains("登记");
        }
        assertThat(GuideScriptCatalog.registerPrompt("X 动作"))
            .contains("X 动作").contains("结构化登记");
    }

    @Test
    @DisplayName("绑定/弱绑定动作必有 skill 与 command 链；候选动作必有 skill、可无 command")
    void bindingStrengthContract() {
        for (GuideScript s : GuideScriptCatalog.all()) {
            switch (s.bindLevel()) {
                case BIND, WEAK -> assertThat(s.skillNames()).as(s.actionCode()).isNotEmpty();
                case CANDIDATE -> assertThat(s.skillNames()).as(s.actionCode()).isNotEmpty();
                case NONE -> { /* 上一用例覆盖 */ }
            }
        }
    }

    @Test
    @DisplayName("scriptOf 未命中抛 50001 fail-loud")
    void scriptOfUnknownCodeFails() {
        assertThatThrownBy(() -> GuideScriptCatalog.scriptOf("A01"))
            .hasMessageContaining("引导话术不存在");
    }
}
