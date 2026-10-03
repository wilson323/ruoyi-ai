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
 * <p><b>失败语义</b>：召回或记录失败向主运行传播，合法空记忆不注入。
 */
final class ProjectAgentLongTermMemoryMiddleware implements MiddlewareBase {

    private final ProjectScopedLongTermMemory longTermMemory;

    ProjectAgentLongTermMemoryMiddleware(ProjectScopedLongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
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

    /** PostCall 等价：正常完成才记录（取消/失败不记），记录完成后主链才完成。 */
    private Mono<Void> recordConversation(Agent agent, RuntimeContext ctx) {
        AgentState state = agent instanceof io.agentscope.core.ReActAgent react
            ? react.getAgentState(ctx) : agent.getAgentState();
        List<Msg> context = state.getContext();
        if (context == null || context.isEmpty()) {
            return Mono.empty();
        }
        return longTermMemory.record(context);
    }
}
