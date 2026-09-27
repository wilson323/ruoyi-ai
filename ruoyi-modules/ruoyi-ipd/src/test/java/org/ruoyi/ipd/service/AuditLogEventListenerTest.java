package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.AiDocumentMapper;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PLAN-KB-AUTO 组件①单测：关键动作→收录（幂等锚 seq）／非关键→零副作用／
 * 重复事件→不重复落文档不重复向量化。纯 Mockito，不连库、不起 Spring 上下文
 * （对齐 AiDocEmbeddingServiceTest 同款风格）。
 */
@Tag("dev")
@DisplayName("PLAN-KB-AUTO：AuditLogEventListener 关键事件收录")
class AuditLogEventListenerTest {

    private AiDocumentMapper documentMapper;
    private AiDocEmbeddingService embeddingService;
    private AuditLogEventListener listener;

    @BeforeEach
    void setUp() {
        documentMapper = mock(AiDocumentMapper.class);
        embeddingService = mock(AiDocEmbeddingService.class);
        listener = new AuditLogEventListener(documentMapper, embeddingService);
    }

    private static AuditLog event(String action) {
        return AuditLog.builder()
            .id(1L).seq(88L).action(action)
            .entityType("GATE").entityId(7L)
            .operatorId(9L).operatorName("张三").operatorRole("PDT_LD")
            .reason("评审通过").createTime(new Date(1_750_000_000_123L))
            .build();
    }

    @Test
    @DisplayName("① GATE_ 关键动作→落 KEY_EVENT 文档并触发向量化（带幂等锚 seq）")
    void gateActionArchivedAndEmbedded() {
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(documentMapper.insert(any(AiDocument.class))).thenReturn(1);

        AuditLogEventListener.Outcome outcome = listener.archiveIfKey(event("GATE_APPROVE"));

        assertEquals(AuditLogEventListener.Outcome.ARCHIVED, outcome);
        ArgumentCaptor<AiDocument> cap = ArgumentCaptor.forClass(AiDocument.class);
        verify(documentMapper).insert(cap.capture());
        AiDocument doc = cap.getValue();
        assertEquals("KEY_EVENT", doc.getDocType());
        assertTrue(doc.getContent().contains("seq=88"), "摘要必须携带审计 seq（幂等锚）");
        assertNotNull(doc.getContentSha256());
        assertEquals(AiDocumentService.sha256Hex(doc.getContent()), doc.getContentSha256(),
            "sha256 必须由摘要正文推出（重放稳定）");
        verify(embeddingService, times(1)).embedAsync(doc);
    }

    @Test
    @DisplayName("② 非关键动作（UPDATE/TRANSIT/PROJECT_STAGE_）→ 不收录（负向）")
    void nonKeyActionSkipped() {
        assertEquals(AuditLogEventListener.Outcome.NOT_KEY_EVENT, listener.archiveIfKey(event("UPDATE")));
        assertEquals(AuditLogEventListener.Outcome.NOT_KEY_EVENT, listener.archiveIfKey(event("TRANSIT")));
        assertEquals(AuditLogEventListener.Outcome.NOT_KEY_EVENT,
            listener.archiveIfKey(event("PROJECT_STAGE_GATE_PASS")));
        verifyNoInteractions(embeddingService);
        verify(documentMapper, never()).insert(any(AiDocument.class));
        verify(documentMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("③ 同一事件重复投递→第二次 DUPLICATE，不重复落文档/向量化（幂等）")
    void duplicateEventNotReEmbedded() {
        when(documentMapper.selectCount(any())).thenReturn(0L, 1L);
        when(documentMapper.insert(any(AiDocument.class))).thenReturn(1);
        AuditLog gate = event("GATE_APPROVE");

        assertEquals(AuditLogEventListener.Outcome.ARCHIVED, listener.archiveIfKey(gate));
        AuditLog replay = event("GATE_APPROVE");
        replay.setCreateTime(new Date(replay.getCreateTime().getTime() + 7_777_000L));
        assertEquals(AuditLogEventListener.Outcome.DUPLICATE, listener.archiveIfKey(replay));

        verify(documentMapper, times(1)).insert(any(AiDocument.class));
        verify(embeddingService, times(1)).embedAsync(any(AiDocument.class));
    }

    @Test
    @DisplayName("KPI_ 动作（主代码真实存在 KPI_SHARED_CONFIRM）→ 收录")
    void kpiActionArchived() {
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(documentMapper.insert(any(AiDocument.class))).thenReturn(1);
        assertEquals(AuditLogEventListener.Outcome.ARCHIVED,
            listener.archiveIfKey(event("KPI_SHARED_CONFIRM")));
        verify(embeddingService, times(1)).embedAsync(any(AiDocument.class));
    }

    @Test
    @DisplayName("INCENTIVE_ 前缀占位（主代码现查 0 动作，一旦出现即收录）")
    void incentivePrefixReserved() {
        when(documentMapper.selectCount(any())).thenReturn(0L);
        when(documentMapper.insert(any(AiDocument.class))).thenReturn(1);
        assertEquals(AuditLogEventListener.Outcome.ARCHIVED,
            listener.archiveIfKey(event("INCENTIVE_GRANTED")));
        assertTrue(AuditLogEventListener.KEY_ACTION_PREFIXES.contains("INCENTIVE_"));
    }

    @Test
    @DisplayName("残缺事件（null/缺 seq/缺 action）→ INVALID_EVENT 零 DB 副作用")
    void invalidEventRejected() {
        assertEquals(AuditLogEventListener.Outcome.INVALID_EVENT, listener.archiveIfKey(null));
        AuditLog noSeq = event("GATE_APPROVE");
        noSeq.setSeq(null);
        assertEquals(AuditLogEventListener.Outcome.INVALID_EVENT, listener.archiveIfKey(noSeq));
        AuditLog noAction = event("GATE_APPROVE");
        noAction.setAction(" ");
        assertEquals(AuditLogEventListener.Outcome.INVALID_EVENT, listener.archiveIfKey(noAction));
        verifyNoInteractions(embeddingService);
        verify(documentMapper, never()).insert(any(AiDocument.class));
    }

    @Test
    @DisplayName("摘要纯函数：同事件重放逐字节一致；毫秒差被截秒吸收；异事件必不同")
    void summaryStableAcrossReplay() {
        AuditLog a = event("GATE_APPROVE");
        AuditLog b = event("GATE_APPROVE");
        b.setCreateTime(new Date(a.getCreateTime().getTime() + 456L));
        assertEquals(AuditLogEventListener.buildSummary(a), AuditLogEventListener.buildSummary(b),
            "毫秒差不得改变摘要（幂等根基）");
        assertNotEquals(AuditLogEventListener.buildSummary(a),
            AuditLogEventListener.buildSummary(event("KPI_SHARED_COLLECT")));
    }
}
