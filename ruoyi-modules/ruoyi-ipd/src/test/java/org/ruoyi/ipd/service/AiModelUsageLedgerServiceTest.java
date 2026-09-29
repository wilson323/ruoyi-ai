package org.ruoyi.ipd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.AiModelUsageLedger;
import org.ruoyi.ipd.mapper.AiModelUsageLedgerMapper;

/**
 * C2 用量账本测试：落账字段语义（失败也落、负值归零、scene/status 兜底）。
 *
 * <p><b>注意</b>：必须 {@code @Tag("dev")}（Surefire groups 过滤，缺 tag 假绿）。
 */
@Tag("dev")
@DisplayName("C2 用量账本：落账语义")
class AiModelUsageLedgerServiceTest {

    @Test
    @DisplayName("落账：字段语义正确（tokens 负值归零、scene/status 兜底）")
    void recordUsageSemantics() {
        AiModelUsageLedgerMapper mapper = mock(AiModelUsageLedgerMapper.class);
        AiModelUsageLedgerService service = new AiModelUsageLedgerService(mapper);

        service.recordUsage(7L, "p-1", null, 120, -5, -100, null, "trace-1");

        ArgumentCaptor<AiModelUsageLedger> captor = ArgumentCaptor.forClass(AiModelUsageLedger.class);
        verify(mapper).insert(captor.capture());
        AiModelUsageLedger row = captor.getValue();
        assertThat(row.getModelConfigId()).isEqualTo(7L);
        assertThat(row.getActorId()).isEqualTo("p-1");
        assertThat(row.getScene()).isEmpty();
        assertThat(row.getPromptTokens()).isEqualTo(120);
        assertThat(row.getCompletionTokens()).isZero();
        assertThat(row.getLatencyMs()).isZero();
        assertThat(row.getStatus()).isEqualTo("ok");
        assertThat(row.getTraceId()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("失败调用也落账：status=错误码，事实不丢")
    void failedCallStillRecorded() {
        AiModelUsageLedgerMapper mapper = mock(AiModelUsageLedgerMapper.class);
        AiModelUsageLedgerService service = new AiModelUsageLedgerService(mapper);

        service.recordUsage(7L, "p-1", "copilot", 0, 0, 3000, "KERNEL_STREAM_ERROR", "trace-2");

        ArgumentCaptor<AiModelUsageLedger> captor = ArgumentCaptor.forClass(AiModelUsageLedger.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("KERNEL_STREAM_ERROR");
    }
}
