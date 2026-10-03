package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
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
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.GateReviewObserverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 防自签/防串签守卫验收（2026-10-03 修复 Gate 双签缺口）。
 *
 * <p>修复前缺口（审计实证）：requireAuthorized 只校验 actor.role ∈ {MARKET_PM, RD_PM}，
 * requireNotSigned 只按 reviewerType 判重，reviewerId 落库不参与任何比对——同一人以双
 * 角色连签两行即可凑满 {@code decided.size() >= 2} 直接放行双签 Gate（自签）；持签署
 * 角色的第三人替在册签署人代签（串签）代码层无拦截。
 *
 * <p>修复后守卫（GateReviewService.requireSignerIdentity）：
 * <ol>
 *   <li><b>防自签</b>：同一 reviewerId 在本轮以另一角色的行出现（占位或已决均算）⇒ 拒</li>
 *   <li><b>防串签</b>：本人角色本轮指定签署人（占位行 reviewerId 优先，退回在册成员解析）
 *       与 actor.id 不符 ⇒ 拒；指定人解析不到（数据治理缺失态）放行，与 openSignQueue
 *       同口径（存量 P252 行为兼容，本类另有兼容用例锁死）</li>
 * </ol>
 *
 * <p>数据构造走 GateSignQueueAcceptanceTest 同款内存签名簿范式：占位行一律由
 * openSignQueue 真实产生，selectList 按 wrapper 的 gateId/round 过滤，忠实复刻
 * roundRows SQL 语义（无脑返回全量会把跨轮行误当本轮，假绿）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateSignSelfImpersonationGuardTest {

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

    /** 同组在册双 PM（正常态）：301 市场 / 302 研发，组 7。 */
    private static final IpdActor MARKET = new IpdActor(301L, "陈市场", "MARKET_PM", 7L);
    private static final IpdActor RD = new IpdActor(302L, "刘研发", "RD_PM", 7L);
    /** 与在册市场PM 同组同角色的第三人（串签者：过得了角色门与组归属门，过不了身份门）。 */
    private static final IpdActor IMPERSONATOR = new IpdActor(305L, "同组冒签", "MARKET_PM", 7L);
    /** 同一自然人换角色二签（自签者：301 已以 MARKET_PM 签过，再以 RD_PM 签）。 */
    private static final IpdActor SELF_DUAL = new IpdActor(301L, "陈市场", "RD_PM", 7L);

    private static final long GATE_ID = 621L;
    private static final long PROJECT_ID = 11L;

    private GateReviewService service;
    private Gate gate;
    /** 内存 gate_reviews 签名簿：insert / selectList / updateById 同源。 */
    private final List<GateReview> reviewRows = new ArrayList<>();
    /** 在册项目成员池。 */
    private final List<ProjectMember> members = new ArrayList<>();

    @BeforeAll
    static void initMybatisMeta() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "gssig");
        TableInfoHelper.initTableInfo(a, GateReview.class);
        TableInfoHelper.initTableInfo(a, Gate.class);
        TableInfoHelper.initTableInfo(a, GateArbitration.class);
        TableInfoHelper.initTableInfo(a, ProjectMember.class);
        TableInfoHelper.initTableInfo(a, Person.class);
    }

    @BeforeEach
    void setUp() {
        service = new GateReviewService(gateMapper, reviewMapper, memberMapper, personMapper,
            arbitrationMapper, observerMapper, systemConfigService, auditLogService, notificationService);
        // 归属断言 fail-closed：未装配 ProjectMapper 一律「无权操作」，故必须注入（同 R212-④ 口径）
        service.setProjectMapper(projectMapper);
        lenient().when(projectMapper.selectById(PROJECT_ID))
            .thenReturn(Project.builder().id(PROJECT_ID).mainGroupId(7L)
                .status("ACTIVE").delFlag("0").build());
        org.ruoyi.ipd.service.impl.DefaultStateMachineGuard guard =
            new org.ruoyi.ipd.service.impl.DefaultStateMachineGuard(auditLogService, notificationService);
        guard.initRules();
        service.setStateMachineGuard(guard);

        gate = new Gate();
        gate.setId(GATE_ID);
        gate.setProjectId(PROJECT_ID);
        gate.setGateCode("G1");
        gate.setStatus("PENDING");
        gate.setCurrentRound(1);
        gate.setStartedAt(new Date());
        gate.setSignDueAt(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3)));

        reviewRows.clear();
        members.clear();
        members.add(member(301L, "MARKET_PM"));
        members.add(member(302L, "RD_PM"));

        lenient().when(gateMapper.selectById(GATE_ID)).thenReturn(gate);
        lenient().when(gateMapper.updateById(any(Gate.class))).thenReturn(1);
        lenient().when(gateMapper.update(any(), any())).thenReturn(1);
        lenient().when(reviewMapper.insert(any(GateReview.class))).thenAnswer(inv -> {
            reviewRows.add(inv.getArgument(0));
            return 1;
        });
        lenient().when(reviewMapper.updateById(any(GateReview.class))).thenReturn(1);
        lenient().when(reviewMapper.selectList(any()))
            .thenAnswer(inv -> reviewRowsMatching(reviewRows, inv.getArgument(0)));
        lenient().when(arbitrationMapper.selectList(any())).thenReturn(new ArrayList<>());
        lenient().when(systemConfigService.getIntValue(anyString(), eq(3))).thenReturn(3);
        lenient().when(memberMapper.selectList(any()))
            .thenAnswer(inv -> membersMatching(members, inv.getArgument(0)));
    }

    private static ProjectMember member(long personId, String role) {
        ProjectMember m = new ProjectMember();
        m.setPersonId(personId);
        m.setRole(role);
        m.setProjectId(PROJECT_ID);
        return m;
    }

    /** 内存 gate_reviews 簿按 wrapper 的 gateId(Long) / round(Integer) 过滤（roundRows SQL 语义）。 */
    private static List<GateReview> reviewRowsMatching(List<GateReview> pool, Object wrapper) {
        AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
        w.getSqlSegment();
        Collection<Object> values = w.getParamNameValuePairs().values();
        List<Long> gateIds = values.stream().filter(v -> v instanceof Long).map(v -> (Long) v).toList();
        List<Integer> rounds = values.stream().filter(v -> v instanceof Integer).map(v -> (Integer) v).toList();
        return pool.stream()
            .filter(r -> gateIds.isEmpty() || gateIds.contains(r.getGateId()))
            .filter(r -> rounds.isEmpty() || rounds.contains(r.getRound()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** 从 LambdaQueryWrapper 参数袋提取签署角色过滤值，无单角色过滤则视作全集。 */
    private static List<ProjectMember> membersMatching(List<ProjectMember> pool, Object wrapper) {
        AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
        w.getSqlSegment();
        Collection<Object> values = w.getParamNameValuePairs().values();
        boolean market = values.contains("MARKET_PM");
        boolean rd = values.contains("RD_PM");
        if (market && rd) {
            return new ArrayList<>(pool);
        }
        if (market || rd) {
            String role = market ? "MARKET_PM" : "RD_PM";
            return pool.stream().filter(m -> role.equals(m.getRole())).toList();
        }
        return new ArrayList<>(pool);
    }

    /** 走真实写入路径预落占位行（等价 submit 置位后的效果）。 */
    private void openQueue() {
        service.openSignQueue(gate);
    }

    // ==================== 正常路径：双角色双签仍通过 ====================

    @Test
    @DisplayName("正常双签：在册 301/302 各签各的角色 ⇒ 双 APPROVE 放行，占位行走 UPDATE")
    void normalDualSign_twoDistinctPersons_passes() {
        openQueue();
        assertThat(reviewRows).hasSize(2); // 仪器自证：占位行真实存在且非空

        GateReview marketRow = service.sign(GATE_ID, "APPROVE", "同意", MARKET);
        GateReview rdRow = service.sign(GATE_ID, "APPROVE", "同意", RD);

        assertThat(gate.getStatus()).isEqualTo("APPROVED");
        assertThat(marketRow.getReviewerId()).isEqualTo(301L);
        assertThat(rdRow.getReviewerId()).isEqualTo(302L);
        // 占位行对偶：签署 UPDATE 原行，行数不增
        assertThat(reviewRows).hasSize(2);
        assertThat(reviewRows).extracting(GateReview::getReviewerType)
            .containsExactlyInAnyOrder("MARKET_PM", "RD_PM");
    }

    @Test
    @DisplayName("存量兼容：无占位行且在册成员缺失（数据治理缺失态）⇒ 放行，reviewerId 如实落 actor.id")
    void legacy_missingMemberBinding_stillAllowed() {
        members.clear(); // 成员池置空 ⇒ membersMatching 返回空列表（勿重打桩：重打桩会触发旧 answer 收到 null wrapper）

        GateReview row = service.sign(GATE_ID, "APPROVE", "同意", MARKET);

        assertThat(row.getReviewerId()).isEqualTo(301L);
        assertThat(reviewRows).hasSize(1);
    }

    // ==================== 防自签：同一 reviewerId 双角色连签 ====================

    @Test
    @DisplayName("防自签（已决行）：301 已以 MARKET_PM 签过 ⇒ 以 RD_PM 二签被拒，Gate 不放行")
    void selfDualSign_afterDecidedRow_rejected() {
        service.sign(GATE_ID, "APPROVE", "同意", MARKET);

        assertThatThrownBy(() -> service.sign(GATE_ID, "APPROVE", "同人补签", SELF_DUAL))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("防自签");

        // 双签未凑满：Gate 保持 PENDING，仍是单行已决
        assertThat(gate.getStatus()).isEqualTo("PENDING");
        assertThat(reviewRows.stream().filter(r -> r.getDecision() != null)).hasSize(1);
    }

    @Test
    @DisplayName("防自签（占位行）：同一人被指定双角色（数据治理异常）⇒ 第二角色签署即被拦")
    void selfDualSign_viaPlaceholderRow_rejected() {
        members.clear();
        members.add(member(301L, "MARKET_PM"));
        members.add(member(301L, "RD_PM")); // 同一自然人双角色在册
        openQueue();
        assertThat(reviewRows).extracting(GateReview::getReviewerId)
            .containsExactlyInAnyOrder(301L, 301L); // 仪器自证：双占位行确为同人

        // 301 以 MARKET_PM 签：另一角色占位行 reviewerId 同为 301 ⇒ 首签即拦
        assertThatThrownBy(() -> service.sign(GATE_ID, "APPROVE", "同意", MARKET))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("防自签");
        assertThat(gate.getStatus()).isEqualTo("PENDING");
    }

    // ==================== 防串签：非本人代签 ====================

    @Test
    @DisplayName("防串签（占位行）：占位行指定 301，同组同角色第三人 305 代签被拒")
    void impersonation_viaPlaceholderReviewerId_rejected() {
        openQueue();

        assertThatThrownBy(() -> service.sign(GATE_ID, "APPROVE", "替他签", IMPERSONATOR))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不可代签");

        assertThat(gate.getStatus()).isEqualTo("PENDING");
        assertThat(reviewRows).noneMatch(r -> r.getDecision() != null); // 无任何已决行落库
    }

    @Test
    @DisplayName("防串签（在册成员）：无占位行、在册市场PM 为 999，301（正确角色+同组）代签被拒")
    void impersonation_viaMemberResolution_rejected() {
        members.clear();
        members.add(member(999L, "MARKET_PM"));
        members.add(member(302L, "RD_PM"));

        assertThatThrownBy(() -> service.sign(GATE_ID, "APPROVE", "代签", MARKET))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不可代签");
        assertThat(reviewRows).isEmpty(); // 落库前即拦
    }

    // ==================== 异常路径：落库失败不推进终态 ====================

    @Test
    @DisplayName("异常路径：第二签落库抛错 ⇒ 异常传播、Gate 保持 PENDING、未发终态推进")
    void secondSign_persistenceFailure_gateStaysPending() {
        service.sign(GATE_ID, "APPROVE", "同意", MARKET);

        when(reviewMapper.insert(any(GateReview.class)))
            .thenThrow(new RuntimeException("uk_gr_gate_type_round 并发撞键（兜底约束）"));

        assertThatThrownBy(() -> service.sign(GATE_ID, "APPROVE", "同意", RD))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("uk_gr_gate_type_round");

        assertThat(gate.getStatus()).isEqualTo("PENDING");
        verify(gateMapper, never()).updateById(any(Gate.class)); // advance/settle 未触达
    }
}
