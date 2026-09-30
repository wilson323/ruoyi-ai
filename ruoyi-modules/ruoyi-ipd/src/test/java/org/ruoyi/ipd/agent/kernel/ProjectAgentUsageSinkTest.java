package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 模型结束事件的用量只在有 token 时写入既有账本。
 */
@Tag("dev")
class ProjectAgentUsageSinkTest {

    @Test
    @DisplayName("无 usage 时明细为空，不编造 token")
    void emptyUsage() {
        assertThat(AgentScopeProjectAgentKernel.modelCallDetail(null)).isEmpty();
    }

    @Test
    @DisplayName("结束事件只带输入和输出 token，不猜测耗时单位")
    void copiesTokenCounts() {
        Map<String, Object> detail = AgentScopeProjectAgentKernel.modelCallDetail(new ChatUsage(11, 7, 1.5d));
        assertThat(detail).containsEntry("inputTokens", 11).containsEntry("outputTokens", 7);
        assertThat(detail).doesNotContainKey("latencyMs");
    }

    @Test
    @DisplayName("只有带 inputTokens 的 MODEL_CALL 落账")
    void recordsOnlyTokenStep() {
        AtomicInteger steps = new AtomicInteger();
        ProjectAgentEventSink delegate = new ProjectAgentEventSink() {
            @Override
            public void onStep(String kind, Map<String, Object> detail) {
                steps.incrementAndGet();
            }

            @Override
            public void onToolCall(String toolCallId, String toolName) {
            }

            @Override
            public void onToolResult(String toolCallId, String toolName, String state) {
            }

            @Override
            public void onSource(Map<String, Object> source) {
            }

            @Override
            public void onText(String delta) {
            }

            @Override
            public void onArtifact(String artifactId, String title, String contentHash, int version) {
            }

            @Override
            public void onError(String errorCode) {
            }

            @Override
            public void onComplete() {
            }
        };
        AiModelUsageLedgerService ledger = mock(AiModelUsageLedgerService.class);
        ProjectAgentUsageSink sink = new ProjectAgentUsageSink(delegate, ledger, 9L, "900101", "42");
        sink.onStep("MODEL_CALL", Map.of());
        verifyNoInteractions(ledger);
        sink.onStep("MODEL_CALL", Map.of("inputTokens", 11, "outputTokens", 7));
        verify(ledger).recordUsage(9L, "900101", ProjectAgentUsageSink.SCENE, 11, 7, 0L, "ok", "42");
        assertThat(steps.get()).isEqualTo(2);
    }
}
