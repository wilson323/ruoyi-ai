package org.ruoyi.ipd.agent.service;

import org.redisson.api.RedissonClient;
import org.redisson.api.RFencedLock;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Redisson只协调执行所有权；业务状态及epoch仍在原运行表。 */
public final class ProjectAgentRunOwnership {
    private static final AtomicLong OWNER_IDS = new AtomicLong(Long.MIN_VALUE);
    private final RedissonClient redisson;
    public ProjectAgentRunOwnership(RedissonClient redisson) { this.redisson = redisson; }

    public interface Lease extends AutoCloseable {
        boolean held();
        long token();
        void close();
    }

    /** 独立逻辑threadId，禁止同JVM线程重入后把活跃运行误认成无人持有。 */
    public Optional<Lease> acquire(Long runId) {
        RFencedLock lock = redisson.getFencedLock("ipd:project-agent:owner:" + runId);
        long owner = OWNER_IDS.incrementAndGet();
        boolean won = lock.tryLockAsync(0, -1, TimeUnit.MILLISECONDS, owner).toCompletableFuture().join();
        if (!won) return Optional.empty();
        try {
            Long token = lock.getToken();
            if (token == null) throw new IllegalStateException("fenced lock returned no token");
            AtomicBoolean closed = new AtomicBoolean();
            return Optional.of(new Lease() {
                public boolean held() {
                    return !closed.get() && !redisson.isShutdown() && !redisson.isShuttingDown()
                        && lock.isHeldByThreadAsync(owner).toCompletableFuture().join();
                }
                public long token() { return token; }
                public void close() {
                    if (closed.compareAndSet(false, true) && !redisson.isShutdown() && !redisson.isShuttingDown()
                        && lock.isHeldByThreadAsync(owner).toCompletableFuture().join())
                        lock.unlockAsync(owner).toCompletableFuture().join();
                }
            });
        } catch (RuntimeException failure) {
            lock.unlockAsync(owner).toCompletableFuture().join();
            throw failure;
        }
    }

    public static final class OwnershipLost extends RuntimeException {
        public OwnershipLost() { super("project agent execution ownership lost"); }
    }
}
