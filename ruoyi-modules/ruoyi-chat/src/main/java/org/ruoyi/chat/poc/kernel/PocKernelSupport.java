package org.ruoyi.chat.poc.kernel;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.ruoyi.chat.kernel.ChatOfficialCapabilities;
import org.ruoyi.chat.kernel.KernelScopeKey;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ModelCreationContext;
import java.util.List;
import java.util.function.Function;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;

/**
 * [PoC G4/G5] AgentScope PoC 共享装配:真库 ipd_poc 状态存储 + HarnessAgent 工厂 + 模型解析。
 *
 * <p>模型:默认 {@code minimax:MiniMax-M3}(agentscope MiniMaxModelProvider,读 MINIMAX_API_KEY,
 * OpenAI 兼容端点 https://api.minimaxi.com/v1),可用 {@code -Dpoc.model.id} 覆盖。
 * 模型凭据缺失时各关标 BLOCKED_ENVIRONMENT,不得伪造输出。
 */
public final class PocKernelSupport {

    public static final String POC_DATABASE = "ipd_poc";
    public static final String STATE_TABLE = "agentscope_sessions";

    private static volatile MysqlAgentStateStore stateStore;
    private static final ConcurrentMap<String, HarnessAgent> AGENTS = new ConcurrentHashMap<>();

    private PocKernelSupport() {}

    /** 共享 MysqlAgentStateStore(ipd_poc.agentscope_sessions,表已按源码 DDL 预建)。 */
    public static MysqlAgentStateStore stateStore() {
        if (stateStore == null) {
            synchronized (PocKernelSupport.class) {
                if (stateStore == null) {
                    stateStore =
                            new MysqlAgentStateStore(pocDataSource(), POC_DATABASE, STATE_TABLE, false);
                }
            }
        }
        return stateStore;
    }

    /** 按 agentId 缓存 HarnessAgent 实例(引擎无状态,per-(userId,sessionId) 内核串行化)。 */
    public static HarnessAgent agent(String agentId) {
        return AGENTS.computeIfAbsent(agentId, PocKernelSupport::buildAgent);
    }

    /** Authenticated PoC consumer: user/project are part of both the cache key and sandbox root. */
    public static HarnessAgent agent(String projectId, String userId, String agentId, String sessionId) {
        var scope = KernelScopeKey.of(projectId, userId, agentId, sessionId);
        if (userId == null || userId.isBlank()) { throw new IllegalArgumentException("Authenticated user required"); }
        return AGENTS.computeIfAbsent(scope.slotId(), ignored -> buildAgent(agentId, scope.userId()));
    }

    public static HarnessAgent buildAgent(String agentId) {
        return buildAgent(agentId, null);
    }

    private static HarnessAgent buildAgent(String agentId, String expectedUser) {
        try {
            if (agentId == null || !agentId.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Single-segment PoC agent identity required");
            }
            Path ws = Files.createTempDirectory("poc-kernel-" + agentId + "-");
            Files.writeString(ws.resolve("AGENTS.md"), "# PoC " + agentId + "\n\nIsolation/streaming PoC employee.\n");
            var binding = resolveModel(System.getProperty("poc.model.id", "minimax:MiniMax-M3"), System::getenv);
            var builder = HarnessAgent.builder()
                    .name(agentId)
                    .sysPrompt("You are a helpful assistant. Answer concisely in the user's language.")
                    .model(binding.model())
                    .workspace(ws)
                    .stateStore(stateStore());
            var capabilities = ChatOfficialCapabilities.configure(builder, ws, expectedUser,
                System.getProperty("chat.kernel.agentscope.sandbox-image", "python:3.13-alpine"), binding.knownSecrets());
            var built = builder.build();
            capabilities.bind(built);
            return built;
        } catch (Exception e) {
            throw new IllegalStateException("build HarnessAgent failed: " + agentId, e);
        }
    }

    /** 模型解析:ModelRegistry(provider SPI),默认 MiniMax OpenAI 兼容端点。 */
    public static Model model() {
        String modelId = System.getProperty("poc.model.id", "minimax:MiniMax-M3");
        return resolveModel(modelId, System::getenv).model();
    }

    record ModelBinding(Model model, List<String> knownSecrets) {
        @Override public String toString() { return "ModelBinding[knownSecrets=<redacted>]"; }
    }

    static ModelBinding resolveModel(String modelId, Function<String, String> environment) {
        // Official 2.0.3 MiniMax provider uses context.apiKey before MINIMAX_API_KEY.
        // Other SPI providers own their credential resolution; no Vault reference is guessed here.
        if (modelId != null && modelId.startsWith("minimax:")) {
            String key = io.agentscope.core.model.ModelProviderSupport.trimToNull(environment.apply("MINIMAX_API_KEY"));
            var context = ModelCreationContext.builder().apiKey(key).build();
            return new ModelBinding(ModelRegistry.resolve(modelId, context), key == null ? List.of() : List.of(key));
        }
        return new ModelBinding(ModelRegistry.resolve(modelId), List.of());
    }

    /** 连接 ipd_poc(凭证走 .codex/ipd-dev/config/mysql-app.cnf,不上命令行)。 */
    public static DataSource pocDataSource() {
        try {
            Properties cnf = readCnf(locateRepoRoot().resolve(".codex/ipd-dev/config/mysql-app.cnf"));
            MysqlDataSource ds = new MysqlDataSource();
            ds.setUrl("jdbc:mysql://127.0.0.1:13306/" + POC_DATABASE
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
            ds.setUser(cnf.getProperty("user"));
            ds.setPassword(cnf.getProperty("password"));
            return ds;
        } catch (Exception e) {
            throw new IllegalStateException("read mysql-app.cnf failed", e);
        }
    }

    /** 向上定位仓根(.codex/ipd-dev/config/mysql-app.cnf 所在层)。 */
    public static Path locateRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(".codex/ipd-dev/config/mysql-app.cnf"))) return dir;
            dir = dir.getParent();
        }
        throw new IllegalStateException("repo root with .codex/ipd-dev/config/mysql-app.cnf not found");
    }

    private static Properties readCnf(Path path) throws Exception {
        Properties props = new Properties();
        for (String line : Files.readAllLines(path)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) props.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return props;
    }
}
