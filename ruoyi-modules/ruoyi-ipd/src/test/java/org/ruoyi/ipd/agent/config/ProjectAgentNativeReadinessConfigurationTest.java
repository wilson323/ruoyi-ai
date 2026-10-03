package org.ruoyi.ipd.agent.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class ProjectAgentNativeReadinessConfigurationTest {
    @TempDir Path temporary;

    @Test void canonicalizesOnlyDefaultJvmTemporaryBase() throws Exception {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        assertThat(ProjectAgentConfiguration.nativeReadinessWorkspace(base.resolve("ipd-project-agent-workspace")))
            .isEqualTo(base.toRealPath().resolve("ipd-project-agent-workspace"));
        Path target = temporary.toRealPath().resolve("actual");
        java.nio.file.Files.createDirectory(target);
        Path custom = temporary.toRealPath().resolve("custom");
        java.nio.file.Files.createSymbolicLink(custom, target);
        assertThat(ProjectAgentConfiguration.nativeReadinessWorkspace(custom)).isEqualTo(custom);
        assertThat(java.nio.file.Files.isSymbolicLink(ProjectAgentConfiguration.nativeReadinessWorkspace(custom))).isTrue();
    }

    @Test void probesAreLazyReadOnlyAndCachedWithoutConvertingFailuresToSuccess() {
        var calls = new AtomicInteger();
        var ready = ProjectAgentConfiguration.cachedNativePrerequisite(() -> { calls.incrementAndGet(); return true; });
        assertThat(calls).hasValue(0);
        assertThat(ready.getAsBoolean()).isTrue();
        assertThat(ready.getAsBoolean()).isTrue();
        assertThat(calls).hasValue(1);
        var unavailable = ProjectAgentConfiguration.cachedNativePrerequisite(() -> { throw new IllegalStateException("Redis unavailable"); });
        assertThat(unavailable.getAsBoolean()).isFalse();
        assertThat(unavailable.getAsBoolean()).isFalse();
    }

    @Test void beanChecksActualStateRedisWithExplicitTimeoutInsteadOfObjectPresence() {
        var redis = org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class);
        var nodes = org.mockito.Mockito.mock(org.redisson.api.redisnode.RedisSingle.class);
        org.mockito.Mockito.when(redis.getRedisNodes(org.redisson.api.redisnode.RedisNodes.SINGLE)).thenReturn(nodes);
        org.mockito.Mockito.when(nodes.pingAll(2, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);
        var state = org.mockito.Mockito.mock(io.agentscope.core.state.AgentStateStore.class);
        var collaboration = org.mockito.Mockito.mock(io.agentscope.harness.agent.filesystem.remote.store.BaseStore.class);
        var client = org.mockito.Mockito.mock(redis.clients.jedis.UnifiedJedis.class);
        var catalog = new ProjectAgentConfiguration().projectAgentToolCatalog(
            new org.ruoyi.ipd.agent.catalog.CapabilityManifest(1, java.util.List.of(), java.util.List.of(), java.util.List.of()),
            temporary, state, null, collaboration, redis, client);
        org.mockito.Mockito.verifyNoInteractions(nodes, client);
        assertThat(catalog.status("web_fetch").reason()).isEqualTo("运行状态存储不可用");
        assertThat(catalog.status("execute").available()).isFalse();
        org.mockito.Mockito.verify(nodes).pingAll(2, java.util.concurrent.TimeUnit.SECONDS);
        org.mockito.Mockito.verifyNoInteractions(client);
    }
}
