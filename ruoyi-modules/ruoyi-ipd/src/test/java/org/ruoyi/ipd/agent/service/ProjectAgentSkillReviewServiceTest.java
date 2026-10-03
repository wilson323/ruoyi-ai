package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.kernel.ProjectAgentSkillBundle;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentSkillReviewServiceTest {
    @TempDir Path root;
    private final AgentRunStore store = mock(AgentRunStore.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectAgentSkillReviewService.ReviewScope scope = new ProjectAgentSkillReviewService.ReviewScope("tenant", 1L, 2L, 3L);
    private ProjectAgentSkillBundle bundle() {
        String md = "---\nname: user-skill\ndescription: Format verified notes\n---\nFormat verified notes.";
        var file = new ProjectAgentSkillBundle.File("SKILL.md", md, ProjectAgentSkillCatalog.sha256Hex(md), "utf-8");
        return new ProjectAgentSkillBundle("user-skill", ProjectAgentSkillBundle.digest(List.of(file)), List.of(file));
    }
    private IpdAgentRunEvent candidate() throws Exception {
        return IpdAgentRunEvent.builder().runId(1L).tenantId("tenant").seq(5L).eventType("STEP")
            .payload(json.writeValueAsString(Map.of("kind", ProjectAgentSkillReviewService.CANDIDATE,
                "bundle", bundle(), "personId", "3", "projectId", "2", "runId", "1", "scanSummary", "pending"))).build();
    }
    private ProjectAgentSkillReviewService service() {
        return new ProjectAgentSkillReviewService(store, root, new CapabilityManifest(1, List.of(), List.of(), List.of()));
    }
    @Test void userApprovalPublishesThroughOfficialPromoterAndEventsReplay() throws Exception {
        var events = new ArrayList<>(List.of(candidate()));
        var result = service().review(scope, events, 5, true, bundle().sha256(), "verified", payload -> {
            try { events.add(IpdAgentRunEvent.builder().runId(1L).tenantId("tenant").seq(5L + events.size())
                .eventType("STEP").payload(json.writeValueAsString(payload)).build()); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        assertThat(result.status()).as(result.scanSummary()).isEqualTo("PUBLISHED");
        assertThat(service().list(events)).containsExactly(result);
        assertThat(events).hasSize(3);
        assertThat(service().review(scope, events, 5, true, bundle().sha256(), "same", ignored -> fail("must be idempotent")))
            .isEqualTo(result);
    }
    @Test void rejectDoesNotPublishAndCannotLaterApprove() throws Exception {
        var events = new ArrayList<>(List.of(candidate()));
        service().review(scope, events, 5, false, bundle().sha256(), "rejected", payload -> {
            try { events.add(IpdAgentRunEvent.builder().runId(1L).tenantId("tenant").seq(6L)
                .eventType("STEP").payload(json.writeValueAsString(payload)).build()); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        assertThat(service().list(events).get(0).status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> service().review(scope, events, 5, true, bundle().sha256(), "", ignored -> {}))
            .hasMessageContaining("already reviewed");
    }
    @Test void changedHashAndOtherPersonCannotApprove() throws Exception {
        var events = List.of(candidate());
        assertThatThrownBy(() -> service().review(scope, events, 5, true, "wrong", "", ignored -> {}))
            .hasMessageContaining("changed");
        var other = new ProjectAgentSkillReviewService.ReviewScope("tenant", 1L, 2L, 4L);
        assertThatThrownBy(() -> service().review(other, events, 5, true, bundle().sha256(), "", ignored -> {}))
            .isInstanceOf(SecurityException.class);
    }
    private IpdAgentRunEvent management(long seq, String kind, String status, long person, boolean approval) throws Exception {
        return IpdAgentRunEvent.builder().runId(1L).tenantId("tenant").seq(seq).eventType("STEP")
            .payload(json.writeValueAsString(Map.of("kind", kind, "candidateSeq", "5", "sha256", bundle().sha256(),
                "status", status, "personId", String.valueOf(person), "projectId", "2", "approved", approval))).build();
    }
    @Test void orphanPublicationCannotCreateApprovedCatalogEntry() throws Exception {
        var events = List.of(candidate(), management(6, ProjectAgentSkillReviewService.PUBLICATION, "PUBLISHED", 3, true));
        assertThatThrownBy(() -> service().list(events)).hasMessageContaining("no matching user approval");
    }
    @Test void decisionCannotClaimPublication() throws Exception {
        var events = List.of(candidate(), management(6, ProjectAgentSkillReviewService.DECISION, "PUBLISHED", 3, true));
        assertThatThrownBy(() -> service().list(events)).hasMessageContaining("Invalid skill review decision");
    }
    @Test void otherReviewerCannotForgeApprovalEvent() throws Exception {
        var events = List.of(candidate(), management(6, ProjectAgentSkillReviewService.DECISION, "PENDING", 4, true));
        assertThatThrownBy(() -> service().list(events)).isInstanceOf(SecurityException.class);
    }
    @Test void rejectedCandidateCannotLaterPublish() throws Exception {
        var events = List.of(candidate(), management(6, ProjectAgentSkillReviewService.DECISION, "REJECTED", 3, false),
            management(7, ProjectAgentSkillReviewService.PUBLICATION, "PUBLISHED", 3, true));
        assertThatThrownBy(() -> service().list(events)).hasMessageContaining("terminal");
    }
    @Test void approvalBooleanMustAgreeWithState() throws Exception {
        var events = List.of(candidate(), management(6, ProjectAgentSkillReviewService.DECISION, "PENDING", 3, false));
        assertThatThrownBy(() -> service().list(events)).hasMessageContaining("Invalid skill review decision");
    }
    @Test void publicationProjectionAfterTransactionRollbackCanBeVerifiedAndCommittedOnRetry() throws Exception {
        var events = List.of(candidate());
        assertThatThrownBy(() -> service().review(scope, events, 5, true, bundle().sha256(), "reviewed", payload -> {
            if (ProjectAgentSkillReviewService.PUBLICATION.equals(payload.get("kind"))) throw new IllegalStateException("transaction rollback");
        })).hasMessageContaining("transaction rollback");
        var committed = new ArrayList<>(events);
        var result = service().review(scope, events, 5, true, bundle().sha256(), "reviewed", payload -> {
            try { committed.add(IpdAgentRunEvent.builder().runId(1L).tenantId("tenant").seq(5L + committed.size())
                .eventType("STEP").payload(json.writeValueAsString(payload)).build()); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        assertThat(result.status()).as(result.scanSummary()).isEqualTo("PUBLISHED");
        assertThat(service().list(committed)).containsExactly(result);
    }

    private void pagedStore(List<IpdAgentRunEvent> events) {
        var run = org.ruoyi.ipd.agent.domain.IpdAgentRun.builder().id(1L).tenantId("tenant").personId(3L).projectId(2L).build();
        when(store.listOwnRuns(any())).thenAnswer(call -> {
            AgentRunStore.OwnRunQuery query = call.getArgument(0);
            return query.beforeId() == null ? List.of(run) : List.of();
        });
        when(store.listEvents(anyLong(), anyLong(), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(1);
            return events.stream().filter(e -> e.getSeq() > after).toList();
        });
    }
    @Test void realPromotionEventsFeedScopedCatalogAndExactFrozenPackage() throws Exception {
        var events = new ArrayList<>(List.of(candidate()));
        var service = service();
        var reviewed = service.review(scope, events, 5, true, bundle().sha256(), "verified", payload -> {
            try { events.add(IpdAgentRunEvent.builder().runId(1L).tenantId("tenant").seq(5L + events.size())
                .eventType("STEP").payload(json.writeValueAsString(payload)).build()); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        assertThat(reviewed.status()).as(reviewed.scanSummary()).isEqualTo("PUBLISHED");
        pagedStore(events);
        var manifest = new CapabilityManifest(1, List.of(), List.of(), List.of());
        var catalog = new ProjectAgentSkillCatalog("ipd-skills", manifest);
        catalog.setPublishedResolver(new ProjectAgentSkillCatalog.PublishedResolver() {
            public Optional<ProjectAgentSkillCatalog.LoadedSkill> load(String tenant, Long project, Long person, String name, String digest) {
                return service.published(new ProjectAgentSkillReviewService.ReviewScope(tenant, null, project, person), name, digest);
            }
            public List<ProjectAgentSkillCatalog.SkillStatus> statuses(String tenant, Long project, Long person) {
                return service.publishedStatuses(new ProjectAgentSkillReviewService.ReviewScope(tenant, null, project, person));
            }
        });
        var loaded = catalog.load("user-skill", "tenant", 2L, 3L, bundle().sha256()).orElseThrow();
        assertThat(loaded.bundle()).isEqualTo(bundle());
        assertThat(loaded.sha256()).isEqualTo(bundle().sha256());
        assertThat(catalog.statuses("tenant", 2L, 3L)).singleElement()
            .satisfies(status -> assertThat(status.sha256()).isEqualTo(bundle().sha256()));
        assertThat(catalog.load("user-skill", "tenant", 2L, 3L, "wrong-digest")).isEmpty();
    }
    @Test void selfConsistentForgedCandidateOwnerCannotEnterCatalog() throws Exception {
        var original = candidate();
        var value = json.readTree(original.getPayload());
        ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("personId", "4");
        original.setPayload(json.writeValueAsString(value));
        pagedStore(List.of(original));
        assertThatThrownBy(() -> service().publishedStatuses(scope))
            .isInstanceOf(SecurityException.class).hasMessageContaining("original run owner");
    }

}
