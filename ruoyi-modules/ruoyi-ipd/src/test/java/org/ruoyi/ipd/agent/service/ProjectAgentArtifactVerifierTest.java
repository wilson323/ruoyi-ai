package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.service.ProjectAgentArtifactVerifier.Rule;
import org.ruoyi.ipd.agent.service.ProjectAgentArtifactVerifier.Severity;
import org.ruoyi.ipd.agent.service.ProjectAgentArtifactVerifier.Verdict;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 产物机器校验器：通用规则判定、占位阈值、WARN 不拦截、动作规则空表、空正文安全。
 */
@Tag("dev")
class ProjectAgentArtifactVerifierTest {

    private final ProjectAgentArtifactVerifier verifier = new ProjectAgentArtifactVerifier();

    private static final String CLEAN_DOC = "# 竞品分析报告\n\n## 结论\n定位与差距摘要。";

    @Test
    @DisplayName("结构完整且无占位的产物判 PASS")
    void cleanDocumentPasses() {
        Verdict verdict = verifier.evaluate("C02", CLEAN_DOC);
        assertThat(verdict.verdict()).isEqualTo(Verdict.PASS);
        assertThat(verdict.gaps()).isEmpty();
        assertThat(verdict.hasBlockingGaps()).isFalse();
    }

    @Test
    @DisplayName("无标题判 BLOCK 缺口（STRUCTURAL）")
    void missingHeadingIsBlocking() {
        Verdict verdict = verifier.evaluate("C02", "只有正文没有标题的半成品。");
        assertThat(verdict.verdict()).isEqualTo(Verdict.GAPS);
        assertThat(verdict.gaps()).singleElement()
            .satisfies(gap -> {
                assertThat(gap.id()).isEqualTo("doc.heading.structure");
                assertThat(gap.severity()).isEqualTo(Severity.BLOCK);
            });
        assertThat(verdict.hasBlockingGaps()).isTrue();
    }

    @Test
    @DisplayName("占位标记阈值：单处合法提及不拦截，两处即 BLOCK")
    void placeholderThresholdIsTwoOccurrences() {
        assertThat(verifier.evaluate("C02", "# 标题\n正文提及 TODO 一次，属合法提及。").hasBlockingGaps())
            .as("单处占位不拦截").isFalse();
        assertThat(verifier.evaluate("C02", "# 标题\nTODO 待补充，TODO 再补。").hasBlockingGaps())
            .as("两处占位拦截").isTrue();
    }

    @Test
    @DisplayName("WARN 级缺口只披露不拦截：hasBlockingGaps 为 false")
    void warnGapsDoNotBlock() {
        java.util.function.Predicate<String> alwaysFail = body -> false;
        Verdict verdict = ProjectAgentArtifactVerifier.evaluateWithRules("# 标题\n正文。",
            java.util.List.of(new Rule("doc.warn.sample", "CONTENT", Severity.WARN, "演示 WARN 规则", alwaysFail)));
        assertThat(verdict.verdict()).isEqualTo(Verdict.GAPS);
        assertThat(verdict.gaps()).singleElement()
            .satisfies(gap -> assertThat(gap.severity()).isEqualTo(Severity.WARN));
        assertThat(verdict.hasBlockingGaps()).isFalse();
        assertThat(verdict.summary()).isEqualTo("共 1 项缺口");
    }

    @Test
    @DisplayName("动作级规则表首批为空：任意动作码与空码规则集一致（恰两条通用规则）")
    void actionRulesEmptyByDesign() {
        assertThat(ProjectAgentArtifactVerifier.rulesFor("C02"))
            .isEqualTo(ProjectAgentArtifactVerifier.rulesFor(null));
        assertThat(ProjectAgentArtifactVerifier.rulesFor("C02")).hasSize(2);
    }

    @Test
    @DisplayName("空/null 正文安全判缺口，不抛异常")
    void nullContentIsGapNotCrash() {
        assertThat(verifier.evaluate("C02", null).hasBlockingGaps()).isTrue();
        assertThat(verifier.evaluate("C02", "").hasBlockingGaps()).isTrue();
    }
}
