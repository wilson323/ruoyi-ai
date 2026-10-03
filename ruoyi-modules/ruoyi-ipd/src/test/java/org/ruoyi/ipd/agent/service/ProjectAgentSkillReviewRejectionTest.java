package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.kernel.ProjectAgentSkillBundle;
import org.ruoyi.ipd.agent.store.AgentRunStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 人工审核环节的<b>拒绝路径</b>反例（对应 AgentScope 审计 A21 中
 * {@code ProjectAgentSkillReviewService} 一段）。
 *
 * <p>技能从「模型写的草稿」变成「本轮可执行的授权技能」，唯一入口就是这里的一次人工审核。
 * 既有 {@code ProjectAgentSkillReviewServiceTest} 覆盖了「换了 sha / 换了人 / 伪造审批 / 撤销后复活」
 * 四类，但没有覆盖：作用域外的运行事件、超长审核意见、不存在的候选序号、管理事件与候选摘要对不上、
 * 发布状态越出白名单。这五条都是「把不该算数的输入算数了」的形态，逐条钉住。
 */
@Tag("dev")
@DisplayName("技能审核反例：越界事件、超长意见、幽灵候选、摘要错配、非法发布状态一律拒绝")
class ProjectAgentSkillReviewRejectionTest {

    @TempDir Path root;

    private final AgentRunStore store = mock(AgentRunStore.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectAgentSkillReviewService.ReviewScope scope =
        new ProjectAgentSkillReviewService.ReviewScope("tenant", 1L, 2L, 3L);

    private ProjectAgentSkillReviewService service() {
        return new ProjectAgentSkillReviewService(store, root,
            new CapabilityManifest(1, List.of(), List.of(), List.of()));
    }

    private ProjectAgentSkillBundle bundle() {
        String md = "---\nname: user-skill\ndescription: Format verified notes\n---\nFormat verified notes.";
        var file = new ProjectAgentSkillBundle.File("SKILL.md", md, ProjectAgentSkillCatalog.sha256Hex(md), "utf-8");
        return new ProjectAgentSkillBundle("user-skill", ProjectAgentSkillBundle.digest(List.of(file)), List.of(file));
    }

    private IpdAgentRunEvent candidateOf(long runId, String tenant, long seq) throws Exception {
        return IpdAgentRunEvent.builder().runId(runId).tenantId(tenant).seq(seq).eventType("STEP")
            .payload(json.writeValueAsString(Map.of("kind", ProjectAgentSkillReviewService.CANDIDATE,
                "bundle", bundle(), "personId", "3", "projectId", "2", "runId", String.valueOf(runId),
                "scanSummary", "pending"))).build();
    }

    private IpdAgentRunEvent candidate() throws Exception {
        return candidateOf(1L, "tenant", 5L);
    }

    private IpdAgentRunEvent management(long seq, String kind, String status, String sha256,
                                        String projectId) throws Exception {
        return IpdAgentRunEvent.builder().runId(1L).tenantId("tenant").seq(seq).eventType("STEP")
            .payload(json.writeValueAsString(Map.of("kind", kind, "candidateSeq", "5", "sha256", sha256,
                "status", status, "personId", "3", "projectId", projectId, "approved", true))).build();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("审核不能跨运行：事件属于别的 run 即拒绝，不得用本次审核去签别人的候选")
    void eventsFromAnotherRunAreRejected() throws Exception {
        List<IpdAgentRunEvent> foreign = List.of(candidateOf(2L, "tenant", 5L));

        assertThatThrownBy(() -> service().review(scope, foreign, 5, true, bundle().sha256(), "", ignored -> { }))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("run scope mismatch");
    }

    @Test
    @DisplayName("审核不能跨租户：同 run 号但别的租户即拒绝")
    void eventsFromAnotherTenantAreRejected() throws Exception {
        List<IpdAgentRunEvent> foreign = List.of(candidateOf(1L, "other-tenant", 5L));

        assertThatThrownBy(() -> service().review(scope, foreign, 5, true, bundle().sha256(), "", ignored -> { }))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("run scope mismatch");
    }

    @Test
    @DisplayName("审核意见超长即拒绝，且一个事件都不得落库")
    void oversizedReviewCommentIsRejectedBeforeAnyAppend() throws Exception {
        List<IpdAgentRunEvent> events = List.of(candidate());
        AtomicInteger appended = new AtomicInteger();

        assertThatThrownBy(() -> service().review(scope, events, 5, true, bundle().sha256(),
            "超".repeat(2001), ignored -> appended.incrementAndGet()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("comment exceeds limit");

        assertThat(appended).as("拒绝必须发生在任何事件追加之前").hasValue(0);
    }

    @Test
    @DisplayName("幽灵候选序号即拒绝——不得凭空造出一条审核记录")
    void unknownCandidateSeqIsRejected() throws Exception {
        List<IpdAgentRunEvent> events = List.of(candidate());
        AtomicInteger appended = new AtomicInteger();

        assertThatThrownBy(() -> service().review(scope, events, 99, true, bundle().sha256(), "",
            ignored -> appended.incrementAndGet()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("candidate does not exist");

        assertThat(appended).hasValue(0);
    }

    @Test
    @DisplayName("管理事件的摘要与候选对不上即拒绝——不能拿一次通过去认领另一个字节包")
    void managementEventWithForeignDigestIsRejected() throws Exception {
        List<IpdAgentRunEvent> events = List.of(candidate(),
            management(6, ProjectAgentSkillReviewService.DECISION, "PENDING", "f".repeat(64), "2"));

        assertThatThrownBy(() -> service().list(events))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not match candidate");
    }

    @Test
    @DisplayName("管理事件的项目与候选不同即拒绝——同 run 内也不许跨项目认领")
    void managementEventFromAnotherProjectIsRejected() throws Exception {
        List<IpdAgentRunEvent> events = List.of(candidate(),
            management(6, ProjectAgentSkillReviewService.DECISION, "PENDING", bundle().sha256(), "9"));

        assertThatThrownBy(() -> service().list(events))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("scope mismatch");
    }

    @Test
    @DisplayName("发布事件的状态越出白名单即拒绝——只有 PUBLISHED / PUBLISH_FAILED 算发布")
    void publicationWithStatusOutsideTheWhitelistIsRejected() throws Exception {
        List<IpdAgentRunEvent> events = List.of(candidate(),
            management(6, ProjectAgentSkillReviewService.DECISION, "PENDING", bundle().sha256(), "2"),
            management(7, ProjectAgentSkillReviewService.PUBLICATION, "PENDING", bundle().sha256(), "2"));

        assertThatThrownBy(() -> service().list(events))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no matching user approval");
    }

    @Test
    @DisplayName("审核通过后追加的事件必须与返回结果一致——拒绝的候选不得在重放里变成已通过")
    void rejectionStaysRejectedAcrossReplay() throws Exception {
        var events = new ArrayList<>(List.of(candidate()));
        var rejected = service().review(scope, events, 5, false, bundle().sha256(), "不合规", payload -> {
            try {
                events.add(IpdAgentRunEvent.builder().runId(1L).tenantId("tenant")
                    .seq(5L + events.size()).eventType("STEP")
                    .payload(json.writeValueAsString(payload)).build());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(service().list(events)).singleElement()
            .satisfies(review -> assertThat(review.status()).isEqualTo("REJECTED"));
        assertThat(service().list(events).get(0).sha256()).isEqualTo(bundle().sha256());
    }
}
