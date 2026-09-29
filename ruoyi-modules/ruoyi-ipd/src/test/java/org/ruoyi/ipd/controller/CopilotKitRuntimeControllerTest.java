package org.ruoyi.ipd.controller;

import cn.dev33.satoken.exception.NotLoginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.AiCopilotService;
import org.ruoyi.ipd.service.CopilotRunRegistryService;
import org.ruoyi.ipd.service.AiDocEmbeddingService;
import org.ruoyi.ipd.service.AiExecutionTrigger;
import org.ruoyi.ipd.service.AiModelConfigService;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.service.IpdAuthService;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.ruoyi.ipd.service.WorkbenchService;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CopilotKit AG-UI 桥（2026-09-28）：{@link CopilotKitRuntimeController} HTTP 面测试。
 * 覆盖 /info 形状、未知 agentId 官方 404 形状、run 端点 SSE 线格式冒烟（data: 事件帧）与未认证负向；
 * 真 {@link AiCopilotService} + stub 模型输出（AiGateway doAnswer），鉴权走真 {@link IpdPermission}。
 */
@Tag("dev")
@DisplayName("CopilotKit AG-UI 桥：/copilotkit/info + /agent/:id/run HTTP 面")
class CopilotKitRuntimeControllerTest {

    private AiModelConfigService modelConfigService;
    private WorkbenchService workbenchService;
    private AiGateway aiGateway;
    private IAuditLogService auditLogService;
    private AiDocEmbeddingService docEmbeddingService;
    private AiExecutionTrigger aiExecutionTrigger;
    private IpdAuthSession session;
    private IpdAuthService authService;
    private MockMvc mockMvc;

    private static final Person SA_PERSON = Person.builder()
        .id(1L).name("sa").personType("SUPER_ADMIN").build();

    private static final String RUN_BODY = """
        {"threadId": "t-1", "runId": "r-1",
         "messages": [{"role": "user", "content": "讲个笑话"}]}
        """;

    @BeforeEach
    void setUp() {
        modelConfigService = mock(AiModelConfigService.class);
        workbenchService = mock(WorkbenchService.class);
        aiGateway = mock(AiGateway.class);
        auditLogService = mock(IAuditLogService.class);
        docEmbeddingService = mock(AiDocEmbeddingService.class);
        aiExecutionTrigger = mock(AiExecutionTrigger.class);
        session = mock(IpdAuthSession.class);
        authService = mock(IpdAuthService.class);
        // RetrievalContext.EMPTY 包私有不可达；null = 无命中，服务端 ragContextBlock 同语义降级
        when(docEmbeddingService.retrieveContext(any(), any(), any())).thenReturn(null);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workbenchService.summary(any(), isNull(), eq("tenant-a"))).thenReturn(Map.of(
            "currentAdvance", Map.of(), "tasks", List.of()));
        when(session.currentPerson()).thenReturn(SA_PERSON);
        when(authService.scopeOf(any())).thenReturn(IpdAuthService.Scope.FULL);

        IpdCopilotAccess access = mock(IpdCopilotAccess.class);
        when(access.requireVisible(any(), any())).thenReturn("tenant-a");
        AiCopilotService service = new AiCopilotService(modelConfigService, workbenchService, aiGateway,
            auditLogService, access, docEmbeddingService, aiExecutionTrigger);
        IpdPermission permission = new IpdPermission(session, authService);
        // 直通 executor：同步确定性（run 语义与生产 SSE_EXECUTOR 相同，仅线程模型不同）
        CopilotKitRuntimeController controller =
            new CopilotKitRuntimeController(service, permission,
                new CopilotRunRegistryService(auditLogService), Runnable::run);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void stubEnabledConfig() {
        AiModelConfig cfg = AiModelConfig.builder().id(1L).provider("openai")
            .endpointUrl("https://chat.example.com/v1").apiKeyEncrypted("cipher")
            .modelName("gpt-x").isActive(true).build();
        when(modelConfigService.currentEnabled()).thenReturn(cfg);
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn("sk-test");
    }

