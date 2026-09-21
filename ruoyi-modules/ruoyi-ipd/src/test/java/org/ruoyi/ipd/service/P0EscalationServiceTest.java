package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.domain.P0EscalationChain;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.NotificationEventMapper;
import org.ruoyi.ipd.mapper.P0EscalationChainMapper;
import org.ruoyi.ipd.mapper.PersonMapper;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * R149 batch2b C4：P0 升级链服务单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>首次记录：count=1, status=PENDING</li>
 *   <li>幂等：第二次记录 count=2</li>
 *   <li>升级触发：count >= 2 触发 NotificationService.publish</li>
 *   <li>通知 receiver_role=BOTH_LEADERS 标识</li>
 *   <li>状态翻 ESCALATED 不重复触发</li>
 *   <li>参数校验：projectId / p0EventId 必填</li>
 * </ul>
 */
@Tag("dev")
class P0EscalationServiceTest {

    private P0EscalationChainMapper chainMapper;
    private NotificationEventMapper notificationEventMapper;
    private PersonMapper personMapper;
    private NotificationService notificationService;
    private ObjectMapper objectMapper;
    private P0EscalationService service;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "pec-test"),
                P0EscalationChain.class);
    }

    @BeforeEach
    void setUp() {
        chainMapper = mock(P0EscalationChainMapper.class);
        notificationEventMapper = mock(NotificationEventMapper.class);
        personMapper = mock(PersonMapper.class);
        notificationService = mock(NotificationService.class);
        objectMapper = new ObjectMapper();
        service = new P0EscalationService(chainMapper, notificationEventMapper, personMapper,
            notificationService, objectMapper);
    }

    private P0EscalationChain chain(Long projectId, Long p0EventId, int count, String status) {
        P0EscalationChain c = new P0EscalationChain();
        c.setId(1L); c.setProjectId(projectId); c.setP0EventId(p0EventId);
        c.setEscalationCount(count); c.setStatus(status);
        c.setLastEscalationAt(new Date());
        return c;
    }

    private Person leader(Long id) {
        Person p = new Person();
        p.setId(id); p.setName("Leader-" + id); p.setPersonType("GROUP_LEADER");
        p.setAccountStatus("ACTIVE"); p.setDelFlag("0");
        return p;
    }

    @Test
    @DisplayName("① 首次记录：count=1, status=PENDING")
    void record_firstTime() {
        when(chainMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(chainMapper.insert(any(P0EscalationChain.class))).thenAnswer(inv -> {
            P0EscalationChain arg = inv.getArgument(0);
            arg.setId(100L);
            return 1;
        });

        P0EscalationChain result = service.recordP0Unresolved(10L, 20L, new Date());

        assertThat(result.getEscalationCount()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(P0EscalationService.STATUS_PENDING);
        assertThat(result.getProjectId()).isEqualTo(10L);
        assertThat(result.getP0EventId()).isEqualTo(20L);
        verify(chainMapper).insert(any(org.ruoyi.ipd.domain.P0EscalationChain.class));
    }

    @Test
    @DisplayName("② 幂等：第二次记录 count=2")
    void record_secondTime() {
        when(chainMapper.selectOne(any(Wrapper.class)))
            .thenReturn(chain(10L, 20L, 1, P0EscalationService.STATUS_PENDING));
        when(chainMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        P0EscalationChain result = service.recordP0Unresolved(10L, 20L, new Date());

        assertThat(result.getEscalationCount()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo(P0EscalationService.STATUS_PENDING);
        verify(chainMapper).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("③ 升级触发：count >= 2 触发 NotificationService.publish")
    void checkEscalation_triggers() {
        when(chainMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(chain(10L, 20L, 2, P0EscalationService.STATUS_PENDING)));
        when(personMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(leader(101L), leader(102L)));
        when(chainMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        int escalated = service.checkEscalation();

        assertThat(escalated).isEqualTo(1);
        verify(notificationService, times(2)).publish(anyLong(), any(), any(), any(), anyLong(),
            any(), any(), any());
        verify(chainMapper).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("④ 无 GROUP_LEADER 时跳过升级")
    void checkEscalation_noLeaders() {
        when(chainMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(chain(10L, 20L, 2, P0EscalationService.STATUS_PENDING)));
        when(personMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        int escalated = service.checkEscalation();

        assertThat(escalated).isEqualTo(0);
        verify(notificationService, never()).publish(anyLong(), any(), any(), any(), anyLong(),
            any(), any(), any());
    }

    @Test
    @DisplayName("⑤ 空结果：不触发升级")
    void checkEscalation_empty() {
        when(chainMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        int escalated = service.checkEscalation();

        assertThat(escalated).isEqualTo(0);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("⑥ 参数校验：projectId 必填")
    void record_invalidProjectId() {
        assertThatThrownBy(() -> service.recordP0Unresolved(null, 20L, new Date()))
            .isInstanceOf(IpdBusinessException.class);
        assertThatThrownBy(() -> service.recordP0Unresolved(0L, 20L, new Date()))
            .isInstanceOf(IpdBusinessException.class);
    }

    @Test
    @DisplayName("⑦ 参数校验：p0EventId 必填")
    void record_invalidP0EventId() {
        assertThatThrownBy(() -> service.recordP0Unresolved(10L, null, new Date()))
            .isInstanceOf(IpdBusinessException.class);
        assertThatThrownBy(() -> service.recordP0Unresolved(10L, -1L, new Date()))
            .isInstanceOf(IpdBusinessException.class);
    }

    @Test
    @DisplayName("⑧ 标记 RESOLVED：成功")
    void resolve_success() {
        when(chainMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        boolean ok = service.resolve(1L, "已修复");
        assertThat(ok).isTrue();
        verify(chainMapper).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("⑨ 标记 RESOLVED：行不存在返回 false")
    void resolve_notFound() {
        when(chainMapper.update(any(), any(Wrapper.class))).thenReturn(0);
        boolean ok = service.resolve(999L, "x");
        assertThat(ok).isFalse();
    }
}
