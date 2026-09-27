package org.ruoyi.ipd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.dto.AiSuggestReq;
import org.ruoyi.ipd.dto.AiSuggestResp;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R232-P1-02：AiSuggestionService structured 输出模式 + 4 场景卡片 schema Catalog 行为锁。
 *
 * <p>覆盖：
 * <ul>
 *   <li>4 结构化场景响应体增 card {type, version, data, sourceRefs}，data 字段名/类型与 Catalog
 *       逐项对齐（含数组 itemFields 子字段）；</li>
 *   <li>3 轻场景纯文本零变化（无 card，且不读 Catalog）；</li>
 *   <li>scene 白名单外 PARAM_INVALID 语义不动；</li>
 *   <li>schema 外字段（含数组子字段）一律不产出——Catalog 是唯一 schema 事实源（禁硬编码：
 *       缩减版 Catalog → data 随之缩减可证）；</li>
 *   <li>Catalog 从 system_configs 读取（mock 读取层，勿直连真库写）；缺失/损坏降级纯文本；</li>
 *   <li>审计三件套：aiRole=suggestion、cardType 元数据、prompt 只记 promptLen 不落原文（BR-AI-04）。</li>
 * </ul>
 *
 * <p>mock 合法性（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md 三硬规则）：mock 行按
 * ipd_dev DDL NOT NULL 实证构造（gate_reviews: id/gate_id/reviewer_type/reviewer_id/due_at/round；
 * gate_element_results: id/gate_id/element_id/result；requirements: id/title/query_code/status/source），
 * 且遵守业务写入规则组合（CONDITIONAL 必附 condition_note+责任人+期限、FAIL 必附 evidence_ref、
 * 待签行 decision=null 合法——decision 列可空）。DB 依赖断言未覆盖 DDL 合法性（见 DisplayName）。
 */
