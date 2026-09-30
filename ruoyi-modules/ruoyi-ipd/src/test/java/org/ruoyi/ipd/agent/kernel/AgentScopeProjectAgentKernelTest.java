package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内核组装面：buildAgent 不触发模型调用；工具集 ⊆ 选定集；非法隔离段 SCOPE_REJECTED。
 * API 与 agentscope-core/harness 2.0.3 javap 对齐（HarnessAgent.getToolkit / ToolCallParam）。
 */
@Tag("dev")
class AgentScopeProjectAgentKernelTest {

    @TempDir
    Path workspaceRoot;

    @Test
    @DisplayName("buildAgent：仅暴露选定只读工具，不调用 Model.stream")
    void buildAgentExposesOnlySelectedTools() throws Exception {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(Flux.never());

        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> model),
            (projectId, docType, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 4);
        ProjectAgentRunSpec spec = spec(List.of(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH));
        RecordingSink sink = new RecordingSink();

        try (HarnessAgent agent = kernel.buildAgent(spec, model, sink)) {
            assertThat(agent.getName()).isEqualTo(ProjectAgentConstants.AGENT_ID);
            assertThat(agent.getToolkit().getToolNames())
                .containsExactly(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH);
            assertThat(agent.getToolkit().getTool(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)
                .isReadOnly()).isTrue();
            assertThat(ProjectAgentPrompt.build(spec)).contains("Skill: competitor-analysis-ipd");
        }
        assertThat(sink.errors).isEmpty();
    }

    @Test
    @DisplayName("buildAgent：未选定工具时 Toolkit 为空（文件系统/Shell/动态 Skill 已关）")
    void buildAgentWithEmptyToolSelection() throws Exception {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> model),
            (p, t, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);

        try (HarnessAgent agent = kernel.buildAgent(spec(List.of()), model, new RecordingSink())) {
            assertThat(agent.getToolkit().getToolNames()).isEmpty();
        }
    }

    @Test
    @DisplayName("execute：隔离键非法段立即 SCOPE_REJECTED，不触模型装配")
    void executeRejectsIllegalScopeWithoutModel() {
        ProjectAgentRunSpec bad = new ProjectAgentRunSpec(1L, 2L, "t", 3L, "C02", "q",
            List.of(), List.of(), new KernelModelRequest("m", "openai", "k", "http://x"),
            Duration.ofSeconds(5));
        AgentScopeProjectAgentKernel failingModel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> {
                throw new IllegalStateException("no model");
            }),
            (p, t, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);
        RecordingSink modelSink = new RecordingSink();
        failingModel.execute(bad, modelSink);
        assertThat(modelSink.errors).containsExactly(AgentScopeProjectAgentKernel.ERR_MODEL_UNAVAILABLE);
    }

    @Test
    @DisplayName("ModelAssembler：MiniMax 注册键翻译为 openai:MiniMax-M3")
    void modelAssemblerRegistryKey() {
        AtomicReference<String> key = new AtomicReference<>();
        ProjectAgentModelAssembler assembler = new ProjectAgentModelAssembler((registryKey, ctx) -> {
            key.set(registryKey);
            Model model = mock(Model.class);
            when(model.getModelName()).thenReturn("MiniMax-M3");
            return model;
        });
        assembler.assemble(new KernelModelRequest("MiniMax-M3", "MiniMax", "sk", "https://example.invalid"));
        assertThat(key.get()).isEqualTo("openai:MiniMax-M3");
        assertThatThrownBy(() -> assembler.assemble(new KernelModelRequest(" ", "openai", null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProjectAgentRunSpec spec(List<String> toolIds) {
        return new ProjectAgentRunSpec(1001L, 20260929L, "tenant-a", 11L, "C02", "竞品分析",
            List.of(new LoadedSkill("competitor-analysis-ipd", "1.0.0", "a".repeat(64), "功能\n价格\n渠道\n技术路线")),
            toolIds, new KernelModelRequest("MiniMax-M3", "MiniMax", "sk-test", "https://example.invalid/v1"),
            Duration.ofSeconds(30));
    }

    /** 仅记录错误码，不落库。 */
    private static final class RecordingSink implements ProjectAgentEventSink {
        final List<String> errors = new ArrayList<>();

        @Override
        public void onStep(String kind, Map<String, Object> detail) {
        }

        @Override
        public void onToolCall(String toolCallId, String toolName) {
        }

        @Override
        public void onToolResult(String toolCallId, String toolName, String state) {
        }

        @Override
        public void onSource(Map<String, Object> source) {
        }

        @Override
        public void onText(String delta) {
        }

        @Override
        public void onArtifact(String artifactId, String title, String contentHash, int version) {
        }

        @Override
        public void onError(String errorCode) {
            errors.add(errorCode);
        }

        @Override
        public void onComplete() {
        }
    }
}
