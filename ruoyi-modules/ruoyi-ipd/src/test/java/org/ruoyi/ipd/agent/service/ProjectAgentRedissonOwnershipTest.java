package org.ruoyi.ipd.agent.service;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.redisson.misc.CompletableFutureWrapper;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class ProjectAgentRedissonOwnershipTest {
    @Test void sameJvmThreadCannotReenterAnotherExecutionAndLateCloseCannotUnlockNewOwner() {
        var redis = mock(RedissonClient.class); var lock = mock(RFencedLock.class); var holder = new AtomicLong();
        when(redis.getFencedLock(anyString())).thenReturn(lock);
        when(lock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenAnswer(call -> {
            long owner = call.getArgument(3); return new CompletableFutureWrapper<Boolean>(holder.compareAndSet(0, owner));
        });
        when(lock.getToken()).thenReturn(10L);
        when(lock.isHeldByThreadAsync(anyLong())).thenAnswer(call -> new CompletableFutureWrapper<Boolean>(holder.get() == (long) call.getArgument(0)));
        when(lock.unlockAsync(anyLong())).thenAnswer(call -> {
            holder.compareAndSet(call.getArgument(0), 0); return new CompletableFutureWrapper<Void>((Void) null);
        });
        var first = new ProjectAgentRunOwnership(redis).acquire(1L).orElseThrow();
        assertTrue(first.held()); assertTrue(new ProjectAgentRunOwnership(redis).acquire(1L).isEmpty());
        // 模拟租约丢失后新的拥有者；旧owner close不能释放新owner。
        holder.set(123L); assertFalse(first.held()); first.close(); assertEquals(123L, holder.get());
        verify(lock, never()).forceUnlockAsync(); verify(lock, never()).unlockAsync(anyLong());
    }
}
