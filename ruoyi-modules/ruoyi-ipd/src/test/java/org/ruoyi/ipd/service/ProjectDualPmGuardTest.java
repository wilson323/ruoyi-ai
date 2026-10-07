package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;
import org.ruoyi.ipd.support.NoopTransactionManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 双PM 完整性三处收口（2026-10-07，审计见 docs/ipd-系统说明/双PM绑定实现审计-20261007.md）：
 * <ol>
 *   <li>P0-a 创建路径补 B7 角色互斥 + AC-TEAM-11 项目数上限/备案（{@code ProjectService.bindProjectPm}）</li>
 *   <li>P0-b/P1 批准开工前双PM 成对校验（{@code ProjectStartService.approve}）</li>
 * </ol>
 * 依据：主 Prompt v3 :228（角色固定不可跨）/ :495 BR-USER-08 / :496 BR-USER-08b /
 * :615 BR-TEAM-08 / BR-TEAM-09（项目数 ≥3 须备案）/ BR-TEAM-10（确定双PM 后生成项目）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectDualPmGuardTest {

    private static final Long PM_MARKET = 900101L;
    private static final Long PM_RD = 900102L;
    private static final Long OPERATOR = 7L;
    private static final Long GROUP = 7L;

    @Mock private ProjectMapper projectMapper;
    @Mock private ProductMapper productMapper;
    @Mock private StageActionMapper stageActionMapper;
    @Mock private KpiRecordMapper kpiRecordMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private GateEngine gateEngine;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private PersonMapper personMapper;
    @Mock private ISystemConfigService systemConfigService;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(projectMapper, productMapper, stageActionMapper,
            kpiRecordMapper, auditLogService, gateEngine, NoopTransactionManager.INSTANCE, null);
        DefaultStateMachineGuard guard = new DefaultStateMachineGuard(null, null);
        guard.initRules();
        projectService.setStateMachineGuard(guard);
        projectService.setProjectMemberMapper(projectMemberMapper);
        projectService.setPersonMapper(personMapper);
        projectService.setSystemConfigService(systemConfigService);
    }

    // ================= P0-a：创建路径 B7 / 备案 =================

    @Test
    @DisplayName("P0-a B7：传 person_type=RD_PM 的人当 marketPmId ⇒ 拒绝，不写成员行")
    void createRejectsPersonWhoseTypeDoesNotMatchTargetRole() {
        stubCreateScaffolding();
        when(personMapper.selectById(PM_MARKET)).thenReturn(person(PM_MARKET, "RD_PM"));

        assertThatThrownBy(() -> projectService.create(base(), OPERATOR, GROUP, PM_MARKET, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("角色固定不可跨");

        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    @DisplayName("P0-a AC-TEAM-11：已达上限（第4个）⇒ 拒绝，备案也不能超上限")
    void createRejectsWhenProjectCountAlreadyAtThreshold() {
        stubCreateScaffolding();
        when(personMapper.selectById(PM_MARKET)).thenReturn(person(PM_MARKET, "MARKET_PM"));
        // 第一次 selectCount = 同项目重复检查(0)，第二次 = 该人活跃绑定数(3)
        when(projectMemberMapper.selectCount(any())).thenReturn(0L, 3L);
        lenient().when(systemConfigService.getIntValue("allowance.projectCountThreshold", 3)).thenReturn(3);

        assertThatThrownBy(() -> projectService.create(base(), OPERATOR, GROUP, PM_MARKET, null))
            .isInstanceOf(org.ruoyi.common.core.exception.ServiceException.class)
            .hasMessageContaining("已达项目数上限 3");

        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    @DisplayName("P0-a AC-TEAM-11：绑第3个且无备案入口 ⇒ 拒绝并指向 members 端点")
    void createRejectsThirdBindingWithoutApprovalRef() {
        stubCreateScaffolding();
        when(personMapper.selectById(PM_MARKET)).thenReturn(person(PM_MARKET, "MARKET_PM"));
        when(projectMemberMapper.selectCount(any())).thenReturn(0L, 2L);
        lenient().when(systemConfigService.getIntValue("allowance.projectCountThreshold", 3)).thenReturn(3);

        assertThatThrownBy(() -> projectService.create(base(), OPERATOR, GROUP, PM_MARKET, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("达到项目数备案阈值")
            // 内部字段名与内部路由都不该出现在面向业务操作方的异常文案里
            .hasMessageNotContaining("approvalRef")
            .hasMessageNotContaining("/api/v1/");

        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    @DisplayName("正常路径不回归：双PM 各 1 个在任绑定 ⇒ 建成，落 role 正确且锁定津贴")
    void createWithBothPmsSucceeds() {
        stubCreateScaffolding();
        when(personMapper.selectById(PM_MARKET)).thenReturn(person(PM_MARKET, "MARKET_PM"));
        when(personMapper.selectById(PM_RD)).thenReturn(person(PM_RD, "RD_PM"));
        when(personMapper.selectById(OPERATOR)).thenReturn(person(OPERATOR, "SUPER_ADMIN"));
        when(projectMemberMapper.selectCount(any())).thenReturn(0L, 0L, 0L, 0L);
        lenient().when(systemConfigService.getIntValue("allowance.projectCountThreshold", 3)).thenReturn(3);
        lenient().when(systemConfigService.getIntValue("allowance.L3", -1)).thenReturn(2000);

        Project created = projectService.create(base(), OPERATOR, GROUP, PM_MARKET, PM_RD);
        assertThat(created.getStatus()).isEqualTo("PENDING_START");

        ArgumentCaptor<ProjectMember> cap = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper, org.mockito.Mockito.atLeast(2)).insert(cap.capture());
        assertThat(cap.getAllValues()).extracting(ProjectMember::getRole)
            .contains("MARKET_PM", "RD_PM");
        assertThat(cap.getAllValues()).allSatisfy(m -> {
            assertThat(m.getLockedLevel()).isEqualTo("L3");
            assertThat(m.getLockedAmount()).isEqualByComparingTo("2000");
        });
    }

    // ================= P1：开工审批双PM 成对校验 =================

    @Mock private ProductLineMapper lineMapper;
    @Mock private ProjectBootstrapService projectBootstrapService;
    @Mock private IProjectCertService projectCertService;
    @Mock private StateMachineGuard stateMachineGuard;

    private ProjectStartService startService;

    @BeforeEach
    void setUpStart() {
        startService = new ProjectStartService(projectMapper, productMapper, lineMapper,
            projectBootstrapService, projectCertService, auditLogService, stateMachineGuard);
        startService.setProjectMemberMapper(projectMemberMapper);
    }

    @Test
    @DisplayName("P1 零成员项目 ⇒ 批准开工被拒，项目状态不变、不生成阶段")
    void approveRejectsProjectWithoutAnyMember() {
        stubPendingStart();
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> startService.approve(9L, superAdmin()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("未完成双PM 组队");

        assertThat(pending.getStatus()).isEqualTo("PENDING_START");
        verify(projectMapper, never()).updateById(any(Project.class));
        verify(projectBootstrapService, never()).bootstrap(any(), any());
    }

    @Test
    @DisplayName("P1 单边 PM（只有市场PM）⇒ 拒绝并指名缺研发PM，状态不变")
    void approveRejectsSingleSidedProjectAndNamesMissingRole() {
        stubPendingStart();
        // 第一次 = MARKET_PM 计数，第二次 = RD_PM 计数
        when(projectMemberMapper.selectCount(any())).thenReturn(1L, 0L);

        assertThatThrownBy(() -> startService.approve(9L, superAdmin()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("研发PM");

        assertThat(pending.getStatus()).isEqualTo("PENDING_START");
        verify(projectBootstrapService, never()).bootstrap(any(), any());
    }

    @Test
    @DisplayName("P1 双PM 齐全 ⇒ 批准开工通过，进入 TEAMING")
    void approvePassesWhenBothPmsActive() {
        stubPendingStart();
        when(projectMemberMapper.selectCount(any())).thenReturn(1L, 1L);
        lenient().when(projectMapper.updateById(any(Project.class))).thenReturn(1);

        Project approved = startService.approve(9L, superAdmin());

        assertThat(approved.getStatus()).isEqualTo("TEAMING");
        verify(projectBootstrapService).bootstrap(9L, 1L);
    }

    // ================= 夹具 =================

    private Project pending;

    private void stubPendingStart() {
        pending = new Project();
        pending.setId(9L);
        pending.setStatus("PENDING_START");
        pending.setName("待开工");
        lenient().when(projectMapper.selectOne(any())).thenReturn(pending);
        lenient().when(projectMapper.findProductLineId(9L)).thenReturn(3L);
        ProductLine line = new ProductLine();
        line.setId(3L);
        line.setLeaderPersonId(null);
        line.setDelFlag("0");
        line.setTenantId("000000");
        lenient().when(lineMapper.selectById(3L)).thenReturn(line);
    }

    private IpdActor superAdmin() {
        return new IpdActor(1L, "root", "SUPER_ADMIN", GROUP);
    }

    private void stubCreateScaffolding() {
        Product product = new Product();
        product.setId(50L);
        product.setProductName("人脸门禁");
        product.setDelFlag("0");
        lenient().when(productMapper.selectById(50L)).thenReturn(product);
        lenient().when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(null);
        lenient().when(projectMapper.insert(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(123456L);
            return 1;
        });
        lenient().when(systemConfigService.getIntValue("allowance.projectCountThreshold", 3)).thenReturn(3);
        lenient().when(systemConfigService.getIntValue("allowance.L3", -1)).thenReturn(2000);
    }

    private Person person(Long id, String type) {
        return Person.builder().id(id).name("P" + id).personType(type).level("L3")
            .employmentStatus("ACTIVE").accountStatus("ENABLED").groupId(GROUP).build();
    }

    private Project base() {
        Project p = new Project();
        p.setName("人脸门禁 S 级");
        p.setProductId(50L);
        p.setTemplateType("HARDWARE");
        p.setTargetMarkets("[\"SA\"]");
        p.setMainGroupId(GROUP);
        p.setLevel("S");
        p.setTargetSalesAmount(new BigDecimal("5000000"));
        p.setTargetChannelCount(10);
        p.setTargetNps(70);
        p.setTargetSceneCount(5);
        return p;
    }

    /**
     * fail-closed 承重用例：成员表访问未装配时**必须拒**，不能放行。
     *
     * <p>mapper 缺失 = 判不出来，不等于没问题。放行会把「这道闸根本没装」伪装成
     * 「查过了、双PM 齐」，失败长得像成功——正是本轮反复出现的失败形态。
     */
    @Test
    @DisplayName("装配缺失时 fail-closed 拒绝（不放行「无法判定」当「没问题」）")
    void approveRejectsWhenMemberAccessNotWired() {
        stubPendingStart();
        startService.setProjectMemberMapper(null);   // 模拟 Spring 未装配

        assertThatThrownBy(() -> startService.approve(9L, superAdmin()))
            .hasMessageContaining("校验不可用");
    }

    /**
     * 承重用例：津贴基准锁定所需依赖未装配时**必须拒**，不能静默跳过。
     *
     * <p>原实现在 {@code personMapper != null && systemConfigService != null} 才写
     * lockedLevel/lockedAmount，缺依赖时安静地不写，只靠 DB 的 NOT NULL 报错——
     * 「业务该拦的」退化成「数据库报了个看不懂的列非空错」，且与同批 ProjectStartService
     * 的 fail-closed 口径不一致。
     */
    @Test
    @DisplayName("津贴锁定依赖未装配时 fail-closed 拒绝（不静默跳过靠 DB NOT NULL 兜底）")
    void bindRejectsWhenAllowanceLockingDepsMissing() {
        stubCreateScaffolding();
        projectService.setSystemConfigService(null);      // 模拟 Spring 未装配
        // 走 MEMBER 分支（创建人自动回填）：PM 角色分支会先跑项目数阈值校验并先抛，
        // 那样测不到津贴锁定这道。MEMBER 不是 PM 角色，不触发 B7/备案，直接落到津贴锁定。
        // personMapper 必须桩上：2026-10-07 起「在册 + 在职 + 账号启用」对所有角色（含 MEMBER）
        // 都生效，缺这一步会先抛「人员不存在」，测不到津贴锁定这道。
        when(personMapper.selectById(900101L)).thenReturn(person(900101L, "SUPER_ADMIN"));

        assertThatThrownBy(() -> projectService.create(
            base(), 900101L, null, null, null, null, "SUPER_ADMIN"))
            .hasMessageContaining("津贴基准锁定不可用");
    }

    /**
     * 承重用例：异常文案里**不得**出现内部路由与雪花 id。
     *
     * <p>业务异常是给操作方看的，把 {@code POST /api/v1/projects/{id}/members} 这类
     * 内部路由和雪花 id 拼进去既是信息泄露，也会随接口改名而失实。
     */
    @Test
    @DisplayName("备案阈值异常文案不含内部路由与雪花 id")
    void thresholdMessageLeaksNoInternalRouteOrId() {
        stubCreateScaffolding();
        when(personMapper.selectById(900103L)).thenReturn(person(900103L, "MARKET_PM"));
        // 第一次=重复绑定检查(0)，第二次=项目数 FOR UPDATE 计数(=threshold-1 ⇒ 需备案)
        when(projectMemberMapper.selectCount(any())).thenReturn(0L, 2L);
        lenient().when(systemConfigService.getIntValue(any(), any(Integer.class))).thenReturn(3);

        assertThatThrownBy(() -> projectService.create(
            base(), 900101L, null, 900103L, null, null, "SUPER_ADMIN"))
            .hasMessageContaining("备案")
            .hasMessageNotContaining("/api/v1/")
            .hasMessageNotContaining("POST ");
    }
}
