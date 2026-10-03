package org.ruoyi.ipd.agent.kernel;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.harness.agent.HarnessAgent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.service.AiModelConfigService;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 官方回退模型装配（AgentScope 2.0.3 fallbackModel 扩展点）三层验证：
 * <ol>
 *   <li>目录层：{@link ProjectAgentModelCatalog#resolveFallback} 按 fallbackFor 约定取回退行
 *       （凭据内存解密、不读 is_active、无约定行 fail-open 返回空）；</li>
 *   <li>装配层：{@link ProjectAgentModelAssembler#assembleFallback} 与主请求同缝
 *       （endpointGuard 前置 + 原生 ModelRegistry 解析 + 人员/运行身份直达 provider context）；</li>
 *   <li>内核层：{@code HarnessAgent.Builder#fallbackModel}（2.0.3 javap 铁证）真实挂上回退模型，
 *       且经 {@link ProjectAgentMeteredModel} 同账计量；未配置回退时官方 ModelConfig.fallbackModel 为 null。</li>
 * </ol>
 * API 以本机 agentscope-core/harness 2.0.3 JAR javap 为准（ReActAgent#getModelConfig、
 * ModelConfig#fallbackModel/maxRetries）。
 */
@Tag("dev")
class ProjectAgentFallbackModelAssemblyTest {

    private static final String ENV_KEY = "unit-test-master-key-32bytes!!!!";
    private static final String PLAIN_KEY = "sk-fallback-plain-credential";

    private static final KernelModelRequest PRIMARY =
        new KernelModelRequest("MiniMax-M3", "MiniMax", PLAIN_KEY, "https://example.invalid/v1");

    @TempDir
    Path workspaceRoot;

    private final AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);

    private final IAuditLogService auditLog = mock(IAuditLogService.class);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiModelConfig.class);
    }

    // ==================== 目录层：fallbackFor 约定解析 ====================

    @Test
    @DisplayName("目录：fallbackFor 约定命中回退行，凭据内存解密且不读 is_active")
    void catalogResolvesConventionFallbackRowWithDecryptedCredential() {
        AiModelConfig fallback = entity(9902L, "MiniMax-M2.7-highspeed",
            "{\"fallbackFor\":\"MiniMax-M3\"}", false);
        AiModelConfig active = entity(9901L, "MiniMax-M3", "{}", true);
        when(mapper.selectList(any())).thenReturn(List.of(active, fallback));
        var catalog = catalog();

        var resolved = catalog.resolveFallback(PRIMARY);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().modelName()).isEqualTo("MiniMax-M2.7-highspeed");
        assertThat(resolved.get().providerCode()).isEqualTo("MiniMax");
        assertThat(resolved.get().apiHost()).isEqualTo("https://example.invalid/v1");
        assertThat(resolved.get().apiKey()).isEqualTo(PLAIN_KEY);
    }

    @Test
    @DisplayName("目录：无 fallbackFor 约定行/回退键指向他人/主请求缺字段 → 空（fail-open）")
    void catalogFallbackEmptyWithoutConventionRow() {
        when(mapper.selectList(any())).thenReturn(List.of(
            entity(9901L, "MiniMax-M3", "{}", true),
            entity(9903L, "MiniMax-M2.5", "{\"fallbackFor\":\"MiniMax-M2\"}", false)));
        var catalog = catalog();

        assertThat(catalog.resolveFallback(PRIMARY)).isEmpty();
        assertThat(catalog.resolveFallback(null)).isEmpty();
        assertThat(catalog.resolveFallback(
            new KernelModelRequest(null, "MiniMax", PLAIN_KEY, "https://example.invalid/v1"))).isEmpty();
        assertThat(catalog.resolveFallback(
            new KernelModelRequest("MiniMax-M3", " ", PLAIN_KEY, "https://example.invalid/v1"))).isEmpty();
    }

    // ==================== 装配层：与主请求同缝 ====================

    @Test
    @DisplayName("装配：未配置回退源或目录无回退 → null，不触碰解析缝")
    void assemblerFallbackNullWithoutSourceOrRow() {
        AtomicReference<Boolean> resolved = new AtomicReference<>();
        ProjectAgentModelAssembler withoutSource = new ProjectAgentModelAssembler((key, ctx) -> {
            resolved.set(true);
            return null;
        });
        assertThat(withoutSource.assembleFallback(PRIMARY, 42L, 77L)).isNull();
        assertThat(withoutSource
            .withFallbackSource(primary -> null)
            .assembleFallback(PRIMARY, 42L, 77L)).isNull();
        assertThat(resolved.get()).isNull();
    }

    @Test
    @DisplayName("装配：回退请求经同一 endpointGuard 与原生注册键（minimax:MiniMax-M2.7-highspeed）")
    void assemblerFallbackResolvesThroughOfficialSeam() {
        AtomicReference<String> guardHost = new AtomicReference<>();
        AtomicReference<String> registryKey = new AtomicReference<>();
        AtomicReference<ModelCreationContext> context = new AtomicReference<>();
        Model fallbackModel = mock(Model.class);
        ProjectAgentModelAssembler assembler = new ProjectAgentModelAssembler((key, ctx) -> {
            registryKey.set(key);
            context.set(ctx);
            return fallbackModel;
        }, guardHost::set).withFallbackSource(primary -> new KernelModelRequest("MiniMax-M2.7-highspeed",
            "MiniMax", "sk-fallback", "https://fallback.example.invalid/v1"));

        Model assembled = assembler.assembleFallback(PRIMARY, 42L, 77L);

        assertThat(assembled).isSameAs(fallbackModel);
        assertThat(guardHost.get()).isEqualTo("https://fallback.example.invalid/v1");
        assertThat(registryKey.get()).isEqualTo("minimax:MiniMax-M2.7-highspeed");
        assertThat(context.get().option("userId")).isEqualTo("42");
        assertThat(context.get().option("sessionId")).isEqualTo("77");
    }

    @Test
    @DisplayName("装配：回退端点不过闸 → 异常原样上抛（同缝闸门，不留旁路）")
    void assemblerFallbackRejectsEndpointThroughSameGuard() {
        ProjectAgentModelAssembler assembler = new ProjectAgentModelAssembler((key, ctx) -> mock(Model.class),
            endpoint -> { throw new IllegalArgumentException("fallback endpoint rejected"); })
            .withFallbackSource(primary -> new KernelModelRequest("MiniMax-M2.7-highspeed", "MiniMax",
                "sk-fallback", "http://127.0.0.1:8765/v1"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> assembler.assembleFallback(PRIMARY, 42L, 77L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 内核层：官方 Builder#fallbackModel 真实装配 ====================

    // 原阻断根因：官方无参 getAgentState() 使用 (null, defaultSessionId)。
    // 当前生产 Builder 将 defaultSessionId 绑定原 runId，TemporaryStateStore 将 null/runId
    // 官方身份映射到同一受控槽。这里恢复全装配断言并显式读取两种身份，防止回归。
    @Test
    @DisplayName("内核：buildAgent 把回退模型挂上官方 ModelConfig（同计量包装），主模型不变")
    void kernelAssemblesOfficialFallbackModelOnDelegate() throws Exception {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("MiniMax-M3");
        Model fallback = mock(Model.class);
        when(fallback.getModelName()).thenReturn("MiniMax-M2.7-highspeed");
        ProjectAgentModelAssembler assembler = new ProjectAgentModelAssembler((key, ctx) ->
                "minimax:MiniMax-M2.7-highspeed".equals(key) ? fallback : primary)
            .withFallbackSource(primaryReq -> new KernelModelRequest("MiniMax-M2.7-highspeed", "MiniMax",
                "sk-fallback", "https://fallback.example.invalid/v1"));
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(assembler,
            (p, t, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);

        try (HarnessAgent agent = kernel.buildAgent(spec(), primary, new NoopSink())) {
            assertThat(agent.getAgentState()).isNotNull();
            assertThat(agent.getDelegate().getAgentState(String.valueOf(spec().runId()),
                String.valueOf(spec().runId()))).isNotNull();
            var modelConfig = agent.getDelegate().getModelConfig();
            assertThat(modelConfig).isNotNull();
            assertThat(modelConfig.fallbackModel()).isNotNull()
                .isInstanceOf(ProjectAgentMeteredModel.class);
            assertThat(modelConfig.fallbackModel().getModelName()).isEqualTo("MiniMax-M2.7-highspeed");
            assertThat(modelConfig.maxRetries()).isPositive();
            // 2.0.3 ModelConfig 只携带 maxRetries/fallbackModel；主模型断言走 ReActAgent.getModel()。
            assertThat(agent.getDelegate().getModel().getModelName()).isEqualTo("MiniMax-M3");
        }
    }

    @Test
    @DisplayName("内核：未配置回退源时官方 ModelConfig.fallbackModel 保持 null（既有行为不变）")
    void kernelWithoutFallbackSourceKeepsDelegateFallbackNull() throws Exception {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("MiniMax-M3");
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((key, ctx) -> primary),
            (p, t, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);

        try (HarnessAgent agent = kernel.buildAgent(spec(), primary, new NoopSink())) {
            assertThat(agent.getAgentState()).isNotNull();
            assertThat(agent.getDelegate().getAgentState(String.valueOf(spec().runId()),
                String.valueOf(spec().runId()))).isNotNull();
            var modelConfig = agent.getDelegate().getModelConfig();
            assertThat(modelConfig).isNotNull();
            assertThat(modelConfig.fallbackModel()).isNull();
        }
    }

    @Test
    void kernelRejectsConfiguredFallbackAssemblyFailure() {
        Model primary = mock(Model.class);
        var assembler = new ProjectAgentModelAssembler((key, ctx) -> {
            throw new IllegalArgumentException("configured fallback unavailable");
        }).withFallbackSource(request -> new KernelModelRequest(
            "MiniMax-M2.7-highspeed", "MiniMax", "test-key", "https://example.invalid/v1"));
        var kernel = new AgentScopeProjectAgentKernel(assembler,
            (p, t, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> kernel.buildAgent(spec(), primary, new NoopSink()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("configured fallback unavailable");
    }

    // ==================== 夹具 ====================

    private ProjectAgentModelCatalog catalog() {
        lenient().when(auditLog.append(any(org.ruoyi.ipd.domain.AuditLog.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        AiModelConfigService service = new AiModelConfigService(mapper, auditLog, ENV_KEY);
        return new ProjectAgentModelCatalog(mapper, service);
    }

    private static AiModelConfig entity(long id, String modelName, String configJson, boolean active) {
        return AiModelConfig.builder()
            .id(id).provider("MiniMax").modelName(modelName)
            .endpointUrl("https://example.invalid/v1")
            .apiKeyEncrypted(org.ruoyi.common.encrypt.utils.EncryptUtils.encryptByAes(PLAIN_KEY, ENV_KEY))
            .configJson(configJson).isActive(active).delFlag("0")
            .build();
    }

    private static ProjectAgentRunSpec spec() {
        return new ProjectAgentRunSpec(1001L, 20261002L, "tenant-a", 11L, "C02", "竞品分析",
            List.of(org.ruoyi.ipd.agent.support.AgentTestFixtures.skillCatalog(
                    org.ruoyi.ipd.agent.support.AgentTestFixtures.manifest())
                .load("competitor-analysis-ipd").orElseThrow()),
            List.of(), PRIMARY, Duration.ofSeconds(30));
    }

    /** 装配面测试不需要事件记录；检查点生命周期与既有内核夹具同源（AgentKernelTestLifecycle）。 */
    private static final class NoopSink implements ProjectAgentEventSink {
        private final org.ruoyi.ipd.agent.service.ProjectAgentRunHandle lifecycle =
            org.ruoyi.ipd.agent.support.AgentKernelTestLifecycle.create();
        @Override public void registerTerminalSuccessReceipt(Runnable receipt) { lifecycle.registerTerminalSuccessReceipt(receipt); }
        @Override public void registerTemporaryStateCleanup(Runnable cleanup) { lifecycle.registerTemporaryStateCleanup(cleanup); }
        @Override public void releaseTemporaryState() { lifecycle.releaseTemporaryState(); }
        @Override public void onStep(String kind, Map<String, Object> detail) { }
        @Override public void onToolCall(String toolCallId, String toolName) { }
        @Override public void onToolResult(String toolCallId, String toolName, String state) { }
        @Override public void onSource(Map<String, Object> source) { }
        @Override public void onText(String delta) { }
        @Override public void onArtifact(String artifactId, String title, String contentHash, int version) { }
        @Override public void onError(String errorCode) { }
        @Override public void onComplete() { }
    }
}
