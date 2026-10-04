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
    @Test void committedSuccessCleansOnlyAfterCommitAndKeepsArchiveEvidence() {
        Fixture f = new Fixture();
        var cleaned = new java.util.concurrent.atomic.AtomicInteger();
        configureCleanup(f, f.executor, run -> {
            assertThat(f.tx.commits).isGreaterThanOrEqualTo(1);
            assertThat(f.events).anyMatch(e -> "RUN_FINISHED".equals(e.getEventType()));
            cleaned.incrementAndGet();
        });
        f.events.add(ProjectAgentRunEvents.of(9L, AgentTestFixtures.TENANT, AgentTestFixtures.ACTOR.id(),
            1, org.ruoyi.ipd.agent.model.AgentEventType.STEP, "{\"kind\":\"SANDBOX_ARCHIVED\",\"snapshots\":[]}", new java.util.Date()));
        assertThat(f.service.reverify(AgentTestFixtures.ACTOR, 9L).status()).isEqualTo("SUCCEEDED");
        assertThat(cleaned.get()).isEqualTo(1);
        assertThat(f.events).anyMatch(e -> e.getPayload().contains("SANDBOX_ARCHIVED"));
        assertThat(f.events.get(f.events.size()-1).getPayload()).contains("DONE");
        assertThat(f.executor.cleanupCommittedCheckpoint(9L)).isFalse();
        assertThat(cleaned.get()).isEqualTo(1);
    }

    @Test void rollbackNeverDeletesCheckpoint() {
        Fixture f = new Fixture();var cleaned = new java.util.concurrent.atomic.AtomicInteger();
        configureCleanup(f, f.executor, run -> cleaned.incrementAndGet());
        f.terminalFailures = 3;
        assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L)).isInstanceOf(RuntimeException.class);
        assertThat(cleaned.get()).isZero();assertThat(f.events).isEmpty();
    }

    @Test void receiptFailureAfterDeletionLeavesPendingForColdScannerRetry() {
        Fixture f = new Fixture();var cleaned = new java.util.concurrent.atomic.AtomicInteger();
        configureCleanup(f, f.executor, run -> cleaned.incrementAndGet());
        var rejectDone = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            IpdAgentRunEvent event = call.getArgument(0);
            if (rejectDone.get() && event.getPayload().contains("\"DONE\"")) return false;
            f.events.add(event);return true;
        }).when(f.runs).appendEvent(any());
        assertThat(f.service.reverify(AgentTestFixtures.ACTOR, 9L).status()).isEqualTo("SUCCEEDED");
        assertThat(cleaned.get()).isEqualTo(1);
        assertThat(f.events).anyMatch(e -> e.getPayload().contains("\"PENDING\""));
        assertThat(f.events).noneMatch(e -> e.getPayload().contains("\"DONE\""));
        rejectDone.set(false);
        var cold = new ProjectAgentRunExecutor(f.runs, null, AgentTestFixtures.MAPPER, Schedulers.immediate(), () -> 1000L, 1);
        configureCleanup(f, cold, run -> cleaned.incrementAndGet());
        when(f.runs.listRecoveryCandidates(any(),anyInt())).thenReturn(List.of());
        when(f.runs.listCommittedCleanupCandidates(any(),anyInt())).thenAnswer(call ->
            f.events.stream().anyMatch(e -> e.getPayload().contains("\"DONE\"")) ? List.of() : List.of(f.runs.findRun(9L).orElseThrow()));
        var scanner = new ProjectAgentInterruptedRunCloser(f.runs, 1000L);
        scanner.setRecovery(mock(ProjectAgentRunRecovery.class));scanner.setExecutor(cold);
        scanner.recoverOwnedCandidates();scanner.recoverOwnedCandidates();
        assertThat(cleaned.get()).isEqualTo(2);
        assertThat(f.events.stream().filter(e -> e.getPayload().contains("\"DONE\""))).hasSize(1);
        assertThat(f.state).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    @Test void activeLeaseAndVerifyingStateCannotBeCleaned() {
        Fixture f = new Fixture();var cleaned = new java.util.concurrent.atomic.AtomicInteger();
        configureCleanup(f, f.executor, run -> cleaned.incrementAndGet());
        assertThat(f.executor.cleanupCommittedCheckpoint(9L)).isFalse();
        var ownership = mock(ProjectAgentRunOwnership.class);
        when(ownership.acquire(9L)).thenReturn(Optional.empty());f.executor.setOwnership(ownership);
        f.state = AgentRunStatus.SUCCEEDED;
        assertThat(f.executor.cleanupCommittedCheckpoint(9L)).isFalse();assertThat(cleaned.get()).isZero();
    }

    private static void configureCleanup(Fixture f, ProjectAgentRunExecutor executor,
            java.util.function.Consumer<IpdAgentRun> cleanup) {
        var ownership = mock(ProjectAgentRunOwnership.class);
        when(ownership.acquire(9L)).thenAnswer(call -> {
            var lease = mock(ProjectAgentRunOwnership.Lease.class);when(lease.held()).thenReturn(true);return Optional.of(lease);
        });
        executor.setOwnership(ownership);executor.setFinishTransaction(new TransactionTemplate(f.tx));
        executor.setPausedCheckpointCleanup(cleanup);
    }
    @Test void skillReviewUsesLockedOwnerAndTransactionWithoutChangingTerminalRun() {
        Fixture f = new Fixture();
        f.state = AgentRunStatus.CANCELLED;
        var reviews = mock(ProjectAgentSkillReviewService.class);
        f.service.setSkillReviews(reviews);
        var req = new org.ruoyi.ipd.agent.dto.AgentSkillReviewReq(true, "b".repeat(64), "已审核");
        var result = new ProjectAgentSkillReviewService.SkillReview("5", "my-method", req.sha256(),
            "PUBLISHED", List.of(), "PASS", req.comment());
        when(reviews.review(any(), anyList(), eq(5L), eq(true), eq(req.sha256()), eq(req.comment()), any()))
            .thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                ProjectAgentSkillReviewService.ReviewScope scope = call.getArgument(0);
                assertThat(scope.personId()).isEqualTo(AgentTestFixtures.ACTOR.id());
                assertThat(scope.projectId()).isEqualTo(AgentTestFixtures.PROJECT_ID);
                java.util.function.Consumer<java.util.Map<String, Object>> append = call.getArgument(6);
                append.accept(java.util.Map.of("kind", ProjectAgentSkillReviewService.PUBLICATION,
                    "candidateSeq", "5", "status", "PUBLISHED"));
                return result;
            });
        assertThat(f.service.reviewSkill(AgentTestFixtures.ACTOR, 9L, 5L, req)).isSameAs(result);
        assertThat(f.state).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(f.events).extracting(IpdAgentRunEvent::getEventType).containsExactly("STEP");
        assertThat(f.tx.commits).isEqualTo(1);
        verify(f.runs, never()).transition(any(), any(), any(), any(), any());
    }

    @Test void failedSkillApprovalAuditCannotCommitApproval() {
        Fixture f = new Fixture();
        var reviews = mock(ProjectAgentSkillReviewService.class);
        f.service.setSkillReviews(reviews);
        doReturn(false).when(f.runs).appendEvent(any());
        when(reviews.review(any(), anyList(), anyLong(), anyBoolean(), anyString(), anyString(), any()))
            .thenAnswer(call -> {
                java.util.function.Consumer<java.util.Map<String, Object>> append = call.getArgument(6);
                append.accept(java.util.Map.of("kind", ProjectAgentSkillReviewService.DECISION));
                throw new AssertionError("failed audit must stop publication");
            });
        assertThatThrownBy(() -> f.service.reviewSkill(AgentTestFixtures.ACTOR, 9L, 5L,
            new org.ruoyi.ipd.agent.dto.AgentSkillReviewReq(true, "b".repeat(64), "同意")))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        assertThat(f.events).isEmpty();
        assertThat(f.tx.commits).isZero();
        assertThat(f.tx.rollbacks).isEqualTo(1);
    }

    @Test void changedLockedOwnerCannotReviewAnotherUsersSkill() {
        Fixture f = new Fixture();
        var reviews = mock(ProjectAgentSkillReviewService.class);
        f.service.setSkillReviews(reviews);
        var other = IpdAgentRun.builder().id(9L).tenantId(AgentTestFixtures.TENANT)
            .projectId(AgentTestFixtures.PROJECT_ID).personId(AgentTestFixtures.OTHER_ACTOR.id())
            .version(1).status("SUCCEEDED").build();
        doReturn(Optional.of(other)).when(f.runs).lockRunForVerification(9L);
        assertThatThrownBy(() -> f.service.reviewSkill(AgentTestFixtures.ACTOR, 9L, 5L,
            new org.ruoyi.ipd.agent.dto.AgentSkillReviewReq(true, "b".repeat(64), "同意")))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        verifyNoInteractions(reviews);
        assertThat(f.events).isEmpty();
    }

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
            .when(f.binder).apply(eq(77L), anyString(), isNull(), eq(AgentTestFixtures.TENANT));
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
            .containsExactly("STEP", "STEP", "RUN_FINISHED");
        assertThat(f.tx.commits).isEqualTo(1);
        assertThat(f.tx.rollbacks).isZero();
        verify(f.binder).apply(77L, "# 结论\n完整正文。", null, AgentTestFixtures.TENANT);
        assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        assertThat(f.events).hasSize(3);
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

    @Test void unknownOutputContractCannotFallBackToLegacyArtifact() {
        Fixture f = new Fixture(); f.snapshot = "{\"outputContractVersion\":99}";
        assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L))
            .isInstanceOf(IllegalStateException.class).hasMessage("Unsupported output contract");
        verify(f.artifacts, never()).listByRunIds(any());
        verifyNoInteractions(f.artifactAccess);
        assertThat(f.state).isEqualTo(AgentRunStatus.VERIFYING);
        assertThat(f.events).isEmpty();
        assertThat(f.tx.rollbacks).isEqualTo(1);
    }

    @Test void coldReverifyChecksEveryTrustedDocumentWithoutCreatingVersions() {
        Fixture f = new Fixture(); f.documents("# First\n正文", "# Second\n正文");
        assertThat(f.service.reverify(AgentTestFixtures.ACTOR, 9L).status()).isEqualTo("SUCCEEDED");
        verify(f.artifactAccess).requireDocumentContent(eq(AgentTestFixtures.ACTOR), eq(9L), argThat(row -> row.getId().equals(71L)));
        verify(f.artifactAccess).requireDocumentContent(eq(AgentTestFixtures.ACTOR), eq(9L), argThat(row -> row.getId().equals(72L)));
        verify(f.artifacts, never()).insert(any());
    }

    @Test void oneInvalidDocumentKeepsWholeRunVerifying() {
        Fixture f = new Fixture(); f.documents("# First\n正文", "缺少标题");
        assertThat(f.service.reverify(AgentTestFixtures.ACTOR, 9L).status()).isEqualTo("VERIFYING");
        assertThat(f.events.stream().filter(e -> "RUN_FINISHED".equals(e.getEventType()))).isEmpty();
    }

    @Test void deniedTamperedOrMissingDocumentRollsBackWithoutSuccess() {
        for (RuntimeException rejection : List.of(new SecurityException("revoked"), new SecurityException("tampered"), new IllegalStateException("missing bytes"))) {
            Fixture f = new Fixture(); f.documents("# First\n正文", "# Second\n正文");
            doThrow(rejection).when(f.artifactAccess).requireDocumentContent(eq(AgentTestFixtures.ACTOR), eq(9L), argThat(row -> row.getId().equals(72L)));
            assertThatThrownBy(() -> f.service.reverify(AgentTestFixtures.ACTOR, 9L)).isSameAs(rejection);
            assertThat(f.state).isEqualTo(AgentRunStatus.VERIFYING);
            assertThat(f.events).hasSize(2);
            assertThat(f.tx.rollbacks).isEqualTo(1);
        }
    }

    private static final class Fixture {
        final AgentRunStore runs = mock(AgentRunStore.class);
        final ArtifactVersionStore artifacts = mock(ArtifactVersionStore.class);
        final DemandCatalogBinder binder = mock(DemandCatalogBinder.class);
        final org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactAccess artifactAccess = mock(org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactAccess.class);
        final List<IpdAgentRunEvent> events = new ArrayList<>();
        AgentRunStatus state = AgentRunStatus.VERIFYING;
        String snapshot;
        int terminalFailures;
        int terminalAttempts;
        final ResourceTransactionManager tx = new ResourceTransactionManager(this);
        final ProjectAgentRunService service;
        final ProjectAgentRunExecutor executor;
        void documents(String first, String second) {
            snapshot = "{\"outputContractVersion\":1}";
            for (int i=0; i<2; i++) {
                long id=71L+i; String body=i==0?first:second;
                var row=IpdAgentArtifactVersion.builder().id(id).runId(9L).tenantId(AgentTestFixtures.TENANT)
                    .artifactId("doc-"+id).versionNo(1).content(body)
                    .contentSha256(org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex(body)).build();
                when(artifacts.findById(id)).thenReturn(Optional.of(row));
                events.add(ProjectAgentRunEvents.of(9L, AgentTestFixtures.TENANT, AgentTestFixtures.ACTOR.id(), i+1L,
                    org.ruoyi.ipd.agent.model.AgentEventType.STEP,
                    "{\"kind\":\"OUTPUT_TYPE\",\"outputKind\":\"DOCUMENT\",\"documentVersionId\":\""+id+"\",\"contentHash\":\""+row.getContentSha256()+"\",\"outputContractVersion\":1}",new java.util.Date()));
            }
        }
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
            when(runs.listEvents(eq(9L), anyLong(), anyInt())).thenAnswer(call -> events.stream()
                .filter(event -> event.getSeq() > (long) call.getArgument(1)).limit((int) call.getArgument(2)).toList());
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
            executor = new ProjectAgentRunExecutor(runs, null, new ObjectMapper(),
                Schedulers.immediate(), () -> 1000L, 1);
            executor.setDemandBinder(binder);
            service = new ProjectAgentRunService(true, access, AgentTestFixtures.planner(), runs,
                artifacts, null, null, null, executor, AgentTestFixtures.MAPPER,
                () -> 1000L, Duration.ofSeconds(60));
            TransactionTemplate transaction = new TransactionTemplate(tx);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            service.setVerificationTransaction(transaction);
            service.setArtifactAccess(artifactAccess);
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
