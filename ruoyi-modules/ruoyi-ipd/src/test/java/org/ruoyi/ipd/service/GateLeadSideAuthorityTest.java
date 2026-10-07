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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G4 上市关卡主导方修正（2026-10-06）：G4 的主导方是市场 PM，不是研发 PM。
 *
 * <p>旧实现 {@code leadSideOf} 把 G3/G4 都判给 RD_PM，导致上市验证 Gate 的单签权落在研发手里。
 * 三条断言口径：
 * <ul>
 *   <li>① leadSideOf 单函数口径：G1/G2/G4/G5⇒MARKET_PM，仅 G3⇒RD_PM；</li>
 *   <li>② G4 单签：市场 PM 放行 ⇒ APPROVED；研发 PM 签署被拒（非授权角色）；</li>
 *   <li>③ 超时弃权：单签 G4 不折算（无对方弃权概念，超期不按研发放行也不误落终态）；
 *       双签 Gate 上仅研发已签时，主导方（市场）未表态 ⇒ 落 ABSTAINED_TIMEOUT 不得放行。</li>
 * </ul>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateLeadSideAuthorityTest {

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
    private static final IpdActor OPERATOR = new IpdActor(303L, "系统管理员", "SUPER_ADMIN", null);

    private Gate gate;
    private final List<GateReview> signedRows = new ArrayList<>();

    @BeforeAll
    static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "G4-gr"), GateReview.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "G4-gate"), Gate.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "G4-ga"), GateArbitration.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "G4-pm"), ProjectMember.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "G4-person"), Person.class);
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
            .thenReturn(Project.builder().id(11L).mainGroupId(7L).status("ACTIVE").delFlag("0").build());
        org.ruoyi.ipd.service.impl.DefaultStateMachineGuard guard =
            new org.ruoyi.ipd.service.impl.DefaultStateMachineGuard(auditLogService, notificationService);
        guard.initRules();
        service.setStateMachineGuard(guard);

        signedRows.clear();
        gate = gate("G4");
        lenient().when(gateMapper.selectById(801L)).thenReturn(gate);
        lenient().when(gateMapper.update(any(), any())).thenReturn(1);
        lenient().when(reviewMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(signedRows));
        lenient().when(reviewMapper.insert(any(GateReview.class))).thenAnswer(inv -> {
            signedRows.add(inv.getArgument(0));
            return 1;
        });
        lenient().when(systemConfigService.getIntValue(any(), any(Integer.class))).thenReturn(3);
        // 按角色解析签署人（真库 SQL 语义）：无脑返回全员会让 RD_PM 解析到 301 触发防串签误判
        List<ProjectMember> pool = List.of(member(301L, "MARKET_PM"), member(302L, "RD_PM"));
        lenient().when(memberMapper.selectList(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) inv.getArgument(0);
            // 关键：MP 的 eq() 把值包在 Supplier 里，直到 SQL 片段被渲染才写进 paramNameValuePairs。
            // 不先 getSqlSegment() 强渲染，values 恒为空 ⇒ 本 Answer 永远落到 RD_PM 分支，
            // 于是 signerPersonId(gate,"MARKET_PM") 也返回 302，触发「本角色签署人非本人」误判。
            w.getSqlSegment();
            java.util.Collection<Object> values = w.getParamNameValuePairs().values();
            if (values.contains("MARKET_PM") && values.contains("RD_PM")) {
                return pool;
            }
            String role = values.contains("MARKET_PM") ? "MARKET_PM" : "RD_PM";
            return pool.stream().filter(m -> role.equals(m.getRole())).toList();
        });
        lenient().when(personMapper.selectList(any())).thenReturn(List.of());
    }

    private Gate gate(String gateCode) {
        Gate g = new Gate();
        g.setId(801L);
        g.setProjectId(11L);
        g.setGateCode(gateCode);
        g.setStatus("PENDING");
        g.setCurrentRound(1);
        g.setStartedAt(new Date());
        g.setSignDueAt(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3)));
        return g;
    }

    /** 按角色解析签署人时的候选池行。personId 与 signed() 传入的 reviewerId 同号，
     *  这样「按角色挑签署人」与「防同人双角色连签」两条断言拿到的是同一组 id。 */
    private ProjectMember member(Long personId, String role) {
        return ProjectMember.builder().id(personId).projectId(11L).personId(personId).role(role).build();
    }

    private void signed(String reviewerType, Long reviewerId, String decision) {
        signedRows.add(GateReview.builder().gateId(801L).reviewerType(reviewerType)
            .reviewerId(reviewerId).decision(decision).signedAt(new Date()).round(1).build());
    }

    @Test
    @DisplayName("G4-① 单函数口径：仅 G3 主导方为研发，G1/G2/G4/G5 全归市场")
    void leadSideOf_singleSource() {
        assertThat(GateReviewService.leadSideOf("G1")).isEqualTo("MARKET_PM");
        assertThat(GateReviewService.leadSideOf("G2")).isEqualTo("MARKET_PM");
        assertThat(GateReviewService.leadSideOf("G3")).isEqualTo("RD_PM");
        assertThat(GateReviewService.leadSideOf("G4")).isEqualTo("MARKET_PM");
        assertThat(GateReviewService.leadSideOf("G5")).isEqualTo("MARKET_PM");
    }

    @Test
    @DisplayName("G4-②a G4 单签：市场 PM APPROVE ⇒ Gate 直接落 APPROVED")
    void g4_marketPmApprove_approved() {
        service.sign(801L, "APPROVE", "上市条件已满足", MARKET);

        assertThat(gate.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("G4-②b G4 单签：研发 PM 签署被拒（非授权角色），Gate 不动")
    void g4_rdPmSign_rejected() {
        assertThatThrownBy(() -> service.sign(801L, "APPROVE", "研发侧确认", RD))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("由市场PM主导签署，您无权签署");

        assertThat(gate.getStatus()).isEqualTo("PENDING");
        verify(gateMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("G4-③a 单签 G4 超期不折算：scanTimeout 不处理（无对方弃权概念），不得误落终态")
    void g4_timeout_notFolded() {
        // 超期
        gate.setSignDueAt(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)));
        lenient().when(gateMapper.selectList(any())).thenReturn(List.of(gate));

        assertThat(service.scanTimeout(OPERATOR)).isZero();

        assertThat(gate.getStatus()).isEqualTo("PENDING");
        verify(gateMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("G4-③b 双签 Gate 仅研发已签：主导方（市场）未表态 ⇒ 落 ABSTAINED_TIMEOUT，不得按研发放行")
    void dualSign_onlyRdSigned_abstainedTimeout() {
        Gate g5 = gate("G5");
        g5.setSignDueAt(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)));
        lenient().when(gateMapper.selectById(801L)).thenReturn(g5);
        lenient().when(gateMapper.selectList(any())).thenReturn(List.of(g5));
        signed("RD_PM", 302L, "APPROVE");

        assertThat(service.scanTimeout(OPERATOR)).isEqualTo(1);

        // settleTimeout 走 LambdaUpdateWrapper 直接改库、不回写内存实体（与 P254 同款），
        // 故断言落库的 UPDATE 谓词值，而非内存 gate.status。
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Gate>> cap =
            org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(gateMapper, org.mockito.Mockito.times(1)).update(org.mockito.ArgumentMatchers.isNull(), cap.capture());
        assertThat(cap.getValue().getParamNameValuePairs().values())
            .as("仅研发已签 + 主导方（市场）未表态 ⇒ 不得落 APPROVED，必须落 ABSTAINED_TIMEOUT")
            .contains("ABSTAINED_TIMEOUT")
            .doesNotContain("APPROVED");
        verify(notificationService, org.mockito.Mockito.atLeastOnce())
            .publishAfterCommit(eq(301L), eq("GATE_ABSTAINED"), eq("ACTION"), eq("gate"), eq(801L),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
