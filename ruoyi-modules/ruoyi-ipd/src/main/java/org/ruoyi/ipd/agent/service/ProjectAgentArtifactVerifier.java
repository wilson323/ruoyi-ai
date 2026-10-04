package org.ruoyi.ipd.agent.service;

import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 通用规则对全部产物动作生效；动作级规则 {@link #ACTION_RULES} 于 2026-10-03 登记首批 6 条，
 * 每条都带 {@link Rule#source()} 规格出处，且必须同时有正例/反例测试夹具
 * （缺夹具时 {@code ProjectAgentArtifactVerifierTest} 会红）。
 * <b>规则只允许来自冻结规格原文</b>——出处写不出原句就不许登记；
 * 未登记的动作码行为与首批之前完全一致（零回归）。
 * 与 {@link ProjectAgentCompletionGate} 零重叠：完成门管来源与越权宣称（判 FAILED），
 * 本类只管产物结构形态（判 VERIFYING）。
 */
public final class ProjectAgentArtifactVerifier {

    /**
     * 缺口严重度。
     *
     * <p><b>BLOCK</b>：运行停到 {@link AgentRunStatus#VERIFYING}，缺口写进 STEP（kind=VERIFY_GAPS）给用户看，
     * 人工补证据后复检。
     *
     * <p><b>WARN</b>：<b>当前接线下不会产生任何用户可见输出</b>——两个调用点都只在
     * 「有 BLOCK 缺口 → 进 VERIFYING」的分支里写缺口事件
     * （{@code ProjectAgentRunHandle#finishOnce} 仅在 {@code effective == VERIFYING} 时调
     * {@code writeVerifyGapsStep}；{@code ProjectAgentRunService#reverify} 只处理已在 VERIFYING 的运行），
     * 纯 WARN 的缺口会随终态事件一起被丢掉。
     * 故本表暂不登记 WARN 规则：登记一条用户看不见的规则等于假绿；
     * 要新增 WARN 规则，先把落点补上（让声明「随事件披露」成为真的）。
     */
    public enum Severity { BLOCK, WARN }

    /**
     * 一条机器可执行规则。
     *
     * @param id 规则标识（如 doc.heading.structure）
     * @param type STRUCTURAL / CONTENT / REFERENTIAL
     * @param severity 缺口严重度
     * @param summary 通过条件的白话描述
     * @param source 规则的规格出处（冻结规格文件名 + 原句）；动作级规则禁止留空
     * @param check 机器判定；true=通过，false=缺口
     */
    public record Rule(String id, String type, Severity severity, String summary, String source,
                       java.util.function.Predicate<String> check) {

        /** 无出处重载：仅供测试夹具构造临时规则，生产注册表一律走带 source 的构造。 */
        public Rule(String id, String type, Severity severity, String summary,
                    java.util.function.Predicate<String> check) {
            this(id, type, severity, summary, "", check);
        }
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

    /** 通用规则出处（V-2 代码级落地的两条通用规则）。 */
    private static final String SOURCE_GENERIC =
        "AgentScope官方化-Quality域Verifier缺口设计-20261002.md §九.1（V-2 通用规则两条）";

    /** 通用规则：对全部产物动作生效，形态契约来自设计 §四规则类型定义。 */
    private static final List<Rule> GENERIC_RULES = List.of(
        new Rule("doc.heading.structure", "STRUCTURAL", Severity.BLOCK,
            "产物正文至少包含一个标题", SOURCE_GENERIC, ProjectAgentArtifactVerifier::hasHeading),
        new Rule("doc.placeholder.content", "CONTENT", Severity.BLOCK,
            "产物正文不得残留占位标记", SOURCE_GENERIC, ProjectAgentArtifactVerifier::freeOfPlaceholder));

    // ===== 维度同义组 =====
    // 每组代表「规格里点名的一个维度」的可接受写法。分组而非单词匹配，是为了压低误报：
    // 写「定价」或「报价」都算命中「价格」维度。同义组内的词只能来自该维度的常识近义写法，
    // 不许为了放宽而塞入跨维度的泛词（会把缺口放过，等于假绿）。

    /** 竞品分析「功能」维度。 */
    private static final Pattern DIM_FUNCTION = Pattern.compile("功能|特性");
    /** 竞品分析「价格」维度。 */
    private static final Pattern DIM_PRICE = Pattern.compile("价格|定价|售价|报价");
    /** 竞品分析 / GTM「渠道」维度。 */
    private static final Pattern DIM_CHANNEL = Pattern.compile("渠道|分销|经销|通路");
    /** 竞品分析「技术路线」维度（不取泛词「技术」，避免任一篇技术文档都命中）。 */
    private static final Pattern DIM_TECH_ROUTE = Pattern.compile("技术路线|技术方案|技术架构|技术选型|技术对比");
    /** C07「成本」维度。 */
    private static final Pattern DIM_COST = Pattern.compile("成本");
    /** C07「毛利」维度。 */
    private static final Pattern DIM_MARGIN = Pattern.compile("毛利");
    /** C08 基准值一：上市 6 个月销售目标。 */
    private static final Pattern BASE_SALES = Pattern.compile("销售目标|目标销售额|销售额|销售收入|销量目标");
    /** C08 基准值二：目标渠道数。 */
    private static final Pattern BASE_CHANNEL_COUNT = Pattern.compile("渠道数|渠道数量|目标渠道|渠道目标");
    /** C08 基准值三：NPS 目标。 */
    private static final Pattern BASE_NPS = Pattern.compile("NPS|净推荐值", Pattern.CASE_INSENSITIVE);
    /** C08 基准值四：目标场景数。 */
    private static final Pattern BASE_SCENE_COUNT = Pattern.compile("场景数|场景数量|目标场景|场景目标");
    /** C09 项目等级 S/A/B。 */
    private static final Pattern GRADE_LEVEL = Pattern.compile("[SAB]\\s*级|等级\\s*[:：=]?\\s*[SAB]\\b");
    /** C09 差异化系数。 */
    private static final Pattern GRADE_COEFFICIENT = Pattern.compile("差异化系数|系数");
    /** V10 跨人种维度。 */
    private static final Pattern DIM_ETHNICITY = Pattern.compile("人种|族裔|种族");
    /** V10 跨年龄维度。 */
    private static final Pattern DIM_AGE = Pattern.compile("年龄");
    /** Gate 会议纪要要素一：遗留项清单。 */
    private static final Pattern MINUTES_LEFTOVER = Pattern.compile("遗留项|遗留问题|待办项|未闭环项");
    /** Gate 会议纪要要素二：责任人。 */
    private static final Pattern MINUTES_OWNER = Pattern.compile("责任人|负责人|指派给");
    /** Gate 会议纪要要素三：关闭期限。 */
    private static final Pattern MINUTES_DUE = Pattern.compile("期限|截止|完成时间|关闭时间|结项时间");

    /**
     * 动作级规则表（2026-10-03 首批 6 条，落在 10 个动作码上）。
     *
     * <p>每条规则的章节/维度清单都逐字取自冻结规格，禁用「通用最佳实践」推断。
     * 登记即随版本发布；运行时不可动态增删。动作码查表前先经
     * {@link ActionCatalog#resolveCode(String)} 归一，Z01–Z05 别名与权威码同规则。
     */
    private static final Map<String, List<Rule>> ACTION_RULES = buildActionRules();

    /** 测试可见的规则视图；生产路径只经 {@link #evaluate(String, String)}。 */
    static List<Rule> rulesFor(String actionCode) {
        String resolved = ActionCatalog.resolveCode(actionCode);
        List<Rule> action = resolved == null ? List.of() : ACTION_RULES.getOrDefault(resolved, List.of());
        List<Rule> all = new ArrayList<>(GENERIC_RULES.size() + action.size());
        all.addAll(GENERIC_RULES);
        all.addAll(action);
        return List.copyOf(all);
    }

    /** 测试可见的动作级规则全表（不可变）；生产路径不读它。 */
    static Map<String, List<Rule>> actionRulesByCode() {
        return ACTION_RULES;
    }

    /**
     * 构造动作级规则表。
     *
     * @return 动作码 → 该动作额外适用的规则（不可变）
     */
    private static Map<String, List<Rule>> buildActionRules() {
        Map<String, List<Rule>> rules = new LinkedHashMap<>();

        rules.put("C02", List.of(new Rule(
            "act.C02.dimension.coverage", "STRUCTURAL", Severity.BLOCK,
            "竞品分析报告须覆盖功能、价格、渠道、技术路线四个维度",
            "六阶段标准动作清单 C02「竞品分析（功能/价格/渠道/技术路线）」；同口径见五大Gate评审要素 G1-3",
            body -> coversAll(body, DIM_FUNCTION, DIM_PRICE, DIM_CHANNEL, DIM_TECH_ROUTE))));

        rules.put("C07", List.of(new Rule(
            "act.C07.dimension.coverage", "STRUCTURAL", Severity.BLOCK,
            "成本与定价测算表须覆盖成本、定价、毛利三项",
            "六阶段标准动作清单 C07「成本/定价/毛利初步测算」；同口径见五大Gate评审要素 G1-5",
            body -> coversAll(body, DIM_COST, DIM_PRICE, DIM_MARGIN))));

        rules.put("C08", List.of(new Rule(
            "act.C08.baseline.coverage", "STRUCTURAL", Severity.BLOCK,
            "商业计划书须录入四项基准值：销售目标、目标渠道数、NPS 目标、目标场景数",
            "六阶段标准动作清单 C08「上市6个月销售目标/渠道数/NPS/场景数四项基准值在此录入」；"
                + "五大Gate评审要素 通用规则·否决项 2「关键数据缺失：…四项基准值未录入」",
            body -> coversAll(body, BASE_SALES, BASE_CHANNEL_COUNT, BASE_NPS, BASE_SCENE_COUNT))));

        rules.put("C09", List.of(new Rule(
            "act.C09.grade.coefficient", "STRUCTURAL", Severity.BLOCK,
            "项目等级评定记录须同时给出项目等级（S/A/B）与差异化系数",
            "六阶段标准动作清单 C09「项目等级评定 S/A/B + 差异化系数（已定：S=1.5 / A=1.0 / B=0.8）」",
            body -> coversAll(body, GRADE_LEVEL, GRADE_COEFFICIENT))));

        rules.put("V10", List.of(new Rule(
            "act.V10.dimension.coverage", "STRUCTURAL", Severity.BLOCK,
            "分人群测试报告须同时覆盖人种维度与年龄维度",
            "六阶段标准动作清单 V10/Z02「跨人种 / 跨年龄适配验证」·交付物「分人群测试报告（强制）」",
            body -> coversAll(body, DIM_ETHNICITY, DIM_AGE))));

        // 五个 Gate 的会议纪要共用同一条规则：强制输出物第三项「会议纪要（含遗留项清单、责任人、期限）」
        // 对五个 Gate 全部适用，故同一规则实例挂到各自的 Gate 动作码上（Gate 动作见 Gate 要素文件各节「动作」行）。
        Rule minutes = new Rule(
            "act.gate.minutes.triple", "STRUCTURAL", Severity.BLOCK,
            "Gate 评审会议纪要须含遗留项清单、责任人、关闭期限三项",
            "五大Gate评审要素 v1「通用规则 › 强制输出物」第 3 条「会议纪要（含遗留项清单、责任人、期限）」",
            body -> coversAll(body, MINUTES_LEFTOVER, MINUTES_OWNER, MINUTES_DUE));
        for (String gateAction : List.of("C11", "P13", "D05", "L07", "LC02")) {
            rules.put(gateAction, List.of(minutes));
        }

        return Map.copyOf(rules);
    }

    /**
     * 全部同义组都命中才算覆盖；任一组缺失即缺口。
     *
     * @param content 产物正文
     * @param groups 每个维度一个同义组
     * @return true=全部维度已覆盖
     */
    private static boolean coversAll(String content, Pattern... groups) {
        for (Pattern group : groups) {
            if (!group.matcher(content).find()) {
                return false;
            }
        }
        return true;
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
