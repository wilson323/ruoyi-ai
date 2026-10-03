package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.ruoyi.chat.kernel.KernelScopeKey;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.kernel.ProjectAgentSkillBundle;
import org.ruoyi.ipd.agent.kernel.ProjectAgentSkillPublisher;
import java.nio.file.Path;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import io.agentscope.core.skill.util.SkillUtil;
import java.util.*;
import java.util.function.Consumer;

/** All durable candidate, human decision and publication facts live in the original run STEP. */
public final class ProjectAgentSkillReviewService {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String CANDIDATE = "SKILL_REVIEW_CANDIDATE";
    public static final String DECISION = "SKILL_REVIEW_DECISION";
    public static final String PUBLICATION = "SKILL_REVIEW_PUBLICATION";
    public record ReviewScope(String tenantId, Long runId, Long projectId, Long personId) { }
    public record SkillReview(String candidateSeq, String skillName, String sha256, String status,
                              List<ProjectAgentSkillBundle.File> files, String scanSummary, String reviewComment) { }
    private final AgentRunStore store;
    private final Path publicationWorkspaceRoot;
    public ProjectAgentSkillReviewService(AgentRunStore store, Path publicationWorkspaceRoot, CapabilityManifest manifest) {
        this.store = Objects.requireNonNull(store);
        try {
            java.nio.file.Files.createDirectories(Objects.requireNonNull(publicationWorkspaceRoot));
            this.publicationWorkspaceRoot = publicationWorkspaceRoot.toRealPath();
        } catch (java.io.IOException error) { throw new IllegalStateException("Skill publication root unavailable"); }
        Objects.requireNonNull(manifest);
    }
    private final ProjectAgentSkillPublisher publisher = new ProjectAgentSkillPublisher();

    public List<SkillReview> list(List<IpdAgentRunEvent> events) {
        Map<String, SkillReview> reviews = new LinkedHashMap<>();
        Map<String, String> owners = new HashMap<>();
        Map<String, String> projects = new HashMap<>();
        Set<String> approvedCandidates = new HashSet<>();
        events.stream().sorted(Comparator.comparing(IpdAgentRunEvent::getSeq)).forEach(event -> {
            if (!"STEP".equals(event.getEventType())) return;
            var value = parse(event);
            String kind = value.path("kind").asText();
            if (CANDIDATE.equals(kind)) {
                var bundle = bundle(value.get("bundle"));
                owners.put(String.valueOf(event.getSeq()), value.path("personId").asText());
                projects.put(String.valueOf(event.getSeq()), value.path("projectId").asText());
                reviews.put(String.valueOf(event.getSeq()), new SkillReview(String.valueOf(event.getSeq()),
                    bundle.skillName(), bundle.sha256(), "PENDING", bundle.files(), value.path("scanSummary").asText(), null));
            } else if (DECISION.equals(kind) || PUBLICATION.equals(kind)) {
                String seq = value.path("candidateSeq").asText(); SkillReview current = reviews.get(seq);
                if (current == null || !current.sha256().equals(value.path("sha256").asText()))
                    throw new IllegalStateException("Skill review event does not match candidate");
                String status = value.path("status").asText();
                if (!Objects.equals(owners.get(seq), value.path("personId").asText())
                    || !Objects.equals(projects.get(seq), value.path("projectId").asText()))
                    throw new SecurityException("Skill review decision scope mismatch");
                if ("REJECTED".equals(current.status()) || "PUBLISHED".equals(current.status()))
                    throw new IllegalStateException("Skill review is terminal");
                if (DECISION.equals(kind)) {
                    var approved = value.get("approved");
                    if (approved == null || !approved.isBoolean()
                        || !(approved.asBoolean() ? "PENDING".equals(status) : "REJECTED".equals(status)))
                        throw new IllegalStateException("Invalid skill review decision");
                    if (approved.asBoolean()) approvedCandidates.add(seq); else approvedCandidates.remove(seq);
                } else {
                    if (!approvedCandidates.remove(seq) || !Set.of("PUBLISHED", "PUBLISH_FAILED").contains(status))
                        throw new IllegalStateException("Skill publication has no matching user approval");
                }
                reviews.put(seq, new SkillReview(seq, current.skillName(), current.sha256(), status,
                    current.files(), value.path("scanSummary").asText(current.scanSummary()),
                    value.path("reviewComment").asText(current.reviewComment())));
            }
        });
        return List.copyOf(reviews.values());
    }

