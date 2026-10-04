package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import org.ruoyi.ipd.agent.support.InMemoryArtifactVersionStore;
import org.ruoyi.ipd.agent.kernel.ProjectAgentEventSink;
import org.ruoyi.ipd.agent.support.FakeProjectAgentKernel;
import org.ruoyi.ipd.agent.support.RunServiceHarness;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        Long runId = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, new org.ruoyi.ipd.agent.dto.AgentRunCreateReq(
            "market-research", "v1", String.valueOf(AgentTestFixtures.MODEL_ID), List.of("competitor-analysis-ipd"),
            List.of("project_knowledge_search"), null, MESSAGE, "life-key-0002")).runId());
        ProjectAgentEventSink sink = h.kernel.last().sink();

        ProjectAgentViews.Events first = h.service.events(ACTOR, runId, 0L);
        assertThat(first.events()).extracting(ProjectAgentViews.Event::seq).containsExactly(1L, 2L, 3L);
        assertThat(first.nextSeq()).isEqualTo(3L);
        assertThat(first.terminal()).isFalse();
        assertThat(first.events().get(0).payload()).isInstanceOf(Map.class);

        sink.onToolCall("call-1", "project_knowledge_search");
        sink.onTrustedSource(Map.of("hits", 2, "retrievalStatus", "SUCCESS", "citationText", "检索资料"));
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

    @Test
    void failedNewAttemptKeepsPreviousReceiptAndItsOwnIdempotency() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        h.kernel.failWith = new IllegalStateException("failed original");
        Long original = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, c02("failed-original-01", MESSAGE)).runId());
        int originalEvents = h.store.events(original).size();
        h.kernel.failWith = null;
        var base = c02("failed-next-01", MESSAGE);
        var request = new org.ruoyi.ipd.agent.dto.AgentRunCreateReq(base.capabilityPackCode(), base.capabilityPackVersion(),
            base.modelConfigId(), base.skillNames(), base.toolIds(), base.actionCode(), base.message(),
            base.idempotencyKey(), base.productLineId(), base.requirementId(), String.valueOf(original), null, null);
        var next = h.service.create(ACTOR, PROJECT_ID, request);
        assertThat(next.runId()).isNotEqualTo(String.valueOf(original));
        assertThat(h.service.create(ACTOR, PROJECT_ID, request).runId()).isEqualTo(next.runId());
        assertThat(h.service.get(ACTOR, Long.valueOf(next.runId())).configSnapshot().previousRunId()).isEqualTo(String.valueOf(original));
        assertThat(h.service.get(ACTOR, original).status()).isEqualTo("FAILED");
        assertThat(h.store.events(original)).hasSize(originalEvents);
        assertThat(h.store.runCount()).isEqualTo(2);
        assertCode(() -> h.service.create(OTHER_ACTOR, PROJECT_ID, request), ApiV1ErrorCode.NOT_FOUND);
    }

    @Test
    void successfulOrActiveRunCannotBeUsedAsFailedAttemptAssociation() {
        for (boolean completed : new boolean[] {false, true}) {
            RunServiceHarness h = new RunServiceHarness(true, false, 4);
            var plain = new org.ruoyi.ipd.agent.dto.AgentRunCreateReq("market-research", "v1",
                String.valueOf(AgentTestFixtures.MODEL_ID), List.of("competitor-analysis-ipd"),
                List.of("project_knowledge_search"), null, MESSAGE, "successful-old-01");
            Long original = Long.valueOf(h.service.create(ACTOR, PROJECT_ID, plain).runId());
            if (completed) { h.kernel.last().sink().onText("普通回答"); h.kernel.last().sink().onComplete(); }
            var next = new org.ruoyi.ipd.agent.dto.AgentRunCreateReq(plain.capabilityPackCode(), plain.capabilityPackVersion(),
                plain.modelConfigId(), plain.skillNames(), plain.toolIds(), null, MESSAGE, "successful-next-01",
                null, null, String.valueOf(original), null, null);
            assertCode(() -> h.service.create(ACTOR, PROJECT_ID, next), ApiV1ErrorCode.STATE_CONFLICT);
            assertThat(h.store.runCount()).isEqualTo(1);
        }
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

    @Test
    @DisplayName("校验驻留：cancel 直接落 CANCELLED；reverify 修复后收口 SUCCEEDED 且 STEP 先于 RUN_FINISHED；仍缺口幂等驻留")
    void verifyingResidenceSupportsCancelAndReverify() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunService svc = new ProjectAgentRunService(true, h.access, AgentTestFixtures.planner(),
            h.store, artifacts, null, null, null, h.executor, AgentTestFixtures.MAPPER, h.clock::get,
            Duration.ofSeconds(60));
        // 本测试仅隔离复检状态和回写；真实来源/bytes/撤权由 ArtifactDeliveryTest 验证。
        var trustedArtifacts = mock(org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactAccess.class);
        svc.setArtifactAccess(trustedArtifacts);

        // 取消：驻留态不经 CANCEL_REQUESTED，直接落 CANCELLED 并补 RUN_FINISHED。
        IpdAgentRun cancelRun = verifyingRun("key-verify-cancel");
        h.store.insertRun(cancelRun);
        artifacts.insert(artifact(cancelRun, "TODO 待补充 TODO 无标题"));
        assertThat(svc.cancel(ACTOR, cancelRun.getId()).status()).isEqualTo("CANCELLED");
        assertThat(h.store.events(cancelRun.getId()))
            .extracting(IpdAgentRunEvent::getEventType).containsExactly("RUN_FINISHED");

        // 复检通过：STEP（复检证据）先于 RUN_FINISHED（终态事件最后，前端轮询不丢事件）。
        IpdAgentRun passRun = verifyingRun("key-verify-pass");
        h.store.insertRun(passRun);
        artifacts.insert(artifact(passRun, "# 竞品分析\n\n完整正文。"));
        assertThat(svc.reverify(ACTOR, passRun.getId()).status()).isEqualTo("SUCCEEDED");
        verify(trustedArtifacts).requireDocumentContent(org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq(passRun.getId()), org.mockito.ArgumentMatchers.any());
        List<IpdAgentRunEvent> passEvents = h.store.events(passRun.getId());
        assertThat(passEvents).extracting(IpdAgentRunEvent::getEventType).containsExactly("STEP", "STEP", "RUN_FINISHED");
        assertThat(passEvents.get(0).getPayload()).contains("recheck").contains("PASS");

        // 复检仍缺口：保持 VERIFYING（幂等）；非驻留态复检拒绝。
        IpdAgentRun gapRun = verifyingRun("key-verify-gap");
        h.store.insertRun(gapRun);
        artifacts.insert(artifact(gapRun, "TODO 再补 TODO"));
        assertThat(svc.reverify(ACTOR, gapRun.getId()).status()).isEqualTo("VERIFYING");
        assertThat(h.store.events(gapRun.getId()))
            .extracting(IpdAgentRunEvent::getEventType).containsExactly("STEP");
        assertCode(() -> svc.reverify(ACTOR, passRun.getId()), ApiV1ErrorCode.STATE_CONFLICT);
        assertCode(() -> svc.reverify(OTHER_ACTOR, gapRun.getId()), ApiV1ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("复检收口：按冻结快照补需求回写；无快照不触发")
    void reverifySuccessRebindsDemandFromFrozenSnapshot() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        DemandCatalogBinder binder = mock(DemandCatalogBinder.class);
        h.executor.setDemandBinder(binder);
        ProjectAgentRunService svc = new ProjectAgentRunService(true, h.access, AgentTestFixtures.planner(),
            h.store, artifacts, null, null, null, h.executor, AgentTestFixtures.MAPPER, h.clock::get,
            Duration.ofSeconds(60));
        // 本测试仅隔离复检状态和回写；真实来源/bytes/撤权由 ArtifactDeliveryTest 验证。
        var trustedArtifacts = mock(org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactAccess.class);
        svc.setArtifactAccess(trustedArtifacts);

        // 首跑路径的 SUCCEEDED 回调随句柄销毁丢失；复检收口必须按冻结快照补回写。
        IpdAgentRun bound = verifyingRun("key-verify-bind");
        bound.setConfigSnapshot("{\"requirementId\":\"77\"}");
        h.store.insertRun(bound);
        String answer = "# 分拣结论\n产品线：attendance\n完整正文。";
        artifacts.insert(artifact(bound, answer));
        assertThat(svc.reverify(ACTOR, bound.getId()).status()).isEqualTo("SUCCEEDED");
        verify(trustedArtifacts).requireDocumentContent(org.mockito.ArgumentMatchers.eq(ACTOR),
            org.mockito.ArgumentMatchers.eq(bound.getId()), org.mockito.ArgumentMatchers.any());
        verify(binder).apply(77L, answer, null, bound.getTenantId());

        // 无快照/无需求单的复检成功不触发回写。
        IpdAgentRun bare = verifyingRun("key-verify-bare");
        h.store.insertRun(bare);
        artifacts.insert(artifact(bare, "# 竞品分析\n完整正文。"));
        assertThat(svc.reverify(ACTOR, bare.getId()).status()).isEqualTo("SUCCEEDED");
        verifyNoMoreInteractions(binder);
    }

    @Test
    @DisplayName("终态事件写入：seq 冲突按新鲜 maxSeq 重试；重试耗尽明确失败")
    void verifyingFinishRetriesTerminalEventWrite() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        FlakyTerminalEventStore flaky = new FlakyTerminalEventStore(h.store);
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunService svc = new ProjectAgentRunService(true, h.access, AgentTestFixtures.planner(),
            flaky, artifacts, null, null, null, h.executor, AgentTestFixtures.MAPPER, h.clock::get,
            Duration.ofSeconds(60));

        // 终态事件首次写入撞 seq：按新鲜 maxSeq 重试后必须写入成功，终态不悬挂。
        flaky.terminalFailures = 1;
        IpdAgentRun retryRun = verifyingRun("key-verify-retry");
        h.store.insertRun(retryRun);
        artifacts.insert(artifact(retryRun, "# 竞品分析\n完整正文。"));
        assertThat(svc.cancel(ACTOR, retryRun.getId()).status()).isEqualTo("CANCELLED");
        assertThat(h.store.events(retryRun.getId()))
            .extracting(IpdAgentRunEvent::getEventType).containsExactly("RUN_FINISHED");

        // 未装配事务的旧构造只能证明显式失败；事务回滚由专用测试验证。
        flaky.terminalFailures = 3;
        IpdAgentRun exhaustedRun = verifyingRun("key-verify-exhausted");
        h.store.insertRun(exhaustedRun);
        artifacts.insert(artifact(exhaustedRun, "# 竞品分析\n完整正文。"));
        assertCode(() -> svc.cancel(ACTOR, exhaustedRun.getId()), ApiV1ErrorCode.STATE_CONFLICT);
        assertThat(h.store.events(exhaustedRun.getId())).isEmpty();
    }

    private static IpdAgentRun verifyingRun(String idempotencyKey) {
        return IpdAgentRun.builder().tenantId(AgentTestFixtures.TENANT).projectId(PROJECT_ID)
            .personId(ACTOR.id()).agentId("ipd_project_agent")
            .status(AgentRunStatus.VERIFYING.name()).idempotencyKey(idempotencyKey).build();
    }

    private static IpdAgentArtifactVersion artifact(IpdAgentRun run, String content) {
        return IpdAgentArtifactVersion.builder().runId(run.getId()).artifactId("doc-" + run.getId())
            .versionNo(1).content(content).status(IpdAgentArtifactVersion.STATUS_DRAFT)
            .tenantId(AgentTestFixtures.TENANT).build();
    }

    /** 终态事件写入缝：前 N 次模拟 (run_id, seq) 撞唯一键返回 false，其余全部委托。 */
    private static final class FlakyTerminalEventStore implements AgentRunStore {
        private final InMemoryAgentRunStore delegate;
        volatile int terminalFailures;

        FlakyTerminalEventStore(InMemoryAgentRunStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean appendEvent(IpdAgentRunEvent event) {
            if (AgentEventType.valueOf(event.getEventType()).isTerminal() && terminalFailures-- > 0) {
                return false;
            }
            return delegate.appendEvent(event);
        }

        @Override
        public boolean insertRun(IpdAgentRun run) {
            return delegate.insertRun(run);
        }

        @Override
        public Optional<IpdAgentRun> findRun(Long runId) {
            return delegate.findRun(runId);
        }

        @Override
        public Optional<IpdAgentRun> findByIdempotencyKey(String tenantId, Long personId, String idempotencyKey) {
            return delegate.findByIdempotencyKey(tenantId, personId, idempotencyKey);
        }

        @Override
        public boolean transition(Long runId, Set<AgentRunStatus> expected, AgentRunStatus target,
                                  String errorCode, Date at) {
            return delegate.transition(runId, expected, target, errorCode, at);
        }

        @Override
        public List<IpdAgentRunEvent> listEvents(Long runId, long afterSeq, int limit) {
            return delegate.listEvents(runId, afterSeq, limit);
        }

        @Override
        public long maxSeq(Long runId) {
            return delegate.maxSeq(runId);
        }

        @Override
        public Optional<Long> terminalSeq(Long runId) {
            return delegate.terminalSeq(runId);
        }

        @Override
        public List<IpdAgentRun> listOwnRuns(AgentRunStore.OwnRunQuery query) {
            return delegate.listOwnRuns(query);
        }

        @Override
        public List<IpdAgentRun> listInterruptedCandidates(Date createdBefore, int limit) {
            return delegate.listInterruptedCandidates(createdBefore, limit);
        }
    }

}
