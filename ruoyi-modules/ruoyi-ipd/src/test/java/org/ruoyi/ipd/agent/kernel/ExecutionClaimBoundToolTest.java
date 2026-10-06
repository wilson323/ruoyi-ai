package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.servicebridge.ProjectAgentProductionArtifacts;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 渲染工具交付链的服务端执行声明（Claim）门禁端到端验证：真实
 * {@link ProjectAgentProductionArtifacts} 工厂 + 真实 {@link ProjectAgentExecutionClaims}
 * + {@link ExecutionClaimBoundTool} 包装。这正是在线验收（16039）暴露的失败链路：
 * 无 Claim 直调 deliver 会被 requireAuthorized 拒绝（SecurityException），包装后放行。
 */
@Tag("dev")
class ExecutionClaimBoundToolTest {

    @TempDir Path root;
    final AgentRunStore runs = mock(AgentRunStore.class);
    final ArtifactVersionStore versions = mock(ArtifactVersionStore.class);
    final PersonMapper persons = mock(PersonMapper.class);
    final IpdCopilotAccess access = mock(IpdCopilotAccess.class);
    final ProjectAgentEventSink sink = mock(ProjectAgentEventSink.class);
    final ProjectAgentRunSpec spec = mock(ProjectAgentRunSpec.class);
    final Agent actor = mock(Agent.class);
    final RuntimeContext runtime =
        KernelScopeKey.of("22", "11", ProjectAgentConstants.AGENT_ID, "33").toRuntimeContext();
    final Map<Long, IpdAgentArtifactVersion> rows = new HashMap<>();
    final java.util.List<IpdAgentArtifactVersionEvent> artifactEvents = new java.util.ArrayList<>();
    final List<Map<String, Object>> steps = new CopyOnWriteArrayList<>();

    private static final class IpdAgentArtifactVersionEvent extends org.ruoyi.ipd.agent.domain.IpdAgentRunEvent {
        long seq() { return getSeq() == null ? 0 : getSeq(); }
    }

