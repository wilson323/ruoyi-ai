package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.HandoverRecord;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.ContributionVersionMapper;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;
import org.ruoyi.ipd.support.NoopTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R221 Task 12 通知缺口 4 处补线行为锁（spec §5.1 / plan Task 12）。
 *
 * <p>锁定契约：四类业务节点成功路径必须经 {@link NotificationService#publishAfterCommit}
 * （或超期场景的 {@code publishDailyAfterCommit}）发布 KIND_ACTION 待办——
 * ①删除 submit → 目标组组长 DEL_CROSS_GROUP_CC；②删除组长驳回 → 申请人 DEL_REJECTED；
 * ③删除组长超期升级 → 申请人+组长 DEL_REVIEW_OVERDUE（每日幂等）；
 * ④贡献度双 PM SUBMITTED → 预落组长；⑤奖金池 compute DRAFT 就绪 → 双 PM；
 * ⑥移交 createDraft → 接手人。
 *
 * <p>断言口径：verify 宿主调用 <em>publishAfterCommit</em>（而非 publish）——这是 R221 复审 W1
 * 教训的机制化：宿主 {@code @Transactional} 方法内直调 publish 会加入宿主事务，异常时打成
 * rollback-only 反噬主链；afterCommit 入口是唯一合法姿势。接收人解析（组长/双 PM）为
 * 业务链路的一部分，走真实 mapper stub；通知本身用 mock NotificationService 隔离。
 */
@Tag("dev")
@DisplayName("R221 通知缺口补线：4 域 6 节点 publishAfterCommit 行为锁")
@ExtendWith(MockitoExtension.class)
class NotificationGapWiringTest {

    /** 固定时钟：期限/审计/通知 day 参数可精确断言 */
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-04T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final Date NOW = Date.from(CLOCK.instant());

    // ---------- 删除域 ----------
    @Mock private DeletionRequestMapper deletionRequestMapper;
    @Mock private ISystemConfigService systemConfigService;
    @Mock private IAuditLogService deletionAuditLogService;
    @Mock private DeleteAuditService deleteAuditService;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private GateMapper gateMapper;
    @Mock private ProductMapper productMapper;
    @Mock private PersonMapper personMapper;
    @Mock private ProductGroupMapper productGroupMapper;
    @Mock private StateMachineGuard deletionGuard;

    // ---------- 贡献度 ----------
    @Mock private ContributionMapper contributionMapper;
    @Mock private ContributionVersionMapper contributionVersionMapper;
    @Mock private IAuditLogService contributionAuditLogService;
    @Mock private IpdPermission ipdPermission;

    // ---------- 奖金池 ----------
    @Mock private BonusPoolMapper bonusPoolMapper;
    @Mock private IAuditLogService bonusAuditLogService;
    @Mock private ISystemConfigService bonusSystemConfigService;

    // ---------- 移交 ----------
    @Mock private HandoverMapper handoverMapper;
    @Mock private IProjectMemberService projectMemberService;
    @Mock private IpdAuthSession ipdAuthSession;

    // ---------- 通知（被测接线点） ----------
    @Mock private NotificationService notificationService;

    private DeletionRequestServiceImpl deletionService;
    private ContributionService contributionService;
    private BonusPoolService bonusPoolService;
    private HandoverService handoverService;

    private static final IpdActor ACTOR_PM = new IpdActor(1L, "市场PM甲", "MARKET_PM", null);
    private static final IpdActor ACTOR_LEADER = new IpdActor(5L, "本组组长", "GROUP_LEADER", 20L);

    /** 纯 JVM 单测无 MP 运行时：初始化 LambdaQueryWrapper/Update 所需列缓存（对齐各宿主测试先例） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DeletionRequest.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, HandoverRecord.class);
        TableInfoHelper.initTableInfo(assistant, Person.class);
    }

    @BeforeEach
    void setUp() {
        deletionService = new DeletionRequestServiceImpl(
            deletionRequestMapper, systemConfigService, deletionAuditLogService, deleteAuditService,
            projectMemberMapper, projectMapper, gateMapper, productMapper, personMapper);
        deletionService.setStateMachineGuard(deletionGuard);
        deletionService.setNotificationService(notificationService);
        deletionService.setProductGroupMapper(productGroupMapper);
        deletionService.setClock(CLOCK);

        contributionService = new ContributionService(contributionMapper, contributionVersionMapper,
            projectMapper, productGroupMapper, contributionAuditLogService, ipdPermission);
        DefaultStateMachineGuard realGuard = new DefaultStateMachineGuard(contributionAuditLogService, null);
        realGuard.initRules();
        contributionService.setStateMachineGuard(realGuard);
        contributionService.setNotificationService(notificationService);

        bonusPoolService = new BonusPoolService(bonusPoolMapper, projectMapper);
        bonusPoolService.setAuditLogService(bonusAuditLogService);
        bonusPoolService.setStateMachineGuard(mock(StateMachineGuard.class));
        bonusPoolService.setSystemConfigService(bonusSystemConfigService);
        bonusPoolService.setProjectMemberMapper(projectMemberMapper);
        bonusPoolService.setNotificationService(notificationService);

        handoverService = new HandoverService(projectMemberMapper, personMapper, projectMapper,
            handoverMapper, deletionAuditLogService, projectMemberService, NoopTransactionManager.INSTANCE,
            ipdAuthSession, notificationService);
        handoverService.setStateMachineGuard(mock(StateMachineGuard.class));
        handoverService.setClock(CLOCK);
    }

    private Project projectOfGroup(Long groupId) {
        Project project = new Project();
        project.setId(100L);
        project.setMainGroupId(groupId);
        return project;
    }

    // ============================================================
    //  ① 删除 submit → 目标组组长 DEL_CROSS_GROUP_CC（AC-DEL-04 死账接线）
    // ============================================================

    @Test
    @DisplayName("删除 submit：LEADER_REVIEW 建单成功 → 知会目标组组长（DEL_CROSS_GROUP_CC，ACTION）")
    void submit_notifiesTargetGroupLeader() {
        when(projectMemberMapper.selectCount(any())).thenReturn(1L); // 在职项目成员：归属校验通过
        when(projectMapper.selectById(100L)).thenReturn(projectOfGroup(20L));
        when(productGroupMapper.selectById(20L)).thenReturn(
            ProductGroup.builder().id(20L).leaderPersonId(5L).build());
        when(systemConfigService.getIntValue("deletion.leaderDeadlineDays", 2)).thenReturn(2);
        when(deletionRequestMapper.insert(any(DeletionRequest.class))).thenAnswer(inv -> {
            ((DeletionRequest) inv.getArgument(0)).setId(77L);
            return 1;
        });

        deletionService.submit(ACTOR_PM, "projects", 100L, "{}", "测试删除");

        verify(notificationService).publishAfterCommit(eq(5L),
            eq(NotificationService.Types.DEL_CROSS_GROUP_CC), eq(NotificationService.KIND_ACTION),
            eq("deletion_request"), eq(77L), any(), any(), eq("/ipd/deletion/review"));
    }

    @Test
    @DisplayName("删除 submit：组长未配置（leader_person_id NULL）→ 不发布、主链不受影响")
    void submit_withoutLeaderConfigured_publishesNothing() {
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        when(projectMapper.selectById(100L)).thenReturn(projectOfGroup(20L));
        // lenient：未接线前主链不解析组长；接线后解析命中——两阶段均不报 UnnecessaryStubbing
        lenient().when(productGroupMapper.selectById(20L)).thenReturn(
            ProductGroup.builder().id(20L).leaderPersonId(null).build());
        when(systemConfigService.getIntValue("deletion.leaderDeadlineDays", 2)).thenReturn(2);
        when(deletionRequestMapper.insert(any(DeletionRequest.class))).thenReturn(1);

        DeletionRequest request = deletionService.submit(ACTOR_PM, "projects", 100L, "{}", "测试删除");

        org.assertj.core.api.Assertions.assertThat(request.getStatus()).isEqualTo("LEADER_REVIEW");
        verify(notificationService, never()).publishAfterCommit(anyLong(), any(), any(), any(), any(),
            any(), any(), any());
    }

    // ============================================================
    //  ② 删除 组长驳回 → 申请人 DEL_REJECTED（AC-DEL-05 死账接线）
    // ============================================================

    @Test
    @DisplayName("删除 leaderDecision REJECT：终态落库 → 通知申请人驳回（DEL_REJECTED，ACTION）")
    void leaderReject_notifiesRequester() {
        DeletionRequest request = DeletionRequest.builder()
            .id(88L).entityType("projects").entityId(100L).requesterId(1L)
            .status(DeletionRequestServiceImpl.ST_LEADER_REVIEW).leaderDueAt(NOW).build();
        request.setCreateTime(NOW);
        when(deletionRequestMapper.selectById(88L)).thenReturn(request);
        // IDOR 组长归属校验：目标项目 100 主组=20（与 ACTOR_LEADER.groupId 匹配）
        when(projectMapper.selectById(100L)).thenReturn(projectOfGroup(20L));

        deletionService.leaderDecision(ACTOR_LEADER, 88L, false, "证据不足");

        verify(notificationService).publishAfterCommit(eq(1L),
            eq(NotificationService.Types.DEL_REJECTED), eq(NotificationService.KIND_ACTION),
            eq("deletion_request"), eq(88L), any(), any(), eq("/ipd/deletion/my-requests"));
    }

    // ============================================================
    //  ③ 删除 组长超期升级 → 申请人+组长 DEL_REVIEW_OVERDUE（AC-DEL-07 死账接线）
    // ============================================================

    @Test
    @DisplayName("删除 escalateOverdueLeaderReview：整批升级 → 申请人+组长各得一条每日超期通知")
    void escalateOverdue_notifiesRequesterAndLeader() {
        DeletionRequest overdue = DeletionRequest.builder()
            .id(99L).entityType("projects").entityId(100L).requesterId(1L)
            .status(DeletionRequestServiceImpl.ST_LEADER_REVIEW)
            .leaderDueAt(new Date(NOW.getTime() - 86_400_000L)).build();
        overdue.setCreateTime(NOW);
        when(deletionRequestMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(overdue));
        when(deletionRequestMapper.update(any(), any())).thenReturn(1);
        // W-1 修复后口径：组长提醒按**目标组**（resolveScope）解析，与申请人组无关——
        // 故意把申请人组配成外组 99（无组长），若实现回退到申请人组解析则本例会因
        // 组长 verify 失败而红。
        lenient().when(personMapper.selectById(1L)).thenReturn(
            Person.builder().id(1L).groupId(99L).build());
        when(projectMapper.selectById(100L)).thenReturn(projectOfGroup(20L));
        when(productGroupMapper.selectById(20L)).thenReturn(
            ProductGroup.builder().id(20L).leaderPersonId(5L).build());
        lenient().when(systemConfigService.getIntValue("deletion.adminDeadlineDays", 2)).thenReturn(2);

        deletionService.escalateOverdueLeaderReview();

        verify(notificationService).publishDailyAfterCommit(eq(1L),
            eq(NotificationService.Types.DEL_REVIEW_OVERDUE), eq(NotificationService.KIND_ACTION),
            eq("deletion_request"), eq(99L), any(), any(), eq("/ipd/deletion/my-requests"), eq(NOW));
        verify(notificationService).publishDailyAfterCommit(eq(5L),
            eq(NotificationService.Types.DEL_REVIEW_OVERDUE), eq(NotificationService.KIND_ACTION),
            eq("deletion_request"), eq(99L), any(), any(), eq("/ipd/deletion/review"), eq(NOW));
    }

    @Test
    @DisplayName("删除 submit：申请人即目标组组长 → 不自发初审待办（never）")
    void submit_applicantIsTargetLeader_publishesNothing() {
        IpdActor leaderActor = new IpdActor(5L, "组长兼PM", "MARKET_PM", 20L);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        when(projectMapper.selectById(100L)).thenReturn(projectOfGroup(20L));
        when(productGroupMapper.selectById(20L)).thenReturn(
            ProductGroup.builder().id(20L).leaderPersonId(5L).build());
        when(systemConfigService.getIntValue("deletion.leaderDeadlineDays", 2)).thenReturn(2);
        when(deletionRequestMapper.insert(any(DeletionRequest.class))).thenReturn(1);

        deletionService.submit(leaderActor, "projects", 100L, "{}", "自删申请");

        verify(notificationService, never()).publishAfterCommit(anyLong(), any(), any(), any(), any(),
            any(), any(), any());
    }

    // ============================================================
    //  ④ 贡献度双 PM SUBMITTED → 预落组长（已在途实现的行为锁）
    // ============================================================

    @Test
    @DisplayName("贡献度 saveSelf：双 PM 自评均完成升 SUBMITTED → 知会预落组长终裁")
    void contribution_bothDone_notifiesLeader() {
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(2L, "R", "RD_PM", 10L));
        Project lifecycle = new Project();
        lifecycle.setId(500L);
        lifecycle.setStatus("ACTIVE");
        lifecycle.setCurrentStage("LIFECYCLE");
        lifecycle.setDelFlag("0");
        when(projectMapper.selectById(500L)).thenReturn(lifecycle);
        Contribution existing = Contribution.builder()
            .id(999L).projectId(500L).status(Contribution.ST_DRAFT)
            .leaderId(5L)
            .marketShare(new BigDecimal("0.55")).rdShare(new BigDecimal("0.45"))
            .marketSelfInitiation(new BigDecimal("80")).marketSelfInnovation(new BigDecimal("90"))
            .marketSelfLaunch(new BigDecimal("70")).marketSelfMarketResult(new BigDecimal("85"))
            .marketSelfLeadership(new BigDecimal("95"))
            .tierCoefficient(new BigDecimal("0.83"))
            .delFlag("0").build();
        when(contributionMapper.selectOne(any())).thenReturn(existing);

        contributionService.saveSelf(500L, new org.ruoyi.ipd.dto.ContributionSaveReq(
            "RD_PM", new BigDecimal("75"), new BigDecimal("85"), new BigDecimal("80"),
            new BigDecimal("80"), new BigDecimal("90"), "研发自评"));

        verify(notificationService).publishAfterCommit(eq(5L), eq("CONTRIBUTION_SUBMITTED"),
            eq(NotificationService.KIND_ACTION), eq("contribution"), eq(999L),
            any(), any(), eq("/projects/500"));
    }

    // ============================================================
    //  ⑤ 奖金池 compute DRAFT 就绪 → 双 PM（已在途实现的行为锁）
    // ============================================================

    @Test
    @DisplayName("奖金池 compute：DRAFT 落库 → 知会 MARKET_PM + RD_PM 各一条确认待办")
    void bonusPool_compute_notifiesBothPms() {
        Project sLevel = new Project();
        sLevel.setId(200L);
        sLevel.setLevel("S");
        sLevel.setLevelCoefficient(new BigDecimal("1.8"));
        when(projectMapper.selectById(200L)).thenReturn(sLevel);
        when(bonusPoolMapper.insert(any(BonusPool.class))).thenAnswer(inv -> {
            ((BonusPool) inv.getArgument(0)).setId(66L);
            return 1;
        });
        // 按 role 谓词区分返回（真实语义：一人只持一角色；若无差别返回同一列表会把两人各发重复通知）。
        // MP 参数懒填充：先调 getTargetSql() 触发 paramNameValuePairs 落值再判定（探针实证的纯 JVM 行为）。
        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<ProjectMember> q = inv.getArgument(0);
            q.getTargetSql();
            boolean rd = q.getParamNameValuePairs().containsValue("RD_PM");
            return List.of(member(rd ? 12L : 11L));
        });

        bonusPoolService.compute(200L, new BigDecimal("10000000"), new BigDecimal("100"),
            new BigDecimal("1.2"), new BigDecimal("0.05"), ACTOR_PM);

        verify(notificationService).publishAfterCommit(eq(11L), eq("BONUS_POOL_READY"),
            eq(NotificationService.KIND_ACTION), eq("bonus_pool"), eq(66L),
            any(), any(), eq("/projects/200"));
        verify(notificationService).publishAfterCommit(eq(12L), eq("BONUS_POOL_READY"),
            eq(NotificationService.KIND_ACTION), eq("bonus_pool"), eq(66L),
            any(), any(), eq("/projects/200"));
    }

    private ProjectMember member(Long personId) {
        ProjectMember m = new ProjectMember();
        m.setProjectId(200L);
        m.setPersonId(personId);
        return m;
    }

    // ============================================================
    //  ⑥ 移交 createDraft → 接手人（已在途实现的行为锁）
    // ============================================================

    @Test
    @DisplayName("移交 initiate：DRAFT 建单成功 → 知会接手人待确认")
    void handover_createDraft_notifiesRecipient() {
        when(projectMapper.selectById(300L)).thenReturn(projectOfGroup(20L));
        when(personMapper.selectById(9L)).thenReturn(Person.builder()
            .id(9L).name("接手的研发PM").personType("RD_PM")
            .accountStatus("ACTIVE").employmentStatus("ACTIVE").build());
        when(handoverMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(handoverMapper.insert(any(HandoverRecord.class))).thenAnswer(inv -> {
            ((HandoverRecord) inv.getArgument(0)).setId(55L);
            return 1;
        });

        handoverService.initiate(300L, "RD_PM", 9L, "工作交接",
            new IpdActor(1L, "原研发PM", "RD_PM", 20L));

        verify(notificationService).publishAfterCommit(eq(9L), eq("HANDOVER_CREATED"),
            eq(NotificationService.KIND_ACTION), eq("handover"), eq(55L),
            any(), any(), eq("/ipd/handovers/inbox"));
    }
}
