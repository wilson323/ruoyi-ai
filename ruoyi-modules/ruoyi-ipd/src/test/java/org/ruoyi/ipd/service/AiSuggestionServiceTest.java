package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.dto.AiSuggestReq;
import org.ruoyi.ipd.dto.AiSuggestResp;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R227-C1（AI-FUSION L2）：AiSuggestionService 行为锁。
 * <p>覆盖红线：
 * <ul>
 *   <li>scene 白名单外拒绝（防任意场景拼 prompt）；</li>
 *   <li>参数必填矩阵（创建类要素材 / 项目类要 projectId / gate 要 entityId）；</li>
 *   <li>BR-AI-05 越权（非成员 NOT_FOUND「项目不可见」；gate 场景经评审归属项目校验）；</li>
 *   <li>审计三件套（aiRole=suggestion；成功 ok / 降级 FAIL 必落且不含原文）；</li>
 *   <li>模型未启用降级不抛 500（degraded=true + intent_match）；</li>
 *   <li>AI 真调失败抛 INTERNAL_ERROR；</li>
 *   <li>composePrompt 7 场景指令齐备 + 上下文/素材拼接 + 超长钳制。</li>
 * </ul>
 */
@Tag("dev")
@DisplayName("R227-C1：AI 建议服务 场景路由 + 越权 + 审计三件套")
class AiSuggestionServiceTest {

