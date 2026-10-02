package org.ruoyi.chat.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import reactor.core.publisher.Flux;
import java.util.function.Function;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;
import io.agentscope.harness.agent.tool.SkillManageConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Shared chat/PoC official capability assembly; never grants IPD Person permissions. */
public final class ChatOfficialCapabilities implements SkillPromotionGate, SkillVisibilityFilter, MiddlewareBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String expectedUser;
    private volatile WorkspaceManager workspace;
    private ChatSafeTranscriptStore safeTranscript;
    private final Map<String, AgentSkill> approved = new ConcurrentHashMap<>();

    /** Null expectedUser is reserved for the standalone PoC fixture; calls still need identity. */
    private ChatOfficialCapabilities(String expectedUser) { this.expectedUser = expectedUser; }

    public static ChatOfficialCapabilities configure(HarnessAgent.Builder builder, Path workspace,
                                                      String expectedUser, String image) {
        return configure(builder, workspace, expectedUser, image, List.of());
    }

    public static ChatOfficialCapabilities configure(HarnessAgent.Builder builder, Path workspace,
            String expectedUser, String image, List<String> knownSecrets) {
        OfficialAgentTraceLogging.install();
        Objects.requireNonNull(workspace);
        if (image == null || image.isBlank()) { throw new IllegalArgumentException("Sandbox image is required"); }
        var capabilities = new ChatOfficialCapabilities(expectedUser);
        var filesystem = new DockerFilesystemSpec().image(image).workspaceRoot("/workspace")
            .network("none")
            .snapshotSpec(new LocalSnapshotSpec(workspace.resolve(".sandbox-snapshots")))
            .additionalRunArgs("--pull=never", "--cap-drop=ALL", "--security-opt=no-new-privileges");
        filesystem.isolationScope(IsolationScope.SESSION);
        capabilities.safeTranscript = new ChatSafeTranscriptStore(knownSecrets);
        builder.filesystem(filesystem).middleware(capabilities)
            .middleware(capabilities.safeTranscript).transcriptStore(capabilities.safeTranscript)
            .skillsEnabled(true)
            .enableSkillManageTool(SkillManageConfig.defaults())
            .enableSkillPromotionGate(capabilities, capabilities)
            .enableSkillCurator(SkillCuratorConfig.defaults())
            .enablePlanMode().enableTaskList().enableMetaTool(true).enablePendingToolRecovery(true)
            .asyncToolTimeout(Duration.ofSeconds(30))
            .enableAgentTracingLog(true)
            .memory(MemoryConfig.builder()
                .flushPrompt("Extract sourced reusable preferences and observations. Never store secrets or raw internal reasoning. "
                    + "Memory and plans are working notes, not permission or business approval. User identity is the authenticated chat user.")
                .consolidationPrompt("Consolidate sourced working notes without secrets or raw internal reasoning. "
                    + "Memory does not approve skills, business operations or permissions. Keep within %d tokens and %d characters.")
                .build());
        return capabilities;
    }

    public void bind(HarnessAgent agent) {
        workspace = Objects.requireNonNull(agent.getWorkspaceManager());
        safeTranscript.bind(workspace);
        // SDK workspace files are model writable. Only explicitly supplied immutable repositories
        // can constitute the reviewed catalog; a forged workspace sidecar is not owner approval.
        for (var repository : agent.getSkillRepositories()) {
            if (repository instanceof WorkspaceSkillRepository) { continue; }
            for (var skill : repository.getAllSkills()) { approved.put(skill.getName(), skill); }
        }
    }

    @Override public List<AgentSkill> filter(List<AgentSkill> all, RuntimeContext context) {
        // SDK visibility exceptions are swallowed; a foreign context must return a closed set.
        if (!owned(context) || all == null) { return List.of(); }
        return all.stream().filter(Objects::nonNull).filter(skill -> {
            var published = approved.get(skill.getName());
            return published != null && Objects.equals(published.getSkillContent(), skill.getSkillContent())
                && Objects.equals(published.getDescription(), skill.getDescription())
                && Objects.equals(published.getMetadataValue("version"), skill.getMetadataValue("version"))
                && Objects.equals(published.getResources(), skill.getResources());
        }).toList();
    }

    @Override public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext context) {
        return Mono.fromCallable(() -> {
            if (!owned(context)) { throw new SecurityException("Chat skill runtime identity mismatch"); }
            if (candidate == null || candidate.name() == null || !candidate.name().matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid skill review identity");
            }
            var manager = Objects.requireNonNull(workspace, "Official chat workspace is not bound");
            var filesystem = Objects.requireNonNull(manager.getFilesystem());
            var drafts = new WorkspaceSkillRepository(filesystem, "skills/_drafts", () -> context);
            if (drafts.getSkill(candidate.name(), context) == null) {
                throw new IllegalStateException("Official skill draft does not exist");
            }
            String draft = drafts.resolveSkillRoot(candidate.name());
            String receipt = draft + "/.owner-review.json";
            String content = JSON.writeValueAsString(Map.of("status", "PENDING_OWNER_APPROVAL",
                "skillName", candidate.name(), "draftPath", draft,
                "reason", "No authenticated owner publication endpoint is available; a model or workspace file cannot approve publication."));
            manager.writeDraftSkillFile(context, receipt, content);
            var readback = filesystem.read(context, receipt, 0, 0);
            if (!readback.isSuccess() || readback.fileData() == null || !content.equals(readback.fileData().content())) {
                throw new IllegalStateException("Skill review receipt cannot be verified");
            }
            return (PromotionDecision) new PromotionDecision.Defer(Duration.ofHours(24),
                "待技能 owner 批准并正式发布；草案：" + draft + "；状态回读：" + receipt);
        });
    }

    @Override public int order() { return Integer.MAX_VALUE; }

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (!owned(context)) { return Flux.error(new SecurityException("Authenticated chat scope required")); }
            return next.apply(input);
        });
    }

    @Override public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (!owned(context)) { return Flux.error(new SecurityException("Authenticated chat scope required")); }
            var catalog = context.get(SkillCatalog.class);
            if (catalog != null) {
                var actual = catalog.all().stream().map(entry -> entry.skill()).toList();
                if (filter(actual, context).size() != actual.size()) {
                    context.put(SkillCatalog.class, SkillCatalog.empty());
                    return Flux.error(new SecurityException("Chat skill catalog has no owner approval evidence"));
                }
            }
            return next.apply(input);
        });
    }

    private boolean owned(RuntimeContext context) {
        return context != null && context.getUserId() != null && !context.getUserId().isBlank()
            && context.getSessionId() != null && !context.getSessionId().isBlank()
            && (expectedUser == null || expectedUser.equals(context.getUserId()));
    }
}
