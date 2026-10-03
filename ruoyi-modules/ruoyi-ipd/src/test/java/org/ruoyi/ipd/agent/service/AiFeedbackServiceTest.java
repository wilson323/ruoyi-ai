package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.dto.AiFeedbackReq;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import org.ruoyi.ipd.agent.support.InMemoryAiFeedbackStore;
import org.ruoyi.ipd.agent.support.InMemoryArtifactVersionStore;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.service.IpdCopilotAccess;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.OTHER_ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.TENANT;

/**
 * AI 反馈（合同 #6）：targetType 仅 RUN_MESSAGE | ARTIFACT_VERSION；本人可更新；
 * 开关关闭 STATE_CONFLICT 且消息含 enabled=false；跨人/跨项目零写入。
 */
@Tag("dev")
class AiFeedbackServiceTest {

    private final InMemoryAgentRunStore runs = new InMemoryAgentRunStore();
    private final InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
    private final InMemoryAiFeedbackStore feedback = new InMemoryAiFeedbackStore();
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final IpdCopilotAccess access = mock(IpdCopilotAccess.class);

    @Test
    @DisplayName("RUN_MESSAGE：本人写入 UP，随后改 DOWN 更新同一行")
    void runMessageUpsertByOwner() {
        when(access.requireVisible(eq(ACTOR), eq(PROJECT_ID))).thenReturn(TENANT);
        Long runId = seedOwnRun();
        AiFeedbackService service = service(true);

        ProjectAgentViews.Feedback first = service.put(ACTOR, ProjectAgentConstants.TARGET_RUN_MESSAGE,
            String.valueOf(runId), new AiFeedbackReq("up", "有用"));
        assertThat(first.rating()).isEqualTo("UP");
        assertThat(first.targetType()).isEqualTo("RUN_MESSAGE");
        assertThat(first.targetId()).isEqualTo(String.valueOf(runId));
        assertThat(feedback.size()).isEqualTo(1);
        assertThat(feedback.inserts).hasValue(1);

        ProjectAgentViews.Feedback second = service.put(ACTOR, ProjectAgentConstants.TARGET_RUN_MESSAGE,
            String.valueOf(runId), new AiFeedbackReq("DOWN", "偏题"));
        assertThat(second.rating()).isEqualTo("DOWN");
        assertThat(second.reason()).isEqualTo("偏题");
        assertThat(feedback.size()).isEqualTo(1);
        assertThat(feedback.updates).hasValue(1);
    }

    @Test
    @DisplayName("非法 targetType：PARAM_INVALID；ARTIFACT_VERSION 无版本行：STATE_CONFLICT；均零写入")
    void targetTypeMustBeEnum() {
        when(access.requireVisible(any(), any())).thenReturn(TENANT);
        seedOwnRun();
        AiFeedbackService service = service(true);

        assertCode(() -> service.put(ACTOR, "MESSAGE", "1", new AiFeedbackReq("UP", null)),
            ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> service.put(ACTOR, ProjectAgentConstants.TARGET_ARTIFACT_VERSION, "1",
            new AiFeedbackReq("UP", null)), ApiV1ErrorCode.STATE_CONFLICT);
        assertThat(feedback.size()).isZero();
    }