@Tag("dev")
@DisplayName("R232-P1-02：AI 建议 structured 输出 + 卡片 schema Catalog（mock 读取层，未覆盖 DDL 合法性）")
class AiSuggestionCardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AiModelConfigService modelConfigService;
    private WorkbenchService workbenchService;
    private AiGateway aiGateway;
    private IAuditLogService auditLogService;
    private ProjectMapper projectMapper;
    private ProjectMemberMapper projectMemberMapper;
    private GateReviewMapper gateReviewMapper;
    private GateElementResultMapper gateElementResultMapper;
    private ISystemConfigService systemConfigService;
    private RequirementMapper requirementMapper;
    private AiSuggestionService service;

    private static final IpdActor SA = new IpdActor(1L, "sa", "SUPER_ADMIN", null);
    private static final IpdActor PM = new IpdActor(2L, "pm", "PM", 100L);

    /**
     * Catalog fixture——与 system_configs 行 {@code ai.suggest.cardCatalog} 同文
     * （交付 JSON 全文见执行报告；此处为 mock 读取层返回值，勿直连真库）。
     */
    static final String CATALOG_JSON = """
        {
          "catalogVersion": 1,
          "description": "R232-P1-02 AI 建议卡 schema Catalog（唯一 schema 事实源）；data 值经 sourceRefs 回读业务表（R3），无源字段不进 schema",
          "cards": [
            {
              "type": "gate.precheck",
              "version": 1,
              "scene": "gate.precheck-checklist",
              "shape": "checklist",
              "description": "Gate 预检清单卡（清单型）：要素逐项判定事实（gate_element_results/gate_reviews）；AI 清单建议在 markdown 建议区",
              "fields": [
                {"name": "gateCode", "type": "string", "source": "gate_reviews.gate_code"},
                {"name": "round", "type": "number", "source": "gate_reviews.round"},
                {"name": "reviewCount", "type": "number", "source": "gate_reviews.id"},
                {"name": "totalElements", "type": "number", "source": "gate_element_results.id"},
                {"name": "items", "type": "array<object>", "source": "gate_element_results", "itemFields": [
                  {"name": "elementId", "type": "number", "source": "gate_element_results.element_id"},
                  {"name": "result", "type": "string", "source": "gate_element_results.result"},
                  {"name": "conditionNote", "type": "string", "source": "gate_element_results.condition_note"},
                  {"name": "evidenceRef", "type": "string", "source": "gate_element_results.evidence_ref"},
                  {"name": "leftoverStatus", "type": "string", "source": "gate_element_results.leftover_status"}
                ]}
              ],
              "sourceRefs": ["gateId", "reviewIds", "elementResultIds"]
            },
            {
              "type": "gate.conclusion",
              "version": 1,
              "scene": "gate.conclusion-draft",
              "shape": "decision",
              "description": "Gate 结论草稿卡（判定型）：签署决定事实（gate_reviews）+ 要素结果计数（gate_element_results.result 聚合）；AI 结论草稿在 markdown 建议区（LLM 复述值不进 data）",
              "fields": [
                {"name": "gateCode", "type": "string", "source": "gate_reviews.gate_code"},
                {"name": "reviews", "type": "array<object>", "source": "gate_reviews", "itemFields": [
                  {"name": "reviewerType", "type": "string", "source": "gate_reviews.reviewer_type"},
                  {"name": "decision", "type": "string", "source": "gate_reviews.decision"},
                  {"name": "opinion", "type": "string", "source": "gate_reviews.opinion"},
                  {"name": "round", "type": "number", "source": "gate_reviews.round"}
                ]},
                {"name": "passCount", "type": "number", "source": "gate_element_results.result"},
                {"name": "conditionalCount", "type": "number", "source": "gate_element_results.result"},
                {"name": "failCount", "type": "number", "source": "gate_element_results.result"}
              ],
              "sourceRefs": ["gateId", "reviewIds", "elementResultIds"]
            },
            {
              "type": "project.charter",
              "version": 1,
              "scene": "project.create.suggest",
              "shape": "compare",
              "description": "立项要点卡（对比型）：当前项目上下文事实（projects）对照 AI 立项建议（markdown 建议区）；目标/范围/干系人建议无 projects 落点列，不进 schema（无源不进 schema）",
              "fields": [
                {"name": "contextProjectId", "type": "number", "source": "projects.id"},
                {"name": "contextProjectCode", "type": "string", "source": "projects.code"},
                {"name": "contextProjectName", "type": "string", "source": "projects.name"},
                {"name": "contextCurrentStage", "type": "string", "source": "projects.current_stage"},
                {"name": "contextProductId", "type": "number", "source": "projects.product_id"}
              ],
              "sourceRefs": ["projectId"]
            },
            {
              "type": "demand.draft",
              "version": 1,
              "scene": "demand.create.from-requirement",
              "shape": "draft",
              "description": "需求单草稿卡（草稿型）：项目锚 + 需求池来源事实（projects/requirements）；AI 规范化草稿在 markdown 建议区；分类/优先级/验收标准建议无 requirements 落点列，不进 schema（无源不进 schema）",
              "fields": [
                {"name": "contextProjectId", "type": "number", "source": "projects.id"},
                {"name": "contextProjectCode", "type": "string", "source": "projects.code"},
                {"name": "contextProjectName", "type": "string", "source": "projects.name"},
                {"name": "requirements", "type": "array<object>", "source": "requirements", "itemFields": [
                  {"name": "requirementId", "type": "number", "source": "requirements.id"},
                  {"name": "title", "type": "string", "source": "requirements.title"},
                  {"name": "status", "type": "string", "source": "requirements.status"},
                  {"name": "source", "type": "string", "source": "requirements.source"}
                ]}
              ],
              "sourceRefs": ["projectId", "requirementIds"]
            }
          ]
        }
        """;

    @BeforeEach
    void setUp() {
        modelConfigService = mock(AiModelConfigService.class);
        workbenchService = mock(WorkbenchService.class);
        aiGateway = mock(AiGateway.class);
        auditLogService = mock(IAuditLogService.class);
        projectMapper = mock(ProjectMapper.class);
        projectMemberMapper = mock(ProjectMemberMapper.class);
        gateReviewMapper = mock(GateReviewMapper.class);
        gateElementResultMapper = mock(GateElementResultMapper.class);
        systemConfigService = mock(ISystemConfigService.class);
        requirementMapper = mock(RequirementMapper.class);
        service = new AiSuggestionService(modelConfigService, workbenchService, aiGateway,
            auditLogService, projectMapper, projectMemberMapper, gateReviewMapper, gateElementResultMapper,
            systemConfigService, requirementMapper)
            .withClock(Clock.fixed(Instant.parse("2026-09-27T08:00:00Z"), ZoneId.of("UTC")));
        // mock 读取层默认返回 Catalog fixture（勿直连真库写）
        when(systemConfigService.getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any()))
            .thenReturn(CATALOG_JSON);
    }

    // ---- mock 合法性构建器（按 ipd_dev DDL NOT NULL + 真实写入规则组合） ----

    /** gate_reviews：id/gate_id/reviewer_type/reviewer_id/due_at/round NOT NULL（DDL 实证）。 */
    private GateReview mockReview(long id, long gateId, long projectId, String reviewerType,
                                  String decision, String opinion) {
        GateReview r = new GateReview();
        r.setId(id);
        r.setGateId(gateId);
        r.setProjectId(projectId);
        r.setReviewerType(reviewerType);
        r.setReviewerId(id * 10);
        r.setDueAt(new Date());
        r.setRound(1);
        r.setGateCode("G4");
        r.setDecision(decision);
        r.setOpinion(opinion);
        return r;
    }

    /** gate_element_results：id/gate_id/element_id/result NOT NULL；CONDITIONAL/FAIL 组合守业务写入规则。 */
    private GateElementResult mockElementResult(long id, long gateId, long elementId, String result,
                                                String conditionNote, String evidenceRef, String leftoverStatus) {
        GateElementResult r = new GateElementResult();
        r.setId(id);
        r.setGateId(gateId);
        r.setElementId(elementId);
        r.setResult(result);
        r.setConditionNote(conditionNote);
        r.setEvidenceRef(evidenceRef);
        r.setLeftoverStatus(leftoverStatus);
        if ("CONDITIONAL".equals(result)) {
            r.setResponsiblePersonId(2L);
            r.setLeftoverDueAt(new Date());
        }
        return r;
    }

    /** requirements：id/title/query_code/status/source NOT NULL（DDL 实证）。 */
    private Requirement mockRequirement(long id, long projectId, String title, String status, String source) {
        Requirement req = new Requirement();
        req.setId(id);
        req.setProjectId(projectId);
        req.setTitle(title);
        req.setContent("原始需求内容-" + id);
        req.setQueryCode("Q-2026-" + id);
        req.setStatus(status);
        req.setSource(source);
        return req;
    }

    private void enableModelAndGateway(String llmContent) {
        AiModelConfig config = new AiModelConfig();
        config.setProvider("openai");
        config.setEndpointUrl("http://mock/v1");
        config.setModelName("mock-mini");
        when(modelConfigService.currentEnabled()).thenReturn(config);
        when(modelConfigService.decryptApiKey(config)).thenReturn("sk-x");
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok(llmContent, 120, 45, 1000L));
    }

    private Project mockProject(long id) {
        Project p = new Project();
        p.setId(id);
        p.setCode("PRJ-" + id);
        p.setName("示例项目");
        p.setCurrentStage("PLAN");
        p.setProductId(5L);
        when(projectMapper.selectById(id)).thenReturn(p);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        return p;
    }

    // ---- schema 对齐核心断言（期望字段从 Catalog fixture 推导，逐名逐型） ----

    private JsonNode cardDefFromCatalog(String scene) {
        try {
            JsonNode cards = JSON.readTree(CATALOG_JSON).get("cards");
            for (JsonNode c : cards) {
                if (scene.equals(c.path("scene").asText())) {
                    return c;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("fixture 解析失败", e);
        }
        throw new IllegalStateException("fixture 缺 scene: " + scene);
    }

    private void assertCardAlignsWithCatalog(AiSuggestResp resp, String scene) {
        assertNotNull(resp.card(), scene + " 应产出 card");
        JsonNode def = cardDefFromCatalog(scene);
        assertEquals(def.path("type").asText(), resp.card().type(), scene + " type");
        assertEquals(def.path("version").asInt(), resp.card().version(), scene + " version");

        // data 字段名逐一断言（集合相等 = 无缺无多；再逐字段验类型）
        List<String> schemaNames = new ArrayList<>();
        for (JsonNode f : def.get("fields")) {
            schemaNames.add(f.path("name").asText());
        }
        assertEquals(new java.util.LinkedHashSet<>(schemaNames), resp.card().data().keySet(),
            scene + " data 字段名集合与 Catalog 逐名对齐（含无多余字段）");
        for (JsonNode f : def.get("fields")) {
            String name = f.path("name").asText();
            String type = f.path("type").asText();
            Object value = resp.card().data().get(name);
            assertValueType(scene + "." + name, type, f.get("itemFields"), value);
        }

        // sourceRefs 键与 Catalog 声明逐名对齐
        List<String> refKeys = new ArrayList<>();
        for (JsonNode k : def.get("sourceRefs")) {
            refKeys.add(k.asText());
        }
        assertEquals(new java.util.LinkedHashSet<>(refKeys), resp.card().sourceRefs().keySet(),
            scene + " sourceRefs 键与 Catalog 声明对齐");
    }

    private void assertValueType(String where, String type, JsonNode itemFields, Object value) {
        switch (type) {
            case "string" -> assertTrue(value == null || value instanceof String, where + " 应为 string，实际 " + value);
            case "number" -> assertTrue(value == null || value instanceof Number, where + " 应为 number，实际 " + value);
            case "array<object>" -> {
                assertTrue(value instanceof List<?>, where + " 应为 array<object>");
                List<String> subNames = new ArrayList<>();
                if (itemFields != null) {
                    for (JsonNode f : itemFields) {
                        subNames.add(f.path("name").asText());
                    }
                }
                for (Object item : (List<?>) value) {
                    assertTrue(item instanceof Map<?, ?>, where + " 数组元素应为 object");
                    Map<?, ?> m = (Map<?, ?>) item;
                    assertEquals(new java.util.LinkedHashSet<>(subNames), m.keySet(),
                        where + " 数组子字段与 itemFields 逐名对齐（含无多余子字段）");
                    for (JsonNode f : itemFields) {
                        Object sub = m.get(f.path("name").asText());
                        String subType = f.path("type").asText();
                        assertTrue("string".equals(subType) ? sub == null || sub instanceof String
                            : sub == null || sub instanceof Number,
                            where + "." + f.path("name").asText() + " 类型应为 " + subType + "，实际 " + sub);
                    }
                }
            }
            default -> throw new IllegalStateException("fixture 未知类型 " + type);
        }
    }

    // ---- 4 结构化场景：card 对齐 schema ----

    @Test
    @DisplayName("gate.precheck-checklist → card(gate.precheck) 与 Catalog 对齐（gate_reviews/gate_element_results 实证 mock）")
    void gatePrecheckCardAligns() {
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        long gateId = 3L;
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, gateId, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, gateId, 21L, "PASS", null, null, null),
            mockElementResult(502L, gateId, 22L, "CONDITIONAL", "补测试报告", null, "OPEN"),
            mockElementResult(503L, gateId, 23L, "FAIL", null, "oss://evid/23.pdf", null)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, gateId, null));

        assertEquals("## 预检清单", resp.markdown(), "markdown 正文零变化");
        assertCardAlignsWithCatalog(resp, "gate.precheck-checklist");
        AiSuggestResp.Card card = resp.card();
        assertEquals("G4", card.data().get("gateCode"));
        assertEquals(1, card.data().get("round"));
        assertEquals(1, card.data().get("reviewCount"));
        assertEquals(3, card.data().get("totalElements"));
        List<?> items = (List<?>) card.data().get("items");
        assertEquals(3, items.size());
        assertEquals("CONDITIONAL", ((Map<?, ?>) items.get(1)).get("result"));
        assertEquals("补测试报告", ((Map<?, ?>) items.get(1)).get("conditionNote"));
        assertEquals("oss://evid/23.pdf", ((Map<?, ?>) items.get(2)).get("evidenceRef"));
        assertEquals(gateId, card.sourceRefs().get("gateId"));
        assertEquals(List.of(91L), card.sourceRefs().get("reviewIds"));
        assertEquals(List.of(501L, 502L, 503L), card.sourceRefs().get("elementResultIds"));
    }

    @Test
    @DisplayName("gate.conclusion-draft → card(gate.conclusion) 与 Catalog 对齐（签署事实+聚合计数）")
    void gateConclusionCardAligns() {
        enableModelAndGateway("## 结论草稿");
        mockProject(7L);
        long gateId = 3L;
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, gateId, 7L, "MARKET_PM", "APPROVE", "通过"),
            mockReview(92L, gateId, 7L, "RD_PM", null, null))); // 待签行 decision=null（列可空，真实组合）
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, gateId, 21L, "PASS", null, null, null),
            mockElementResult(502L, gateId, 22L, "CONDITIONAL", "补测试报告", null, "OPEN"),
            mockElementResult(503L, gateId, 23L, "FAIL", null, "oss://evid/23.pdf", null)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.conclusion-draft", null, gateId, null));

        assertCardAlignsWithCatalog(resp, "gate.conclusion-draft");
        AiSuggestResp.Card card = resp.card();
        List<?> reviews = (List<?>) card.data().get("reviews");
        assertEquals(2, reviews.size());
        assertEquals("MARKET_PM", ((Map<?, ?>) reviews.get(0)).get("reviewerType"));
        assertNull(((Map<?, ?>) reviews.get(1)).get("decision"), "待签行 decision 原样 null（不编造值）");
        assertEquals(1L, card.data().get("passCount"));
        assertEquals(1L, card.data().get("conditionalCount"));
        assertEquals(1L, card.data().get("failCount"));
    }

    @Test
    @DisplayName("project.create.suggest → card(project.charter) 与 Catalog 对齐（projects 锚事实）")
    void projectCreateCardAligns() {
        enableModelAndGateway("## 立项要点");
        mockProject(7L);

        AiSuggestResp resp = service.suggest(PM,
            new AiSuggestReq("project.create.suggest", 7L, null, "做一款扫地机器人"));

        assertCardAlignsWithCatalog(resp, "project.create.suggest");
        AiSuggestResp.Card card = resp.card();
        assertEquals(7L, card.data().get("contextProjectId"));
        assertEquals("PRJ-7", card.data().get("contextProjectCode"));
        assertEquals("PLAN", card.data().get("contextCurrentStage"));
        assertEquals(5L, card.data().get("contextProductId"));
        assertEquals(7L, card.sourceRefs().get("projectId"));
    }

    @Test
    @DisplayName("demand.create.from-requirement → card(demand.draft) 与 Catalog 对齐（projects+requirements 池事实）")
    void demandCreateCardAligns() {
        enableModelAndGateway("## 需求单草稿");
        mockProject(7L);
        when(requirementMapper.selectList(any())).thenReturn(List.of(
            mockRequirement(31L, 7L, "游客需求-扫地机", "SUBMITTED", "PORTAL_GUEST"),
            mockRequirement(32L, 7L, "内部需求-基站自清洁", "ACCEPTED", "INTERNAL")));

        AiSuggestResp resp = service.suggest(PM,
            new AiSuggestReq("demand.create.from-requirement", 7L, null, "客户要一台扫地机器人"));

        assertCardAlignsWithCatalog(resp, "demand.create.from-requirement");
        AiSuggestResp.Card card = resp.card();
        List<?> reqs = (List<?>) card.data().get("requirements");
        assertEquals(2, reqs.size());
        assertEquals("游客需求-扫地机", ((Map<?, ?>) reqs.get(0)).get("title"));
        assertEquals("PORTAL_GUEST", ((Map<?, ?>) reqs.get(0)).get("source"));
        assertEquals(List.of(31L, 32L), card.sourceRefs().get("requirementIds"));
        assertEquals(7L, card.sourceRefs().get("projectId"));
    }

    // ---- 3 轻场景：纯文本零变化 ----

    @ParameterizedTest(name = "轻场景 {0} 保持纯文本（无 card，且不读 Catalog）")
    @ValueSource(strings = {"workbench.next-step", "workbench.risk-warning", "project.summary.refresh"})
    void lightScenesStayPlainText(String scene) {
        enableModelAndGateway("建议正文");
        mockProject(7L);
        when(workbenchService.summary(any(), any())).thenReturn(Map.of(
            "stats", Map.of("pending", 1, "overdue", 0, "completed", 2)));

        AiSuggestResp resp = service.suggest(PM,
            new AiSuggestReq(scene, scene.startsWith("workbench.") ? null : 7L, null, null));

        assertNull(resp.card(), scene + " 不应产出 card");
        assertEquals("建议正文", resp.markdown(), scene + " markdown 零变化");
        verify(systemConfigService, never()).getValue(anyString(), any());
    }

    // ---- 白名单语义不动 ----

    @Test
    @DisplayName("白名单外 scene → PARAM_INVALID（语义不动：不调 AI 不落审计不读 Catalog）")
    void unknownSceneStillParamInvalid() {
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("rm.delete.all", 1L, null, "x")));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(aiGateway, never()).chat(any(AiTestConfig.class), anyString(), anyInt(), any());
        verify(auditLogService, never()).append(any(AuditLog.class));
        verify(systemConfigService, never()).getValue(anyString(), any());
    }

    // ---- schema 外字段不出现（Catalog 是唯一 schema 事实源） ----

    @Test
    @DisplayName("schema 外字段不出现：facts 存在但 schema 未声明的键（reviews/passCount 等）不进 precheck data")
    void schemaExternalFieldsDroppedFullFlow() {
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        long gateId = 3L;
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, gateId, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, gateId, 21L, "PASS", null, null, null)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, gateId, null));

        // gate.conclusion schema 的字段存在于 facts，但 gate.precheck schema 未声明 → 必须不出现
        assertFalse(resp.card().data().containsKey("reviews"));
        assertFalse(resp.card().data().containsKey("passCount"));
        assertFalse(resp.card().data().containsKey("conditionalCount"));
        assertFalse(resp.card().data().containsKey("failCount"));
        assertFalse(resp.card().sourceRefs().containsKey("requirementIds"));
    }

    @Test
    @DisplayName("schema 外字段不出现（单元面）：注入越 schema 顶层键与数组子字段 → 丢弃不产出")
    void schemaExternalFieldsDroppedUnit() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("gateCode", "G4");
        facts.put("round", 1);
        facts.put("reviewCount", 1);
        facts.put("totalElements", 1);
        facts.put("aiSuggestedDecision", "APPROVE"); // 越 schema 顶层键（LLM 复述值形态）
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("elementId", 21L);
        item.put("result", "PASS");
        item.put("conditionNote", null);
        item.put("evidenceRef", null);
        item.put("leftoverStatus", null);
        item.put("aiConfidence", 0.93); // 越 schema 数组子字段
        facts.put("items", List.of(item));
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("gateId", 3L);
        refs.put("reviewIds", List.of(91L));
        refs.put("elementResultIds", List.of(501L));
        refs.put("strayRef", "x"); // 越 schema sourceRefs 键

        AiSuggestResp.Card card = AiSuggestionService.assembleCard(
            "gate.precheck-checklist", CATALOG_JSON, facts, refs);

        assertNotNull(card);
        assertFalse(card.data().containsKey("aiSuggestedDecision"), "schema 外顶层字段必须丢弃");
        assertFalse(card.sourceRefs().containsKey("strayRef"), "schema 外 sourceRefs 键必须丢弃");
        Map<?, ?> projected = (Map<?, ?>) ((List<?>) card.data().get("items")).get(0);
        assertFalse(projected.containsKey("aiConfidence"), "schema 外数组子字段必须丢弃");
    }

    @Test
    @DisplayName("Catalog 是唯一 schema 事实源（禁硬编码）：缩减版 Catalog → data 随之只剩声明字段")
    void catalogIsSoleSchemaSource() {
        String reduced = """
            {"cards":[{"type":"gate.precheck","version":2,"scene":"gate.precheck-checklist",
            "fields":[{"name":"gateCode","type":"string","source":"gate_reviews.gate_code"}],
            "sourceRefs":["gateId"]}]}
            """;
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("gateCode", "G4");
        facts.put("round", 1);
        facts.put("items", List.of());
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("gateId", 3L);
        refs.put("reviewIds", List.of(91L));

        AiSuggestResp.Card card = AiSuggestionService.assembleCard(
            "gate.precheck-checklist", reduced, facts, refs);

        assertNotNull(card);
        assertEquals(2, card.version(), "version 取 Catalog 声明");
        assertEquals(java.util.Set.of("gateCode"), card.data().keySet(), "data 必须随 Catalog 缩减（Java 无硬编码字段表）");
        assertEquals(java.util.Set.of("gateId"), card.sourceRefs().keySet());
    }

    // ---- Catalog 读取层与降级 ----

    @Test
    @DisplayName("Catalog 从 system_configs 读取（键 ai.suggest.cardCatalog，mock 读取层）")
    void catalogReadFromSystemConfigs() {
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null)));

        service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        verify(systemConfigService).getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any());
    }

    @Test
    @DisplayName("Catalog 缺失 → 无 card 降级纯文本（文本降级路径永不删）")
    void catalogMissingDegradesToText() {
        when(systemConfigService.getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any()))
            .thenReturn("");
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        assertNull(resp.card(), "Catalog 缺失不得产出 card");
        assertEquals("## 预检清单", resp.markdown(), "markdown 正常返回");
    }

    @Test
    @DisplayName("Catalog JSON 损坏 → 无 card 降级纯文本")
    void catalogMalformedDegradesToText() {
        when(systemConfigService.getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any()))
            .thenReturn("{not-json");
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        assertNull(resp.card());
        assertEquals("## 预检清单", resp.markdown());
    }

    @Test
    @DisplayName("模型未启用降级 → 无 card（降级应答恒纯文本）")
    void degradedModelHasNoCard() {
        when(modelConfigService.currentEnabled())
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "no active model"));
        mockProject(7L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        assertTrue(resp.degraded());
        assertNull(resp.card());
    }

    // ---- 审计三件套（AI-审计三件套规约 §2 Layer2） ----

    @Test
    @DisplayName("结构化产出审计：aiRole=suggestion + cardType 元数据 + prompt 只记 promptLen 不落原文（BR-AI-04）")
    void cardEmissionAuditsWithoutPromptText() {
        enableModelAndGateway("## 需求单草稿");
        mockProject(7L);
        when(requirementMapper.selectList(any())).thenReturn(List.of(
            mockRequirement(31L, 7L, "游客需求-扫地机", "SUBMITTED", "PORTAL_GUEST")));

        String raw = "客户要一台扫地机器人，最好能自动倒垃圾";
        service.suggest(PM, new AiSuggestReq("demand.create.from-requirement", 7L, null, raw));

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        String after = cap.getValue().getAfterData();
        assertEquals("AI_SUGGEST", cap.getValue().getAction());
        assertTrue(after.contains("\"aiRole\":\"suggestion\""), after);
        assertTrue(after.contains("\"cardType\":\"demand.draft\""), after);
        assertTrue(after.contains("\"promptLen\":" + raw.length()), after);
        assertFalse(after.contains("扫地机器人"), "prompt/素材原文不得进审计（BR-AI-04）");
    }

    @Test
    @DisplayName("轻场景审计载荷零变化：无 cardType 键（3 轻场景纯文本零变化）")
    void lightSceneAuditPayloadUnchanged() {
        enableModelAndGateway("建议正文");
        mockProject(7L);

        service.suggest(PM, new AiSuggestReq("project.summary.refresh", 7L, null, null));

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        String after = cap.getValue().getAfterData();
        assertTrue(after.contains("\"aiRole\":\"suggestion\""), after);
        assertFalse(after.contains("cardType"), after);
    }
}
