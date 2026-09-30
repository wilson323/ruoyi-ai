package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图收紧：选择型「还是」才澄清；已绑定动作的步骤只来自技能「## 步骤」。
 */
@Tag("dev")
class ProjectAgentIntentTest {

    private static final List<String> COMPETITOR_STEPS = List.of(
        "检索「竞品」「价格」「渠道」「技术方案」；无竞品名单则停止比较，只出缺项。",
        "**目的裁剪**（用户声明时）：产品设计侧重功能矩阵；战略侧重格局与壁垒线索；融资材料侧重差异一页纸。未声明则四维齐全、篇幅克制。",
        "对每个竞品填四维：功能、价格（币种/时间）、渠道、技术路线；每格附来源。",
        "**可信度**：公开规格/报价页摘录=高；转述无出处=低（不得当已验证事实）。标注信息时效（资料中的日期）。",
        "差异化机会仅基于有来源事实；推断标「推断」。可选：追平/差异/创新三层建议，须挂证据行。",
        "输出「资料核对 / 对比表 / 差异机会 / 冲突与待确认 / 缺项清单」。不凑默认「5 家」。"
    );

    @Test
    @DisplayName("负例：我还是要做…不是未选定方向")
    void stillDoingIsNotAChoice() {
        ProjectAgentIntent.Decision decision = ProjectAgentIntent.decide(
            "我还是要做竞品的功能和价格", "C02", List.of(skillMarkdown()));

        assertThat(decision.needsClarification()).isFalse();
        assertThat(decision.needsPlan()).isTrue();
        assertThat(decision.steps()).isEqualTo(COMPETITOR_STEPS);
        assertThat(decision.steps()).noneMatch(step -> step.contains("核对功能"));
    }

    @Test
    @DisplayName("正例：选择型「还是」两侧带进问题，不展开计划")
    void alternativeNeedsClarification() {
        ProjectAgentIntent.Decision decision = ProjectAgentIntent.decide(
            "做功能对比还是做价格对比", "C02", List.of(skillMarkdown()));

        assertThat(decision.needsClarification()).isTrue();
        assertThat(decision.needsPlan()).isFalse();
        assertThat(decision.steps()).isEmpty();
        assertThat(decision.questions()).containsExactly(
            "这句话里有未选定的方向：「做功能对比」还是「做价格对比」。请指定其中一个后再执行。");
        assertThat(ProjectAgentIntent.prompt(decision)).contains("不要调用工具");
    }

    @Test
    @DisplayName("绑定 C02 时步骤等于竞品技能步骤节，提示词不得增删改序")
    void boundActionUsesSkillSectionVerbatim() {
        String markdown = skillMarkdown();
        assertThat(ProjectAgentIntent.orderedSteps(markdown)).containsExactlyElementsOf(COMPETITOR_STEPS);

        ProjectAgentIntent.Decision decision = ProjectAgentIntent.decide(
            "请对本项目做竞品分析：功能、价格、渠道、技术路线", "C02", List.of(markdown));

        assertThat(decision.steps()).containsExactlyElementsOf(COMPETITOR_STEPS);
        String prompt = ProjectAgentIntent.prompt(decision);
        assertThat(prompt).contains("不得增删改序").contains(COMPETITOR_STEPS.get(0));
        assertThat(prompt).doesNotContain("核对功能");
    }

    @Test
    @DisplayName("套话即使已绑定动作也只澄清；短句不再按字数当含糊")
    void vaguePhraseClarifiesButShortRequestDoesNot() {
        ProjectAgentIntent.Decision hello = ProjectAgentIntent.decide("你好", "C02", List.of(skillMarkdown()));
        assertThat(hello.needsClarification()).isTrue();
        assertThat(hello.questions()).contains("请说明要交付的结果，以及范围限定在本项目的哪一块。");
        assertThat(hello.questions()).anyMatch(q -> q.contains("动作 C02"));

        ProjectAgentIntent.Decision brief = ProjectAgentIntent.decide("出报告", "C02", List.of(skillMarkdown()));
        assertThat(brief.needsClarification()).isFalse();
        assertThat(brief.steps()).containsExactlyElementsOf(COMPETITOR_STEPS);
    }

