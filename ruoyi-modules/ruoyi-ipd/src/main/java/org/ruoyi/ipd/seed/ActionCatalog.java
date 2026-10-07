package org.ruoyi.ipd.seed;

import org.ruoyi.ipd.domain.ActionDef;

import org.ruoyi.ipd.common.IpdBusinessException;
import java.util.List;

/**
 * IPD 六阶段标准动作清单 v3 目录（67 动作 = 深管 40 / 轻管 27，阻断 36 / 非阻断 31）
 * 来源：docs/ipd-系统说明/外部资源/IPD系统_六阶段标准动作清单_v3.md（已按 Gavin 全部决策定稿）；
 * 文首「v4 退役标注」节：LC01（上市后销售与回款跟踪）与 LC03（上市后6个月终算）已于 2026-10-03
 * 随「回款台账」「奖金池」功能块退役，故本目录由 v3 的 69 动作同步为 67 动作。
 *
 * 设计说明：动作编码体系（C01/P01/D05/C12/D11/V10...）与阶段/深度/阻断属性是流程定义事实，
 * 编译期固化于本目录；项目等级裁剪规则（A 级必做集、B 级清单）由超管在 system_configs 后台配置（v3 原文），
 * B 级清单因 v3 有权威定义而内置为常量（见 B_LEVEL_BLOCKING_CODES）。
 *
 * ownerRole 在 MARKET_PM|RD_PM|BOTH 之外补充 GROUP_LEADER（K01-K04 共担KPI 归集动作主责=产品组长，
 * I5 决策；LC09 R=系统自动，业务责任 A=产品组长，归组到 GROUP_LEADER）。
 * K01-K04 无独立阶段，挂 LIFECYCLE（上市后 6 个月归集窗口）。
 */
public final class ActionCatalog {

    private ActionCatalog() {
    }

