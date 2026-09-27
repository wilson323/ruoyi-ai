package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.dto.AiSuggestReq;
import org.ruoyi.ipd.dto.AiSuggestResp;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI-P2-1：Gate 材料预审端点后端行为锁（service 层单测，Mock 装配——
 * 非真实 HTTP 链路，鉴权注解层由 Sa-Token 统一拦截、不在本测范围）。
 *
 * <p>覆盖卡面四用例 + 附加红线：
 * <ol>
 *   <li>非评审参与人 → FORBIDDEN（HTTP 403）；</li>
 *   <li>gateId 不存在 → NOT_FOUND（HTTP 404）；</li>
 *   <li>材料为空（无要素判定且无交付物）→ PARAM_INVALID（HTTP 400）；</li>
 *   <li>正常 → 结构化「已覆盖/部分/缺失 + 证据定位」且审计带 aiAssisted/precheck；</li>
 *   <li>红线锁：预审零写库（gates/gate_reviews 不 update/insert）、不阻塞（blocking=false）；</li>
 *   <li>AI 环节抛错 → 预审仍返回结构化结果（degraded 标记），审计照落；</li>
 *   <li>仲裁分歧汇总草稿：同轮双 PM 决策不一致才算分歧。</li>
 * </ol>
 *
 * <p>{@code @Tag("dev")} 必须——Surefire 按 active profile（dev）过滤 groups，
 * 无 Tag 会被静默跳过形成假绿（项目治理轮注释同款警示）。
 */
@Tag("dev")
@DisplayName("AI-P2-1：Gate 材料预审 参与人/空材料/结构化/审计 + 仲裁分歧草稿")
class GatePrecheckServiceTest {

    private GateMapper gateMapper;
    private GateElementResultMapper elementResultMapper;
    private GateReviewMapper gateReviewMapper;
    private ProjectMemberMapper projectMemberMapper;
    private GateMaterialChecker gateMaterialChecker;
    private AiSuggestionService aiSuggestionService;
    private IAuditLogService auditLogService;
    private GatePrecheckService service;

    private static final IpdActor SA = new IpdActor(1L, "sa", "SUPER_ADMIN", null);
    private static final IpdActor PM = new IpdActor(2L, "pm", "MARKET_PM", 100L);
    private static final IpdActor OUTSIDER = new IpdActor(3L, "out", "MARKET_PM", 200L);

    @BeforeEach
    void setUp() {
        gateMapper = mock(GateMapper.class);
        elementResultMapper = mock(GateElementResultMapper.class);
        gateReviewMapper = mock(GateReviewMapper.class);
        projectMemberMapper = mock(ProjectMemberMapper.class);
        gateMaterialChecker = mock(GateMaterialChecker.class);
        aiSuggestionService = mock(AiSuggestionService.class);
        auditLogService = mock(IAuditLogService.class);
        service = new GatePrecheckService(gateMapper, elementResultMapper, gateReviewMapper,
            projectMemberMapper, gateMaterialChecker, aiSuggestionService, auditLogService)
            .withClock(Clock.fixed(Instant.parse("2026-09-27T08:00:00Z"), ZoneId.of("UTC")));
    }

    private Gate gate(long id, long projectId) {
        Gate g = new Gate();
        g.setId(id);
        g.setProjectId(projectId);
        g.setGateCode("G2");
        g.setStatus("PENDING");
        g.setCurrentRound(1);
        when(gateMapper.selectById(id)).thenReturn(g);
        return g;
    }

    /** 参与人命中：project_members count=1。 */
    private void makeParticipant() {
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
    }

    private Map<String, Object> materials(long total, boolean isReady) {
        Map<String, Object> m = new HashMap<>();
        m.put("total", total);
        m.put("uploaded", 0L);
        m.put("missing", total);
        m.put("isReady", isReady);
        m.put("items", List.of());
        return m;
    }

    private GateElementResult result(long elementId, String verdict, String evidenceRef, String note) {
        GateElementResult r = new GateElementResult();
        r.setGateId(50L);
        r.setElementId(elementId);
        r.setResult(verdict);
        r.setEvidenceRef(evidenceRef);
        r.setConditionNote(note);
        return r;
    }

    // ---- 用例① 无权限 → 403 ----