    @Test
    @DisplayName("无动作且两句都含动词才自由计划，不补核对面")
    void freePlanUsesVerbClausesOnly() {
        ProjectAgentIntent.Decision planned = ProjectAgentIntent.decide(
            "先分析功能。再对比价格。", null, List.of());
        assertThat(planned.needsPlan()).isTrue();
        assertThat(planned.needsClarification()).isFalse();
        assertThat(planned.steps()).containsExactly("先分析功能", "再对比价格");
        assertThat(planned.steps()).noneMatch(step -> step.contains("核对"));

        ProjectAgentIntent.Decision one = ProjectAgentIntent.decide(
            "请分析竞品的功能和价格", null, List.of());
        assertThat(one.needsPlan()).isFalse();
        assertThat(one.steps()).isEmpty();
    }

    @Test
    @DisplayName("已确认计划：步骤逐字等于各行，不切句、不补核对、不因还是再澄清")
    void confirmedPlanKeepsCopiedLines() {
        String message = """
            按已确认计划执行

            先分析功能、再对比价格
            做功能对比还是做价格对比
            """;
        ProjectAgentIntent.Decision decision = ProjectAgentIntent.decide(message, null, List.of());

        assertThat(decision.needsClarification()).isFalse();
        assertThat(decision.needsPlan()).isTrue();
        assertThat(decision.steps()).containsExactly(
            "先分析功能、再对比价格",
            "做功能对比还是做价格对比");
        assertThat(decision.steps()).noneMatch(step -> step.contains("核对"));
        assertThat(ProjectAgentIntent.prompt(decision)).contains("先分析功能、再对比价格");
    }

    @Test
    @DisplayName("只有确认开头：步骤为空，仍记为需要计划")
    void confirmedHeaderWithoutStepsStaysEmpty() {
        ProjectAgentIntent.Decision header = ProjectAgentIntent.decide("按已确认计划执行", null, List.of());
        assertThat(header.needsClarification()).isFalse();
        assertThat(header.needsPlan()).isTrue();
        assertThat(header.steps()).isEmpty();

        ProjectAgentIntent.Decision blanks = ProjectAgentIntent.decide("按已确认计划执行\n  \n", null, List.of());
        assertThat(blanks.steps()).isEmpty();
        assertThat(blanks.needsPlan()).isTrue();
    }

    @Test
    @DisplayName("已绑定动作不把确认正文当成步骤，仍用技能步骤节")
    void boundActionIgnoresConfirmationCopy() {
        ProjectAgentIntent.Decision decision = ProjectAgentIntent.decide(
            "按已确认计划执行\n先分析功能\n再对比价格", "C02", List.of(skillMarkdown()));

        assertThat(decision.needsClarification()).isFalse();
        assertThat(decision.steps()).containsExactlyElementsOf(COMPETITOR_STEPS);
        assertThat(decision.steps()).noneMatch(step -> step.equals("先分析功能"));
    }

    @Test
    @DisplayName("有动作但技能没有步骤节：一条合成句，仍不切用户原文")
    void missingSectionFallsBackToOneStep() {
        ProjectAgentIntent.Decision decision = ProjectAgentIntent.decide(
            "请分析功能和价格。再对比渠道。", "C02", List.of("没有步骤标题"));

        assertThat(decision.needsPlan()).isTrue();
        assertThat(decision.steps()).containsExactly("按动作 C02 与已加载技能执行，不另列计划。");
    }

    private static String skillMarkdown() {
        try (InputStream in = ProjectAgentIntentTest.class.getClassLoader()
            .getResourceAsStream("ipd-skills/competitor-analysis-ipd/SKILL.md")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
