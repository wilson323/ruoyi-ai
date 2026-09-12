package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.mapper.NotificationEventMapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通知 outbox 生产链扫描器单测（2026-09-11 修复轮；@Tag("dev") 必须）。
 *
 * <p>覆盖 4 件事：
 * <ol>
 *   <li>扫描编排：到期行逐条调 dispatchAsync，返回入队成功数（false=聚合节流不计）</li>
 *   <li>健壮性：单行入队异常不阻断后续行</li>
 *   <li>组合轮次：dispatchCycle = 先入队（enqueueDue）后消费（consumeOnce）</li>
 *   <li>边界：无到期行时返回 0 且不抛</li>
 * </ol>
 *
 * <p>纯 mock 用例仅验证编排交互；mock 行组合（PENDING + WEBSOCKET + retryCount=0）
 * 均为 publish 真库写入路径可产生的状态。真库端到端由 16039「造行 → 自动翻 SENT」验证。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class NotificationOutboxScannerTest {

    @Mock
    private NotificationEventMapper mapper;
    @Mock
    private AsyncNotificationDispatcher dispatcher;

    private NotificationOutboxScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new NotificationOutboxScanner(mapper, dispatcher);
    }

    @Test
    @DisplayName("enqueueDue：到期行逐条入队——成功计数、聚合节流(false)不计入")
    void enqueueDue_countsOnlyEnqueued() {
        NotificationEvent first = dueRow(11L);
        NotificationEvent second = dueRow(12L);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        when(dispatcher.dispatchAsync(first)).thenReturn(true);
        when(dispatcher.dispatchAsync(second)).thenReturn(false);

        assertThat(scanner.enqueueDue()).isEqualTo(1);
        verify(dispatcher, times(1)).dispatchAsync(first);
        verify(dispatcher, times(1)).dispatchAsync(second);
    }

    @Test
    @DisplayName("enqueueDue：单行入队异常不阻断后续行（下一轮重扫兜底）")
    void enqueueDue_rowFailure_doesNotBlockRest() {
        NotificationEvent broken = dueRow(21L);
        NotificationEvent healthy = dueRow(22L);
        when(mapper.selectList(any())).thenReturn(List.of(broken, healthy));
        when(dispatcher.dispatchAsync(broken)).thenThrow(new RuntimeException("redis down"));
        when(dispatcher.dispatchAsync(healthy)).thenReturn(true);

        assertThat(scanner.enqueueDue()).isEqualTo(1);
        verify(dispatcher, times(1)).dispatchAsync(healthy);
    }

    @Test
    @DisplayName("dispatchCycle：先扫入队再消费队列（consumeOnce 必被调用）")
    void dispatchCycle_enqueuesThenConsumes() {
        NotificationEvent due = dueRow(31L);
        when(mapper.selectList(any())).thenReturn(List.of(due));
        when(dispatcher.dispatchAsync(due)).thenReturn(true);
        when(dispatcher.consumeOnce(NotificationOutboxScanner.CONSUME_LIMIT))
            .thenReturn(Map.of("sent", 1, "failed", 0, "dead", 0, "skipped", 0, "aggregated", 0));

        scanner.dispatchCycle();

        verify(dispatcher, times(1)).dispatchAsync(due);
        verify(dispatcher, times(1)).consumeOnce(NotificationOutboxScanner.CONSUME_LIMIT);
    }

    @Test
    @DisplayName("enqueueDue：无到期行返回 0，不触达 dispatcher")
    void enqueueDue_emptyDue_returnsZero() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThat(scanner.enqueueDue()).isZero();
        verify(dispatcher, never()).dispatchAsync(any());
        verifyNoInteractions(dispatcher);
    }

    private static NotificationEvent dueRow(long id) {
        NotificationEvent e = NotificationEvent.builder()
            .id(id).receiverId(9L).eventType("GATE_REJECTED").kind("ACTION")
            .sourceType("gate_reviews").sourceId(7L)
            .dedupKey("gate_reviews:GATE_REJECTED:7:9")
            .title("t").content("c").channel("MOCK")
            .targetChannel("WEBSOCKET").locale("zh-CN")
            .deliveryStatus("PENDING").retryCount(0).readFlag("0")
            .build();
        e.setCreateTime(new Date());
        e.setUpdateTime(new Date());
        return e;
    }
}
