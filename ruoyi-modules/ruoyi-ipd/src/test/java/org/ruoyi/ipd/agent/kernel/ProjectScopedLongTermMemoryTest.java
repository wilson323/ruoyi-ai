package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.List;
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
        var sink = new ProjectAgentUsageSink(recorder, ledger, 123L, "person", "run");
        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
            new ProjectAgentMeteredModel(delegate, sink))
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
        var sink = new ProjectAgentUsageSink(recorder, ledger, 123L, "person", "run");
        new ProjectScopedLongTermMemory(PROJECT, PERSON, RUN, mapper,
            new ProjectAgentMeteredModel(modelReturning("MEM|NONE|空"), sink))
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
}
