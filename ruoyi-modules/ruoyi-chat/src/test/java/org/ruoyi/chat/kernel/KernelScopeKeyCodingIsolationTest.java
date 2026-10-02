package org.ruoyi.chat.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class KernelScopeKeyCodingIsolationTest {
    @Test
    void codingTenantUserAndRunEachSeparateNativeStateSlot() {
        var first = KernelScopeKey.of("tenant-a", "11", "durable-coding", "run-1");
        assertThat(first.slotId()).isNotEqualTo(KernelScopeKey.of("tenant-b", "11", "durable-coding", "run-1").slotId());
        assertThat(first.slotId()).isNotEqualTo(KernelScopeKey.of("tenant-a", "12", "durable-coding", "run-1").slotId());
        assertThat(first.slotId()).isNotEqualTo(KernelScopeKey.of("tenant-a", "11", "durable-coding", "run-2").slotId());
        assertThat(first.toRuntimeContext().getUserId()).isEqualTo(first.userId());
        assertThat(first.toRuntimeContext().getSessionId()).isEqualTo(first.sessionId());
    }

    @Test
    void injectedCodingRunCannotAddressAnotherStateSlot() {
        assertThatThrownBy(() -> KernelScopeKey.of("tenant-a", "11", "durable-coding", "run-1:run-2"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
