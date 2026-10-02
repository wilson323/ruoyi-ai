package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import org.ruoyi.ipd.agent.support.InMemoryArtifactVersionStore;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行句柄：seq 单调、文本合并、恰一终态、取消竞争与迟到帧丢弃、跨节点取消探测、seq 去重。
 * 存储为合同同构内存替身（非运行态证据）。
 */
@Tag("dev")
class ProjectAgentRunHandleTest {

    private final InMemoryAgentRunStore store = new InMemoryAgentRunStore();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicInteger closedCallbacks = new AtomicInteger();
    private IpdAgentRun run;
    private ProjectAgentRunHandle handle;

    @BeforeEach
    void setUp() {
        run = IpdAgentRun.builder().tenantId(AgentTestFixtures.TENANT).projectId(AgentTestFixtures.PROJECT_ID)
            .personId(AgentTestFixtures.ACTOR.id()).agentId("ipd_project_agent")
            .status(AgentRunStatus.RUNNING.name()).idempotencyKey("key-handle-0001").build();
        store.insertRun(run);
        handle = new ProjectAgentRunHandle(run, store, AgentTestFixtures.MAPPER, clock::get,
            closedCallbacks::incrementAndGet);
    }

    @Test
    @DisplayName("成功路径：seq 从 1 单调递增，文本合并落库，恰一个 RUN_FINISHED 且位于最后")
    void successPathHasMonotonicSeqAndSingleTerminal() {
        handle.append(AgentEventType.RUN_STARTED, Map.of("agentId", "ipd_project_agent"));
        handle.onText("竞品");
        handle.onText("分析");
        handle.onToolCall("call-1", "project_knowledge_search");
        handle.onSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "竞品分析",
            "sourceEvidence", List.of(Map.of("sourceName", "测试知识片段", "documentId", "fixture-fragment",
                "knowledgeId", "fixture-knowledge", "sourceType", "KNOWLEDGE_FRAGMENT",
                "reviewStatus", "NOT_PROJECT_DOCUMENT"))));
        handle.onComplete();
        handle.onComplete();
        handle.onError("STREAM_ERROR");

        List<IpdAgentRunEvent> events = store.events(run.getId());
        assertThat(events).extracting(IpdAgentRunEvent::getSeq).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("RUN_STARTED", "TEXT_DELTA", "TOOL_CALL", "SOURCE", "RUN_FINISHED");
        assertThat(events.get(1).getPayload()).contains("竞品分析");
        assertThat(events.stream().filter(e -> AgentEventType.valueOf(e.getEventType()).isTerminal())).hasSize(1);
        assertThat(store.findRun(run.getId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(closedCallbacks).hasValue(1);
    }

    @Test
    @DisplayName("成功路径：有产物存储时落 ARTIFACT 草稿后再写 RUN_FINISHED")
    void successPathPersistsArtifactBeforeTerminal() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        withArtifacts.onText("竞品分析正文");
        withArtifacts.onComplete();

        List<IpdAgentRunEvent> events = store.events(run.getId());
        assertThat(events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("TEXT_DELTA", "ARTIFACT", "RUN_FINISHED");
        assertThat(artifacts.size()).isEqualTo(1);
        assertThat(events.get(1).getPayload()).contains("artifactId").contains("contentHash").contains("\"version\":1");
        String versionId = String.valueOf(artifacts.listByRunIds(List.of(run.getId())).get(0).getId());
        assertThat(events.get(1).getPayload()).contains("\"versionId\":\"" + versionId + "\"");
        assertThat(versionId).doesNotContain("artifactId");
    }

    @Test
    @DisplayName("失败路径：只有 ERROR 终态、无 RUN_FINISHED，错误码与安全文案落库")
    void failurePathWritesOnlyError() {
        handle.onText("部分输出");
        handle.onError("RUN_TIMEOUT");
        handle.onComplete();

        List<IpdAgentRunEvent> events = store.events(run.getId());
        assertThat(events).extracting(IpdAgentRunEvent::getEventType).containsExactly("TEXT_DELTA", "ERROR");
        assertThat(events.get(1).getPayload()).contains("RUN_TIMEOUT").contains("运行超时");
        IpdAgentRun after = store.findRun(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrorCode()).isEqualTo("RUN_TIMEOUT");
    }

    @Test
    @DisplayName("取消竞争：CANCEL_REQUESTED 后迟到的完成收口为 CANCELLED，迟到帧不写业务事件")
    void lateCompletionAfterCancelRequestBecomesCancelled() {
        handle.onText("取消前");
        store.forceStatus(run.getId(), AgentRunStatus.CANCEL_REQUESTED);
        handle.onComplete();
        handle.onText("迟到文本");
        handle.onToolCall("late", "project_knowledge_search");
        handle.onSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "竞品分析",
            "sourceEvidence", List.of(Map.of("sourceName", "测试知识片段", "documentId", "fixture-fragment",
                "knowledgeId", "fixture-knowledge", "sourceType", "KNOWLEDGE_FRAGMENT",
                "reviewStatus", "NOT_PROJECT_DOCUMENT"))));

        List<IpdAgentRunEvent> events = store.events(run.getId());
        assertThat(events).extracting(IpdAgentRunEvent::getEventType).containsExactly("TEXT_DELTA", "RUN_FINISHED");
        assertThat(events.get(1).getPayload()).contains("CANCELLED");
        assertThat(store.findRun(run.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("CAS 竞争：迁移前被他方改为 CANCEL_REQUESTED，重读后收口 CANCELLED 且只写一个终态")
    void finishRetriesAfterConcurrentCancelRequest() {
        store.beforeTransition = () -> store.forceStatus(run.getId(), AgentRunStatus.CANCEL_REQUESTED);
        boolean wrote = handle.finish(AgentRunStatus.SUCCEEDED, null);

        assertThat(wrote).isTrue();
        assertThat(store.findRun(run.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType).containsExactly("RUN_FINISHED");
    }

    @Test
    @DisplayName("本机取消：cancel() 收口 CANCELLED，之后任何回调都不再写入")
    void localCancelClosesWriter() {
        store.forceStatus(run.getId(), AgentRunStatus.CANCEL_REQUESTED);
        assertThat(handle.cancel()).isTrue();
        assertThat(handle.cancel()).isFalse();
        handle.onComplete();
        handle.onError("STREAM_ERROR");

        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType).containsExactly("RUN_FINISHED");
        assertThat(closedCallbacks).hasValue(1);
    }

    @Test
    @DisplayName("跨节点取消：超过探测间隔后读到 CANCEL_REQUESTED 即收口，不再写后续帧")
    void crossNodeCancelIsDetectedOnNextAppend() {
        handle.onStep("MODEL_CALL", Map.of());
        store.forceStatus(run.getId(), AgentRunStatus.CANCEL_REQUESTED);
        clock.addAndGet(ProjectAgentRunHandle.STATUS_PROBE_INTERVAL_MS + 1);
        handle.onToolCall("after", "project_knowledge_search");

        assertThat(handle.isClosed()).isTrue();
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("STEP", "RUN_FINISHED");
        assertThat(store.findRun(run.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("seq 去重：同 (run_id, seq) 的重复写入被唯一键拒绝，不产生第二行")
    void duplicateSeqIsRejectedByUniqueKey() {
        store.appendEvent(ProjectAgentRunEvents.of(run.getId(), AgentTestFixtures.TENANT, 11L, 1L,
            AgentEventType.STEP, "{}", new Date()));
        ProjectAgentRunHandle second = new ProjectAgentRunHandle(run, store, AgentTestFixtures.MAPPER, clock::get, null);
        second.onStep("MODEL_CALL", Map.of());
        boolean duplicated = store.appendEvent(ProjectAgentRunEvents.of(run.getId(), AgentTestFixtures.TENANT, 11L, 2L,
            AgentEventType.STEP, "{}", new Date()));

        assertThat(duplicated).isFalse();
        assertThat(store.duplicateEvents).hasValue(1);
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getSeq).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("长文本按阈值合并为多段 TEXT_DELTA，拼接后与原文一致")
    void longTextFlushesInChunks() {
        String piece = "字".repeat(150);
        handle.onText(piece);
        handle.onText(piece);
        handle.onText("尾");
        handle.onComplete();

        List<IpdAgentRunEvent> texts = store.events(run.getId()).stream()
            .filter(e -> "TEXT_DELTA".equals(e.getEventType())).toList();
        assertThat(texts).hasSize(2);
        String joined = texts.stream().map(e -> e.getPayload().replaceAll("^\\{\"text\":\"|\"}$", ""))
            .reduce("", String::concat);
        assertThat(joined).isEqualTo(piece + piece + "尾");
    }

    @Test
    @DisplayName("未满字符阈值时，超过刷新间隔的后续增量会先落库，不等运行结束")
    void shortTextFlushesWhenIntervalElapses() {
        handle.onText("甲");
        assertThat(store.events(run.getId()).stream().filter(e -> "TEXT_DELTA".equals(e.getEventType()))).isEmpty();

        clock.addAndGet(ProjectAgentConstants.TEXT_FLUSH_INTERVAL_MS);
        handle.onText("乙");

        List<IpdAgentRunEvent> texts = store.events(run.getId()).stream()
            .filter(e -> "TEXT_DELTA".equals(e.getEventType())).toList();
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0).getPayload()).contains("甲乙");
    }

    @Test
    @DisplayName("检索零命中且正文未写未取得：失败收口，不落 ARTIFACT")
    void zeroHitWithoutDisclosureFailsBeforeArtifact() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        withArtifacts.onToolCall("call-1", "project_knowledge_search");
        withArtifacts.onSource(Map.of("hits", 0));
        withArtifacts.onText("竞品价格为 12 元");
        withArtifacts.onComplete();

        assertThat(artifacts.size()).isZero();
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType)
            .contains("ERROR")
            .doesNotContain("ARTIFACT", "RUN_FINISHED");
        IpdAgentRun after = store.findRun(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrorCode()).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(store.events(run.getId()).stream().filter(e -> "ERROR".equals(e.getEventType()))
            .findFirst().orElseThrow().getPayload())
            .contains("\"completionReason\":\"MISSING_RETRIEVAL_DISCLOSURE\"")
            .doesNotContain("竞品价格", "12 元");
    }

    @Test
    @DisplayName("检索零命中但正文写明未取得：仍可落草稿")
    void zeroHitWithDisclosurePersistsArtifact() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        withArtifacts.onToolCall("call-1", "project_knowledge_search");
        withArtifacts.onSource(Map.of("hits", 0));
        withArtifacts.onText("公开价格未取得");
        withArtifacts.onComplete();

        assertThat(artifacts.size()).isEqualTo(1);
        assertThat(store.findRun(run.getId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("正文宣称评审通过：即使有命中也不落 ARTIFACT")
    void gateClaimFailsEvenWhenHitsExist() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        withArtifacts.onToolCall("call-1", "project_knowledge_search");
        withArtifacts.onSource(Map.of("hits", 3));
        withArtifacts.onText("建议 Gate 签署，评审通过");
        withArtifacts.onComplete();

        assertThat(artifacts.size()).isZero();
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType)
            .doesNotContain("ARTIFACT", "RUN_FINISHED");
        assertThat(store.findRun(run.getId()).orElseThrow().getErrorCode())
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(store.events(run.getId()).stream().filter(e -> "ERROR".equals(e.getEventType()))
            .findFirst().orElseThrow().getPayload())
            .contains("\"completionReason\":\"GATE_AUTHORITY_CLAIM\"")
            .doesNotContain("建议 Gate 签署");
    }
    @Test
    @DisplayName("C02 使用运行记录动作合同：可选用途冒作阻塞时拒绝草稿")
    void c02OptionalPurposeBlockerCannotPersistArtifact() {
        IpdAgentRun c02 = IpdAgentRun.builder().tenantId(AgentTestFixtures.TENANT)
            .projectId(AgentTestFixtures.PROJECT_ID).personId(AgentTestFixtures.ACTOR.id())
            .agentId("ipd_project_agent").actionCode("C02").status(AgentRunStatus.RUNNING.name())
            .idempotencyKey("key-c02-default-contract").build();
        store.insertRun(c02);
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle c02Handle = new ProjectAgentRunHandle(c02, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, () -> { });
        c02Handle.onText("| 编号 | 缺项 | 影响 | 处理 |\n| G-06 | 目的裁剪（用户声明时） | 阻塞 C02 步骤2 | 未声明则按规则四维齐全、篇幅克制。 |");
        c02Handle.onComplete();
        c02Handle.onComplete();

        assertThat(artifacts.size()).isZero();
        assertThat(store.findRun(c02.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(store.findRun(c02.getId()).orElseThrow().getErrorCode())
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(store.events(c02.getId())).extracting(IpdAgentRunEvent::getEventType)
            .doesNotContain("ARTIFACT", "RUN_FINISHED");
        assertThat(store.events(c02.getId()).stream().filter(e -> "ERROR".equals(e.getEventType())))
            .singleElement().satisfies(e -> assertThat(e.getPayload())
                .contains("SKILL_CONTRACT_MISMATCH").doesNotContain("目的裁剪"));
    }

    @Test
    @DisplayName("C02 默认四维可生成草稿；其他动作不借用 C02 用途规则")
    void recordedActionKeepsDefaultScopeAndOtherActionsCompatible() {
        Map<String, String> cases = Map.of(
            "C02", "用途未声明，不阻塞；按默认四维齐全、篇幅克制。竞品名单未取得，暂不比较。",
            "C01", "| 编号 | 缺项 | 影响 | 处理 |\n| G-06 | 目的裁剪（用户声明时） | 阻塞步骤2 | 未声明则四维齐全、篇幅克制。 |");
        cases.forEach((action, body) -> {
            IpdAgentRun recorded = IpdAgentRun.builder().tenantId(AgentTestFixtures.TENANT)
                .projectId(AgentTestFixtures.PROJECT_ID).personId(AgentTestFixtures.ACTOR.id())
                .agentId("ipd_project_agent").actionCode(action).status(AgentRunStatus.RUNNING.name())
                .idempotencyKey("key-default-contract-" + action).build();
            store.insertRun(recorded);
            InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
            ProjectAgentRunHandle scoped = new ProjectAgentRunHandle(recorded, store, artifacts,
                AgentTestFixtures.MAPPER, clock::get, () -> { });
            scoped.onText(body);
            scoped.onComplete();
            assertThat(store.findRun(recorded.getId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
            assertThat(artifacts.size()).isEqualTo(1);
        });
    }

}
