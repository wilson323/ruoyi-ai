package org.ruoyi.ipd.seed;

import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方法论话术目录（Track C1）：67 动作 ×（绑定强度 + pm-skills 真名 + command 链 + 引导话术）。
 *
 * <p>存储形态决策见 Track-C 详稿 C1.0（R236 先例：纯派生数据放目录旁，哨兵测试锁定）。
 * 数据源 = §3-pm-skills映射.md §3.2 逐行转写（skill/command 只用 §3.1 真名，禁改 ActionCatalog 名称口径）。
 * 不绑定（NONE）动作走 {@link #REGISTER_TEMPLATE} 结构化登记话术，skillNames 为空。
 */
public final class GuideScriptCatalog {

    /** §3.2.8 四级绑定强度。 */
    public enum BindLevel { BIND, WEAK, CANDIDATE, NONE }

    /** 单动作话术行（1:1 于 ActionCatalog 的 action_code）。 */
    public record GuideScript(String actionCode, BindLevel bindLevel,
                              List<String> skillNames, List<String> commandChain,
                              String guidePrompt) {
    }

    /** 不绑定动作的结构化登记话术模板（占位符 {actionName}）。 */
    public static final String REGISTER_TEMPLATE =
        "本动作走结构化登记：{actionName}。请按页面表单逐项录入并留存凭证，AI 不代填决策值，仅做必填项与区间校验提醒。";

    private static final Map<String, GuideScript> SCRIPTS = new LinkedHashMap<>();

    private GuideScriptCatalog() {
    }

    private static void add(String code, BindLevel level, List<String> skills,
                            List<String> commands, String prompt) {
        SCRIPTS.put(code, new GuideScript(code, level,
            List.copyOf(skills), List.copyOf(commands), prompt));
    }

    private static void none(String code, String prompt) {
        add(code, BindLevel.NONE, List.of(), List.of(), prompt);
    }

    // ---- 阶段一 CONCEPT（12，§3.2.1）----
    static {
        add("C01", BindLevel.BIND,
            List.of("interview-script", "summarize-interview", "market-sizing"),
            List.of("/interview prep", "/interview summarize"),
            "先用 JTBD/Mom Test 提纲做痛点访谈，逐份归纳访谈信号，再估 TAM/SAM/SOM，产出市场调研报告。");
        add("C02", BindLevel.BIND,
            List.of("competitor-analysis", "porters-five-forces", "swot-analysis"),
            List.of("/competitive-analysis", "/market-scan"),
            "先做竞品四维对比（功能/价格/渠道/技术路线），再用五力+SWOT 收敛差异化空间。");
        add("C03", BindLevel.BIND,
            List.of("user-personas", "market-segments", "user-segmentation"),
            List.of("/research-users"),
            "从访谈证据提炼三类画像，收敛 3-5 个细分市场并评估匹配度，产出目标客户画像。");
        add("C04", BindLevel.BIND,
            List.of("pestle-analysis", "market-segments"),
            List.of("/market-scan"),
            "按目标国别跑 PESTLE（法规/文化/电压/插头等变量），输出区域市场差异清单。");
        none("C05", "轻管动作不强制交付物：仅登记技术可行性预研的状态/日期/备注三要素即完成。");
        add("C06", BindLevel.BIND,
            List.of("product-vision", "value-proposition", "positioning-ideas"),
            List.of("/strategy", "/value-proposition", "/market-product"),
            "先立产品愿景与 JTBD 六段式价值主张，再从竞品定位角度收敛差异化，产出产品概念说明书。");
        add("C07", BindLevel.BIND,
            List.of("pricing-strategy", "monetization-strategy"),
            List.of("/pricing"),
            "用定价模型+竞品价格+支付意愿测算，产出成本与定价测算表。");
        add("C08", BindLevel.BIND,
            List.of("market-sizing", "brainstorm-okrs", "north-star-metric"),
            List.of("/plan-okrs", "/north-star"),
            "录入四项基准值（上市6个月销售目标/渠道数/NPS/场景数）并锁口径（G1 后锁定，为奖金池/共担KPI 计算基准），产出商业计划书 Charter。");
        none("C09", "治理决策走结构化表单登记：录入项目等级（S/A/B）+差异化系数（S=1.5/A=1.0/B=0.8）+产品组长 A 角+双签审计；AI 不代填系数，仅做必填项与区间校验提醒。");
        add("C10", BindLevel.CANDIDATE,
            List.of("privacy-policy"),
            List.of("/privacy-policy"),
            "强制上传知识产权检索报告（含 FTO）；专利检索依赖人工/外部工具，AI 只提醒归档与完整性，隐私合规文本可辅助起草。");
        add("C11", BindLevel.BIND,
            List.of("summarize-meeting", "strategy-red-team", "stakeholder-map"),
            List.of("/red-team-prd", "/stakeholder-map", "/meeting-notes"),
            "会前用红队攻击 Charter 关键假设，按干系人策略对齐双签人，会后纪要+行动项留痕（G1 立项 Go/No-Go）。");
        add("C12", BindLevel.BIND,
            List.of("privacy-policy"),
            List.of("/privacy-policy"),
            "按 GDPR 特殊类别数据+个保法+《人脸识别技术应用安全管理办法》逐项勾稽，产出合规审查清单（法律红线，S/A/B 级均阻断）。");
    }

    // ---- 阶段二 PLAN（13，§3.2.2）----
    static {
        add("P01", BindLevel.BIND,
            List.of("create-prd", "strategy-red-team"),
            List.of("/write-prd", "/red-team-prd"),
            "按八段式起草 PRD，再用红队找最脆弱前提并修订，产出产品需求规格书。");
        add("P02", BindLevel.BIND,
            List.of("prioritize-features", "prioritization-frameworks", "outcome-roadmap"),
            List.of("/triage-requests", "/transform-roadmap"),
            "选 RICE/ICE 等框架排需求池，把功能清单转 outcome 路线图，产出版本规划表。");
        add("P03", BindLevel.CANDIDATE,
            List.of("shipping-artifacts"),
            List.of(),
            "轻管仅登记完成；若产出 AI 代码方案，可用可审查文档集骨架组织设计文档（轻量提示，不产强制交付物）。");
        none("P04", "硬件方案（ID/结构/硬件/固件）无对应技能：登记完成即可。");
        add("P05", BindLevel.CANDIDATE,
            List.of("shipping-artifacts"),
            List.of(),
            "登记完成；软件概要设计如需文档骨架可轻量提示可审查文档集（推断，不产强制交付物）。");
        add("P06", BindLevel.CANDIDATE,
            List.of("customer-journey-map"),
            List.of(),
            "登记完成；解决方案场景梳理可用客户旅程图轻量提示（推断）。");
        none("P07", "供应链评估无对应技能：登记完成即可。");
        add("P08", BindLevel.CANDIDATE,
            List.of("sprint-plan"),
            List.of(),
            "登记完成；必须登记关键里程碑日期（上市准时率/窗口命中率 KPI 依赖它），排期思路可轻量提示 sprint-plan。");
        none("P09", "资源与预算评估走表单登记：登记完成即可。");
        none("P10", "按国别认证模板库带出清单登记（认证与法规清单确认）；阻断走系统门禁（认证缺失无法上市），AI 不替代清单判定。");
        add("P11", BindLevel.CANDIDATE,
            List.of("pre-mortem"),
            List.of(),
            "轻管不产强制交付物；可用 Tigers/Paper Tigers/Elephants 话术口头引导风险识别并登记。");
        add("P12", BindLevel.BIND,
            List.of("value-proposition", "pricing-strategy", "value-prop-statements"),
            List.of("/value-proposition", "/pricing"),
            "卖点用 JTBD 六段式收束、定价复核毛利，产出差异化卖点清单+定价策略。");
        add("P13", BindLevel.BIND,
            List.of("summarize-meeting", "strategy-red-team"),
            List.of("/red-team-prd", "/meeting-notes"),
            "会前红队验证卖点可交付性与毛利复核，会后纪要双签（G2 差异化确认）。");
    }

    // ---- 阶段三 DEV（11，§3.2.3）----
    static {
        none("D01", "详细设计（结构/硬件/软件）登记完成（三要素登记）。");
        none("D02", "首版 BOM 冻结与采购为硬件/采购动作，无对应技能：登记完成即可。");
        none("D03", "手板/EVT 样机为硬件动作，无对应技能：登记完成即可。");
        add("D04", BindLevel.CANDIDATE,
            List.of("test-scenarios"),
            List.of(),
            "登记完成；单测场景可轻量提示 test-scenarios（推断，不产强制交付物）。");
        add("D05", BindLevel.BIND,
            List.of("summarize-meeting", "outcome-roadmap"),
            List.of("/meeting-notes", "/sprint retro"),
            "双周纪要盯进度+场景完整度（市场 PM 视角）；连续 2 次 P0 阻塞未升级自动升级双方产品组长（G3）。");
        add("D06", BindLevel.BIND,
            List.of("analyze-feature-requests", "prioritize-features", "strategy-red-team"),
            List.of("/triage-requests", "/red-team-prd"),
            "变更请求归类→影响/优先级评估→红队检验必要性，产出需求变更单（双签否决；系统自动统计变更率供 KPI 取数）。");
        none("D07", "模具开发与 T1 试模为硬件动作，无对应技能：登记完成即可。");
        none("D08", "成本复核为表单登记（与 C07 区分：C07 绑定价技能，本动作不绑）：登记完成即可。");
        add("D09", BindLevel.CANDIDATE,
            List.of("release-notes"),
            List.of(),
            "登记内测发布日期；发布说明可轻量提示 release-notes（推断）。");
        add("D10", BindLevel.CANDIDATE,
            List.of("test-scenarios"),
            List.of(),
            "登记完成；联调用例可轻量提示 test-scenarios（推断）。");
        none("D11", "无算法评测技能：引导录入实测 FAR/FRR 数值字段并提示与基线横向对比（数值登记）。");
    }

    // ---- 阶段四 VALID（12，§3.2.4）----
    static {
        add("V01", BindLevel.CANDIDATE,
            List.of("test-scenarios"),
            List.of(),
            "硬件 DVT 登记完成；测试场景骨架可轻量提示（推断）。");
        none("V02", "认证测试送检：登记证书编号+通过日期（不强制上传扫描件）；阻断走系统门禁。");
        add("V03", BindLevel.BIND,
            List.of("interview-script", "summarize-interview", "sentiment-analysis"),
            List.of("/interview", "/analyze-feedback"),
            "Beta 访谈提纲→逐字稿归纳→大样本情绪/主题分析，产出 Beta 试用报告。");
        add("V04", BindLevel.CANDIDATE,
            List.of("test-scenarios"),
            List.of(),
            "登记完成；可用 test-scenarios 生成用例骨架（不产强制交付物）。");
        none("V05", "试产 PVT/小批量为硬件动作，无对应技能：登记完成即可。");
        add("V06", BindLevel.BIND,
            List.of("pre-mortem", "summarize-meeting"),
            List.of("/pre-mortem", "/meeting-notes"),
            "会前预演量产失败风险（A=产品组长），纪要+评审材料留痕。");
        add("V07", BindLevel.BIND,
            List.of("value-prop-statements", "grammar-check"),
            List.of("/market-product", "/proofread"),
            "文案价值表达定调后逐份校对，产出包装设计稿+用户手册（推断）。");
        none("V08", "售后与维修方案无对应技能：登记完成即可。");
        add("V09", BindLevel.BIND,
            List.of("test-scenarios", "intended-vs-implemented"),
            List.of("/test-scenarios", "/derive-tests"),
            "试点验收用例覆盖对照「文档意图 vs 实现」，产出试点交付验收报告（推断）。");
        add("V10", BindLevel.BIND,
            List.of("user-segmentation", "metrics-dashboard"),
            List.of("/setup-metrics"),
            "按人群分层定义 FAR/FRR 指标与告警阈值，分人群测试报告强制上传（推断；Z08 公平性测试并入本动作 SOP）。");
        add("V11", BindLevel.BIND,
            List.of("intended-vs-implemented", "shipping-artifacts"),
            List.of("/document-app", "/ship-check"),
            "登记对接范围与通过结论；解决方案模板升级深管时用「文档意图 vs 实现」对照审查集成偏差（推断绑定仅在解模板生效）。");
        add("V12", BindLevel.WEAK,
            List.of("grammar-check"),
            List.of("/proofread"),
            "本地化验收清单逐项打勾（语言/阿拉伯语 RTL/电压 110V-220V/插头/国别认证），闭环 C04↔V07↔L03；多语言文案可校对（推断）。");
    }

    // ---- 阶段五 LAUNCH（8，§3.2.5）----
    static {
        add("L01", BindLevel.BIND,
            List.of("gtm-strategy", "beachhead-segment", "ideal-customer-profile"),
            List.of("/plan-launch"),
            "滩头市场→ICP→信息/渠道/节奏一次成链，产出 GTM 上市方案。");
        add("L02", BindLevel.BIND,
            List.of("pricing-strategy", "gtm-motions", "monetization-strategy"),
            List.of("/pricing", "/growth-strategy"),
            "渠道组合用 gtm-motions 选型，价格体系复核毛利与竞品价，产出渠道价格政策。");
        add("L03", BindLevel.BIND,
            List.of("competitive-battlecard", "value-prop-statements", "marketing-ideas"),
            List.of("/battlecard", "/market-product"),
            "battlecard+价值主张语句做物料骨架，多语言物料对齐 V12 差异，产出工具包清单+物料。");
        add("L04", BindLevel.BIND,
            List.of("competitive-battlecard", "value-prop-statements"),
            List.of("/battlecard"),
            "培训材料以异议处理/赢单打法/价值话术为核心（推断），产出培训材料+签到记录。");
        none("L05", "首批量产与备货为硬件供应链动作：登记备货完成日期即完成。");
        add("L06", BindLevel.WEAK,
            List.of("value-prop-statements", "grammar-check"),
            List.of("/proofread"),
            "逐渠道确认上架项+文案校对，产出上架确认记录（推断：以上架文案质量替代产出物类引导）。");
        add("L07", BindLevel.BIND,
            List.of("pre-mortem", "stakeholder-map", "summarize-meeting"),
            List.of("/pre-mortem", "/stakeholder-map", "/meeting-notes"),
            "会前按 8 要素预演失败模式，销售/供应/售后干系人对齐，纪要双签后方可 L08（G4 GTM 就绪）。");
        add("L08", BindLevel.BIND,
            List.of("release-notes"),
            List.of("/sprint release"),
            "生成发布说明；上市日期录入后锁定（全部后置 KPI 起算原点，修改需双签+审计）。");
    }

    // ---- 阶段六 LIFECYCLE（7，§3.2.6；LC01/LC03 已于 2026-10-03 退役，不再登记引导脚本）----
    static {
        add("LC02", BindLevel.BIND,
            List.of("retro", "summarize-meeting"),
            List.of("/sprint retro", "/meeting-notes"),
            "复盘落到负责人+期限明确的行动项，产出 90 天复盘报告+纪要（G5 双签否决）。");
        none("LC04", "治理评定走结构化评定表登记（三方评定=双 PM+各自产品组长，五维度市场 40-65%/研发 35-60%）：AI 不代评，仅做区间校验提醒。");
        add("LC05", BindLevel.BIND,
            List.of("sentiment-analysis", "analyze-feature-requests"),
            List.of("/analyze-feedback", "/triage-requests"),
            "反馈情绪/主题提取→问题归类分流→处理记录留痕（深管但非阻断：强制留痕+逾期提醒）。");
        add("LC06", BindLevel.CANDIDATE,
            List.of("release-notes"),
            List.of(),
            "登记完成；维护版发布说明可轻量提示 release-notes（推断）。");
        none("LC07", "生命周期状态维护（在售/限售/停产）为状态机变更记录：无方法论技能，结构化状态登记+变更留痕。");
        add("LC08", BindLevel.WEAK,
            List.of("grammar-check"),
            List.of("/proofread"),
            "停产评估为决策表单（A=产品组长），公告文案可校对（推断）。");
        add("LC09", BindLevel.BIND,
            List.of("shipping-artifacts"),
            List.of("/document-app"),
            "按「可审查文档集」骨架组织归档包转只读（R=系统自动，A=产品组长）；无代码仓时仅结构化归档清单（推断绑定）。");
    }

    // ---- 共担 KPI 归集（4，§3.2.7）----
    static {
        add("K01", BindLevel.BIND,
            List.of("sql-queries", "metrics-dashboard"),
            List.of("/write-query", "/setup-metrics"),
            "ERP 出库取数（一期手动录入+凭证）+指标口径定义；产品组长录入，次月第 5 个工作日 18:00 前。");
        add("K02", BindLevel.BIND,
            List.of("sql-queries", "metrics-dashboard"),
            List.of("/write-query", "/setup-metrics"),
            "渠道签约台账/CRM 取数+覆盖率指标定义。");
        add("K03", BindLevel.BIND,
            List.of("sentiment-analysis", "metrics-dashboard"),
            List.of("/analyze-feedback", "/setup-metrics"),
            "问卷有效样本≥30；NPS 文本用情绪/主题分析辅助解读（推断），数值走结构化字段。");
        add("K04", BindLevel.BIND,
            List.of("sql-queries", "metrics-dashboard"),
            List.of("/write-query", "/setup-metrics"),
            "销售报备+交付验收记录取数+覆盖率口径定义。");
    }

    /** 67 条全量（不可变，插入序 = C→P→D→V→L→LC→K）。 */
    public static List<GuideScript> all() {
        return Collections.unmodifiableList(new ArrayList<>(SCRIPTS.values()));
    }

    /** 按动作码取话术；未命中抛 50001 fail-loud（调用方应先经 ActionCatalog 校验码）。 */
    public static GuideScript scriptOf(String actionCode) {
        GuideScript script = SCRIPTS.get(actionCode);
        if (script == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND,
                "引导话术不存在: " + actionCode);
        }
        return script;
    }

    /** 结构化登记话术（NONE 动作统一口径）：模板占位符 {actionName} 由调用方替换。 */
    public static String registerPrompt(String actionName) {
        return REGISTER_TEMPLATE.replace("{actionName}", actionName);
    }
}
