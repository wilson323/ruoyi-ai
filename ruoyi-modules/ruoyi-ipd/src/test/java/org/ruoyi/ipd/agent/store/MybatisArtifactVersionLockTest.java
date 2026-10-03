package org.ruoyi.ipd.agent.store;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.mapper.IpdAgentArtifactVersionMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class MybatisArtifactVersionLockTest {
    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rejectsLockOutsideTransaction() {
        var mapper = mock(IpdAgentArtifactVersionMapper.class);
        var store = new MybatisArtifactVersionStore(mapper);
        assertThatThrownBy(() -> store.findLatestForUpdate("tenant", 9L, "art"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("active transaction");
        verifyNoInteractions(mapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void locksLatestVersionWithTenantAndRunScope() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "lock-test"),
            IpdAgentArtifactVersion.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var mapper = mock(IpdAgentArtifactVersionMapper.class);
        var store = new MybatisArtifactVersionStore(mapper);
        var row = IpdAgentArtifactVersion.builder().id(71L).build();
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(row);
        assertThat(store.findLatestForUpdate("tenant", 9L, " art ")).contains(row);
        ArgumentCaptor<Wrapper> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(query.capture());
        assertThat(query.getValue().getSqlSegment())
            .contains("tenant_id", "run_id", "artifact_id", "del_flag", "version_no DESC")
            .endsWith("LIMIT 1 FOR UPDATE");
        assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper) query.getValue())
            .getParamNameValuePairs().values()).contains("tenant", 9L, "art", "0");
    }
}
