package org.ruoyi.ipd.copilotkit;

import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * slash command 降级层（§3.4.2-6）：Claude 专属 slash 语义在 CopilotKit/Java 运行时
 * 降级为「skill 名 + 步骤话术」自然语言引导。纯静态无状态（可单测、无 IO）。
 *
 * <p>规格源：§3.1 的 42 command 真名 + §3.2 command 链用法；参数变体按 §3.1 标注解析。
 * 未命中命令 fail-loud（10001），防止出现无方法论引导的静默跳步。
 */
public final class CommandDegrader {

    /** 一条降级结果：原命令（含参数）→ 技能名列表 + 自然语言步骤话术。 */
    public record DegradedStep(String command, List<String> skillNames, String stepPrompt) {
    }

    private record Spec(List<String> skills, String stepPrompt) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    /** 参数变体覆盖：命令名 →（参数 key → 技能列表）；参数 key 为命令后首个词。 */
    private static final Map<String, Map<String, List<String>>> ARG_SKILLS = new LinkedHashMap<>();

    private CommandDegrader() {
    }

    private static void spec(String cmd, String skillsCsv, String prompt) {
        SPECS.put(cmd, new Spec(List.of(skillsCsv.split(",")), prompt));
    }

    static {
        // pm-product-discovery（5）
        spec("/discover", "brainstorm-ideas-new,identify-assumptions-new,prioritize-assumptions,opportunity-solution-tree",
            "按发现四步走：发散点子→映射假设→排优先级→设计实验验证。");
        spec("/brainstorm", "brainstorm-ideas-new", "发散想法（ideas/experiments × existing/new 变体见 ARG_SKILLS）。");
        spec("/triage-requests", "analyze-feature-requests", "把需求/反馈归类去重并评估价值，输出优先级清单。");
        spec("/interview", "interview-script", "准备访谈提纲（prep 变体）或归纳访谈（summarize 变体）。");
        spec("/setup-metrics", "metrics-dashboard", "定义指标口径、看板与告警阈值。");
        // pm-product-strategy（5）
        spec("/strategy", "product-strategy", "按 9 部分战略画布逐段填写产品战略。");
        spec("/business-model", "business-model", "梳理商业模式（lean/full/startup/value-prop/all 变体）。");
        spec("/value-proposition", "value-proposition", "按 JTBD 六段式写价值主张。");
        spec("/market-scan", "swot-analysis,pestle-analysis,porters-five-forces,ansoff-matrix",
            "四工具合成市场扫描：SWOT+PESTLE+五力+Ansoff。");
        spec("/pricing", "pricing-strategy", "用定价模型结合竞品价与支付意愿测算定价。");
        // pm-execution（11）
        spec("/write-prd", "create-prd", "按 8 部分 PRD 结构起草产品需求规格。");
        spec("/plan-okrs", "brainstorm-okrs", "设定 O/KR 并对齐商业目标。");
        spec("/transform-roadmap", "outcome-roadmap", "把功能清单转成 outcome 路线图。");
        spec("/sprint", "sprint-plan", "规划 Sprint（plan/retro/release 变体）。");
        spec("/pre-mortem", "pre-mortem", "用 Tigers/Paper Tigers/Elephants 预演失败模式。");
        spec("/red-team-prd", "strategy-red-team", "红队攻击文档最脆弱前提并修订。");
        spec("/meeting-notes", "summarize-meeting", "输出纪要：决议+行动项（负责人+期限）。");
        spec("/stakeholder-map", "stakeholder-map", "按权力×关注度画干系人地图并定沟通策略。");
        spec("/write-stories", "user-stories", "写故事（user/job/wwa 变体）。");
        spec("/test-scenarios", "test-scenarios", "生成测试场景与用例骨架。");
        spec("/generate-data", "dummy-dataset", "生成演示/测试用假数据集。");
    }

