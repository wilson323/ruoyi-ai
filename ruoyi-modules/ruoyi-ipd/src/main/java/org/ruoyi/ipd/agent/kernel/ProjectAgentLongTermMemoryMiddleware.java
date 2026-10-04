package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.memory.LongTermMemoryTools;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 长期记忆 v2 官方形态触发器（{@link MiddlewareBase}）。
 *
 * <p>官方 2.0.3 Harness Builder 将 hook 接到父 ReActAgent；旧版“父 hook 不接线”说明错误。
 * 本实现保留官方 v2 推荐的 MiddlewareBase 单轨，以组合式生命周期等待记忆持久化。
 * call 前召回，正常完成后记录运行时身份的对话；失败和取消不触发记录。
 *
 * <p><b>执行身份（2026-10-03 实测修正）</b>：运行时活状态必须按 onAgent 实参
 * 的 RuntimeContext 取（{@code ReActAgent#getAgentState(ctx)}）——stateCache 按
 * (userId, sessionId) 槽缓存活引用，执行链写入的是 trustedScope 复合槽
 * （{@code p{project}:u{person}} / {@code a{agent}:s{run}}）；预取 (runId, runId)
 * 或官方无参 (null, defaultSessionId) 槽拿到的是另一个空缓存对象。
 *
 * <p><b>失败语义（2026-10-03 run 2106378468009717761 实测后修订）</b>：
 * 召回失败向主运行传播（它决定本轮回答质量）；<b>记录失败不再改写业务终态</b>，
 * 改为写 {@code MEMORY_RECEIPT} 持久回执（状态 / 错误类别 / 可重试）后正常收尾。
 * 合法空记忆不注入。
 *
 * <p><b>回执三态互斥（2026-10-03 补）</b>：{@code NO_RESULT}（本轮没有值得记忆的内容）、
 * {@code WRITTEN}（抽取到并已尝试入库）、{@code WRITE_FAILED}（抽取或入库失败）三者
 * 恰有一个成立，且每个正常完成的运行都会落下其中一个。补第三态之前，抽取到 0 条被贴成
 * {@code WRITTEN}，空对话上下文则连回执都不落——前者让「没记住」冒充「记住了」，
 * 后者让「没抽取」与「抽取失败」都只表现为「查不到记录」。
 */
final class ProjectAgentLongTermMemoryMiddleware implements MiddlewareBase {

    private static final String RECEIPT_WRITTEN = "WRITTEN";
    private static final String RECEIPT_WRITE_FAILED = "WRITE_FAILED";
    /**
     * 无结果：本轮没有值得记忆的内容（空对话 / 转录为空 / 模型判定 NONE）。
     *
     * <p>与 {@link #RECEIPT_WRITTEN}、{@link #RECEIPT_WRITE_FAILED} 互斥，且<b>不是失败</b>：
     * 补这第三态之前，抽取到 0 条被贴上 WRITTEN，事后按 status 统计会把空轮算成写入轮。
     */
    private static final String RECEIPT_NO_RESULT = "NO_RESULT";

    private final ProjectScopedLongTermMemory longTermMemory;
    private final ProjectAgentEventSink sink;

    ProjectAgentLongTermMemoryMiddleware(ProjectScopedLongTermMemory longTermMemory, ProjectAgentEventSink sink) {
        this.longTermMemory = longTermMemory;
        this.sink = java.util.Objects.requireNonNull(sink);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AtomicBoolean suspended = new AtomicBoolean();
            AtomicBoolean rootSucceeded = new AtomicBoolean();
            return withRecall(input).flatMapMany(next)
                .doOnNext(event -> {
                    // 官方暂停也是正常 stream completion；父或子暂停都不能启动新的抽取调用。
                    if (event instanceof RequireUserConfirmEvent || event instanceof RequireExternalExecutionEvent
                        || event instanceof RequestStopEvent || event instanceof ExceedMaxItersEvent) {
                        suspended.set(true);
                    } else if (event instanceof AgentResultEvent result && result.getResult() != null) {
                        GenerateReason reason = result.getResult().getGenerateReason();
                        if (reason != GenerateReason.MODEL_STOP && reason != GenerateReason.STRUCTURED_OUTPUT) {
                            suspended.set(true);
                        } else if (event.getSource() == null || event.getSource().isBlank()) {
                            rootSucceeded.set(true);
                        }
                    }
                })
                .concatWith(Mono.defer(() -> !rootSucceeded.get() || suspended.get() ? Mono.<Void>empty()
                    : recordConversation(agent, ctx)).thenMany(Flux.empty()));
        });
    }

    /** PreCall 等价：召回本人本项目记忆，包 {@code <long_term_memory>} 标签注入消息末尾。 */
    private Mono<AgentInput> withRecall(AgentInput input) {
        List<Msg> msgs = input.msgs();
        if (msgs == null || msgs.isEmpty()) {
            return Mono.just(input);
        }
        int queryIndex = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) != null && msgs.get(i).getRole() == MsgRole.USER) {
                queryIndex = i;
                break;
            }
        }
        if (queryIndex < 0) {
            return Mono.just(input);
        }
        return longTermMemory.retrieve(msgs.get(queryIndex))
            .filter(recall -> !recall.isBlank())
            .map(recall -> {
                List<Msg> enhanced = new ArrayList<>(msgs);
                enhanced.add(Msg.builder().role(MsgRole.USER).name("long_term_memory")
                    .content(TextBlock.builder().text(LongTermMemoryTools.wrap(recall)).build()).build());
                return new AgentInput(enhanced);
            }).defaultIfEmpty(input);
    }

    /**
     * PostCall 等价：正常完成才记录（取消/失败不记），记录完成后主链才完成。
     *
     * <p><b>2026-10-03 run 2106378468009717761 实测修订</b>：旧实现把
     * {@code longTermMemory.record(...)} 直接 {@code concatWith} 到主流尾部，
     * 抽取一旦超时，错误就沿主流冒泡，内核判 STREAM_ERROR、整轮落 FAILED，而此时
     * 答案正文与 {@code TEXT_MESSAGE_END} 早已推送并落库——用户拿到的是完整回答，
     * 系统告诉他「模型输出中断」。这里改为<b>错误不逃逸</b>：失败写 {@code MEMORY_RECEIPT}
     * 持久回执（状态 / 错误类别 / 可重试标记）后正常结束主链。
     * 回执本身写不进去才算真故障，那时才向上抛——不能把回执丢失也一并吞掉。
     */
    private Mono<Void> recordConversation(Agent agent, RuntimeContext ctx) {
        AgentState state = agent instanceof io.agentscope.core.ReActAgent react
            ? react.getAgentState(ctx) : agent.getAgentState();
        List<Msg> context = state.getContext();
        if (context == null || context.isEmpty()) {
            // 抽取根本没启动，但结局同样是「无结果」：不落回执照样是静默缺口——
            // 它与「抽取跑了但失败」都只表现为「查不到任何记忆记录」，事后无法区分。
            // 空上下文还可能意味着读错了 state 槽（见类注释的执行身份一节），更需留痕。
            receipt(RECEIPT_NO_RESULT, null, false, 0, 0);
            return Mono.empty();
        }
        return longTermMemory.record(context)
            .onErrorResume(failure -> {
                ProjectScopedLongTermMemory.RecordOutcome outcome = longTermMemory.lastOutcome();
                Throwable cause = outcome == null ? failure : outcome.failure();
                receipt(RECEIPT_WRITE_FAILED, cause, true, 0, 0);
                return Mono.empty();
            })
            .doOnSuccess(ignored -> {
                ProjectScopedLongTermMemory.RecordOutcome outcome = longTermMemory.lastOutcome();
                if (outcome == null) {
                    return;
                }
                if (outcome.noResult()) {
                    receipt(RECEIPT_NO_RESULT, null, false, outcome.extracted(), outcome.saved());
                } else if (outcome.written()) {
                    receipt(RECEIPT_WRITTEN, null, false, outcome.extracted(), outcome.saved());
                }
            });
    }

    /**
     * 写本次运行的记忆持久回执。只记录类别与计数，不带异常原文、凭据或对话正文。
     *
     * <p>回执写失败必须向上抛：那意味着「连失败都没留下痕迹」，属于真故障，
     * 静默吞掉会把一次性抖动变成永久无痕的数据缺失。
     */
    private void receipt(String status, Throwable failure, boolean retryable, int extracted, int saved) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("status", status);
        payload.put("retryable", retryable);
        payload.put("extracted", extracted);
        payload.put("saved", saved);
        payload.put("errorType", failure == null ? null : failure.getClass().getSimpleName());
        sink.onMemoryReceipt(payload);
    }
}
