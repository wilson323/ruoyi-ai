package org.ruoyi.ipd.agent.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentOfficialCollaborationRedisTest {
    @Test void inheritsActualDatabaseAclAndTimeoutWithoutDisclosingCredentials() {
        var config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setDatabase(6)
            .setUsername("test-acl").setPassword("test-secret").setConnectTimeout(1234).setTimeout(2345);
        var connection = ProjectAgentOfficialCollaborationRedis.connection(config);
        assertEquals(6, connection.database());
        assertEquals("test-acl", connection.username());
        assertEquals("test-secret", connection.password());
        assertEquals(1234, connection.connectTimeout());
        assertEquals(2345, connection.timeout());
        assertFalse(connection.toString().contains("test-secret"));
        assertFalse(connection.ssl());
    }

    @Test void topologyAndTlsIdentityCannotSilentlyDowngrade() {
        var cluster = new Config();
        cluster.useClusterServers().addNodeAddress("redis://127.0.0.1:6379");
        assertThrows(IllegalStateException.class, () -> ProjectAgentOfficialCollaborationRedis.connection(cluster));
        var tls = new Config();
        tls.useSingleServer().setAddress("rediss://127.0.0.1:6379");
        assertTrue(ProjectAgentOfficialCollaborationRedis.connection(tls).ssl());
    }

    @Test void jsonHiddenTlsFactoriesAndDynamicCredentialsCannotBeLost() throws Exception {
        var tls = new Config();
        tls.useSingleServer().setAddress("rediss://127.0.0.1:6379")
            .setSslTrustManagerFactory(javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()));
        assertThrows(IllegalStateException.class, () -> ProjectAgentOfficialCollaborationRedis.connection(tls));
        var dynamic = new Config();
        dynamic.useSingleServer().setAddress("redis://127.0.0.1:6379")
            .setCredentialsResolver(org.mockito.Mockito.mock(org.redisson.config.CredentialsResolver.class));
        assertThrows(IllegalStateException.class, () -> ProjectAgentOfficialCollaborationRedis.connection(dynamic));
    }
}
