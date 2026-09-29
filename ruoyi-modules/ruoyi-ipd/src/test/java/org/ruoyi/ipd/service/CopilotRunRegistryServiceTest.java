package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.dto.AiCopilotResp;
import org.ruoyi.ipd.security.IpdActor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P3 取消切片（B↔C 合同 G1，2026-09-29）：{@link CopilotRunRegistryService} 注册/归属/取消/守卫语义。
 * {@code @Tag("dev")} 必须，否则 Surefire 静默跳过（假绿陷阱）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>register：runId 空→服务端生成；他人占用同 runId→30001；同 owner 重复注册=替换；</li>
 *   <li>cancel：未知/非 owner→50001（不泄露存在性）；首次取消落审计 AI_COPILOT_RUN_CANCEL；重复取消幂等不重复落；</li>
 *   <li>guard：取消先赢→晚到 meta/delta/done/error 全吞（无成功帧，ADR-0075）；未取消→逐帧直通；</li>
 *   <li>注销：done/error 终帧后句柄从注册表移除，再 cancel→50001。</li>
 * </ol>
 */
@Tag("dev")
@DisplayName("P3 取消切片：run 注册表 + guard 取消先赢语义")
class CopilotRunRegistryServiceTest {

    private static final IpdActor OWNER = new IpdActor(9001L, "alice", "RD_PM", 1L);
    private static final IpdActor OTHER = new IpdActor(9002L, "bob", "MARKET_PM", 1L);

    private IAuditLogService audit;
    private CopilotRunRegistryService registry;

    @BeforeEach
    void setUp() {
        audit = mock(IAuditLogService.class);
        registry = new CopilotRunRegistryService(audit);
    }

    /** 记录型 sink 桩。 */
    private static final class Recording implements AiCopilotService.CopilotStreamSink {
        final List<String> calls = new ArrayList<>();

        @Override
        public void meta(AiCopilotResp resp) {
            calls.add("meta");
        }

        @Override
        public void delta(String token) {
            calls.add("delta");
        }

        @Override
        public void done(AiCopilotResp resp) {
            calls.add("done");
        }

        @Override
        public void error(String code, String message) {
            calls.add("error:" + code);
        }
    }

    private static AiCopilotResp resp() {
        return new AiCopilotResp("CHITCHAT", "", List.of(), List.of(), 1, 1, 1L);
    }

    @Test
    @DisplayName("register：runId 空→服务端生成非空 id")
    void register_generatesRunIdWhenBlank() {
        CopilotRunRegistryService.RunHandle h = registry.register(OWNER, "  ");
        assertTrue(h.runId() != null && !h.runId().isBlank());
        assertFalse(h.isCancelled());
    }

    @Test
    @DisplayName("register：他人已占用同 runId→30001（防劫持取消面）")
    void register_crossOwnerRejected() {
        registry.register(OWNER, "run-x");
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> registry.register(OTHER, "run-x"));
        assertEquals(ApiV1ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("register：同 owner 重复注册=替换新句柄")
    void register_sameOwnerReplaces() {
        CopilotRunRegistryService.RunHandle first = registry.register(OWNER, "run-r");
        registry.cancel(OWNER, "run-r");
        CopilotRunRegistryService.RunHandle second = registry.register(OWNER, "run-r");
        assertNotEquals(first, second);
        assertFalse(second.isCancelled(), "替换后的新句柄不受旧取消位污染");
    }

    @Test
    @DisplayName("cancel：未知 runId→50001；非 owner→50001（不泄露存在性）")
    void cancel_unknownOrForeign_notFound() {
        registry.register(OWNER, "run-a");
        IpdBusinessException unknown = assertThrows(IpdBusinessException.class,
            () -> registry.cancel(OWNER, "no-such-run"));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, unknown.getErrorCode());
        assertThrows(IpdBusinessException.class, () -> registry.cancel(OTHER, "run-a"));
        verify(audit, never()).append(any(IpdActor.class), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("cancel：首次置位+落审计；重复取消幂等（取消后句柄已注销→50001）")
    void cancel_firstAuditsThenIdempotent() {
        CopilotRunRegistryService.RunHandle h = registry.register(OWNER, "run-b");
        Map<String, Object> r1 = registry.cancel(OWNER, "run-b");
        assertEquals(true, r1.get("cancelled"));
        assertEquals(true, r1.get("firstTime"));
        assertTrue(h.isCancelled());
        verify(audit, times(1)).append(eq(OWNER), eq("AI_COPILOT_RUN_CANCEL"), eq("AI_COPILOT"), any(), eq("runId=run-b"));
        // 首次取消已注销：第二次走 NOT_FOUND（句柄不复活，取消事实仍由首次审计背书）
        assertEquals(ApiV1ErrorCode.NOT_FOUND, assertThrows(IpdBusinessException.class,
            () -> registry.cancel(OWNER, "run-b")).getErrorCode());
        verify(audit, times(1)).append(any(IpdActor.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("guard：未取消逐帧直通；done 注销句柄")
    void guard_passesThroughAndUnregisters() {
        CopilotRunRegistryService.RunHandle h = registry.register(OWNER, "run-c");
        Recording inner = new Recording();
        AiCopilotService.CopilotStreamSink g = registry.guard(h, inner);
        g.meta(resp());
        g.delta("x");
        g.done(resp());
        assertEquals(List.of("meta", "delta", "done"), inner.calls);
        assertEquals(ApiV1ErrorCode.NOT_FOUND, assertThrows(IpdBusinessException.class,
            () -> registry.cancel(OWNER, "run-c")).getErrorCode(), "done 后句柄已注销");
    }

    @Test
    @DisplayName("guard：取消先赢→晚到 meta/delta/done/error 全吞（无成功帧，ADR-0075）")
    void guard_cancelWinsSuppressesLateFrames() {
        CopilotRunRegistryService.RunHandle h = registry.register(OWNER, "run-d");
        Recording inner = new Recording();
        AiCopilotService.CopilotStreamSink g = registry.guard(h, inner);
        g.delta("partial");
        registry.cancel(OWNER, "run-d");
        g.delta("late");
        g.meta(resp());
        g.done(resp());
        g.error("X", "late error");
        assertEquals(List.of("delta"), inner.calls, "取消后无任何晚到帧穿透");
    }

    @Test
    @DisplayName("guard：handle=null 直通 inner（registry 未启用路径零行为变化）")
    void guard_nullHandlePassthrough() {
        Recording inner = new Recording();
        AiCopilotService.CopilotStreamSink g = registry.guard(null, inner);
        g.delta("t");
        assertEquals(List.of("delta"), inner.calls);
    }
}