    @BeforeEach
    void setup() throws Exception {
        root = root.toRealPath();
        when(spec.personId()).thenReturn(11L);
        when(spec.projectId()).thenReturn(22L);
        when(spec.runId()).thenReturn(33L);
        when(spec.tenantId()).thenReturn("test");
        var person = new Person();
        person.setId(11L);
        person.setName("Person");
        person.setPersonType("MARKET_PM");
        person.setGroupId(1L);
        when(persons.selectById(11L)).thenReturn(person);
        when(access.requireVisible(any(), eq(22L))).thenReturn("test");
        var run = new IpdAgentRun();
        run.setId(33L);
        run.setPersonId(11L);
        run.setProjectId(22L);
        run.setTenantId("test");
        run.setStatus("RUNNING");
        when(runs.findRun(33L)).thenReturn(Optional.of(run));
        when(runs.listEvents(eq(33L), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
            .thenAnswer(i -> {
                long after = i.getArgument(1);
                return artifactEvents.stream().filter(e -> e.seq() > after).toList();
            });
        when(versions.findLatestForUpdate(eq("test"), eq(33L), anyString()))
            .thenAnswer(i -> rows.values().stream()
                .filter(r -> r.getArtifactId().equals(i.getArgument(2)))
                .max(Comparator.comparing(IpdAgentArtifactVersion::getVersionNo)));
        when(versions.insert(any())).thenAnswer(i -> {
            IpdAgentArtifactVersion row = i.getArgument(0);
            rows.put(row.getId(), row);
            return true;
        });
        when(versions.findById(any()))
            .thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(sink.executionEpoch()).thenReturn(7L);
        when(sink.withActiveOwnership(any()))
            .thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        doAnswer(i -> {
            org.assertj.core.api.Assertions.assertThat(
                org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var event = new IpdAgentArtifactVersionEvent();
            event.setTenantId("test");
            event.setRunId(33L);
            event.setSeq((long) artifactEvents.size() + 1);
            event.setEventType("ARTIFACT");
            event.setPayload(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(i.getArgument(0)));
            artifactEvents.add(event);
            return null;
        }).when(sink).onArtifactPayload(anyMap());
        runtime.put(io.agentscope.harness.agent.workspace.WorkspacePathNormalizer.class,
            io.agentscope.harness.agent.workspace.WorkspacePathNormalizer.of("/workspace"));
    }

    /** 渲染进程 fake：成功落 page.html（契约见 AnswerMeHtmlRendererTest）。 */
    static final class RenderProcess implements AnswerMeHtmlRenderer.ProcessExecutor {
        final AtomicInteger renders = new AtomicInteger();

        @Override public AnswerMeHtmlRenderer.ProcessOutput run(List<String> command,
                Map<String, String> environment, Path workingDirectory, Duration timeout) {
            if (command.contains("--version")) {
                return new AnswerMeHtmlRenderer.ProcessOutput(0, "v22.22.3\n", "", false);
            }
            renders.incrementAndGet();
            Path html = Path.of(command.get(command.indexOf("-o") + 1));
            try {
                Files.writeString(html, "<!doctype html><html>竞争格局页面</html>", StandardCharsets.UTF_8);
            } catch (IOException io) {
                throw new java.io.UncheckedIOException(io);
            }
            return new AnswerMeHtmlRenderer.ProcessOutput(0,
                "✓ " + html + "\n  sheet · blueprint · 2 panels\n  STE ✓ 0 warnings", "", false);
        }
    }

    private ProjectAgentProductionArtifacts factory() {
        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        return new ProjectAgentProductionArtifacts(runs, versions, persons, access, manager, root);
    }

    private final ProjectAgentArtifactProviderFactory.Provider[] providerHolder =
        new ProjectAgentArtifactProviderFactory.Provider[1];

    private HtmlPageRenderTool renderTool(ProjectAgentProductionArtifacts factory) {
        var provider = factory.create(spec, runtime, sink, (a, c) -> { });
        providerHolder[0] = provider;
        return new HtmlPageRenderTool(
            new AnswerMeHtmlRenderer(
                new AnswerMeHtmlRenderer.Settings(true, "node", Duration.ofSeconds(30), 65_536, 8_000),
                new RenderProcess(), root.resolve("engine-cache")),
            provider.target(), steps::add);
    }

    private static ToolCallParam param(Agent actor, RuntimeContext runtime, Map<String, Object> input) {
        return ToolCallParam.builder()
            .agent(actor)
            .runtimeContext(runtime)
            .input(input)
            .toolUseBlock(new ToolUseBlock("render-call-1", ProjectAgentToolCatalog.HTML_PAGE_RENDER, input))
            .build();
    }

    @Test
    @DisplayName("经执行声明包装：渲染产物交付成功落 DRAFT（16039 在线失败链路的修复验证）")
    void claimBoundRenderDeliversDraftSuccessfully() {
        var factory = factory();
        var bound = new ExecutionClaimBoundTool(renderTool(factory), providerHolder[0].claims());
        Map<String, Object> input = Map.of("draft", "# 竞争格局\n\n## 市场格局", "fileName", "竞争格局页面.html");

        ToolResultBlock result = bound.callAsync(param(actor, runtime, input)).block();

        assertThat(result).isNotNull();
        assertThat(result.getState().name()).isNotEqualTo("ERROR");
        assertThat(result.getOutput().toString())
            .contains("已生成并登记产物草稿", "sha256=");
        assertThat(rows).hasSize(1);
        var row = rows.values().iterator().next();
        assertThat(row.getTitle()).isEqualTo("竞争格局页面.html");
        assertThat(row.getStatus()).isEqualTo("DRAFT");
        assertThat(row.getContent()).contains("<!doctype html>");
        assertThat(steps).singleElement()
            .satisfies(step -> assertThat(step.get("fileName")).isEqualTo("竞争格局页面.html"));
    }

    @Test
    @DisplayName("无执行声明直调 deliver 被拒：报「产物登记失败」文本（线上被吞诊断的根因回归）")
    void withoutClaimDeliveryIsDeniedAsTextDiagnostic() {
        var factory = factory();
        HtmlPageRenderTool bare = renderTool(factory);
        Map<String, Object> input = Map.of("draft", "# 竞争格局\n\n## 市场格局", "fileName", "竞争格局页面.html");

        ToolResultBlock result = bare.callAsync(param(actor, runtime, input)).block();

        assertThat(result.getState().name()).isNotEqualTo("ERROR");
        assertThat(result.getOutput().toString()).contains("产物登记失败");
        assertThat(rows).isEmpty();
        assertThat(steps).isEmpty();
    }

    @Test
    @DisplayName("声明一次性消费：两次调用各自签发；执行结束后同 runtime 不能复用；重名交付明确 conflict")
    void claimsAreSingleUseAndScopedToExecution() {
        var factory = factory();
        var bound = new ExecutionClaimBoundTool(renderTool(factory), providerHolder[0].claims());

        ToolResultBlock first = bound.callAsync(param(actor, runtime,
            Map.of("draft", "# 竞争格局", "fileName", "竞争格局页面.html"))).block();
        assertThat(first.getState().name()).isNotEqualTo("ERROR");

        // 同名重交付（force=false）：产物链明确 conflict，模型可换名或改用 force 语义。
        ToolResultBlock again = bound.callAsync(param(actor, runtime,
            Map.of("draft", "# 竞争格局", "fileName", "竞争格局页面.html"))).block();
        assertThat(again.getState().name()).isNotEqualTo("ERROR");
        assertThat(again.getOutput().toString()).contains("产物登记失败", "Artifact exists");
        assertThat(rows).hasSize(1);

        // 声明只在执行期内有效：执行结束（Claim 已关闭）后，同 runtime 直调同一交付链必被拒。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> providerHolder[0].target().deliver(runtime,
            new io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest(
                null, "<html>x</html>".getBytes(StandardCharsets.UTF_8), "竞争格局页面.html", null, false)))
            .isInstanceOf(SecurityException.class);
    }
}
