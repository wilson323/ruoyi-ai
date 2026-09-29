package org.ruoyi.ipd.copilotkit;

import cn.dev33.satoken.exception.NotLoginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.AiCopilotService;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CopilotKit AG-UI 桥（2026-09-28）：{@link AgUiCopilotRun} 编排测试。
 *
 * <p>mock 合法性：只 mock 模型输出（{@link AiGateway} doAnswer 同步驱动 StreamHandler / chat 返回
 * 合法 stub JSON）与项目访问边界（IpdCopilotAccess）；
 * 真 {@link AiCopilotService}（存储/审计路径走真代码，mock 只挡外部模型面）。
 */
@Tag("dev")
@DisplayName("CopilotKit AG-UI 桥：RunAgentInput → ai-copilot 流式链 → AG-UI 事件")
class AgUiCopilotRunTest {

    /** 直通 executor：同步确定性（生产由控制器注入 SSE_EXECUTOR）。 */
    private static final Executor DIRECT = Runnable::run;

    private AiModelConfigService modelConfigService;
    private WorkbenchService workbenchService;
    private AiGateway aiGateway;
    private IAuditLogService auditLogService;
    private IpdCopilotAccess access;
    private AiDocEmbeddingService docEmbeddingService;
    private AiExecutionTrigger aiExecutionTrigger;
    private IpdAuthSession session;
    private IpdAuthService authService;
    private AiCopilotService service;
    private IpdPermission permission;

    private static final Person SA_PERSON = Person.builder()
        .id(1L).name("sa").personType("SUPER_ADMIN").build();
    private static final Person RD_PERSON = Person.builder()
        .id(2L).name("rd").personType("RD_PM").groupId(9L).build();

    /** 记录型 AG-UI 下发口。 */
    private static final class RecordingSink implements AgUiCopilotRun.AgUiSseSink {
        final List<Map<String, Object>> events = new ArrayList<>();
        boolean completed;

        @Override
        public void send(List<Map<String, Object>> events) {
            this.events.addAll(events);
        }

        @Override
        public void complete() {
            this.completed = true;
        }

        List<String> types() {
            List<String> t = new ArrayList<>();
            for (Map<String, Object> e : events) {
                t.add(String.valueOf(e.get("type")));
            }
            return t;
        }
    }

