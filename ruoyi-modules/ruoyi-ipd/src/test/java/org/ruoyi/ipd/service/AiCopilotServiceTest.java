package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.ipd.dto.AiCopilotResp;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AI-P2-3（2026-09-11）：副驾问答三路径 + 越权 + 审计三件套 + composePrompt 钳制单测。
 * <p>五条红线对齐：
 * <ul>
 *   <li>BR-AI-04：审计 afterData 三件套必带（aiAssisted/aiModel/aiRole=copilot_answer）；</li>
 *   <li>BR-AI-05：越权拦截（SA 全见；其余角色按 project_members 命中）；</li>
 *   <li>P1-3 门禁：aiRole 白名单必须包含 copilot_answer；</li>
 *   <li>intent_match 路径诚实标记（aiModel=intent_match）避免 AI 假标；</li>
 *   <li>composePrompt 超 MAX 钳制 + 项目上下文取 currentAdvance 真实键。</li>
 * </ul>
 */
@Tag("dev")
@DisplayName("AI-P2-3：AI 副驾三路径 + 越权 + 审计")
class AiCopilotServiceTest {

    private AiModelConfigService modelConfigService;
    private WorkbenchService workbenchService;
    private AiGateway aiGateway;
    private AuditLogService auditLogService;
    private ProjectMapper projectMapper;
    private ProjectMemberMapper projectMemberMapper;
    private AiCopilotService service;

    private static final IpdActor SA = new IpdActor(1L, "sa", "SUPER_ADMIN", null);
    private static final IpdActor RD_PM = new IpdActor(2L, "rd", "RD_PM", 100L);

