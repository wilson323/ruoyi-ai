package org.ruoyi.ipd.agent.kernel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.servicebridge.ProjectAgentProductionArtifacts;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** 验证真实生产 Factory 消费者与 Spring 同步回调；数据库存储用确定性替身。 */
@Tag("dev")
class ProjectAgentProductionArtifactsTransactionTest {
    @TempDir Path root;
    final AgentRunStore runs = mock(AgentRunStore.class);
    final ArtifactVersionStore versions = mock(ArtifactVersionStore.class);
    final PersonMapper persons = mock(PersonMapper.class);
    final IpdCopilotAccess access = mock(IpdCopilotAccess.class);
    final ProjectAgentEventSink sink = mock(ProjectAgentEventSink.class);
    final Map<Long, IpdAgentArtifactVersion> rows = new HashMap<>();
    final List<IpdAgentRunEvent> events = new ArrayList<>();
    final ProjectAgentRunSpec spec = mock(ProjectAgentRunSpec.class);
    final Agent actor = mock(Agent.class);
    final RuntimeContext runtime =
            KernelScopeKey.of("22", "11", ProjectAgentConstants.AGENT_ID, "33").toRuntimeContext();
    boolean commitFails;
    boolean requireExecutionTransaction;
    int commits;
    ProjectAgentArtifactProviderFactory.Provider provider;

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
        when(runs.listEvents(eq(33L), anyLong(), anyInt()))
                .thenAnswer(
                        i ->
                                events.stream()
                                        .filter(e -> e.getSeq() > (long) i.getArgument(1))
                                        .toList());
        when(versions.findLatestForUpdate(eq("test"), eq(33L), anyString()))
                .thenAnswer(
                        i ->
                                rows.values().stream()
                                        .filter(r -> r.getArtifactId().equals(i.getArgument(2)))
                                        .max(
                                                Comparator.comparing(
                                                        IpdAgentArtifactVersion::getVersionNo)));
        when(versions.insert(any()))
                .thenAnswer(
                        i -> {
                            IpdAgentArtifactVersion row = i.getArgument(0);
                            rows.put(row.getId(), row);
                            return true;
                        });
        when(versions.findById(any()))
                .thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(sink.executionEpoch()).thenReturn(7L);
        when(sink.withActiveOwnership(any()))
                .thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get());
        doAnswer(
                        i -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            var event = new IpdAgentRunEvent();
                            event.setTenantId("test");
                            event.setRunId(33L);
                            event.setSeq((long) events.size() + 1);
                            event.setEventType("ARTIFACT");
                            event.setPayload(
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                            .writeValueAsString(i.getArgument(0)));
                            events.add(event);
                            return null;
                        })
                .when(sink)
                .onArtifactPayload(anyMap());
        runtime.put(WorkspacePathNormalizer.class, WorkspacePathNormalizer.of("/workspace"));
    }

    private ProjectAgentProductionArtifacts factory() {
        var manager =
                new AbstractPlatformTransactionManager() {
                    final class Tx {
                        boolean active;
                        Map<Long, IpdAgentArtifactVersion> beforeRows;
                        List<IpdAgentRunEvent> beforeEvents;
                    }

                    final Tx current = new Tx();

                    @Override
                    protected Object doGetTransaction() {
                        return current;
                    }

                    @Override
                    protected boolean isExistingTransaction(Object tx) {
                        return current.active;
                    }

                    @Override
                    protected void doBegin(Object tx, TransactionDefinition definition) {
                        current.active = true;
                        current.beforeRows = new HashMap<>(rows);
                        current.beforeEvents = new ArrayList<>(events);
                    }

                    @Override
                    protected void doCommit(DefaultTransactionStatus status) {
                        commits++;
                        if (commitFails)
                            throw new IllegalStateException("forced database commit failure");
                        current.active = false;
                    }

                    @Override
                    protected void doRollback(DefaultTransactionStatus status) {
                        rows.clear();
                        rows.putAll(current.beforeRows);
                        events.clear();
                        events.addAll(current.beforeEvents);
                        current.active = false;
                    }
                };
        manager.setRollbackOnCommitFailure(true);
        return new ProjectAgentProductionArtifacts(runs, versions, persons, access, manager, root);
    }

    private ProjectAgentExecutionClaims.ExecutionScope approved(
            ProjectAgentArtifactProviderFactory.Provider p) {
        var input =
                Map.<String, Object>of(
                        "filePath",
                        "/workspace/report.md",
                        "fileName",
                        "report.md",
                        "force",
                        false);
        return p.claims()
                .openApproved(
                        ToolCallParam.builder()
                                .agent(actor)
                                .runtimeContext(runtime)
                                .input(input)
                                .toolUseBlock(
                                        ToolUseBlock.builder()
                                                .id("delivery-call")
                                                .name("deliver_artifact")
                                                .input(input)
                                                .build())
                                .build());
    }

    private ArtifactDeliveryRequest request() {
        return new ArtifactDeliveryRequest(
                "report.md",
                "完整正文".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "report.md",
                null,
                false);
    }

    @Test
    void originalScopeTransactionOriginAndConsumedClaim() throws Exception {
        var factory = factory();
        provider =
                factory.create(
                        spec,
                        runtime,
                        sink,
                        (a, c) -> {
                            assertThat(a).isSameAs(actor);
                            if (requireExecutionTransaction)
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isTrue();
                        });
        try (var claim = approved(provider)) {
            requireExecutionTransaction = true;
            assertThat(provider.target().deliver(claim.runtime(), request()).successful()).isTrue();
            assertThat(commits).isEqualTo(1);
            assertThat(rows).hasSize(1);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getPayload()).contains("IPD_NATIVE_DELIVERY_V1");
            assertThatThrownBy(() -> provider.target().deliver(claim.runtime(), request()))
                    .isInstanceOf(SecurityException.class);
            var row = rows.values().iterator().next();
            assertThat(
                            factory.forOwnedRun(
                                            new org.ruoyi.ipd.security.IpdActor(
                                                    11L, "Person", "MARKET_PM", 1L),
                                            33L)
                                    .download(
                                            new org.ruoyi.ipd.security.IpdActor(
                                                    11L, "Person", "MARKET_PM", 1L),
                                            row.getId()))
                    .isEqualTo(request().content());
        }
        try (var files = Files.list(root)) {
            assertThat(files.count()).isEqualTo(2);
        }
    }

    @Test
    void commitFailureRollsBackOriginalVersionOriginBytesAndAbortsClaim() throws Exception {
        commitFails = true;
        provider = factory().create(spec, runtime, sink, (a, c) -> {});
        try (var claim = approved(provider)) {
            assertThatThrownBy(() -> provider.target().deliver(claim.runtime(), request()))
                    .hasMessageContaining("forced database commit failure");
            assertThat(rows).isEmpty();
            assertThat(events).isEmpty();
            try (var files = Files.list(root)) {
                assertThat(files.count()).isZero();
            }
            assertThatThrownBy(() -> provider.target().deliver(claim.runtime(), request()))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void rawPersonAndRunIdsCannotReplaceTheFourDimensionalRuntime() {
        assertThatThrownBy(
                        () ->
                                factory()
                                        .create(
                                                spec,
                                                RuntimeContext.builder()
                                                        .userId("11")
                                                        .sessionId("33")
                                                        .build(),
                                                sink,
                                                (a, c) -> {}))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("root identity mismatch");
        assertThat(rows).isEmpty();
        assertThat(events).isEmpty();
    }
}
