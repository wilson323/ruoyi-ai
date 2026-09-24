package org.ruoyi.ipd.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.CertTemplate;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateReviewObserver;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectCertItem;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.GateArbitrationMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.GateReviewObserverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectCertItemMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.service.CertTemplateService;
import org.ruoyi.ipd.service.GateReviewService;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.service.IProjectCertService;
import org.ruoyi.ipd.service.IProjectMemberService;
import org.ruoyi.ipd.service.ISystemConfigService;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.ProjectCertServiceImpl;
import org.ruoyi.ipd.service.ProjectMemberServiceImpl;
import org.ruoyi.ipd.service.StageActionService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R212 孤儿写端点越权处置（看板卡 dbe1b6a7 · A 组 ①~⑤）：service 层对象级归属校验验收。
 *
 * <p>证据来源 {@code docs/ipd-系统说明/验收/R212-孤儿跟进-20260924/r212-orphan-security.md}：
 * 这 5 个写端点当年扫出来时已有「登录 + 角色门」，缺的是「操作人组 == 目标对象组」这一层
 * （守卫 6 {@link IpdIdorGuard#assertSameGroupIpd}），即同角色跨组横向越权（IDOR）。
 *
 * <p>口径：每条一正（同组放行，业务语义原样生效）+ 一反（他组 actor 真的被 4xx 拒 +
 * 零写入零副作用，红脸自证）。全部走 service 真实实现，仅 mock Mapper/协作者。
 * mock 合法性对齐 {@code docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md}：
 * Project 一律带 main_group_id（真库不变量，Project.create 强制非空 BR-ORG-01），
 * 不构造 {@code new Project()} 这类真库不可能状态。
 */
@Tag("dev")
@DisplayName("R212 孤儿写端点：组归属越权校验（①成员绑定 ②instantiate ③C12补挂 ④gate列席邀请 ⑤认证re-sync）")
class R212OrphanWriteEndpointAuthorizationTest {

    private static final Long GROUP_A = 7L;
    private static final Long GROUP_B = 8L;
    private static final Long PROJECT_ID = 900L;
    private static final Long PERSON_ID = 911L;

    /** 同组市场PM —— 放行侧。 */
    private static final IpdActor PM_IN_GROUP_A = new IpdActor(900L, "市场PM-甲", "MARKET_PM", GROUP_A);
    /** 他组市场PM —— 越权侧（红脸自证主体：同角色、同权限码、只是不同产品组）。 */
    private static final IpdActor PM_IN_GROUP_B = new IpdActor(901L, "市场PM-乙", "MARKET_PM", GROUP_B);
    private static final IpdActor GROUP_LEADER_A = new IpdActor(902L, "组长-甲", "GROUP_LEADER", GROUP_A);
    private static final IpdActor GROUP_LEADER_B = new IpdActor(903L, "组长-乙", "GROUP_LEADER", GROUP_B);
    private static final IpdActor SUPER_ADMIN = new IpdActor(1L, "超管", "SUPER_ADMIN", null);

    @BeforeAll
    static void initMybatisMeta() {
        // 正例路径会真的构造 LambdaQueryWrapper（判重/计数），纯 Mockito 环境需预置 TableInfo
        // lambda cache，否则运行期抛 "can not find lambda cache for this entity"（P062/P241 同法）。
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "R212-dbe1b6a7");
        TableInfoHelper.initTableInfo(a, ProjectMember.class);
        TableInfoHelper.initTableInfo(a, StageAction.class);
        TableInfoHelper.initTableInfo(a, ProjectCertItem.class);
        TableInfoHelper.initTableInfo(a, GateReviewObserver.class);
    }

    /** 真库不变量：main_group_id 非空（Project.create 强制，BR-ORG-01）；status ACTIVE 避开暂停/归档门禁。 */
    private static Project project(Long id, Long mainGroupId) {
        return Project.builder().id(id).mainGroupId(mainGroupId).status("ACTIVE").delFlag("0").build();
    }

    private static Person person(Long id, String personType, String level) {
        Person p = new Person();
        p.setId(id);
        p.setName("成员-" + id);
        p.setPersonType(personType);
        p.setLevel(level);
        p.setAccountStatus("ACTIVE");
        p.setEmploymentStatus("ACTIVE");
        p.setDelFlag("0");
        return p;
    }

    /** 红脸自证统一断言：确实是 403 语义（守卫 6 口径的 FORBIDDEN=30001 → HTTP 403），且文案不泄漏存在性。 */
    private static void assertForbidden403(IpdBusinessException ex) {
        assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.FORBIDDEN);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(30001);
        assertThat(ex.getErrorCode().getHttpStatus()).isEqualTo(403);
        assertThat(ex).hasMessage("无权操作");
    }

    private static void assertUnauthorized401(IpdBusinessException ex) {
        assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.UNAUTHORIZED);
        assertThat(ex.getErrorCode().getHttpStatus()).isEqualTo(401);
    }

    // ==================== ① POST /projects/{id}/members ====================

    private ProjectMemberMapper memberMapper;
    private PersonMapper personMapper;
    private ProjectMapper bindProjectMapper;
    private ISystemConfigService bindConfig;
    private IAuditLogService bindAudit;
    private IProjectMemberService bindService;

    private void setUpBind() {
        memberMapper = mock(ProjectMemberMapper.class);
        personMapper = mock(PersonMapper.class);
        bindProjectMapper = mock(ProjectMapper.class);
        bindConfig = mock(ISystemConfigService.class);
        bindAudit = mock(IAuditLogService.class);
        bindService = new ProjectMemberServiceImpl(memberMapper, personMapper, bindProjectMapper,
            bindConfig, bindAudit);
        when(bindProjectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, GROUP_A));
    }

    @Test
    @DisplayName("①成员绑定 正例：同组PM → 评级/津贴快照照常锁定并插行")
    void bindMember_sameGroup_locksSnapshot() {
        setUpBind();
        when(personMapper.selectById(PERSON_ID)).thenReturn(person(PERSON_ID, "RD_PM", "L2"));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(bindConfig.getIntValue("allowance.projectCountThreshold", 3)).thenReturn(3);
        when(bindConfig.getIntValue("allowance.L2", -1)).thenReturn(1500);

        ProjectMember bound = bindService.bindMember(PROJECT_ID, PERSON_ID, "RD_PM", PM_IN_GROUP_A);

        assertThat(bound.getLockedLevel()).isEqualTo("L2");
        assertThat(bound.getLockedAmount().toPlainString()).isEqualTo("1500");
        verify(memberMapper).insert(any(ProjectMember.class));
    }

    @Test
    @DisplayName("①成员绑定 反例：他组PM（同角色同权限码）→ 403，人员不查、行不插、审计不落")
    void bindMember_crossGroup_forbidden403AndNoWrite() {
        setUpBind();

        assertForbidden403(assertThrows(IpdBusinessException.class,
            () -> bindService.bindMember(PROJECT_ID, PERSON_ID, "RD_PM", PM_IN_GROUP_B)));

        // 守卫位于人员查询之前 ⇒ 越权者连「这个人存不存在」都探不到，且零写入
        verify(personMapper, never()).selectById(any());
        verify(memberMapper, never()).insert(any(ProjectMember.class));
        verify(bindAudit, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("①成员绑定 超管豁免：SUPER_ADMIN（groupId=null）跨组运维绑定仍放行（守卫6既有语义）")
    void bindMember_superAdmin_exempt() {
        setUpBind();
        when(personMapper.selectById(PERSON_ID)).thenReturn(person(PERSON_ID, "RD_PM", "L2"));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(bindConfig.getIntValue("allowance.projectCountThreshold", 3)).thenReturn(3);
        when(bindConfig.getIntValue("allowance.L2", -1)).thenReturn(1500);

        ProjectMember bound = bindService.bindMember(PROJECT_ID, PERSON_ID, "RD_PM", SUPER_ADMIN);

        assertThat(bound.getMemberType()).isEqualTo("PRIMARY");
        verify(memberMapper).insert(any(ProjectMember.class));
    }

    // ============ ② POST /projects/{id}/stages/{sid}/instantiate  ③ .../ensure-bio-compliance ============

    private StageActionMapper stageActionMapper;
    private ProjectMapper stageProjectMapper;
    private StageActionService stageActionService;

    private void setUpStageAction() {
        stageActionMapper = mock(StageActionMapper.class);
        stageProjectMapper = mock(ProjectMapper.class);
        stageActionService = new StageActionService(stageActionMapper, mock(DeliverableMapper.class),
            mock(IAuditLogService.class), mock(ProjectStageMapper.class), stageProjectMapper);
        when(stageProjectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, GROUP_A));
    }

    @Test
    @DisplayName("②instantiate 正例：同组PM → 阶段动作照常批量物化（一次 selectList + 一次 insertBatch）")
    void instantiate_sameGroup_materializesActions() {
        setUpStageAction();
        when(stageActionMapper.selectList(any())).thenReturn(List.of());

        int created = stageActionService.instantiate(PROJECT_ID, 5L, "CONCEPT", PM_IN_GROUP_A);

        assertThat(created).isGreaterThan(0);
        verify(stageActionMapper).insertBatch(anyList(), eq(200));
    }

    @Test
    @DisplayName("②instantiate 反例：他组PM → 403，连已有动作判重查询都不发起（零物化）")
    void instantiate_crossGroup_forbidden403AndNoMaterialize() {
        setUpStageAction();

        assertForbidden403(assertThrows(IpdBusinessException.class,
            () -> stageActionService.instantiate(PROJECT_ID, 5L, "CONCEPT", PM_IN_GROUP_B)));

        verify(stageActionMapper, never()).selectList(any());
        verify(stageActionMapper, never()).insertBatch(anyList(), anyInt());
    }

    @Test
    @DisplayName("②instantiate 旁路封死：actor=null（无会话身份）→ 401，且不触达任何 DB 读")
    void instantiate_nullActor_unauthorized401() {
        setUpStageAction();

        assertUnauthorized401(assertThrows(IpdBusinessException.class,
            () -> stageActionService.instantiate(PROJECT_ID, 5L, "CONCEPT", null)));

        verify(stageProjectMapper, never()).selectById(any());
        verify(stageActionMapper, never()).insertBatch(anyList(), anyInt());
    }

    @Test
    @DisplayName("③ensure-bio-compliance 正例：同组PM → 穿过组断言抵达涉生物判定（本例无涉生物动作 → 0）")
    void ensureBioCompliance_sameGroup_reachesBioCheck() {
        setUpStageAction();
        when(stageActionMapper.selectCount(any())).thenReturn(0L);

        assertThat(stageActionService.ensureBioComplianceMount(PROJECT_ID, PM_IN_GROUP_A)).isZero();

        // selectCount 属 hasBioFeatureActions 的查询：被调到 = 组断言已放行
        verify(stageActionMapper, atLeastOnce()).selectCount(any());
    }

    @Test
    @DisplayName("③ensure-bio-compliance 反例：他组PM → 403，涉生物判定根本不执行（零 C12 写入）")
    void ensureBioCompliance_crossGroup_forbidden403() {
        setUpStageAction();

        assertForbidden403(assertThrows(IpdBusinessException.class,
            () -> stageActionService.ensureBioComplianceMount(PROJECT_ID, PM_IN_GROUP_B)));

        verify(stageActionMapper, never()).selectCount(any());
        verify(stageActionMapper, never()).insert(any(StageAction.class));
    }

    @Test
    @DisplayName("③ensure-bio-compliance 语义保持：projectId 非法仍是原「项目ID必须为正数」（校验顺序未挤掉既有参数错）")
    void ensureBioCompliance_invalidId_keepsLegacyParamError() {
        setUpStageAction();

        assertThatThrownBy(() -> stageActionService.ensureBioComplianceMount(0L, PM_IN_GROUP_A))
            .isInstanceOf(ServiceException.class)
            .hasMessage("项目ID必须为正数");

        verify(stageProjectMapper, never()).selectById(any());
    }

    // ============ ④ POST /gates/{gateId}/observers/invite ============

    private static final Long GATE_ID = 100L;
    private GateMapper gateMapper;
    private GateReviewObserverMapper observerMapper;
    private PersonMapper invitePersonMapper;
    private ProjectMapper gateProjectMapper;
    private NotificationService notificationService;
    private IAuditLogService inviteAudit;

    /** @param wireProjectMapper false=复刻「协作者未装配」的装配期缺陷，验证 fail-closed 而非 fail-open */
    private GateReviewService setUpGateService(boolean wireProjectMapper) {
        gateMapper = mock(GateMapper.class);
        observerMapper = mock(GateReviewObserverMapper.class);
        invitePersonMapper = mock(PersonMapper.class);
        notificationService = mock(NotificationService.class);
        inviteAudit = mock(IAuditLogService.class);
        gateProjectMapper = mock(ProjectMapper.class);
        GateReviewService service = new GateReviewService(gateMapper, mock(GateReviewMapper.class),
            mock(ProjectMemberMapper.class), invitePersonMapper, mock(GateArbitrationMapper.class),
            observerMapper, mock(ISystemConfigService.class), inviteAudit, notificationService);
        if (wireProjectMapper) {
            service.setProjectMapper(gateProjectMapper);
            when(gateProjectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, GROUP_A));
        }
        when(gateMapper.selectById(GATE_ID)).thenReturn(Gate.builder().id(GATE_ID).projectId(PROJECT_ID)
            .gateCode("G1").status("PENDING").currentRound(1).build());
        return service;
    }

    @Test
    @DisplayName("④邀请列席 正例：本组组长 → gate→项目→组链路成立，插行 + 通知照常")
    void inviteObservers_sameGroupLeader_invites() {
        GateReviewService service = setUpGateService(true);
        when(invitePersonMapper.selectById(PERSON_ID)).thenReturn(person(PERSON_ID, "MARKET_PM", "L2"));
        when(observerMapper.selectCount(any())).thenReturn(0L);

        assertThat(service.inviteObservers(GATE_ID, List.of(PERSON_ID), "SALES", GROUP_LEADER_A)).isEqualTo(1);

        verify(observerMapper).insert(any(GateReviewObserver.class));
        verify(notificationService).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("④邀请列席 反例：他组组长（角色门本可通过）→ 403，不插行、不发跨组骚扰通知")
    void inviteObservers_crossGroupLeader_forbidden403() {
        GateReviewService service = setUpGateService(true);

        assertForbidden403(assertThrows(IpdBusinessException.class,
            () -> service.inviteObservers(GATE_ID, List.of(PERSON_ID), "SALES", GROUP_LEADER_B)));

        verify(invitePersonMapper, never()).selectById(any());
        verify(observerMapper, never()).insert(any(GateReviewObserver.class));
        verify(notificationService, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("④邀请列席 fail-closed：归属链路协作者未装配 → 组长一律 403（宁可拒也不错放）")
    void inviteObservers_unwiredProjectMapper_failClosed() {
        GateReviewService service = setUpGateService(false);

        assertForbidden403(assertThrows(IpdBusinessException.class,
            () -> service.inviteObservers(GATE_ID, List.of(PERSON_ID), "SALES", GROUP_LEADER_A)));

        verify(observerMapper, never()).insert(any(GateReviewObserver.class));
    }

    @Test
    @DisplayName("④邀请列席 超管豁免：SUPER_ADMIN 短路在链路解析之前（不产生项目读，防无谓 DB 放大）")
    void inviteObservers_superAdmin_exemptWithoutProjectRead() {
        GateReviewService service = setUpGateService(true);
        when(invitePersonMapper.selectById(PERSON_ID)).thenReturn(person(PERSON_ID, "MARKET_PM", "L2"));
        when(observerMapper.selectCount(any())).thenReturn(0L);

        assertThat(service.inviteObservers(GATE_ID, List.of(PERSON_ID), "SALES", SUPER_ADMIN)).isEqualTo(1);

        verify(gateProjectMapper, never()).selectById(any());
    }

    // ============ ⑤ POST /projects/{id}/cert-items/sync ============

    private ProjectCertItemMapper certItemMapper;
    private CertTemplateService certTemplateService;
    private IAuditLogService certAudit;
    private IProjectCertService certService;
    private Project certProject;

    private void setUpCert() {
        certItemMapper = mock(ProjectCertItemMapper.class);
        certTemplateService = mock(CertTemplateService.class);
        certAudit = mock(IAuditLogService.class);
        certService = new ProjectCertServiceImpl(certItemMapper, mock(ProjectMapper.class),
            certTemplateService, certAudit);
        certProject = Project.builder().id(PROJECT_ID).mainGroupId(GROUP_A).status("ACTIVE")
            .delFlag("0").targetMarkets("[\"SA\"]").build();
    }

    @Test
    @DisplayName("⑤认证清单re-sync 正例：同组PM → 模板解析 + 批量插行照常（返回新增条数）")
    void certSync_sameGroup_insertsItems() {
        setUpCert();
        when(certTemplateService.resolve(any())).thenReturn(List.of(CertTemplate.builder()
            .id(1L).countryCode("SA").countryName("沙特阿拉伯").certName("SABER/SASO").isMandatory("1").build()));
        when(certItemMapper.selectList(any())).thenReturn(List.of());

        assertThat(certService.syncFromProjectAuthorized(certProject, PM_IN_GROUP_A)).isEqualTo(1);

        verify(certItemMapper).insertBatch(anyList(), eq(200));
    }

    @Test
    @DisplayName("⑤认证清单re-sync 反例：他组PM（原实现只把 operatorId 落审计，零归属校验）→ 403，模板不解析、清单零写入")
    void certSync_crossGroup_forbidden403AndNoWrite() {
        setUpCert();

        assertForbidden403(assertThrows(IpdBusinessException.class,
            () -> certService.syncFromProjectAuthorized(certProject, PM_IN_GROUP_B)));

        verify(certTemplateService, never()).resolve(any());
        verify(certItemMapper, never()).insertBatch(anyList(), anyInt());
        verify(certAudit, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("⑤认证清单re-sync 旁路封死：actor=null → 401（不接受匿名 re-sync）")
    void certSync_nullActor_unauthorized401() {
        setUpCert();

        assertUnauthorized401(assertThrows(IpdBusinessException.class,
            () -> certService.syncFromProjectAuthorized(certProject, null)));

        verify(certItemMapper, never()).insertBatch(anyList(), anyInt());
    }

    @Test
    @DisplayName("⑤边界自证：两参 syncFromProject 仍是立项 bootstrap 内部口（无 actor 语义不变，异名而非重载）")
    void certSync_legacyTwoArgForInternalBootstrap_unchanged() {
        setUpCert();
        when(certTemplateService.resolve(any())).thenReturn(List.of(CertTemplate.builder()
            .id(1L).countryCode("SA").countryName("沙特阿拉伯").certName("SABER/SASO").isMandatory("1").build()));
        when(certItemMapper.selectList(any())).thenReturn(List.of());

        assertThat(certService.syncFromProject(certProject, 9L)).isEqualTo(1);
    }
}
