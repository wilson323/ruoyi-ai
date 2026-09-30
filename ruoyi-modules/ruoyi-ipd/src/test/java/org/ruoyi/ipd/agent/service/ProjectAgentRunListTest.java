package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.support.InMemoryArtifactVersionStore;
import org.ruoyi.ipd.agent.support.RunServiceHarness;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.OTHER_ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.OTHER_PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.TENANT;

/**
 * 运行列表：只返回本人该项目，搜索命中动作、状态和产物，响应不含原文与摘要。
 */
@Tag("dev")
class ProjectAgentRunListTest {

    private static final String SECRET_MESSAGE = "用户提问原文不入库XYZ";
    private static final String DIGEST = "digest-must-not-be-searchable-0123456789abcdef";

    @Test
    @DisplayName("空 q 返回本人最近运行；他人不可见；摘要和原文都不进响应")
    void emptyQueryReturnsOwnRecentRuns() throws Exception {
        Fixture fixture = new Fixture();
        Long older = fixture.seed(ACTOR.id(), PROJECT_ID, "C01", "SUCCEEDED", "key-list-0001");
        Long newer = fixture.seed(ACTOR.id(), PROJECT_ID, "C02", "RUNNING", "key-list-0002");
        fixture.seed(OTHER_ACTOR.id(), PROJECT_ID, "C02", "SUCCEEDED", "key-list-other");
        fixture.artifact(newer, "竞品对比表", "这是产物正文而不是提问。" + "补".repeat(80));

        List<ProjectAgentViews.RunItem> items = fixture.service.list(ACTOR, PROJECT_ID, " ", null, null, null, null);

        assertThat(items).extracting(ProjectAgentViews.RunItem::runId)
            .containsExactly(String.valueOf(newer), String.valueOf(older));
        assertThat(items.get(0).actionCode()).isEqualTo("C02");
        assertThat(items.get(0).artifactTitles()).containsExactly("竞品对比表");
        assertThat(items.get(0).artifactExcerpt()).hasSize(80).startsWith("这是产物正文");
        assertThat(items.get(0).inputChars()).isEqualTo(SECRET_MESSAGE.length());
        String json = new ObjectMapper().writeValueAsString(items);
        assertThat(json).doesNotContain("inputDigest").doesNotContain(DIGEST).doesNotContain(SECRET_MESSAGE);
    }

    @Test
    @DisplayName("q 命中产物标题和正文，不命中 inputDigest")
    void queryMatchesArtifactButNotDigest() {
        Fixture fixture = new Fixture();
        Long hit = fixture.seed(ACTOR.id(), PROJECT_ID, "C03", "FAILED", "key-list-0003");
        fixture.artifact(hit, "渠道价格表", "正文里有独特词青果");

        assertThat(fixture.service.list(ACTOR, PROJECT_ID, "青果", null, null, null, 20))
            .extracting(ProjectAgentViews.RunItem::runId).containsExactly(String.valueOf(hit));
        assertThat(fixture.service.list(ACTOR, PROJECT_ID, "渠道价格", null, null, null, 20))
            .extracting(ProjectAgentViews.RunItem::runId).containsExactly(String.valueOf(hit));
        assertThat(fixture.service.list(ACTOR, PROJECT_ID, "C03", null, null, null, 20)).hasSize(1);
        assertThat(fixture.service.list(ACTOR, PROJECT_ID, "FAILED", null, null, null, 20)).hasSize(1);
        assertThat(fixture.service.list(ACTOR, PROJECT_ID, DIGEST, null, null, null, 20)).isEmpty();
        assertThat(fixture.service.list(ACTOR, PROJECT_ID, SECRET_MESSAGE, null, null, null, 20)).isEmpty();
    }

    @Test
    @DisplayName("cursor 翻页；跨项目不可见；非法 status 拒绝")
    void cursorAndVisibility() {
        Fixture fixture = new Fixture();
        fixture.seed(ACTOR.id(), PROJECT_ID, "C01", "SUCCEEDED", "key-page-1");
        Long middle = fixture.seed(ACTOR.id(), PROJECT_ID, "C02", "SUCCEEDED", "key-page-2");
        fixture.seed(ACTOR.id(), PROJECT_ID, "C03", "SUCCEEDED", "key-page-3");
        fixture.seed(ACTOR.id(), OTHER_PROJECT_ID, "C02", "SUCCEEDED", "key-page-other-project");

        List<ProjectAgentViews.RunItem> first = fixture.service.list(ACTOR, PROJECT_ID, null, null, null, null, 2);
        assertThat(first).hasSize(2);
        List<ProjectAgentViews.RunItem> second = fixture.service.list(
            ACTOR, PROJECT_ID, null, null, null, Long.valueOf(first.get(1).runId()), 2);
        assertThat(second).extracting(ProjectAgentViews.RunItem::runId).containsExactly(String.valueOf(middle - 1));
        assertThatThrownBy(() -> fixture.service.list(ACTOR, OTHER_PROJECT_ID, null, null, null, null, 20))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> fixture.service.list(ACTOR, PROJECT_ID, null, "NO_SUCH", null, null, 20))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
    }

    private static final class Fixture {
        private final RunServiceHarness harness = new RunServiceHarness(true, true, 4);
        private final InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        private final ProjectAgentRunService service = new ProjectAgentRunService(
            true, harness.access, org.ruoyi.ipd.agent.support.AgentTestFixtures.planner(),
            harness.store, artifacts, null, null, null, harness.executor,
            org.ruoyi.ipd.agent.support.AgentTestFixtures.MAPPER, harness.clock::get, Duration.ofSeconds(60));

        Long seed(Long personId, Long projectId, String action, String status, String key) {
            IpdAgentRun run = IpdAgentRun.builder()
                .tenantId(TENANT)
                .projectId(projectId)
                .personId(personId)
                .agentId(ProjectAgentConstants.AGENT_ID)
                .status(status)
                .actionCode(action)
                .capabilityPackCode("market-research")
                .capabilityPackVersion("v1")
                .idempotencyKey(key)
                .requestDigest("req")
                .inputDigest(DIGEST)
                .inputChars(SECRET_MESSAGE.length())
                .version(0)
                .delFlag("0")
                .build();
            run.setCreateTime(new Date(harness.clock.get()));
            if (!AgentRunStatus.PENDING.name().equals(status) && !AgentRunStatus.RUNNING.name().equals(status)) {
                run.setFinishedAt(new Date(harness.clock.get()));
            }
            harness.store.insertRun(run);
            return run.getId();
        }

        void artifact(Long runId, String title, String content) {
            IpdAgentArtifactVersion version = IpdAgentArtifactVersion.builder()
                .tenantId(TENANT).runId(runId).artifactId("logic-" + runId).versionNo(1)
                .title(title).content(content).contentSha256("c".repeat(64))
                .status(IpdAgentArtifactVersion.STATUS_DRAFT).delFlag("0").build();
            artifacts.insert(version);
        }
    }
}
