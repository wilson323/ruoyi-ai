package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.service.ProjectAgentArtifactVerifier.Gap;
import org.ruoyi.ipd.agent.service.ProjectAgentArtifactVerifier.Rule;
import org.ruoyi.ipd.agent.service.ProjectAgentArtifactVerifier.Severity;
import org.ruoyi.ipd.agent.service.ProjectAgentArtifactVerifier.Verdict;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import org.ruoyi.ipd.agent.support.InMemoryArtifactVersionStore;
import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 产物机器校验器：通用规则判定、占位阈值、WARN 不拦截、动作级规则表（首批 6 条）
 * 逐条正反例、规则表与测试夹具的完备性、动作码归一与非法码防御。
 *
 * <p>★ 维护契约：动作级规则表每新增一条规则，必须在 {@link #FIXTURES} 补一组正例/反例，
 * 否则 {@link #everyRegisteredActionRuleHasBothFixtureDirections()} 会红。
 * 这是「规则不能被无测试地加进注册表」的机器保证。
 */
@Tag("dev")
class ProjectAgentArtifactVerifierTest {

    private final ProjectAgentArtifactVerifier verifier = new ProjectAgentArtifactVerifier();

    /** 无动作级规则的动作码，用于隔离通用规则判定。 */
    private static final String NEUTRAL_CODE = "C01";

    private static final String CLEAN_DOC = "# 竞品分析报告\n\n## 结论\n定位与差距摘要。";

    // ================= 动作级规则夹具：每条规则一组正例 / 反例 =================
    // 反例一律是正例删掉「被那条规则要求的那一段」，因此反例必须恰好只报该条规则。

    private static final String C02_PASS = """
        # 竞品分析报告

        ## 竞品范围
        主要竞品三家。

        ## 功能对比
        功能矩阵与特性清单。

        ## 价格对比
        定价区间与报价策略。

        ## 渠道对比
        经销与分销渠道不同。

        ## 技术路线
        技术路线差异明显。
        """;

    private static final String C02_FAIL = """
        # 竞品分析报告

        ## 竞品范围
        主要竞品三家。

        ## 功能对比
        功能矩阵与特性清单。

        ## 价格对比
        定价区间与报价策略。

        ## 渠道对比
        经销与分销渠道不同。
        """;

    private static final String C07_PASS = """
        # 成本与定价测算表

        ## 成本测算
        BOM 成本合计 520 元。

        ## 定价方案
        建议定价 899 元。

        ## 毛利测算
        毛利率 42%。
        """;

    private static final String C07_FAIL = """
        # 成本与定价测算表

        ## 成本测算
        BOM 成本合计 520 元。

        ## 定价方案
        建议定价 899 元。
        """;

    private static final String C08_PASS = """
        # 商业计划书 Charter

        ## 销售目标
        上市后 6 个月销售目标：销售额 1200 万元。

        ## 目标渠道数
        目标渠道数 30 家。

        ## NPS 目标
        NPS 目标不低于 45。

        ## 场景数
        目标场景数 8 个。
        """;

    private static final String C08_FAIL = """
        # 商业计划书 Charter

        ## 销售目标
        上市后 6 个月销售目标：销售额 1200 万元。

        ## 目标渠道数
        目标渠道数 30 家。

        ## NPS 目标
        NPS 目标不低于 45。
        """;

    private static final String C09_PASS = """
        # 项目等级评定记录

        ## 项目等级
        本项目评定为 A 级。

        ## 差异化系数
        差异化系数取 1.0。
        """;

    private static final String C09_FAIL = """
        # 项目等级评定记录

        ## 项目等级
        本项目评定为 A 级。
        """;

    private static final String V10_PASS = """
        # 分人群测试报告

        ## 人种维度
        覆盖三个人种的样本。

        ## 年龄维度
        覆盖 18 到 60 岁各年龄段。
        """;

    private static final String V10_FAIL = """
        # 分人群测试报告

        ## 人种维度
        覆盖三个人种的样本。
        """;

    private static final String MINUTES_PASS = """
        # 评审会议纪要

        ## 评审结论
        结论：通过。

        ## 遗留项清单
        遗留项 1 项。

        ## 责任人
        责任人：市场PM。

        ## 关闭期限
        关闭期限：2026-10-31。
        """;

    private static final String MINUTES_FAIL = """
        # 评审会议纪要

        ## 评审结论
        结论：通过。

        ## 责任人
        责任人：市场PM。

        ## 关闭期限
        关闭期限：2026-10-31。
        """;

    /**
     * 一条动作级规则的正例与反例。
     *
     * @param actionCode 触发该规则的动作码
     * @param ruleId 规则标识
     * @param passBody 应通过该规则的完整合格产物
     * @param failBody 应恰好触发该规则的产物
     */
    private record Fixture(String actionCode, String ruleId, String passBody, String failBody) {
    }

    private static final List<Fixture> FIXTURES = List.of(
        new Fixture("C02", "act.C02.dimension.coverage", C02_PASS, C02_FAIL),
        new Fixture("C07", "act.C07.dimension.coverage", C07_PASS, C07_FAIL),
        new Fixture("C08", "act.C08.baseline.coverage", C08_PASS, C08_FAIL),
        new Fixture("C09", "act.C09.grade.coefficient", C09_PASS, C09_FAIL),
        new Fixture("V10", "act.V10.dimension.coverage", V10_PASS, V10_FAIL),
        new Fixture("C11", "act.gate.minutes.triple", MINUTES_PASS, MINUTES_FAIL));

    // ================= 通用规则（原有判定不变） =================

    @Test
    @DisplayName("结构完整且无占位的产物判 PASS")
    void cleanDocumentPasses() {
        Verdict verdict = verifier.evaluate(NEUTRAL_CODE, CLEAN_DOC);
        assertThat(verdict.verdict()).isEqualTo(Verdict.PASS);
        assertThat(verdict.gaps()).isEmpty();
        assertThat(verdict.hasBlockingGaps()).isFalse();
    }

    @Test
    @DisplayName("无标题判 BLOCK 缺口（STRUCTURAL）")
    void missingHeadingIsBlocking() {
        Verdict verdict = verifier.evaluate(NEUTRAL_CODE, "只有正文没有标题的半成品。");
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
        assertThat(verifier.evaluate(NEUTRAL_CODE, "# 标题\n正文提及 TODO 一次，属合法提及。").hasBlockingGaps())
            .as("单处占位不拦截").isFalse();
        assertThat(verifier.evaluate(NEUTRAL_CODE, "# 标题\nTODO 待补充，TODO 再补。").hasBlockingGaps())
            .as("两处占位拦截").isTrue();
    }

    @Test
    @DisplayName("WARN 级缺口只披露不拦截：hasBlockingGaps 为 false")
    void warnGapsDoNotBlock() {
        java.util.function.Predicate<String> alwaysFail = body -> false;
        Verdict verdict = ProjectAgentArtifactVerifier.evaluateWithRules("# 标题\n正文。",
            List.of(new Rule("doc.warn.sample", "CONTENT", Severity.WARN, "演示 WARN 规则", alwaysFail)));
        assertThat(verdict.verdict()).isEqualTo(Verdict.GAPS);
        assertThat(verdict.gaps()).singleElement()
            .satisfies(gap -> assertThat(gap.severity()).isEqualTo(Severity.WARN));
        assertThat(verdict.hasBlockingGaps()).isFalse();
        assertThat(verdict.summary()).isEqualTo("共 1 项缺口");
    }

    @Test
    @DisplayName("空/null 正文安全判缺口，不抛异常")
    void nullContentIsGapNotCrash() {
        assertThat(verifier.evaluate(NEUTRAL_CODE, null).hasBlockingGaps()).isTrue();
        assertThat(verifier.evaluate(NEUTRAL_CODE, "").hasBlockingGaps()).isTrue();
    }

    // ================= 动作级规则表（首批 6 条） =================

    @Test
    @DisplayName("每条动作级规则：正例不报该缺口且整体 PASS，反例恰好只报该缺口")
    void everyRuleHasWorkingPositiveAndNegativeFixture() {
        for (Fixture fixture : FIXTURES) {
            Verdict pass = verifier.evaluate(fixture.actionCode(), fixture.passBody());
            assertThat(pass.verdict())
                .as("%s 正例必须是该动作码的完整合格产物（若新增了规则，请把它的要求补进正例）",
                    fixture.ruleId())
                .isEqualTo(Verdict.PASS);
            assertThat(gapIds(pass)).as("%s 正例不应报该缺口", fixture.ruleId()).doesNotContain(fixture.ruleId());

            Verdict fail = verifier.evaluate(fixture.actionCode(), fixture.failBody());
            assertThat(gapIds(fail))
                .as("%s 反例应恰好只报该条缺口（多报说明反例夹带了别的问题）", fixture.ruleId())
                .containsExactly(fixture.ruleId());
            assertThat(fail.hasBlockingGaps()).as("%s 反例必须可拦截", fixture.ruleId()).isTrue();
        }
    }

    @Test
    @DisplayName("完备性：注册表里每条动作级规则都有正反例夹具，夹具里也没有多余项")
    void everyRegisteredActionRuleHasBothFixtureDirections() {
        Set<String> registered = registeredActionRuleIds();
        Set<String> fixtureRuleIds = new LinkedHashSet<>(FIXTURES.stream().map(Fixture::ruleId).toList());

        assertThat(fixtureRuleIds)
            .as("注册表新增/删除动作级规则时，FIXTURES 必须同步——禁止把规则无测试地加进注册表")
            .containsExactlyInAnyOrderElementsOf(registered);
    }

    @Test
    @DisplayName("每条规则都必须带规格出处，动作级规则一律 act. 前缀且为 BLOCK")
    void everyRuleCarriesSpecCitation() {
        Set<String> codes = new LinkedHashSet<>(ProjectAgentArtifactVerifier.actionRulesByCode().keySet());
        codes.add(NEUTRAL_CODE);
        for (String code : codes) {
            for (Rule rule : ProjectAgentArtifactVerifier.rulesFor(code)) {
                assertThat(rule.source())
                    .as("规则 %s 缺规格出处——写不出原句就不许登记", rule.id())
                    .isNotBlank();
            }
        }
        for (Rule rule : ProjectAgentArtifactVerifier.actionRulesByCode().values().stream()
            .flatMap(List::stream).toList()) {
            assertThat(rule.id()).as("动作级规则标识须以 act. 开头").startsWith("act.");
            assertThat(rule.severity()).as("动作级规则 %s 当前一律 BLOCK", rule.id())
                .isEqualTo(Severity.BLOCK);
        }
    }

    @Test
    @DisplayName("注册表键必须是真实存在的动作码：拼错或写退役动作码则红")
    void everyRegistryKeyIsALiveActionCode() {
        for (String code : ProjectAgentArtifactVerifier.actionRulesByCode().keySet()) {
            assertThatCode(() -> ActionCatalog.byCode(code))
                .as("动作码 %s 在 ActionCatalog 中不存在（拼错，或登记了已退役动作）", code)
                .doesNotThrowAnyException();
        }
        assertThat(ProjectAgentArtifactVerifier.actionRulesByCode().keySet())
            .as("LC01 回款跟踪 / LC03 终算+奖金池已于 2026-10-03 退役，不得为其登记规则")
            .doesNotContain("LC01", "LC03");
    }

    @Test
    @DisplayName("五个 Gate 的会议纪要共用同一条规则（强制输出物第三项对全部 Gate 适用）")
    void gateMinutesRuleAppliesToAllFiveGateActions() {
        for (String gateAction : List.of("C11", "P13", "D05", "L07", "LC02")) {
            assertThat(ProjectAgentArtifactVerifier.rulesFor(gateAction))
                .as("%s 是 Gate 动作，须适用会议纪要三要素规则", gateAction)
                .extracting(Rule::id)
                .contains("act.gate.minutes.triple");
        }
        assertThat(gapIds(verifier.evaluate("LC02", MINUTES_FAIL)))
            .as("G5 复盘动作走同一规则")
            .containsExactly("act.gate.minutes.triple");
    }

    @Test
    @DisplayName("Z 系别名归一：Z02 与权威码 V10 命中同一条规则")
    void actionCodeAliasResolvesToSameRules() {
        assertThat(ProjectAgentArtifactVerifier.rulesFor("Z02"))
            .as("Z02 是 V10 的第二编码，规则集必须一致")
            .extracting(Rule::id)
            .isEqualTo(ProjectAgentArtifactVerifier.rulesFor("V10").stream().map(Rule::id).toList());
        assertThat(gapIds(verifier.evaluate("Z02", V10_FAIL)))
            .containsExactly("act.V10.dimension.coverage");
    }

    @Test
    @DisplayName("未登记动作码与 null/空码：行为与首批之前一致，只有两条通用规则（零回归）")
    void unregisteredActionCodeGetsOnlyGenericRules() {
        assertThat(ProjectAgentArtifactVerifier.rulesFor("C01")).hasSize(2);
        assertThat(ProjectAgentArtifactVerifier.rulesFor(null)).hasSize(2);
        assertThat(ProjectAgentArtifactVerifier.rulesFor("")).hasSize(2);
        assertThat(ProjectAgentArtifactVerifier.rulesFor("不存在的码")).hasSize(2);
        assertThat(verifier.evaluate(null, CLEAN_DOC).verdict()).isEqualTo(Verdict.PASS);
    }

    // ================= 接线级：规则是否真的能拦住一次运行 =================
    // 单元级的缺口列表不算「能拦」——必须证明它在真实运行句柄上改变了终态、且缺口对用户可见。

    @Test
    @DisplayName("接线级正例：完整合格的 C02 产物在真实运行句柄上走 SUCCEEDED 并落一份草稿")
    void completeC02ArtifactReachesSucceededThroughRunHandle() {
        Recorded recorded = runThrough("C02", C02_PASS);
        assertThat(recorded.status()).isEqualTo("SUCCEEDED");
        assertThat(recorded.artifactCount()).isEqualTo(1);
        assertThat(recorded.verifyGapsPayload()).as("通过时不写缺口事件").isEmpty();
    }

    @Test
    @DisplayName("接线级反例：缺「技术路线」的 C02 产物停在 VERIFYING（不是 FAILED），缺口写进 VERIFY_GAPS 事件")
    void incompleteC02ArtifactStopsAtVerifyingAndPublishesGap() {
        Recorded recorded = runThrough("C02", C02_FAIL);
        assertThat(recorded.status())
            .as("缺维度应停在驻留态等人工复检，而不是判失败")
            .isEqualTo("VERIFYING");
        assertThat(recorded.verifyGapsPayload())
            .as("缺口必须写进 STEP 事件，否则用户根本看不见这条拦截")
            .contains("VERIFY_GAPS")
            .contains("act.C02.dimension.coverage");
        assertThat(recorded.verifyGapsPayload())
            .as("缺口说明必须是白话，让用户知道缺什么")
            .contains("未满足：竞品分析报告须覆盖功能、价格、渠道、技术路线四个维度");
        assertThat(recorded.verifyGapsPayload())
            .as("缺口须可寻址")
            .contains("artifact:body");
    }

    @Test
    @DisplayName("接线级零回归：未登记规则的动作码在真实链路上仍走 SUCCEEDED")
    void unregisteredActionStillSucceedsThroughRunHandle() {
        Recorded recorded = runThrough(NEUTRAL_CODE, "# 缺项表\n| 编号 | 缺项 | 影响 | 处理 |\n| G-06 | 目的裁剪 | 阻塞步骤2 | 未声明则四维齐全。 |");
        assertThat(recorded.status()).isEqualTo("SUCCEEDED");
        assertThat(recorded.artifactCount()).isEqualTo(1);
    }

    /** 用真实运行句柄跑一次「正文 → 落草稿 → 终态」，返回终态、产物计数与缺口事件正文。 */
    private static Recorded runThrough(String actionCode, String body) {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        IpdAgentRun run = IpdAgentRun.builder().tenantId(AgentTestFixtures.TENANT)
            .projectId(AgentTestFixtures.PROJECT_ID).personId(AgentTestFixtures.ACTOR.id())
            .agentId("ipd_project_agent").actionCode(actionCode).status(AgentRunStatus.RUNNING.name())
            .idempotencyKey("key-verifier-wiring-" + actionCode).build();
        store.insertRun(run);
        ProjectAgentRunHandle handle = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, () -> 1_000_000L, () -> { });
        handle.onText(body);
        handle.onComplete();
        String verifyPayload = store.events(run.getId()).stream()
            .map(IpdAgentRunEvent::getPayload)
            .filter(payload -> payload != null && payload.contains("VERIFY_GAPS"))
            .findFirst().orElse("");
        return new Recorded(store.findRun(run.getId()).orElseThrow().getStatus(),
            artifacts.size(), verifyPayload);
    }

    /**
     * 一次接线级运行的观测结果。
     *
     * @param status 运行终态
     * @param artifactCount 落库的产物版本数
     * @param verifyGapsPayload VERIFY_GAPS 事件正文；未产生该事件时为空串
     */
    private record Recorded(String status, int artifactCount, String verifyGapsPayload) {
    }

    // ================= 辅助 =================

    private static List<String> gapIds(Verdict verdict) {
        return verdict.gaps().stream().map(Gap::id).toList();
    }

    private static Set<String> registeredActionRuleIds() {
        Set<String> ids = new LinkedHashSet<>();
        ProjectAgentArtifactVerifier.actionRulesByCode().values()
            .forEach(rules -> rules.forEach(rule -> ids.add(rule.id())));
        return ids;
    }
}
