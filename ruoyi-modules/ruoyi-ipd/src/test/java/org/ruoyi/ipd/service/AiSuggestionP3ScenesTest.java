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
import org.ruoyi.ipd.domain.HandoverRecord;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.domain.RequirementChange;
import org.ruoyi.ipd.dto.AiSuggestReq;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI-P3 场景包后端切片（2026-09-27）：AiSuggestionService 新增 4 场景行为锁——
 * demand.dedupe（需求查重路由）/ change.impact-analyze（变更影响面）/
 * handover.checklist-generate（移交清单）/ report.nl-query（NL查报表·导航语义）。
 * 全部走既有 POST /api/v1/ai/suggest 单入口（零新 HTTP 端点），只读拉上下文，
 * AI 输出仅 markdown 不写业务表（继承 R227-C1 红线）。
 */
@Tag("dev")
@DisplayName("AI-P3：场景包 4 新场景 参数矩阵 + 越权 + 上下文渲染 + 审计")
class AiSuggestionP3ScenesTest {

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
    private static final IpdActor PM = new IpdActor(2L, "pm", "MARKET_PM", 100L);

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
            .withClock(Clock.fixed(Instant.parse("2026-09-27T08:00:00Z"), ZoneId.of("UTC")));
    }

    private void enableModel() {
        AiModelConfig config = new AiModelConfig();
        config.setProvider("openai");
        config.setEndpointUrl("http://mock/v1");
        config.setModelName("mock-mini");
        when(modelConfigService.currentEnabled()).thenReturn(config);
        when(modelConfigService.decryptApiKey(config)).thenReturn("sk-x");
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

    private void stubChatOk() {
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok("## 建议", 10, 5, 100L));
    }

    private String capturedPrompt() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(aiGateway).chat(any(AiTestConfig.class), cap.capture(), anyInt(), any());
        return cap.getValue();
    }

    // ---- 白名单 ----

    /**
     * SCENES 20 场景镜像清单（R227 7 + AI-P3 4 + L2 每页 AI 入口补全 10 中的 9，2026-09-28；
     * {@code bonus.fairness-analyze} 已于 2026-10-03 随「奖金池」功能块退役移除）。
     * 与 AiSuggestionService.SCENES 逐字对齐——本清单若与 Service 漂移，
     * sceneWhitelistExact 双向断言必红（禁「现状放行」式假绿）。
     */
    private static final List<String> EXPECTED_SCENES = List.of(
        "workbench.next-step", "workbench.risk-warning",
        "project.summary.refresh",
        "project.create.suggest", "demand.create.from-requirement",
        "gate.precheck-checklist", "gate.conclusion-draft",
        "demand.dedupe", "change.impact-analyze", "handover.checklist-generate",
        "report.nl-query",
        "demand.classify", "demand.priority", "bid.evaluate-proposal",
        "kpi.monthly-summary", "kpi.contributor-summary",
        "timeline.storyline", "report.trend-analyze", "audit.anomaly-detect",
        "product.name-classify");

    @Test
    @DisplayName("SCENES 白名单恰为 R227 7 + AI-P3 4 + L2 9 = 20，双向断言无增删漂移")
    void sceneWhitelistExact() {
        assertEquals(20, EXPECTED_SCENES.size(), "镜像清单自身必须 20");
        assertEquals(20, AiSuggestionService.SCENES.size(),
            "SCENES 实态: " + AiSuggestionService.SCENES);
        assertTrue(AiSuggestionService.SCENES.containsAll(EXPECTED_SCENES),
            "缺场景: " + EXPECTED_SCENES.stream()
                .filter(s -> !AiSuggestionService.SCENES.contains(s)).toList());
        assertTrue(EXPECTED_SCENES.containsAll(AiSuggestionService.SCENES),
            "多余场景: " + AiSuggestionService.SCENES.stream()
                .filter(s -> !EXPECTED_SCENES.contains(s)).toList());
    }

    // ---- demand.dedupe ----

    @Test
    @DisplayName("dedupe：缺 projectId / 缺 userPrompt → PARAM_INVALID")
    void dedupeParamMatrix() {
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("demand.dedupe", null, null, "新需求原文"))).getErrorCode());
        visibleProject(7L);
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("demand.dedupe", 7L, null, "  "))).getErrorCode());
    }

    @Test
    @DisplayName("dedupe 成功：同项目既有需求 top-30 渲染进 prompt + 审计 scene")
    void dedupeRendersExistingRequirements() {
        enableModel();
        visibleProject(7L);
        when(requirementMapper.selectList(any())).thenReturn(List.of(
            Requirement.builder().id(101L).title("门禁人脸批量下发").status("ROUTED").build(),
            Requirement.builder().id(102L).title("批量下发失败重试").status("NEW").build()));
        stubChatOk();

        service.suggest(PM, new AiSuggestReq("demand.dedupe", 7L, null, "希望人脸下发支持失败自动重试"));

        String prompt = capturedPrompt();
        assertTrue(prompt.contains("门禁人脸批量下发"), prompt);
        assertTrue(prompt.contains("失败自动重试"), prompt);
        assertTrue(prompt.contains("路由建议"), prompt);
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertTrue(cap.getValue().getAfterData().contains("\"scene\":\"demand.dedupe\""));
        assertTrue(cap.getValue().getAfterData().contains("\"status\":\"ok\""));
    }

    @Test
    @DisplayName("renderDedupeContext：31 条截断渲染前 30；空清单给基准为空提示")
    void dedupeContextTruncation() {
        List<Requirement> many = java.util.stream.LongStream.rangeClosed(1, 31).boxed()
            .map(i -> Requirement.builder().id(i).title("R" + i).status("NEW").build()).toList();
        String ctx = AiSuggestionService.renderDedupeContext(many);
        assertTrue(ctx.contains("仅渲染前 30 条"), ctx);
        assertTrue(ctx.contains("R30"), ctx);
        assertTrue(!ctx.contains("R31（"), ctx);
        assertTrue(AiSuggestionService.renderDedupeContext(List.of()).contains("暂无既有需求"));
    }

    // ---- change.impact-analyze ----

    private RequirementChange change(long id, long projectId, Long requirementId) {
        return RequirementChange.builder().id(id).projectId(projectId).requirementId(requirementId)
            .changeType("SCOPE").reason("客户新增国别认证要求")
            .beforeSnapshot("{\"items\":1}").afterSnapshot("{\"items\":2}")
            .status("PENDING_SIGN").build();
    }

    @Test
    @DisplayName("impact：entityId 缺失 → PARAM_INVALID；变更单不存在 → NOT_FOUND")
    void impactEntityGuards() {
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("change.impact-analyze", null, null, null))).getErrorCode());
        when(requirementChangeMapper.selectById(9L)).thenReturn(null);
        assertEquals(ApiV1ErrorCode.NOT_FOUND, assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("change.impact-analyze", null, 9L, null))).getErrorCode());
    }

    @Test
    @DisplayName("impact 越权：变更单归属项目非成员 → NOT_FOUND（BR-AI-05 同构）")
    void impactNonMemberBlocked() {
        when(requirementChangeMapper.selectById(9L)).thenReturn(change(9L, 7L, 101L));
        Project p = new Project();
        p.setId(7L);
        when(projectMapper.selectById(7L)).thenReturn(p);
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.suggest(PM, new AiSuggestReq("change.impact-analyze", null, 9L, null)));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("impact 成功：变更类型/原因/关联需求/快照渲染进 prompt，建议性口径")
    void impactRendersChangeContext() {
        enableModel();
        visibleProject(7L);
        when(requirementChangeMapper.selectById(9L)).thenReturn(change(9L, 7L, 101L));
        when(requirementMapper.selectById(101L))
            .thenReturn(Requirement.builder().id(101L).title("人脸识别门禁").status("ROUTED").build());
        stubChatOk();

        service.suggest(PM, new AiSuggestReq("change.impact-analyze", null, 9L, null));

        String prompt = capturedPrompt();
        assertTrue(prompt.contains("SCOPE"), prompt);
        assertTrue(prompt.contains("客户新增国别认证要求"), prompt);
        assertTrue(prompt.contains("人脸识别门禁"), prompt);
        assertTrue(prompt.contains("{\"items\":2}"), prompt);
        assertTrue(prompt.contains("影响面评级"), prompt);
    }

    // ---- handover.checklist-generate ----

    private HandoverRecord handover(long id, String type, Long projectId, long from, long to) {
        return HandoverRecord.builder().id(id).handoverType(type).projectId(projectId)
            .fromPersonId(from).toPersonId(to).status("DRAFT").handoverRole("MARKET_PM")
            .note("项目整体移交").deadlineAt(new Date(1_780_000_000_000L)).build();
    }

    @Test
    @DisplayName("handover：entityId 缺失 → PARAM_INVALID；记录不存在 → NOT_FOUND")
    void handoverEntityGuards() {
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("handover.checklist-generate", null, null, null))).getErrorCode());
        when(handoverMapper.selectById(5L)).thenReturn(null);
        assertEquals(ApiV1ErrorCode.NOT_FOUND, assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("handover.checklist-generate", null, 5L, null))).getErrorCode());
    }

    @Test
    @DisplayName("handover 越权：非项目类移交（projectId=null）仅限移交双方/超管，第三人 NOT_FOUND")
    void handoverThirdPartyBlocked() {
        IpdActor stranger = new IpdActor(99L, "other", "RD_PM", 100L);
        when(handoverMapper.selectById(5L)).thenReturn(handover(5L, "SUPER_ADMIN", null, 2L, 3L));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, assertThrows(IpdBusinessException.class,
            () -> service.suggest(stranger,
                new AiSuggestReq("handover.checklist-generate", null, 5L, null))).getErrorCode());
    }

    @Test
    @DisplayName("handover 成功：承接人视角渲染移交类型/角色/截止时间进 prompt")
    void handoverRendersChecklistContext() {
        enableModel();
        visibleProject(7L);
        when(handoverMapper.selectById(5L)).thenReturn(handover(5L, "PROJECT", 7L, 88L, 2L));
        stubChatOk();

        service.suggest(PM, new AiSuggestReq("handover.checklist-generate", null, 5L, null));

        String prompt = capturedPrompt();
        assertTrue(prompt.contains("PROJECT"), prompt);
        assertTrue(prompt.contains("MARKET_PM"), prompt);
        assertTrue(prompt.contains("项目整体移交"), prompt);
        assertTrue(prompt.contains("移交清单草稿") || prompt.contains("移交管理助手"), prompt);
    }

    // ---- report.nl-query（NL查报表·导航语义，不触 text2sql） ----

    @Test
    @DisplayName("report：缺 userPrompt → PARAM_INVALID")
    void reportRequiresQuestion() {
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, assertThrows(IpdBusinessException.class,
            () -> service.suggest(SA, new AiSuggestReq("report.nl-query", null, null, null))).getErrorCode());
    }

    @Test
    @DisplayName("report 成功：静态目录注入 prompt（三端点；奖金导出端点已随奖金池退役移除），全局维度免 projectId")
    void reportNavigatesWithCatalog() {
        enableModel();
        stubChatOk();

        service.suggest(PM, new AiSuggestReq("report.nl-query", null, null, "上月各项目绩效汇总在哪看"));

        String prompt = capturedPrompt();
        assertTrue(prompt.contains("/api/v1/report/project-summary"), prompt);
        assertTrue(prompt.contains("export/allowance"), prompt);
        assertTrue(prompt.contains("上月各项目绩效汇总在哪看"), prompt);
    }

    // ---- composePrompt 指令齐备 ----

    @ParameterizedTest(name = "新场景 {0} 有专属指令且含 markdown 约束")
    @ValueSource(strings = {"demand.dedupe", "change.impact-analyze",
        "handover.checklist-generate", "report.nl-query"})
    void everyNewSceneHasInstruction(String scene) {
        String prompt = AiSuggestionService.composePrompt(scene, "- 上下文行\n", "素材");
        assertTrue(prompt.contains("【业务上下文】"), scene + ": " + prompt);
        assertTrue(prompt.contains("markdown"), scene + ": " + prompt);
    }

    @Test
    @DisplayName("abbrev：null/空白给占位横线；超长截断加省略号")
    void abbrevGuards() {
        assertEquals("-", AiSuggestionService.abbrev(null, 10));
        assertEquals("-", AiSuggestionService.abbrev("  ", 10));
        String cut = AiSuggestionService.abbrev("字".repeat(20), 10);
        assertTrue(cut.startsWith("字".repeat(10)) && cut.endsWith("…"), cut);
    }
}
