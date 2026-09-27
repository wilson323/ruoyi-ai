package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.HandoverRecord;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.domain.RequirementChange;
import org.ruoyi.ipd.dto.AiSuggestReq;
import org.ruoyi.ipd.dto.AiSuggestResp;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementChangeMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * R227-C1（AI-FUSION Layer 2，2026-09-26，owner 拍板核心 5 页）：域内 AI 建议统一服务。
 *
 * <p>方案 §5.1：单一入口、多场景分发——按 scene 拉业务实体上下文拼 prompt 调 LLM，
 * 返回 markdown 供用户在页面上自行采纳。
 *
 * <p>强约束（方案 §5.1 + 产品圣经）：
 * <ul>
 *   <li>AI 输出**只返回 markdown，绝不写业务表**——采纳动作在前端由用户手动完成；</li>
 *   <li>BR-AI-05 越权：项目相关场景按 copilot 同款 project_members 可见性校验
 *       （不重新实现语义，复制 {@code AiCopilotService.assertProjectVisible} 的同构私有实现——
 *       copilot 正被兄弟会话在途修改，不动其文件避免撞车）；</li>
 *   <li>AI-审计三件套规约：每次调用（含降级/失败）落 {@code AI_SUGGEST} 审计行，
 *       aiAssisted=true / aiRole=suggestion / aiModel=真模型名或 "intent_match"（降级），
 *       只记 promptLen 不记原文（BR-AI-04）。</li>
 * </ul>
 *
 * <p>场景白名单（7 个，对应拍板的 5 个页面）：
 * workbench.next-step / workbench.risk-warning / project.summary.refresh /
 * project.create.suggest / demand.create.from-requirement /
 * gate.precheck-checklist / gate.conclusion-draft。
 *
 * <p>R232-P1-02（2026-09-27，CopilotKit 三能力落地 Phase 1）：增 structured 输出模式——
 * 4 结构化场景（{@link #STRUCTURED_SCENES}）响应体增 {@code card} 字段
 * （{type, version, data, sourceRefs}，母文件 §2.2 契约），3 轻场景保持纯文本零变化。
 * 纪律：
 * <ul>
 *   <li>**Schema Catalog 是唯一 schema 事实源**（禁硬编码）：4 卡类型定义 JSON 存
 *       {@code system_configs} 行 {@value #CARD_CATALOG_KEY}，data 字段名清单/类型/源表.源列
 *       全部以 Catalog 为准，Java 只做「Catalog 驱动投影」；Catalog 缺失/损坏 → 无 card 降级纯文本；</li>
 *   <li>**R3 铁律**：card.data 值全部经 sourceRefs 指向的业务表行回读组装
 *       （gate_* 三表 / projects / requirements），LLM 复述值不进 card（AI 建议正文仍在 markdown）；</li>
 *   <li>**C08 红线**：本节点只出建议数据，零业务表写入（审计行除外，审计亦只记 promptLen）；</li>
 *   <li>card 组装任何异常只降级为无 card（卡片层是增强不是依赖），不影响建议主流程。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiSuggestionService {

    /** 场景白名单（未命中直接 PARAM_INVALID，防任意 scene 拼 prompt 注入）。 */
    static final Set<String> SCENES = Set.of(
        "workbench.next-step", "workbench.risk-warning",
        "project.summary.refresh",
        "project.create.suggest", "demand.create.from-requirement",
        "gate.precheck-checklist", "gate.conclusion-draft",
        // AI-P3 场景包（2026-09-27）：需求查重路由 / 变更影响面 / 移交清单 / NL查报表（导航语义）
        "demand.dedupe", "change.impact-analyze", "handover.checklist-generate",
        "report.nl-query");

    /** AI-P3：素材驱动场景（无实体上下文也可出建议，但 userPrompt 必填作提问素材）。 */
    static final Set<String> USER_PROMPT_REQUIRED_SCENES = Set.of(
        "project.create.suggest", "demand.create.from-requirement",
        "demand.dedupe", "report.nl-query");

    /** R232-P1-02：结构化输出场景（响应体增 card 字段）；3 轻场景保持纯文本零变化。 */
    static final Set<String> STRUCTURED_SCENES = Set.of(
        "gate.precheck-checklist", "gate.conclusion-draft",
        "project.create.suggest", "demand.create.from-requirement");

    /** R232-P1-02：Schema Catalog 配置键（system_configs.config_key；value_type=JSON）。 */
    static final String CARD_CATALOG_KEY = "ai.suggest.cardCatalog";

    /** Catalog JSON 解析器（仅解析配置，不落任何业务数据）。 */
    private static final ObjectMapper CATALOG_JSON = new ObjectMapper();

    /** 建议输出 maxTokens（markdown 草稿比 copilot 短答长，比文档生成短）。 */
    static final int MAX_TOKENS = 1200;
    /** 建议调用超时（与 copilot 同档：同步交互 30s 上限）。 */
    static final int SUGGEST_TIMEOUT_MS = 30_000;
    /** prompt 总长上限（上下文渲染后截断，防极端数据撑爆）。 */
    static final int PROMPT_MAX = 30_000;

    private final AiModelConfigService modelConfigService;
    private final WorkbenchService workbenchService;
    private final AiGateway aiGateway;
    private final IAuditLogService auditLogService;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final GateReviewMapper gateReviewMapper;
    private final GateElementResultMapper gateElementResultMapper;
    private final ISystemConfigService systemConfigService;
    private final RequirementMapper requirementMapper;
    // AI-P3 场景包只读依赖：需求查重 / 变更影响面 / 移交清单
    private final RequirementChangeMapper requirementChangeMapper;
    private final HandoverMapper handoverMapper;

    /** 测试口注入固定时钟（同 copilot 模式）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public AiSuggestionService(AiModelConfigService modelConfigService,
                               WorkbenchService workbenchService,
                               AiGateway aiGateway,
                               IAuditLogService auditLogService,
                               ProjectMapper projectMapper,
                               ProjectMemberMapper projectMemberMapper,
                               GateReviewMapper gateReviewMapper,
                               GateElementResultMapper gateElementResultMapper,
                               ISystemConfigService systemConfigService,
                               RequirementMapper requirementMapper,
                               RequirementChangeMapper requirementChangeMapper,
                               HandoverMapper handoverMapper) {
        this.modelConfigService = modelConfigService;
        this.workbenchService = workbenchService;
        this.aiGateway = aiGateway;
        this.auditLogService = auditLogService;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.gateReviewMapper = gateReviewMapper;
        this.gateElementResultMapper = gateElementResultMapper;
        this.systemConfigService = systemConfigService;
        this.requirementMapper = requirementMapper;
        this.requirementChangeMapper = requirementChangeMapper;
        this.handoverMapper = handoverMapper;
    }

    AiSuggestionService withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /**
     * 统一入口：校验 → 越权 → 场景上下文 → prompt → LLM → 审计 → 返回 markdown。
     * 模型未启用走降级应答（同 copilot 语义：友好提示不抛 500，前端按 degraded 展示引导）。
     */
    public AiSuggestResp suggest(IpdActor actor, AiSuggestReq req) {
        long start = clock.millis();
        if (req == null || req.scene() == null || !SCENES.contains(req.scene())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "scene 必填且在白名单内：" + String.join(", ", SCENES.stream().sorted().toList()));
        }
        String scene = req.scene();
        // 创建/素材类场景必须有用户素材；项目类场景必须有 projectId
        if (USER_PROMPT_REQUIRED_SCENES.contains(scene)
            && (req.userPrompt() == null || req.userPrompt().isBlank())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "该场景 userPrompt 必填（原始素材）");
        }
        if (scene.startsWith("gate.")) {
            if (req.entityId() == null) {
                throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "gate 场景 entityId（gateId）必填");
            }
            List<GateReview> reviews = gateReviewMapper.selectList(new LambdaQueryWrapper<GateReview>()
                .eq(GateReview::getGateId, req.entityId()));
            if (reviews.isEmpty()) {
                throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "该 Gate 下无评审记录");
            }
            assertProjectVisible(actor, reviews.get(0).getProjectId());
        } else if (scene.startsWith("workbench.")) {
            assertProjectVisible(actor, req.projectId()); // 可空=全局，同 copilot 语义
        } else if (scene.equals("change.impact-analyze")) {
            loadChangeForActor(actor, req.entityId()); // AI-P3：entityId=changeId，缺失/越权均 NOT_FOUND
        } else if (scene.equals("handover.checklist-generate")) {
            loadHandoverForActor(actor, req.entityId()); // AI-P3：entityId=handoverId，仅限归属项目成员/移交双方/超管
        } else if (scene.equals("report.nl-query")) {
            assertProjectVisible(actor, req.projectId()); // 全局可空=跨项目报告导航；带项目则校验可见性
        } else {
            if (req.projectId() == null) {
                throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "该场景 projectId 必填");
            }
            assertProjectVisible(actor, req.projectId());
        }

        String context = renderContext(actor, req);
        String prompt = composePrompt(scene, context, req.userPrompt());

        AiModelConfig config;
        try {
            config = modelConfigService.currentEnabled();
        } catch (IpdBusinessException ex) {
            long latency = clock.millis() - start;
            audit(actor, req, latency, 0, 0,
                "FAIL:" + (ex.getErrorCode() == null ? "UNKNOWN" : ex.getErrorCode().name()), null, null);
            return AiSuggestResp.degraded(scene,
                "AI 建议暂未启用：未配置生效的 AI 模型。请联系超管在「AI 模型配置」启用。", latency);
        }

        AiChatResult result = aiGateway.chat(
            new AiTestConfig(config.getProvider(), config.getEndpointUrl(),
                modelConfigService.decryptApiKey(config), config.getModelName(), SUGGEST_TIMEOUT_MS),
            prompt, MAX_TOKENS, new BigDecimal("0.50"));

        long latency = clock.millis() - start;
        if (!result.success()) {
            audit(actor, req, latency, 0, 0,
                "FAIL:" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()), config.getModelName(), null);
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "AI 建议暂不可用：" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()));
        }
        // R232-P1-02/03：4 结构化场景组装 card（Catalog 驱动 + R2 双向白名单 + R3 源回读对账）；失败只降级无 card
        AiSuggestResp.Card card = buildCard(actor, req, config.getModelName());
        audit(actor, req, latency, result.promptTokens(), result.completionTokens(), "ok", config.getModelName(),
            card == null ? null : card.type());
        return new AiSuggestResp(scene, result.content() == null ? "" : result.content(),
            config.getModelName(), result.promptTokens(), result.completionTokens(), latency, false, card);
    }

    // ---- R232-P1-02/03 结构化输出（cardPayload 组装器：R2 白名单双向校验 + R3 回读对账） ----

    /** gate 事实字段分组（schema 驱动采集：只查声明字段需要的源表，只读）。 */
    private static final Set<String> GATE_REVIEW_FIELDS = Set.of("gateCode", "round", "reviewCount", "reviews");
    private static final Set<String> GATE_RESULT_FIELDS =
        Set.of("totalElements", "items", "passCount", "conditionalCount", "failCount");

    /** requirements 需求池展示上限（与 renderContext「仅渲染前 20 条」同口径截断）。 */
    private static final int REQUIREMENT_POOL_LIMIT = 20;

    /**
     * 结构化卡片组装：Catalog 从 system_configs 读取（唯一 schema 事实源）→ 业务表行回读事实
     * （R3）→ Catalog 驱动投影成 {type, version, data, sourceRefs}。非结构化场景恒 null；
     * Catalog 缺失/损坏/组装异常只降级为无 card（纯文本路径永不删）。
     */
    private AiSuggestResp.Card buildCard(IpdActor actor, AiSuggestReq req, String aiModel) {
        if (!STRUCTURED_SCENES.contains(req.scene())) {
            return null;
        }
        try {
            String catalogJson = systemConfigService.getValue(CARD_CATALOG_KEY, "");
            if (catalogJson == null || catalogJson.isBlank()) {
                return null;
            }
            JsonNode def = findCardDef(parseCatalog(catalogJson), req.scene());
            if (def == null || !def.hasNonNull("type") || !def.hasNonNull("version") || !def.hasNonNull("fields")) {
                return null;
            }
            Map<String, Object> sourceRefs = new LinkedHashMap<>();
            // 入参侧（R2 双向①的采集面）：事实按 Catalog schema 声明采集，schema 外字段在采集侧即无入口
            Map<String, Object> facts = collectCardFacts(req, def, sourceRefs);
            return buildCardChecked(actor, req, catalogJson, facts, sourceRefs, aiModel);
        } catch (RuntimeException ex) {
            log.warn("card 组装失败降级纯文本 scene={} err={}", req.scene(), ex.toString());
            return null;
        }
    }

    /**
     * cardPayload 组装器（R232-P1-03，测试直入缝）：对**不可信** facts/sourceRefs 做 R2 白名单双向校验
     * （schema 外字段一律丢弃 + 落审计）→ 组装 → R3 sourceRefs 回读对账（数值逐字段对账）。
     * sourceRefs 缺失 / 回读失败 / 对账不一致 → 拒出卡降级纯文本（不得用 LLM 复述值兜底）。
     */
    AiSuggestResp.Card buildCardChecked(IpdActor actor, AiSuggestReq req, String catalogJson,
                                        Map<String, Object> facts, Map<String, Object> sourceRefs, String aiModel) {
        JsonNode def = findCardDef(parseCatalog(catalogJson), req.scene());
        if (def == null || !def.hasNonNull("type") || !def.hasNonNull("version") || !def.hasNonNull("fields")) {
            return null;
        }
        String cardType = def.get("type").asText();
        List<String> dropped = new ArrayList<>();
        // R2 双向①（入参/组装侧防御）：schema 外字段一律丢弃 + 留痕（照抄 FILL_FIELD_WHITELIST/filterFillFields
        // 校验模式；白名单来源=Catalog fields/itemFields/sourceRefs 声明，禁硬编码字段表）
        Map<String, Object> data = filterCardData(def.get("fields"), facts, dropped, "");
        Map<String, Object> refs = filterSourceRefs(def, sourceRefs, dropped);
        // R2 双向②（出卡前过滤，后端权威）：对已组装载荷再过同一白名单，防绕过组装侧直塞
        data = filterCardData(def.get("fields"), data, dropped, "");
        refs = filterSourceRefs(def, refs, dropped);
        AiSuggestResp.Card card = new AiSuggestResp.Card(cardType, def.get("version").asInt(), data, refs);
        if (!dropped.isEmpty()) {
            Collections.sort(dropped);
            auditCardEvent(actor, req, aiModel, cardType, "SCHEMA_DROP", dropped);
        }
        String reject = reconcileCard(def, req, card, refs);
        if (reject != null) {
            auditCardEvent(actor, req, aiModel, cardType, "CARD_REJECT:" + reject, List.of());
            return null;
        }
        return card;
    }

    /**
     * R2 白名单双向校验（照抄 {@code AiCopilotService.FILL_FIELD_WHITELIST} / {@code filterFillFields}
     * 先例的白名单结构与丢弃写法）：schema 外字段一律丢弃并记入 dropped（字段路径）；同时承担 data
     * 投影职责——白名单内字段按 Catalog 顺序产出（源值缺失=null 占位，字段名集合与 Catalog 恒等），
     * 数组元素按 itemFields 子白名单投影。
     */
    static Map<String, Object> filterCardData(JsonNode fieldsNode, Map<String, Object> raw,
                                              List<String> dropped, String prefix) {
        Map<String, Object> kept = new LinkedHashMap<>();
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return kept;
        }
        Set<String> allowed = fieldNames(fieldsNode);
        if (raw != null) {
            for (String k : raw.keySet()) {
                if (!allowed.contains(k) && dropped != null) {
                    dropped.add(prefix + k);
                }
            }
        }
        for (JsonNode field : fieldsNode) {
            String name = field.path("name").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            kept.put(name, filterItemValue(field, raw == null ? null : raw.get(name), dropped, prefix + name));
        }
        return kept;
    }

    /** 数组字段按 itemFields 子白名单投影：schema 外子字段一律丢弃 + 留痕（items.aiConfidence 路径形态）；非数组原样返回。 */
    private static Object filterItemValue(JsonNode field, Object value, List<String> dropped, String path) {
        JsonNode itemFields = field.get("itemFields");
        if (itemFields == null || !itemFields.isArray() || !(value instanceof List<?> list)) {
            return value;
        }
        Set<String> subAllowed = fieldNames(itemFields);
        List<Object> projected = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                projected.add(item);
                continue;
            }
            for (Object k : m.keySet()) {
                String name = String.valueOf(k);
                if (!subAllowed.contains(name) && dropped != null) {
                    dropped.add(path + "." + name);
                }
            }
            Map<String, Object> subKept = new LinkedHashMap<>();
            for (JsonNode f : itemFields) {
                String n = f.path("name").asText(null);
                if (n != null) {
                    subKept.put(n, m.get(n));
                }
            }
            projected.add(subKept);
        }
        return projected;
    }

    /** sourceRefs 同样按 Catalog 声明键白名单投影（schema 外键丢弃 + 留痕 sourceRefs.x）。 */
    static Map<String, Object> filterSourceRefs(JsonNode def, Map<String, Object> raw, List<String> dropped) {
        Map<String, Object> kept = new LinkedHashMap<>();
        if (raw == null) {
            return kept;
        }
        Set<String> allowed = refKeys(def);
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (allowed.contains(e.getKey())) {
                kept.put(e.getKey(), e.getValue());
            } else if (dropped != null) {
                dropped.add("sourceRefs." + e.getKey());
            }
        }
        return kept;
    }

    /**
     * R3 回读对账（铁律）：card.data 全部字段经 sourceRefs 重新回读真实业务表行，逐字段与源行投影值对账。
     * sourceRefs 缺必备键 / 回读失败（DB 异常、行不齐、行不属该锚）/ 任一字段数值不一致 → 返回拒绝原因
     * （拒出卡降级纯文本；禁止用 LLM 复述值兜底）；全部一致返回 null。只读，零业务表写入（C08）。
     */
    private String reconcileCard(JsonNode def, AiSuggestReq req, AiSuggestResp.Card card, Map<String, Object> sourceRefs) {
        try {
            for (String key : refKeys(def)) {
                if (!sourceRefs.containsKey(key) || sourceRefs.get(key) == null) {
                    return "source_refs_missing:" + key;
                }
            }
            Set<String> wanted = fieldNames(def.get("fields"));
            Map<String, Object> facts2 = new LinkedHashMap<>();
            if (req.scene().startsWith("gate.")) {
                List<Long> reviewIds = longIds(sourceRefs.get("reviewIds"));
                List<Long> resultIds = longIds(sourceRefs.get("elementResultIds"));
                List<GateReview> reviews = reviewIds.isEmpty() ? List.of()
                    : gateReviewMapper.selectList(new LambdaQueryWrapper<GateReview>().in(GateReview::getId, reviewIds));
                List<GateElementResult> results = resultIds.isEmpty() ? List.of()
                    : gateElementResultMapper.selectList(new LambdaQueryWrapper<GateElementResult>().in(GateElementResult::getId, resultIds));
                if (reviews.size() != reviewIds.size() || results.size() != resultIds.size()) {
                    return "reread_incomplete";
                }
                for (GateReview r : reviews) {
                    if (!req.entityId().equals(r.getGateId())) {
                        return "reread_foreign_row";
                    }
                }
                for (GateElementResult r : results) {
                    if (!req.entityId().equals(r.getGateId())) {
                        return "reread_foreign_row";
                    }
                }
                projectGateFacts(wanted, reviews, results, facts2);
            } else {
                Project project = projectMapper.selectById(req.projectId());
                if (project == null) {
                    return "reread_incomplete";
                }
                projectProjectFacts(wanted, project, facts2);
                if (req.scene().equals("demand.create.from-requirement")) {
                    List<Long> ids = longIds(sourceRefs.get("requirementIds"));
                    List<Requirement> pool = ids.isEmpty() ? List.of()
                        : requirementMapper.selectList(new LambdaQueryWrapper<Requirement>().in(Requirement::getId, ids));
                    if (pool.size() != ids.size()) {
                        return "reread_incomplete";
                    }
                    for (Requirement r : pool) {
                        if (!req.projectId().equals(r.getProjectId())) {
                            return "reread_foreign_row";
                        }
                    }
                    projectRequirementFacts(wanted, shownRequirements(pool), facts2);
                }
            }
            // 数值逐字段对账（两跳同一投影口径；任一字段不等=对账失败，拒出卡）
            Map<String, Object> data2 = filterCardData(def.get("fields"), facts2, null, "");
            for (Map.Entry<String, Object> e : card.data().entrySet()) {
                if (!Objects.equals(e.getValue(), data2.get(e.getKey()))) {
                    return "value_mismatch:" + e.getKey();
                }
            }
            return null;
        } catch (RuntimeException ex) {
            log.warn("R3 回读对账异常拒出卡 scene={} err={}", req.scene(), ex.toString());
            return "reread_failed:" + ex.getClass().getSimpleName();
        }
    }

    /**
     * 事实源回读（R3 第一跳）：按 Catalog schema 声明**只收白名单内字段**（无声明字段不进 facts），
     * 并登记 sourceRefs 行 id 引用（只收 Catalog 声明键）。gate.* 复用 renderContext 同款查询
     * （gate_reviews / gate_element_results 按 gateId）；创建类场景锚定 projects 行；demand 场景补
     * requirements 需求池行（按 projectId，前 20 条同口径截断）。只读，零业务表写入（C08）。
     */
    private Map<String, Object> collectCardFacts(AiSuggestReq req, JsonNode def, Map<String, Object> sourceRefs) {
        String scene = req.scene();
        Set<String> wanted = fieldNames(def.get("fields"));
        Set<String> wantedRefs = refKeys(def);
        Map<String, Object> facts = new LinkedHashMap<>();
        if (scene.startsWith("gate.")) {
            List<GateReview> reviews = List.of();
            List<GateElementResult> results = List.of();
            if (wantedRefs.contains("reviewIds") || containsAny(wanted, GATE_REVIEW_FIELDS)) {
                reviews = gateReviewMapper.selectList(new LambdaQueryWrapper<GateReview>()
                    .eq(GateReview::getGateId, req.entityId()));
            }
            if (wantedRefs.contains("elementResultIds") || containsAny(wanted, GATE_RESULT_FIELDS)) {
                results = gateElementResultMapper.selectList(new LambdaQueryWrapper<GateElementResult>()
                    .eq(GateElementResult::getGateId, req.entityId()));
            }
            projectGateFacts(wanted, reviews, results, facts);
            if (wantedRefs.contains("gateId")) {
                sourceRefs.put("gateId", req.entityId());
            }
            if (wantedRefs.contains("reviewIds")) {
                sourceRefs.put("reviewIds", reviews.stream().map(GateReview::getId).sorted().toList());
            }
            if (wantedRefs.contains("elementResultIds")) {
                sourceRefs.put("elementResultIds", results.stream().map(GateElementResult::getId).sorted().toList());
            }
            return facts;
        }
        Project project = requireProject(req.projectId());
        projectProjectFacts(wanted, project, facts);
        if (wantedRefs.contains("projectId")) {
            sourceRefs.put("projectId", req.projectId());
        }
        if (scene.equals("demand.create.from-requirement")) {
            List<Requirement> pool = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getProjectId, req.projectId()));
            List<Requirement> shown = shownRequirements(pool);
            projectRequirementFacts(wanted, shown, facts);
            if (wantedRefs.contains("requirementIds")) {
                sourceRefs.put("requirementIds", shown.stream().map(Requirement::getId).toList());
            }
        }
        return facts;
    }

    /**
     * gate 事实投影（R3 两跳共用：首过组装与 sourceRefs 回读对账走同一投影；行按 id 定序保证两跳逐字段可比）。
     * 值全部来自业务表行（gate_reviews / gate_element_results），LLM 复述值无入口。
     */
    static void projectGateFacts(Set<String> wanted, List<GateReview> reviews, List<GateElementResult> results,
                                 Map<String, Object> facts) {
        List<GateReview> rs = new ArrayList<>(reviews);
        rs.sort(Comparator.comparing(GateReview::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<GateElementResult> es = new ArrayList<>(results);
        es.sort(Comparator.comparing(GateElementResult::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (!rs.isEmpty()) {
            GateReview latest = rs.get(rs.size() - 1); // id 最大行=最新一轮（定序保证两跳一致）
            if (wanted.contains("gateCode")) {
                facts.put("gateCode", latest.getGateCode());
            }
            if (wanted.contains("round")) {
                facts.put("round", latest.getRound());
            }
        }
        if (wanted.contains("reviewCount")) {
            facts.put("reviewCount", rs.size());
        }
        if (wanted.contains("totalElements")) {
            facts.put("totalElements", es.size());
        }
        if (wanted.contains("items")) {
            List<Map<String, Object>> items = new ArrayList<>(es.size());
            for (GateElementResult r : es) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("elementId", r.getElementId());
                m.put("result", r.getResult());
                m.put("conditionNote", r.getConditionNote());
                m.put("evidenceRef", r.getEvidenceRef());
                m.put("leftoverStatus", r.getLeftoverStatus());
                items.add(m);
            }
            facts.put("items", items);
        }
        if (wanted.contains("reviews")) {
            List<Map<String, Object>> reviewRows = new ArrayList<>(rs.size());
            for (GateReview r : rs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reviewerType", r.getReviewerType());
                m.put("decision", r.getDecision());
                m.put("opinion", r.getOpinion());
                m.put("round", r.getRound());
                reviewRows.add(m);
            }
            facts.put("reviews", reviewRows);
        }
        if (wanted.contains("passCount")) {
            facts.put("passCount", countResult(es, "PASS"));
        }
        if (wanted.contains("conditionalCount")) {
            facts.put("conditionalCount", countResult(es, "CONDITIONAL"));
        }
        if (wanted.contains("failCount")) {
            facts.put("failCount", countResult(es, "FAIL"));
        }
    }

    /** projects 行事实投影（R3 两跳共用；值全部来自 projects 行，LLM 复述值无入口）。 */
    static void projectProjectFacts(Set<String> wanted, Project p, Map<String, Object> facts) {
        if (wanted.contains("contextProjectId")) {
            facts.put("contextProjectId", p.getId());
        }
        if (wanted.contains("contextProjectCode")) {
            facts.put("contextProjectCode", p.getCode());
        }
        if (wanted.contains("contextProjectName")) {
            facts.put("contextProjectName", p.getName());
        }
        if (wanted.contains("contextCurrentStage")) {
            facts.put("contextCurrentStage", p.getCurrentStage());
        }
        if (wanted.contains("contextProductId")) {
            facts.put("contextProductId", p.getProductId());
        }
    }

    /** requirements 池事实投影（R3 两跳共用；值全部来自 requirements 行）。 */
    static void projectRequirementFacts(Set<String> wanted, List<Requirement> shown, Map<String, Object> facts) {
        if (!wanted.contains("requirements")) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>(shown.size());
        for (Requirement r : shown) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requirementId", r.getId());
            m.put("title", r.getTitle());
            m.put("status", r.getStatus());
            m.put("source", r.getSource());
            rows.add(m);
        }
        facts.put("requirements", rows);
    }

    /** requirements 展示集（id 定序 + 前 20 条同口径截断；两跳共用保证对账可比）。 */
    static List<Requirement> shownRequirements(List<Requirement> pool) {
        List<Requirement> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparing(Requirement::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted.size() > REQUIREMENT_POOL_LIMIT
            ? new ArrayList<>(sorted.subList(0, REQUIREMENT_POOL_LIMIT)) : sorted;
    }

    /**
     * R2/R3 卡片事件审计（AI-审计三件套规约 §2 Layer2：aiRole=suggestion ∈ 7 值白名单、prompt 只记
     * promptLen 不落原文（BR-AI-04））：schema 外字段丢弃（SCHEMA_DROP）/ 拒出卡（CARD_REJECT:*）
     * 这类安全事件单独留痕；正常出卡不加行（轻场景与正常路径零影响）。
     */
    private void auditCardEvent(IpdActor actor, AiSuggestReq req, String aiModel, String cardType,
                                String status, List<String> droppedFields) {
        String json = AuditEventData.json(
            "aiAssisted", true,
            "aiModel", aiModel == null || aiModel.isBlank() ? "intent_match" : aiModel,
            "aiRole", "suggestion",
            "scene", req.scene(),
            "projectId", req.projectId(),
            "entityId", req.entityId(),
            "cardType", cardType,
            "status", status,
            "droppedFields", droppedFields,
            "promptLen", req.userPrompt() == null ? 0 : req.userPrompt().length());
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id()).operatorName(actor.name()).operatorRole(actor.role())
            .action("AI_SUGGEST").entityType("AI_SUGGESTION")
            .afterData(json)
            .build());
    }

    // ---- Catalog schema 工具（白名单来源=Catalog，禁硬编码字段表） ----

    /** Catalog JSON → 树；解析失败返回 null（降级无 card，文本路径永不删）。 */
    private static JsonNode parseCatalog(String catalogJson) {
        try {
            return CATALOG_JSON.readTree(catalogJson);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** fields/itemFields JSON 数组 → 字段名白名单。 */
    private static Set<String> fieldNames(JsonNode fieldsNode) {
        Set<String> names = new LinkedHashSet<>();
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode f : fieldsNode) {
                String n = f.path("name").asText(null);
                if (n != null && !n.isBlank()) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    /** Catalog 卡定义的 sourceRefs 声明键白名单。 */
    private static Set<String> refKeys(JsonNode def) {
        Set<String> keys = new LinkedHashSet<>();
        JsonNode declared = def.get("sourceRefs");
        if (declared != null && declared.isArray()) {
            for (JsonNode k : declared) {
                String s = k.asText(null);
                if (s != null) {
                    keys.add(s);
                }
            }
        }
        return keys;
    }

    private static boolean containsAny(Set<String> wanted, Set<String> group) {
        for (String g : group) {
            if (wanted.contains(g)) {
                return true;
            }
        }
        return false;
    }

    /** sourceRefs 值 → id 列表（回读用；非数值元素忽略）。 */
    private static List<Long> longIds(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        return ids;
    }

    /**
     * Catalog 驱动投影（schema 事实源唯一）：只产出 Catalog 字段清单内的 data 字段（含数组 itemFields
     * 子字段白名单），schema 外字段一律丢弃（可选 droppedSink 记录丢弃路径，R2 留痕）；sourceRefs 同样
     * 按 Catalog 声明的键白名单投影。Catalog 未登记该 scene / 结构不合法 → null。
     */
    static AiSuggestResp.Card assembleCard(String scene, String catalogJson,
                                           Map<String, Object> facts, Map<String, Object> sourceRefs) {
        return assembleCard(scene, catalogJson, facts, sourceRefs, null);
    }

    static AiSuggestResp.Card assembleCard(String scene, String catalogJson,
                                           Map<String, Object> facts, Map<String, Object> sourceRefs,
                                           List<String> droppedSink) {
        JsonNode def = findCardDef(parseCatalog(catalogJson), scene);
        if (def == null || !def.hasNonNull("type") || !def.hasNonNull("version") || !def.hasNonNull("fields")) {
            return null;
        }
        Map<String, Object> data = filterCardData(def.get("fields"), facts, droppedSink, "");
        Map<String, Object> refs = filterSourceRefs(def, sourceRefs, droppedSink);
        return new AiSuggestResp.Card(def.get("type").asText(), def.get("version").asInt(), data, refs);
    }

    private static JsonNode findCardDef(JsonNode root, String scene) {
        if (root == null) {
            return null;
        }
        JsonNode cards = root.get("cards");
        if (cards == null || !cards.isArray()) {
            return null;
        }
        for (JsonNode c : cards) {
            if (scene.equals(c.path("scene").asText(null))) {
                return c;
            }
        }
        return null;
    }

    private static long countResult(List<GateElementResult> results, String target) {
        return results.stream().filter(r -> target.equals(r.getResult())).count();
    }

    // ---- 场景上下文渲染（包私有静态便于行为测试直渲断言） ----

    /** 按场景拉业务上下文（渲染为纯文本块，进 prompt 不进审计）。 */
    private String renderContext(IpdActor actor, AiSuggestReq req) {
        String scene = req.scene();
        if (scene.startsWith("workbench.")) {
            return renderWorkbenchContext(workbenchService.summary(actor, req.projectId()));
        }
        if (scene.equals("report.nl-query")) {
            return REPORT_CATALOG; // AI-P3：NL查报表=导航语义，静态目录注入（不执行任意查询，不触 text2sql）
        }
        if (scene.equals("change.impact-analyze")) {
            RequirementChange c = loadChangeForActor(actor, req.entityId());
            return renderChangeContext(c,
                c.getRequirementId() == null ? null : requirementMapper.selectById(c.getRequirementId()),
                requireProject(c.getProjectId()));
        }
        if (scene.equals("handover.checklist-generate")) {
            HandoverRecord h = loadHandoverForActor(actor, req.entityId());
            return renderHandoverContext(h,
                h.getProjectId() == null ? null : requireProject(h.getProjectId()));
        }
        if (scene.equals("demand.dedupe")) {
            List<Requirement> existing = requirementMapper.selectList(
                new LambdaQueryWrapper<Requirement>().eq(Requirement::getProjectId, req.projectId()));
            return renderDedupeContext(existing);
        }
        if (scene.equals("project.summary.refresh")) {
            return renderProjectContext(requireProject(req.projectId()));
        }
        if (scene.startsWith("gate.")) {
            List<GateReview> reviews = gateReviewMapper.selectList(new LambdaQueryWrapper<GateReview>()
                .eq(GateReview::getGateId, req.entityId()));
            GateReview latest = reviews.get(reviews.size() - 1);
            StringBuilder sb = new StringBuilder(renderProjectContext(requireProject(latest.getProjectId())));
            for (GateReview r : reviews) {
                sb.append("- 评审行：gate=").append(nullToDash(r.getGateCode()))
                    .append("，轮次=").append(r.getRound())
                    .append("，签署人类型=").append(nullToDash(r.getReviewerType()))
                    .append("，决定=").append(nullToDash(r.getDecision()))
                    .append("，意见=").append(nullToDash(r.getOpinion())).append('\n');
            }
            List<GateElementResult> results = gateElementResultMapper.selectList(
                new LambdaQueryWrapper<GateElementResult>()
                    .eq(GateElementResult::getGateId, req.entityId()));
            int shown = 0;
            for (GateElementResult r : results) {
                if (shown++ >= 20) {
                    sb.append("- （要素结果共 ").append(results.size()).append(" 条，仅渲染前 20 条）\n");
                    break;
                }
                sb.append("- 要素#").append(r.getElementId())
                    .append("：结果=").append(nullToDash(r.getResult()))
                    .append("，备注=").append(nullToDash(r.getConditionNote())).append('\n');
            }
            return sb.toString();
        }
        // 创建类场景无实体上下文，素材在 userPrompt
        return "";
    }

    // ---- AI-P3 场景包：实体装载（越权即 NOT_FOUND，与 gate 同构语义） ----

    /** change.impact-analyze：entityId=requirement_changes 主键；无单/项目不可见均 NOT_FOUND。 */
    private RequirementChange loadChangeForActor(IpdActor actor, Long changeId) {
        if (changeId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "change.impact-analyze 场景 entityId（changeId）必填");
        }
        RequirementChange c = requirementChangeMapper.selectById(changeId);
        if (c == null || c.getProjectId() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "变更单不存在");
        }
        assertProjectVisible(actor, c.getProjectId());
        return c;
    }

    /** handover.checklist-generate：entityId=handover_records 主键；项目移交经可见性校验，非项目类仅限移交双方/超管。 */
    private HandoverRecord loadHandoverForActor(IpdActor actor, Long handoverId) {
        if (handoverId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "handover.checklist-generate 场景 entityId（handoverId）必填");
        }
        HandoverRecord h = handoverMapper.selectById(handoverId);
        if (h == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "移交记录不存在");
        }
        if (h.getProjectId() != null) {
            assertProjectVisible(actor, h.getProjectId());
        }
        boolean party = actor.id() != null
            && (actor.id().equals(h.getFromPersonId()) || actor.id().equals(h.getToPersonId()));
        if (!"SUPER_ADMIN".equals(actor.role()) && !party && h.getProjectId() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "移交记录不可见");
        }
        return h;
    }

    /** AI-P3 report.nl-query：静态报告目录（与 ReportController 四端点同源；改端点需同步此常量）。 */
    static final String REPORT_CATALOG =
        "- GET /api/v1/report/project-summary?month=YYYY-MM —— 项目绩效汇总列表（分页）\n"
        + "- GET /api/v1/report/export/allowance?month=YYYY-MM —— 补贴台账导出（xlsx）\n"
        + "- GET /api/v1/report/export/bonus?projectId= —— 奖金分配导出（xlsx）\n"
        + "- GET /api/v1/report/export/project?month=YYYY-MM —— 项目汇总导出（xlsx）\n";

    /** demand.dedupe：同项目既有需求清单（前 30 条，标题+状态；正文不进 prompt 防超长）。 */
    static String renderDedupeContext(List<Requirement> existing) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Requirement r : existing) {
            if (shown++ >= 30) {
                sb.append("- （既有需求共 ").append(existing.size()).append(" 条，仅渲染前 30 条）\n");
                break;
            }
            sb.append("- 需求#").append(r.getId())
                .append("：").append(nullToDash(r.getTitle()))
                .append("（状态 ").append(nullToDash(r.getStatus())).append("）\n");
        }
        if (shown == 0) {
            sb.append("- （该项目暂无既有需求，查重基准为空）\n");
        }
        return sb.toString();
    }

    static String renderChangeContext(RequirementChange c, Requirement reqRow, Project p) {
        StringBuilder sb = new StringBuilder(renderProjectContext(p)).append('\n');
        sb.append("- 变更单#").append(c.getId())
            .append("：类型 ").append(nullToDash(c.getChangeType()))
            .append("，状态 ").append(nullToDash(c.getStatus()))
            .append("，原因 ").append(abbrev(c.getReason(), 300)).append('\n');
        if (reqRow != null) {
            sb.append("- 关联需求#").append(reqRow.getId())
                .append("：").append(nullToDash(reqRow.getTitle()))
                .append("（状态 ").append(nullToDash(reqRow.getStatus())).append("）\n");
        }
        sb.append("- 变更前快照：").append(abbrev(c.getBeforeSnapshot(), 500)).append('\n');
        sb.append("- 变更后快照：").append(abbrev(c.getAfterSnapshot(), 500)).append('\n');
        return sb.toString();
    }

    static String renderHandoverContext(HandoverRecord h, Project p) {
        StringBuilder sb = new StringBuilder();
        if (p != null) {
            sb.append(renderProjectContext(p)).append('\n');
        }
        sb.append("- 移交记录#").append(h.getId())
            .append("：类型 ").append(nullToDash(h.getHandoverType()))
            .append("，角色 ").append(nullToDash(h.getHandoverRole()))
            .append("，状态 ").append(nullToDash(h.getStatus()))
            .append("，移交人 personId=").append(h.getFromPersonId())
            .append("，承接人 personId=").append(h.getToPersonId())
            .append("，截止 ").append(h.getDeadlineAt() == null ? "-" : h.getDeadlineAt())
            .append("，说明 ").append(abbrev(h.getNote(), 300)).append('\n');
        return sb.toString();
    }

    /** 快照/长文本截断（防单字段撑爆 prompt；null 安全）。 */
    static String abbrev(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** workbench summary → 文本块（当前推进 + 待办前 10 条 + 统计）；渲染口径同 copilot 意图兜底路径。 */
    @SuppressWarnings("unchecked")
    static String renderWorkbenchContext(Map<String, Object> summary) {
        StringBuilder sb = new StringBuilder();
        Object adv = summary.get("currentAdvance");
        if (adv instanceof Map<?, ?> m && !m.isEmpty()) {
            Map<String, Object> a = (Map<String, Object>) m;
            sb.append("- 当前推进：项目 ").append(nullToDash(str(a.get("projectCode"))))
                .append(' ').append(nullToDash(str(a.get("projectName"))))
                .append("，阶段 ").append(nullToDash(str(a.get("currentStage"))))
                .append("，下一动作 ").append(nullToDash(str(a.get("actionName")))).append('\n');
        }
        if (summary.get("stats") instanceof Map<?, ?> s) {
            sb.append("- 统计：待处理 ").append(s.get("pending"))
                .append("，超期 ").append(s.get("overdue"))
                .append("，未完成 ").append(s.get("completed")).append('\n');
        }
        if (summary.get("tasks") instanceof List<?> tasks) {
            int i = 0;
            for (Object t : tasks) {
                if (i++ >= 10) {
                    sb.append("- （待办共 ").append(tasks.size()).append(" 条，仅渲染前 10 条）\n");
                    break;
                }
                if (t instanceof Map<?, ?> tm) {
                    Map<String, Object> m = (Map<String, Object>) tm;
                    sb.append("- 待办：").append(nullToDash(str(m.get("title"))))
                        .append("（状态 ").append(nullToDash(str(m.get("status"))))
                        .append("，截止 ").append(nullToDash(str(m.get("dueAt"))))
                        .append(m.get("overdue") == Boolean.TRUE ? "，已超期" : "").append("）\n");
                }
            }
        }
        return sb.toString();
    }

    static String renderProjectContext(Project p) {
        return "- 项目：" + nullToDash(p.getCode()) + ' ' + nullToDash(p.getName())
            + "，当前阶段 " + nullToDash(p.getCurrentStage()) + '\n';
    }

    private Project requireProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不存在");
        }
        return p;
    }

    /** scene → 任务指令模板 + 上下文拼接（超出 PROMPT_MAX 截断；userPrompt 原文只进 prompt）。 */
    static String composePrompt(String scene, String context, String userPrompt) {
        String instruction = switch (scene) {
            case "workbench.next-step" ->
                "根据以下工作台上下文，给出该产品经理接下来最应推进的 3 个具体行动（按优先级排序，每条一句话+理由）。";
            case "workbench.risk-warning" ->
                "根据以下工作台上下文，识别 3 条最紧迫的风险预警（超期、停滞、临期），每条给出风险描述与建议动作。";
            case "project.summary.refresh" ->
                "根据以下项目上下文，写一段 150 字以内的项目状态总结（进展、阻塞、下一步），markdown 格式。";
            case "project.create.suggest" ->
                "你是 IPD 立项助手。根据用户的原始想法，起草项目章程要点：项目名建议、目标（可衡量的 3 条）、范围（含/不含）、关键干系人建议。markdown 分节输出。";
            case "demand.create.from-requirement" ->
                "你是需求管理助手。把用户的原始需求整理成规范需求单草稿：标题（≤30字）、需求描述、分类建议、优先级建议（P0-P3+理由）、验收标准草案。markdown 分节输出。";
            case "gate.precheck-checklist" ->
                "你是 Gate 评审助手。根据以下评审与要素结果上下文，生成评审前检查清单：哪些要素证据已齐、哪些缺失或存疑、评审需要追问的问题。markdown 清单格式。";
            case "gate.conclusion-draft" ->
                "你是 Gate 评审助手。根据以下评审上下文与要素结果，起草评审结论（含通过/不通过/有条件通过的建议倾向与理由、遗留条件建议）。markdown，200 字以内。";
            case "demand.dedupe" ->
                "你是需求管理助手。对照以下项目既有需求清单，判断用户新需求是否与存量重复：给出查重结论（重复/部分重复/全新）、最相似的存量需求（≤5 条，含编号与理由）、"
                + "路由建议（并入哪条存量需求 / 新建并指派市场PM还是研发PM）。不确定标「待确认」。markdown 分节输出。";
            case "change.impact-analyze" ->
                "你是变更管理助手。根据以下需求变更单上下文，输出影响面分析：受影响需求/阶段动作/Gate/排期的候选清单、风险点（≤3 条）、"
                + "影响面评级（高/中/低+理由）、是否推荐批准的建议（仅建议性，双签审批链以真实流程为准）。缺数据标「待确认」，不得虚构。markdown 分节输出。";
            case "handover.checklist-generate" ->
                "你是移交管理助手。根据以下移交记录上下文，生成移交清单草稿：未完成流程/待交接资料与附件/Gate 评审遗留/台账与待办续交、接收方责任清单、"
                + "建议交接顺序。缺数据标「待确认」，不得虚构。markdown 清单格式输出。";
            case "report.nl-query" ->
                "你是报表助手。以下是系统现有报告中心目录（唯一可查数据源，不支持目录外查询与自由 SQL）。根据用户的自然语言问题：选择应导航到的报告端点与参数（month/projectId 等）、"
                + "说明该报告能否回答此问题；不能回答时明确说「暂不支持」并给最近似替代。markdown 输出：选中端点/参数建议/缺口说明。";
            default -> throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "未知场景：" + scene);
        };
        StringBuilder sb = new StringBuilder(instruction).append('\n');
        if (!context.isEmpty()) {
            sb.append("\n【业务上下文】\n").append(context);
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n【用户原始素材】\n").append(userPrompt).append('\n');
        }
        sb.append("\n要求：只输出 markdown 正文，不要解释你没有的数据；不确定的信息标注「待确认」。");
        String s = sb.toString();
        return s.length() > PROMPT_MAX ? s.substring(0, PROMPT_MAX) : s;
    }

    // ---- 越权与审计 ----

    /** 项目可见性校验（copilot 同构：SA 全可见，其余需 project_members 命中；不改动 copilot 文件避免在途撞车）。 */
    private void assertProjectVisible(IpdActor actor, Long projectId) {
        if (projectId == null) {
            return; // 全局维度不限制（workbench 全局待办）
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        if ("SUPER_ADMIN".equals(actor.role())) {
            return;
        }
        Long hit = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getPersonId, actor.id()));
        if (hit == null || hit == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见");
        }
    }

    /**
     * AI_SUGGEST 审计行（AI-审计三件套规约 §2 Layer2：aiRole=suggestion；原文不落库，只记 promptLen）。
     * R232-P1-02：结构化产出附 cardType（仅元数据；轻场景/无 card 不加键，审计载荷零变化）。
     */
    private void audit(IpdActor actor, AiSuggestReq req, long latencyMs,
                       int tokenPrompt, int tokenCompletion, String status, String aiModel, String cardType) {
        StringBuilder json = new StringBuilder();
        Object[] base = {
            "aiAssisted", true,
            "aiModel", aiModel == null || aiModel.isBlank() ? "intent_match" : aiModel,
            "aiRole", "suggestion",
            "scene", req.scene(),
            "projectId", req.projectId(),
            "entityId", req.entityId(),
            "status", status,
            "tokenPrompt", tokenPrompt,
            "tokenCompletion", tokenCompletion,
            "latencyMs", latencyMs,
            "promptLen", req.userPrompt() == null ? 0 : req.userPrompt().length()
        };
        java.util.List<Object> pairs = new java.util.ArrayList<>(java.util.Arrays.asList(base));
        if (cardType != null) {
            pairs.add("cardType");
            pairs.add(cardType);
        }
        json.append(AuditEventData.json(pairs.toArray()));
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id()).operatorName(actor.name()).operatorRole(actor.role())
            .action("AI_SUGGEST").entityType("AI_SUGGESTION")
            .afterData(json.toString())
            .build());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() || "null".equals(s) ? "-" : s;
    }
}