    @Test
    @DisplayName("GET /copilotkit/info：agents 元数据 + sse 模式（官方 get-runtime-info 形状）")
    void infoReturnsRuntimeShape() throws Exception {
        mockMvc.perform(get("/copilotkit/info"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.version").isNotEmpty())
            .andExpect(jsonPath("$.mode").value("sse"))
            .andExpect(jsonPath("$.agents.ipd_copilot.name").value("ipd_copilot"))
            .andExpect(jsonPath("$.agents.ipd_copilot.description").isNotEmpty())
            .andExpect(jsonPath("$.agents.ipd_copilot.capabilities").isArray())
            .andExpect(jsonPath("$.threadEndpoints.list").value(false))
            .andExpect(jsonPath("$.telemetryDisabled").value(true));
    }

    @Test
    @DisplayName("POST /copilotkit/agent/other/run：404 官方 error 形状（Agent not found）")
    void unknownAgentReturnsOfficial404() throws Exception {
        mockMvc.perform(post("/copilotkit/agent/other/run")
                .contentType(MediaType.APPLICATION_JSON).content(RUN_BODY))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Agent not found"))
            .andExpect(jsonPath("$.message").value("Agent 'other' does not exist"));
    }

    @Test
    @DisplayName("POST run（CHITCHAT 真流式 stub）：SSE 线格式冒烟——data: 事件帧含 RUN_STARTED…RUN_FINISHED")
    void runEmitsAgUiSseWireFormat() throws Exception {
        stubEnabledConfig();
        doAnswer(inv -> {
            AiGateway.StreamHandler h = inv.getArgument(4);
            h.onDelta("你好");
            h.onDelta("，我是");
            h.onDelta("副驾");
            h.onComplete(10, 6, 120L);
            return null;
        }).when(aiGateway).stream(any(AiTestConfig.class), anyString(), anyInt(), any(BigDecimal.class), any());

        String sse = performRun(RUN_BODY);

        assertTrue(sse.startsWith("data:"), sse);
        assertTrue(sse.contains("\"type\":\"RUN_STARTED\""), sse);
        assertTrue(sse.contains("\"type\":\"TEXT_MESSAGE_START\""), sse);
        assertTrue(sse.contains("\"type\":\"TEXT_MESSAGE_CONTENT\""), sse);
        assertTrue(sse.contains("\"type\":\"TEXT_MESSAGE_END\""), sse);
        assertTrue(sse.contains("\"type\":\"RUN_FINISHED\""), sse);
        assertTrue(sse.contains("\"threadId\":\"t-1\""), sse);
    }

    @Test
    @DisplayName("POST run（done+fillPayload 分支）：RUN_STARTED…TEXT_MESSAGE_*…STATE_DELTA(/fillPayload)…RUN_FINISHED 顺序映射")
    void runFillPayloadBranchEmitsOrderedAgUiSequence() throws Exception {
        stubEnabledConfig();
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any(BigDecimal.class)))
            .thenReturn(AiChatResult.ok("{\"certNo\":\"C-1\"}", 8, 4, 90L));

        String sse = performRun("""
            {"threadId": "t-1", "runId": "r-1",
             "messages": [{"role": "user", "content": "帮我填写认证编号"}],
             "context": [{"description": "pageContext",
                          "value": "{\\"scene\\":\\"stage-action-fields\\"}"}]}
            """);

        int iRunStarted = sse.indexOf("\"type\":\"RUN_STARTED\"");
        int iTextStart = sse.indexOf("\"type\":\"TEXT_MESSAGE_START\"");
        int iContent = sse.indexOf("\"type\":\"TEXT_MESSAGE_CONTENT\"");
        int iTextEnd = sse.indexOf("\"type\":\"TEXT_MESSAGE_END\"");
        int iStateDelta = sse.indexOf("\"type\":\"STATE_DELTA\"");
        int iRunFinished = sse.indexOf("\"type\":\"RUN_FINISHED\"");
        assertTrue(iRunStarted >= 0 && iRunStarted < iTextStart && iTextStart < iContent
            && iContent < iTextEnd && iTextEnd < iStateDelta && iStateDelta < iRunFinished, sse);
        assertTrue(sse.contains("\"path\":\"/fillPayload\""), sse);
    }

    @Test
    @DisplayName("POST run 未认证：SSE in-band RUN_ERROR code=20001（不回 JSON，MIME 安全）")
    void runUnauthenticatedEmitsRunErrorFrame() throws Exception {
        when(session.currentPerson()).thenThrow(new NotLoginException("x", "NOT_LOGIN", "session expired"));

        String sse = performRun(RUN_BODY);

        assertTrue(sse.startsWith("data:"), sse);
        assertTrue(sse.contains("\"type\":\"RUN_STARTED\""), sse);
        assertTrue(sse.contains("\"type\":\"RUN_ERROR\""), sse);
        assertTrue(sse.contains("\"code\":\"20001\""), sse);
    }

    /** 执行 run 端点并取 SSE 响应体（同步执行路径下 async 立即完成；兼容 async 已/未开始两种形态）。 */
    private String performRun(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/copilotkit/agent/ipd_copilot/run")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        return result.getResponse().getContentAsString();
    }
}
