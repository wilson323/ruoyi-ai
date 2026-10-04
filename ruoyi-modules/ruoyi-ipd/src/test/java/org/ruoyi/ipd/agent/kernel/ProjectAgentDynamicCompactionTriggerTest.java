package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.support.AgentKernelTestLifecycle;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长对话压缩的<b>触发线</b>验收（此前只验过 {@code getCompactionHook() != null}，即"装了钩子"，
 * 从未验过"钩子会响、且在正确的点上响"）。
 *
 * <p>本仓装配面 {@code AgentScopeProjectAgentKernel} 用的是 {@code CompactionConfig.builder().build()}，
 * 即官方默认：{@code triggerTokens == 0} 的动态模式。换算发生在 {@code CompactionMiddleware}
 * 私有的 {@code resolveEffectiveConfig()}：
 *
 * <pre>
 *   contextWindow &gt; 0 且 contextWindow - reserved &gt; 0 → effectiveTrigger = contextWindow - reserved
 *   contextWindow - reserved &lt;= 0                        → clamp 为 max(1, contextWindow / 2)
 *   contextWindow &lt;= 0                                   → FALLBACK_TRIGGER_TOKENS = 160000
 * </pre>
 *
 * <p>因此"160k 触发"只是<b>兜底</b>，不是规则；真实触发线随模型窗口移动。断言打在
 * {@code onReasoning} 的下游消息上——压缩发生时下游会多出 {@link ConversationCompactor#SUMMARY_MSG_NAME}
 * 摘要消息，未压缩则原样透传。该观测点是<b>语义级</b>的，不受 flush/计量等旁路调用干扰。
 *
 * <p>用例成对设计（B 与 D 用同一批消息、仅窗口不同、期望相反），避免"恒绿"或"恒红"的假门禁。
 * Token 量由官方 {@link TokenCounterUtil}（CHARS_PER_TOKEN = 2.5，每消息 +5）自证，不靠估算。
 */
@Tag("dev")
class ProjectAgentDynamicCompactionTriggerTest {

    private static final int RESERVED = 20_000;
    /** 触发线的消息量：16050 token &gt; 10000（窗口 30000 的线）。 */
    private static final int CHARS_OVER_LINE = 4_000;
    /** 线下消息量：6050 token &lt; 10000，但 &gt; 5000（窗口 10000 的 clamp 线）。 */
    private static final int CHARS_UNDER_LINE = 1_500;

    @TempDir
    Path workspaceRoot;

    @Test
    @DisplayName("窗口30000：越线(16050>10000)必须压缩，下游出现官方摘要消息")
    void compactsWhenOverDynamicTrigger() throws Exception {
        var downstream = runOnReasoning(30_000, messages(10, CHARS_OVER_LINE));

        assertThat(summaryCount(downstream.get()))
            .as("窗口30000-预留20000=10000线，16050 token 必须触发压缩")
            .isPositive();
    }

    @Test
    @DisplayName("窗口30000：线下(6050<10000)不得压缩，原样透传")
    void doesNotCompactUnderDynamicTrigger() throws Exception {
        var downstream = runOnReasoning(30_000, messages(10, CHARS_UNDER_LINE));

        assertThat(summaryCount(downstream.get()))
            .as("6050 token 未达 10000 线，不得压缩")
            .isZero();
    }

    @Test
    @DisplayName("模型不报窗口：回退160000兜底，同样的越线消息不再触发")
    void fallsBackTo160kWhenModelReportsNoContextWindow() throws Exception {
        var sameMessages = messages(10, CHARS_OVER_LINE);
        var downstream = runOnReasoning(0, sameMessages);

        assertThat(summaryCount(downstream.get()))
            .as("窗口不可知时兜底 160000，16050 token 远不及线，不得压缩")
            .isZero();
    }

    @Test
    @DisplayName("窗口10000<预留20000：clamp为窗口一半(5000)，同一批线下消息反而触发")
    void clampsWhenReservedExceedsContextWindow() throws Exception {
        // 与 doesNotCompactUnderDynamicTrigger 完全相同的消息量，仅窗口不同 → 期望相反。
        var downstream = runOnReasoning(10_000, messages(10, CHARS_UNDER_LINE));

        assertThat(summaryCount(downstream.get()))
            .as("窗口10000-预留20000<=0，clamp 为 5000；6050 token 越线必须压缩")
            .isPositive();
    }

    @Test
    @DisplayName("触发线换算自证：同一批消息在窗口30000与10000下结论必须相反")
    void sameMessagesFlipVerdictWithContextWindow() throws Exception {
        var messages = messages(10, CHARS_UNDER_LINE);
        int tokens = TokenCounterUtil.calculateToken(messages);
        int wideLine = 30_000 - RESERVED;
        int clampedLine = 10_000 / 2;

        assertThat(tokens)
            .as("仪器自检：消息量必须落在 clamp 线(5000)之上、普通线(10000)之下，否则上面两例不构成对照")
            .isGreaterThan(clampedLine)
            .isLessThan(wideLine);

        assertThat(summaryCount(runOnReasoning(30_000, messages).get())).isZero();
        assertThat(summaryCount(runOnReasoning(10_000, messages).get())).isPositive();
    }

    // ---- 装配与驱动 ----

    /**
     * 走真实装配面：kernel 建 agent → 取它装配的 {@link CompactionMiddleware} → 喂一批消息进
     * {@code onReasoning}，返回下游实际收到的 {@link ReasoningInput}。
     *
     * <p>下游用 {@code Flux.empty()} 短路，不让 stub 模型真的被当对话模型调用；压缩发生时 middleware
     * 仍会先调模型生成摘要，随后才把压缩结果交给下游。
     */
    private AtomicReference<ReasoningInput> runOnReasoning(int contextWindow, List<Msg> messages)
            throws Exception {
        Model model = stubModel(contextWindow);
        var kernel = new AgentScopeProjectAgentKernel(
            new ProjectAgentModelAssembler((registryKey, ctx) -> model),
            (projectId, docType, q) -> new RetrievalContext(0, 0, ""), workspaceRoot, 4);
        var sink = new SilentSink();
        var downstream = new AtomicReference<ReasoningInput>();
        Function<ReasoningInput, Flux<AgentEvent>> next = input -> {
            downstream.set(input);
            return Flux.empty();
        };

        try (HarnessAgent agent = kernel.buildAgent(spec(), model, sink)) {
            CompactionMiddleware middleware = agent.getCompactionHook();
            assertThat(middleware).as("kernel 必须装配官方压缩中间件").isNotNull();

            middleware.onReasoning(
                    agent.getDelegate(),
                    RuntimeContext.empty(),
                    new ReasoningInput(messages, List.of(), null),
                    next)
                .blockLast(Duration.ofSeconds(20));
        }
        assertThat(downstream.get()).as("下游必须被调用一次").isNotNull();
        return downstream;
    }

    /** 官方摘要消息名即压缩发生的语义标记。 */
    private static int summaryCount(ReasoningInput input) {
        return (int) input.messages().stream()
            .filter(msg -> ConversationCompactor.SUMMARY_MSG_NAME.equals(msg.getName()))
            .count();
    }

    private static List<Msg> messages(int count, int charsEach) {
        String body = "压".repeat(charsEach);
        var messages = new ArrayList<Msg>();
        for (int index = 0; index < count; index++) {
            messages.add(Msg.builder().role(MsgRole.USER)
                .textContent(body + " #" + index).build());
        }
        return messages;
    }

    /**
     * 只声明窗口与摘要能力的桩模型；{@code getContextWindowSize()} 是压缩触发线的唯一输入，
     * 故必须由测试精确控制——返回 0 即模拟"模型不报窗口"的兜底分支。
     */
    private static Model stubModel(int contextWindow) {
        return new Model() {
            @Override public String getModelName() { return "compaction-trigger-stub"; }

            @Override public int getContextWindowSize() { return contextWindow; }

            @Override public boolean supportsNativeStructuredOutput() { return true; }

            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                                       GenerateOptions options) {
                return Flux.just(ChatResponse.builder().id("synthetic-summary").finishReason("stop")
                    .content(List.of(TextBlock.builder().text("SYNTHETIC COMPACTED SUMMARY").build()))
                    .build());
            }
        };
    }

    private static ProjectAgentRunSpec spec() {
        return new ProjectAgentRunSpec(1001L, 20261003L, "tenant-a", 11L, "C02", "竞品分析",
            List.of(AgentTestFixtures.skillCatalog(AgentTestFixtures.manifest())
                .load("competitor-analysis-ipd").orElseThrow()),
            List.of(),
            new KernelModelRequest("compaction-trigger-stub", "Stub", "sk-test", "https://example.invalid/v1"),
            Duration.ofSeconds(30));
    }

    /** 不落库、只承接生命周期回调的最小 sink。 */
    private static final class SilentSink implements ProjectAgentEventSink {
        private final org.ruoyi.ipd.agent.service.ProjectAgentRunHandle lifecycle =
            AgentKernelTestLifecycle.create();

        @Override public void registerTerminalSuccessReceipt(Runnable receipt) {
            lifecycle.registerTerminalSuccessReceipt(receipt);
        }

        @Override public void registerTemporaryStateCleanup(Runnable cleanup) {
            lifecycle.registerTemporaryStateCleanup(cleanup);
        }

        @Override public void releaseTemporaryState() { lifecycle.releaseTemporaryState(); }

        @Override public void onStep(String kind, java.util.Map<String, Object> detail) { }

        @Override public void onToolCall(String id, String name) { }

        @Override public void onToolResult(String id, String name, String state) { }

        @Override public void onSource(java.util.Map<String, Object> source) { }

        @Override public void onText(String text) { }

        @Override public void onArtifact(String id, String title, String hash, int version) { }

        @Override public void onError(String error) { }

        @Override public void onComplete() { }
    }
}