    public static final List<ActionDef> ALL = List.of(
        // ===== 阶段一 概念 CONCEPT（12：深 11 / 轻 1；阻断 10）=====
        new ActionDef("C01", "市场机会与痛点调研", "CONCEPT", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("C02", "竞品分析", "CONCEPT", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("C03", "目标客户与细分市场定义", "CONCEPT", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("C04", "区域市场准入与需求差异调研", "CONCEPT", "MARKET_PM", "DEEP", true, "OVERSEAS", "", false, "", "AI_GENERATE"),
        new ActionDef("C05", "技术可行性预研", "CONCEPT", "RD_PM", "LIGHT", false, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("C06", "产品概念与差异化定位", "CONCEPT", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("C07", "成本/定价/毛利初步测算", "CONCEPT", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("C08", "销量预测与商业目标(四项基准值录入)", "CONCEPT", "MARKET_PM", "DEEP", true, "ALL", "BASELINE", false, "", "AI_DIRECT"),
        new ActionDef("C09", "项目等级评定与差异化系数", "CONCEPT", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("C10", "知识产权与合规预检(含专利FTO)", "CONCEPT", "RD_PM", "DEEP", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("C11", "Charter立项评审会", "CONCEPT", "BOTH", "DEEP", true, "ALL", "", false, "G1", "HUMAN_GATE"),
        new ActionDef("C12", "生物特征数据合规审查", "CONCEPT", "MARKET_PM", "DEEP", true, "ALL", "", true, "", "AI_GENERATE"),
        // ===== 阶段二 计划 PLAN（13：深 4 / 轻 9；阻断 5）=====
        new ActionDef("P01", "产品需求规格定义PRD", "PLAN", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("P02", "需求优先级排序与版本规划", "PLAN", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("P03", "总体技术方案与系统架构设计", "PLAN", "RD_PM", "LIGHT", false, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("P04", "ID/结构/硬件/固件方案设计", "PLAN", "RD_PM", "LIGHT", false, "HW", "", false, "", "AI_GENERATE"),
        new ActionDef("P05", "软件概要设计", "PLAN", "RD_PM", "LIGHT", false, "SW", "", false, "", "AI_GENERATE"),
        new ActionDef("P06", "解决方案场景设计与集成方案", "PLAN", "RD_PM", "LIGHT", false, "SOL", "", false, "", "AI_GENERATE"),
        new ActionDef("P07", "关键器件选型与供应链评估", "PLAN", "RD_PM", "LIGHT", false, "HW", "", false, "", "AI_GENERATE"),
        new ActionDef("P08", "项目计划与里程碑排期", "PLAN", "RD_PM", "LIGHT", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("P09", "资源与预算评估", "PLAN", "RD_PM", "LIGHT", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("P10", "认证与法规清单确认", "PLAN", "RD_PM", "LIGHT", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("P11", "风险识别与应对计划", "PLAN", "RD_PM", "LIGHT", false, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("P12", "差异化卖点确认与价值定价", "PLAN", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("P13", "差异化确认评审会", "PLAN", "BOTH", "DEEP", true, "ALL", "", false, "G2", "HUMAN_GATE"),
        // ===== 阶段三 开发 DEV（11：深 2 / 轻 9；阻断 2）=====
        new ActionDef("D01", "详细设计", "DEV", "RD_PM", "LIGHT", false, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("D02", "首版BOM冻结与采购下单", "DEV", "RD_PM", "LIGHT", false, "HW", "", false, "", "AI_DIRECT"),
        new ActionDef("D03", "手板/EVT样机制作", "DEV", "RD_PM", "LIGHT", false, "HW", "", false, "", "AI_DIRECT"),
        new ActionDef("D04", "软件开发与单元测试", "DEV", "RD_PM", "LIGHT", false, "SW", "", false, "", "AI_GENERATE"),
        new ActionDef("D05", "双周开发评审", "DEV", "BOTH", "DEEP", true, "ALL", "", false, "G3", "HUMAN_GATE"),
        new ActionDef("D06", "需求变更评估与审批", "DEV", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("D07", "模具开发与T1试模", "DEV", "RD_PM", "LIGHT", false, "HW", "", false, "", "AI_DIRECT"),
        new ActionDef("D08", "开发阶段成本复核", "DEV", "MARKET_PM", "LIGHT", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("D09", "内测版本发布Alpha", "DEV", "RD_PM", "LIGHT", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("D10", "解决方案联调与集成测试环境搭建", "DEV", "RD_PM", "LIGHT", false, "SOL", "", false, "", "AI_DIRECT"),
        new ActionDef("D11", "BioCV算法训练与评测", "DEV", "RD_PM", "LIGHT", false, "BIOCV", "FAR,FRR", true, "", "AI_DIRECT"),
        // ===== 阶段四 验证 VALID（12：深 6 / 轻 6；阻断 7）=====
        new ActionDef("V01", "DVT设计验证测试", "VALID", "RD_PM", "LIGHT", false, "HW", "", false, "", "AI_DIRECT"),
        new ActionDef("V02", "认证测试送检", "VALID", "RD_PM", "LIGHT", true, "ALL", "CERT_NO,CERT_DATE", false, "", "AI_DIRECT"),
        new ActionDef("V03", "Beta客户试用与反馈收集", "VALID", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("V04", "软件系统测试与缺陷收敛", "VALID", "RD_PM", "LIGHT", false, "SW", "", false, "", "AI_DIRECT"),
        new ActionDef("V05", "试产PVT/小批量", "VALID", "RD_PM", "LIGHT", false, "HW", "", false, "", "AI_DIRECT"),
        new ActionDef("V06", "量产准入评审", "VALID", "RD_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("V07", "包装说明书快速指南定稿", "VALID", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("V08", "售后与维修方案准备", "VALID", "MARKET_PM", "LIGHT", false, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("V09", "解决方案试点客户交付验证", "VALID", "MARKET_PM", "DEEP", true, "SOL", "", false, "", "AI_DIRECT"),
        new ActionDef("V10", "跨人种跨年龄适配验证", "VALID", "MARKET_PM", "DEEP", true, "BIOCV", "", true, "", "AI_DIRECT"),
        new ActionDef("V11", "平台兼容性与SDK-API对接验证", "VALID", "RD_PM", "LIGHT", false, "SOL", "", false, "", "AI_DIRECT"),
        new ActionDef("V12", "海外市场本地化适配验证", "VALID", "MARKET_PM", "DEEP", true, "OVERSEAS", "", false, "", "AI_DIRECT"),
        // ===== 阶段五 发布 LAUNCH（8：深 7 / 轻 1；阻断 7）=====
        new ActionDef("L01", "GTM上市策略", "LAUNCH", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("L02", "销售渠道与价格体系发布", "LAUNCH", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("L03", "销售工具包", "LAUNCH", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("L04", "销售与渠道培训", "LAUNCH", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("L05", "首批量产与备货", "LAUNCH", "RD_PM", "LIGHT", false, "HW", "", false, "", "AI_DIRECT"),
        new ActionDef("L06", "系统上架", "LAUNCH", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("L07", "GTM就绪评审", "LAUNCH", "BOTH", "DEEP", true, "ALL", "", false, "G4", "HUMAN_GATE"),
        new ActionDef("L08", "正式上市发布(录入上市日期)", "LAUNCH", "MARKET_PM", "DEEP", true, "ALL", "LAUNCH_DATE", false, "", "AI_DIRECT"),
        // ===== 阶段六 生命周期 LIFECYCLE（7：深 6 / 轻 1；阻断 5）=====
        // LC01 / LC03 已于 2026-10-03 退役（回款台账 / 奖金池功能块下线），不再作开发或验收依据
        new ActionDef("LC02", "上市后90天复盘", "LIFECYCLE", "BOTH", "DEEP", true, "ALL", "", false, "G5", "HUMAN_GATE"),
        new ActionDef("LC04", "双PM贡献度评定", "LIFECYCLE", "BOTH", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("LC05", "客户反馈与质量问题处理", "LIFECYCLE", "MARKET_PM", "DEEP", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("LC06", "版本迭代与维护发布", "LIFECYCLE", "RD_PM", "LIGHT", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("LC07", "生命周期状态维护", "LIFECYCLE", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("LC08", "停产评估与公告", "LIFECYCLE", "MARKET_PM", "DEEP", true, "ALL", "", false, "", "AI_GENERATE"),
        new ActionDef("LC09", "项目归档(系统自动,业务责任产品组长)", "LIFECYCLE", "GROUP_LEADER", "DEEP", true, "ALL", "", false, "", "AI_DIRECT"),
        // ===== 共担KPI 归集（4：深 4 / 轻 0；阻断 0，主责=产品组长 I5 决策）=====
        new ActionDef("K01", "销量出货量达成率归集", "LIFECYCLE", "GROUP_LEADER", "DEEP", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("K02", "渠道商覆盖达成率归集", "LIFECYCLE", "GROUP_LEADER", "DEEP", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("K03", "客户NPS调研归集", "LIFECYCLE", "GROUP_LEADER", "DEEP", false, "ALL", "", false, "", "AI_DIRECT"),
        new ActionDef("K04", "场景覆盖率归集", "LIFECYCLE", "GROUP_LEADER", "DEEP", false, "ALL", "", false, "", "AI_DIRECT"));

    /**
     * B 级项目阻断集（v3 权威定义）：Gate 关联动作 7 项 + P10/V02 法规认证 + C12 生物特征合规（全等级）= 10 项。
     * A 级清单 v3 未定死（约 25），由超管后台配置，此处不内置（不造无源数据）。
     */
    public static final List<String> B_LEVEL_BLOCKING_CODES = List.of(
        "C11", "P12", "P13", "D05", "L07", "L08", "LC02", "P10", "V02", "C12");

    /** 主 Prompt v3 L513-517 别名对：Z 系为同一动作第二编码（64+5 建制=69 历史口径） */
    public static final java.util.Map<String, String> ALIASES = java.util.Map.of(
        "Z01", "D11", "Z02", "V10", "Z03", "C12", "Z04", "V11", "Z05", "V12");

    /**
     * 别名归一：Z 系编码解析为权威编码；未知编码原样返回。
     *
     * <p><b>空值契约</b>：{@code code} 为 null 时返回 null。
     * {@link #ALIASES} 是 {@link java.util.Map#of} 构造的不可变 Map，
     * 其 {@code getOrDefault(null, …)} 会在 {@code MapN.probe} 内对 null 调 {@code hashCode()}
     * 而抛 NPE；而 {@code actionCode} 在 {@code AgentRunCreateReq} 契约上是**可空**的
     * （{@code ProjectAgentRunService#loadProjectFacts → docTypeOf} 会直接透传），
     * 故此处必须先挡 null，否则省略动作码的创建请求会 500。
     */
    public static String resolveCode(String code) {
        return code == null ? null : ALIASES.getOrDefault(code, code);
    }

    public static List<ActionDef> byStage(String stage) {
        return ALL.stream().filter(a -> a.stage().equals(stage)).toList();
    }

    /**
     * 取某个 Gate 在动作目录里对应的大阶段。
     *
     * @param gateCode G1 到 G5
     * @return 阶段编码；目录里没有这个 Gate 时为空
     */
    public static String stageOfGate(String gateCode) {
        if (gateCode == null || gateCode.isBlank()) {
            return null;
        }
        return ALL.stream().filter(a -> gateCode.equals(a.gate())).map(ActionDef::stage).findFirst().orElse(null);
    }

    /**
     * F3：阶段 → Gate 的逆向映射（{@link #stageOfGate} 的反函数）。
     *
     * <p>取目录里第一个挂了该 stage 的 Gate 动作的 gate 值。目录事实：
     * CONCEPT→G1（C11）、PLAN→G2（P13）、DEV→G3（D05）、LAUNCH→G4（L07）、LIFECYCLE→G5（LC02）；
     * <b>VALID 阶段没有任何动作挂 Gate</b>，故 {@code gateOfStage("VALID")} 返回 null。
     *
     * <p>空契约：{@code stage} 为 null/空白、或目录中该阶段无 Gate 动作时返回 null
     * （调用方据此判定「本阶段无出口 Gate」，不得据此阻断推进）。
     *
     * @param stage 阶段编码 CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE
     * @return Gate 编码 G1..G5；无则为 null
     */
    public static String gateOfStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        return ALL.stream()
            .filter(a -> stage.equals(a.stage()))
            .map(ActionDef::gate)
            .filter(g -> g != null && !g.isBlank())
            .findFirst()
            .orElse(null);
    }

    /**
     * 按编码取目录定义；Z 系别名先归一再查（P1-8.2 / AC-IPD-17）。
     *
     * @param code 权威码或 Z01–Z05 别名
     * @return 目录定义
     */
    public static ActionDef byCode(String code) {
        String resolved = resolveCode(code);
        return ALL.stream().filter(a -> a.code().equals(resolved)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("动作编码不存在: " + code));
    }

    /** BioCV 算法分类白名单（stage_actions.algo_type） */
    public static final java.util.Set<String> ALGO_TYPES = java.util.Set.of(
        "FINGERPRINT", "FACE", "PALM", "VEIN", "MULTI");

    /**
     * 校验算法分类；空串视为未填（由调用方决定是否必填）。
     *
     * @param algoType 分类码
     * @return 归一后的大写分类；null/blank → null
     */
    public static String normalizeAlgoType(String algoType) {
        if (algoType == null || algoType.isBlank()) {
            return null;
        }
        String t = algoType.trim().toUpperCase();
        if (!ALGO_TYPES.contains(t)) {
            throw new IpdBusinessException(
                "算法分类非法，允许: " + String.join("|", ALGO_TYPES));
        }
        return t;
    }

    /**
     * P1-3.2 / AC-PROD-02/03：动作是否适用于给定模板与目标市场。
     * <p>V11 全模板挂载（硬件轻管 / 方案深管由 {@link #expectedDepth} 决定）；
     * OVERSEAS 仅当目标市场含海外码；BIOCV 保持挂载（涉生物由 C12 补挂门控）。
     *
     * @param def               目录定义
     * @param templateType      HARDWARE|SOFTWARE|SOLUTION
     * @param targetMarketsJson 目标市场 JSON 数组字符串，可为 null
     * @return true=适用（初始 NOT_STARTED）；false=模板裁剪为 NA
     */
    public static boolean applicableTo(ActionDef def, String templateType, String targetMarketsJson) {
        if (def == null) {
            return false;
        }
        String code = resolveCode(def.code());
        // AC-IPD-21/22：V11 在硬件/软件/方案均挂载，深度另算
        if ("V11".equals(code)) {
            return true;
        }
        String applicable = def.applicable();
        if (applicable == null || applicable.isBlank() || "ALL".equals(applicable)) {
            return true;
        }
        return switch (applicable) {
            case "HW" -> "HARDWARE".equals(templateType);
            case "SW" -> "SOFTWARE".equals(templateType);
            case "SOL" -> "SOLUTION".equals(templateType);
            case "BIOCV" -> true;
            case "OVERSEAS" -> hasOverseasMarket(targetMarketsJson);
            default -> true;
        };
    }

    /**
     * 判断目标市场 JSON 是否包含海外市场（非 CN/国内）。
     *
     * @param targetMarketsJson 如 {@code ["SA","国内"]}
     * @return 含任一海外码则为 true
     */
    public static boolean hasOverseasMarket(String targetMarketsJson) {
        if (targetMarketsJson == null || targetMarketsJson.isBlank()) {
            return false;
        }
        String raw = targetMarketsJson.trim();
        String lower = raw.toLowerCase();
        if (lower.contains("海外") || lower.contains("overseas") || lower.contains("mea")) {
            return true;
        }
        String compact = raw.replaceAll("[\\[\\]\\s\"]", "");
        if (compact.isEmpty()) {
            return false;
        }
        for (String part : compact.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if ("CN".equalsIgnoreCase(p) || "国内".equals(p) || "CHINA".equalsIgnoreCase(p)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * P1-3.2：模板深度——SOLUTION 下 V11 升深管，其余沿用目录；C05 保持轻管。
     *
     * @param def          动作定义
     * @param templateType 模板
     * @return DEEP|LIGHT
     */
    public static String expectedDepth(ActionDef def, String templateType) {
        if (def == null) {
            return "LIGHT";
        }
        String code = resolveCode(def.code());
        if ("V11".equals(code) && "SOLUTION".equals(templateType)) {
            return "DEEP";
        }
        return def.depth();
    }

    /**
     * R236：动作码 → AI 文档类型（docType）静态映射。
     *
     * <p><b>为何放这里而不加 {@code ActionDef} 字段</b>：加字段要改 record 签名 + 69 行数据 +
     * 所有 {@code new ActionDef(...)} 引用点（契约 §7 B6 估算 +2 修改类）；本方法是纯派生数据，
     * 放在目录旁即 SSOT 同处，且由哨兵测试锁定。
     *
     * <p><b>词表口径</b>：市场族沿用既有 {@code MARKET_RESEARCH}（而非 {@code MRD}）——真库
     * {@code ai_documents} 已有 C01 历史草稿用该值，改词会断掉 RAG Phase-2 按 docType 的过滤
     * 连续性（{@code AiGenerationService} L143-146）；成熟通用体裁复用 {@link org.ruoyi.ipd.domain.PromptType}；版本规划、
     * 包装说明、销售物料等使用动作合同约定的具体交付物体裁。文档存储与审核沿用
     * ai_documents 同一状态链，不能把物料核对报告冒充外部实物已完成。
     *
     * @param code 动作码
     * @return docType；**无自然归类返回 {@code null}** —— {@code AiGenerationService} L145-147
     *         明写 docType 为空时 RAG 退回不按类型过滤（向后兼容），不得为此编造类型
     */
    public static String docTypeOf(String code) {
        // 字符串 switch 会对选择器调 hashCode()，null 选择器直接 NPE；先归一再判空。
        String resolved = resolveCode(code);
        if (resolved == null) {
            return null;
        }
        return switch (resolved) {
            case "C01", "C02", "C03", "C04", "C06" -> "MARKET_RESEARCH";
            case "C07", "P12" -> "BRD";
            case "C08" -> "CHARTER";
            case "C09" -> "PROJECT_GRADE_ASSESSMENT";
            case "C10" -> "IP_FTO_SEARCH_REPORT";
            case "P01" -> "PRD";
            case "P02" -> "VERSION_ROADMAP";
            case "C05" -> "TECHNICAL_FEASIBILITY_REPORT";
            case "P03" -> "SYSTEM_ARCHITECTURE";
            case "P04" -> "HARDWARE_DESIGN_PLAN";
            case "P05" -> "SOFTWARE_DESIGN_PLAN";
            case "P06" -> "SOLUTION_INTEGRATION_PLAN";
            case "P07" -> "COMPONENT_SUPPLY_ASSESSMENT";
            case "P08" -> "PROJECT_MILESTONE_PLAN";
            case "P09" -> "RESOURCE_BUDGET_PLAN";
            case "P11" -> "PROJECT_RISK_PLAN";
            case "D01" -> "DETAILED_DESIGN";
            case "V08" -> "AFTERSALES_REPAIR_PLAN";
            case "D04" -> "TEST_REPORT";
            case "C11", "C12", "D06", "V06", "P13", "D05", "L07" -> "REVIEW";
            case "V03" -> "BETA_FEEDBACK_REPORT";
            case "V07" -> "PACKAGING_USER_GUIDE";
            case "V09" -> "PILOT_DELIVERY_REPORT";
            case "V10" -> "DEMOGRAPHIC_VALIDATION_REPORT";
            case "V12" -> "LOCALIZATION_VALIDATION_REPORT";
            case "L01" -> "GTM_PLAN";
            case "L02" -> "CHANNEL_PRICE_POLICY";
            case "L03" -> "SALES_TOOLKIT";
            case "L04" -> "CHANNEL_TRAINING_MATERIAL";
            case "L06" -> "CATALOG_LISTING_RECORD";
            case "L08" -> "RELEASE_NOTE";
            case "LC02" -> "RETROSPECTIVE";
            case "LC04" -> "PM_CONTRIBUTION_ASSESSMENT";
            case "LC05" -> "CUSTOMER_QUALITY_FEEDBACK";
            case "LC07" -> "LIFECYCLE_CHANGE_RECORD";
            case "LC09" -> "PROJECT_ARCHIVE_PACKAGE";
            case "K01", "K02", "K03", "K04" -> "KPI_EVIDENCE_REPORT";
            case "LC08" -> "RELEASE_NOTE";
            default -> null;
        };
    }
}