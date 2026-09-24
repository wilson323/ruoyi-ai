package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R212 第⑧条（看板卡 dbe1b6a7 · C 组）：AI 生成主链的 {@code ai.allowed-hosts} SSRF 豁免门禁测试。
 *
 * <p>被测实现在 R213 已随 {@code d9a1ffc8} 合入（{@link AiGenerationService} 的
 * {@code allowListed(host)} 分支）——本卡<b>只补测试、不动实现</b>。它此前零覆盖：
 * 全测试树 grep {@code allowed-hosts|allowListed|allowedHosts} 结果为 0，
 * {@code P422AcceptanceTest#ssrfBlocked} 只钉住了「字段未注入（null）时 loopback 被拒」这一半。
 *
 * <p>钉死的契约（双向）：
 * <ul>
 *   <li>命中 allowlist ⇒ 本机 mock 验收窗口（R184-A）放行到网关，不再无条件 400；</li>
 *   <li>生产默认空串 ⇒ loopback 仍被拒（行为不变，防止豁免逻辑变成默认 SSRF 开口）；</li>
 *   <li>allowlist 字面等值匹配 ⇒ 配了别的 host 不得顺手放过 127.0.0.1（无泛匹配/前缀匹配）。</li>
 * </ul>
 *
 * <p>私有 {@code @Value} 字段用反射写入（不为此开生产 setter，最小侵入）；mock 组合合法：
 * endpoint 指向 loopback 是本机 ollama mock 的真实部署形态，非真库不可能状态。
 */
@Tag("dev")
@DisplayName("R212-⑧ AI 生成：ai.allowed-hosts 豁免 SSRF 黑名单的门禁双向测试")
class R212AiAllowedHostsGateTest {

    private static final IpdActor ACTOR = new IpdActor(9L, "pm-甲", "MARKET_PM", 900001L);
    private static final String LOOPBACK_ENDPOINT = "http://127.0.0.1:11434/v1";

    private AiGateway aiGateway;
    private AiDocumentService documentService;

    private static AiGenerateReq req() {
        return new AiGenerateReq(77L, "PRD", "需求文档", "原始资料：用户反馈整理与竞品速览");
    }

    /** 装配被测服务，并把 {@code ai.allowed-hosts} 写进私有 @Value 字段（等价 Spring 装配效果）。 */
    private AiGenerationService serviceWithAllowedHosts(String allowedHosts) throws Exception {
        aiGateway = mock(AiGateway.class);
        documentService = mock(AiDocumentService.class);
        AiModelConfigService modelConfigService = mock(AiModelConfigService.class);
        when(modelConfigService.currentEnabled()).thenReturn(AiModelConfig.builder()
            .id(1L).provider("ollama").endpointUrl(LOOPBACK_ENDPOINT)
            .apiKeyEncrypted("ciphertext-not-plain").modelName("qwen2.5:7b")
            .configJson("{}").isActive(true).build());
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn("sk-plain-test-1234567890");
        when(aiGateway.chat(any(), anyString(), any(), any()))
            .thenReturn(AiChatResult.ok("生成的 PRD 正文", 120, 480, 1500));
        when(documentService.createGenerated(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(AiDocument.builder().id(555L).projectId(77L).docType("PRD").title("需求文档")
                .content("生成的 PRD 正文").model("qwen2.5:7b").status("GENERATED").versionNo(1).build());
        AiGenerationService service = new AiGenerationService(mock(AiDocumentMapper.class), documentService,
            modelConfigService, mock(IAuditLogService.class), aiGateway, mock(AiDocEmbeddingService.class));
        Field field = AiGenerationService.class.getDeclaredField("allowedHosts");
        field.setAccessible(true);
        field.set(service, allowedHosts);
        return service;
    }

    @Test
    @DisplayName("正例：ai.allowed-hosts 命中 127.0.0.1 → loopback 端点豁免黑名单，照常出站生成")
    void allowListedHost_exemptsLoopbackEndpoint() throws Exception {
        AiGenerationService service = serviceWithAllowedHosts("127.0.0.1, localhost");

        AiDocument doc = service.generate(ACTOR, req());

        assertEquals(555L, doc.getId(), "豁免窗口内应走完生成主链");
        verify(aiGateway).chat(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("反例：生产默认（空串）→ loopback 仍 10001/HTTP 400 拒绝，零出站零落库（豁免逻辑没把门拆了）")
    void productionDefault_emptyString_stillRejectsLoopback() throws Exception {
        AiGenerationService service = serviceWithAllowedHosts("");

        IpdBusinessException ex = assertThrows(IpdBusinessException.class, () -> service.generate(ACTOR, req()));

        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertEquals(400, ex.getErrorCode().getHttpStatus());
        assertEquals("模型端点不可用: loopback", ex.getMessage());
        verify(aiGateway, never()).chat(any(), anyString(), any(), any());
        verify(documentService, never()).createGenerated(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("反例：allowlist 只配了别的 host → 不得泛匹配放过 127.0.0.1（字面等值口径）")
    void allowListedOtherHost_doesNotFuzzyMatchLoopback() throws Exception {
        AiGenerationService service = serviceWithAllowedHosts("api.openai.com,10.0.0.5");

        assertThrows(IpdBusinessException.class, () -> service.generate(ACTOR, req()));

        verify(aiGateway, never()).chat(any(), anyString(), any(), any());
    }
}
