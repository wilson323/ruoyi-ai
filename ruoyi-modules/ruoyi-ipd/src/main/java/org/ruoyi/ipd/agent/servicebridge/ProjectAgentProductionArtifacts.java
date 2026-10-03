package org.ruoyi.ipd.agent.servicebridge;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.ruoyi.ipd.agent.kernel.ProjectAgentArtifactProviderFactory;
import org.ruoyi.ipd.agent.kernel.ProjectAgentEventSink;
import org.ruoyi.ipd.agent.kernel.ProjectAgentExecutionClaims;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 官方交付 SPI 的生产适配；原运行租约、版本表和事件事务仍是权威。 */
public final class ProjectAgentProductionArtifacts implements ProjectAgentArtifactProviderFactory,
        ProjectAgentArtifactAccess.OwnedTargetFactory {
    private final AgentRunStore runs;
    private final ArtifactVersionStore versions;
    private final PersonMapper persons;
    private final IpdCopilotAccess access;
    private final TransactionTemplate transaction;
    private final Path root;

    public ProjectAgentProductionArtifacts(AgentRunStore runs, ArtifactVersionStore versions,
            PersonMapper persons, IpdCopilotAccess access, PlatformTransactionManager manager, Path root) {
        this.runs = Objects.requireNonNull(runs);
        this.versions = Objects.requireNonNull(versions);
        this.persons = Objects.requireNonNull(persons);
        this.access = Objects.requireNonNull(access);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        Path configured = Objects.requireNonNull(root).toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(configured)) throw new SecurityException("Artifact storage cannot be a symlink");
            Files.createDirectories(configured);
            // macOS /tmp 和 /var 是系统别名；目标本身不可为符号链接，存储使用真实路径。
            this.root = configured.toRealPath();
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("Artifact storage unavailable", unavailable);
        }
    }

    @Override
    public Provider create(ProjectAgentRunSpec spec, RuntimeContext trustedRoot, ProjectAgentEventSink sink,
            BiConsumer<Agent, RuntimeContext> requireKnownActor) {
        var expected = org.ruoyi.chat.kernel.KernelScopeKey.of(String.valueOf(spec.projectId()),
            String.valueOf(spec.personId()), org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID,
            String.valueOf(spec.runId()));
        if (!expected.userId().equals(trustedRoot.getUserId())
                || !expected.sessionId().equals(trustedRoot.getSessionId())) {
            throw new SecurityException("Artifact root identity mismatch");
        }
        IpdActor actor = currentActor(spec.personId());
        var binding = binding(actor, spec.runId());
        if (!Objects.equals(binding.projectId(), spec.projectId())
                || !Objects.equals(binding.tenantId(), spec.tenantId())) {
            throw new SecurityException("Artifact run scope mismatch");
        }
        var epoch = new ProjectAgentExecutionClaims.Binding(String.valueOf(spec.runId()),
            trustedRoot.getUserId(), sink.executionEpoch());
        var claims = new ProjectAgentExecutionClaims(epoch, sink::executionEpoch, (executing, runtime) -> {
            requireKnownActor.accept(executing, runtime);
            sink.requireActiveOwnership();
            if (!Objects.equals(spec.tenantId(), access.requireVisible(currentActor(spec.personId()), spec.projectId())))
                throw new SecurityException("Artifact current access mismatch");
        }, (executing, runtime, path) -> {
            var normalizer = Objects.requireNonNull(runtime.get(WorkspacePathNormalizer.class),
                "Executing actor workspace normalizer is required");
            return normalizer.normalize(path);
        });
        var origin = new ProjectAgentArtifactOrigin(runs, sink::onArtifactPayload);
        var delivery = target(binding, new ProjectAgentArtifactDelivery.RunOwnerTransaction() {
            @Override public <T> T owned(java.util.function.Supplier<T> body) {
                return sink.withActiveOwnership(body);
            }
        }, (ignored, runtime) -> claims.requireAuthorized(runtime, epoch), origin);
        return new Provider(claims, (runtime, request) -> sink.withActiveOwnership(() ->
            Objects.requireNonNull(transaction.execute(status -> {
                claims.requireDelivery(runtime, epoch, request);
                var reservation = claims.reserve(runtime, epoch);
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int completion) {
                            if (completion == STATUS_COMMITTED) reservation.commit();
                            else reservation.rollback();
                        }
                    });
                var result = delivery.deliver(runtime, request);
                if (!result.successful()) status.setRollbackOnly();
                return result;
            }), "Artifact transaction returned no result")));

    }

    @Override
    public ProjectAgentArtifactDelivery forOwnedRun(IpdActor actor, Long runId) {
        IpdActor current = currentActor(actor.id());
        var binding = binding(current, runId);
        return target(binding, new ProjectAgentArtifactDelivery.RunOwnerTransaction() {
            @Override public <T> T owned(java.util.function.Supplier<T> body) {
                throw new SecurityException("Read access cannot authorize delivery");
            }
        }, (ignored, runtime) -> { throw new SecurityException("Read access cannot authorize delivery"); },
            new ProjectAgentArtifactOrigin(runs, payload -> {
                throw new SecurityException("Read access cannot issue artifact origin");
            }));
    }

    private ProjectAgentArtifactDelivery target(ProjectAgentArtifactDelivery.Binding binding,
            ProjectAgentArtifactDelivery.RunOwnerTransaction owner,
            ProjectAgentArtifactDelivery.RuntimeAuthority authority, ProjectAgentArtifactOrigin origin) {
        return new ProjectAgentArtifactDelivery(binding, access, runs, versions, transaction, owner, root,
            com.baomidou.mybatisplus.core.toolkit.IdWorker::getId, authority, origin);
    }

    private IpdActor currentActor(Long id) {
        var person = persons.selectById(id);
        if (person == null || !Objects.equals(id, person.getId())) throw new SecurityException("Artifact person unavailable");
        return new IpdActor(person.getId(), person.getName(), person.getPersonType(), person.getGroupId());
    }

    private ProjectAgentArtifactDelivery.Binding binding(IpdActor actor, Long runId) {
        var run = runs.findRun(runId).orElseThrow(() -> new SecurityException("Artifact run unavailable"));
        if (!Objects.equals(actor.id(), run.getPersonId())) throw new SecurityException("Artifact run owner mismatch");
        if (!Objects.equals(run.getTenantId(), access.requireVisible(actor, run.getProjectId())))
            throw new SecurityException("Artifact tenant mismatch");
        var scope = org.ruoyi.chat.kernel.KernelScopeKey.of(String.valueOf(run.getProjectId()),
            String.valueOf(actor.id()), org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID, String.valueOf(runId));
        return new ProjectAgentArtifactDelivery.Binding(actor, run.getProjectId(), run.getTenantId(), runId,
            scope.userId(), scope.sessionId());
    }
}
