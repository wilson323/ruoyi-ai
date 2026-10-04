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
        when(lock.getTokenAsync()).thenReturn(new CompletableFutureWrapper<Long>(10L));
        when(lock.isHeldByThreadAsync(anyLong())).thenAnswer(call -> new CompletableFutureWrapper<Boolean>(holder.get() == (long) call.getArgument(0)));
        when(lock.unlockAsync(anyLong())).thenAnswer(call -> {
            holder.compareAndSet(call.getArgument(0), 0); return new CompletableFutureWrapper<Void>((Void) null);
        });
        var first = new ProjectAgentRunOwnership(redis).acquire(1L).orElseThrow();
        assertTrue(first.held()); assertTrue(new ProjectAgentRunOwnership(redis).acquire(1L).isEmpty());
        // 模拟租约丢失后新的拥有者；旧owner close不能释放新owner。
        holder.set(123L); assertFalse(first.held()); first.close(); assertEquals(123L, holder.get());
        verify(lock, never()).forceUnlockAsync(); verify(lock).unlockAsync(anyLong());
    }
    @Test void stalledOwnershipCheckFailsClosedAndCloseStillReleasesOriginalOwner() {
        var redis = mock(RedissonClient.class); var lock = mock(RFencedLock.class);
        when(redis.getFencedLock(anyString())).thenReturn(lock);
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
            .thenReturn(new CompletableFutureWrapper<Boolean>(true));
        when(lock.getTokenAsync()).thenReturn(new CompletableFutureWrapper<Long>(10L));
        when(lock.isHeldByThreadAsync(anyLong())).thenAnswer(ignored ->
            new CompletableFutureWrapper<Boolean>(new java.util.concurrent.CompletableFuture<Boolean>()));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Void>((Void) null));
        var lease = new ProjectAgentRunOwnership(redis, 20L).acquire(1L).orElseThrow();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
            assertFalse(lease.held()); lease.close();
        });
        verify(lock).unlockAsync(anyLong());
        verify(lock, never()).forceUnlockAsync();
    }

    @Test void interruptedOwnershipCheckPreservesInterruptionAndFailsClosed() {
        var redis = mock(RedissonClient.class); var lock = mock(RFencedLock.class);
        when(redis.getFencedLock(anyString())).thenReturn(lock);
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
            .thenReturn(new CompletableFutureWrapper<Boolean>(true));
        when(lock.getTokenAsync()).thenReturn(new CompletableFutureWrapper<Long>(10L));
        when(lock.isHeldByThreadAsync(anyLong())).thenAnswer(ignored ->
            new CompletableFutureWrapper<Boolean>(new java.util.concurrent.CompletableFuture<Boolean>()));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Void>((Void) null));
        var lease = new ProjectAgentRunOwnership(redis, 20L).acquire(1L).orElseThrow();
        Thread.currentThread().interrupt();
        try { assertFalse(lease.held()); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        lease.close(); verify(lock).unlockAsync(anyLong());
    }
    @Test void lateAcquisitionAfterTimeoutReleasesOnlyItsOriginalOwner() {
        var redis = mock(RedissonClient.class); var lock = mock(RFencedLock.class);
        var pending = new java.util.concurrent.CompletableFuture<Boolean>();
        when(redis.getFencedLock(anyString())).thenReturn(lock);
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
            .thenReturn(new CompletableFutureWrapper<Boolean>(pending));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
            assertThrows(IllegalStateException.class, () -> new ProjectAgentRunOwnership(redis, 20L).acquire(1L)));
        var owner = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(lock).tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), owner.capture());
        verify(lock, never()).unlockAsync(anyLong());
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Void>((Void) null));
        pending.complete(true);
        verify(lock).unlockAsync(owner.getValue());
        verify(lock, never()).forceUnlockAsync();
    }

    @Test void stalledReleaseHasBoundedWaitAndNeverForceUnlocks() {
        var redis = mock(RedissonClient.class); var lock = mock(RFencedLock.class);
        when(redis.getFencedLock(anyString())).thenReturn(lock);
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
            .thenReturn(new CompletableFutureWrapper<Boolean>(true));
        when(lock.getTokenAsync()).thenReturn(new CompletableFutureWrapper<Long>(10L));
        when(lock.isHeldByThreadAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Boolean>(true));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Void>(
            new java.util.concurrent.CompletableFuture<Void>()));
        var lease = new ProjectAgentRunOwnership(redis, 20L).acquire(1L).orElseThrow();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
            assertThrows(IllegalStateException.class, lease::close));
        assertFalse(lease.held());
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Void>((Void) null));
        assertDoesNotThrow(lease::close); assertDoesNotThrow(lease::close);
        verify(lock, times(2)).unlockAsync(anyLong()); verify(lock, never()).forceUnlockAsync();
    }
    @Test void closeAfterOwnershipMovedIgnoresOnlyOriginalOwnerAlreadyReleased() {
        var redis = mock(RedissonClient.class); var lock = mock(RFencedLock.class);
        when(redis.getFencedLock(anyString())).thenReturn(lock);
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
            .thenReturn(new CompletableFutureWrapper<Boolean>(true));
        when(lock.getTokenAsync()).thenReturn(new CompletableFutureWrapper<Long>(10L));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Void>(
            java.util.concurrent.CompletableFuture.failedFuture(new IllegalMonitorStateException("owner moved"))));
        var lease = new ProjectAgentRunOwnership(redis, 20L).acquire(1L).orElseThrow();
        assertDoesNotThrow(lease::close); assertDoesNotThrow(lease::close);
        verify(lock, times(1)).unlockAsync(anyLong()); verify(lock, never()).forceUnlockAsync();
    }
}
