package org.ruoyi.ipd.agent.config;

import io.agentscope.extensions.redis.store.RedisStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.net.URI;
import javax.net.ssl.SSLParameters;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/** 官方 BaseStore 复用当前运行 Redisson 的实际连接配置，不另选库或回落内存。 */
@Configuration(proxyBeanMethods = false)
public class ProjectAgentOfficialCollaborationRedis {
    public static final String CLIENT_BEAN = "projectAgentCollaborationRedisClient";
    public static final String STORE_BEAN = "projectAgentCollaborationBaseStore";

    @Bean(name = CLIENT_BEAN, destroyMethod = "close")
    public UnifiedJedis projectAgentCollaborationRedisClient(RedissonClient existing) {
        Connection settings = connection(existing.getConfig());
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        var config = DefaultJedisClientConfig.builder().database(settings.database())
            .user(settings.username()).password(settings.password()).ssl(settings.ssl())
            .sslParameters(sslParameters).connectionTimeoutMillis(settings.connectTimeout())
            .socketTimeoutMillis(settings.timeout()).build();
        return new JedisPooled(new HostAndPort(settings.host(), settings.port()), config);
    }

    @Bean(name = STORE_BEAN)
    public BaseStore projectAgentCollaborationBaseStore(@Qualifier(CLIENT_BEAN) UnifiedJedis client) {
        return new RedisStore(client, "ipd:project-agent:collaboration:");
    }

    static Connection connection(Config config) {
        if (config == null || !config.isSingleConfig())
            throw new IllegalStateException("Active Redis single-server configuration is required; other topologies require topology-specific configuration");
        // Redisson 3.51 returns the existing single-server object after topology checks.
        var liveSingle = config.useSingleServer();
        if (!(liveSingle.getCredentialsResolver() instanceof org.redisson.client.DefaultCredentialsResolver))
            throw new IllegalStateException("Dynamic Redis credentials require an equivalent official client configuration");
        if (liveSingle.getSslTrustManagerFactory() != null || liveSingle.getSslKeyManagerFactory() != null)
            throw new IllegalStateException("Redis TLS identity factories require an equivalent official client configuration");
        // TLS identity and algorithm settings must remain equivalent to the active provider.
        if (liveSingle.getSslTruststore() != null || liveSingle.getSslKeystore() != null
            || liveSingle.getSslTruststorePassword() != null || liveSingle.getSslKeystorePassword() != null)
            throw new IllegalStateException("Redis TLS material requires an equivalent official client configuration");
        if (liveSingle.getSslProtocols() != null && liveSingle.getSslProtocols().length > 0
            || liveSingle.getSslCiphers() != null && liveSingle.getSslCiphers().length > 0)
            throw new IllegalStateException("Redis TLS algorithms require an equivalent official client configuration");
        URI address;
        try { address = URI.create(liveSingle.getAddress()); }
        catch (Exception invalid) { throw new IllegalStateException("Active Redis address is invalid"); }
        boolean tls = "rediss".equals(address.getScheme());
        if ((!tls && !"redis".equals(address.getScheme())) || address.getHost() == null
            || address.getPort() < 1 || address.getUserInfo() != null)
            throw new IllegalStateException("Active Redis address is unsupported");
        if (tls && liveSingle.getSslVerificationMode() != org.redisson.config.SslVerificationMode.STRICT)
            throw new IllegalStateException("Redis TLS verification mode requires an equivalent official client configuration");
        return new Connection(address.getHost(), address.getPort(), liveSingle.getDatabase(),
            liveSingle.getUsername(), liveSingle.getPassword(), tls,
            liveSingle.getConnectTimeout(), liveSingle.getTimeout());
    }

    record Connection(String host, int port, int database, String username, String password,
                      boolean ssl, int connectTimeout, int timeout) {
        @Override public String toString() { return "OfficialRedisConnection[configuration redacted]"; }
    }
}
