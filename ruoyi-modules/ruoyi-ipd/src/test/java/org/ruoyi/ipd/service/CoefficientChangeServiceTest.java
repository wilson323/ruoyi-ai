package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.CoefficientChangeRequest;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import org.ruoyi.ipd.security.IpdActor;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-INC-15c：双PM 提议 → 产品组长确认写档。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CoefficientChangeServiceTest {

    @Mock private CoefficientChangeRequestMapper requestMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProductGroupMapper productGroupMapper;
    @Mock private IAuditLogService auditLogService;

    private CoefficientChangeService service;

    @BeforeEach
    void setUp() {
        service = new CoefficientChangeService(requestMapper, projectMapper, productGroupMapper, auditLogService);
        // R24：装配真实守卫实例（已 initRules 注入全部 36 条规则）——测试覆盖接线路径。
        DefaultStateMachineGuard guard = new DefaultStateMachineGuard(auditLogService, null);
        guard.initRules();
        service.setStateMachineGuard(guard);
    }

    /** 系统性梳理-20260909 新②：决策 CAS 化用 LambdaUpdateWrapper，纯单测需预建实体 lambda 缓存（同 AiModelConfigSecurityRound3Test 惯例）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CoefficientChangeRequest.class);
    }

    private IpdActor proposerActor() {
        // 提议人=市场PM 101L，归属同组（与 sProject().mainGroupId 对齐）
        return new IpdActor(101L, "M", "MARKET_PM", 7L);
    }

    /** R11/A2：主组 7L 配组长 900L（product_groups.leader_person_id 预落锚；leaderDecision 用例同值对齐）。 */
    private void stubGroupLeader() {
        when(productGroupMapper.selectById(7L)).thenReturn(
            ProductGroup.builder().id(7L).leaderPersonId(900L).build());
    }

    private Project sProject() {
        Project p = new Project();
        p.setId(10L);
        p.setLevel("S");
        p.setLevelCoefficient(new BigDecimal("1.5"));
        p.setMainGroupId(7L); // 合流补参：同组守卫 assertSameGroupIpd 需与 proposerActor().groupId 一致
        p.setDelFlag("0");
        return p;
    }

    @Test
    @DisplayName("双PM提议进入 PENDING_LEADER；A 级拒绝；越界拒绝")
    void proposeHappyAndGuards() {
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        when(requestMapper.selectCount(any())).thenReturn(0L);
        stubGroupLeader();
        when(requestMapper.insert(any(CoefficientChangeRequest.class))).thenAnswer(inv -> {
            CoefficientChangeRequest r = inv.getArgument(0);
            r.setId(99L);
            return 1;
        });

        CoefficientChangeRequest created = service.propose(
            10L, new BigDecimal("1.8"), "旗舰溢价", 101L, 102L, 101L,
            // 合流对齐：第 7 参为操作人 actor（须为双 PM 之一或超管，R11/A2 同组守卫）
            proposerActor());
        assertThat(created.getStatus()).isEqualTo(CoefficientChangeRequest.ST_PENDING_LEADER);
        assertThat(created.getProposedCoefficient()).isEqualByComparingTo("1.8");
        // R11 / A2 防回归行为锁（该修复曾被 2a3799d4 merge 取错侧吞掉）：propose 时刻必须预落
        // leader_id=主组组长——StrategicChangeAggregator 的 CC- 卡以 PENDING_LEADER+leader_id
        // 非 NULL 为投递锚，此断言拆掉即真活 CC- 卡恒空回归。
        assertThat(created.getLeaderId()).isEqualTo(900L);

        Project a = sProject();
        a.setLevel("A");
        when(projectMapper.selectById(11L)).thenReturn(a);
        assertThatThrownBy(() -> service.propose(11L, new BigDecimal("1.0"), "x", 101L, 102L, 101L, proposerActor()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("A 级");

        assertThatThrownBy(() -> service.propose(10L, new BigDecimal("2.5"), "越界", 101L, 102L, 101L, proposerActor()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("1.5–2.0");
    }

    @Test
    @DisplayName("R11/A2 fail-closed：mainGroupId 无组长 ⇒ propose 拒绝对新（防工作台 CC-卡恒空）")
    void proposeWithoutGroupLeaderFailsClosed() {
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        when(requestMapper.selectCount(any())).thenReturn(0L);
        // 变体1：product_groups 查无组行（mock 默认返回 null）；变体2：组行在但 leader_person_id NULL
        assertThatThrownBy(() -> service.propose(10L, new BigDecimal("1.8"), "旗舰溢价", 101L, 102L, 101L, proposerActor()))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("未配置产品组长");
        when(productGroupMapper.selectById(7L)).thenReturn(
            ProductGroup.builder().id(7L).leaderPersonId(null).build());
        assertThatThrownBy(() -> service.propose(10L, new BigDecimal("1.8"), "旗舰溢价", 101L, 102L, 101L, proposerActor()))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("防工作台 CC-卡恒空");
        // 对齐 A1 更严口径：拒绝发生在任何写入之前，不产生 leader_id NULL 的投不出卡的在途单
        verify(requestMapper, never()).insert(any(CoefficientChangeRequest.class));
    }

    @Test
    @DisplayName("R11/A2：leaderDecision actor≠预落 leaderId 且非超管 ⇒ 拒签（防组长 B 代签组长 A）")
    void leaderDecisionActorMustMatchPreallocatedLeader() {
        CoefficientChangeRequest pending = CoefficientChangeRequest.builder()
            .id(99L).projectId(10L).proposedCoefficient(new BigDecimal("1.8"))
            .reason("旗舰").marketPmId(1L).rdPmId(2L).proposerId(1L)
            .leaderId(900L) // propose 时刻预落（A2 修复后恒非 NULL；900L ≠ 下面 actor 999L）
            .status(CoefficientChangeRequest.ST_PENDING_LEADER).build();
        when(requestMapper.selectById(99L)).thenReturn(pending);

        assertThatThrownBy(() -> service.leaderDecision(99L, 999L, true, "同意",
            new IpdActor(999L, "L2", "GROUP_LEADER", 7L)))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("预落组长");
        verify(requestMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("组长确认后写入项目 levelCoefficient")
    void leaderConfirmWritesArchive() {
        CoefficientChangeRequest pending = CoefficientChangeRequest.builder()
            .id(99L).projectId(10L).proposedCoefficient(new BigDecimal("1.8"))
            .reason("旗舰").marketPmId(1L).rdPmId(2L).proposerId(1L)
            .status(CoefficientChangeRequest.ST_PENDING_LEADER).build();
        when(requestMapper.selectById(99L)).thenReturn(pending);
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        // 系统性梳理-20260909 新②：决策 CAS 化后，翻转走条件 update(null, wrapper)
        when(requestMapper.update(isNull(), any())).thenReturn(1);
        when(projectMapper.updateById(any(Project.class))).thenReturn(1);

        CoefficientChangeRequest done = service.leaderDecision(99L, 900L, true, "同意",
            new IpdActor(900L, "L", "GROUP_LEADER", 7L));
        assertThat(done.getStatus()).isEqualTo(CoefficientChangeRequest.ST_CONFIRMED);

        ArgumentCaptor<Project> cap = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(cap.capture());
        assertThat(cap.getValue().getLevelCoefficient()).isEqualByComparingTo("1.8");
        assertThat(cap.getValue().getLevelCoefficientReason()).isEqualTo("旗舰");
    }

    @Test
    @DisplayName("并发决策：CAS 未命中（已被并发处理）抛异常且不写项目档案")
    void leaderDecisionCasMissRejectsAndSkipsArchive() {
        CoefficientChangeRequest pending = CoefficientChangeRequest.builder()
            .id(99L).projectId(10L).proposedCoefficient(new BigDecimal("1.8"))
            .reason("旗舰").marketPmId(1L).rdPmId(2L).proposerId(1L)
            .status(CoefficientChangeRequest.ST_PENDING_LEADER).build();
        when(requestMapper.selectById(99L)).thenReturn(pending);
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        when(requestMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.leaderDecision(99L, 900L, true, "同意",
            new IpdActor(900L, "L", "GROUP_LEADER", 7L)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("并发");
        verify(projectMapper, never()).updateById(any(Project.class));
    }
}
