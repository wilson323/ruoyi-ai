package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.mapper.ProjectKnowledgeFragmentMapper;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内核组装面：buildAgent 不触发模型调用；选定业务工具与官方基础能力共同装配；非法隔离段 SCOPE_REJECTED。
 * API 与 agentscope-core/harness 2.0.3 javap 对齐（HarnessAgent.getToolkit / ToolCallParam）。
 */
@Tag("dev")
class AgentScopeProjectAgentKernelTest {

    @TempDir
    Path workspaceRoot;

    @Test
    @DisplayName("官方模型输入：不读cwd工程AGENTS或@路径，仍保留项目事实和冻结技能")
    void modelInputExcludesImplicitEngineeringContextButKeepsExplicitBusinessFactsAndSkills() throws Exception {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("context-canary");
        AtomicReference<String> captured = new AtomicReference<>();
        StringBuilder capturedCalls = new StringBuilder();
        when(model.stream(any(), any(), any())).thenAnswer(invocation -> {
            List<io.agentscope.core.message.Msg> messages = invocation.getArgument(0);
            capturedCalls.append(messages.stream().map(io.agentscope.core.message.Msg::getTextContent)
                .collect(java.util.stream.Collectors.joining("\n"))).append('\n');
            captured.set(capturedCalls.toString());
            return Flux.just(ChatResponse.builder().id("context-canary").finishReason("stop")
                .content(List.of(TextBlock.builder().text("完成").build())).build());
        });
        var selected = spec(List.of());
        // 已存在的repo AGENTS是污染来源；该路径不得被当作用户附件展开。
        Path repoRoot = Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.isRegularFile(repoRoot.resolve("AGENTS.md")) && repoRoot.getParent() != null) {
            repoRoot = repoRoot.getParent();
        }
        Path repoAgents = repoRoot.resolve("AGENTS.md");
        assertThat(java.nio.file.Files.readString(repoAgents)).contains("万傲瑞达 V6600");
        Path workspace = ProjectAgentWorkspace.prepare(workspaceRoot, String.valueOf(selected.projectId()),
            String.valueOf(selected.personId()), ProjectAgentConstants.AGENT_ID);
        ProjectAgentRunSpec input = new ProjectAgentRunSpec(System.nanoTime(), selected.projectId(),
            selected.tenantId(), selected.personId(), selected.actionCode(),
            "请处理 @" + repoAgents + " 与 @" + workspace.resolve("AGENTS.md") + " 与 @./AGENTS.md", selected.skills(), List.of(),
            selected.model(), Duration.ofSeconds(30)).withProjectFacts("项目事实_CANARY_明确授权材料");
        var kernel = new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler((key, ctx) -> model),
            (p, t, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);
        CountDownLatch done = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink(done);
        int baseline = activeRequests();
        var execution = kernel.execute(input, sink);
        try {
            assertThat(done.await(40, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.errors).isEmpty();
            assertThat(captured.get()).contains("项目事实_CANARY_明确授权材料",
                "competitor-analysis-ipd", "@" + repoAgents, "@./AGENTS.md")
                .doesNotContain("万傲瑞达 V6600", "并发写单一写入者",
                    "IPD project agent (read-only research).");
        } finally {
            execution.dispose();
            awaitRequestBaseline(baseline);
        }
    }