    private AiModelConfigService modelConfigService;
    private WorkbenchService workbenchService;
    private AiGateway aiGateway;
    private IAuditLogService auditLogService;
    private ProjectMapper projectMapper;
    private ProjectMemberMapper projectMemberMapper;
    private GateReviewMapper gateReviewMapper;
    private GateElementResultMapper gateElementResultMapper;
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
        service = new AiSuggestionService(modelConfigService, workbenchService, aiGateway,
            auditLogService, projectMapper, projectMemberMapper, gateReviewMapper, gateElementResultMapper)
            .withClock(Clock.fixed(Instant.parse("2026-09-26T08:00:00Z"), ZoneId.of("UTC")));
    }

    private AiModelConfig enableModel() {
        AiModelConfig config = new AiModelConfig();
        config.setProvider("openai");
        config.setEndpointUrl("http://mock/v1");
        config.setModelName("mock-mini");
        when(modelConfigService.currentEnabled()).thenReturn(config);
        when(modelConfigService.decryptApiKey(config)).thenReturn("sk-x");
        return config;
    }

    private Project visibleProject(long id) {
        Project p = new Project();
        p.setId(id);
        p.setCode("PRJ-" + id);
        p.setName("示例项目");
        p.setCurrentStage("PLAN");
        when(projectMapper.selectById(id)).thenReturn(p);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        return p;
    }

    // ---- scene 白名单与参数矩阵 ----

    @Test
    @DisplayName("白名单外 scene → PARAM_INVALID 且不调 AI 不落审计")
    void unknownSceneRejected() {
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("rm.delete.all", 1L, null, "x")));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(aiGateway, never()).chat(any(AiTestConfig.class), anyString(), anyInt(), any());
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("创建类场景缺 userPrompt → PARAM_INVALID")
    void createScenesRequirePrompt() {
        for (String scene : List.of("project.create.suggest", "demand.create.from-requirement")) {
            IpdBusinessException ex = assertThrows(IpdBusinessException.class,
                () -> service.suggest(SA, new AiSuggestReq(scene, null, null, "  ")));
            assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode(), scene);
        }
    }

    @Test
    @DisplayName("项目类场景缺 projectId → PARAM_INVALID；gate 缺 entityId → PARAM_INVALID")
    void entityParamsRequired() {
        assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("project.summary.refresh", null, null, null)));
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", 1L, null, null)));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    // ---- 越权（BR-AI-05） ----

    @Test
    @DisplayName("非项目成员 → NOT_FOUND 项目不可见（copilot 同构语义）")
    void nonMemberBlocked() {
        Project p = new Project();
        p.setId(7L);
        when(projectMapper.selectById(7L)).thenReturn(p);
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.suggest(PM, new AiSuggestReq("project.summary.refresh", 7L, null, null)));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不可见"));
    }

    @Test
    @DisplayName("gate 场景按 gateId 拉评审链经归属项目做越权校验：无评审 NOT_FOUND；项目不可见 NOT_FOUND")
    void gateReviewOwnershipChecked() {
        when(gateReviewMapper.selectList(any())).thenReturn(List.of());
        assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("gate.conclusion-draft", null, 9L, null)));

        GateReview review = new GateReview();
        review.setId(9L);
        review.setProjectId(7L);
        review.setGateId(3L);
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(review));
        when(projectMapper.selectById(7L)).thenReturn(null); // 项目也不存在
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.suggest(PM, new AiSuggestReq("gate.conclusion-draft", null, 9L, null)));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ---- 成功路径 + 审计三件套 ----

    @Test
    @DisplayName("成功：AI 真调 → markdown 返回；审计 AI_SUGGEST 三件套 aiRole=suggestion status=ok")
    void successPathAuditsTriplet() {
        enableModel();
        visibleProject(7L);
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok("## 总结\n状态正常", 120, 45, 1000L));

        AiSuggestResp resp = service.suggest(PM, new AiSuggestReq("project.summary.refresh", 7L, null, null));

        assertFalse(resp.degraded());
        assertEquals("## 总结\n状态正常", resp.markdown());
        assertEquals("mock-mini", resp.aiModel());

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        AuditLog log = cap.getValue();
        assertEquals("AI_SUGGEST", log.getAction());
        String after = log.getAfterData();
        assertTrue(after.contains("\"aiAssisted\":true"), after);
        assertTrue(after.contains("\"aiModel\":\"mock-mini\""), after);
        assertTrue(after.contains("\"aiRole\":\"suggestion\""), after);
        assertTrue(after.contains("\"status\":\"ok\""), after);
        assertTrue(after.contains("\"scene\":\"project.summary.refresh\""), after);
        // BR-AI-04：原文不进审计（本场景无 userPrompt，promptLen=0；上下文更不可能出现）
        assertFalse(after.contains("状态正常"), after);
    }

    @Test
    @DisplayName("workbench 全局维度（projectId=null）放行：走 summary 上下文真调 AI")
    void workbenchGlobalAllowed() {
        enableModel();
        when(workbenchService.summary(PM, null)).thenReturn(Map.of(
            "stats", Map.of("pending", 3, "overdue", 1, "completed", 5),
            "tasks", List.of(Map.of("title", "写章程", "status", "PENDING", "overdue", true))));
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok("建议", 10, 5, 100L));

        AiSuggestResp resp = service.suggest(PM, new AiSuggestReq("workbench.next-step", null, null, null));
        assertEquals("建议", resp.markdown());
        verify(projectMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("gate.precheck 按 gateId 渲染评审链+要素结果上下文进 prompt（前 20 条截断）")
    void gatePrecheckRendersElements() {
        enableModel();
        GateReview review = new GateReview();
        review.setId(9L);
        review.setProjectId(7L);
        review.setGateId(3L);
        review.setGateCode("G4");
        review.setRound(1);
        review.setReviewerType("MARKET_PM");
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(review));
        visibleProject(7L);
        GateElementResult r = new GateElementResult();
        r.setGateId(3L);
        r.setElementId(21L);
        r.setResult("PASS");
        r.setConditionNote("证据齐");
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of(r));
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok("清单", 10, 5, 100L));

        service.suggest(SA, new AiSuggestReq("gate.precheck-checklist", null, 9L, null));

        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);
        verify(aiGateway).chat(any(AiTestConfig.class), promptCap.capture(), anyInt(), any());
        assertTrue(promptCap.getValue().contains("G4"), promptCap.getValue());
        assertTrue(promptCap.getValue().contains("证据齐"), promptCap.getValue());
    }

    // ---- 降级与失败 ----

    @Test
    @DisplayName("模型未启用 → 降级应答不抛 500；审计 FAIL:* + aiModel=intent_match")
    void modelNotConfiguredDegrades() {
        when(modelConfigService.currentEnabled())
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "no active model"));
        visibleProject(7L);

        AiSuggestResp resp = service.suggest(PM, new AiSuggestReq("project.summary.refresh", 7L, null, null));

        assertTrue(resp.degraded());
        assertEquals("intent_match", resp.aiModel());
        assertTrue(resp.markdown().contains("暂未启用"));
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertTrue(cap.getValue().getAfterData().contains("FAIL:"), cap.getValue().getAfterData());
    }

    @Test
    @DisplayName("AI 真调失败 → INTERNAL_ERROR 且审计 FAIL（与 copilot 失败语义一致）")
    void aiFailureThrowsAndAudits() {
        enableModel();
        visibleProject(7L);
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.fail("TIMEOUT", "upstream", 100L));

        assertThrows(IpdBusinessException.class,
            () -> service.suggest(PM, new AiSuggestReq("project.summary.refresh", 7L, null, null)));
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertTrue(cap.getValue().getAfterData().contains("FAIL:TIMEOUT"), cap.getValue().getAfterData());
    }

    // ---- composePrompt 静态行为 ----

    @ParameterizedTest(name = "场景 {0} 有专属指令")
    @ValueSource(strings = {"workbench.next-step", "workbench.risk-warning", "project.summary.refresh",
        "project.create.suggest", "demand.create.from-requirement",
        "gate.precheck-checklist", "gate.conclusion-draft"})
    void everySceneHasInstruction(String scene) {
        String prompt = AiSuggestionService.composePrompt(scene, "- 上下文行\n", "原始素材");
        assertTrue(prompt.contains("【业务上下文】"), scene + ": " + prompt);
        assertTrue(prompt.contains("原始素材"), scene);
        assertTrue(prompt.contains("markdown"), prompt);
    }

    @Test
    @DisplayName("composePrompt：无上下文场景不渲染空上下文块；超长钳制 PROMPT_MAX")
    void promptBlocksAndClamp() {
        String noCtx = AiSuggestionService.composePrompt("project.create.suggest", "", "做一款扫地机器人");
        assertFalse(noCtx.contains("【业务上下文】"), noCtx);
        assertTrue(noCtx.contains("扫地机器人"), noCtx);

        String huge = AiSuggestionService.composePrompt("demand.create.from-requirement", "", "字".repeat(40_000));
        assertEquals(AiSuggestionService.PROMPT_MAX, huge.length());
    }

    @Test
    @DisplayName("renderWorkbenchContext：真实键 currentAdvance/stats/tasks 渲染且待办超 10 条截断")
    void workbenchContextRendering() {
        Map<String, Object> summary = Map.of(
            "currentAdvance", Map.of("projectCode", "PRJ-1", "projectName", "Alpha",
                "currentStage", "DEV", "actionName", "提交评审"),
            "stats", Map.of("pending", 12, "overdue", 2, "completed", 30),
            "tasks", java.util.stream.IntStream.rangeClosed(1, 12).boxed()
                .map(i -> Map.<String, Object>of("title", "T" + i, "status", "PENDING"))
                .toList());
        String ctx = AiSuggestionService.renderWorkbenchContext(summary);
        assertTrue(ctx.contains("提交评审"), ctx);
        assertTrue(ctx.contains("超期 2"), ctx);
        assertTrue(ctx.contains("仅渲染前 10 条"), ctx);
        assertFalse(ctx.contains("T11"), ctx);
    }
}
