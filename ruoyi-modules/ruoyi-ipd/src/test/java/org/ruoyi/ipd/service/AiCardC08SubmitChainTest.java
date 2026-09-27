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
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.ipd.dto.AiCopilotResp;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
 * R232-P1-08（C08 收口）：卡片建议链红线断言测试（负向优先）——
 * 「每张带提交按钮的卡 = 一次既有 /api/v1 真人端点调用 + 审计三件套落痕」的后端防线面。
 *
 * <p>红线四面（本类锁定 ①②③，④ 为 HTTP/DB 留证见文末证据段）：
 * <ol>
 *   <li><b>AI 零业务写入（负向 SQL 思路）</b>：AiSuggestionService 卡片/建议链对业务表
 *       零写入——仿既有 verifyNoBusinessWrites 模式并扩展：5 Mapper（Gate/Project/Demand
 *       提交面全集：ProjectMapper/ProjectMemberMapper/GateReviewMapper/GateElementResultMapper/
 *       RequirementMapper）insert/updateById 恒 never，且全写方法口径扫描（insert|update|delete|
 *       save|upsert|replace 任意重载）零命中；AI 系统身份（SYSTEM_ACTOR personId=0）走链路同样零写入；
 *       降级/失败/拒卡路径同样零写入。</li>
 *   <li><b>审计三件套落痕</b>：AI_SUGGEST 行 aiRole 恒 "suggestion" ∈ 既有 7 值白名单
 *       （draft|precheck|summarize|copilot_answer|streaming|agent_exec|suggestion，禁自造新值）、
 *       aiModel 非空、prompt 原文不入库（只 promptLen）；SCHEMA_DROP / CARD_REJECT:reason
 *       留痕格式逐字段断言（合法 JSON、行内键集、丢弃路径、拒因）；requireAiTrail 白名单门禁
 *       双向实证（7 值放行 + 决策语义词拒入）。</li>
 *   <li><b>mode 防御</b>：按既有语义断言（不发明语义）——mode 非建议链输入，
 *       mode!=='suggest'（如 "auto"）载荷在 DTO 边界被防御性忽略，链路行为与无 mode 全等
 *       （不产生卡差异、不落差异审计、零业务写入）；后端唯一带 mode 的出口（copilot
 *       fillPayload / AI_FILL 审计）恒 "suggest"，永不产出 auto。</li>
 *   <li><b>HTTP 真人提交链留证</b>（不在单测内做活体断言，防假红/假绿；证据见文末）。</li>
 * </ol>
 *
 * <p>mock 合法性（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md 三硬规则）：
 * mock 行按 ipd_dev DDL NOT NULL 实证构造（gate_reviews: id/gate_id/reviewer_type/reviewer_id/
 * due_at/round；gate_element_results: id/gate_id/element_id/result；requirements: id/title/
 * query_code/status/source），业务写入规则组合同 AiSuggestionCardTest。纯 mock 用例未覆盖
 * DDL 合法性。
 *
 * <p>================================ C08 ④ HTTP/DB 留证（2026-09-27，16039 活体实证） ================================
 * <pre>
 * [HTTP 真人提交链] POST /api/v1/gates/2098386333478199298/sign（既有端点 → GateReviewController.sign
 *   → GateReviewService.sign L207；真人 sa-token 会话 ipd-rd/RD_PM personId=900104，非 AI 身份）：
 *   HTTP 200 / code=0，data={id:2104192409268248578, gateId:2098386333478199298, reviewerType:RD_PM,
 *   decision:APPROVE, round:1, signedAt:2026-09-27T20:52:19+08}。
 * [DB 审计落痕回读] audit_logs seq 7204→7206；GATE_SIGN 行 seq=7206 operator_id=900104（真人）
 *   after_data={"decision":"APPROVE","opinion":"C08收口留证-真人签署验证","round":1}（无 prompt 原文）。
 * [DB AI_SUGGEST 三件套抽样] seq 7194/7195：aiAssisted=true、aiModel="test-rag-20260923" 非空、
 *   aiRole="suggestion" ∈7 值、promptLen=10/8 数值键在、after_data 无 userPrompt 原文。
 * [负向 SQL] SELECT COUNT(*) WHERE create_by=0（AI_SYSTEM_PERSON_ID）：gate_reviews=0、
 *   gate_element_results=0、projects=0、requirements=0 —— 无 AI 系统身份写入业务表。
 * [观察项] 签署行 create_by=-1（框架无登录态默认值；reviewer_id=900104 真人、非 AI 身份 0），非红线违例。
 * mysql 只读探针凭证经 defaults-extra-file，未上命令行；本类不留活体依赖断言（防假红/假绿）。
 * </pre>
 */
