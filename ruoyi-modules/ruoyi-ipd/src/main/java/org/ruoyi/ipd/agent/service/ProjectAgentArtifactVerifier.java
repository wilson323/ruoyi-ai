package org.ruoyi.ipd.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 产物机器校验（AgentScope 官方化 Quality 域 V-2，设计 20261002 §四）。
 *
 * <p>判定逻辑是可测试的代码不是提示词；返回结构化缺口，不返回布尔。
 * BLOCK 级缺口只把运行停到 {@link AgentRunStatus#VERIFYING}，不判 FAILED；
 * 人工审核链（ai_documents / ipd:ai-document:review）语义不变。
 *
 * <p>规则是代码级注册表，随版本发布，运行时不可动态增删（验收标准 4）。
 * 通用规则对全部产物动作生效；动作级章节清单 {@link #ACTION_RULES} 首批为空，
 * 待 owner 按 §七拍板首批种子动作后登记，未登记前不编造章节要求。
 * 与 {@link ProjectAgentCompletionGate} 零重叠：完成门管来源与越权宣称（判 FAILED），
 * 本类只管产物结构形态（判 VERIFYING）。
 */
public final class ProjectAgentArtifactVerifier {

    /** 缺口严重度：BLOCK 停 VERIFYING；WARN 只随事件披露不拦截。 */
    public enum Severity { BLOCK, WARN }

    /**
     * 一条机器可执行规则。
     *
     * @param id 规则标识（如 doc.heading.structure）
     * @param type STRUCTURAL / CONTENT / REFERENTIAL
     * @param severity 缺口严重度
     * @param summary 通过条件的白话描述
     * @param check 机器判定；true=通过，false=缺口
     */
    public record Rule(String id, String type, Severity severity, String summary,
                       java.util.function.Predicate<String> check) {
    }

    /**
     * 单条缺口（可寻址）。
     *
     * @param id 规则标识
     * @param status 固定 FAIL
     * @param severity 缺口严重度
     * @param evidencePath 缺口在产物中的定位（正文行号或整体）
     * @param gapSummary 白话缺口说明
     */
    public record Gap(String id, String status, Severity severity, String evidencePath, String gapSummary) {
    }

    /**
     * 校验结论：PASS 或带缺口列表的 GAPS。
     *
     * @param verdict PASS / GAPS
     * @param gaps 全部缺口（含 WARN；PASS 时空）
     */
    public record Verdict(String verdict, List<Gap> gaps) {
        public static final String PASS = "PASS";
        public static final String GAPS = "GAPS";

        public boolean hasBlockingGaps() {
            return gaps.stream().anyMatch(gap -> gap.severity() == Severity.BLOCK);
        }

        public String summary() {
            return gaps.isEmpty() ? "机器校验通过" : "共 " + gaps.size() + " 项缺口";
        }
    }

    /** 正文至少含一个 Markdown 标题，才具备文档结构。 */
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}[\\t ]+\\S");

    /** 占位标记：同一正文出现多处即视为半成品。单处合法提及不拦截。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("TODO|TBD|待补充|【占位】|占位符");

    /** 通用规则：对全部产物动作生效，形态契约来自设计 §四规则类型定义。 */
    private static final List<Rule> GENERIC_RULES = List.of(
        new Rule("doc.heading.structure", "STRUCTURAL", Severity.BLOCK,
            "产物正文至少包含一个标题", ProjectAgentArtifactVerifier::hasHeading),
        new Rule("doc.placeholder.content", "CONTENT", Severity.BLOCK,
            "产物正文不得残留占位标记", ProjectAgentArtifactVerifier::freeOfPlaceholder));

    /**
     * 动作级规则表（首批为空）。登记即随版本发布并经评审留痕；
     * 运行时不可动态增删。章节清单必须有事实源（工程合同/规格），禁止编造。
     */
    private static final Map<String, List<Rule>> ACTION_RULES = Map.of();

    /** 测试可见的规则视图；生产路径只经 {@link #evaluate(String, String)}。 */
    static List<Rule> rulesFor(String actionCode) {
        List<Rule> action = ACTION_RULES.getOrDefault(actionCode == null ? "" : actionCode, List.of());
        List<Rule> all = new ArrayList<>(GENERIC_RULES.size() + action.size());
        all.addAll(GENERIC_RULES);
        all.addAll(action);
        return List.copyOf(all);
    }

    /**
     * 校验产物正文。
     *
     * @param actionCode 动作码（可空；仅用于动作级规则检索）
     * @param content 产物正文
     * @return PASS 或 GAPS（缺口含 WARN；是否拦截看 hasBlockingGaps）
     */
    public Verdict evaluate(String actionCode, String content) {
        return evaluateWithRules(content, rulesFor(actionCode));
    }

    /** 测试 seam：按给定规则集校验，不读注册表。与生产入口不同名，避免重载歧义。 */
    static Verdict evaluateWithRules(String content, List<Rule> rules) {
        String body = content == null ? "" : content;
        List<Gap> gaps = new ArrayList<>();
        for (Rule rule : rules) {
            if (!rule.check().test(body)) {
                gaps.add(new Gap(rule.id(), "FAIL", rule.severity(), "artifact:body",
                    "未满足：" + rule.summary()));
            }
        }
        return gaps.isEmpty() ? new Verdict(Verdict.PASS, List.of()) : new Verdict(Verdict.GAPS, List.copyOf(gaps));
    }

    private static boolean hasHeading(String content) {
        return HEADING.matcher(content).find();
    }

    private static boolean freeOfPlaceholder(String content) {
        int count = 0;
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(content);
        while (matcher.find()) {
            count++;
            if (count >= 2) {
                return false;
            }
        }
        return true;
    }
}
