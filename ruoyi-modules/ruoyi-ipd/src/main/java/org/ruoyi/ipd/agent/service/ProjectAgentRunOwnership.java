package org.ruoyi.ipd.agent.service;

import org.redisson.api.RedissonClient;
import org.redisson.api.RFencedLock;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Redisson只协调执行所有权；业务状态及epoch仍在原运行表。 */
public final class ProjectAgentRunOwnership {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectAgentRunOwnership.class);
    private static final AtomicLong OWNER_IDS = new AtomicLong(Long.MIN_VALUE);
    private final RedissonClient redisson;
    private final long checkTimeoutMillis;
    public ProjectAgentRunOwnership(RedissonClient redisson) { this(redisson, 10_000L); }
    ProjectAgentRunOwnership(RedissonClient redisson, long checkTimeoutMillis) {
        if (checkTimeoutMillis <= 0) throw new IllegalArgumentException("ownership check timeout must be positive");
        this.redisson = redisson; this.checkTimeoutMillis = checkTimeoutMillis;
    }

    private <T> T await(java.util.concurrent.CompletableFuture<T> request, String operation) {
        try { return request.get(checkTimeoutMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while " + operation + " project agent ownership", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            throw new IllegalStateException("Project agent ownership " + operation + " did not complete", unavailable);
        }
    }

    private boolean heldBy(RFencedLock lock, long owner) {
        try { return Boolean.TRUE.equals(await(lock.isHeldByThreadAsync(owner).toCompletableFuture(), "checking")); }
        catch (RuntimeException unavailable) {
            return false; // Unknown ownership must never authorize a business write or unlock.
        }
    }

    private static boolean alreadyReleased(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof IllegalMonitorStateException) return true;
        return false;
    }

    private void unlock(RFencedLock lock, long owner) {
        await(lock.unlockAsync(owner).toCompletableFuture(), "release");
    }

    public interface Lease extends AutoCloseable {
        boolean held();
        long token();
        void close();
    }

    /** 独立逻辑threadId，禁止同JVM线程重入后把活跃运行误认成无人持有。 */
    public Optional<Lease> acquire(Long runId) {
        RFencedLock lock = redisson.getFencedLock("ipd:project-agent:owner:" + runId);
        long owner = OWNER_IDS.incrementAndGet();
        var pending = lock.tryLockAsync(0, -1, TimeUnit.MILLISECONDS, owner).toCompletableFuture();
        boolean won;
        try { won = Boolean.TRUE.equals(await(pending, "acquire")); }
        catch (RuntimeException failure) {
            // A timed-out Redis command can still acquire later. Release only that logical owner;
            // never cancel the request or force-unlock a possible successor's lease.
            pending.whenComplete((acquired, error) -> {
                if (Boolean.TRUE.equals(acquired)) {
                    try {
                        lock.unlockAsync(owner).whenComplete((ignored, releaseFailure) -> {
                            if (releaseFailure != null && !alreadyReleased(releaseFailure))
                                log.warn("project_agent operation=LATE_OWNER_RELEASE status=PENDING_RECOVERY runId={} errorType={}",
                                    runId, releaseFailure.getClass().getName());
                        });
                    } catch (RuntimeException releaseFailure) {
                        log.warn("project_agent operation=LATE_OWNER_RELEASE status=PENDING_RECOVERY runId={} errorType={}",
                            runId, releaseFailure.getClass().getName());
                    }
                }
            });
            throw failure;
        }
        if (!won) return Optional.empty();
        try {
            Long token = await(lock.getTokenAsync().toCompletableFuture(), "token read");
            if (token == null) throw new IllegalStateException("fenced lock returned no token");
            AtomicBoolean closed = new AtomicBoolean();
            AtomicBoolean released = new AtomicBoolean();
            return Optional.of(new Lease() {
                public boolean held() {
                    return !closed.get() && !redisson.isShutdown() && !redisson.isShuttingDown()
                        && heldBy(lock, owner);
                }
                public long token() { return token; }
                public void close() {
                    closed.set(true); // Stop authorizing writes even when Redis release needs a retry.
                    if (!released.get() && !redisson.isShutdown() && !redisson.isShuttingDown()) {
                        try { unlock(lock, owner); released.set(true); }
                        catch (RuntimeException failure) {
                            if (!alreadyReleased(failure)) throw failure;
                            released.set(true);
                        }
                    }
                }
            });
        } catch (RuntimeException failure) {
            try { unlock(lock, owner); }
            catch (RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
            throw failure;
        }
    }

    public static final class OwnershipLost extends RuntimeException {
        public OwnershipLost() { super("project agent execution ownership lost"); }
    }
}
