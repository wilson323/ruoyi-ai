package org.ruoyi.ipd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.RequirementChangeMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R232-P1-03（CopilotKit 三能力落地 Phase 1）：cardPayload 组装器行为锁——
 * R2 白名单双向校验（schema 外字段一律丢弃 + 落审计）+ R3 sourceRefs 回读对账（拒出卡降级纯文本）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>负向：注入越 schema 顶层字段 / 数组子字段 / sourceRefs 键 → 丢弃且 audit_logs 新增一行
 *       （SCHEMA_DROP，审计字段按 AI-审计三件套规约：aiRole∈7 值白名单、prompt 只记 promptLen）；</li>
 *   <li>负向：sourceRefs 缺必备键 → 拒出卡降级纯文本（CARD_REJECT:source_refs_missing:*）；</li>
 *   <li>负向：sourceRefs 回读失败（DB 异常 / 行不齐）→ 拒出卡降级纯文本（CARD_REJECT:reread_*），
 *       不得用 LLM 复述值兜底（card 恒 null、markdown 原样保留）；</li>
 *   <li>正向：4 结构化场景 card 与 Catalog 逐字段对账（值全部等于 sourceRefs 指向的源行投影值）；</li>
 *   <li>负向（P1-03 挂账补账）：sourceRefs 回读值≠组装值 → 拒出卡降级纯文本（value_mismatch）、
 *       sourceRefs 指向他人/越权行 → 拒出卡降级纯文本（reread_foreign_row）；</li>
 *   <li>P1-04 对账哨兵：fixture ↔ DB Catalog 导出双份 schema 逐字段双向对账（零增删）+
 *       4 场景 card schema ↔ DB Catalog 逐字段双向对账（零增删）；</li>
 *   <li>回归：3 轻场景零影响（无 card、不读 Catalog、审计载荷零变化）。</li>
 * </ul>
 *
 * <p>mock 合法性（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md 三硬规则）：mock 行按
 * ipd_dev DDL NOT NULL 实证构造（gate_reviews: id/gate_id/reviewer_type/reviewer_id/due_at/round；
 * gate_element_results: id/gate_id/element_id/result；requirements: id/title/query_code/status/source），
 * 业务写入规则组合同 AiSuggestionCardTest（CONDITIONAL 附 condition_note+责任人+期限、FAIL 附 evidence_ref）。
 * 纯 mock 用例未覆盖 DDL 合法性（见类 DisplayName）。
 */
@Tag("dev")
@DisplayName("R232-P1-03：cardPayload 组装器 R2 双向白名单 + R3 回读对账（纯 mock，未覆盖 DDL 合法性）")
class AiSuggestionCardR2R3Test {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Catalog fixture 复用 P1-02 同文（唯一 fixture 源防漂移；真库行 ai.suggest.cardCatalog 已对账同文）。 */
    private static final String CATALOG_JSON = AiSuggestionCardTest.CATALOG_JSON;

