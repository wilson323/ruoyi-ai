package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L0-4 SSE 真流式（AI-STRAT-3，2026-09-23）：{@link AiCopilotService#chatStream} 编排 wiring 单测。
 *
 * <p><b>与 {@link org.ruoyi.ipd.service.ai.AiGatewayStreamingTest} 的分工（mock 合法性）</b>：
 * 本类 mock 的是<b>项目自有的 {@link AiGateway}</b>（不是 Langchain4j ChatModel），用 doAnswer 同步驱动
 * {@link AiGateway.StreamHandler} 回调，验证<b>服务编排契约</b>——意图分类、meta/delta/done/error 帧序列、
 * token 聚合、aiRole=streaming 单行审计（totalChunks/status/token）。「真逐段 token 到达」由
 * AiGatewayStreamingTest 走真 HTTP 打 mock server 独立证明（真活）；两层各司其职，不重复、不假造 AI 响应。
 *
 * <p>doAnswer 同步回调是服务单测的确定性手段（真实 gateway 异步推送）：此处只验「若 gateway 推 delta，
 * service 是否正确接线到 sink + 审计」，不涉真实模型行为，故不违反 mock 合法性三规约。
 */
@Tag("dev")
@DisplayName("L0-4 AiCopilotService.chatStream：编排 wiring（帧序列 + 审计 streaming）")
class AiCopilotServiceStreamTest {

    private AiModelConfigService modelConfigService;
    private WorkbenchService workbenchService;
    private AiGateway aiGateway;
    private IAuditLogService auditLogService;
    private ProjectMapper projectMapper;
    private ProjectMemberMapper projectMemberMapper;
    private AiDocEmbeddingService docEmbeddingService;
    private AiExecutionTrigger aiExecutionTrigger;
    private AiCopilotService service;

    private static final IpdActor SA = new IpdActor(1L, "sa", "SUPER_ADMIN", null);

    /** 记录型 sink：把语义事件按到达顺序落 {@link #events}，便于断言 SSE 帧序列。 */
    private static final class RecordingSink implements AiCopilotService.CopilotStreamSink {
        final List<String> events = new ArrayList<>();
        final List<String> deltas = new ArrayList<>();
        AiCopilotResp meta;
        AiCopilotResp done;
        String errCode;
        String errMsg;

        @Override
        public void meta(AiCopilotResp resp) {
            this.meta = resp;
            events.add("meta");
        }

        @Override
        public void delta(String token) {
            deltas.add(token);
            events.add("delta");
        }

        @Override
        public void done(AiCopilotResp resp) {
            this.done = resp;
            events.add("done");
        }

        @Override
        public void error(String code, String message) {
            this.errCode = code;
            this.errMsg = message;
            events.add("error");
        }
    }

    @BeforeEach
    void setUp() {
        modelConfigService = mock(AiModelConfigService.class);
        workbenchService = mock(WorkbenchService.class);
        aiGateway = mock(AiGateway.class);
        auditLogService = mock(IAuditLogService.class);
        projectMapper = mock(ProjectMapper.class);
        projectMemberMapper = mock(ProjectMemberMapper.class);
        docEmbeddingService = mock(AiDocEmbeddingService.class);
        aiExecutionTrigger = mock(AiExecutionTrigger.class);
        when(docEmbeddingService.retrieveContext(any(), any(), any()))
            .thenReturn(AiDocEmbeddingService.RetrievalContext.EMPTY);
        service = new AiCopilotService(modelConfigService, workbenchService, aiGateway,
            auditLogService, projectMapper, projectMemberMapper, docEmbeddingService, aiExecutionTrigger);
        service.withClock(Clock.fixed(Instant.parse("2026-09-23T19:00:00Z"), ZoneId.of("UTC")));
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        // 默认（projectId=null）空上下文；各测试可覆盖
        when(workbenchService.summary(any(), isNull())).thenReturn(Map.of(
            "currentAdvance", Map.of(), "tasks", List.of()));
    }

    private void stubEnabledConfig() {
        AiModelConfig cfg = AiModelConfig.builder().id(1L).provider("openai")
            .endpointUrl("https://chat.example.com/v1").apiKeyEncrypted("cipher")
            .modelName("gpt-x").isActive(true).build();
        when(modelConfigService.currentEnabled()).thenReturn(cfg);
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn("sk-test");
    }

    @Test
    @DisplayName("CHITCHAT 真流式：onDelta×3 + onComplete → 帧 meta/delta×3/done + 审计 streaming(totalChunks=3,ok)")
    void chatStreamChitchatStreamsDeltas() {
        stubEnabledConfig();
        // 驱动 StreamHandler：模拟 gateway 异步推 3 段 delta 后 onComplete（token 10/6，latency 120）
        doAnswer(inv -> {
            AiGateway.StreamHandler h = inv.getArgument(4);
            h.onDelta("你好");
            h.onDelta("，我是");
            h.onDelta("副驾");
            h.onComplete(10, 6, 120L);
            return null;
        }).when(aiGateway).stream(any(AiTestConfig.class), anyString(), anyInt(), any(BigDecimal.class), any());

        RecordingSink sink = new RecordingSink();
        service.chatStream(SA, new AiCopilotReq(null, "讲个笑话", List.of()), sink);

        // 帧序列：meta 首帧 + 3 段 delta + done 末帧（顺序即前端 SSE 消费顺序）
        assertEquals(List.of("meta", "delta", "delta", "delta", "done"), sink.events);
        assertEquals(List.of("你好", "，我是", "副驾"), sink.deltas);
        // meta 首帧：intent 已到、answer 空、token 0（真流式：结构化先渲染，answer 随后 delta 填）
        assertEquals("CHITCHAT", sink.meta.intent());
        assertEquals("", sink.meta.answer());
        assertEquals(0, sink.meta.tokenPrompt());
        // done 末帧：聚合 tokenUsage + latency
        assertEquals(10, sink.done.tokenPrompt());
        assertEquals(6, sink.done.tokenCompletion());
        assertEquals(120L, sink.done.latencyMs());

        // 审计：AI_COPILOT_STREAM 单行 + aiRole=streaming + totalChunks=3 + status=ok
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        AuditLog log = cap.getValue();
        assertEquals("AI_COPILOT_STREAM", log.getAction());
        assertEquals("AI_COPILOT", log.getEntityType());
        String after = log.getAfterData();
        assertTrue(after.contains("\"aiAssisted\":true"), after);
        assertTrue(after.contains("\"aiModel\":\"gpt-x\""), after);
        assertTrue(after.contains("\"aiRole\":\"streaming\""), after);
        assertTrue(after.contains("\"scene\":\"ai_copilot\""), after);
        assertTrue(after.contains("\"totalChunks\":3"), after);
        assertTrue(after.contains("\"status\":\"ok\""), after);
        assertTrue(after.contains("\"tokenPrompt\":10"), after);
        assertTrue(after.contains("\"tokenCompletion\":6"), after);
        // BR-AI-04：prompt 原文绝不入审计
        assertFalse(after.contains("讲个笑话"), after);
    }

    @Test
    @DisplayName("意图兜底(TASKS)：不调 AI 流式 → 帧 meta/delta/done(整段一次)，verify stream never")
    void chatStreamIntentFallbackNoAi() {
        when(workbenchService.summary(any(), isNull())).thenReturn(Map.of(
            "tasks", List.of(Map.of("taskType", "stage_sign", "title", "阶段签署", "hint", "待签 TR2", "url", "/w/s/1")),
            "currentAdvance", Map.of()));

        RecordingSink sink = new RecordingSink();
        service.chatStream(SA, new AiCopilotReq(null, "我该干啥", List.of()), sink);

        assertEquals(List.of("meta", "delta", "done"), sink.events);
        assertEquals("TASKS", sink.done.intent());
        assertEquals(1, sink.deltas.size(), "整段 answer 一次性 delta（无真流式）");
        // 意图兜底不真调 AI 流式网关
        verify(aiGateway, never()).stream(any(), anyString(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("流式失败：onError → 帧 meta/error(errCode 透传) + 审计 status=FAIL:UNREACHABLE")
    void chatStreamErrorPath() {
        stubEnabledConfig();
        doAnswer(inv -> {
            AiGateway.StreamHandler h = inv.getArgument(4);
            h.onError(AiChatResult.fail("UNREACHABLE", "连不上模型服务", 500L));
            return null;
        }).when(aiGateway).stream(any(AiTestConfig.class), anyString(), anyInt(), any(BigDecimal.class), any());

        RecordingSink sink = new RecordingSink();
        service.chatStream(SA, new AiCopilotReq(null, "闲聊", List.of()), sink);

        // meta 首帧已在 kick off stream 前送出，随后 error（无 delta/done）
        assertEquals(List.of("meta", "error"), sink.events);
        assertEquals("UNREACHABLE", sink.errCode);

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        String after = cap.getValue().getAfterData();
        assertTrue(after.contains("\"aiRole\":\"streaming\""), after);
        assertTrue(after.contains("\"status\":\"FAIL:UNREACHABLE\""), after);
        assertTrue(after.contains("\"totalChunks\":0"), after);
    }
}
