package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 事务故障策略测试；资源替身不代替真实数据库故障验收。 */
@Tag("dev")
class ProjectAgentRunFinishTransactionTest {
    @Test
    void terminalEventFailureRollsBackSuccessAndArtifactThenCommitsFailure() {
        Fixture f = new Fixture();
        f.rejectTerminalOnce = true;
        f.handle.onText("正文");
        f.handle.onComplete();
        assertThat(f.state).isEqualTo(AgentRunStatus.FAILED);
        assertThat(f.artifacts).isEmpty();
        assertThat(f.events).extracting(IpdAgentRunEvent::getEventType).containsExactly("TEXT_DELTA", "ERROR");
        assertThat(f.events).extracting(IpdAgentRunEvent::getSeq).containsExactly(1L, 2L);
        assertThat(f.tx.rollbacks).isEqualTo(1);
        assertThat(f.tx.commits).isEqualTo(1);
        assertThat(f.closed).hasValue(1);
        assertThat(f.handle.isClosed()).isTrue();
    }

    @Test
    void successReleasesOnlyAfterCommit() {
        Fixture f = new Fixture();
        f.handle.onText("正文");
        f.handle.onComplete();
        assertThat(f.state).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(f.artifacts).hasSize(1);
        assertThat(f.events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("TEXT_DELTA", "ARTIFACT", "RUN_FINISHED");
        assertThat(f.closed).hasValue(1);
        assertThat(f.tx.commits).isEqualTo(1);
    }

    @Test
    void cancellationCommitsNoArtifact() {
        Fixture f = new Fixture();
        f.state = AgentRunStatus.CANCEL_REQUESTED;
        f.handle.onText("正文");
        f.handle.onComplete();
        assertThat(f.state).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(f.artifacts).isEmpty();
        assertThat(f.events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("TEXT_DELTA", "RUN_FINISHED");
    }

    @Test
    void persistentFailureDoesNotPretendToCloseOrRelease() {
        Fixture f = new Fixture();
        f.rejectAllEvents = true;
        f.handle.onText("正文");
        f.handle.onComplete();
        assertThat(f.state).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(f.events).isEmpty();
        assertThat(f.artifacts).isEmpty();
        assertThat(f.handle.isClosed()).isFalse();
        assertThat(f.closed).hasValue(0);
        assertThat(f.tx.rollbacks).isEqualTo(3);
        // 存储恢复后仍能从原游标和文本继续收口。
        f.rejectAllEvents = false;
        f.handle.onError("KERNEL_ERROR");
        assertThat(f.events).extracting(IpdAgentRunEvent::getSeq).containsExactly(1L, 2L);
        assertThat(f.events.get(0).getPayload()).contains("正文");
        assertThat(f.closed).hasValue(1);
    }

    @Test
    void existingTerminalWithoutEventRemainsUnresolved() {
        Fixture f = new Fixture();
        f.state = AgentRunStatus.SUCCEEDED;
        f.handle.onComplete();
        assertThat(f.handle.isClosed()).isFalse();
        assertThat(f.closed).hasValue(0);
        assertThat(f.events).isEmpty();
    }

    @Test
    void refreshesSequenceFromPersistedEvents() {
        Fixture f = new Fixture();
        f.events.add(IpdAgentRunEvent.builder().seq(5L).eventType("SOURCE").build());
        f.handle.onText("正文");
        f.handle.onComplete();
        assertThat(f.events).extracting(IpdAgentRunEvent::getSeq).containsExactly(5L, 6L, 7L, 8L);
    }

    @Test
    void serializationFailureDoesNotPersistFakeEvent() {
        Fixture f = new Fixture();
        f.rejectSerialization = true;
        f.handle.onText("正文");
        f.handle.onComplete();
        assertThat(f.events).isEmpty();
        assertThat(f.state).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(f.handle.isClosed()).isFalse();
        f.rejectSerialization = false;
        f.handle.onError("KERNEL_ERROR");
        assertThat(f.events).extracting(IpdAgentRunEvent::getSeq).containsExactly(1L, 2L);
        assertThat(f.events.get(0).getPayload()).contains("正文");
    }

    @Test
    void pendingTerminalEventFailureRollsBackPendingStatus() {
        Fixture f = new Fixture();
        f.state = AgentRunStatus.PENDING;
        f.rejectTerminalOnce = true;
        ProjectAgentRunExecutor executor = f.executor();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            executor.finishPending(f.run(), AgentRunStatus.CANCELLED, null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(f.state).isEqualTo(AgentRunStatus.PENDING);
        assertThat(f.events).isEmpty();
        assertThat(executor.finishPending(f.run(), AgentRunStatus.CANCELLED, null)).isTrue();
        assertThat(f.state).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(f.events).extracting(IpdAgentRunEvent::getEventType).containsExactly("RUN_FINISHED");
    }

    @Test
    void startupEventFailureUsesFailureTransactionWithoutCallingKernel() {
        Fixture f = new Fixture();
        f.state = AgentRunStatus.PENDING;
        f.rejectStartedOnce = true;
        ProjectAgentRunExecutor executor = f.executor();
        var spec = mock(org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec.class);
        when(spec.skills()).thenReturn(List.of());
        when(spec.message()).thenReturn("说明当前项目");
        executor.tryReserve();
        executor.start(f.run(), spec);
        assertThat(f.state).isEqualTo(AgentRunStatus.FAILED);
        assertThat(f.events).extracting(IpdAgentRunEvent::getEventType).containsExactly("ERROR");
        assertThat(executor.handle(9L)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(f.kernel);
    }

    @Test
    void preparedInputFailureSettlesInsertedRunAndReturnsOnePermit() {
        Fixture f = new Fixture();
        f.state = AgentRunStatus.PENDING;
        ProjectAgentRunExecutor executor = f.executor();
        assertThat(executor.tryReserve()).isTrue();
        executor.submitPrepared(f.run(), () -> { throw new IllegalStateException("input assembly failed"); });
        assertThat(f.state).isEqualTo(AgentRunStatus.FAILED);
        assertThat(f.events).extracting(IpdAgentRunEvent::getEventType).containsExactly("ERROR");
        assertThat(executor.tryReserve()).isTrue();
        assertThat(executor.tryReserve()).isFalse();
        executor.release();
        org.mockito.Mockito.verifyNoInteractions(f.kernel);
    }

    private static final class Fixture {
        AgentRunStatus state = AgentRunStatus.RUNNING;
        final List<IpdAgentRunEvent> events = new ArrayList<>();
        final List<IpdAgentArtifactVersion> artifacts = new ArrayList<>();
        final AtomicInteger closed = new AtomicInteger();
        boolean rejectTerminalOnce;
        boolean rejectStartedOnce;
        boolean rejectSerialization;
        final AgentRunStore runs = mock(AgentRunStore.class);
        final ArtifactVersionStore versions = mock(ArtifactVersionStore.class);
        final org.ruoyi.ipd.agent.kernel.ProjectAgentKernel kernel = mock(org.ruoyi.ipd.agent.kernel.ProjectAgentKernel.class);
        boolean rejectAllEvents;
        final ResourceTransactionManager tx = new ResourceTransactionManager(this);
        final ProjectAgentRunHandle handle;

        Fixture() {
            when(runs.maxSeq(9L)).thenAnswer(call -> events.stream().mapToLong(IpdAgentRunEvent::getSeq).max().orElse(0L));
            when(runs.terminalSeq(9L)).thenAnswer(call -> events.stream()
                .filter(event -> "RUN_FINISHED".equals(event.getEventType()) || "ERROR".equals(event.getEventType()))
                .map(IpdAgentRunEvent::getSeq).findFirst());
            when(runs.findRun(9L)).thenAnswer(call -> Optional.of(run()));
            when(runs.transition(any(), any(), any(), any(), any())).thenAnswer(call -> {
                Set<AgentRunStatus> expected = call.getArgument(1);
                AgentRunStatus target = call.getArgument(2);
                if (!expected.contains(state) || !state.canTransitTo(target)) {
                    return false;
                }
                state = target;
                return true;
            });
            when(runs.appendEvent(any())).thenAnswer(call -> {
                IpdAgentRunEvent event = call.getArgument(0);
                if (rejectStartedOnce && "RUN_STARTED".equals(event.getEventType())) {
                    rejectStartedOnce = false;
                    return false;
                }
                if (rejectAllEvents) {
                    return false;
                }
                if (rejectTerminalOnce && "RUN_FINISHED".equals(event.getEventType())) {
                    rejectTerminalOnce = false;
                    return false;
                }
                events.add(event);
                return true;
            });
            when(versions.insert(any())).thenAnswer(call -> {
                IpdAgentArtifactVersion row = call.getArgument(0);
                row.setId(71L);
                artifacts.add(row);
                return true;
            });
            handle = new ProjectAgentRunHandle(run(), runs, versions, new ObjectMapper() {
                @Override public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                    if (rejectSerialization) {
                        throw new com.fasterxml.jackson.core.JsonProcessingException("test serialization failure") { };
                    }
                    return super.writeValueAsString(value);
                }
            }, () -> 1000L, () -> {
                assertThat(tx.commits).isPositive();
                closed.incrementAndGet();
            });
            handle.setFinishTransaction(new TransactionTemplate(tx));
        }

        ProjectAgentRunExecutor executor() {
            ProjectAgentRunExecutor executor = new ProjectAgentRunExecutor(runs, versions, kernel,
                new ObjectMapper(), reactor.core.scheduler.Schedulers.immediate(), () -> 1000L, 1);
            executor.setFinishTransaction(new TransactionTemplate(tx));
            return executor;
        }

        IpdAgentRun run() {
            return IpdAgentRun.builder().id(9L).tenantId("tenant").personId(1L)
                .projectId(2L).status(state.name()).build();
        }
    }

    /** 可回滚资源替身：用于直接观察Spring事务提交顺序和故障补偿。 */
    private static final class ResourceTransactionManager extends AbstractPlatformTransactionManager {
        final Fixture fixture;
        AgentRunStatus previous;
        int eventCount;
        int artifactCount;
        int commits;
        int rollbacks;
        ResourceTransactionManager(Fixture fixture) { this.fixture = fixture; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            previous = fixture.state;
            eventCount = fixture.events.size();
            artifactCount = fixture.artifacts.size();
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            fixture.state = previous;
            fixture.events.subList(eventCount, fixture.events.size()).clear();
            fixture.artifacts.subList(artifactCount, fixture.artifacts.size()).clear();
            rollbacks++;
        }
    }
}
