package org.ruoyi.ipd.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        JsonNode root;
        try { root = new ObjectMapper().readTree(config.toJSON()); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot inspect the active Redis provider configuration"); }
        if (!root.path("clusterServersConfig").isMissingNode() && !root.path("clusterServersConfig").isNull()
            || !root.path("sentinelServersConfig").isMissingNode() && !root.path("sentinelServersConfig").isNull()
            || !root.path("replicatedServersConfig").isMissingNode() && !root.path("replicatedServersConfig").isNull())
            throw new IllegalStateException("Official collaboration Redis provider requires topology-specific configuration");
        JsonNode single = root.path("singleServerConfig");
        if (single.isMissingNode() || single.isNull())
            throw new IllegalStateException("Active Redis single-server configuration is required");
        // ConfigSupport 的 JSON mixin 忽略 TLS factory，不能仅靠序列化快照判断安全等价。
        // 已确认singleServerConfig存在后useSingleServer只返回该对象（Redisson3.51源码285-298），不改配置。
        var liveSingle = config.useSingleServer();
        if (!(liveSingle.getCredentialsResolver() instanceof org.redisson.client.DefaultCredentialsResolver))
            throw new IllegalStateException("Dynamic Redis credentials require an equivalent official client configuration");
        if (liveSingle.getSslTrustManagerFactory() != null || liveSingle.getSslKeyManagerFactory() != null)
            throw new IllegalStateException("Redis TLS identity factories require an equivalent official client configuration");
        // TLS证书、客户端身份或端点校验不能换成Jedis默认并假称等价。
        for (String field : new String[]{"sslTruststore", "sslKeystore", "sslTruststorePassword",
                "sslKeystorePassword", "sslTrustManagerFactory", "sslKeyManagerFactory"})
            if (!single.path(field).isMissingNode() && !single.path(field).isNull())
                throw new IllegalStateException("Redis TLS material requires an equivalent official client configuration");
        for (String field : new String[]{"sslProtocols", "sslCiphers"})
            if (single.path(field).isArray() && !single.path(field).isEmpty())
                throw new IllegalStateException("Redis TLS algorithms require an equivalent official client configuration");
        URI address;
        try { address = URI.create(single.path("address").asText()); }
        catch (Exception invalid) { throw new IllegalStateException("Active Redis address is invalid"); }
        boolean tls = "rediss".equals(address.getScheme());
        if ((!tls && !"redis".equals(address.getScheme())) || address.getHost() == null
            || address.getPort() < 1 || address.getUserInfo() != null)
            throw new IllegalStateException("Active Redis address is unsupported");
        if (tls && (!single.path("sslEnableEndpointIdentification").asBoolean(true)
            || !"STRICT".equals(single.path("sslVerificationMode").asText("STRICT"))))
            throw new IllegalStateException("Redis TLS verification mode requires an equivalent official client configuration");
        return new Connection(address.getHost(), address.getPort(), single.path("database").asInt(0),
            text(single, "username"), text(single, "password"), tls,
            single.path("connectTimeout").asInt(10000), single.path("timeout").asInt(3000));
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isMissingNode() || node.path(field).isNull() ? null : node.path(field).asText();
    }
    record Connection(String host, int port, int database, String username, String password,
                      boolean ssl, int connectTimeout, int timeout) {
        @Override public String toString() { return "OfficialRedisConnection[configuration redacted]"; }
    }
}
