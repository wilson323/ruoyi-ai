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
