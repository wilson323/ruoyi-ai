package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.IpdAgentMemory;
import org.ruoyi.ipd.mapper.IpdAgentMemoryMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆（官方 {@code LongTermMemory} SPI 自实现）的行为单测。
 *
 * <p>2026-10-02 owner 决策：作用域=项目+人（个人记忆），内容=LLM 抽取的可复用事实与偏好，
 * 权威性=非权威（AGENTS.md:77「记忆不得自动成为业务权威」）。
 */
@Tag("dev")
@DisplayName("长期记忆：项目+人作用域 + 非权威标注 + 敏感内容拦截")
class ProjectScopedLongTermMemoryTest {

    private static final Long PROJECT = 1001L;
    private static final Long PERSON = 900101L;
    private static final Long RUN = 555L;

    /** 产出固定抽取结果的脚本模型。 */
    private static Model modelReturning(String text) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("extract-stub");
        when(model.stream(any(), any(), any())).thenReturn(
            reactor.core.publisher.Flux.just(ChatResponse.builder()
                .finishReason("stop")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build()));
        return model;
    }

    private static List<Msg> userSaid(String text) {
        return List.of(Msg.builder().role(MsgRole.USER).textContent(text).build());
    }

    @Test
    @DisplayName("召回文本必须带「非权威」标注——红线由代码保证，不依赖模型或下游遵守")
    void recallTextCarriesNonAuthoritativeLabel() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.recallForScope(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
            IpdAgentMemory.builder().kind(IpdAgentMemory.KIND_PREFERENCE)
                .content("要表格，不要长段落").status(IpdAgentMemory.STATUS_CANDIDATE).build(),
            IpdAgentMemory.builder().kind(IpdAgentMemory.KIND_FACT)
                .content("已确认走方案 B").status(IpdAgentMemory.STATUS_PROMOTED).build()));

        String recalled = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, null)
            .retrieve(userSaid("再来一份").get(0)).block(Duration.ofSeconds(10));

        assertThat(recalled).isNotNull();
        assertThat(recalled).contains("非权威");
        assertThat(recalled).contains("不是业务事实");
        assertThat(recalled).contains("要表格，不要长段落");
        assertThat(recalled).contains("[PREFERENCE]").contains("[FACT]");
    }

    @Test
    @DisplayName("无记忆时返回 null（由 SDK 跳过注入），不塞空壳文本")
    void recallReturnsNullWhenEmpty() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.recallForScope(anyLong(), anyLong(), anyInt())).thenReturn(List.of());

        String recalled = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, null)
            .retrieve(userSaid("你好").get(0)).block(Duration.ofSeconds(10));

        assertThat(recalled).isNull();
    }

    @Test
    @DisplayName("召回必须同时按 project_id + person_id 过滤——跨用户泄漏的唯一防线")
    void recallAlwaysFiltersByProjectAndPerson() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.recallForScope(anyLong(), anyLong(), anyInt())).thenReturn(List.of());

        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, null)
            .retrieve(userSaid("x").get(0)).block(Duration.ofSeconds(10));

        // 精确匹配：两个维度都必须是本实例构造期绑定的值，任一被替换即为跨用户泄漏
        verify(mapper).recallForScope(PROJECT, PERSON, IpdAgentMemory.RECALL_LIMIT);
    }

    @Test
    @DisplayName("record：抽取结果按项目+人入库，幂等口去重")
    void recordPersistsScopedAndDeduplicated() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.insertIgnoreDuplicate(any())).thenReturn(1);
        Model model = modelReturning("MEM|PREFERENCE|要表格\nMEM|FACT|已确认走方案 B");

        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, model)
            .record(userSaid("给我表格；方案定了走 B")).block(Duration.ofSeconds(15));
        // 先确认模型确实被调到了——否则「零交互」无从判断是抽取没跑还是解析没出条目
        verify(model).stream(any(), any(), any());

        var captor = org.mockito.ArgumentCaptor.forClass(IpdAgentMemory.class);
        verify(mapper, times(2)).insertIgnoreDuplicate(captor.capture());
        for (IpdAgentMemory row : captor.getAllValues()) {
            assertThat(row.getProjectId()).isEqualTo(PROJECT);
            assertThat(row.getPersonId()).isEqualTo(PERSON);
            assertThat(row.getRunId()).isEqualTo(RUN);
            assertThat(row.getStatus()).isEqualTo(IpdAgentMemory.STATUS_CANDIDATE);
            assertThat(row.getSourceDigest()).isNotBlank();
        }
        assertThat(captor.getAllValues().stream().map(IpdAgentMemory::getKind))
            .containsExactlyInAnyOrder(IpdAgentMemory.KIND_PREFERENCE, IpdAgentMemory.KIND_FACT);
    }

    @Test
    @DisplayName("record：含凭据的抽取结果一律拒收（模型不可靠时的兜底闸）")
    void recordRejectsSensitiveContent() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);

        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
                modelReturning("MEM|FACT|数据库口令 password=Ruoyi@2024\nMEM|PREFERENCE|要表格"))
            .record(userSaid("密码是多少")).block(Duration.ofSeconds(15));

        var captor = org.mockito.ArgumentCaptor.forClass(IpdAgentMemory.class);
        verify(mapper, times(1)).insertIgnoreDuplicate(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("要表格");
    }

    @Test
    @DisplayName("record：有输入但缺模型时明确失败")
    void recordFailsWhenModelUnavailable() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        assertThatThrownBy(() -> new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, null)
            .record(userSaid("x")).block(Duration.ofSeconds(5)))
            .hasMessageContaining("Long-term memory record failed");
        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    @Test
    @DisplayName("record：模型失败传播脱敏错误，不能冒充成功")
    void recordPropagatesModelError() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        Model broken = mock(Model.class);
        when(broken.getModelName()).thenReturn("broken");
        when(broken.stream(any(), any(), any()))
            .thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("secret-model-key")));

        assertThatThrownBy(() -> new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, broken)
            .record(userSaid("x")).block(Duration.ofSeconds(15)))
            .hasMessageContaining("Long-term memory record failed")
            .hasMessageNotContaining("secret-model-key")
            .hasNoCause();

        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    @Test void actualExtractionConsumesMeteredCumulativeUsageOnce() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.insertIgnoreDuplicate(any())).thenReturn(1);
        Model delegate = mock(Model.class);
        when(delegate.stream(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.just(
            ChatResponse.builder().id("extract-response").usage(new io.agentscope.core.model.ChatUsage(11, 2, 0))
                .content(List.of(TextBlock.builder().text("MEM|PREFERENCE|").build())).build(),
            ChatResponse.builder().id("extract-response").usage(new io.agentscope.core.model.ChatUsage(11, 7, 0))
                .content(List.of(TextBlock.builder().text("要表格").build())).build()));
        var recorder = new ProjectAgentMeteredModelTest.Recorder();
        var ledger = mock(org.ruoyi.ipd.service.AiModelUsageLedgerService.class);
        var identity = new org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity(123L, "fixture", "fixture-model");
        var sink = new ProjectAgentUsageSink(recorder, ledger, List.of(identity), "person", "run");
        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
            new ProjectAgentMeteredModel(delegate, sink, sink, identity))
            .record(userSaid("要表格")).block(Duration.ofSeconds(5));
        assertThat(recorder.ends).hasSize(1);
        assertThat(recorder.total("inputTokens")).isEqualTo(11);
        assertThat(recorder.total("outputTokens")).isEqualTo(7);
        verify(ledger).recordUsage(123L, "person", "project_agent", 11, 7, 0L, "ok", "run");
        verify(mapper).insertIgnoreDuplicate(any());
    }

    @Test void actualExtractionWithoutUsageDoesNotInventLedgerTokens() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        var recorder = new ProjectAgentMeteredModelTest.Recorder();
        var ledger = mock(org.ruoyi.ipd.service.AiModelUsageLedgerService.class);
        var identity = new org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity(123L, "fixture", "fixture-model");
        var sink = new ProjectAgentUsageSink(recorder, ledger, List.of(identity), "person", "run");
        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
            new ProjectAgentMeteredModel(modelReturning("MEM|NONE|空"), sink, sink, identity))
            .record(userSaid("你好")).block(Duration.ofSeconds(5));
        assertThat(recorder.ends).hasSize(1);
        assertThat(recorder.ends.get(0)).doesNotContainKeys("inputTokens", "outputTokens");
        org.mockito.Mockito.verifyNoInteractions(ledger);
        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    @Test void extractionRetainsSubscriberContext() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        Model delegate = mock(Model.class);
        AtomicReference<String> observed = new AtomicReference<>();
        when(delegate.stream(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.deferContextual(ctx -> {
            observed.set(ctx.get("runDeadline"));
            return reactor.core.publisher.Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("MEM|NONE|空").build())).build());
        }));
        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, delegate)
            .record(userSaid("你好")).contextWrite(ctx -> ctx.put("runDeadline", "owned-deadline"))
            .block(Duration.ofSeconds(5));
        assertThat(observed.get()).isEqualTo("owned-deadline");
        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    @Test void outerDeadlineCancelsExtractionWithoutPersistence() throws Exception {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        Model delegate = mock(Model.class);
        java.util.concurrent.CountDownLatch cancelled = new java.util.concurrent.CountDownLatch(1);
        when(delegate.stream(any(), any(), any())).thenReturn(reactor.core.publisher.Flux.<ChatResponse>never()
            .doOnCancel(cancelled::countDown));
        assertThatThrownBy(() -> new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, delegate)
            .record(userSaid("你好")).timeout(Duration.ofMillis(100)).block(Duration.ofSeconds(3)))
            .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(cancelled.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    @Test void recallDatabaseFailureIsNotAnEmptyResult() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.recallForScope(anyLong(), anyLong(), anyInt()))
            .thenThrow(new IllegalStateException("secret-db-url"));
        assertThatThrownBy(() -> new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, null)
            .retrieve(userSaid("x").get(0)).block(Duration.ofSeconds(5)))
            .hasMessageContaining("Long-term memory retrieve failed")
            .hasMessageNotContaining("secret-db-url").hasNoCause();
    }

    @Test void persistenceFailureIsNotSuccessfulRecording() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.insertIgnoreDuplicate(any())).thenThrow(new IllegalStateException("secret-db-url"));
        assertThatThrownBy(() -> new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
            modelReturning("MEM|PREFERENCE|要表格"))
            .record(userSaid("要表格")).block(Duration.ofSeconds(5)))
            .hasMessageContaining("Long-term memory record failed")
            .hasMessageNotContaining("secret-db-url").hasNoCause();
    }

    @Test void emptyInputDoesNotRequireModel() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, null)
            .record(List.of()).block(Duration.ofSeconds(5));
        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    @Test
    @DisplayName("构造期即绑定作用域：缺 project/person 直接拒绝（fail-closed）")
    void constructorRejectsMissingScope() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> new ProjectScopedLongTermMemory(null, PERSON, RUN, mapper, null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> new ProjectScopedLongTermMemory(PROJECT, null, RUN, mapper, null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, null, null));
    }

    // ---------------------------------------------------------------------
    // run 2106378468009717761（2026-10-03）：抽取流 93ae5117 挂死后空转满旧 30s
    // 才被外层发现，errorType=TimeoutException。下列测试把「空闲期限 / 有限重试 /
    // 可重试类别 / 结果记账」四件事钉死。
    // ---------------------------------------------------------------------

    /** 吐了第一片之后永远不再来片——真实故障的流形状。 */
    private static reactor.core.publisher.Flux<ChatResponse> stallingAfterFirstChunk() {
        return reactor.core.publisher.Flux.concat(
            reactor.core.publisher.Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("MEM|PREFERENCE|要表格").build()))
                .build()),
            reactor.core.publisher.Flux.never());
    }

    /**
     * 「慢但活着」不等于「死了」——本轮三次参数返工的共同内核，必须有行为测试钉住。
     *
     * <p>初版把流空闲期限设成 5 秒、兜底总期限 25 秒，两次都因为「没有对应实测」返工：
     * 真实服务实测记忆抽取最长 14 秒，5 秒会掐断健康调用；25 秒只剩 4.5 秒余量，
     * 一次 20 秒的健康抽取会被砍成假的 WRITE_FAILED 回执。教训不是「把参数调大」，
     * 而是<b>两个期限抓的根本不是同一件事</b>：空闲期限抓「死流」，总期限只抓「活着但异常慢」。
     *
     * <p>本测试用一条「每 2 秒来一片、共约 6 秒」的慢流钉住：总耗时超过空闲期限（10s 的一半），
     * 但每片间隔（2s）远小于空闲期限，因此必须<b>正常完成</b>。
     * 若有人把总期限调回与慢流同量级、或误把空闲期限当成总期限，这里立刻红。
     * 与 {@code stallingAfterFirstChunk()} 那条（吐一片后永不��来，必被掐断）构成对照。
     */
    @Test
    @DisplayName("慢但持续有数据的流不被掐断——空闲期限抓的是死流不是慢流")
    void slowButAliveStreamIsNotAbandoned() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.insertIgnoreDuplicate(any())).thenReturn(1);
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("test");
        // 3 片 × 2 秒间隔 = 总耗时约 6 秒；每片都到，间隔 2s < 空闲期限 10s
        when(delegate.stream(any(), any(), any())).thenReturn(
            reactor.core.publisher.Flux.interval(java.time.Duration.ofSeconds(2))
                .take(3)
                .map(i -> ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("MEM|PREFERENCE|要表格").build()))
                    .build()));

        var memory = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, delegate);
        long start = System.nanoTime();
        memory.record(userSaid("要表格")).block(Duration.ofSeconds(30));
        long elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000L;

        assertThat(memory.lastOutcome().failure())
            .as("慢流必须正常完成；若被掐断说明空闲期限被误当成总期限")
            .isNull();
        assertThat(memory.lastOutcome().written()).isTrue();
        assertThat(memory.lastOutcome().extracted()).isEqualTo(1);
        assertThat(memory.lastOutcome().saved()).isEqualTo(1);
        // 确认这条流真的「慢」过：否则本测试可能在参数收紧后仍然通过，失去对照意义。
        assertThat(elapsedSeconds)
            .as("测试前提：这条流必须慢于空闲期限的一半，否则测不出区分")
            .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("挂死流在空闲期限内被掐断并立刻重试——不等整轮兜底期限（旧实现必红）")
    void stallingStreamIsAbandonedOnIdleTimeoutAndRetried() throws Exception {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        Model delegate = mock(Model.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch retriedOnce = new CountDownLatch(1);
        when(delegate.stream(any(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() >= 2) {
                retriedOnce.countDown();
            }
            return stallingAfterFirstChunk();
        });

        var memory = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, delegate);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch terminated = new CountDownLatch(1);
        memory.record(userSaid("要表格")).subscribe(
            v -> terminated.countDown(),
            t -> { failure.set(t); terminated.countDown(); },
            terminated::countDown);

        // 证伪点：第一次尝试在空闲期限（EXTRACT_IDLE_TIMEOUT，当前 10s）后被放弃并触发第二次调用。
        // 窗口取 20s：必须大于「空闲期限 + 重试退避」，同时**必须显著小于旧实现的 30s**——
        // 否则这条测试就不再能证伪「回退到 Flux.timeout(30s)」这个旧行为。20s 满足两个约束。
        assertThat(retriedOnce.await(20, TimeUnit.SECONDS))
            .as("第一次尝试应在空闲期限内被掐断并触发第二次模型调用")
            .isTrue();

        assertThat(terminated.await(25, TimeUnit.SECONDS))
            .as("挂死流最终必须以失败终止，不得无限挂起")
            .isTrue();
        assertThat(failure.get()).isNotNull()
            .hasMessageContaining("Long-term memory record failed")
            .hasMessageNotContaining("null");
        // 1 次首发 + EXTRACT_RETRIES(1) 次重试 = 2 次；两次都在空闲期限被掐断后按重试耗尽而终止，
        // 不应触及 25s 整轮兜底（最坏 2×10s + 0.5s = 20.5s）。
        assertThat(calls.get()).isEqualTo(2);
        assertThat(memory.lastOutcome().failure()).isNotNull();
        assertThat(memory.lastOutcome().written()).isFalse();
        assertThat(memory.lastOutcome().extracted()).isZero();
        assertThat(memory.lastOutcome().saved()).isZero();
        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    @Test
    @DisplayName("可重试故障（TimeoutException）重试一次即恢复，记账为成功写入")
    void retryableTimeoutIsRetriedAndSucceeds() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.insertIgnoreDuplicate(any())).thenReturn(1);
        Model delegate = mock(Model.class);
        AtomicInteger calls = new AtomicInteger();
        when(delegate.stream(any(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new java.util.concurrent.TimeoutException("upstream stalled");
            }
            return reactor.core.publisher.Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("MEM|PREFERENCE|要表格").build())).build());
        });

        var memory = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, delegate);
        memory.record(userSaid("要表格")).block(Duration.ofSeconds(15));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(memory.lastOutcome().failure()).isNull();
        assertThat(memory.lastOutcome().written()).isTrue();
        assertThat(memory.lastOutcome().extracted()).isEqualTo(1);
        assertThat(memory.lastOutcome().saved()).isEqualTo(1);
        verify(mapper, times(1)).insertIgnoreDuplicate(any());
    }

    @Test
    @DisplayName("确定性失败（auth failed）不重试——只调模型一次，不把同一个失败打三遍")
    void deterministicFailureIsNotRetried() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        Model delegate = mock(Model.class);
        when(delegate.stream(any(), any(), any()))
            .thenAnswer(invocation -> { throw new IllegalStateException("auth failed"); });

        var memory = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper, delegate);
        assertThatThrownBy(() -> memory.record(userSaid("x")).block(Duration.ofSeconds(15)))
            .hasMessageContaining("Long-term memory record failed")
            .hasMessageNotContaining("auth failed");

        verify(delegate, times(1)).stream(any(), any(), any());
        assertThat(memory.lastOutcome().failure()).isNotNull();
        assertThat(memory.lastOutcome().written()).isFalse();
        assertThat(memory.lastOutcome().extracted()).isZero();
        assertThat(memory.lastOutcome().saved()).isZero();
        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    @Test
    @DisplayName("RecordOutcome 记账：extracted 是解析条数，saved 只数实际插入的行")
    void recordOutcomeCountsOnlyActuallyInsertedRows() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        // 第一条新入库，第二条命中去重口返回 0 —— saved 必须如实反映为 1
        when(mapper.insertIgnoreDuplicate(any())).thenReturn(1, 0);
        var memory = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
            modelReturning("MEM|PREFERENCE|要表格\nMEM|FACT|已确认走方案 B"));
        memory.record(userSaid("给我表格；方案定了走 B")).block(Duration.ofSeconds(15));

        assertThat(memory.lastOutcome().extracted()).isEqualTo(2);
        assertThat(memory.lastOutcome().saved()).isEqualTo(1);
        assertThat(memory.lastOutcome().failure()).isNull();
        assertThat(memory.lastOutcome().written()).isTrue();
        verify(mapper, times(2)).insertIgnoreDuplicate(any());
    }

    @Test
    @DisplayName("RecordOutcome 记账：落库失败时 failure 非空且 written() 为 false")
    void recordOutcomeMarksPersistenceFailure() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        when(mapper.insertIgnoreDuplicate(any())).thenThrow(new IllegalStateException("secret-db-url"));
        var memory = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
            modelReturning("MEM|PREFERENCE|要表格"));

        assertThatThrownBy(() -> memory.record(userSaid("要表格")).block(Duration.ofSeconds(15)))
            .hasMessageContaining("Long-term memory record failed")
            .hasMessageNotContaining("secret-db-url");
        assertThat(memory.lastOutcome().failure()).isNotNull();
        assertThat(memory.lastOutcome().written()).isFalse();
    }

    /**
     * 抽取判定为「无可记内容」不是写入也不是失败：它是第三种结局。
     *
     * <p>旧实现 {@code written()} 只判 {@code failure == null}，本轮因此被记成 WRITTEN，
     * 于是空轮在回执里与「真的记住了一条」同形。模型对 {@code MEM|NONE|空} 的原生回应
     * 是最常见的一种空轮形态（提示词明确要求这么答），必须走的正是这条真实路径。
     */
    @Test
    @DisplayName("RecordOutcome 记账：抽取到 0 条为「无结果」态，既非写入也非失败")
    void zeroExtractionIsNoResultNotWritten() {
        IpdAgentMemoryMapper mapper = mock(IpdAgentMemoryMapper.class);
        var memory = new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
            modelReturning("MEM|NONE|空"));

        memory.record(userSaid("今天天气不错")).block(Duration.ofSeconds(15));

        var outcome = memory.lastOutcome();
        assertThat(outcome.failure()).isNull();
        assertThat(outcome.extracted()).isZero();
        assertThat(outcome.noResult()).isTrue();
        assertThat(outcome.written()).isFalse();
        verify(mapper, never()).insertIgnoreDuplicate(any());
    }

    /** 三态互斥且完备：任一 RecordOutcome 上恰有一个状态成立，不允许重叠，也不允许空档。 */
    @Test
    @DisplayName("RecordOutcome 记账：无结果 / 已写入 / 失败 三态互斥且完备")
    void recordOutcomeStatesAreMutuallyExclusiveAndExhaustive() {
        var outcomes = List.of(
            new ProjectScopedLongTermMemory.RecordOutcome(0, 0, null),
            new ProjectScopedLongTermMemory.RecordOutcome(1, 1, null),
            new ProjectScopedLongTermMemory.RecordOutcome(2, 0, null),
            new ProjectScopedLongTermMemory.RecordOutcome(0, 0, new IllegalStateException("boom")));

        assertThat(outcomes).allSatisfy(outcome -> {
            int holding = (outcome.noResult() ? 1 : 0) + (outcome.written() ? 1 : 0)
                + (outcome.failure() != null ? 1 : 0);
            assertThat(holding).as("三态恰有一个成立，outcome=%s", outcome).isEqualTo(1);
        });

        // 边界逐条钉死：0 条不是写入；抽取 ≥1 条且未失败才是写入；有 failure 一律不是写入。
        assertThat(outcomes.get(0).noResult()).isTrue();
        assertThat(outcomes.get(0).written()).isFalse();
        assertThat(outcomes.get(1).written()).isTrue();
        assertThat(outcomes.get(2).written()).isTrue();
        assertThat(outcomes.get(3).noResult()).isFalse();
        assertThat(outcomes.get(3).written()).isFalse();
    }
}