@Tag("dev")
@DisplayName("R232-P1-08 C08 收口：卡片建议链红线断言（AI 零业务写入 + 审计三件套 + mode 防御，纯 mock）")
class AiCardC08SubmitChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 既有 aiRole 7 值白名单（AI-审计三件套规约；AuditEventData.AI_ROLES 同文，禁自造新值）。 */
    private static final Set<String> AI_ROLES_7 =
        Set.of("draft", "precheck", "summarize", "copilot_answer", "streaming", "agent_exec", "suggestion");

    /** prompt 原文哨兵（BR-AI-04：断言此串绝不入审计载荷）。 */
    private static final String PROMPT_SENTINEL = "C08原文哨兵XJ7-扫地机器人立项素材";

    /** 负向 SQL 全写方法口径（MyBatis-Plus BaseMapper 及子类任意写重载）。 */
    private static final Pattern WRITE_METHOD = Pattern.compile("^(insert|update|delete|save|upsert|replace)");

    /** Catalog fixture 复用 P1-02 同文（唯一 fixture 源防漂移）。 */
    private static final String CATALOG_JSON = AiSuggestionCardTest.CATALOG_JSON;

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
    /** AI 系统身份（AiExecutionEngine.SYSTEM_ACTOR 同文：AI_SYSTEM_PERSON_ID=0）。 */
    private static final IpdActor AI_SYSTEM = new IpdActor(0L, "system", "SYSTEM", null);

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
            .withClock(Clock.fixed(Instant.parse("2026-09-27T09:00:00Z"), ZoneId.of("UTC")));
        when(systemConfigService.getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any()))
            .thenReturn(CATALOG_JSON);
    }

    // ================================ 红线面① AI 零业务写入（负向 SQL 思路） ================================

    @ParameterizedTest(name = "场景 {0}：AI 建议/卡片链对业务表零写入（audit 落痕除外）")
    @ValueSource(strings = {"workbench.next-step", "workbench.risk-warning", "project.summary.refresh",
        "project.create.suggest", "demand.create.from-requirement",
        "gate.precheck-checklist", "gate.conclusion-draft"})
    void suggestionChainZeroBusinessWritesAllSevenScenes(String scene) {
        AiSuggestReq req = stubScene(scene);
        enableModelAndGateway("## 建议正文");

        service.suggest(SA, req);

        verifyNoBusinessWrites();
        List<AuditLog> rows = capturedAuditRows();
        assertFalse(rows.isEmpty(), "AI_SUGGEST 审计必须落痕（审计是链路唯一写入面）");
    }

    @Test
    @DisplayName("AI 系统身份（SYSTEM_ACTOR personId=0）走建议链：业务表同样零写入（负向 SQL：无 AI 系统身份业务写入）")
    void aiSystemIdentityCannotWriteBusinessTables() {
        mockProject(7L);
        mockGateRows(3L);
        when(workbenchService.summary(any(), any())).thenReturn(Map.of(
            "stats", Map.of("pending", 1, "overdue", 0, "completed", 2)));
        enableModelAndGateway("## 建议正文");

        service.suggest(AI_SYSTEM, new AiSuggestReq("workbench.next-step", null, null, null));
        service.suggest(AI_SYSTEM, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));

        verifyNoBusinessWrites();
        for (AuditLog row : capturedAuditRows()) {
            assertEquals(0L, row.getOperatorId(), "AI 系统身份调用仅允许审计留痕，业务表零写入");
        }
    }

    @Test
    @DisplayName("降级/真调失败/拒卡三路异常路径：业务表零写入（AI 只建议，任何失败不得落业务行）")
    void degradedFailedAndRejectPathsZeroBusinessWrites() {
        // ① 模型未启用 → 降级应答
        when(modelConfigService.currentEnabled()).thenThrow(new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "无模型"));
        mockProject(7L);
        AiSuggestResp degraded = service.suggest(SA, new AiSuggestReq("project.summary.refresh", 7L, null, null));
        assertTrue(degraded.degraded(), "模型未启用走降级应答");
        // ② 真调失败 → INTERNAL_ERROR（审计 FAIL 落痕）；reset 清掉 thenThrow 桩位再重桩
        Mockito.reset(modelConfigService);
        enableModelAndGateway(null);
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.fail("TIMEOUT", "30s 超时", 30000L));
        assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("project.summary.refresh", 7L, null, null)));
        // ③ R3 回读对账失败 → 拒出卡降级纯文本
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok("## 文本", 1, 1, 1L));
        mockGateRows(3L);
        List<GateElementResult> oneRow = List.of(mockElementResult(501L, 3L, 21L, "PASS", null, null, null));
        when(gateElementResultMapper.selectList(any()))
            .thenReturn(oneRow).thenReturn(oneRow).thenThrow(new IllegalStateException("reread down"));
        AiSuggestResp rejected = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));
        assertNull(rejected.card(), "回读对账失败必须拒出卡");

        verifyNoBusinessWrites();
    }

    // ================================ 红线面② 审计三件套落痕 ================================

    @Test
    @DisplayName("成功出卡审计三件套逐字段断言：aiRole=suggestion∈7 值、aiModel 非空、promptLen 记长不记原文、键集精确")
    void auditThreePieceFieldByFieldOnSuccess() {
        mockProject(7L);
        mockGateRows(3L);
        enableModelAndGateway("## 预检清单");

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, null));
        assertNotNull(resp.card(), "正常应出卡");

        List<AuditLog> rows = capturedAuditRows();
        assertEquals(1, rows.size(), "正常出卡仅主审计行（无事件行）");
        AuditLog row = rows.get(0);
        assertEquals("AI_SUGGEST", row.getAction());
        assertEquals("AI_SUGGESTION", row.getEntityType());
        assertEquals(SA.id(), row.getOperatorId());
        assertEquals(SA.role(), row.getOperatorRole());
        JsonNode after = parse(row.getAfterData());
        // 键集精确（禁恒真：多键少键都红）
        assertEquals(Set.of("aiAssisted", "aiModel", "aiRole", "scene", "projectId", "entityId",
                "status", "tokenPrompt", "tokenCompletion", "latencyMs", "promptLen", "cardType"),
            keySet(after), "审计载荷键集精确对齐");
        assertTrue(after.get("aiAssisted").asBoolean(), "aiAssisted=true");
        assertEquals("suggestion", after.get("aiRole").asText(), "卡片建议链 aiRole 恒 suggestion");
        assertTrue(AI_ROLES_7.contains(after.get("aiRole").asText()), "aiRole ∈ 既有 7 值白名单");
        assertEquals("mock-mini", after.get("aiModel").asText(), "aiModel=真模型名");
        assertEquals("gate.precheck-checklist", after.get("scene").asText());
        assertTrue(after.get("projectId").isNull(), "gate 场景 projectId 为空，审计如实记 null");
        assertEquals(3L, after.get("entityId").asLong());
        assertEquals("ok", after.get("status").asText());
        assertEquals(120, after.get("tokenPrompt").asInt());
        assertEquals(45, after.get("tokenCompletion").asInt());
        assertEquals(0L, after.get("latencyMs").asLong(), "固定时钟 latencyMs=0");
        assertEquals(0, after.get("promptLen").asInt(), "无 userPrompt 时 promptLen=0");
        assertEquals("gate.precheck", after.get("cardType").asText(), "cardType 仅元数据");
        // 三件套门禁实证：载荷过 requireAiTrail 不抛（白名单门禁在位且放行）
        AuditEventData.requireAiTrail(row.getAfterData(), "after_data");
    }

    @Test
    @DisplayName("aiRole 7 值白名单门禁双向实证：7 值全放行、决策语义词（approve/reject/decide）与缺 aiModel/aiRole 拒入")
    void aiRoleWhitelistGateAcceptsSevenRejectsDecisionRoles() {
        for (String role : AI_ROLES_7) {
            String payload = AuditEventData.json("aiAssisted", true, "aiModel", "m", "aiRole", role);
            AuditEventData.requireAiTrail(payload, "after_data"); // 不抛=放行
        }
        for (String decisionRole : List.of("approve", "reject", "decide", "sign", "auto")) {
            String payload = AuditEventData.json("aiAssisted", true, "aiModel", "m", "aiRole", decisionRole);
            assertThrows(DataIntegrityViolationException.class,
                () -> AuditEventData.requireAiTrail(payload, "after_data"),
                "决策语义 aiRole 必须拒入：" + decisionRole);
        }
        assertThrows(DataIntegrityViolationException.class, () -> AuditEventData.requireAiTrail(
            AuditEventData.json("aiAssisted", true, "aiModel", "m"), "after_data"), "缺 aiRole 必须拒入");
        assertThrows(DataIntegrityViolationException.class, () -> AuditEventData.requireAiTrail(
            AuditEventData.json("aiAssisted", true, "aiModel", " ", "aiRole", "suggestion"), "after_data"),
            "aiAssisted=true 缺非空 aiModel 必须拒入");
    }

    @Test
    @DisplayName("BR-AI-04：userPrompt 原文绝不入库（成功 + SCHEMA_DROP 两路审计均只记 promptLen）")
    void promptRawNeverPersistedOnlyPromptLen() {
        mockProject(7L);
        enableModelAndGateway("## 立项要点");
        service.suggest(PM, new AiSuggestReq("project.create.suggest", 7L, null, PROMPT_SENTINEL));

        // SCHEMA_DROP 路径同样只记 promptLen
        mockGateRows(3L);
        Map<String, Object> facts = baseGateFacts();
        facts.put("aiSuggestedDecision", "APPROVE");
        service.buildCardChecked(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, PROMPT_SENTINEL),
            CATALOG_JSON, facts, baseGateRefs(), "mock-mini");

        List<AuditLog> rows = capturedAuditRows();
        assertTrue(rows.size() >= 2, "成功行 + SCHEMA_DROP 事件行");
        for (AuditLog row : rows) {
            String after = row.getAfterData();
            assertFalse(after.contains("C08原文哨兵"), "prompt 原文绝不入审计：" + after);
            assertTrue(after.contains("\"promptLen\":" + PROMPT_SENTINEL.length()),
                "必须只记 promptLen=" + PROMPT_SENTINEL.length() + "：" + after);
            parse(after); // 合法 JSON
        }
    }

    @Test
    @DisplayName("SCHEMA_DROP 留痕格式逐字段断言：status=SCHEMA_DROP、droppedFields 三路径齐、行类型/JSON 合法")
    void schemaDropEventFormatCompliant() {
        mockGateRows(3L);
        Map<String, Object> facts = baseGateFacts();
        Map<String, Object> item1 = (Map<String, Object>) ((List<?>) facts.get("items")).get(0);
        item1.put("aiConfidence", 0.93); // 越 schema 数组子字段
        facts.put("aiSuggestedDecision", "APPROVE"); // 越 schema 顶层字段
        Map<String, Object> refs = baseGateRefs();
        refs.put("strayRef", "x"); // 越 schema sourceRefs 键

        AiSuggestResp.Card card = service.buildCardChecked(SA,
            new AiSuggestReq("gate.precheck-checklist", null, 3L, null), CATALOG_JSON, facts, refs, "mock-mini");

        assertNotNull(card, "白名单内字段应正常出卡");
        List<AuditLog> rows = capturedAuditRows();
        assertEquals(1, rows.size(), "SCHEMA_DROP 事件行恰一条");
        AuditLog row = rows.get(0);
        assertEquals("AI_SUGGEST", row.getAction());
        assertEquals("AI_SUGGESTION", row.getEntityType());
        JsonNode after = parse(row.getAfterData());
        AuditEventData.requireJson(row.getAfterData(), "after_data"); // DEF-6 合法 JSON
        assertEquals("SCHEMA_DROP", after.get("status").asText());
        assertEquals("gate.precheck", after.get("cardType").asText());
        assertEquals("suggestion", after.get("aiRole").asText());
        assertEquals("mock-mini", after.get("aiModel").asText());
        ArrayNode dropped = (ArrayNode) after.get("droppedFields");
        List<String> paths = new java.util.ArrayList<>();
        dropped.forEach(n -> paths.add(n.asText()));
        assertTrue(paths.contains("aiSuggestedDecision"), "顶层丢弃路径留痕：" + paths);
        assertTrue(paths.contains("items.aiConfidence"), "数组子字段丢弃路径留痕：" + paths);
        assertTrue(paths.contains("sourceRefs.strayRef"), "sourceRefs 丢弃路径留痕：" + paths);
        AuditEventData.requireAiTrail(row.getAfterData(), "after_data");
    }

    @Test
    @DisplayName("CARD_REJECT 留痕格式逐字段断言：status=CARD_REJECT:<reason>、拒因具体、card 恒 null、markdown 保留")
    void cardRejectEventFormatCompliant() {
        // Catalog 契约变异：gate.precheck 声明 gate 场景不可能派生的 requirementIds → source_refs_missing
        when(systemConfigService.getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any()))
            .thenReturn(catalogWithExtraGateRef("requirementIds"));
        enableModelAndGateway("## 预检清单");
        mockProject(7L);
        mockGateRows(3L);

        AiSuggestResp resp = service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 3L, PROMPT_SENTINEL));

        assertNull(resp.card(), "拒出卡降级纯文本");
        assertEquals("## 预检清单", resp.markdown(), "markdown 原样保留");
        List<AuditLog> rows = capturedAuditRows();
        assertEquals(2, rows.size(), "主审计行 + CARD_REJECT 事件行");
        AuditLog reject = rows.stream().filter(r -> r.getAfterData().contains("CARD_REJECT")).findFirst()
            .orElseThrow(() -> new AssertionError("缺 CARD_REJECT 行"));
        JsonNode after = parse(reject.getAfterData());
        String status = after.get("status").asText();
        assertTrue(status.startsWith("CARD_REJECT:"), "status 必须以 CARD_REJECT: 起头：" + status);
        assertTrue(status.length() > "CARD_REJECT:".length(), "拒因必须非空：" + status);
        assertEquals("CARD_REJECT:source_refs_missing:requirementIds", status, "拒因精确到缺失键");
        assertEquals("gate.precheck", after.get("cardType").asText());
        assertEquals("suggestion", after.get("aiRole").asText());
        assertEquals(PROMPT_SENTINEL.length(), after.get("promptLen").asInt());
        assertFalse(reject.getAfterData().contains("C08原文哨兵"), "拒卡行同样不落原文");
        AuditEventData.requireJson(reject.getAfterData(), "after_data");
        AuditEventData.requireAiTrail(reject.getAfterData(), "after_data");
    }

    // ================================ 红线面③ mode 防御（既有语义，不发明） ================================

    @Test
    @DisplayName("mode!=='suggest'（auto）载荷在 DTO 边界防御性忽略：与无 mode 请求行为全等、不产卡差异、不落差异审计、零业务写入")
    void modeOtherThanSuggestDefensivelyIgnoredOnSuggestChain() throws Exception {
        mockProject(7L);
        enableModelAndGateway("## 立项要点");
        // 前端防御性忽略（action-detail mode!=='suggest'）之外，后端入参侧同样防御：mode 非链路输入
        AiSuggestReq withMode = JSON.readValue(
            "{\"scene\":\"project.create.suggest\",\"projectId\":7,\"userPrompt\":\"做一款扫地机器人\",\"mode\":\"auto\"}",
            AiSuggestReq.class);
        AiSuggestReq withoutMode = new AiSuggestReq("project.create.suggest", 7L, null, "做一款扫地机器人");
        assertEquals(withoutMode, withMode, "mode≠'suggest' 被忽略，请求与无 mode 全等");

        AiSuggestResp respWith = service.suggest(PM, withMode);
        AiSuggestResp respWithout = service.suggest(PM, withoutMode);

        assertEquals(respWithout, respWith, "行为全等：响应（含 card）与无 mode 逐字段相同");
        List<AuditLog> rows = capturedAuditRows();
        assertEquals(2, rows.size(), "两次调用各一行审计——mode 不落差异审计");
        assertEquals(rows.get(0).getAfterData(), rows.get(1).getAfterData(), "审计载荷全等（无 mode 键混入）");
        assertFalse(rows.get(0).getAfterData().contains("auto"), "审计载荷不含 mode=auto");
        verifyNoBusinessWrites();
    }

    @Test
    @DisplayName("后端唯一 mode 出口恒 suggest：FILL_PAGE fillPayload 与 AI_FILL 审计（含 REJECTED 路径）永不产出 auto")
    void backendEmitsSuggestModeOnlyNeverAuto() {
        // copilot 副链（mode 语义唯一出处：AiCopilotService L376 fillPayload + auditFill）
        AiModelConfigService cfg = mock(AiModelConfigService.class);
        WorkbenchService wb = mock(WorkbenchService.class);
        AiGateway gw = mock(AiGateway.class);
        IAuditLogService audit = mock(IAuditLogService.class);
        ProjectMapper pm = mock(ProjectMapper.class);
        ProjectMemberMapper pmm = mock(ProjectMemberMapper.class);
        AiDocEmbeddingService docs = mock(AiDocEmbeddingService.class);
        AiExecutionTrigger trigger = mock(AiExecutionTrigger.class);
        AiCopilotService copilot = new AiCopilotService(cfg, wb, gw, audit, pm, pmm, docs, trigger)
            .withClock(Clock.fixed(Instant.parse("2026-09-27T09:00:00Z"), ZoneId.of("UTC")));
        AiModelConfig config = new AiModelConfig();
        config.setProvider("openai");
        config.setEndpointUrl("http://mock/v1");
        config.setModelName("mock-mini");
        when(cfg.currentEnabled()).thenReturn(config);
        when(cfg.decryptApiKey(config)).thenReturn("sk-x");
        Project p = new Project();
        p.setId(100L);
        p.setCode("PRJ-100");
        p.setName("示例项目");
        when(pm.selectById(100L)).thenReturn(p);
        when(gw.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok("{\"farValue\":\"0.002\",\"salary\":\"99999\"}", 10, 20, 50L));

        // ① FILL_PAGE 正常路径：fillPayload.mode 恒 suggest
        AiCopilotResp fill = copilot.chat(SA, new AiCopilotReq(100L, "帮我把基准值填了", List.of(), null,
            "{\"scene\":\"stage-action-fields\",\"actionCode\":\"C08\",\"stageActionId\":9003}"));
        assertEquals("FILL_PAGE", fill.intent());
        assertNotNull(fill.fillPayload());
        assertEquals("suggest", fill.fillPayload().get("mode"), "fillPayload.mode 恒 suggest（敏感字段红线）");
        ArgumentCaptor<String> payloadJson = ArgumentCaptor.forClass(String.class);
        verify(trigger).triggerChat(eq(100L), eq("C08"), eq(9003L), eq(1L), payloadJson.capture(), any());
        assertTrue(payloadJson.getValue().contains("\"mode\":\"suggest\""), payloadJson.getValue());

        // ② REJECTED 路径（未知 scene）：AI_FILL 审计同样 "mode":"suggest"
        when(wb.summary(any(), any())).thenReturn(Map.of("currentAdvance", Map.of(), "tasks", List.of()));
        when(gw.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok("好笑的回答", 1, 1, 5L));
        copilot.chat(SA, new AiCopilotReq(null, "帮我填一下", List.of(), null, "{\"scene\":\"hack-scene\"}"));

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit, Mockito.atLeastOnce()).append(cap.capture());
        List<AuditLog> fillRows = cap.getAllValues().stream().filter(r -> "AI_FILL".equals(r.getAction())).toList();
        assertEquals(2, fillRows.size(), "FILL_PAGE ok + REJECTED 各一条 AI_FILL");
        for (AuditLog r : fillRows) {
            assertTrue(r.getAfterData().contains("\"mode\":\"suggest\""), r.getAfterData());
            assertFalse(r.getAfterData().contains("\"mode\":\"auto\""), "永不产出 mode=auto：" + r.getAfterData());
        }
    }

    // ================================ 工具与 mock 合法性构建 ================================

    /** 场景入参与配套 mock（DDL NOT NULL 实证构造，业务写入规则组合同 AiSuggestionCardTest）。 */
    private AiSuggestReq stubScene(String scene) {
        mockProject(7L);
        when(workbenchService.summary(any(), any())).thenReturn(Map.of(
            "stats", Map.of("pending", 1, "overdue", 0, "completed", 2)));
        when(requirementMapper.selectList(any())).thenReturn(List.of(
            mockRequirement(31L, 7L, "游客需求-扫地机", "SUBMITTED", "PORTAL_GUEST")));
        return switch (scene) {
            case "workbench.next-step", "workbench.risk-warning" -> new AiSuggestReq(scene, null, null, null);
            case "project.summary.refresh" -> new AiSuggestReq(scene, 7L, null, null);
            case "project.create.suggest" -> new AiSuggestReq(scene, 7L, null, "做一款扫地机器人");
            case "demand.create.from-requirement" -> new AiSuggestReq(scene, 7L, null, "客户要一台扫地机器人");
            default -> {
                mockGateRows(3L);
                yield new AiSuggestReq(scene, null, 3L, null);
            }
        };
    }

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

    private void mockGateRows(long gateId) {
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            mockReview(91L, gateId, 7L, "MARKET_PM", "APPROVE", "证据齐")));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(
            mockElementResult(501L, gateId, 21L, "PASS", null, null, null),
            mockElementResult(502L, gateId, 22L, "CONDITIONAL", "补测试报告", null, "OPEN"),
            mockElementResult(503L, gateId, 23L, "FAIL", null, "oss://evid/23.pdf", null)));
    }

    private void enableModelAndGateway(String llmContent) {
        AiModelConfig config = new AiModelConfig();
        config.setProvider("openai");
        config.setEndpointUrl("http://mock/v1");
        config.setModelName("mock-mini");
        when(modelConfigService.currentEnabled()).thenReturn(config);
        when(modelConfigService.decryptApiKey(config)).thenReturn("sk-x");
        if (llmContent != null) {
            when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
                .thenReturn(AiChatResult.ok(llmContent, 120, 45, 1000L));
        }
    }

    /** SCHEMA_DROP 用例的合法 gate 事实面（注入越 schema 字段前的底座）。 */
    private Map<String, Object> baseGateFacts() {
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
        return facts;
    }

    private Map<String, Object> baseGateRefs() {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("gateId", 3L);
        refs.put("reviewIds", List.of(91L));
        refs.put("elementResultIds", List.of(501L, 502L, 503L));
        return refs;
    }

    /** Catalog 契约变异：给 gate.precheck 的 sourceRefs 追加一个声明键。 */
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

    private JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("审计载荷不是合法 JSON：" + json, e);
        }
    }

    private Set<String> keySet(JsonNode node) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private List<AuditLog> capturedAuditRows() {
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, Mockito.atLeastOnce()).append(cap.capture());
        return cap.getAllValues();
    }

    /** C08 红线①既有模式：5 Mapper（Gate/Project/Demand 提交面全集）insert/updateById 恒 never。 */
    private void verifyNoBusinessWrites() {
        verify(projectMapper, never()).insert(any(Project.class));
        verify(projectMapper, never()).updateById(any(Project.class));
        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
        verify(projectMemberMapper, never()).updateById(any(ProjectMember.class));
        verify(gateReviewMapper, never()).insert(any(GateReview.class));
        verify(gateReviewMapper, never()).updateById(any(GateReview.class));
        verify(gateElementResultMapper, never()).insert(any(GateElementResult.class));
        verify(gateElementResultMapper, never()).updateById(any(GateElementResult.class));
        verify(requirementMapper, never()).insert(any(Requirement.class));
        verify(requirementMapper, never()).updateById(any(Requirement.class));
        // 扩展（负向 SQL 全写方法口径）：insert|update|delete|save|upsert|replace 任意重载零命中
        assertMapperReadOnly(projectMapper, "projectMapper");
        assertMapperReadOnly(projectMemberMapper, "projectMemberMapper");
        assertMapperReadOnly(gateReviewMapper, "gateReviewMapper");
        assertMapperReadOnly(gateElementResultMapper, "gateElementResultMapper");
        assertMapperReadOnly(requirementMapper, "requirementMapper");
    }

    /** 全调用面扫描：业务 Mapper 一旦出现写方法调用即红（读方法（select/count 等）白放行）。 */
    private void assertMapperReadOnly(Object mapperMock, String label) {
        for (Invocation inv : Mockito.mockingDetails(mapperMock).getInvocations()) {
            String name = inv.getMethod().getName();
            assertFalse(WRITE_METHOD.matcher(name).find(),
                label + " 出现业务表写方法调用（AI 链路零写入红线）：" + name);
        }
    }
}