    /**
     * P1-04 对账哨兵基准：DB 行 {@code ai.suggest.cardCatalog}（id=1948091001）的导出快照——
     * **必须与 DB 同文**（哨兵用例与 fixture 双份对账即以本快照为裁决侧）。取数探针（只读；
     * 凭证在 defaults-extra-file，不上命令行）：
     * <pre>{@code
     * mysql --defaults-extra-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf ipd_dev --vertical -e "SELECT config_value FROM system_configs WHERE config_key='ai.suggest.cardCatalog'"
     * }</pre>
     * 与 {@link #CATALOG_JSON}（P1-02 fixture）已知唯一差异：gate.conclusion.description 说明文案的
     * 空白字符（DB 原文 "+" 后双空格、"建议区" 后多一空格），description 非 schema 字段不参与对账。
     */
    static final String CATALOG_DB_EXPORT = """
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
                {
                  "name": "gateCode",
                  "type": "string",
                  "source": "gate_reviews.gate_code"
                },
                {
                  "name": "round",
                  "type": "number",
                  "source": "gate_reviews.round"
                },
                {
                  "name": "reviewCount",
                  "type": "number",
                  "source": "gate_reviews.id"
                },
                {
                  "name": "totalElements",
                  "type": "number",
                  "source": "gate_element_results.id"
                },
                {
                  "name": "items",
                  "type": "array<object>",
                  "source": "gate_element_results",
                  "itemFields": [
                    {
                      "name": "elementId",
                      "type": "number",
                      "source": "gate_element_results.element_id"
                    },
                    {
                      "name": "result",
                      "type": "string",
                      "source": "gate_element_results.result"
                    },
                    {
                      "name": "conditionNote",
                      "type": "string",
                      "source": "gate_element_results.condition_note"
                    },
                    {
                      "name": "evidenceRef",
                      "type": "string",
                      "source": "gate_element_results.evidence_ref"
                    },
                    {
                      "name": "leftoverStatus",
                      "type": "string",
                      "source": "gate_element_results.leftover_status"
                    }
                  ]
                }
              ],
              "sourceRefs": [
                "gateId",
                "reviewIds",
                "elementResultIds"
              ]
            },
            {
              "type": "gate.conclusion",
              "version": 1,
              "scene": "gate.conclusion-draft",
              "shape": "decision",
              "description": "Gate 结论草稿卡（判定型）：签署决定事实（gate_reviews）+  要素结果计数（gate_element_results.result 聚合）；AI 结论草稿在 markdown 建议区 （LLM 复述值不进 data）",
              "fields": [
                {
                  "name": "gateCode",
                  "type": "string",
                  "source": "gate_reviews.gate_code"
                },
                {
                  "name": "reviews",
                  "type": "array<object>",
                  "source": "gate_reviews",
                  "itemFields": [
                    {
                      "name": "reviewerType",
                      "type": "string",
                      "source": "gate_reviews.reviewer_type"
                    },
                    {
                      "name": "decision",
                      "type": "string",
                      "source": "gate_reviews.decision"
                    },
                    {
                      "name": "opinion",
                      "type": "string",
                      "source": "gate_reviews.opinion"
                    },
                    {
                      "name": "round",
                      "type": "number",
                      "source": "gate_reviews.round"
                    }
                  ]
                },
                {
                  "name": "passCount",
                  "type": "number",
                  "source": "gate_element_results.result"
                },
                {
                  "name": "conditionalCount",
                  "type": "number",
                  "source": "gate_element_results.result"
                },
                {
                  "name": "failCount",
                  "type": "number",
                  "source": "gate_element_results.result"
                }
              ],
              "sourceRefs": [
                "gateId",
                "reviewIds",
                "elementResultIds"
              ]
            },
            {
              "type": "project.charter",
              "version": 1,
              "scene": "project.create.suggest",
              "shape": "compare",
              "description": "立项要点卡（对比型）：当前项目上下文事实（projects）对照 AI 立项建议（markdown 建议区）；目标/范围/干系人建议无 projects 落点列，不进 schema（无源不进 schema）",
              "fields": [
                {
                  "name": "contextProjectId",
                  "type": "number",
                  "source": "projects.id"
                },
                {
                  "name": "contextProjectCode",
                  "type": "string",
                  "source": "projects.code"
                },
                {
                  "name": "contextProjectName",
                  "type": "string",
                  "source": "projects.name"
                },
                {
                  "name": "contextCurrentStage",
                  "type": "string",
                  "source": "projects.current_stage"
                },
                {
                  "name": "contextProductId",
                  "type": "number",
                  "source": "projects.product_id"
                }
              ],
              "sourceRefs": [
                "projectId"
              ]
            },
            {
              "type": "demand.draft",
              "version": 1,
              "scene": "demand.create.from-requirement",
              "shape": "draft",
              "description": "需求单草稿卡（草稿型）：项目锚 + 需求池来源事实（projects/requirements）；AI 规范化草稿在 markdown 建议区；分类/优先级/验收标准建议无 requirements 落点列，不进 schema（无源不进 schema）",
              "fields": [
                {
                  "name": "contextProjectId",
                  "type": "number",
                  "source": "projects.id"
                },
                {
                  "name": "contextProjectCode",
                  "type": "string",
                  "source": "projects.code"
                },
                {
                  "name": "contextProjectName",
                  "type": "string",
                  "source": "projects.name"
                },
                {
                  "name": "requirements",
                  "type": "array<object>",
                  "source": "requirements",
                  "itemFields": [
                    {
                      "name": "requirementId",
                      "type": "number",
                      "source": "requirements.id"
                    },
                    {
                      "name": "title",
                      "type": "string",
                      "source": "requirements.title"
                    },
                    {
                      "name": "status",
                      "type": "string",
                      "source": "requirements.status"
                    },
                    {
                      "name": "source",
                      "type": "string",
                      "source": "requirements.source"
                    }
                  ]
                }
              ],
              "sourceRefs": [
                "projectId",
                "requirementIds"
              ]
            }
          ]
        }
        """;

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
    private RequirementChangeMapper requirementChangeMapper;
    private HandoverMapper handoverMapper;
    private AiSuggestionService service;

