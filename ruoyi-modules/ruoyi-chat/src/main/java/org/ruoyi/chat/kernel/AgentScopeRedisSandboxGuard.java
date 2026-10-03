package org.ruoyi.chat.kernel;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Redisson 版沙箱执行互斥（Redis {@code SET NX PX} 租约 + token CAS 释放）。
 *
 * <p>官方 {@code RedisSandboxExecutionGuard} 只接受 {@code UnifiedJedis}，本仓 Redis 底座是
 * 共享 Redisson 3.51，故按官方 {@link SandboxExecutionGuard} javadoc 的 Redis 租约示例等价实现：
 * 同一隔离键跨副本串行获得执行权，覆盖 acquire → start → (call) → stop → release 全窗口。
 *
 * <p>与 {@code LocalSessionTurnGate} 的关系：turn gate 是 JVM 级整轮互斥（先获取），本 guard 在
 * SandboxManager 内层（后获取），锁序恒定 gate → guard，无反向获取路径。单副本下两者无竞争；
 * 多副本下由本 guard 补齐跨节点串行，堵住双副本同 slot 并发保存的版本 CAS 竞态。
 *
 * <p>租约 TTL 是防副本崩溃后永久死锁的安全阀（对齐官方默认 30 分钟），不是正确性保证；
 * 自旋等待期间线程被中断即抛 {@link InterruptedException}，与官方实现一致。
 */
public final class AgentScopeRedisSandboxGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeRedisSandboxGuard.class);

    /** 对齐官方 RedisSandboxExecutionGuard 的默认租约 TTL（安全阀，非正确性保证）。 */
    static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(30);

    /** 对齐官方实现的租约获取自旋间隔。 */
    private static final long RETRY_INTERVAL_MS = 500L;

    private final RedissonClient redisson;
    private final String keyPrefix;
    private final Duration leaseTtl;

    public AgentScopeRedisSandboxGuard(RedissonClient redisson, String keyPrefix) {
        this(redisson, keyPrefix, DEFAULT_LEASE_TTL);
    }

    AgentScopeRedisSandboxGuard(RedissonClient redisson, String keyPrefix, Duration leaseTtl) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        Objects.requireNonNull(key, "key");
        String redisKey = redisKeyOf(key);
        String token = UUID.randomUUID().toString();
        while (true) {
            if (bucket(redisKey).setIfAbsent(token, leaseTtl)) {
                return () -> releaseQuietly(redisKey, token);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("sandbox guard wait interrupted: " + redisKey);
            }
            Thread.sleep(RETRY_INTERVAL_MS);
        }
    }

    private String redisKeyOf(SandboxIsolationKey key) {
        // KernelScopeKey 段内已拒 ':'，复合值带冒号也只是 Redis 键分隔符，无歧义。
        return keyPrefix + key.getScope().name().toLowerCase() + ":" + key.getValue();
    }

    private RBucket<String> bucket(String redisKey) {
        return redisson.getBucket(redisKey, StringCodec.INSTANCE);
    }

    /** CAS 删除：仅当仍持本 token 才释放；TTL 已过期被他人接手时不误删，失败只告警不抛（lease close 语义）。 */
    private void releaseQuietly(String redisKey, String token) {
        try {
            bucket(redisKey).compareAndSet(token, null);
        } catch (RuntimeException e) {
            log.warn("[sandbox-guard] lease release failed (key={}): {}", redisKey, e.getMessage());
        }
    }
}