    @Test
    void continuousModelEventsCannotExtendRunDeadline() throws Exception {
        var modelStopped = new AtomicReference<reactor.core.publisher.SignalType>();
        Model model = mock(Model.class);
        when(model.stream(any(), any(), any())).thenReturn(
            Flux.interval(Duration.ofMillis(20)).map(i -> ChatResponse.builder()
                .id("stream").content(List.of(TextBlock.builder().text("持续输出").build())).build())
                .doFinally(modelStopped::set));
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger texts = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        long started = System.nanoTime();
        var deadline = new AgentScopeProjectAgentKernel.DeadlineMiddleware(Duration.ofMillis(250));
        var context = io.agentscope.core.agent.RuntimeContext.empty();
        var execution = deadline.onModelCall(null, context, null, ignored ->
            model.stream(List.of(), List.of(), null).map(response ->
                (io.agentscope.core.event.AgentEvent) new io.agentscope.core.event.TextBlockDeltaEvent(
                    response.getId(), "continuous", "持续输出")))
            .subscribe(event -> texts.incrementAndGet(), failure -> {
                error.set(failure); stopped.countDown();
            }, stopped::countDown);
        try {
            assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(texts.get()).isPositive();
            assertThat(error.get()).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        } finally {
            execution.dispose();
            assertThat(modelStopped.get()).isEqualTo(reactor.core.publisher.SignalType.CANCEL);
        }
    }

    @Test
    void expiredPreparationCannotResetDeadlineAtAgentEntry() throws Exception {
        var deadline = new AgentScopeProjectAgentKernel.DeadlineMiddleware(Duration.ofMillis(250));
        Thread.sleep(300); // Simulates preparation consuming this SAME budget, not a longer run allowance.
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        deadline.onAgent(null, io.agentscope.core.agent.RuntimeContext.empty(), null, ignored ->
            deadline.onModelCall(null, io.agentscope.core.agent.RuntimeContext.empty(), null, input -> {
                modelCalls.incrementAndGet();
                return Flux.never();
            })).subscribe(event -> { }, failure -> { error.set(failure); done.countDown(); }, done::countDown);
        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(modelCalls.get()).isZero();
    }

