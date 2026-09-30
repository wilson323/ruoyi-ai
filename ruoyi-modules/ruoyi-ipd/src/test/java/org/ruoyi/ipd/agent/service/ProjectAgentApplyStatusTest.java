package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.service.AiDocumentService;
import org.ruoyi.ipd.service.IpdCopilotAccess;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.TENANT;

/**
 * apply 回执：库内码仍是 GENERATED，用户可见主状态为待审核；索引未就绪不得标 READY。
 */
@Tag("dev")
class ProjectAgentApplyStatusTest {

    @Test
    @DisplayName("apply 成功：主状态待审核，索引保持 NOT_INDEXED")
    void applyKeepsPendingReviewAndUnindexed() {
        Harness harness = new Harness();
        IpdAgentArtifactVersion draft = version(IpdAgentArtifactVersion.STATUS_DRAFT, null);
        when(harness.artifacts.findLatest(9L, "art-1")).thenReturn(Optional.of(draft));
        AiDocument doc = AiDocument.builder().id(8001L).status(AiDocumentService.STATUS_GENERATED).build();
        when(harness.documents.createGeneratedAuthorized(eq(ACTOR), eq(PROJECT_ID), any(), any(), any(),
            any(), any(), any())).thenReturn(doc);
        when(harness.artifacts.markApplied(71L, 8001L)).thenReturn(true);

        ProjectAgentViews.ArtifactApply view = harness.service.applyArtifact(ACTOR, 9L, "art-1");

        assertThat(doc.getStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(view.documentStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(view.documentStatusLabel()).isEqualTo(AiDocumentService.LABEL_PENDING_REVIEW);
        assertThat(view.documentStatusLabel()).isNotEqualTo("已审核");
        assertThat(view.indexStatus()).isEqualTo(ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
        assertThat(view.indexStatus()).isNotEqualTo("READY");
    }

    @Test
    @DisplayName("已应用幂等：仍是待审核，索引不得改成 READY")
    void appliedReplayStaysUnindexed() {
        Harness harness = new Harness();
        IpdAgentArtifactVersion applied = version(IpdAgentArtifactVersion.STATUS_APPLIED, 8001L);
        when(harness.artifacts.findLatest(9L, "art-1")).thenReturn(Optional.of(applied));

        ProjectAgentViews.ArtifactApply view = harness.service.applyArtifact(ACTOR, 9L, "art-1");

        assertThat(view.documentStatusLabel()).isEqualTo("待审核");
        assertThat(view.indexStatus()).isEqualTo("NOT_INDEXED");
        assertThat(view.indexStatus()).isNotEqualTo("READY");
    }

    private static IpdAgentArtifactVersion version(String status, Long documentId) {
        return IpdAgentArtifactVersion.builder()
            .id(71L)
            .artifactId("art-1")
            .versionNo(1)
            .title("产物")
            .content("正文")
            .status(status)
            .documentId(documentId)
            .build();
    }

    /** 只装配 apply 需要的替身；内核与执行器不参与本次断言。 */
    private static final class Harness {
        final AgentRunStore runs = mock(AgentRunStore.class);
        final ArtifactVersionStore artifacts = mock(ArtifactVersionStore.class);
        final AiDocumentService documents = mock(AiDocumentService.class);
        final ProjectAgentRunService service;

        Harness() {
            IpdCopilotAccess access = mock(IpdCopilotAccess.class);
            when(access.requireVisible(ACTOR, PROJECT_ID)).thenReturn(TENANT);
            IpdAgentRun run = IpdAgentRun.builder()
                .id(9L)
                .tenantId(TENANT)
                .projectId(PROJECT_ID)
                .personId(ACTOR.id())
                .actionCode("C02")
                .build();
            when(runs.findRun(9L)).thenReturn(Optional.of(run));
            service = new ProjectAgentRunService(true, access, mock(ProjectAgentRunPlanner.class), runs, artifacts,
                documents, null, null, mock(ProjectAgentRunExecutor.class), new ObjectMapper(), () -> 0L,
                Duration.ofSeconds(60));
        }
    }
}