    private static final IpdActor SA = new IpdActor(1L, "sa", "SUPER_ADMIN", null);
    private static final IpdActor PM = new IpdActor(2L, "pm", "PM", 100L);

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
        requirementChangeMapper = mock(RequirementChangeMapper.class);
        handoverMapper = mock(HandoverMapper.class);
        service = new AiSuggestionService(modelConfigService, workbenchService, aiGateway,
            auditLogService, projectMapper, projectMemberMapper, gateReviewMapper, gateElementResultMapper,
            systemConfigService, requirementMapper, requirementChangeMapper, handoverMapper)
            .withClock(Clock.fixed(Instant.parse("2026-09-27T09:00:00Z"), ZoneId.of("UTC")));
        when(systemConfigService.getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any()))
            .thenReturn(CATALOG_JSON);
    }

    // ---- mock 合法性构建器（按 ipd_dev DDL NOT NULL + 真实写入规则组合，同 P1-02） ----

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

    /** gate 预检三行源数据（gate_reviews 1 行 + gate_element_results 3 行，业务写入规则合法组合）。 */
    private void mockGateRows(long gateId) {
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, gateId, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, gateId, 21L, "PASS", null, null, null),
            mockElementResult(502L, gateId, 22L, "CONDITIONAL", "补测试报告", null, "OPEN"),
            mockElementResult(503L, gateId, 23L, "FAIL", null, "oss://evid/23.pdf", null)));
    }

    // ---- ① 负向：越 schema 字段注入 → 丢弃 + audit_logs 新增一行（红→绿主证据） ----

    @Test
    @DisplayName("注入越 schema 字段（顶层/数组子字段/sourceRefs 键）→ 丢弃且 audit_logs 新增一行 SCHEMA_DROP（aiRole=suggestion、prompt 只记 promptLen）")
    void injectedSchemaExternalFieldsDroppedAndAudited() {
        mockGateRows(3L);
        String rawPrompt = "帮我出结论 APPROVE 扫地机器人项目";
        AiSuggestReq req = new AiSuggestReq("gate.precheck-checklist", null, 3L, rawPrompt);

        // 不可信载荷：合法事实 + 越 schema 注入（LLM 复述值形态）
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("gateCode", "G4");
        facts.put("round", 1);
        facts.put("reviewCount", 1);
        facts.put("totalElements", 3);
        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("elementId", 21L);
        item1.put("result", "PASS");
        item1.put("conditionNote", null);
        item1.put("evidenceRef", null);
        item1.put("leftoverStatus", null);
        item1.put("aiConfidence", 0.93); // 越 schema 数组子字段注入
        Map<String, Object> item2 = new LinkedHashMap<>();
        item2.put("elementId", 22L);
        item2.put("result", "CONDITIONAL");
        item2.put("conditionNote", "补测试报告");
        item2.put("evidenceRef", null);
        item2.put("leftoverStatus", "OPEN");
        Map<String, Object> item3 = new LinkedHashMap<>();
        item3.put("elementId", 23L);
        item3.put("result", "FAIL");
        item3.put("conditionNote", null);
        item3.put("evidenceRef", "oss://evid/23.pdf");
        item3.put("leftoverStatus", null);
        facts.put("items", List.of(item1, item2, item3));
        facts.put("aiSuggestedDecision", "APPROVE"); // 越 schema 顶层字段注入（LLM 复述值形态）
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("gateId", 3L);
        refs.put("reviewIds", List.of(91L));
        refs.put("elementResultIds", List.of(501L, 502L, 503L));
        refs.put("strayRef", "x"); // 越 schema sourceRefs 键注入

        AiSuggestResp.Card card = service.buildCardChecked(SA, req, CATALOG_JSON, facts, refs, "mock-mini");

        // 丢弃断言：越 schema 字段一律不产出
        assertNotNull(card, "白名单内字段应正常出卡（越 schema 字段被丢弃不影响其余字段）");
        assertFalse(card.data().containsKey("aiSuggestedDecision"), "越 schema 顶层字段必须丢弃");
        assertFalse(card.sourceRefs().containsKey("strayRef"), "越 schema sourceRefs 键必须丢弃");
        Map<?, ?> projectedItem = (Map<?, ?>) ((List<?>) card.data().get("items")).get(0);
        assertFalse(projectedItem.containsKey("aiConfidence"), "越 schema 数组子字段必须丢弃");
        assertTrue(card.data().containsKey("gateCode"), "白名单内字段保留");

        // 审计断言：audit_logs 新增一行（R2 留痕），审计字段按 AI-审计三件套规约
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).append(cap.capture());
        AuditLog row = cap.getValue();
        assertEquals("AI_SUGGEST", row.getAction());
        String after = row.getAfterData();
        assertTrue(after.contains("\"status\":\"SCHEMA_DROP\""), after);
        assertTrue(after.contains("\"aiRole\":\"suggestion\""), after);
        assertTrue(after.contains("\"aiAssisted\":true"), after);
        assertTrue(after.contains("\"cardType\":\"gate.precheck\""), after);
        assertTrue(after.contains("aiSuggestedDecision"), after);
        assertTrue(after.contains("items.aiConfidence"), after);
        assertTrue(after.contains("sourceRefs.strayRef"), after);
        assertTrue(after.contains("\"promptLen\":" + rawPrompt.length()), after);
        assertFalse(after.contains("扫地机器人"), "prompt/素材原文不得进审计（BR-AI-04）");
    }

    // ---- ② 负向：sourceRefs 缺失 → 拒出卡降级纯文本 ----

    @Test
    @DisplayName("sourceRefs 缺必备键 → 拒出卡降级纯文本（CARD_REJECT:source_refs_missing:*，markdown 原样返回）")
    void sourceRefsMissingRejectsCardAndDegradesToText() {
        // Catalog 契约变异：gate.precheck 的 sourceRefs 声明了 gate 场景不可能派生的 requirementIds
        when(systemConfigService.getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any()))
            .thenReturn(catalogWithExtraGateRef("requirementIds"));
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        mockGateRows(3L);

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        assertNull(resp.card(), "sourceRefs 缺失必须拒出卡");
        assertEquals("## 预检清单", resp.markdown(), "降级纯文本：markdown 原样返回（文本降级路径永不删）");

        List<AuditLog> rows = capturedAuditRows();
        assertEquals(2, rows.size(), "主审计行 + CARD_REJECT 事件行");
        String rejectRow = rows.stream().map(AuditLog::getAfterData).filter(a -> a.contains("CARD_REJECT"))
            .findFirst().orElseThrow(() -> new AssertionError("缺 CARD_REJECT 审计行: " + rows));
        assertTrue(rejectRow.contains("CARD_REJECT:source_refs_missing:requirementIds"), rejectRow);
        assertTrue(rejectRow.contains("\"aiRole\":\"suggestion\""), rejectRow);
    }

    // ---- ③ 负向：回读失败 → 拒出卡降级纯文本（不得用 LLM 复述值兜底） ----

    @Test
    @DisplayName("sourceRefs 回读 DB 异常 → 拒出卡降级纯文本（CARD_REJECT:reread_failed:*，不以 LLM 复述值兜底）")
    void rereadFailureRejectsCardAndDegradesToText() {
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        List<GateElementResult> results = List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null));
        // 第 1/2 跳（越权校验/上下文渲染之后的事实采集）正常，第 3 跳（R3 回读对账）DB 异常
        when(gateElementResultMapper.selectList(any()))
            .thenReturn(results).thenReturn(results).thenThrow(new IllegalStateException("reread down"));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        assertNull(resp.card(), "回读失败必须拒出卡（不得用 LLM 复述值兜底）");
        assertEquals("## 预检清单", resp.markdown(), "降级纯文本：markdown 原样返回");
        String rejectRow = capturedAuditRows().stream().map(AuditLog::getAfterData)
            .filter(a -> a.contains("CARD_REJECT")).findFirst()
            .orElseThrow(() -> new AssertionError("缺 CARD_REJECT 审计行"));
        assertTrue(rejectRow.contains("CARD_REJECT:reread_failed:IllegalStateException"), rejectRow);
    }

    @Test
    @DisplayName("sourceRefs 回读行不齐（行缺失）→ 拒出卡降级纯文本（CARD_REJECT:reread_incomplete）")
    void rereadIncompleteRejectsCard() {
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        List<GateElementResult> results = List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null),
            mockElementResult(502L, 3L, 22L, "FAIL", null, "oss://evid/22.pdf", null));
        when(gateElementResultMapper.selectList(any()))
            .thenReturn(results).thenReturn(results).thenReturn(List.of(results.get(0)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        assertNull(resp.card());
        assertEquals("## 预检清单", resp.markdown());
        String rejectRow = capturedAuditRows().stream().map(AuditLog::getAfterData)
            .filter(a -> a.contains("CARD_REJECT")).findFirst()
            .orElseThrow(() -> new AssertionError("缺 CARD_REJECT 审计行"));
        assertTrue(rejectRow.contains("CARD_REJECT:reread_incomplete"), rejectRow);
    }


    // ---- ③b 负向（P1-03 挂账补账）：回读值漂移 / 越权行 → 拒出卡降级纯文本 ----

    @Test
    @DisplayName("sourceRefs 回读值≠组装值（回读时源行已漂移）→ 拒出卡降级纯文本（CARD_REJECT:value_mismatch:*，不以 LLM 复述值兜底）")
    void valueMismatchRejectsCardAndDegradesToText() {
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        GateReview assembled = mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐");
        GateReview drifted = mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐");
        drifted.setGateCode("G5"); // 回读时 gateCode 已被并发更新（回读值≠组装值）
        // 校验/上下文渲染/事实采集 3 跳读到组装值，第 4 跳（R3 回读对账）读到漂移值
        when(gateReviewMapper.selectList(any()))
            .thenReturn(List.of(assembled)).thenReturn(List.of(assembled))
            .thenReturn(List.of(assembled)).thenReturn(List.of(drifted));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        assertNull(resp.card(), "回读值≠组装值必须拒出卡（不得用 LLM 复述值兜底）");
        assertEquals("## 预检清单", resp.markdown(), "降级纯文本：markdown 原样返回");
        String rejectRow = capturedAuditRows().stream().map(AuditLog::getAfterData)
            .filter(a -> a.contains("CARD_REJECT")).findFirst()
            .orElseThrow(() -> new AssertionError("缺 CARD_REJECT 审计行"));
        assertTrue(rejectRow.contains("CARD_REJECT:value_mismatch:gateCode"), rejectRow);
    }

    @Test
    @DisplayName("sourceRefs 指向他人/越权行（同 id 异主挂到别的 gate）→ 拒出卡降级纯文本（CARD_REJECT:reread_foreign_row）")
    void rereadForeignRowRejectsCardAndDegradesToText() {
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        List<GateElementResult> own = List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null),
            mockElementResult(502L, 3L, 22L, "FAIL", null, "oss://evid/22.pdf", null));
        // sourceRefs 指向的行回读后挂在他人 gate 99 下（越权行形态：id 命中但锚不属本 gate）
        List<GateElementResult> foreign = List.of(
            mockElementResult(501L, 99L, 21L, "PASS", null, null, null),
            mockElementResult(502L, 99L, 22L, "FAIL", null, "oss://evid/22.pdf", null));
        when(gateElementResultMapper.selectList(any()))
            .thenReturn(own).thenReturn(own).thenReturn(foreign);

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        assertNull(resp.card(), "越权行必须拒出卡（不得用 LLM 复述值兜底）");
        assertEquals("## 预检清单", resp.markdown(), "降级纯文本：markdown 原样返回");
        String rejectRow = capturedAuditRows().stream().map(AuditLog::getAfterData)
            .filter(a -> a.contains("CARD_REJECT")).findFirst()
            .orElseThrow(() -> new AssertionError("缺 CARD_REJECT 审计行"));
        assertTrue(rejectRow.contains("CARD_REJECT:reread_foreign_row"), rejectRow);
    }
    // ---- ④ 正向：4 结构化场景 card 与 Catalog 逐字段对账（值=sourceRefs 源行投影值） ----

    @Test
    @DisplayName("gate.precheck-checklist 正常出卡：card 与 Catalog 逐字段对账 + 值与源行逐字段相等 + C08 零业务写入")
    void normalGatePrecheckReconcilesFieldByField() {
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        mockGateRows(3L);

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        AiSuggestResp.Card card = resp.card();
        assertNotNull(card);
        assertCardAlignsWithCatalog(card, "gate.precheck-checklist");
        // 逐字段对账（期望值=gate_reviews/gate_element_results 源行投影值，Catalog source 列对齐）
        assertEquals("G4", card.data().get("gateCode"));
        assertEquals(1, card.data().get("round"));
        assertEquals(1, card.data().get("reviewCount"));
        assertEquals(3, card.data().get("totalElements"));
        List<?> items = (List<?>) card.data().get("items");
        assertEquals(3, items.size());
        assertEquals(21L, ((Map<?, ?>) items.get(0)).get("elementId"));
        assertEquals("CONDITIONAL", ((Map<?, ?>) items.get(1)).get("result"));
        assertEquals("补测试报告", ((Map<?, ?>) items.get(1)).get("conditionNote"));
        assertEquals("oss://evid/23.pdf", ((Map<?, ?>) items.get(2)).get("evidenceRef"));
        assertEquals(3L, card.sourceRefs().get("gateId"));
        assertEquals(List.of(91L), card.sourceRefs().get("reviewIds"));
        assertEquals(List.of(501L, 502L, 503L), card.sourceRefs().get("elementResultIds"));
        // 无丢弃/拒卡事件行，仅主审计行
        List<AuditLog> rows = capturedAuditRows();
        assertEquals(1, rows.size(), "正常出卡不新增事件行");
        assertTrue(rows.get(0).getAfterData().contains("\"cardType\":\"gate.precheck\""));
        // C08：零业务表写入（audit_logs 落审计除外）
        verifyNoBusinessWrites();
    }

    @Test
    @DisplayName("gate.conclusion-draft 正常出卡：card 与 Catalog 逐字段对账（签署事实+聚合计数=源行投影值）")
    void normalGateConclusionReconcilesFieldByField() {
        enableModelAndGateway("## 结论草稿");
        mockProject(7L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, 3L, 7L, "MARKET_PM", "APPROVE", "通过"),
            mockReview(92L, 3L, 7L, "RD_PM", null, null)));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, 3L, 21L, "PASS", null, null, null),
            mockElementResult(502L, 3L, 22L, "CONDITIONAL", "补测试报告", null, "OPEN"),
            mockElementResult(503L, 3L, 23L, "FAIL", null, "oss://evid/23.pdf", null)));

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.conclusion-draft", null, 3L, null));

        AiSuggestResp.Card card = resp.card();
        assertNotNull(card);
        assertCardAlignsWithCatalog(card, "gate.conclusion-draft");
        assertEquals("G4", card.data().get("gateCode"));
        List<?> reviews = (List<?>) card.data().get("reviews");
        assertEquals(2, reviews.size());
        assertEquals("MARKET_PM", ((Map<?, ?>) reviews.get(0)).get("reviewerType"));
        assertNull(((Map<?, ?>) reviews.get(1)).get("decision"), "待签行 decision 原样 null（不编造值）");
        assertEquals(1L, card.data().get("passCount"));
        assertEquals(1L, card.data().get("conditionalCount"));
        assertEquals(1L, card.data().get("failCount"));
        assertEquals(List.of(91L, 92L), card.sourceRefs().get("reviewIds"));
    }

    @Test
    @DisplayName("project.create.suggest 正常出卡：card 与 Catalog 逐字段对账（projects 锚事实=源行投影值）")
    void normalProjectCharterReconcilesFieldByField() {
        enableModelAndGateway("## 立项要点");
        mockProject(7L);

        AiSuggestResp resp = service.suggest(PM,
            new AiSuggestReq("project.create.suggest", 7L, null, "做一款扫地机器人"));

        AiSuggestResp.Card card = resp.card();
        assertNotNull(card);
        assertCardAlignsWithCatalog(card, "project.create.suggest");
        assertEquals(7L, card.data().get("contextProjectId"));
        assertEquals("PRJ-7", card.data().get("contextProjectCode"));
        assertEquals("示例项目", card.data().get("contextProjectName"));
        assertEquals("PLAN", card.data().get("contextCurrentStage"));
        assertEquals(5L, card.data().get("contextProductId"));
        assertEquals(7L, card.sourceRefs().get("projectId"));
    }

    @Test
    @DisplayName("demand.create.from-requirement 正常出卡：card 与 Catalog 逐字段对账（projects+requirements=源行投影值）")
    void normalDemandDraftReconcilesFieldByField() {
        enableModelAndGateway("## 需求单草稿");
        mockProject(7L);
        when(requirementMapper.selectList(any())).thenReturn(List.of(
            mockRequirement(31L, 7L, "游客需求-扫地机", "SUBMITTED", "PORTAL_GUEST"),
            mockRequirement(32L, 7L, "内部需求-基站自清洁", "ACCEPTED", "INTERNAL")));

        AiSuggestResp resp = service.suggest(PM,
            new AiSuggestReq("demand.create.from-requirement", 7L, null, "客户要一台扫地机器人"));

        AiSuggestResp.Card card = resp.card();
        assertNotNull(card);
        assertCardAlignsWithCatalog(card, "demand.create.from-requirement");
        assertEquals(7L, card.data().get("contextProjectId"));
        assertEquals("PRJ-7", card.data().get("contextProjectCode"));
        assertEquals("示例项目", card.data().get("contextProjectName"));
        List<?> reqs = (List<?>) card.data().get("requirements");
        assertEquals(2, reqs.size());
        assertEquals(31L, ((Map<?, ?>) reqs.get(0)).get("requirementId"));
        assertEquals("游客需求-扫地机", ((Map<?, ?>) reqs.get(0)).get("title"));
        assertEquals("PORTAL_GUEST", ((Map<?, ?>) reqs.get(0)).get("source"));
        assertEquals(List.of(31L, 32L), card.sourceRefs().get("requirementIds"));
        assertEquals(7L, card.sourceRefs().get("projectId"));
    }

    // ---- ⑤ 回归：3 轻场景零影响 ----

    @ParameterizedTest(name = "轻场景 {0} 零影响回归（无 card、不读 Catalog、审计载荷零变化）")
    @ValueSource(strings = {"workbench.next-step", "workbench.risk-warning", "project.summary.refresh"})
    void lightScenesZeroImpact(String scene) {
        enableModelAndGateway("建议正文");
        mockProject(7L);
        when(workbenchService.summary(any(), any())).thenReturn(Map.of(
            "stats", Map.of("pending", 1, "overdue", 0, "completed", 2)));

        AiSuggestResp resp = service.suggest(PM,
            new AiSuggestReq(scene, scene.startsWith("workbench.") ? null : 7L, null, null));

        assertNull(resp.card(), scene + " 不应产出 card");
        assertEquals("建议正文", resp.markdown());
        verify(systemConfigService, never()).getValue(anyString(), any());
        List<AuditLog> rows = capturedAuditRows();
        assertEquals(1, rows.size(), scene + " 审计行数零变化");
        String after = rows.get(0).getAfterData();
        assertFalse(after.contains("cardType"), after);
        assertFalse(after.contains("SCHEMA_DROP"), after);
        assertFalse(after.contains("CARD_REJECT"), after);
    }


    // ---- ⑥ P1-04 对账哨兵：fixture ↔ DB Catalog 双份对账 + 4 场景 card ↔ DB Catalog 对账 ----

    @Test
    @DisplayName("P1-04 哨兵：fixture ↔ DB Catalog 导出双份 schema 逐字段双向对账（4 卡零增删：type/version/scene/shape + fields/itemFields 的 name/type/source + sourceRefs）")
    void catalogFixtureReconcilesWithDbExportFieldByField() throws Exception {
        JsonNode fixture = JSON.readTree(CATALOG_JSON);
        JsonNode db = JSON.readTree(CATALOG_DB_EXPORT);
        assertEquals(db.path("catalogVersion").asInt(), fixture.path("catalogVersion").asInt(), "catalogVersion 对齐");
        assertEquals(4, db.get("cards").size(), "DB Catalog 含 4 卡");
        assertEquals(db.get("cards").size(), fixture.get("cards").size(), "卡片数零增删");
        for (JsonNode dbCard : db.get("cards")) {
            String type = dbCard.path("type").asText();
            JsonNode fxCard = cardDefByType(CATALOG_JSON, type);
            assertEquals(type, fxCard.path("type").asText(), type + " type 双向对齐");
            assertEquals(dbCard.path("version").asInt(), fxCard.path("version").asInt(), type + " version 零增删");
            assertEquals(dbCard.path("scene").asText(), fxCard.path("scene").asText(), type + " scene 对齐");
            assertEquals(dbCard.path("shape").asText(), fxCard.path("shape").asText(), type + " shape 对齐");
            assertFieldSeqSameSchema(dbCard.get("fields"), fxCard.get("fields"), type + ".fields");
            List<String> dbRefs = refKeyList(dbCard);
            List<String> fxRefs = refKeyList(fxCard);
            assertEquals(new LinkedHashSet<>(dbRefs), new LinkedHashSet<>(fxRefs), type + " sourceRefs 逐名双向零增删");
            assertEquals(dbRefs.size(), fxRefs.size(), type + " sourceRefs 数量零增删");
        }
    }

    @Test
    @DisplayName("P1-04 哨兵：4 场景 card schema ↔ DB Catalog 逐字段双向对账（type/version/data 字段名与类型/嵌套 itemFields/sourceRefs 零增删；双份 Catalog 组装结果全等）")
    void fourSceneCardsReconcileWithDbCatalogFieldByField() {
        for (String scene : List.of("gate.precheck-checklist", "gate.conclusion-draft",
            "project.create.suggest", "demand.create.from-requirement")) {
            Map<String, Object> facts = sentinelFacts(scene);
            Map<String, Object> refs = sentinelRefs(scene);
            AiSuggestResp.Card fromDb = AiSuggestionService.assembleCard(scene, CATALOG_DB_EXPORT, facts, refs);
            AiSuggestResp.Card fromFixture = AiSuggestionService.assembleCard(scene, CATALOG_JSON, facts, refs);
            assertNotNull(fromDb, scene + " 应按 DB Catalog 出卡");
            assertEquals(fromFixture, fromDb, scene + " 双份 Catalog 组装结果全等（fixture↔DB 双份对账）");
            assertCardSchemaBidirectional(fromDb, cardDefFromCatalog(CATALOG_DB_EXPORT, scene), scene);
        }
    }
    // ---- 对账与工具 ----

    /** card 与 Catalog 逐项对账：type/version 一致、data 字段名集合与 fields 逐名相等、sourceRefs 与声明逐名相等。 */
    private void assertCardAlignsWithCatalog(AiSuggestResp.Card card, String scene) {
        JsonNode def = cardDefFromCatalog(CATALOG_JSON, scene);
        assertEquals(def.path("type").asText(), card.type(), scene + " type 对齐 Catalog");
        assertEquals(def.path("version").asInt(), card.version(), scene + " version 对齐 Catalog");
        List<String> schemaNames = new ArrayList<>();
        for (JsonNode f : def.get("fields")) {
            schemaNames.add(f.path("name").asText());
        }
        assertEquals(new LinkedHashSet<>(schemaNames), card.data().keySet(),
            scene + " data 字段名集合与 Catalog 逐名对齐（无缺无多）");
        List<String> refKeys = new ArrayList<>();
        for (JsonNode k : def.get("sourceRefs")) {
            refKeys.add(k.asText());
        }
        assertEquals(new LinkedHashSet<>(refKeys), card.sourceRefs().keySet(),
            scene + " sourceRefs 键与 Catalog 声明逐名对齐");
    }

    private JsonNode cardDefFromCatalog(String catalogJson, String scene) {
        try {
            for (JsonNode c : JSON.readTree(catalogJson).get("cards")) {
                if (scene.equals(c.path("scene").asText())) {
                    return c;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Catalog fixture 解析失败", e);
        }
        throw new IllegalStateException("Catalog 缺 scene: " + scene);
    }

    /** Catalog 契约变异：给 gate.precheck 的 sourceRefs 追加一个声明键（模拟 schema 与场景能力不匹配）。 */
    private String catalogWithExtraGateRef(String extraRef) {
        try {
            JsonNode root = JSON.readTree(CATALOG_JSON);
            ObjectNode precheck = (ObjectNode) root.get("cards").get(0);
            ((ArrayNode) precheck.get("sourceRefs")).add(extraRef);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Catalog 变异失败", e);
        }
    }

    private List<AuditLog> capturedAuditRows() {
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).append(cap.capture());
        return cap.getAllValues();
    }

    /** C08 红线：AI 建议链零业务表写入（audit_logs 落审计除外）。 */
    private void verifyNoBusinessWrites() {
        verify(projectMapper, never()).insert(any(Project.class));
        verify(projectMapper, never()).updateById(any(Project.class));
        verify(gateReviewMapper, never()).insert(any(GateReview.class));
        verify(gateReviewMapper, never()).updateById(any(GateReview.class));
        verify(gateElementResultMapper, never()).insert(any(GateElementResult.class));
        verify(gateElementResultMapper, never()).updateById(any(GateElementResult.class));
        verify(requirementMapper, never()).insert(any(Requirement.class));
        verify(requirementMapper, never()).updateById(any(Requirement.class));
    }

    // ---- P1-04 哨兵工具（双份对账断言） ----

    /** 按 type 取卡定义（哨兵双份对账用）。 */
    private JsonNode cardDefByType(String catalogJson, String type) {
        try {
            for (JsonNode c : JSON.readTree(catalogJson).get("cards")) {
                if (type.equals(c.path("type").asText())) {
                    return c;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Catalog 解析失败", e);
        }
        throw new IllegalStateException("Catalog 缺 type: " + type);
    }

    /** 卡定义的 sourceRefs 声明键（有序）。 */
    private List<String> refKeyList(JsonNode def) {
        List<String> keys = new ArrayList<>();
        for (JsonNode k : def.get("sourceRefs")) {
            keys.add(k.asText());
        }
        return keys;
    }

    /** fields/itemFields 序列逐字段对账（name/type/source + itemFields 递归；序位+数量双向零增删）。 */
    private void assertFieldSeqSameSchema(JsonNode dbFields, JsonNode fxFields, String where) {
        assertNotNull(dbFields, where + " DB 侧存在");
        assertNotNull(fxFields, where + " fixture 侧存在");
        assertEquals(dbFields.size(), fxFields.size(), where + " 字段数零增删");
        for (int i = 0; i < dbFields.size(); i++) {
            JsonNode d = dbFields.get(i);
            JsonNode f = fxFields.get(i);
            String w = where + "[" + d.path("name").asText() + "]";
            assertEquals(d.path("name").asText(), f.path("name").asText(), w + " name");
            assertEquals(d.path("type").asText(), f.path("type").asText(), w + " type");
            assertEquals(d.path("source").asText(), f.path("source").asText(), w + " source");
            assertEquals(d.has("itemFields"), f.has("itemFields"), w + " itemFields 有无对齐");
            if (d.has("itemFields")) {
                assertFieldSeqSameSchema(d.get("itemFields"), f.get("itemFields"), w + ".itemFields");
            }
        }
    }

    /** card schema ↔ DB Catalog 逐字段双向对账（零增删）：type/version + data 名/型/嵌套 + sourceRefs。 */
    private void assertCardSchemaBidirectional(AiSuggestResp.Card card, JsonNode def, String scene) {
        assertEquals(def.path("type").asText(), card.type(), scene + " type 对齐 DB Catalog");
        assertEquals(def.path("version").asInt(), card.version(), scene + " version 对齐 DB Catalog");
        List<String> declared = new ArrayList<>();
        for (JsonNode f : def.get("fields")) {
            declared.add(f.path("name").asText());
        }
        assertTrue(card.data().keySet().containsAll(declared), scene + " DB Catalog→card 字段零缺失：" + declared);
        assertTrue(declared.containsAll(card.data().keySet()), scene + " card→DB Catalog 字段零增项：" + card.data().keySet());
        assertEquals(declared.size(), card.data().size(), scene + " data 字段数零增删");
        for (JsonNode f : def.get("fields")) {
            assertValueTypeMatches(scene + "." + f.path("name").asText(),
                f.path("type").asText(), f.get("itemFields"), card.data().get(f.path("name").asText()));
        }
        List<String> refDeclared = refKeyList(def);
        assertTrue(card.sourceRefs().keySet().containsAll(refDeclared), scene + " sourceRefs 零缺失");
        assertTrue(refDeclared.containsAll(card.sourceRefs().keySet()), scene + " sourceRefs 零增项");
        assertEquals(refDeclared.size(), card.sourceRefs().size(), scene + " sourceRefs 数量零增删");
    }

    /** 值类型 ↔ Catalog type 对账（含 array<object> 的 itemFields 子字段双向零增删 + 子类型）。 */
    private void assertValueTypeMatches(String where, String type, JsonNode itemFields, Object value) {
        switch (type) {
            case "string" -> assertTrue(value == null || value instanceof String, where + " 应为 string，实际 " + value);
            case "number" -> assertTrue(value == null || value instanceof Number, where + " 应为 number，实际 " + value);
            case "array<object>" -> {
                assertTrue(value instanceof List<?>, where + " 应为 array<object>，实际 " + value);
                List<String> subNames = new ArrayList<>();
                for (JsonNode f : itemFields) {
                    subNames.add(f.path("name").asText());
                }
                for (Object item : (List<?>) value) {
                    assertTrue(item instanceof Map<?, ?>, where + " 数组元素应为 object");
                    Map<?, ?> m = (Map<?, ?>) item;
                    assertTrue(m.keySet().containsAll(subNames), where + " itemFields→item 子字段零缺失：" + subNames);
                    assertTrue(subNames.containsAll(m.keySet()), where + " item→itemFields 子字段零增项：" + m.keySet());
                    assertEquals(subNames.size(), m.size(), where + " 数组子字段数零增删");
                    for (JsonNode f : itemFields) {
                        Object sub = m.get(f.path("name").asText());
                        String subType = f.path("type").asText();
                        assertTrue("string".equals(subType) ? sub == null || sub instanceof String
                            : sub == null || sub instanceof Number,
                            where + "." + f.path("name").asText() + " 类型应为 " + subType + "，实际 " + sub);
                    }
                }
            }
            default -> throw new IllegalStateException("DB Catalog 未知类型 " + type);
        }
    }

    /** 哨兵样本 facts（4 场景全字段覆盖；值仅作类型样本，R3 真值链路由④ 正向用例覆盖）。 */
    private Map<String, Object> sentinelFacts(String scene) {
        Map<String, Object> facts = new LinkedHashMap<>();
        switch (scene) {
            case "gate.precheck-checklist" -> {
                facts.put("gateCode", "G4");
                facts.put("round", 1);
                facts.put("reviewCount", 1);
                facts.put("totalElements", 1);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("elementId", 21L);
                item.put("result", "PASS");
                item.put("conditionNote", null);
                item.put("evidenceRef", null);
                item.put("leftoverStatus", null);
                facts.put("items", List.of(item));
            }
            case "gate.conclusion-draft" -> {
                facts.put("gateCode", "G4");
                Map<String, Object> review = new LinkedHashMap<>();
                review.put("reviewerType", "MARKET_PM");
                review.put("decision", "APPROVE");
                review.put("opinion", "通过");
                review.put("round", 1);
                facts.put("reviews", List.of(review));
                facts.put("passCount", 1L);
                facts.put("conditionalCount", 0L);
                facts.put("failCount", 0L);
            }
            case "project.create.suggest" -> {
                facts.put("contextProjectId", 7L);
                facts.put("contextProjectCode", "PRJ-7");
                facts.put("contextProjectName", "示例项目");
                facts.put("contextCurrentStage", "PLAN");
                facts.put("contextProductId", 5L);
            }
            case "demand.create.from-requirement" -> {
                facts.put("contextProjectId", 7L);
                facts.put("contextProjectCode", "PRJ-7");
                facts.put("contextProjectName", "示例项目");
                Map<String, Object> reqRow = new LinkedHashMap<>();
                reqRow.put("requirementId", 31L);
                reqRow.put("title", "游客需求-扫地机");
                reqRow.put("status", "SUBMITTED");
                reqRow.put("source", "PORTAL_GUEST");
                facts.put("requirements", List.of(reqRow));
            }
            default -> throw new IllegalStateException("哨兵未覆盖 scene: " + scene);
        }
        return facts;
    }

    /** 哨兵样本 sourceRefs（与 sentinelFacts 同场景配套，键=Catalog 声明键）。 */
    private Map<String, Object> sentinelRefs(String scene) {
        Map<String, Object> refs = new LinkedHashMap<>();
        switch (scene) {
            case "gate.precheck-checklist", "gate.conclusion-draft" -> {
                refs.put("gateId", 3L);
                refs.put("reviewIds", List.of(91L));
                refs.put("elementResultIds", List.of(501L));
            }
            case "project.create.suggest" -> refs.put("projectId", 7L);
            case "demand.create.from-requirement" -> {
                refs.put("projectId", 7L);
                refs.put("requirementIds", List.of(31L));
            }
            default -> throw new IllegalStateException("哨兵未覆盖 scene: " + scene);
        }
        return refs;
    }
}
