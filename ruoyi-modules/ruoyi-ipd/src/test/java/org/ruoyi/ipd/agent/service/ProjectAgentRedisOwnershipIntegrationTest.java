package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 仅本机专用Redis双客户端实测；负数runId不对应业务运行，清理仅删除该随机lock及token。 */
@Tag("dev")
@EnabledIfSystemProperty(named = "ipd.agent.redis.integration.enabled", matches = "true")
class ProjectAgentRedisOwnershipIntegrationTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void independentClientsRespectLiveOwnerThenTakeOverAfterWatchdogOwnerStops() throws Exception {
        long runId = -ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String key = "ipd:project-agent:owner:" + runId;
        RedissonClient first = null;
        RedissonClient second = null;
        ProjectAgentRunOwnership.Lease original = null;
        ProjectAgentRunOwnership.Lease successor = null;
        try {
            first = client();
            second = client();
            var firstOwner = new ProjectAgentRunOwnership(first);
            var secondOwner = new ProjectAgentRunOwnership(second);
            original = firstOwner.acquire(runId).orElseThrow();
            long firstToken = original.token();
            assertTrue(original.held());
            assertTrue(secondOwner.acquire(runId).isEmpty(), "other client must not acquire a live owner's lease");
            // 超过一次watchdog周期，证明活跃拥有者续租后仍不可夺权。
            Thread.sleep(1200);
            assertTrue(original.held());
            assertTrue(secondOwner.acquire(runId).isEmpty(), "watchdog must keep the active owner's lock");
            first.shutdown(0, 0, TimeUnit.MILLISECONDS);
            assertFalse(original.held(), "stopped client cannot claim effective ownership");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (successor == null && System.nanoTime() < deadline) {
                successor = secondOwner.acquire(runId).orElse(null);
                if (successor == null) Thread.sleep(50);
            }
            assertNotNull(successor, "owner stop must permit acquisition after its watchdog TTL expires");
            assertTrue(successor.token() > firstToken, "fence token must increase across independent owners");
            assertTrue(successor.held());
            original.close();
            assertTrue(successor.held(), "old owner cleanup must not unlock the new owner's lease");
            assertTrue(secondOwner.acquire(runId).isEmpty(), "same JVM thread also cannot reenter another execution's lease");
        } finally {
            RedissonClient cleanupClient = null;
            try {
                try { if (original != null) original.close(); }
                finally { if (successor != null) successor.close(); }
            } finally {
                try {
                    RedissonClient available = second != null && !second.isShutdown() && !second.isShuttingDown() ? second
                        : first != null && !first.isShutdown() && !first.isShuttingDown() ? first : null;
                    if (available == null && (first != null || second != null)) {
                        // 异常时两测试client均已停止，仅开短连接清理自己的exact keys。
                        cleanupClient = client();
                        available = cleanupClient;
                    }
                    if (available != null) {
                        // 3.51源码RedissonFencedLock.tokenName=RedissonObject.prefixName(...,rawName)。
                        // RFencedLock不继承RObject.delete；RKeys.delete(String...)才是官方接口。
                        available.getKeys().delete(key, org.redisson.RedissonObject.prefixName("redisson_lock_token", key));
                    }
                } finally {
                    try { if (cleanupClient != null && !cleanupClient.isShutdown()) cleanupClient.shutdown(0, 0, TimeUnit.MILLISECONDS); }
                    finally {
                        try { if (first != null && !first.isShutdown()) first.shutdown(0, 0, TimeUnit.MILLISECONDS); }
                        finally { if (second != null && !second.isShutdown()) second.shutdown(0, 0, TimeUnit.MILLISECONDS); }
                    }
                }
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void nativeSdkStateRoundTripsThinkingAndRejectsStaleCasWithoutDeletingAdjacentSession() {
        String prefix = "ipd:test:agent-state:" + java.util.UUID.randomUUID() + ":";
        RedissonClient client = client();
        var store = org.ruoyi.chat.kernel.AgentScopeRedisStateStores.create(client, prefix);
        String user = "negative-test-user";
        String session = "negative-test-run";
        String adjacent = "negative-test-adjacent";
        var thinking = io.agentscope.core.message.ThinkingBlock.builder().thinking("temporary private reasoning fixture").build();
        var state = io.agentscope.core.state.AgentState.builder().userId(user).sessionId(session)
            .addMessage(io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .content(java.util.List.of(thinking)).build()).build();
        try {
            assertTrue(store.supportsVersioning());
            assertEquals(1, store.saveIfVersion(user, session, "agent_state", state, 0));
            var read = store.getVersioned(user, session, "agent_state", io.agentscope.core.state.AgentState.class);
            assertEquals(1, read.version());
            var restored = store.get(user, session, "agent_state", io.agentscope.core.state.AgentState.class).orElseThrow();
            assertTrue(restored.getContext().stream().flatMap(msg -> msg.getContent().stream())
                .filter(io.agentscope.core.message.ThinkingBlock.class::isInstance)
                .map(io.agentscope.core.message.ThinkingBlock.class::cast)
                .anyMatch(block -> "temporary private reasoning fixture".equals(block.getThinking())));
            assertEquals(io.agentscope.core.state.AgentStateStore.UNVERSIONED,
                store.saveIfVersion(user, session, "agent_state", state, 0), "native CAS must reject stale version");
            assertEquals(1, store.getVersioned(user, session, "agent_state", io.agentscope.core.state.AgentState.class).version());
            assertEquals(2, store.saveIfVersion(user, session, "agent_state", state, 1));
            store.save(user, adjacent, "agent_state", state);
            assertTrue(store.exists(user, adjacent));
            store.delete(user, session);
            assertFalse(store.exists(user, session));
            assertTrue(store.exists(user, adjacent), "native delete must isolate the exact session");
            store.close();
            assertFalse(client.isShutdown(), "state store does not own the shared Redis client");
        } finally {
            try {
                store.delete(user, session);
                store.delete(user, adjacent);
            } finally {
                try { store.close(); }
                finally { client.shutdown(0, 0, TimeUnit.MILLISECONDS); }
            }
        }
    }

    private static RedissonClient client() {
        Config config = new Config();
        config.setLockWatchdogTimeout(1000);
        config.setNettyThreads(2);
        config.setThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:16379")
            .setDatabase(0).setConnectTimeout(500).setTimeout(500).setRetryAttempts(0)
            .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2)
            .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        return Redisson.create(config);
    }
}
