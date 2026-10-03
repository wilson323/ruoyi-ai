package org.ruoyi.ipd.agent.store;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.domain.IpdAiFeedback;
import org.ruoyi.ipd.agent.mapper.IpdAgentRunEventMapper;
import org.ruoyi.ipd.agent.mapper.IpdAgentRunMapper;
import org.ruoyi.ipd.agent.mapper.IpdAiFeedbackMapper;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.springframework.dao.DuplicateKeyException;

import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MyBatis store：唯一键冲突返回 false、CAS 迁移按影响行数、终态 seq 查询。
 * 不连真库；Mapper 为替身（非运行态证据）。
 */
@Tag("dev")
class MybatisAgentRunStoreTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration conf = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(conf, "ipd-agent-store-test");
        TableInfoHelper.initTableInfo(assistant, IpdAgentRun.class);
        TableInfoHelper.initTableInfo(assistant, IpdAgentRunEvent.class);
        TableInfoHelper.initTableInfo(assistant, IpdAiFeedback.class);
    }

    @Test
    @DisplayName("insertRun：DuplicateKeyException → false，不抛")
    void insertRunTranslatesDuplicateKey() {
        IpdAgentRunMapper runMapper = mock(IpdAgentRunMapper.class);
        IpdAgentRunEventMapper eventMapper = mock(IpdAgentRunEventMapper.class);
        when(runMapper.insert(any(IpdAgentRun.class))).thenThrow(new DuplicateKeyException("uk"));
        MybatisAgentRunStore store = new MybatisAgentRunStore(runMapper, eventMapper);

        assertThat(store.insertRun(IpdAgentRun.builder().id(1L).status("PENDING").build())).isFalse();
    }

    @Test
    @DisplayName("transition：影响 1 行返回 true；0 行返回 false；合法来源空集短路")
    void transitionUsesRowCountAsCas() {
        IpdAgentRunMapper runMapper = mock(IpdAgentRunMapper.class);
        IpdAgentRunEventMapper eventMapper = mock(IpdAgentRunEventMapper.class);
        when(runMapper.update(isNull(), any())).thenReturn(1).thenReturn(0);
        MybatisAgentRunStore store = new MybatisAgentRunStore(runMapper, eventMapper);
        Date now = new Date();

        assertThat(store.transition(9L, EnumSet.of(AgentRunStatus.PENDING), AgentRunStatus.RUNNING, null, now))
            .isTrue();
        assertThat(store.transition(9L, EnumSet.of(AgentRunStatus.PENDING), AgentRunStatus.RUNNING, null, now))
            .isFalse();
        assertThat(store.transition(9L, EnumSet.of(AgentRunStatus.SUCCEEDED), AgentRunStatus.RUNNING, null, now))
            .isFalse();
        assertThat(store.transition(null, EnumSet.of(AgentRunStatus.PENDING), AgentRunStatus.RUNNING, null, now))
            .isFalse();
    }

    @Test
    void executionEpochHasExplicitVersionCasAndRequiresSameTransactionRowLock() {
        IpdAgentRunMapper runs = mock(IpdAgentRunMapper.class);
        IpdAgentRunEventMapper events = mock(IpdAgentRunEventMapper.class);
        MybatisAgentRunStore store = new MybatisAgentRunStore(runs, events);
        when(runs.update(isNull(), any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<IpdAgentRun> wrapper = call.getArgument(1);
            assertThat(wrapper.getSqlSegment()).contains("version", "status", "id");
            assertThat(wrapper.getSqlSet()).contains("version");
            return 1;
        });
        when(runs.selectOne(any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IpdAgentRun> wrapper = call.getArgument(0);
            assertThat(wrapper.getSqlSegment()).contains("FOR UPDATE");
            return IpdAgentRun.builder().id(9L).version(4).status("RUNNING").build();
        });
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> store.claimEpoch(9L, 3, EnumSet.of(AgentRunStatus.RUNNING)));
        var transaction = org.ruoyi.ipd.agent.support.AgentOwnershipTestTransactions.create();
        transaction.executeWithoutResult(status -> {
            assertThat(store.claimEpoch(9L, 3, EnumSet.of(AgentRunStatus.RUNNING))).contains(4);
            assertThat(store.lockEpoch(9L, 4)).isTrue();
            assertThat(store.lockEpoch(9L, 3)).isFalse();
        });
    }

    @Test
    void verificationLockReturnsCurrentMapperRowAndRejectsMissingTransaction() {
        IpdAgentRunMapper runs = mock(IpdAgentRunMapper.class);
        MybatisAgentRunStore store = new MybatisAgentRunStore(runs, mock(IpdAgentRunEventMapper.class));
        IpdAgentRun current = IpdAgentRun.builder().id(9L).version(1).status("CANCELLED").build();
        when(runs.selectOne(any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IpdAgentRun> wrapper = call.getArgument(0);
            assertThat(wrapper.getSqlSegment()).contains("FOR UPDATE", "id");
            assertThat(wrapper.getParamNameValuePairs().values()).contains(9L);
            return current;
        });
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> store.lockRunForVerification(9L));
        var transaction = org.ruoyi.ipd.agent.support.AgentOwnershipTestTransactions.create();
        transaction.executeWithoutResult(status -> {
            assertThat(store.lockRunForVerification(9L)).containsSame(current);
        });
        org.mockito.Mockito.verify(runs, org.mockito.Mockito.never()).selectById(any(java.io.Serializable.class));
    }

    @Test
    @DisplayName("appendEvent：冲突 false；maxSeq/terminalSeq 口径")
    void eventAppendAndSeqQueries() {
        IpdAgentRunMapper runMapper = mock(IpdAgentRunMapper.class);
        IpdAgentRunEventMapper eventMapper = mock(IpdAgentRunEventMapper.class);
        when(eventMapper.insert(any(IpdAgentRunEvent.class))).thenReturn(1).thenThrow(new DuplicateKeyException("uk_seq"));
        IpdAgentRunEvent last = IpdAgentRunEvent.builder().seq(6L).eventType("TEXT_DELTA").build();
        IpdAgentRunEvent terminal = IpdAgentRunEvent.builder().seq(7L)
            .eventType(AgentEventType.RUN_FINISHED.name()).build();
        when(eventMapper.selectOne(any())).thenReturn(last).thenReturn(terminal);
        when(eventMapper.selectList(any())).thenReturn(List.of(last));
        MybatisAgentRunStore store = new MybatisAgentRunStore(runMapper, eventMapper);

        IpdAgentRunEvent event = IpdAgentRunEvent.builder().runId(1L).seq(1L)
            .eventType("RUN_STARTED").payload("{}").build();
        assertThat(store.appendEvent(event)).isTrue();
        assertThat(store.appendEvent(event)).isFalse();
        assertThat(store.maxSeq(1L)).isEqualTo(6L);
        Optional<Long> term = store.terminalSeq(1L);
        assertThat(term).contains(7L);
        assertThat(store.listEvents(1L, 0L, 10)).hasSize(1);
    }

    @Test
    void chineseStatusSearchKeepsOriginalTextArtifactUnionAndOuterScope() {
        IpdAgentRunMapper runs = mock(IpdAgentRunMapper.class);
        MybatisAgentRunStore store = new MybatisAgentRunStore(runs, mock(IpdAgentRunEventMapper.class));
        when(runs.selectList(any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IpdAgentRun> filter = call.getArgument(0);
            assertThat(filter.getSqlSegment()).contains("tenant_id", "project_id", "person_id", "action_code LIKE",
                "status LIKE", "status IN", "id IN", "AND (", "ORDER BY", "LIMIT 20");
            assertThat(filter.getParamNameValuePairs().values()).contains("tenant-a", 1001L, 11L,
                "%失败%", "FAILED", 42L, 900L);
            return List.of();
        });
        store.listOwnRuns(new AgentRunStore.OwnRunQuery("tenant-a", 1001L, 11L, null, null,
            "失败", java.util.Set.of(42L), 900L, 20));
    }

    @Test
    void statusSearchUsesOnlyExistingUiLabelsAndPreservesLiteralSearch() {
        var labels = java.util.Map.of("排队中", "PENDING", "运行中", "RUNNING", "等待审批", "WAITING_APPROVAL",
            "取消中", "CANCEL_REQUESTED", "已完成", "SUCCEEDED", "失败", "FAILED", "已取消", "CANCELLED");
        for (var entry : labels.entrySet()) {
            IpdAgentRunMapper runs = mock(IpdAgentRunMapper.class);
            when(runs.selectList(any())).thenAnswer(call -> {
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IpdAgentRun> filter = call.getArgument(0);
                assertThat(filter.getSqlSegment()).contains("status IN");
                assertThat(filter.getParamNameValuePairs().values()).contains(entry.getValue(), "%" + entry.getKey() + "%");
                return List.of();
            });
            new MybatisAgentRunStore(runs, mock(IpdAgentRunEventMapper.class)).listOwnRuns(
                new AgentRunStore.OwnRunQuery("tenant-a", 1001L, 11L, null, null, entry.getKey(),
                    java.util.Set.of(), null, 20));
        }
        for (String text : List.of("执行中", "等确认", "FAILED", "%_")) {
            IpdAgentRunMapper runs = mock(IpdAgentRunMapper.class);
            when(runs.selectList(any())).thenAnswer(call -> {
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IpdAgentRun> filter = call.getArgument(0);
                assertThat(filter.getSqlSegment()).contains("status LIKE").doesNotContain("status IN");
                assertThat(filter.getParamNameValuePairs().values()).contains(LikePatterns.containsPattern(text));
                return List.of();
            });
            new MybatisAgentRunStore(runs, mock(IpdAgentRunEventMapper.class)).listOwnRuns(
                new AgentRunStore.OwnRunQuery("tenant-a", 1001L, 11L, null, null, text,
                    java.util.Set.of(), null, 20));
        }
    }

    @Test
    @DisplayName("MybatisAiFeedbackStore：insert 冲突 false；updateRating 按影响行数")
    void feedbackStoreDuplicateAndUpdate() {
        IpdAiFeedbackMapper mapper = mock(IpdAiFeedbackMapper.class);
        when(mapper.insert(any(IpdAiFeedback.class))).thenThrow(new DuplicateKeyException("uk"));
        when(mapper.update(isNull(), any())).thenReturn(1).thenReturn(0);
        MybatisAiFeedbackStore store = new MybatisAiFeedbackStore(mapper);

        assertThat(store.insert(IpdAiFeedback.builder().targetType("RUN_MESSAGE").targetId(1L)
            .personId(11L).rating("UP").build())).isFalse();
        assertThat(store.updateRating("RUN_MESSAGE", 1L, 11L, "DOWN", "x")).isTrue();
        assertThat(store.updateRating("RUN_MESSAGE", 1L, 11L, "DOWN", "x")).isFalse();
    }
}
