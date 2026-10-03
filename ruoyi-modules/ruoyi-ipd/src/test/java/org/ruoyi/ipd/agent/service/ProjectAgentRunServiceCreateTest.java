package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.support.RunServiceHarness;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.OTHER_PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.c02;

/**
 * 创建运行：成功落库与冻结快照、幂等、开关关闭/跨项目/非成员/冻结拒绝且零写入、目录校验、并发额度。
 * 内核为替身（非运行态证据）；Skill 正文与清单为真实 classpath 资源。
 */
@Tag("dev")
class ProjectAgentRunServiceCreateTest {

    private static final String MESSAGE = "请对本项目做竞品分析：功能、价格、渠道、技术路线";

    @Test
    @DisplayName("成功：返回字符串 runId 与 PENDING；落库冻结快照；启动后 RUN_STARTED，再 SKILL_SELECTED，再 INTENT")
    void createPersistsSnapshotAndStarts() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        ProjectAgentViews.RunStatus created = h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0001", MESSAGE));

        assertThat(created.status()).isEqualTo("PENDING");
        Long runId = Long.valueOf(created.runId());
        IpdAgentRun run = h.store.findRun(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getAgentId()).isEqualTo(ProjectAgentConstants.AGENT_ID);
        assertThat(run.getTenantId()).isEqualTo(AgentTestFixtures.TENANT);
        assertThat(run.getPersonId()).isEqualTo(ACTOR.id());
        assertThat(run.getConfigSnapshot()).contains("competitor-analysis-ipd")
            .contains(AgentTestFixtures.manifest().skill("competitor-analysis-ipd").orElseThrow().sha256())
            .contains("project_knowledge_search").contains("\"modelConfigId\":\"7001\"");
        assertThat(run.getInputDigest()).hasSize(64).doesNotContain(MESSAGE);
        assertThat(run.getTaskId()).as("W1 不关联 ai_agent_tasks").isNull();

        List<IpdAgentRunEvent> events = h.store.events(runId);
        assertThat(events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("RUN_STARTED", "STEP", "STEP");
        Map<String, Object> skillStep = payload(events.get(1));
        Map<String, Object> intentStep = payload(events.get(2));
        assertThat(skillStep.get("kind")).isEqualTo("SKILL_SELECTED");
        assertThat(skillStep).containsEntry("name", "competitor-analysis-ipd");
        assertThat(String.valueOf(skillStep.get("sha256"))).hasSize(64);
        assertThat(intentStep.get("kind")).isEqualTo("INTENT");
        assertThat(intentStep.get("needsPlan")).isEqualTo(true);

        ProjectAgentRunSpec spec = h.kernel.last().spec();
        assertThat(spec.projectId()).isEqualTo(PROJECT_ID);
        assertThat(spec.personId()).isEqualTo(ACTOR.id());
        assertThat(spec.skills()).singleElement().satisfies(s -> assertThat(s.content()).contains("功能").contains("技术路线"));
        assertThat(spec.model().modelName()).isEqualTo("MiniMax-M3");
    }

    @Test
    @DisplayName("幂等：同人同键同请求返回原 runId，只插入一次、只执行一次")
    void sameKeyReturnsOriginalRun() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        String first = h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0002", MESSAGE)).runId();
        String second = h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0002", MESSAGE)).runId();