    /** Caller must hold the original run row lock, check owner+tenant+project ACL, and append transactionally. */
    public SkillReview review(ReviewScope scope, List<IpdAgentRunEvent> events, long candidateSeq,
                             boolean approved, String sha256, String comment, Consumer<Map<String, Object>> append) {
        for (var event : events) if (!Objects.equals(event.getRunId(), scope.runId())
            || !Objects.equals(event.getTenantId(), scope.tenantId())) throw new SecurityException("Skill review run scope mismatch");
        SkillReview current = list(events).stream().filter(r -> r.candidateSeq().equals(String.valueOf(candidateSeq)))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Skill candidate does not exist"));
        if (!current.sha256().equals(sha256)) throw new IllegalStateException("Skill candidate changed; reload before review");
        var original = events.stream().filter(e -> e.getSeq() == candidateSeq).findFirst().orElseThrow();
        var payload = parse(original);
        if (!String.valueOf(scope.personId()).equals(payload.path("personId").asText())
            || !String.valueOf(scope.projectId()).equals(payload.path("projectId").asText()))
            throw new SecurityException("Skill candidate owner mismatch");
        if ("PUBLISHED".equals(current.status()) || "REJECTED".equals(current.status())) {
            if (approved == "PUBLISHED".equals(current.status())) return current;
            throw new IllegalStateException("Skill candidate already reviewed");
        }
        ProjectAgentSkillBundle bundle = bundle(payload.get("bundle"));
        String reviewComment = comment == null ? "" : comment.trim();
        if (reviewComment.length() > 2000) throw new IllegalArgumentException("Skill review comment exceeds limit");
        append.accept(Map.of("kind", DECISION, "candidateSeq", current.candidateSeq(), "sha256", sha256,
            "status", approved ? "PENDING" : "REJECTED", "personId", String.valueOf(scope.personId()),
            "projectId", String.valueOf(scope.projectId()), "approved", approved, "reviewComment", reviewComment));
        if (!approved) return new SkillReview(current.candidateSeq(), bundle.skillName(), sha256, "REJECTED",
            bundle.files(), current.scanSummary(), reviewComment);
        String status; String summary;
        try {
            RuntimeContext ctx = KernelScopeKey.of(String.valueOf(scope.projectId()), String.valueOf(scope.personId()),
                ProjectAgentConstants.AGENT_ID, String.valueOf(scope.runId())).toRuntimeContext();
            Path target = publicationWorkspaceRoot.resolve(ProjectAgentSkillCatalog.sha256Hex(Objects.requireNonNull(scope.tenantId())))
                .resolve(String.valueOf(scope.projectId()))
                .resolve(String.valueOf(scope.personId())).resolve(String.valueOf(scope.runId()));
            for (Path scopePath = target; scopePath != null && scopePath.startsWith(publicationWorkspaceRoot); scopePath = scopePath.getParent())
                if (java.nio.file.Files.isSymbolicLink(scopePath)) throw new SecurityException("Skill publication scope symbolic link rejected");
            var published = publisher.publish(target, ctx, bundle, String.valueOf(scope.personId()));
            status = published.published() ? "PUBLISHED" : "PUBLISH_FAILED"; summary = published.scanSummary();
        } catch (RuntimeException failure) {
            status = "PUBLISH_FAILED"; summary = "Publication failed (" + failure.getClass().getSimpleName() + ")";
        }
        append.accept(Map.of("kind", PUBLICATION, "candidateSeq", current.candidateSeq(), "sha256", sha256,
            "status", status, "personId", String.valueOf(scope.personId()), "projectId", String.valueOf(scope.projectId()), "scanSummary", summary,
            "reviewComment", reviewComment));
        return new SkillReview(current.candidateSeq(), bundle.skillName(), sha256, status, bundle.files(), summary, reviewComment);
    }
    public Optional<ProjectAgentSkillCatalog.LoadedSkill> published(ReviewScope scope, String name, String digest) {
        return publishedReviews(scope).stream().filter(r -> r.skillName().equals(name)
            && (digest == null || digest.equals(r.sha256())))
            .findFirst().map(review -> {
                var bundle = new ProjectAgentSkillBundle(review.skillName(), review.sha256(), review.files());
                var skill = SkillUtil.createFrom(bundle.markdown(), bundle.textResources());
                return new ProjectAgentSkillCatalog.LoadedSkill(name, "review-" + review.sha256().substring(0, 12),
                    review.sha256(), skill.getSkillContent(), bundle);
            });
    }
    public List<ProjectAgentSkillCatalog.SkillStatus> publishedStatuses(ReviewScope scope) {
        Map<String, ProjectAgentSkillCatalog.SkillStatus> statuses = new LinkedHashMap<>();
        for (var review : publishedReviews(scope)) statuses.putIfAbsent(review.skillName(),
            new ProjectAgentSkillCatalog.SkillStatus(review.skillName(), "review-" + review.sha256().substring(0, 12),
                review.sha256(), true, null));
        return List.copyOf(statuses.values());
    }
    private List<SkillReview> publishedReviews(ReviewScope scope) {
        List<SkillReview> result = new ArrayList<>(); Long cursor = null;
        do {
            var runs = store.listOwnRuns(new AgentRunStore.OwnRunQuery(scope.tenantId(), scope.projectId(),
                scope.personId(), null, null, null, Set.of(), cursor, 50));
            if (runs.isEmpty()) break;
            for (var run : runs) {
                if (!Objects.equals(run.getTenantId(), scope.tenantId()) || !Objects.equals(run.getPersonId(), scope.personId())
                    || !Objects.equals(run.getProjectId(), scope.projectId())) throw new SecurityException("Skill catalog scope mismatch");
                List<IpdAgentRunEvent> events = new ArrayList<>(); long after = 0;
                for (;;) {
                    var page = store.listEvents(run.getId(), after, 200);
                    if (page.isEmpty()) break;
                    for (var event : page) if (!Objects.equals(event.getRunId(), run.getId())
                        || !Objects.equals(event.getTenantId(), scope.tenantId()))
                        throw new SecurityException("Skill catalog event scope mismatch");
                    events.addAll(page); long next = page.get(page.size() - 1).getSeq();
                    if (next <= after) throw new IllegalStateException("Skill event cursor did not advance");
                    after = next;
                }
                for (var event : events) {
                    if (!"STEP".equals(event.getEventType())) continue;
                    var payload = parse(event);
                    if (CANDIDATE.equals(payload.path("kind").asText())
                        && (!String.valueOf(run.getPersonId()).equals(payload.path("personId").asText())
                        || !String.valueOf(run.getProjectId()).equals(payload.path("projectId").asText())
                        || !String.valueOf(run.getId()).equals(payload.path("runId").asText())))
                        throw new SecurityException("Skill candidate differs from original run owner");
                }
                var reviews = list(events); for (int i = reviews.size() - 1; i >= 0; i--)
                    if ("PUBLISHED".equals(reviews.get(i).status())) result.add(reviews.get(i));
            }
            Long next = runs.get(runs.size() - 1).getId();
            if (cursor != null && next >= cursor) throw new IllegalStateException("Skill run cursor did not advance");
            cursor = next;
        } while (true);
        return result;
    }
    private static com.fasterxml.jackson.databind.JsonNode parse(IpdAgentRunEvent event) {
        try { return JSON.readTree(event.getPayload()); }
        catch (Exception error) { throw new IllegalStateException("Skill review event unreadable"); }
    }
    private static ProjectAgentSkillBundle bundle(com.fasterxml.jackson.databind.JsonNode value) {
        try { return JSON.treeToValue(value, ProjectAgentSkillBundle.class); }
        catch (Exception error) { throw new IllegalStateException("Skill review package invalid"); }
    }
}