    @Test
    @DisplayName("ARTIFACT_VERSION：版本行存在且本人运行时写入成功")
    void artifactVersionAcceptedWhenRowExists() {
        when(access.requireVisible(eq(ACTOR), eq(PROJECT_ID))).thenReturn(TENANT);
        Long runId = seedOwnRun();
        IpdAgentArtifactVersion version = IpdAgentArtifactVersion.builder()
            .tenantId(TENANT).runId(runId).artifactId("art-1").versionNo(1)
            .title("t").content("c").contentSha256("a".repeat(64))
            .status(IpdAgentArtifactVersion.STATUS_DRAFT).delFlag("0").build();
        artifacts.insert(version);
        AiFeedbackService service = service(true);

        ProjectAgentViews.Feedback result = service.put(ACTOR, ProjectAgentConstants.TARGET_ARTIFACT_VERSION,
            String.valueOf(version.getId()), new AiFeedbackReq("UP", "可用"));
        assertThat(result.targetType()).isEqualTo("ARTIFACT_VERSION");
        assertThat(result.targetId()).isEqualTo(String.valueOf(version.getId()));
        assertThat(result.rating()).isEqualTo("UP");
        assertThat(feedback.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("逻辑 artifactId 不能当版本行点赞：非数字是 PARAM_INVALID，数字但不是版本行是 STATE_CONFLICT")
    void logicalArtifactIdIsNotAcceptedAsVersion() {
        when(access.requireVisible(eq(ACTOR), eq(PROJECT_ID))).thenReturn(TENANT);
        Long runId = seedOwnRun();
        IpdAgentArtifactVersion version = IpdAgentArtifactVersion.builder()
            .tenantId(TENANT).runId(runId).artifactId("art-1").versionNo(1)
            .title("t").content("c").contentSha256("b".repeat(64))
            .status(IpdAgentArtifactVersion.STATUS_DRAFT).delFlag("0").build();
        artifacts.insert(version);
        AiFeedbackService service = service(true);

        assertCode(() -> service.put(ACTOR, ProjectAgentConstants.TARGET_ARTIFACT_VERSION, "art-1",
            new AiFeedbackReq("UP", null)), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> service.put(ACTOR, ProjectAgentConstants.TARGET_ARTIFACT_VERSION, "1",
            new AiFeedbackReq("UP", null)), ApiV1ErrorCode.STATE_CONFLICT);
        assertThat(feedback.size()).isZero();
    }

    @Test
    @DisplayName("运行服务不可用：STATE_CONFLICT，零写入")
    void unavailableRunServiceRejectsWithoutWrite() {
        assertThatThrownBy(() -> service(false).put(ACTOR, ProjectAgentConstants.TARGET_RUN_MESSAGE, "1",
            new AiFeedbackReq("UP", null)))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("项目智能体运行服务不可用");
        assertThat(feedback.size()).isZero();
    }

    @Test
    @DisplayName("非本人运行：NOT_FOUND，零写入")
    void nonOwnerTargetNotFound() {
        when(access.requireVisible(eq(OTHER_ACTOR), eq(PROJECT_ID))).thenReturn(TENANT);
        Long runId = seedOwnRun();

        assertCode(() -> service(true).put(OTHER_ACTOR, ProjectAgentConstants.TARGET_RUN_MESSAGE,
            String.valueOf(runId), new AiFeedbackReq("UP", null)), ApiV1ErrorCode.NOT_FOUND);
        assertThat(feedback.size()).isZero();
    }

    @Test
    @DisplayName("rating 非法 / targetId 非数字：PARAM_INVALID")
    void invalidRatingOrTargetId() {
        Long runId = seedOwnRun();
        when(access.requireVisible(any(), any())).thenReturn(TENANT);
        AiFeedbackService service = service(true);

        assertCode(() -> service.put(ACTOR, ProjectAgentConstants.TARGET_RUN_MESSAGE,
            String.valueOf(runId), new AiFeedbackReq("LIKE", null)), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> service.put(ACTOR, ProjectAgentConstants.TARGET_RUN_MESSAGE, "abc",
            new AiFeedbackReq("UP", null)), ApiV1ErrorCode.PARAM_INVALID);
    }

    private AiFeedbackService service(boolean enabled) {
        return new AiFeedbackService(enabled, access, runs, artifacts, feedback, clock::get);
    }

    private Long seedOwnRun() {
        IpdAgentRun run = IpdAgentRun.builder()
            .tenantId(TENANT)
            .projectId(PROJECT_ID)
            .personId(ACTOR.id())
            .agentId(ProjectAgentConstants.AGENT_ID)
            .status(AgentRunStatus.SUCCEEDED.name())
            .idempotencyKey("fb-seed-0001")
            .requestDigest("digest")
            .delFlag("0")
            .version(0)
            .build();
        runs.insertRun(run);
        return run.getId();
    }

    private static void assertCode(Runnable call, ApiV1ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(IpdBusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
