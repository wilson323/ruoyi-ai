package org.ruoyi.chat.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redisson 版沙箱执行互斥测试（2026-10-03 聊天内核跨副本互斥补齐）。
 *
 * <p>契约来源：官方 SandboxExecutionGuard javadoc 的 Redis 租约语义（SET NX PX + token 释放 +
 * 中断即抛）。纯锁语义 mock（RBucket 返回值受控），不构造真库数据组合。
 */
@Tag("dev")
class AgentScopeRedisSandboxGuardTest {

    private static final String PREFIX = "ruoyi:agentscope:chat:guard:";

    @SuppressWarnings("unchecked")
    private RBucket<String> bucketOf(RedissonClient redisson) {
        RBucket<String> bucket = mock(RBucket.class);
        // getBucket 是泛型方法，thenReturn 会推断成 RBucket<Object>；doReturn 绕过编译期类型检查。
        org.mockito.Mockito.doReturn(bucket).when(redisson).getBucket(anyString(), any());
        return bucket;
    }

    private static SandboxIsolationKey sessionKey(String sessionId) {
        return SandboxIsolationKey.resolve(IsolationScope.SESSION,
                RuntimeContext.builder().sessionId(sessionId).build(), "chat-agent").orElseThrow();
    }

    @Test
    @DisplayName("获得执行权走 SET NX 租约，Redis 键为 prefix+scope+复合 sessionId")
    void acquiresLeaseWithExpectedRedisKey() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucketOf(redisson);
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);

        var guard = new AgentScopeRedisSandboxGuard(redisson, PREFIX);
        try (SandboxLease lease = guard.tryEnter(sessionKey("a1:s2"))) {
            assertThat(lease).isNotNull();
        }

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        // acquire 与 release 各取一次 bucket，两次键名都必须是完整隔离键。
        verify(redisson, org.mockito.Mockito.times(2)).getBucket(key.capture(), eq(StringCodec.INSTANCE));
        assertThat(key.getAllValues()).allSatisfy(k -> assertThat(k).isEqualTo(PREFIX + "session:a1:s2"));
    }

    @Test
    @DisplayName("释放走 token CAS 删除，之后同键可再次获得")
    void releaseAllowsReacquire() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucketOf(redisson);
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);

        var guard = new AgentScopeRedisSandboxGuard(redisson, PREFIX);
        try (SandboxLease first = guard.tryEnter(sessionKey("a1:s2"))) {
            assertThat(first).isNotNull();
        }
        try (SandboxLease second = guard.tryEnter(sessionKey("a1:s2"))) {
            assertThat(second).isNotNull();
        }
        // 两轮 acquire/close 各释放一次，token CAS 删除共两次。
        verify(bucket, org.mockito.Mockito.times(2)).compareAndSet(anyString(), isNull());
    }

    @Test
    @DisplayName("槽位被占且等待线程被中断时抛 InterruptedException（对齐官方自旋语义）")
    void interruptedWaitThrows() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucketOf(redisson);
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);

        var guard = new AgentScopeRedisSandboxGuard(redisson, PREFIX);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> guard.tryEnter(sessionKey("a1:s2")))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            // 清除中断标记，避免污染同线程后续用例。
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("释放失败只告警不抛且幂等（lease close 语义：必须可多次安全调用）")
    void releaseFailureIsQuietAndIdempotent() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<String> bucket = bucketOf(redisson);
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        when(bucket.compareAndSet(anyString(), isNull()))
                .thenThrow(new IllegalStateException("redis down"));

        var guard = new AgentScopeRedisSandboxGuard(redisson, PREFIX);
        SandboxLease lease = guard.tryEnter(sessionKey("a1:s2"));
        lease.close();
        lease.close();
    }
}
