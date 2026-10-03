package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.kernel.ProjectAgentEventSink;
import org.ruoyi.ipd.agent.support.FakeProjectAgentKernel;
import org.ruoyi.ipd.agent.support.RunServiceHarness;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.OTHER_ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.c02;

/**
 * 运行生命周期：详情仅本人可见、事件 afterSeq 增量与 terminal 判定、取消（PENDING 直接收口 /
 * 运行中两段式 / 终态幂等 / 迟到帧丢弃）、内核异常收口。内核为替身（非运行态证据）。
 */
@Tag("dev")
class ProjectAgentRunServiceLifecycleTest {

    private static final String MESSAGE = "竞品分析";

    @Test
    @DisplayName("详情：本人可见且 ID 为字符串、快照可回读；他人/冻结/开关关闭拒绝")
    void getIsOwnerOnly() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        Long runId = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, c02("life-key-0001", MESSAGE)).runId());

        ProjectAgentViews.Run view = h.service.get(ACTOR, runId);
        assertThat(view.runId()).isEqualTo(String.valueOf(runId));
        assertThat(view.projectId()).isEqualTo(String.valueOf(PROJECT_ID));
        assertThat(view.agentId()).isEqualTo("ipd_project_agent");
        assertThat(view.actionCode()).isEqualTo("C02");
        assertThat(view.configSnapshot().capabilityPackCode()).isEqualTo("market-research");
        assertThat(view.configSnapshot().skills()).singleElement()
            .satisfies(s -> assertThat(s.sha256()).hasSize(64));
        assertThat(view.createdAt()).endsWith("Z");

        assertCode(() -> h.service.get(OTHER_ACTOR, runId), ApiV1ErrorCode.NOT_FOUND);
        assertCode(() -> h.service.get(RunServiceHarness.FROZEN, runId), ApiV1ErrorCode.FORBIDDEN);
        assertCode(() -> h.service.get(ACTOR, 999_999L), ApiV1ErrorCode.NOT_FOUND);
        RunServiceHarness off = new RunServiceHarness(false, false, 4);
        assertCode(() -> off.service.get(ACTOR, runId), ApiV1ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("事件：afterSeq 增量不重复；终态事件送达前 terminal=false，送达后 true")
    void eventsAreIncrementalAndTerminalFlagIsExact() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        Long runId = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, c02("life-key-0002", MESSAGE)).runId());
        ProjectAgentEventSink sink = h.kernel.last().sink();

        ProjectAgentViews.Events first = h.service.events(ACTOR, runId, 0L);
        assertThat(first.events()).extracting(ProjectAgentViews.Event::seq).containsExactly(1L, 2L, 3L);
        assertThat(first.nextSeq()).isEqualTo(3L);
        assertThat(first.terminal()).isFalse();
        assertThat(first.events().get(0).payload()).isInstanceOf(Map.class);

        sink.onToolCall("call-1", "project_knowledge_search");
        sink.onSource(Map.of("hits", 2, "retrievalStatus", "SUCCESS", "citationText", "检索资料"));
        sink.onText("结论");
        sink.onComplete();

        ProjectAgentViews.Events rest = h.service.events(ACTOR, runId, first.nextSeq());
        assertThat(rest.events()).extracting(ProjectAgentViews.Event::type)
            .containsExactly("TOOL_CALL", "SOURCE", "TEXT_DELTA", "RUN_FINISHED");
        assertThat(rest.events()).extracting(ProjectAgentViews.Event::seq).containsExactly(4L, 5L, 6L, 7L);
        assertThat(rest.terminal()).isTrue();

        ProjectAgentViews.Events partial = h.service.events(ACTOR, runId, 4L);
        assertThat(partial.events()).hasSize(3);
        ProjectAgentViews.Events drained = h.service.events(ACTOR, runId, rest.nextSeq());
        assertThat(drained.events()).isEmpty();
        assertThat(drained.nextSeq()).isEqualTo(7L);
        assertThat(drained.terminal()).isTrue();

        assertCode(() -> h.service.events(ACTOR, runId, -1L), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> h.service.events(OTHER_ACTOR, runId, 0L), ApiV1ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("取消 PENDING：直接 CANCELLED + 唯一 RUN_FINISHED；随后调度到的启动放弃、不写事件、释放额度")
    void cancelPendingFinishesDirectly() {
        RunServiceHarness h = new RunServiceHarness(true, true, 1);
        Long runId = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, c02("life-key-0003", MESSAGE)).runId());

        ProjectAgentViews.RunStatus cancelled = h.service.cancel(ACTOR, runId);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        h.runDeferred();

        assertThat(h.kernel.executions).as("取消后的启动不得调内核").isEmpty();
        assertThat(h.store.events(runId)).extracting(IpdAgentRunEvent::getEventType).containsExactly("RUN_FINISHED");
        assertThat(h.service.events(ACTOR, runId, 0L).terminal()).isTrue();
        h.service.create(ACTOR, PROJECT_ID, c02("life-key-0004", MESSAGE));
        assertThat(h.store.runCount()).as("额度已释放").isEqualTo(2);
    }

    @Test
    @DisplayName("取消运行中：CANCEL_REQUESTED→CANCELLED，上游被 dispose，迟到帧不落库；重复取消幂等")
    void cancelRunningIsTwoPhaseAndDropsLateFrames() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        Long runId = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, c02("life-key-0005", MESSAGE)).runId());
        FakeProjectAgentKernel.Execution execution = h.kernel.last();
        execution.sink().onText("取消前的输出");

        assertThat(h.service.cancel(ACTOR, runId).status()).isEqualTo("CANCELLED");
        assertThat(execution.disposed()).isTrue();
        execution.sink().onText("迟到文本");
        execution.sink().onComplete();

        assertThat(h.store.events(runId)).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("RUN_STARTED", "STEP", "STEP", "TEXT_DELTA", "RUN_FINISHED");
        assertThat(h.service.cancel(ACTOR, runId).status()).isEqualTo("CANCELLED");
        assertThat(h.store.events(runId)).hasSize(5);
        assertCode(() -> h.service.cancel(OTHER_ACTOR, runId), ApiV1ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("他节点运行：本机无句柄时停在 CANCEL_REQUESTED，不越权代写终态")
    void cancelWithoutLocalHandleStaysRequested() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        Long runId = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, c02("life-key-0006", MESSAGE)).runId());
        RunServiceHarness remote = new RunServiceHarness(true, false, 4);
        remote.store.insertRun(h.store.findRun(runId).orElseThrow());

        assertThat(remote.service.cancel(ACTOR, runId).status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(remote.store.events(runId)).isEmpty();
    }

    @Test
    void detachedDurableAguiPauseCanBeCancelledButOrdinaryWaitCannot() throws Exception {
        for (boolean agui : new boolean[] {false, true}) {
            var source = new RunServiceHarness(true, false, 4);
            Long runId = Long.valueOf(source.service.create(ACTOR, PROJECT_ID,
                c02(agui ? "life-pause-agui" : "life-pause-normal", MESSAGE)).runId());
            var run = source.store.findRun(runId).orElseThrow();
            run.setStatus("WAITING_APPROVAL");
            var remote = new RunServiceHarness(true, false, 4);
            remote.store.insertRun(run);
            var cleanups = new java.util.concurrent.atomic.AtomicInteger();
            remote.executor.setPausedCheckpointCleanup(r -> cleanups.incrementAndGet());
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("kind", "AWAIT_USER");
            if (agui) {
                payload.put("reason", "AGUI_INTERRUPT");
                payload.put("pauseEpoch", 1); payload.put("checkpointVersion", 3);
                payload.put("runId", String.valueOf(runId)); payload.put("threadId", String.valueOf(runId));
                payload.put("ownerPersonId", String.valueOf(ACTOR.id()));
                payload.put("interrupts", Map.of("i", Map.of("id", "i", "toolCallId", "call")));
            }
            remote.store.appendEvent(IpdAgentRunEvent.builder().runId(runId).tenantId(run.getTenantId())
                .seq(1L).eventType("STEP").payload(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(payload)).build());
            assertThat(remote.service.cancel(ACTOR, runId).status())
                .isEqualTo(agui ? "CANCELLED" : "CANCEL_REQUESTED");
            assertThat(remote.store.terminalSeq(runId).isPresent()).isEqualTo(agui);
            assertThat(cleanups.get()).isEqualTo(agui ? 1 : 0);
        }
    }

    @Test
    @DisplayName("内核执行即抛异常：收口 FAILED + 唯一 ERROR(KERNEL_ERROR)，额度释放")
    void kernelFailureIsFinalizedOnce() {
        RunServiceHarness h = new RunServiceHarness(true, false, 1);
        h.kernel.failWith = new IllegalStateException("boom");
        Long runId = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, c02("life-key-0007", MESSAGE)).runId());

        ProjectAgentViews.Run view = h.service.get(ACTOR, runId);
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.errorCode()).isEqualTo("KERNEL_ERROR");
        assertThat(view.finishedAt()).isNotNull();
        assertThat(h.store.events(runId)).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("RUN_STARTED", "STEP", "STEP", "ERROR");
        h.kernel.failWith = null;
        h.service.create(ACTOR, PROJECT_ID, c02("life-key-0008", MESSAGE));
        assertThat(h.store.runCount()).isEqualTo(2);
    }

    private static void assertCode(Runnable call, ApiV1ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(IpdBusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    @Test
    void eventHttpProjectionHidesInternalReceiptWithoutChangingPersistenceOrToolArgs() throws Exception {
        var h = new RunServiceHarness(true, false, 4);
        Long runId = Long.valueOf(h.service.create(ACTOR, PROJECT_ID,
            c02("life-public-event", MESSAGE)).runId());
        String internal = "ipd.server.child.invocation";
        var metadata = Map.of("agentscope.interruptKind", "permission_confirm", "toolName", "write_file",
            "toolInput", Map.of(internal, "ordinary tool argument"), "toolContent", "original raw args",
            "replyId", "reply", internal, "private locator");
        h.executor.handle(runId).orElseThrow().onStep("AWAIT_USER", Map.of("reason", "AGUI_INTERRUPT",
            internal, Map.of("actor", "private receipt"), "interrupts", Map.of("interrupt",
                Map.of("id", "interrupt", "reason", "tool_call", "metadata", metadata))));
        var event = h.service.events(ACTOR, runId, 0L).events().stream()
            .filter(e -> e.type().equals("STEP") && "AWAIT_USER".equals(((Map<?, ?>)e.payload()).get("kind")))
            .findFirst().orElseThrow();
        var payload = (Map<?, ?>)event.payload();
        assertThat(payload.containsKey(internal)).isFalse();
        var pending = (Map<?, ?>)((Map<?, ?>)payload.get("interrupts")).get("interrupt");
        var publicMetadata = (Map<?, ?>)pending.get("metadata");
        assertThat(publicMetadata.containsKey(internal)).isFalse();
        assertThat(publicMetadata.get("agentscope.interruptKind")).isEqualTo("permission_confirm");
        assertThat(((Map<?, ?>)publicMetadata.get("toolInput")).get(internal))
            .isEqualTo("ordinary tool argument");
        assertThat(publicMetadata.get("toolContent")).isEqualTo("original raw args");
        var stored = h.store.listEvents(runId, event.seq() - 1, 1).get(0).getPayload();
        assertThat(stored).contains("private locator", "private receipt");
        assertThat(event.seq()).isEqualTo(h.store.listEvents(runId, event.seq() - 1, 1).get(0).getSeq());
    }

}
