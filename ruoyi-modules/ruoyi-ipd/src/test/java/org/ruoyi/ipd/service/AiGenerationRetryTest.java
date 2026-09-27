package org.ruoyi.ipd.service;

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
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI-P1-1 失败重试切片（2026-09-27）：生成链对瞬时类模型调用失败同请求内补一次重试。
 * <ul>
 *   <li>重试白名单 = TIMEOUT / UNREACHABLE / HTTP_5xx（AiGateway.mapFailure 同源错误码）；</li>
 *   <li>确定性失败（AUTH_FAILED / HTTP_429 / HTTP_4xx / EMPTY_RESPONSE / UNSUPPORTED_PROTOCOL）
 *       零重试——既有 P422 failureReleasesGatePermit（HTTP_429×3 逐次消费 stub）语义不受扰；</li>
 *   <li>审计只在最终失败落一条 AI_GENERATE_FAILED，retried=true 标记发生过重试。</li>
 * </ul>
 */
@Tag("dev")
@DisplayName("AI-P1-1：生成链失败重试 1 次（仅瞬时错误）+ 重试审计标记")
class AiGenerationRetryTest {

    private static final IpdActor ACTOR = new IpdActor(9L, "pm-甲", "MARKET_PM", 900001L);

    private AiDocumentMapper documentMapper;
    private AiDocumentService documentService;
    private AiModelConfigService modelConfigService;
    private IAuditLogService auditLogService;
    private AiGateway aiGateway;
    private AiDocEmbeddingService docEmbeddingService;
    private AiGenerationService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(AiDocumentMapper.class);
        documentService = mock(AiDocumentService.class);
        modelConfigService = mock(AiModelConfigService.class);
        auditLogService = mock(IAuditLogService.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        aiGateway = mock(AiGateway.class);
        docEmbeddingService = mock(AiDocEmbeddingService.class);
        when(docEmbeddingService.retrieveContext(any(), any(), any()))
            .thenReturn(AiDocEmbeddingService.RetrievalContext.EMPTY);
        service = new AiGenerationService(documentMapper, documentService,
            modelConfigService, auditLogService, aiGateway, docEmbeddingService);
        AiModelConfig config = AiModelConfig.builder().id(1L).provider("openai")
            .endpointUrl("https://api.openai.com/v1").apiKeyEncrypted("ct")
            .modelName("gpt-4o-mini").configJson("{}").isActive(true).build();
        when(modelConfigService.currentEnabled()).thenReturn(config);
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn("sk-x");
    }

    private static AiGenerateReq req() {
        return new AiGenerateReq(77L, "PRD", "需求文档", "原始资料");
    }

    private AiDocument doc() {
        return AiDocument.builder().id(555L).projectId(77L).docType("PRD").title("需求文档")
            .content("正文").model("gpt-4o-mini").status(AiDocumentService.STATUS_GENERATED)
            .versionNo(1).build();
    }

    // ---- 表驱动：错误码 → 是否重试 ----

    @ParameterizedTest(name = "{0} → 重试={1}")
    @CsvSource({
        "TIMEOUT,true", "UNREACHABLE,true", "HTTP_500,true", "HTTP_502,true", "HTTP_503,true",
        "AUTH_FAILED,false", "HTTP_429,false", "HTTP_400,false", "EMPTY_RESPONSE,false",
        "UNSUPPORTED_PROTOCOL,false"
    })
    void transientFailureMatrix(String code, boolean retried) {
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.fail(code, "x", 10));
        assertThrows(IpdBusinessException.class, () -> service.generate(ACTOR, req()));
        verify(aiGateway, times(retried ? 2 : 1))
            .chat(any(AiTestConfig.class), anyString(), any(), any());
    }

    @Test
    @DisplayName("瞬时失败一次 + 重试成功：正常落库登记 v1，且无失败审计")
    void retryThenSuccess() {
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.fail("TIMEOUT", "slow", 10))
            .thenReturn(AiChatResult.ok("正文", 10, 20, 40));
        when(documentService.createGenerated(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(doc());

        assertDoesNotThrow(() -> service.generate(ACTOR, req()));

        verify(aiGateway, times(2)).chat(any(AiTestConfig.class), anyString(), any(), any());
        verify(documentService).createGenerated(any(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertEquals("AI_GENERATE", cap.getValue().getAction(), "重试成功只落成功审计");
    }

    @Test
    @DisplayName("重试后仍失败：单条 AI_GENERATE_FAILED 审计且 retried=true")
    void retryThenFailAuditsRetriedFlag() {
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.fail("HTTP_503", "upstream", 10));

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.generate(ACTOR, req()));
        assertEquals(ApiV1ErrorCode.INTERNAL_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("HTTP_503"));

        verify(aiGateway, times(2)).chat(any(AiTestConfig.class), anyString(), any(), any());
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).append(cap.capture());
        AuditLog log = cap.getValue();
        assertEquals("AI_GENERATE_FAILED", log.getAction());
        assertTrue(log.getAfterData().contains("\"errorCode\":\"HTTP_503\""), log.getAfterData());
        assertTrue(log.getAfterData().contains("\"retried\":true"), log.getAfterData());
        verify(documentService, never()).createGenerated(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("确定性失败不重试：失败审计 retried=false（HTTP_429 护栏，护 P422 既有语义）")
    void deterministicFailureNoRetryAuditsUnretried() {
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.fail("HTTP_429", "rate", 10));

        assertThrows(IpdBusinessException.class, () -> service.generate(ACTOR, req()));

        verify(aiGateway, times(1)).chat(any(AiTestConfig.class), anyString(), any(), any());
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertTrue(cap.getValue().getAfterData().contains("\"retried\":false"),
            cap.getValue().getAfterData());
    }

    @ParameterizedTest(name = "isTransientFailure(\"{0}\")=false")
    @ValueSource(strings = {"AUTH_FAILED", "HTTP_429", "HTTP_404", "EMPTY_RESPONSE", "", "BOGUS"})
    void nonTransientCodesNotRetryable(String code) {
        assertEquals(!code.isEmpty() && (code.equals("TIMEOUT") || code.equals("UNREACHABLE")
            || code.startsWith("HTTP_5")), AiGenerationService.isTransientFailure(code));
    }

    @Test
    @DisplayName("isTransientFailure：null 安全 + 5xx 命中")
    void transientPredicate() {
        assertTrue(AiGenerationService.isTransientFailure("HTTP_500"));
        assertTrue(AiGenerationService.isTransientFailure("TIMEOUT"));
        assertTrue(AiGenerationService.isTransientFailure("UNREACHABLE"));
        assertEquals(false, AiGenerationService.isTransientFailure(null));
    }
}
