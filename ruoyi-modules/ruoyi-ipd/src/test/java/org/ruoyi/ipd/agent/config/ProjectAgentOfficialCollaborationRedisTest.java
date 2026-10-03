package org.ruoyi.ipd.agent.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentOfficialCollaborationRedisTest {
    @Test void inheritsActualDatabaseAclAndTimeoutWithoutDisclosingCredentials() throws Exception {
        var config = org.mockito.Mockito.spy(new Config());
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setDatabase(6)
            .setUsername("test-acl").setPassword("test-secret").setConnectTimeout(1234).setTimeout(2345);
        var original = config.useSingleServer();
        var connection = ProjectAgentOfficialCollaborationRedis.connection(config);
        assertSame(original, config.useSingleServer());
        org.mockito.Mockito.verify(config, org.mockito.Mockito.never()).toJSON();
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

    @Test void tlsFactoriesAndDynamicCredentialsCannotBeLost() throws Exception {
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

    @Test void unsupportedTopologiesAndMissingConfigurationStayRejectedWithoutMutation() {
        var empty = new Config();
        assertThrows(IllegalStateException.class, () -> ProjectAgentOfficialCollaborationRedis.connection(empty));
        assertFalse(empty.isSingleConfig());
        var sentinel = new Config();
        sentinel.useSentinelServers().setMasterName("test").addSentinelAddress("redis://127.0.0.1:26379");
        var replicated = new Config();
        replicated.useReplicatedServers().addNodeAddress("redis://127.0.0.1:6379");
        var masterSlave = new Config();
        masterSlave.useMasterSlaveServers().setMasterAddress("redis://127.0.0.1:6379");
        for (var unsupported : new Config[]{sentinel, replicated, masterSlave}) {
            assertThrows(IllegalStateException.class,
                () -> ProjectAgentOfficialCollaborationRedis.connection(unsupported));
            assertFalse(unsupported.isSingleConfig());
        }
    }

    @Test void tlsMaterialAlgorithmsAndVerificationCannotSilentlyChange() throws Exception {
        for (int kind = 0; kind < 7; kind++) {
            var config = new Config();
            var single = config.useSingleServer().setAddress("rediss://127.0.0.1:6379");
            switch (kind) {
                case 0 -> single.setSslTruststore(new java.net.URI("file:/tmp/test-truststore").toURL());
                case 1 -> single.setSslKeystorePassword("test-secret");
                case 2 -> single.setSslProtocols(new String[]{"TLSv1.3"});
                case 3 -> single.setSslCiphers(new String[]{"TLS_AES_128_GCM_SHA256"});
                case 4 -> single.setSslEnableEndpointIdentification(false);
                case 5 -> single.setSslVerificationMode(org.redisson.config.SslVerificationMode.NONE);
                case 6 -> single.setSslKeyManagerFactory(javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()));
            }
            var rejected = assertThrows(IllegalStateException.class,
                () -> ProjectAgentOfficialCollaborationRedis.connection(config));
            assertFalse(rejected.getMessage().contains("test-secret"));
        }
    }
}
