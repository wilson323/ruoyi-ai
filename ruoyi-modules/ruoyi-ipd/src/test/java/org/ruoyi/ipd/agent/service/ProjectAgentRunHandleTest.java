package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;
import org.ruoyi.ipd.agent.support.InMemoryArtifactVersionStore;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        handle.onSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS"));
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
        withArtifacts.onText("# 竞品分析\n竞品分析正文");
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
    @DisplayName("产物校验驻留：BLOCK 缺口停 VERIFYING、写 VERIFY_GAPS 步骤、无终态事件")
    void blockingGapsResideInVerifyingWithoutTerminal() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        withArtifacts.onText("草稿正文，无标题且 TODO 待补充 TODO");
        withArtifacts.onComplete();

        IpdAgentRun after = store.findRun(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("VERIFYING");
        assertThat(after.getErrorCode()).isNull();
        List<IpdAgentRunEvent> events = store.events(run.getId());
        assertThat(events).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("TEXT_DELTA", "ARTIFACT", "STEP");
        assertThat(events.get(2).getPayload()).contains("VERIFY_GAPS").contains("doc.heading.structure");
        assertThat(events.stream().filter(e -> AgentEventType.valueOf(e.getEventType()).isTerminal()))
            .as("驻留态不写终态事件，复检或取消才收口").isEmpty();
        assertThat(closedCallbacks).hasValue(1);
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
        handle.onSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS"));

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
            .findFirst().orElseThrow().getPayload()).contains("MISSING_RETRIEVAL_DISCLOSURE");
    }

    @Test
    @DisplayName("检索零命中但正文写明未取得：仍可落草稿")
    void zeroHitWithDisclosurePersistsArtifact() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        withArtifacts.onToolCall("call-1", "project_knowledge_search");
        withArtifacts.onSource(Map.of("hits", 0));
        withArtifacts.onText("# 公开价格\n公开价格未取得");
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
            .findFirst().orElseThrow().getPayload()).contains("GATE_AUTHORITY_CLAIM");
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
            "C02", "# 结论\n用途未声明，不阻塞；按默认四维齐全、篇幅克制。竞品名单未取得，暂不比较。",
            "C01", "# 缺项表\n| 编号 | 缺项 | 影响 | 处理 |\n| G-06 | 目的裁剪（用户声明时） | 阻塞步骤2 | 未声明则四维齐全、篇幅克制。 |");
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

    @Test
    @DisplayName("没有正文拒绝成功；有正文且不接产物存储仍沿原事件链")
    void emptyCompletionFailsButTextWithoutArtifactStoreSucceeds() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        withArtifacts.onComplete();

        assertThat(artifacts.size()).isZero();
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("ERROR");
        assertThat(store.findRun(run.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(store.findRun(run.getId()).orElseThrow().getErrorCode()).isEqualTo(ProjectAgentCompletionGate.REJECTED);

        IpdAgentRun another = IpdAgentRun.builder().tenantId(AgentTestFixtures.TENANT)
            .projectId(AgentTestFixtures.PROJECT_ID).personId(AgentTestFixtures.ACTOR.id())
            .agentId("ipd_project_agent").status(AgentRunStatus.RUNNING.name())
            .idempotencyKey("key-handle-empty-store").build();
        store.insertRun(another);
        ProjectAgentRunHandle noStore = new ProjectAgentRunHandle(another, store, AgentTestFixtures.MAPPER,
            clock::get, () -> { });
        noStore.onText("只有回答");
        noStore.onComplete();
        assertThat(store.findRun(another.getId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(store.events(another.getId())).extracting(IpdAgentRunEvent::getEventType)
            .containsExactly("TEXT_DELTA", "RUN_FINISHED")
            .doesNotContain("ARTIFACT");
    }

    @Test
    @DisplayName("产物插入抛错：终态是 FAILED，不保留 SUCCEEDED，不写 RUN_FINISHED")
    void artifactInsertExceptionFailsTheRun() {
        AtomicInteger binds = new AtomicInteger();
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store,
            new InsertControlStore(true, false), AgentTestFixtures.MAPPER, clock::get,
            closedCallbacks::incrementAndGet);
        withArtifacts.whenSucceeded(text -> binds.incrementAndGet());
        withArtifacts.onText("应落库的正文");
        withArtifacts.onComplete();

        IpdAgentRun after = store.findRun(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrorCode()).isEqualTo("ARTIFACT_PERSIST");
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType)
            .contains("ERROR")
            .doesNotContain("RUN_FINISHED", "ARTIFACT");
        assertThat(binds).hasValue(0);
        assertThat(closedCallbacks).hasValue(1);
    }

    @Test
    @DisplayName("产物插入返回失败：终态是 FAILED，不把重复插入当成成功")
    void artifactInsertRejectedFailsTheRun() {
        ProjectAgentRunHandle withArtifacts = new ProjectAgentRunHandle(run, store,
            new InsertControlStore(false, true), AgentTestFixtures.MAPPER, clock::get,
            closedCallbacks::incrementAndGet);
        withArtifacts.onText("应落库的正文");
        withArtifacts.onComplete();

        IpdAgentRun after = store.findRun(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrorCode()).isEqualTo("ARTIFACT_PERSIST");
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType)
            .doesNotContain("RUN_FINISHED", "ARTIFACT");
    }

    /**
     * 只控制 insert 的成败，其余方法委托内存存储。
     */
    private static final class InsertControlStore implements ArtifactVersionStore {
        private final InMemoryArtifactVersionStore inner = new InMemoryArtifactVersionStore();
        private final boolean throwOnInsert;
        private final boolean rejectInsert;

        private InsertControlStore(boolean throwOnInsert, boolean rejectInsert) {
            this.throwOnInsert = throwOnInsert;
            this.rejectInsert = rejectInsert;
        }

        @Override
        public boolean insert(IpdAgentArtifactVersion version) {
            if (throwOnInsert) {
                throw new IllegalStateException("artifact insert failed");
            }
            if (rejectInsert) {
                return false;
            }
            return inner.insert(version);
        }

        @Override
        public Optional<IpdAgentArtifactVersion> findById(Long versionId) {
            return inner.findById(versionId);
        }

        @Override
        public Optional<IpdAgentArtifactVersion> findLatest(Long runId, String artifactId) {
            return inner.findLatest(runId, artifactId);
        }

        @Override
        public boolean markApplied(Long versionId, Long documentId) {
            return inner.markApplied(versionId, documentId);
        }

        @Override
        public Set<Long> findRunIdsByContent(String tenantId, String text) {
            return inner.findRunIdsByContent(tenantId, text);
        }

        @Override
        public List<IpdAgentArtifactVersion> listByRunIds(Collection<Long> runIds) {
            return inner.listByRunIds(runIds);
        }
    }
    @Test
    void fullEvidenceStaysInternalAndArtifactUsesDeliveredBody() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        run.setConfigSnapshot("{\"outputContractVersion\":1}");
        ProjectAgentRunHandle subject = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        subject.setDocumentVerifier(row -> {});
        String delivered = "# 费用结论\n费用率12.83%。";
        var document = IpdAgentArtifactVersion.builder().tenantId(run.getTenantId()).runId(run.getId())
            .artifactId("delivered-report").versionNo(1).title("report.md").content(delivered)
            .contentSha256(org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex(delivered)).status("DRAFT").delFlag("0").build();
        artifacts.insert(document); subject.onDocument(document.getId());
        java.util.concurrent.atomic.AtomicReference<String> bound = new java.util.concurrent.atomic.AtomicReference<>();
        subject.whenSucceeded(bound::set);
        subject.onToolCall("c1", "project_knowledge_search");
        subject.onTrustedSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "preview", "短预览",
            "citationText", "原报价12.83%" + "授权正文".repeat(2200) + "不应持久化的全文尾部"));
        subject.onText("# 费用结论\n");
        subject.onText("<thi");
        subject.onText("nk>错误试算118.69</thi");
        subject.onText("nk>费用率12.83%。<think>未闭合思考13.09");
        subject.onComplete();
        assertThat(store.findRun(run.getId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(artifacts.listByRunIds(List.of(run.getId())).get(0).getContent())
            .isEqualTo("# 费用结论\n费用率12.83%。");
        assertThat(bound.get()).isEqualTo("# 费用结论\n费用率12.83%。");
        assertThat(store.events(run.getId()).stream().filter(e -> "SOURCE".equals(e.getEventType())))
            .singleElement().satisfies(e -> {
                assertThat(e.getPayload()).contains("citationQuote", "citationQuoteSha256", "citationQuoteTruncated")
                    .doesNotContain("citationText", "不应持久化的全文尾部", "错误试算");
                try {
                    var payload = AgentTestFixtures.MAPPER.readTree(e.getPayload());
                    assertThat(payload.path("citationQuote").asText()).hasSize(8000);
                    assertThat(payload.path("citationQuoteSha256").asText()).isEqualTo(
                        org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex(payload.path("citationQuote").asText()));
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            });
        assertThat(artifacts.size()).isEqualTo(1);
    }

    @Test
    void thinkingOnlyCannotFinishSuccessfully() {
        InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
        ProjectAgentRunHandle subject = new ProjectAgentRunHandle(run, store, artifacts,
            AgentTestFixtures.MAPPER, clock::get, closedCallbacks::incrementAndGet);
        subject.onText("<think>只有思考，没有交付正文</think>");
        subject.onComplete();
        IpdAgentRun after = store.findRun(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrorCode()).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(artifacts.size()).isZero();
        assertThat(store.events(run.getId())).extracting(IpdAgentRunEvent::getEventType)
            .contains("ERROR").doesNotContain("ARTIFACT", "RUN_FINISHED");
    }

}