    @Test
    void runDeadlineIncludesSandboxPreparationAndCancelsSubscribedModel() throws Exception {
        var modelStopped = new java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.SignalType>();
        var modelSubscribed = new java.util.concurrent.atomic.AtomicBoolean();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(any(), any(), any())).thenReturn(
            Flux.interval(Duration.ofMillis(20)).map(i -> ChatResponse.builder()
                .id("stream").content(List.of(TextBlock.builder().text("持续输出").build())).build())
                .doOnSubscribe(ignored -> modelSubscribed.set(true))
                .doFinally(modelStopped::set));
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((key, ctx) -> model),
            (project, type, query) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger texts = new AtomicInteger();
        RecordingSink sink = new RecordingSink(done) {
            @Override
            public void onText(String delta) { super.onText(delta); texts.incrementAndGet(); }
        };
        ProjectAgentRunSpec run = new ProjectAgentRunSpec(1001L, 20260929L, "tenant-a", 11L,
            "C02", "question", List.of(), List.of(),
            spec(List.of()).model(), Duration.ofMillis(250));
        int baseline = activeRequests();
        var execution = kernel.execute(run, sink);
        try {
                // The 250 ms execution budget is unchanged. The terminal sink follows
                // SDK resource close; Docker close is outside the model execution assertion.
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(sink.errors).containsExactly(AgentScopeProjectAgentKernel.ERR_RUN_TIMEOUT);
        } finally {
            execution.dispose();
            awaitRequestBaseline(baseline);
            // Real Docker setup observed in the acceptance log exceeds this same 250 ms.
            // Cleanup may finish later, but no late model subscription or output is permitted.
            assertThat(modelSubscribed.get()).isFalse();
            assertThat(texts.get()).isZero();
            assertThat(modelStopped.get()).isNull();
        }
    }

    @Test
    void cancellingStreamingRunReleasesNativeRequest() throws Exception {
        var modelStopped = new java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.SignalType>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(any(), any(), any())).thenReturn(
            Flux.interval(Duration.ofMillis(20)).map(i -> ChatResponse.builder()
                .id("stream").content(List.of(TextBlock.builder().text("持续输出").build())).build())
                .doFinally(modelStopped::set));
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((key, ctx) -> model),
            (project, type, query) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);
        CountDownLatch text = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink() {
            @Override public void onText(String delta) { super.onText(delta); text.countDown(); }
        };
        int baseline = activeRequests();
        var execution = kernel.execute(spec(List.of()), sink);
        try {
            assertThat(text.await(15, TimeUnit.SECONDS)).isTrue();
        } finally { execution.dispose(); }
        awaitRequestBaseline(baseline);
        assertThat(modelStopped.get()).isEqualTo(reactor.core.publisher.SignalType.CANCEL);
    }

    @Test
    @DisplayName("buildAgent：选定业务工具和完整官方基础能力装配，不调用 Model.stream")
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
            assertThat(agent.getDelegate().getStateStore())
                .isInstanceOf(ProjectAgentTemporaryStateStore.class);
            // 防退化：长对话压缩必须装配，否则溢出时整轮硬失败。
            assertThat(agent.getCompactionHook()).isNotNull();
            assertThat(agent.getToolkit().getToolNames())
                .contains(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH, "read_file", "write_file", "execute",
                    "memory_search", "memory_get", "memory_save", "session_search", "web_fetch", "web_search",
                    "plan_enter", "plan_write", "plan_exit", "skill_manage");
            assertThat(agent.getToolkit().getTool(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)
                .isReadOnly()).isTrue();
            FrozenProjectAgentSkills nativeSkills = new FrozenProjectAgentSkills(spec.skills());
            assertThat(nativeSkills.getAllSkillNames()).containsExactly("competitor-analysis-ipd");
            assertThat(nativeSkills.getSkill("competitor-analysis-ipd").getSkillContent())
                .isEqualTo(spec.skills().get(0).content());
        }
        assertThat(sink.errors).isEmpty();
    }

    @Test
    @DisplayName("实际注册工具：零命中故障向模型返回ERROR并保留来源状态")
    void registeredToolPreservesZeroHitFailure() throws Exception {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        String failure = ProjectKnowledgeVectorSearch.FAILURE_MARK + "服务暂不可用";
        List<Map<String, Object>> sources = new ArrayList<>();
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((key, ctx) -> model),
            (p, t, q) -> new RetrievalContext(0, failure.length(), failure), workspaceRoot, 2);
        var modelCalls = new AtomicInteger();
        when(model.stream(any(), any(), any())).thenAnswer(invocation -> Flux.just(ChatResponse.builder()
            .id("failure-response").finishReason(modelCalls.get() == 0 ? "tool_calls" : "stop")
            .content(modelCalls.getAndIncrement() == 0
                ? List.of(toolUse("failure-call", Map.of("query", "研发")))
                : List.of(TextBlock.builder().text("检索服务失败，请恢复后重试").build())).build()));
        CountDownLatch done = new CountDownLatch(1);
        var toolState = new AtomicReference<String>();
        RecordingSink nativeSink = new RecordingSink(done) {
            @Override public void onSource(Map<String, Object> source) { sources.add(source); }
            @Override public void onToolResult(String id, String name, String state) {
                if ("failure-call".equals(id)) toolState.set(state);
            }
        };
        int baseline = activeRequests();
        var execution = kernel.execute(spec(List.of(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)), nativeSink);
        try {
            assertThat(done.await(40, TimeUnit.SECONDS)).isTrue();
            assertThat(nativeSink.errors).isEmpty();
            assertThat(toolState.get()).isEqualTo("ERROR");
            assertThat(sources).singleElement().satisfies(source -> {
                assertThat(source.get("retrievalStatus")).isEqualTo("FAILED");
                assertThat(source.get("preview").toString()).contains("服务暂不可用");
            });
        } finally {
            execution.dispose();
            awaitRequestBaseline(baseline);
        }
    }

    @Test
    @DisplayName("buildAgent：未选定业务工具仍保留完整官方基础能力")
    void buildAgentWithEmptyToolSelection() throws Exception {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> model),
            (p, t, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);

        try (HarnessAgent agent = kernel.buildAgent(spec(List.of()), model, new RecordingSink())) {
            assertThat(agent.getToolkit().getToolNames())
                .contains("read_file", "write_file", "execute", "memory_search", "skill_manage", "plan_enter", "web_fetch")
                .doesNotContain(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH);
        }
    }

    @Test
    @DisplayName("execute：隔离键非法段立即 SCOPE_REJECTED，不触模型装配")
    void executeRejectsIllegalScopeWithoutModel() throws Exception {
        ProjectAgentRunSpec bad = new ProjectAgentRunSpec(1L, 2L, "t", 3L, "C02", "q",
            List.of(), List.of(), new KernelModelRequest("m", "openai", "k", "http://x"),
            Duration.ofSeconds(5));
        AgentScopeProjectAgentKernel failingModel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> {
                throw new IllegalStateException("no model");
            }),
            (p, t, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 2);
        RecordingSink modelSink = new RecordingSink();
        int baseline = activeRequests();
        var execution = failingModel.execute(bad, modelSink);
        try {
            assertThat(modelSink.errors).containsExactly(AgentScopeProjectAgentKernel.ERR_MODEL_UNAVAILABLE);
        } finally {
            execution.dispose();
            awaitRequestBaseline(baseline);
        }
    }

    @Test
    @DisplayName("同一次回复里的两次检索会返回结果，不会挂到整轮超时")
    void twoToolCallsReturnInsteadOfHanging() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        List<Integer> toolSchemaSizes = new ArrayList<>();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                modelCalls.incrementAndGet();
                Object tools = invocation.getArgument(1);
                boolean withTools = tools instanceof List<?> list && !list.isEmpty();
                toolSchemaSizes.add(tools instanceof List<?> list ? list.size() : -1);
                if (withTools && searches.get() == 0) {
                    int turn = modelCalls.get();
                    Map<String, Object> first = Map.of("query", "海康威视");
                    Map<String, Object> second = Map.of("query", "海康威视研发投入");
                    return Flux.just(ChatResponse.builder()
                        .id("tool-turn")
                        .finishReason("tool_calls")
                        .content(List.of(
                            toolUse("call-" + turn + "-a", first),
                            toolUse("call-" + turn + "-b", second)))
                        .build());
                }
                return Flux.just(ChatResponse.builder()
                    .id("text-turn")
                    .finishReason("stop")
                    .content(List.of(TextBlock.builder().text("已查完").build()))
                    .build());
            });
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> model),
            (projectId, docType, query) -> {
                searches.incrementAndGet();
                return new RetrievalContext(1, 5, "42.13");
            },
            workspaceRoot, 4);
        CountDownLatch done = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink(done);
        ProjectAgentRunSpec spec = new ProjectAgentRunSpec(System.nanoTime(), 9140005L, "tenant-a", 900103L, "C02",
            "只查海康威视", List.of(), List.of(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH),
            new KernelModelRequest("MiniMax-M3", "MiniMax", "sk-test", "https://example.invalid/v1"),
            Duration.ofSeconds(30));

        int baseline = activeRequests();
        var execution = kernel.execute(spec, sink);
        try {
            assertThat(done.await(40, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.errors).doesNotContain(AgentScopeProjectAgentKernel.ERR_RUN_TIMEOUT);
            assertThat(searches.get())
                .as("errors=%s calls=%s results=%s modelCalls=%s schemas=%s",
                    sink.errors, sink.toolCalls, sink.toolResults, modelCalls.get(), toolSchemaSizes)
                .isEqualTo(2);
            assertThat(sink.toolResults).hasSize(2);
        } finally {
            execution.dispose();
            awaitRequestBaseline(baseline);
        }
    }

    @Test
    @DisplayName("检索抛出链接错误时工具仍返回，不会空转到整轮超时")
    void linkageErrorReturnsInsteadOfHanging() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                Object tools = invocation.getArgument(1);
                boolean withTools = tools instanceof List<?> list && !list.isEmpty();
                if (withTools && searches.get() == 0) {
                    return Flux.just(ChatResponse.builder()
                        .id("tool-turn")
                        .finishReason("tool_calls")
                        .content(List.of(toolUse("call-link", Map.of("query", "海康威视"))))
                        .build());
                }
                return Flux.just(ChatResponse.builder()
                    .id("text-turn")
                    .finishReason("stop")
                    .content(List.of(TextBlock.builder().text("已返回错误").build()))
                    .build());
            });
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> model),
            (projectId, docType, query) -> {
                searches.incrementAndGet();
                throw new NoSuchMethodError("Search.search");
            },
            workspaceRoot, 4);
        CountDownLatch done = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink(done);
        ProjectAgentRunSpec spec = new ProjectAgentRunSpec(System.nanoTime(), 9140005L, "tenant-a", 900103L, "C02",
            "只查海康威视", List.of(), List.of(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH),
            new KernelModelRequest("MiniMax-M3", "MiniMax", "sk-test", "https://example.invalid/v1"),
            Duration.ofSeconds(30));

        int baseline = activeRequests();
        var execution = kernel.execute(spec, sink);
        try {
            assertThat(done.await(40, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.errors).doesNotContain(AgentScopeProjectAgentKernel.ERR_RUN_TIMEOUT);
            assertThat(searches.get()).isEqualTo(1);
            assertThat(sink.toolResults).hasSize(1);
        } finally {
            execution.dispose();
            awaitRequestBaseline(baseline);
        }
    }

    @Test
    @DisplayName("传入具体检索器时，运行人员会进入向量检索")
    void concreteRetrieverPassesRunPerson() throws Exception {
        AtomicReference<Long> seenPerson = new AtomicReference<>();
        ProjectKnowledgeFragmentMapper mapper = mock(ProjectKnowledgeFragmentMapper.class);
        when(mapper.search(anyLong(), anyLong(), any(), anyInt())).thenReturn(List.of());
        ProjectKnowledgeRetriever concrete = new ProjectKnowledgeRetriever(
            (projectId, docType, query) -> new RetrievalContext(0, 0, ""),
            new ProjectKnowledgeFragmentTextSearch(mapper),
            (projectId, personId, query) -> {
                seenPerson.set(personId);
                return new RetrievalContext(1, 8, "【知识库向量｜42.13");
            });
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                Object tools = invocation.getArgument(1);
                boolean withTools = tools instanceof List<?> list && !list.isEmpty();
                if (withTools && seenPerson.get() == null) {
                    return Flux.just(ChatResponse.builder()
                        .id("tool-turn")
                        .finishReason("tool_calls")
                        .content(List.of(toolUse("call-person", Map.of("query", "海康威视"))))
                        .build());
                }
                return Flux.just(ChatResponse.builder()
                    .id("text-turn")
                    .finishReason("stop")
                    .content(List.of(TextBlock.builder().text("已查完").build()))
                    .build());
            });
        AgentScopeProjectAgentKernel kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> model),
            concrete, workspaceRoot, 4);
        CountDownLatch done = new CountDownLatch(1);
        RecordingSink sink = new RecordingSink(done);
        int baseline = activeRequests();
        var execution = kernel.execute(new ProjectAgentRunSpec(System.nanoTime(), 9140005L, "tenant-a", 900103L, "C02",
            "只查海康威视", List.of(), List.of(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH),
            new KernelModelRequest("MiniMax-M3", "MiniMax", "sk-test", "https://example.invalid/v1"),
            Duration.ofSeconds(30)), sink);
        try {
            assertThat(done.await(40, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.errors).doesNotContain(AgentScopeProjectAgentKernel.ERR_RUN_TIMEOUT);
            assertThat(seenPerson.get()).isEqualTo(900103L);
            assertThat(sink.toolResults).hasSize(1);
        } finally {
            execution.dispose();
            awaitRequestBaseline(baseline);
        }
    }

    @Test
    @DisplayName("ModelAssembler：MiniMax 注册键保留专用原生 provider")
    void modelAssemblerRegistryKey() {
        AtomicReference<String> key = new AtomicReference<>();
        ProjectAgentModelAssembler assembler = new ProjectAgentModelAssembler((registryKey, ctx) -> {
            key.set(registryKey);
            Model model = mock(Model.class);
            when(model.getModelName()).thenReturn("MiniMax-M3");
            return model;
        });
        assembler.assemble(new KernelModelRequest("MiniMax-M3", "MiniMax", "sk", "https://example.invalid"));
        assertThat(key.get()).isEqualTo("minimax:MiniMax-M3");
        assertThatThrownBy(() -> assembler.assemble(new KernelModelRequest(" ", "openai", null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static int activeRequests() {
        return GracefulShutdownManager.getInstance().getActiveRequestCount();
    }

    private static void awaitRequestBaseline(int baseline) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (activeRequests() != baseline && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(activeRequests()).as("SDK active requests must return to baseline after disposal")
            .isEqualTo(baseline);
    }

    private static ProjectAgentRunSpec spec(List<String> toolIds) {
        return new ProjectAgentRunSpec(1001L, 20260929L, "tenant-a", 11L, "C02", "竞品分析",
            List.of(org.ruoyi.ipd.agent.support.AgentTestFixtures.skillCatalog(org.ruoyi.ipd.agent.support.AgentTestFixtures.manifest())
                .load("competitor-analysis-ipd").orElseThrow()),
            toolIds, new KernelModelRequest("MiniMax-M3", "MiniMax", "sk-test", "https://example.invalid/v1"),
            Duration.ofSeconds(30));
    }

    /**
     * 构造带原始 JSON 的工具调用。校验读的是 content，只放 Map 会被当成缺 query。
     *
     * @param id 调用编号
     * @param input 参数，必须含 query
     * @return 可被 ToolValidator 接受的工具块
     */
    private static ToolUseBlock toolUse(String id, Map<String, Object> input) {
        String query = String.valueOf(input.get("query"));
        return new ToolUseBlock(id, ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH, input,
            "{\"query\":\"" + query + "\"}", null);
    }

    /** 仅记录错误码和工具结果，不落库。 */
    private static class RecordingSink implements ProjectAgentEventSink {
        private final org.ruoyi.ipd.agent.service.ProjectAgentRunHandle lifecycle = org.ruoyi.ipd.agent.support.AgentKernelTestLifecycle.create();
        @Override public void registerTerminalSuccessReceipt(Runnable receipt) { lifecycle.registerTerminalSuccessReceipt(receipt); }
        @Override public void registerTemporaryStateCleanup(Runnable cleanup) { lifecycle.registerTemporaryStateCleanup(cleanup); }
        @Override public void releaseTemporaryState() { lifecycle.releaseTemporaryState(); }
        final List<String> errors = new ArrayList<>();
        final List<String> toolCalls = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();
        private final CountDownLatch done;

        private RecordingSink() {
            this(null);
        }

        private RecordingSink(CountDownLatch done) {
            this.done = done;
        }

        @Override
        public void onStep(String kind, Map<String, Object> detail) {
        }

        @Override
        public void onToolCall(String toolCallId, String toolName) {
            toolCalls.add(toolName);
        }

        @Override
        public void onToolResult(String toolCallId, String toolName, String state) {
            toolResults.add(toolCallId);
        }

        @Override
        public void onSource(Map<String, Object> source) {
        }

        @Override
        public void onText(String delta) {
            lifecycle.onText(delta);
        }

        @Override public void onFinalText(String text) { lifecycle.onFinalText(text); }

        @Override
        public void onArtifact(String artifactId, String title, String contentHash, int version) {
        }

        @Override
        public void onError(String errorCode) {
            errors.add(errorCode);
            if (done != null) {
                done.countDown();
            }
        }

        @Override
        public void onComplete() {
            lifecycle.onComplete();
            if (done != null) {
                done.countDown();
            }
        }
    }
}