    static {
        // pm-market-research（3）
        spec("/research-users", "user-personas,market-segments,user-segmentation,customer-journey-map",
            "画像→分群→旅程图三步用户研究。");
        spec("/competitive-analysis", "competitor-analysis", "竞品多维对比分析。");
        spec("/analyze-feedback", "sentiment-analysis", "反馈情绪与主题分析。");
        // pm-data-analytics（3）
        spec("/write-query", "sql-queries", "写取数 SQL 并核对口径。");
        spec("/analyze-cohorts", "cohort-analysis", "分批 cohort 对比分析。");
        spec("/analyze-test", "ab-test-analysis", "A/B 实验结果分析。");
        // pm-go-to-market（3）
        spec("/plan-launch", "gtm-strategy,beachhead-segment,ideal-customer-profile",
            "滩头市场→ICP→上市节奏全链规划。");
        spec("/growth-strategy", "growth-loops", "设计增长循环与增长策略。");
        spec("/battlecard", "competitive-battlecard", "产出竞品作战卡（异议处理/赢单打法）。");
        // pm-marketing-growth（2）
        spec("/market-product", "marketing-ideas,positioning-ideas,value-prop-statements,product-name",
            "营销想法/定位/价值主张/命名四步。");
        spec("/north-star", "north-star-metric", "确定北极星指标。");
        // pm-toolkit（5）
        spec("/review-resume", "review-resume", "简历评审。");
        spec("/tailor-resume", "review-resume", "按岗位 JD 调整简历（复用简历评审方法，推断）。");
        spec("/draft-nda", "draft-nda", "起草 NDA。");
        spec("/privacy-policy", "privacy-policy", "起草 GDPR/CCPA 合规隐私政策。");
        spec("/proofread", "grammar-check", "逐份校对语法与文案。");
        // pm-ai-shipping（5）
        spec("/ship-check", "shipping-artifacts", "发布前按可审查文档集过审查包。");
        spec("/document-app", "shipping-artifacts", "逆向整理系统文档骨架。");
        spec("/derive-tests", "intended-vs-implemented,test-scenarios", "文档意图→测试覆盖对照推导用例。");
        spec("/security-audit-static", "code-review", "静态安全审查（复用 code-review 找跨边界缺陷，推断）。");
        spec("/performance-audit-static", "code-review", "静态性能审查（复用 code-review，推断）。");

        // 参数变体覆盖（§3.1 标注的 5 条参数化命令）
        ARG_SKILLS.put("/interview", Map.of(
            "prep", List.of("interview-script"),
            "summarize", List.of("summarize-interview")));
        ARG_SKILLS.put("/business-model", Map.of(
            "lean", List.of("lean-canvas"),
            "full", List.of("business-model"),
            "startup", List.of("startup-canvas"),
            "value-prop", List.of("value-proposition"),
            "all", List.of("lean-canvas", "business-model", "startup-canvas", "value-proposition")));
        ARG_SKILLS.put("/sprint", Map.of(
            "plan", List.of("sprint-plan"),
            "retro", List.of("retro"),
            "release", List.of("release-notes")));
        ARG_SKILLS.put("/write-stories", Map.of(
            "user", List.of("user-stories"),
            "job", List.of("job-stories"),
            "wwa", List.of("wwas")));
    }

    /** /brainstorm 双参数变体（ideas|experiments × existing|new）单独解析。 */
    private static List<String> brainstormSkills(String[] words) {
        String kind = words.length > 1 ? words[1] : "ideas";
        String basis = words.length > 2 ? words[2] : "new";
        return List.of(("experiments".equals(kind) ? "brainstorm-experiments-" : "brainstorm-ideas-") + basis);
    }

    /** 单命令降级（command 可带参数串，如 "/interview prep"）。未知命令抛 10001 fail-loud。 */
    public static DegradedStep degrade(String command) {
        String[] words = command.trim().split("\\s+");
        String name = words[0];
        Spec spec = SPECS.get(name);
        if (spec == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "未知 command: " + command);
        }
        List<String> skills = spec.skills();
        if ("/brainstorm".equals(name)) {
            skills = brainstormSkills(words);
        } else if (words.length > 1) {
            Map<String, List<String>> variants = ARG_SKILLS.get(name);
            if (variants != null && variants.containsKey(words[1])) {
                skills = variants.get(words[1]);
            }
        }
        return new DegradedStep(command, List.copyOf(skills), spec.stepPrompt());
    }

    /** 链式降级（保持顺序；空链→空列表）。 */
    public static List<DegradedStep> degradeChain(List<String> commandChain) {
        if (commandChain == null || commandChain.isEmpty()) {
            return List.of();
        }
        List<DegradedStep> out = new ArrayList<>(commandChain.size());
        for (String command : commandChain) {
            out.add(degrade(command));
        }
        return List.copyOf(out);
    }
}