        assertThat(second).isEqualTo(first);
        assertThat(h.store.runInserts).hasValue(1);
        assertThat(h.kernel.executions).hasSize(1);
    }

    @Test
    @DisplayName("幂等冲突：同键不同请求体 STATE_CONFLICT，不新建运行")
    void sameKeyDifferentBodyConflicts() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0003", MESSAGE));

        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0003", "另一个问题")),
            ApiV1ErrorCode.STATE_CONFLICT);
        assertThat(h.store.runInserts).hasValue(1);
    }

    @Test
    @DisplayName("开关关闭：STATE_CONFLICT + 明确原因，不触访问守卫、不写库、不调内核")
    void disabledRejectsWithoutWrites() {
        RunServiceHarness h = new RunServiceHarness(false, false, 4);
        assertThatThrownBy(() -> h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0004", MESSAGE)))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining(org.ruoyi.ipd.agent.ProjectAgentConstants.REASON_DISABLED);

        verifyNoInteractions(h.access);
        assertThat(h.store.runCount()).isZero();
        assertThat(h.kernel.executions).isEmpty();
    }

    @Test
    @DisplayName("跨项目/非成员：NOT_FOUND，零写入")
    void crossProjectRejectedWithoutWrites() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        assertCode(() -> h.service.create(ACTOR, OTHER_PROJECT_ID, c02("idem-key-0005", MESSAGE)),
            ApiV1ErrorCode.NOT_FOUND);
        assertThat(h.store.runCount()).isZero();
        assertThat(h.store.eventInserts).hasValue(0);
    }

    @Test
    @DisplayName("冻结账号：FORBIDDEN，零写入")
    void frozenAccountRejectedWithoutWrites() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        assertCode(() -> h.service.create(RunServiceHarness.FROZEN, PROJECT_ID, c02("idem-key-0006", MESSAGE)),
            ApiV1ErrorCode.FORBIDDEN);
        assertThat(h.store.runCount()).isZero();
    }

    @Test
    @DisplayName("目录校验：未知能力包/包外 Skill/包外工具/包外动作 PARAM_INVALID，未启用模型 STATE_CONFLICT，均零写入")
    void catalogValidationRejectsWithoutWrites() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        List<String> skill = List.of("competitor-analysis-ipd");
        List<String> tool = List.of("project_knowledge_search");
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, new AgentRunCreateReq("market-research", "v9", "7001",
            skill, tool, "C02", MESSAGE, "idem-key-0007")), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, new AgentRunCreateReq("market-research", "v1", "7001",
            List.of("create-prd"), tool, "C02", MESSAGE, "idem-key-0008")), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, new AgentRunCreateReq("market-research", "v1", "7001",
            skill, List.of("web_search"), "C02", MESSAGE, "idem-key-0009")), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, new AgentRunCreateReq("market-research", "v1", "7001",
            skill, tool, "P01", MESSAGE, "idem-key-0010")), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, new AgentRunCreateReq("market-research", "v1",
            String.valueOf(AgentTestFixtures.INACTIVE_MODEL_ID), skill, tool, "C02", MESSAGE, "idem-key-0011")),
            ApiV1ErrorCode.STATE_CONFLICT);
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, new AgentRunCreateReq("market-research", "v1", "abc",
            skill, tool, "C02", MESSAGE, "idem-key-0012")), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, c02("短键", MESSAGE)), ApiV1ErrorCode.PARAM_INVALID);
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0013", " ")), ApiV1ErrorCode.PARAM_INVALID);

        assertThat(h.store.runCount()).isZero();
        assertThat(h.kernel.executions).isEmpty();
    }

    @Test
    @DisplayName("并发额度：满额 RATE_LIMITED 零写入；运行收口后额度释放可再创建")
    void capacityLimitIsEnforcedBeforeWrite() {
        RunServiceHarness h = new RunServiceHarness(true, false, 1);
        h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0014", MESSAGE));
        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0015", MESSAGE)),
            ApiV1ErrorCode.RATE_LIMITED);
        assertThat(h.store.runCount()).isEqualTo(1);

        h.kernel.last().sink().onComplete();
        h.service.create(ACTOR, PROJECT_ID, c02("idem-key-0016", MESSAGE));
        assertThat(h.store.runCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("普通项目不得携带需求分拣 ID：拒绝且不写运行、不调用内核")
    void ordinaryProjectCannotInjectDemandContext() {
        RunServiceHarness h = new RunServiceHarness(true, false, 4);
        AgentRunCreateReq base = c02("idem-demand-0001", MESSAGE);
        AgentRunCreateReq req = new AgentRunCreateReq(base.capabilityPackCode(), base.capabilityPackVersion(),
            base.modelConfigId(), base.skillNames(), base.toolIds(), base.actionCode(), base.message(),
            base.idempotencyKey(), null, "42");

        assertCode(() -> h.service.create(ACTOR, PROJECT_ID, req), ApiV1ErrorCode.PARAM_INVALID);
        assertThat(h.store.runCount()).isZero();
        assertThat(h.kernel.executions).isEmpty();
    }

    @Test
    @DisplayName("固定分拣项目保留需求 ID 接线；非法需求 ID 在写运行前拒绝")
    void triageProjectKeepsDemandInputAndRejectsMalformedIdBeforeWrites() {
        RunServiceHarness h = new RunServiceHarness(true, true, 4);
        org.mockito.Mockito.when(h.access.requireVisible(ACTOR, DemandTriageRun.TRIAGE_PROJECT_ID))
            .thenReturn(AgentTestFixtures.TENANT);
        AgentRunCreateReq base = c02("idem-demand-0002", MESSAGE);
        AgentRunCreateReq invalid = new AgentRunCreateReq(base.capabilityPackCode(), base.capabilityPackVersion(),
            base.modelConfigId(), base.skillNames(), base.toolIds(), base.actionCode(), base.message(),
            base.idempotencyKey(), null, "not-an-id");
        assertCode(() -> h.service.create(ACTOR, DemandTriageRun.TRIAGE_PROJECT_ID, invalid),
            ApiV1ErrorCode.PARAM_INVALID);
        assertThat(h.store.runCount()).isZero();

        AgentRunCreateReq valid = new AgentRunCreateReq(base.capabilityPackCode(), base.capabilityPackVersion(),
            base.modelConfigId(), base.skillNames(), base.toolIds(), base.actionCode(), base.message(),
            base.idempotencyKey(), null, "42");
        h.service.create(ACTOR, DemandTriageRun.TRIAGE_PROJECT_ID, valid);
        h.runDeferred();
        assertThat(h.kernel.last().spec().requirementId()).isEqualTo(42L);
    }

    @Test
    void projectFactsFailureOccursBeforeReservationOrInsert() {
        RunServiceHarness h = new RunServiceHarness(true, true, 1);
        var projects = org.mockito.Mockito.mock(org.ruoyi.ipd.mapper.ProjectMapper.class);
        org.mockito.Mockito.when(projects.selectById(PROJECT_ID))
            .thenThrow(new IllegalStateException("project lookup failed"));
        ProjectAgentRunService service = new ProjectAgentRunService(true, h.access,
            AgentTestFixtures.planner(), h.store, null, null, projects, null, h.executor,
            AgentTestFixtures.MAPPER, h.clock::get, java.time.Duration.ofSeconds(60));
        assertThatThrownBy(() -> service.create(ACTOR, PROJECT_ID, c02("facts-fail-0001", MESSAGE)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("project lookup failed");
        assertThat(h.store.runInserts).hasValue(0);
        assertThat(h.deferred).isEmpty();
        assertThat(h.executor.tryReserve()).isTrue();
        assertThat(h.executor.tryReserve()).isFalse();
        h.executor.release();
    }

    @Test
    void insertExceptionReturnsExactlyOneReservation() {
        RunServiceHarness h = new RunServiceHarness(true, true, 1);
        var runs = org.mockito.Mockito.mock(org.ruoyi.ipd.agent.store.AgentRunStore.class,
            org.mockito.AdditionalAnswers.delegatesTo(h.store));
        org.mockito.Mockito.doThrow(new IllegalStateException("run insert failed"))
            .when(runs).insertRun(org.mockito.ArgumentMatchers.any());
        var executor = new ProjectAgentRunExecutor(runs, h.kernel, AgentTestFixtures.MAPPER,
            reactor.core.scheduler.Schedulers.fromExecutor(h.deferred::add), h.clock::get, 1);
        ProjectAgentRunService service = new ProjectAgentRunService(true, h.access,
            AgentTestFixtures.planner(), runs, executor, AgentTestFixtures.MAPPER,
            h.clock::get, java.time.Duration.ofSeconds(60));
        assertThatThrownBy(() -> service.create(ACTOR, PROJECT_ID, c02("insert-fail-0001", MESSAGE)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("run insert failed");
        assertThat(executor.tryReserve()).isTrue();
        assertThat(executor.tryReserve()).isFalse();
        executor.release();
        assertThat(h.deferred).isEmpty();
    }

    private static Map<String, Object> payload(IpdAgentRunEvent event) {
        try {
            return new ObjectMapper().readValue(event.getPayload(), new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertCode(Runnable call, ApiV1ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(IpdBusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