    @BeforeEach
    void setUp() {
        modelConfigService = mock(AiModelConfigService.class);
        workbenchService = mock(WorkbenchService.class);
        aiGateway = mock(AiGateway.class);
        auditLogService = mock(IAuditLogService.class);
        access = mock(IpdCopilotAccess.class);
        when(access.requireVisible(any(), any())).thenReturn("tenant-a");
        docEmbeddingService = mock(AiDocEmbeddingService.class);
        aiExecutionTrigger = mock(AiExecutionTrigger.class);
        session = mock(IpdAuthSession.class);
        authService = mock(IpdAuthService.class);
        // RetrievalContext.EMPTY 包私有不可达；null = 无命中，服务端 ragContextBlock 同语义降级
        when(docEmbeddingService.retrieveContext(any(), any(), any())).thenReturn(null);
        service = new AiCopilotService(modelConfigService, workbenchService, aiGateway,
            auditLogService, access, docEmbeddingService, aiExecutionTrigger);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workbenchService.summary(any(), isNull(), eq("tenant-a"))).thenReturn(Map.of(
            "currentAdvance", Map.of(), "tasks", List.of()));
        when(session.currentPerson()).thenReturn(SA_PERSON);
        when(authService.scopeOf(any())).thenReturn(IpdAuthService.Scope.FULL);
        permission = new IpdPermission(session, authService);
    }

    private void stubEnabledConfig() {
        AiModelConfig cfg = AiModelConfig.builder().id(1L).provider("openai")
            .endpointUrl("https://chat.example.com/v1").apiKeyEncrypted("cipher")
            .modelName("gpt-x").isActive(true).build();
        when(modelConfigService.currentEnabled()).thenReturn(cfg);
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn("sk-test");
    }

    private static RunAgentInput input(String message) {
        return new RunAgentInput("thread-1", "run-1", null, Map.of(),
            List.of(new RunAgentInput.Message("m1", "user", null, message)),
            List.of(), List.of(), Map.of(), null);
    }

    @Test
    @DisplayName("CHITCHAT 真流式：meta/delta×3/done → AG-UI 七事件序列 + RUN 边界闭合 + complete")
    void happyPathStreamsAgUiEvents() {
        stubEnabledConfig();
        doAnswer(inv -> {
            AiGateway.StreamHandler h = inv.getArgument(4);
            h.onDelta("你好");
            h.onDelta("，我是");
            h.onDelta("副驾");
            h.onComplete(10, 6, 120L);
            return null;
        }).when(aiGateway).stream(any(AiTestConfig.class), anyString(), anyInt(), any(BigDecimal.class), any());

        RecordingSink out = new RecordingSink();
        new AgUiCopilotRun(service, permission, DIRECT).execute(input("讲个笑话"), out);

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
            "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED"), out.types());
        assertEquals("thread-1", out.events.get(0).get("threadId"));
        assertEquals("run-1", out.events.get(0).get("runId"));
        assertTrue(out.completed);
    }

    @Test
    @DisplayName("FILL_PAGE+fillPayload（R221）：done → STATE_DELTA(/fillPayload) 后接 RUN_FINISHED")
    void fillPayloadPathEmitsStateDelta() {
        stubEnabledConfig();
        doAnswer(inv -> AiChatResult.ok("{\"certNo\":\"C-1\",\"hacker\":\"x\"}", 8, 4, 90L))
            .when(aiGateway).chat(any(AiTestConfig.class), anyString(), any(), any(BigDecimal.class));

        RecordingSink out = new RecordingSink();
        RunAgentInput in = new RunAgentInput("thread-1", "run-1", null, Map.of(),
            List.of(new RunAgentInput.Message("m1", "user", null, "帮我填写认证编号")),
            List.of(), List.of(new RunAgentInput.Context("pageContext",
                "{\"scene\":\"stage-action-fields\"}")), Map.of(), null);
        new AgUiCopilotRun(service, permission, DIRECT).execute(in, out);

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
            "TEXT_MESSAGE_END", "STATE_DELTA", "RUN_FINISHED"), out.types());
        List<?> patch = (List<?>) out.events.get(4).get("delta");
        Map<?, ?> op = (Map<?, ?>) patch.get(0);
        assertEquals("/fillPayload", op.get("path"));
        Map<?, ?> value = (Map<?, ?>) op.get("value");
        assertEquals("stage-action-fields", value.get("scene"));
        assertEquals("suggest", value.get("mode"));
        assertTrue(out.completed);
    }

    @Test
    @DisplayName("projectId 越权（非成员）：in-band RUN_ERROR code=50001「项目不可见」（既有语义）")
    void projectEscalationEmitsRunError50001() {
        when(session.currentPerson()).thenReturn(RD_PERSON);
        when(authService.scopeOf(any())).thenReturn(IpdAuthService.Scope.FULL);
        doThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"))
            .when(access).requireVisible(any(), eq(7L));

        RecordingSink out = new RecordingSink();
        new AgUiCopilotRun(service, permission, DIRECT)
            .execute(new RunAgentInput("t", "r", null, Map.of(),
                List.of(new RunAgentInput.Message("m1", "user", null, "讲个笑话")),
                List.of(),
                List.of(new RunAgentInput.Context("projectId", "7")), Map.of(), null), out);

        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), out.types());
        assertEquals("50001", out.events.get(1).get("code"));
        assertEquals("项目不可见", out.events.get(1).get("message"));
        assertTrue(out.completed);
    }

    @Test
    @DisplayName("未认证（NotLoginException）：in-band RUN_ERROR code=20001（对齐既有鉴权语义）")
    void unauthenticatedEmitsRunError20001() {
        when(session.currentPerson()).thenThrow(new NotLoginException("x", "NOT_LOGIN", "session expired"));

        RecordingSink out = new RecordingSink();
        new AgUiCopilotRun(service, permission, DIRECT).execute(input("讲个笑话"), out);

        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), out.types());
        assertEquals("20001", out.events.get(1).get("code"));
        assertTrue(out.completed);
    }

    @Test
    @DisplayName("RunAgentInput → AiCopilotReq：末条 user= message，其余 user/assistant=history，context 优先 forwardedProps")
    void translatesRunAgentInputToCopilotReq() {
        RunAgentInput in = new RunAgentInput("t", "r", null, Map.of(),
            List.of(
                new RunAgentInput.Message("m1", "system", null, "sys"),
                new RunAgentInput.Message("m2", "user", null, "第一问"),
                new RunAgentInput.Message("m3", "assistant", null, "第一答"),
                new RunAgentInput.Message("m4", "user", null, "第二问")),
            List.of(),
            List.of(new RunAgentInput.Context("projectId", "42")),
            Map.of("pageContext", "ctx-json"), null);
        AiCopilotReq req = AgUiCopilotRun.toCopilotReq(in);
        assertEquals("第二问", req.message());
        assertEquals(Long.valueOf(42L), req.projectId());
        assertEquals("ctx-json", req.pageContext());
        // history 含先前 user/assistant 轮，不含 system、不含当前 message
        assertEquals(List.of(
            new AiCopilotReq.CopilotTurn("user", "第一问"),
            new AiCopilotReq.CopilotTurn("assistant", "第一答")), req.history());

        // forwardedProps fallback（context 无 projectId 项时）
        RunAgentInput in2 = new RunAgentInput("t", "r", null, Map.of(),
            List.of(new RunAgentInput.Message("m1", "user", null, "hi")),
            List.of(), List.of(), Map.of("projectId", 7), null);
        assertEquals(Long.valueOf(7L), AgUiCopilotRun.toCopilotReq(in2).projectId());

        // context 优先 forwardedProps
        RunAgentInput in3 = new RunAgentInput("t", "r", null, Map.of(),
            List.of(new RunAgentInput.Message("m1", "user", null, "hi")),
            List.of(), List.of(new RunAgentInput.Context("projectId", "1")),
            Map.of("projectId", 2), null);
        assertEquals(Long.valueOf(1L), AgUiCopilotRun.toCopilotReq(in3).projectId());
    }

    @Test
    @DisplayName("参数违规：缺 user 文本 / message 超长 / projectId 非法 → IllegalArgumentException（→10001 帧）")
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> AgUiCopilotRun.toCopilotReq(
            new RunAgentInput("t", "r", null, Map.of(),
                List.of(new RunAgentInput.Message("m1", "assistant", null, "only assistant")),
                List.of(), List.of(), Map.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> AgUiCopilotRun.toCopilotReq(
            new RunAgentInput("t", "r", null, Map.of(),
                List.of(new RunAgentInput.Message("m1", "user", null, "x".repeat(2001))),
                List.of(), List.of(), Map.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> AgUiCopilotRun.toCopilotReq(
            new RunAgentInput("t", "r", null, Map.of(),
                List.of(new RunAgentInput.Message("m1", "user", null, "hi")),
                List.of(), List.of(new RunAgentInput.Context("projectId", "abc")), Map.of(), null)));
    }

    @Test
    @DisplayName("消息参数违规 → in-band RUN_ERROR code=10001（参数校验失败，既有语义）")
    void invalidInputEmitsRunError10001() {
        RecordingSink out = new RecordingSink();
        new AgUiCopilotRun(service, permission, DIRECT)
            .execute(new RunAgentInput("t", "r", null, Map.of(),
                List.of(new RunAgentInput.Message("m1", "user", null, "x".repeat(2001))),
                List.of(), List.of(), Map.of(), null), out);
        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), out.types());
        assertEquals("10001", out.events.get(1).get("code"));
        assertTrue(out.completed);
    }
}