    @BeforeEach
    void setUp() {
        modelConfigService = mock(AiModelConfigService.class);
        workbenchService = mock(WorkbenchService.class);
        aiGateway = mock(AiGateway.class);
        auditLogService = mock(AuditLogService.class);
        projectMapper = mock(ProjectMapper.class);
        projectMemberMapper = mock(ProjectMemberMapper.class);
        service = new AiCopilotService(modelConfigService, workbenchService, aiGateway,
            auditLogService, projectMapper, projectMemberMapper);
        // 固定时钟便于断言 latencyMs
        service.withClock(Clock.fixed(Instant.parse("2026-09-10T19:00:00Z"), ZoneId.of("UTC")));
        // auditLogService.append 透传捕获
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ============ 意图分类（关键字命中；静态方法） ============

    @Test
    @DisplayName("classifyIntent：TASKS 关键字命中（待办/todo/该干什么/next）")
    void classifyIntentTasks() {
        assertEquals("TASKS", AiCopilotService.classifyIntent("我该干啥"));
        assertEquals("TASKS", AiCopilotService.classifyIntent("还有哪些待办"));
        assertEquals("TASKS", AiCopilotService.classifyIntent("todo list"));
        assertEquals("TASKS", AiCopilotService.classifyIntent("next steps"));
    }

    @Test
    @DisplayName("classifyIntent：ADVANCE 关键字命中（进度/阶段/推进/advance）")
    void classifyIntentAdvance() {
        assertEquals("ADVANCE", AiCopilotService.classifyIntent("项目当前进度"));
        assertEquals("ADVANCE", AiCopilotService.classifyIntent("推进到下一阶段"));
        assertEquals("ADVANCE", AiCopilotService.classifyIntent("卡在哪"));
        assertEquals("ADVANCE", AiCopilotService.classifyIntent("当前阶段是否进入 TR3"));
        // 注意：英文 next/todo 在关键字顺序上被 TASKS 抢命中（"next"/"todo" 是 TASKS 关键字），
        // 测试改用纯中文 ADVANCE 短语；中文场景无此冲突
    }

    @Test
    @DisplayName("classifyIntent：未命中关键字 → CHITCHAT；null/空白容错")
    void classifyIntentChitchat() {
        assertEquals("CHITCHAT", AiCopilotService.classifyIntent("你好"));
        assertEquals("CHITCHAT", AiCopilotService.classifyIntent("讲个笑话"));
        assertEquals("CHITCHAT", AiCopilotService.classifyIntent(null));
        assertEquals("CHITCHAT", AiCopilotService.classifyIntent(""));
    }

    // ============ 越权拦截（不依赖反射；走 project_members 命中） ============

    @Test
    @DisplayName("越权：projectId 命中且 SA 角色 → 放行；service.chat 不抛")
    void visibilitySuperAdminPasses() {
        AiCopilotReq req = new AiCopilotReq(10L, "项目当前进度", List.of());
        when(projectMapper.selectById(10L)).thenReturn(project(10L));
        when(workbenchService.summary(any(), eq(10L))).thenReturn(Map.of(
            "currentAdvance", Map.of("projectName", "P1", "currentStage", "TR2", "actionName", "立项评审"),
            "tasks", List.of()
        ));
        AiCopilotResp resp = service.chat(SA, req);
        assertEquals("ADVANCE", resp.intent());
        // SA 不查 projectMemberMapper
        verify(projectMemberMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("越权：非 SA 角色，project_members 未命中 → IpdBusinessException NOT_FOUND")
    void visibilityRejectWhenNotMember() {
        AiCopilotReq req = new AiCopilotReq(10L, "项目当前进度", List.of());
        when(projectMapper.selectById(10L)).thenReturn(project(10L));
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.chat(RD_PM, req));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("越权：非 SA 角色，project_members 命中 → 放行")
    void visibilityPassWhenMember() {
        AiCopilotReq req = new AiCopilotReq(10L, "项目当前进度", List.of());
        when(projectMapper.selectById(10L)).thenReturn(project(10L));
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        when(workbenchService.summary(any(), eq(10L))).thenReturn(Map.of(
            "currentAdvance", Map.of("projectName", "P1", "currentStage", "TR2"),
            "tasks", List.of()
        ));
        assertDoesNotThrow(() -> service.chat(RD_PM, req));
    }

    @Test
    @DisplayName("越权：projectId 不存在 → NOT_FOUND")
    void visibilityRejectWhenProjectMissing() {
        AiCopilotReq req = new AiCopilotReq(999L, "查询", List.of());
        when(projectMapper.selectById(999L)).thenReturn(null);
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.chat(SA, req));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ============ TASKS 路径：命中即返回结构化数据 + intent_match 审计 ============

    @Test
    @DisplayName("TASKS 路径：返回 workbench.tasks 结构化；审计 aiModel=intent_match + token=0")
    void tasksPathReturnsStructured() {
        AiCopilotReq req = new AiCopilotReq(null, "我该干啥", List.of());
        when(workbenchService.summary(any(), isNull())).thenReturn(Map.of(
            "tasks", List.of(
                Map.of("taskType", "stage_sign", "title", "阶段签署", "hint", "待签 TR2", "url", "/w/stage/1"),
                Map.of("taskType", "deletion_review", "title", "归档复核", "hint", "限 24h", "url", "/w/del/2")
            ),
            "currentAdvance", Map.of()
        ));
        AiCopilotResp resp = service.chat(SA, req);
        assertEquals("TASKS", resp.intent());
        assertEquals(2, resp.data().size(), "返回 workbench.tasks 全量");
        assertEquals("stage_sign", resp.data().get(0).type());
        assertEquals("阶段签署", resp.data().get(0).title());
        assertTrue(resp.answer().contains("2 项"));
        // 审计三件套：intent_match 路径诚实标记未真调 AI
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        AuditLog log = cap.getValue();
        assertEquals("AI_COPILOT_CHAT", log.getAction());
        assertEquals("AI_COPILOT", log.getEntityType());
        String after = log.getAfterData();
        assertTrue(after.contains("\"aiAssisted\":true"));
        assertTrue(after.contains("\"aiModel\":\"intent_match\""));
        assertTrue(after.contains("\"aiRole\":\"copilot_answer\""));
        assertTrue(after.contains("\"intent\":\"TASKS\""));
        assertTrue(after.contains("\"tokenPrompt\":0"));
        assertTrue(after.contains("\"tokenCompletion\":0"));
    }

    @Test
    @DisplayName("TASKS 路径：tasks 为空 → 返回「当前没有待你处理的待办」")
    void tasksPathEmpty() {
        AiCopilotReq req = new AiCopilotReq(null, "我该干啥", List.of());
        when(workbenchService.summary(any(), isNull())).thenReturn(Map.of(
            "tasks", List.of(), "currentAdvance", Map.of()
        ));
        AiCopilotResp resp = service.chat(SA, req);
        assertEquals(0, resp.data().size());
        assertTrue(resp.answer().contains("没有"));
    }

    // ============ ADVANCE 路径：取 currentAdvance（真键），不是 advance ============

    @Test
    @DisplayName("ADVANCE 路径：取 WorkbenchService 真键 currentAdvance；旧键 advance 兜底")
    void advancePathReadsCurrentAdvance() {
        AiCopilotReq req = new AiCopilotReq(10L, "项目当前进度", List.of());
        when(projectMapper.selectById(10L)).thenReturn(project(10L));
        when(workbenchService.summary(any(), eq(10L))).thenReturn(Map.of(
            // 只放 currentAdvance 键（与 WorkbenchService.summary 一致）
            "currentAdvance", Map.of("projectName", "P1", "currentStage", "TR2", "actionName", "立项评审"),
            "tasks", List.of()
        ));
        AiCopilotResp resp = service.chat(SA, req);
        assertEquals("ADVANCE", resp.intent());
        assertEquals(1, resp.data().size(), "currentAdvance 命中（非空 advance）");
        assertEquals("ADVANCE", resp.data().get(0).type());
        assertTrue(resp.data().get(0).title().contains("P1"));
        assertTrue(resp.data().get(0).title().contains("TR2"));
    }

    @Test
    @DisplayName("ADVANCE 路径：currentAdvance 为空 → 友好提示（不调 AI）")
    void advancePathEmpty() {
        AiCopilotReq req = new AiCopilotReq(10L, "项目当前进度", List.of());
        when(projectMapper.selectById(10L)).thenReturn(project(10L));
        when(workbenchService.summary(any(), eq(10L))).thenReturn(Map.of(
            "currentAdvance", Map.of(), "tasks", List.of()
        ));
        AiCopilotResp resp = service.chat(SA, req);
        assertEquals(0, resp.data().size());
        verify(aiGateway, never()).chat(any(), anyString(), any(), any());
    }

    // ============ CHITCHAT 路径：调 AI + 审计真三件套 ============

    @Test
    @DisplayName("CHITCHAT 路径：成功 → answer 透传 + 审计 aiModel=真模型名 + token 双记")
    void chitchatPathSuccess() {
        stubEnabledConfig();
        AiCopilotReq req = new AiCopilotReq(10L, "讲个笑话", List.of());
        when(projectMapper.selectById(10L)).thenReturn(project(10L));
        when(workbenchService.summary(any(), eq(10L))).thenReturn(Map.of(
            "currentAdvance", Map.of("projectName", "P1", "currentStage", "TR2", "actionName", "评审"),
            "tasks", List.of(
                Map.of("taskType", "stage_sign", "title", "阶段签署", "hint", "TR2 待签")
            )
        ));
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any(BigDecimal.class)))
            .thenReturn(AiChatResult.ok("好笑的回答", 123, 45, 900L));

        AiCopilotResp resp = service.chat(SA, req);
        assertEquals("CHITCHAT", resp.intent());
        assertEquals("好笑的回答", resp.answer());
        assertEquals(123, resp.tokenPrompt());
        assertEquals(45, resp.tokenCompletion());
        assertTrue(resp.sources().contains("project.advance"));
        assertTrue(resp.sources().contains("workbench.tasks"));

        // 审计三件套 + aiModel=真模型名
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        String after = cap.getValue().getAfterData();
        assertTrue(after.contains("\"aiModel\":\"gpt-x\""));
        assertTrue(after.contains("\"aiRole\":\"copilot_answer\""));
        assertTrue(after.contains("\"intent\":\"CHITCHAT\""));
        assertTrue(after.contains("\"tokenPrompt\":123"));
        assertTrue(after.contains("\"tokenCompletion\":45"));
        assertTrue(after.contains("\"status\":\"ok\""));
        // prompt 原文必不入审计（BR-AI-04）
        assertFalse(after.contains("讲个笑话"));
    }

    @Test
    @DisplayName("CHITCHAT 路径：AI 失败 → IpdBusinessException + 审计 status=FAIL:xxx")
    void chitchatPathFail() {
        stubEnabledConfig();
        AiCopilotReq req = new AiCopilotReq(null, "闲聊", List.of());
        when(workbenchService.summary(any(), isNull())).thenReturn(Map.of(
            "currentAdvance", Map.of(), "tasks", List.of()
        ));
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any(BigDecimal.class)))
            .thenReturn(AiChatResult.fail("TIMEOUT", "30s 超时", 30000L));

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.chat(SA, req));
        assertEquals(ApiV1ErrorCode.INTERNAL_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("TIMEOUT"));

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        String after = cap.getValue().getAfterData();
        assertTrue(after.contains("\"status\":\"FAIL:TIMEOUT\""));
        assertTrue(after.contains("\"aiModel\":\"gpt-x\""), "失败也记 aiModel（白名单可通过）");
    }

    @Test
    @DisplayName("CHITCHAT 路径：未启用 AI 模型（currentEnabled STATE_CONFLICT）→ 友好兜底，不抛 50002")
    void chitchatPathNoEnabledConfig() {
        AiCopilotReq req = new AiCopilotReq(null, "闲聊一下", List.of());
        when(workbenchService.summary(any(), isNull())).thenReturn(Map.of(
            "currentAdvance", Map.of(), "tasks", List.of()
        ));
        when(modelConfigService.currentEnabled())
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "当前未启用任何 AI 模型"));

        AiCopilotResp resp = service.chat(SA, req);
        assertEquals("CHITCHAT", resp.intent());
        assertTrue(resp.answer().contains("AI 副驾暂未启用"), "友好提示替代抛错");
        assertTrue(resp.sources().contains("config.disabled"));
        assertEquals(0, resp.tokenPrompt());
        verify(aiGateway, never()).chat(any(), anyString(), any(), any());

        // 审计：status=FAIL:STATE_CONFLICT + aiModel=intent_match（未真调）
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        String after = cap.getValue().getAfterData();
        assertTrue(after.contains("\"status\":\"FAIL:STATE_CONFLICT\""));
        assertTrue(after.contains("\"aiModel\":\"intent_match\""));
    }

    // ============ composePrompt 钳制 ============

    @Test
    @DisplayName("composePrompt：项目+个人上下文在前、需求在后；超 COPILOT_PROMPT_MAX 钳到上限")
    void composePromptClamps() {
        AiCopilotReq req = new AiCopilotReq(null, "Q".repeat(2_000), List.of(
            new AiCopilotReq.CopilotTurn("user", "前文1"),
            new AiCopilotReq.CopilotTurn("assistant", "前答1"),
            new AiCopilotReq.CopilotTurn("user", "前文2")
        ));
        String projectCtx = "项目：P1\n当前阶段：TR2\n下一步动作：评审";
        String personalCtx = "1. [stage_sign] 阶段签署 — TR2 待签\n2. [deletion_review] 归档复核 — 限 24h";

        String prompt = AiCopilotService.composePrompt(req, projectCtx, personalCtx);
        assertTrue(prompt.startsWith("你是 IPD"), "system 指令在前");
        assertTrue(prompt.contains("【项目上下文】"));
        assertTrue(prompt.contains("【个人上下文"));
        assertTrue(prompt.contains("【历史对话】"));
        assertTrue(prompt.contains("【本次问题】"));
        assertTrue(prompt.endsWith("Q".repeat(2_000)), "需求原文完整保留（按 MAX 钳制）");

        // 超 MAX 钳制：构造极大 projectCtx 看是否能钳到 COPILOT_PROMPT_MAX
        String hugeProjectCtx = "P".repeat(20_000);
        String prompt2 = AiCopilotService.composePrompt(req, hugeProjectCtx, null);
        assertEquals(AiCopilotService.COPILOT_PROMPT_MAX, prompt2.length(), "总长必钳到 COPILOT_PROMPT_MAX");
    }

    @Test
    @DisplayName("composePrompt：contexts 空白 → 跳过对应块；history 为空 → 仅 system + 需求")
    void composePromptWithEmptyContexts() {
        AiCopilotReq req = new AiCopilotReq(null, "问题", null);
        String prompt = AiCopilotService.composePrompt(req, "", "");
        assertFalse(prompt.contains("【项目上下文】"));
        assertFalse(prompt.contains("【个人上下文"));
        assertTrue(prompt.contains("【本次问题】"));
        assertTrue(prompt.endsWith("问题"));
    }

    @Test
    @DisplayName("composePrompt：history 超 MAX_HISTRY=8 → 仅取末尾 8 轮")
    void composePromptHistoryCap() {
        List<AiCopilotReq.CopilotTurn> hist = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            hist.add(new AiCopilotReq.CopilotTurn("user", "round-" + i));
        }
        AiCopilotReq req = new AiCopilotReq(null, "今问", hist);
        String prompt = AiCopilotService.composePrompt(req, null, null);
        // 末尾 8 轮 = round-12..round-19
        assertTrue(prompt.contains("round-19"));
        assertTrue(prompt.contains("round-12"));
        assertFalse(prompt.contains("round-11"), "round-11 必被截掉");
    }

    // ============ 入口校验 ============

    @Test
    @DisplayName("入口：message 必填（null/空白 → PARAM_INVALID）")
    void entryGuardMessageRequired() {
        AiCopilotReq req = new AiCopilotReq(null, "  ", List.of());
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.chat(SA, req));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("入口：req=null → PARAM_INVALID")
    void entryGuardReqNull() {
        assertThrows(IpdBusinessException.class, () -> service.chat(SA, null));
    }

    // ============ helper ============

    private static Project project(Long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("P-" + id);
        return p;
    }

    private void stubEnabledConfig() {
        AiModelConfig cfg = AiModelConfig.builder().id(1L).provider("openai")
            .endpointUrl("https://chat.example.com/v1").apiKeyEncrypted("cipher")
            .modelName("gpt-x").isActive(true).build();
        when(modelConfigService.currentEnabled()).thenReturn(cfg);
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn("sk-test");
    }
}