    @Test
    @DisplayName("①非评审参与人 → FORBIDDEN（映射 HTTP 403），不调 AI 不落审计")
    void nonParticipantForbidden() {
        gate(50L, 7L);
        when(projectMemberMapper.selectCount(any())).thenReturn(0L); // 非项目成员

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.precheck(50L, OUTSIDER));
        assertEquals(ApiV1ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals(403, ex.getErrorCode().getHttpStatus());
        verify(aiSuggestionService, never()).suggest(any(), any());
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    // ---- 用例② gate 不存在 → 404 ----

    @Test
    @DisplayName("②gateId 不存在 → NOT_FOUND（映射 HTTP 404）")
    void gateNotFound() {
        when(gateMapper.selectById(404L)).thenReturn(null);

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.precheck(404L, SA));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals(404, ex.getErrorCode().getHttpStatus());
    }

    // ---- 用例③ 材料为空 → 400 ----

    @Test
    @DisplayName("③材料为空（零要素判定且零交付物）→ PARAM_INVALID（映射 HTTP 400）")
    void emptyMaterialsRejected() {
        gate(50L, 7L);
        makeParticipant();
        when(elementResultMapper.selectList(any())).thenReturn(List.of());
        when(gateMaterialChecker.listMaterialStatus(50L, 7L)).thenReturn(materials(0L, false));

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.precheck(50L, PM));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertEquals(400, ex.getErrorCode().getHttpStatus());
        verify(aiSuggestionService, never()).suggest(any(), any());
    }

    // ---- 用例④ 正常 → 结构化 + 审计 precheck ----

    @Test
    @DisplayName("④正常：PASS/CONDITIONAL/FAIL → COVERED/PARTIAL/MISSING + 证据定位；审计 aiAssisted+precheck")
    void happyPathStructuredAndAudited() {
        gate(50L, 7L);
        makeParticipant();
        when(elementResultMapper.selectList(any())).thenReturn(List.of(
            result(21L, "PASS", "att://charter-v3", null),
            result(22L, "CONDITIONAL", null, "等供应商确认"),
            result(23L, "FAIL", "att://reject-proof", null)));
        when(gateMaterialChecker.listMaterialStatus(50L, 7L)).thenReturn(materials(5L, false));
        when(aiSuggestionService.suggest(any(), any())).thenReturn(
            new AiSuggestResp("gate.precheck-checklist", "## 预审清单\n- 追问供应商确认", "mock-mini",
                100, 30, 900L, false));

        Map<String, Object> out = service.precheck(50L, PM);

        // 结构化覆盖统计
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertEquals(3, summary.get("total"));
        assertEquals(1, summary.get("covered"));
        assertEquals(1, summary.get("partial"));
        assertEquals(1, summary.get("missing"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals("COVERED", items.get(0).get("status"));
        assertEquals("att://charter-v3", items.get(0).get("evidenceRef")); // 证据定位
        assertEquals("PARTIAL", items.get(1).get("status"));
        assertEquals("等供应商确认", items.get(1).get("conditionNote"));
        assertEquals("MISSING", items.get(2).get("status"));
        assertEquals("## 预审清单\n- 追问供应商确认",
            ((Map<?, ?>) out.get("aiChecklist")).get("markdown"));
        // 卡面硬约束自证：不阻塞、不写决策
        assertEquals(false, out.get("blocking"));
        assertEquals(false, out.get("decisionWritten"));

        // 复用既有 suggest 链（scene 参数锁死，防另起 AI 调用轨）
        ArgumentCaptor<AiSuggestReq> reqCap = ArgumentCaptor.forClass(AiSuggestReq.class);
        verify(aiSuggestionService).suggest(any(), reqCap.capture());
        assertEquals("gate.precheck-checklist", reqCap.getValue().scene());
        assertEquals(50L, reqCap.getValue().entityId());

        // 审计三件套：aiAssisted=true / aiRole=precheck（白名单既有值）
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        AuditLog log = cap.getValue();
        assertEquals("AI_PRECHECK", log.getAction());
        assertEquals("GATE", log.getEntityType());
        String after = log.getAfterData();
        assertTrue(after.contains("\"aiAssisted\":true"), after);
        assertTrue(after.contains("\"aiRole\":\"precheck\""), after);
        assertTrue(after.contains("\"aiModel\":\"mock-mini\""), after);
        assertTrue(after.contains("\"covered\":1"), after);
    }

    // ---- 红线锁：预审零写库 ----

    @Test
    @DisplayName("红线：预审全程不写 gates/gate_reviews（无 update/insert/delete）")
    void precheckNeverWritesGateState() {
        gate(50L, 7L);
        makeParticipant();
        when(elementResultMapper.selectList(any())).thenReturn(List.of(
            result(21L, "PASS", "att://a", null)));
        when(gateMaterialChecker.listMaterialStatus(50L, 7L)).thenReturn(materials(2L, true));
        when(aiSuggestionService.suggest(any(), any())).thenReturn(
            new AiSuggestResp("gate.precheck-checklist", "清单", "mock-mini", 10, 5, 50L, false));

        service.precheck(50L, PM);

        verify(gateMapper, never()).updateById(any(Gate.class));
        verify(gateMapper, never()).insert(any(Gate.class));
        verify(gateMapper, never()).deleteById(anyLong());
        verify(gateReviewMapper, never()).insert(any(GateReview.class));
        verify(gateReviewMapper, never()).updateById(any(GateReview.class));
        verify(elementResultMapper, never()).insert(any(GateElementResult.class));
        verify(elementResultMapper, never()).updateById(any(GateElementResult.class));
    }

    // ---- AI 降级不拖垮预审 ----

    @Test
    @DisplayName("AI 建议链抛错 → 预审仍返回结构化结果，aiChecklist.degraded=true，审计照落")
    void aiFailureDegradesButPrecheckSucceeds() {
        gate(50L, 7L);
        makeParticipant();
        when(elementResultMapper.selectList(any())).thenReturn(List.of(
            result(21L, "PASS", "att://a", null)));
        when(gateMaterialChecker.listMaterialStatus(50L, 7L)).thenReturn(materials(2L, true));
        when(aiSuggestionService.suggest(any(), any()))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "AI 建议暂不可用：TIMEOUT"));

        Map<String, Object> out = service.precheck(50L, PM);

        Map<?, ?> aiChecklist = (Map<?, ?>) out.get("aiChecklist");
        assertEquals(true, aiChecklist.get("degraded"));
        assertEquals("intent_match", aiChecklist.get("aiModel"));
        Map<?, ?> summary = (Map<?, ?>) out.get("summary");
        assertEquals(1, summary.get("covered")); // 结构化部分不受 AI 影响
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertTrue(cap.getValue().getAfterData().contains("\"aiRole\":\"precheck\""),
            cap.getValue().getAfterData());
        assertTrue(cap.getValue().getAfterData().contains("\"degraded\":true"),
            cap.getValue().getAfterData());
    }

    // ---- 仲裁分歧汇总（草稿） ----

    @Test
    @DisplayName("仲裁分歧草稿：同轮 MARKET_PM/RD_PM 决策不一致记分歧；一致/未签不计")
    void arbitrationDivergenceDraft() {
        gate(50L, 7L);
        GateReview r1m = new GateReview();
        r1m.setRound(1);
        r1m.setReviewerType("MARKET_PM");
        r1m.setDecision("APPROVE");
        r1m.setOpinion("按计划走");
        GateReview r1d = new GateReview();
        r1d.setRound(1);
        r1d.setReviewerType("RD_PM");
        r1d.setDecision("REJECT");
        r1d.setOpinion("样机未过温测");
        GateReview r2m = new GateReview();
        r2m.setRound(2);
        r2m.setReviewerType("MARKET_PM");
        r2m.setDecision("APPROVE");
        GateReview r2d = new GateReview();
        r2d.setRound(2);
        r2d.setReviewerType("RD_PM");
        r2d.setDecision("APPROVE");
        GateReview unsigned = new GateReview();
        unsigned.setRound(3);
        unsigned.setReviewerType("RD_PM");
        unsigned.setDecision(null); // 未签行不参与
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(r1m, r1d, r2m, r2d, unsigned));

        Map<String, Object> out = service.arbitrationDivergences(50L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> div = (List<Map<String, Object>>) out.get("divergences");
        assertEquals(1, div.size());
        assertEquals(1, div.get(0).get("round"));
        assertEquals("APPROVE", div.get(0).get("marketDecision"));
        assertEquals("REJECT", div.get(0).get("rdDecision"));
        assertEquals("样机未过温测", div.get(0).get("rdOpinion"));
        assertFalse(div.toString().contains("round=2"));
    }
}
