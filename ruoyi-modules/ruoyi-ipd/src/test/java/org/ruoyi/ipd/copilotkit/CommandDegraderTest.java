package org.ruoyi.ipd.copilotkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.copilotkit.CommandDegrader.DegradedStep;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class CommandDegraderTest {

    /** §3.1 的 42 command 全集（SPECS 键面锚）。 */
    private static final List<String> ALL_COMMANDS = List.of(
        "/discover", "/brainstorm", "/triage-requests", "/interview", "/setup-metrics",
        "/strategy", "/business-model", "/value-proposition", "/market-scan", "/pricing",
        "/write-prd", "/plan-okrs", "/transform-roadmap", "/sprint", "/pre-mortem",
        "/red-team-prd", "/meeting-notes", "/stakeholder-map", "/write-stories",
        "/test-scenarios", "/generate-data", "/research-users", "/competitive-analysis",
        "/analyze-feedback", "/write-query", "/analyze-cohorts", "/analyze-test",
        "/plan-launch", "/growth-strategy", "/battlecard", "/market-product", "/north-star",
        "/review-resume", "/tailor-resume", "/draft-nda", "/privacy-policy", "/proofread",
        "/ship-check", "/document-app", "/derive-tests",
        "/security-audit-static", "/performance-audit-static");

    @Test
    @DisplayName("42 命令全量可降级：skill 非空、话术非空")
    void allFortyTwoCommandsDegrade() {
        for (String command : ALL_COMMANDS) {
            DegradedStep step = CommandDegrader.degrade(command);
            assertThat(step.skillNames()).as(command).isNotEmpty();
            assertThat(step.stepPrompt()).as(command).isNotBlank();
        }
    }

    @Test
    @DisplayName("参数变体映射：interview/business-model/sprint/write-stories/brainstorm")
    void argumentVariantsMapToSpecificSkills() {
        assertThat(CommandDegrader.degrade("/interview prep").skillNames())
            .containsExactly("interview-script");
        assertThat(CommandDegrader.degrade("/interview summarize").skillNames())
            .containsExactly("summarize-interview");
        assertThat(CommandDegrader.degrade("/business-model lean").skillNames())
            .containsExactly("lean-canvas");
        assertThat(CommandDegrader.degrade("/business-model all").skillNames()).hasSize(4);
        assertThat(CommandDegrader.degrade("/sprint release").skillNames())
            .containsExactly("release-notes");
        assertThat(CommandDegrader.degrade("/write-stories wwa").skillNames())
            .containsExactly("wwas");
        assertThat(CommandDegrader.degrade("/brainstorm ideas existing").skillNames())
            .containsExactly("brainstorm-ideas-existing");
        assertThat(CommandDegrader.degrade("/brainstorm experiments new").skillNames())
            .containsExactly("brainstorm-experiments-new");
    }

    @Test
    @DisplayName("degradeChain：顺序保持、空链→空列表、未知命令 fail-loud 10001")
    void chainPreservesOrderAndFailsLoud() {
        List<DegradedStep> chain = CommandDegrader.degradeChain(
            List.of("/write-prd", "/red-team-prd"));
        assertThat(chain).extracting(DegradedStep::command)
            .containsExactly("/write-prd", "/red-team-prd");
        assertThat(CommandDegrader.degradeChain(List.of())).isEmpty();
        assertThatThrownBy(() -> CommandDegrader.degrade("/no-such-command"))
            .hasMessageContaining("未知 command");
    }
}
