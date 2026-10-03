package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实服务与 Spring 事务编排；存储为可回滚资源替身，不代表 MySQL 验收。 */
@Tag("dev")
class ProjectAgentRunVerificationTransactionTest {
    @Test void terminalEventExhaustionRollsBackReverifyStepAndState() {
        Fixture f = new Fixture();
        f.terminalFailures = 3;
        assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        assertThat(f.state).isEqualTo(AgentRunStatus.VERIFYING);
        assertThat(f.events).isEmpty();
        assertThat(f.tx.rollbacks).isEqualTo(1);
        assertThat(f.terminalAttempts).isEqualTo(3);
        assertThat(f.tx.commits).isZero();
    }

    @Test void terminalEventExhaustionRollsBackCancellation() {
        Fixture f = new Fixture();
        f.terminalFailures = 3;
        assertThatThrownBy(() -> f.service.cancel(AgentTestFixtures.ACTOR, 9L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        assertThat(f.state).isEqualTo(AgentRunStatus.VERIFYING);
        assertThat(f.events).isEmpty();
        assertThat(f.tx.rollbacks).isEqualTo(1);
    }

    @Test void demandBindingFailureRollsBackStateAndEvidence() {
        Fixture f = new Fixture();
        f.snapshot = "{\"requirementId\":\"77\"}";
        doThrow(new IllegalStateException("binding unavailable"))
            .when(f.binder).apply(eq(77L), anyString(), isNull());
        assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L))
            .isInstanceOf(IllegalStateException.class);
        assertThat(f.state).isEqualTo(AgentRunStatus.VERIFYING);
        assertThat(f.events).isEmpty();
        assertThat(f.tx.rollbacks).isEqualTo(1);
        assertThat(f.terminalAttempts).isZero();
    }

    @Test void successfulReverifyCommitsOneTerminalAfterStepAndBinding() {
        Fixture f = new Fixture();
        f.snapshot = "{\"requirementId\":\"77\"}";
        f.terminalFailures = 1;
        assertThat(f.service.reverify(AgentTestFixtures.ACTOR, 9L).status()).isEqualTo("SUCCEEDED");
        assertThat(f.events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("STEP", "RUN_FINISHED");
        assertThat(f.tx.commits).isEqualTo(1);
        assertThat(f.tx.rollbacks).isZero();
        verify(f.binder).apply(77L, "# 结论\n完整正文。", null);
        assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        assertThat(f.events).hasSize(2);
    }

    @Test void lockedTerminalRowRejectsStaleVerifyingSnapshotWithoutStep() {
        Fixture f = new Fixture();
        var locked = IpdAgentRun.builder().id(9L).tenantId(AgentTestFixtures.TENANT)
            .projectId(AgentTestFixtures.PROJECT_ID).personId(AgentTestFixtures.ACTOR.id())
            .version(1).status("CANCELLED").build();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(locked);
        }).when(f.runs).lockRunForVerification(9L);
        assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        assertThat(f.events).isEmpty();
        verify(f.artifacts, never()).listByRunIds(any());
        verify(f.runs, never()).transition(any(), any(), any(), any(), any());
    }

    @Test void lockedRowIdentityIsRecheckedBeforeEvidenceWrite() {
        Fixture f = new Fixture();
        var locked = IpdAgentRun.builder().id(9L).tenantId(AgentTestFixtures.TENANT)
            .projectId(AgentTestFixtures.PROJECT_ID).personId(AgentTestFixtures.OTHER_ACTOR.id())
            .version(1).status("VERIFYING").build();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(locked);
        }).when(f.runs).lockRunForVerification(9L);
        assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        assertThat(f.events).isEmpty();
        verify(f.runs, never()).transition(any(), any(), any(), any(), any());
    }

    private static final class Fixture {
        final AgentRunStore runs = mock(AgentRunStore.class);
        final ArtifactVersionStore artifacts = mock(ArtifactVersionStore.class);
        final DemandCatalogBinder binder = mock(DemandCatalogBinder.class);
        final List<IpdAgentRunEvent> events = new ArrayList<>();
        AgentRunStatus state = AgentRunStatus.VERIFYING;
        String snapshot;
        int terminalFailures;
        int terminalAttempts;
        final ResourceTransactionManager tx = new ResourceTransactionManager(this);
        final ProjectAgentRunService service;
        Fixture() {
            var access = mock(IpdCopilotAccess.class);
            when(access.requireVisible(any(), eq(AgentTestFixtures.PROJECT_ID)))
                .thenReturn(AgentTestFixtures.TENANT);
            when(runs.findRun(9L)).thenAnswer(call -> Optional.of(IpdAgentRun.builder().id(9L)
                .tenantId(AgentTestFixtures.TENANT).projectId(AgentTestFixtures.PROJECT_ID)
                .personId(AgentTestFixtures.ACTOR.id()).version(1).status(state.name())
                .configSnapshot(snapshot).build()));
            when(runs.lockRunForVerification(9L)).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return runs.findRun(9L);
            });
            when(runs.maxSeq(9L)).thenAnswer(call -> events.stream()
                .mapToLong(IpdAgentRunEvent::getSeq).max().orElse(0L));
            when(runs.transition(eq(9L), any(), any(), any(), any())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                Set<AgentRunStatus> expected = call.getArgument(1);
                if (!expected.contains(state)) return false;
                state = call.getArgument(2);
                return true;
            });
            when(runs.appendEvent(any())).thenAnswer(call -> {
                var event = (IpdAgentRunEvent) call.getArgument(0);
                if ("RUN_FINISHED".equals(event.getEventType())) {
                    terminalAttempts++;
                    if (terminalFailures-- > 0) return false;
                }
                events.add(event);
                return true;
            });
            when(artifacts.listByRunIds(any())).thenReturn(List.of(IpdAgentArtifactVersion.builder()
                .id(71L).runId(9L).artifactId("doc-9").versionNo(1)
                .content("# 结论\n完整正文。").status(IpdAgentArtifactVersion.STATUS_DRAFT).build()));
            var executor = new ProjectAgentRunExecutor(runs, null, new ObjectMapper(),
                Schedulers.immediate(), () -> 1000L, 1);
            executor.setDemandBinder(binder);
            service = new ProjectAgentRunService(true, access, AgentTestFixtures.planner(), runs,
                artifacts, null, null, null, executor, AgentTestFixtures.MAPPER,
                () -> 1000L, Duration.ofSeconds(60));
            TransactionTemplate transaction = new TransactionTemplate(tx);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            service.setVerificationTransaction(transaction);
        }
    }

    private static final class ResourceTransactionManager extends AbstractPlatformTransactionManager {
        final Fixture fixture;
        AgentRunStatus previous;
        int eventCount;
        int commits;
        int rollbacks;
        ResourceTransactionManager(Fixture fixture) { this.fixture = fixture; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            previous = fixture.state;
            eventCount = fixture.events.size();
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            fixture.state = previous;
            fixture.events.subList(eventCount, fixture.events.size()).clear();
            rollbacks++;
        }
    }
}
