package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateArbitration;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.GateArbitrationMapper;
import org.ruoyi.ipd.mapper.GateReviewObserverMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F4 终裁结果必须落状态（D19）：超管终裁 APPROVE 后 Gate 不落 APPROVED，
 * 阶段推进无从收敛；REJECT 终裁又要求幂等不空转打库。
 *
 * <p>四条断言口径：
 * <ul>
 *   <li>① APPROVE 终裁 ⇒ Gate 落 APPROVED + 审计 GATE_FINAL_RULING_APPLIED；</li>
 *   <li>② REJECT 终裁（目标态==当前态 REJECTED）⇒ 跳过 UPDATE 不打库，但审计仍落；</li>
 *   <li>③ CAS 未命中（UPDATE 影响 0 行）⇒ 抛冲突，Gate 状态不被改写；</li>
 *   <li>④ Gate 已 APPROVED 后二次终裁 ⇒ 被 requireArbitratable 的起点守卫拦（非 REJECTED）。</li>
 * </ul>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateFinalRulingEffectTest {

    @Mock
    private GateMapper gateMapper;
    @Mock
    private GateReviewMapper reviewMapper;
    @Mock
    private ProjectMemberMapper memberMapper;
    @Mock
    private PersonMapper personMapper;
    @Mock
    private GateArbitrationMapper arbitrationMapper;
    @Mock
    private GateReviewObserverMapper observerMapper;
    @Mock
    private ISystemConfigService systemConfigService;
    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ProjectMapper projectMapper;

    private GateReviewService service;

    private static final IpdActor MARKET = new IpdActor(301L, "陈市场", "MARKET_PM", 7L);
    private static final IpdActor RD = new IpdActor(302L, "刘研发", "RD_PM", 7L);
    private static final IpdActor SUPER = new IpdActor(303L, "系统管理员", "SUPER_ADMIN", null);
    private static final IpdActor LEADER_A = new IpdActor(304L, "王组长", "GROUP_LEADER", 7L);
    private static final IpdActor LEADER_B = new IpdActor(305L, "李组长", "GROUP_LEADER", 8L);

    private Gate gate;
    private final List<GateReview> signedRows = new ArrayList<>();
    private final List<GateArbitration> arbitrationRows = new ArrayList<>();

    private static Person person(long id, String name, String type, Long groupId) {
        Person p = new Person();
        p.setId(id);
        p.setName(name);
        p.setPersonType(type);
        p.setGroupId(groupId);
        return p;
    }

    @BeforeAll
    static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "F4-gr"), GateReview.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "F4-gate"), Gate.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "F4-ga"), GateArbitration.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "F4-pm"), ProjectMember.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "F4-person"), Person.class);
    }

    @BeforeEach
    void setUp() {
        service = new GateReviewService(gateMapper, reviewMapper, memberMapper,
            personMapper, arbitrationMapper, observerMapper, systemConfigService, auditLogService, notificationService);
        service.setProjectMapper(projectMapper);
        ProjectService visibility = org.mockito.Mockito.mock(ProjectService.class);
        service.setProjectVisibility(visibility);
        lenient().when(visibility.getVisibleById(anyLong(), any())).thenAnswer(inv -> Project.builder()
            .id(inv.getArgument(0)).tenantId("000000").build());
        lenient().when(projectMapper.selectById(11L))
            .thenReturn(Project.builder().id(11L).mainGroupId(MARKET.groupId())
                .status("ACTIVE").delFlag("0").build());
        // 装配真实守卫：F4 的两条 finalRuling 规则未登记时 preCheck 必抛——这正是契约测试要钉住的东西。
        org.ruoyi.ipd.service.impl.DefaultStateMachineGuard guard =
            new org.ruoyi.ipd.service.impl.DefaultStateMachineGuard(auditLogService, notificationService);
        guard.initRules();
        service.setStateMachineGuard(guard);

        gate = new Gate();
        gate.setId(701L);
        gate.setProjectId(11L);
        gate.setGateCode("G1");
        gate.setStatus("REJECTED");
        gate.setCurrentRound(1);
        gate.setStartedAt(new Date());
        gate.setSignDueAt(new Date(System.currentTimeMillis() + 3L * 24 * 3600 * 1000));
        lenient().when(gateMapper.selectById(701L)).thenReturn(gate);
        signedRows.clear();
        arbitrationRows.clear();

        // 双 PM 分歧（先 APPROVE 后 REJECT）：requireArbitratable / hasPmConflict 的前置证据。
        signedRows.add(signed("MARKET_PM", 301L, "APPROVE"));
        signedRows.add(signed("RD_PM", 302L, "REJECT"));
        // 两组长对立意见：超管终裁的前置证据。
        arbitrationRows.add(arbitration(304L, "GROUP_LEADER", "APPROVE"));
        arbitrationRows.add(arbitration(305L, "GROUP_LEADER", "REJECT"));

        lenient().when(reviewMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(signedRows));
        lenient().when(arbitrationMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(arbitrationRows));
        lenient().when(arbitrationMapper.insert(any(GateArbitration.class))).thenAnswer(inv -> {
            arbitrationRows.add(inv.getArgument(0));
            return 1;
        });
        lenient().when(memberMapper.selectList(any())).thenAnswer(inv -> List.of(
            member(301L, "MARKET_PM"), member(302L, "RD_PM")));
        lenient().when(personMapper.selectList(any())).thenReturn(List.of(person(303L, "系统管理员", "SUPER_ADMIN", null)));
    }

    private static ProjectMember member(long personId, String role) {
        ProjectMember m = new ProjectMember();
        m.setPersonId(personId);
        m.setRole(role);
        return m;
    }

    private GateReview signed(String reviewerType, Long reviewerId, String decision) {
        return GateReview.builder().gateId(701L).reviewerType(reviewerType)
            .reviewerId(reviewerId).decision(decision).signedAt(new Date()).round(1).build();
    }

    private GateArbitration arbitration(long arbitratorId, String type, String decision) {
        return GateArbitration.builder().gateId(701L).round(1)
            .arbitratorType(type).arbitratorId(arbitratorId).decision(decision).build();
    }

    /** 审计动作序列：取 mock 的实际调用记录（各用例审计条数不同，不写死 verify 次数）。 */
    private List<String> auditActions() {
        List<String> actions = new ArrayList<>();
        org.mockito.Mockito.mockingDetails(auditLogService).getInvocations().stream()
            .filter(i -> "append".equals(i.getMethod().getName()))
            .map(i -> ((AuditLog) i.getArgument(0)).getAction())
            .forEach(actions::add);
        return actions;
    }

    @Test
    @DisplayName("F4-① 终裁 APPROVE ⇒ Gate 落 APPROVED + 审计 GATE_FINAL_RULING_APPLIED")
    void finalRulingApprove_appliesGateStatus() {
        when(gateMapper.update(any(), any())).thenReturn(1);

        GateArbitration row = service.finalRuling(701L, "APPROVE", "证据已补齐，准予通过", SUPER);

        assertThat(row.getDecision()).isEqualTo("APPROVE");
        assertThat(gate.getStatus()).isEqualTo("APPROVED");
        assertThat(auditActions()).contains("GATE_FINAL_RULING", "GATE_FINAL_RULING_APPLIED");
        verify(gateMapper, times(1)).update(any(), any());
        // 终裁结果先落库再知会：双方 PM 各收一条 GATE_FINAL_RULING_RESULT
        verify(notificationService, times(2)).publishAfterCommit(anyLong(),
            eq(NotificationService.Types.GATE_FINAL_RULING_RESULT), eq("ACTION"), eq("gate"), eq(701L),
            anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("F4-② 终裁 REJECT 自环幂等：目标态==当前态，不 UPDATE 不打库，审计仍落")
    void finalRulingReject_selfLoopIdempotent() {
        service.finalRuling(701L, "REJECT", "维持驳回", SUPER);

        assertThat(gate.getStatus()).isEqualTo("REJECTED");
        verify(gateMapper, never()).update(any(), any());
        assertThat(auditActions()).contains("GATE_FINAL_RULING_APPLIED");
    }

    @Test
    @DisplayName("F4-③ CAS 未命中（UPDATE 影响 0 行）⇒ 抛冲突，Gate 状态不被改写")
    void finalRulingApprove_casMiss_throws() {
        when(gateMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.finalRuling(701L, "APPROVE", "准予通过", SUPER))
            .isInstanceOf(org.ruoyi.common.core.exception.ServiceException.class)
            .hasMessageContaining("终裁落状态失败");

        assertThat(gate.getStatus()).isEqualTo("REJECTED");
        verify(notificationService, never()).publishAfterCommit(anyLong(),
            eq(NotificationService.Types.GATE_FINAL_RULING_RESULT), anyString(), anyString(),
            anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("F4-④ Gate 已 APPROVED 后二次终裁被 requireArbitratable 拦（起点非 REJECTED）")
    void finalRulingTwice_afterApplied_rejected() {
        when(gateMapper.update(any(), any())).thenReturn(1);
        service.finalRuling(701L, "APPROVE", "准予通过", SUPER);
        assertThat(gate.getStatus()).isEqualTo("APPROVED");

        // 换一位超管重提：起点已是 APPROVED，仲裁/终裁链不再受理
        GateArbitration second = arbitration(399L, "SUPER_ADMIN", "APPROVE");
        arbitrationRows.add(second);

        assertThatThrownBy(() -> service.finalRuling(701L, "APPROVE", "再通过一次", SUPER))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("仅被驳回的 Gate 存在仲裁/终裁流程");

        verify(gateMapper, times(1)).update(any(), any());
    }
}
