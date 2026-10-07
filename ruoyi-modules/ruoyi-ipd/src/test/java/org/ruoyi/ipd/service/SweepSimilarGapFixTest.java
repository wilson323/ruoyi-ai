package org.ruoyi.ipd.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.OssFileMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同类异常清扫 5 处缺口的回归锁（2026-10-07，来源 docs/ipd-系统说明/同类异常清扫-20261007.md）：
 * <ol>
 *   <li>{@code ProjectService.bindProjectPm}：MEMBER 分支绕过在册/在职校验（津贴污染源头）；</li>
 *   <li>{@code KpiSharedReconcileService.leadersOf}：对账接收人漏 role 收窄；</li>
 *   <li>{@code KpiRecordService.recordScore}：mapper 未装配时静默丢写却返回 draft；</li>
 *   <li>{@code GateElementResultService.resolveMinCustomerVerifications}：catch 吞异常无日志；</li>
 *   <li>{@code IpdReportService.resolveVisibleProjectIds}：两处可见集查询漏 exitDate 收窄。</li>
 * </ol>
 *
 * <p><b>断言口径</b>：凡涉及「传了什么条件去查」的用例，一律用
 * {@link ArgumentCaptor} 捕获 {@link LambdaQueryWrapper} → {@code getSqlSegment()} /
 * {@code getParamNameValuePairs()}，<b>不</b>断言 mapper 的桩返回值。给桩返回值时，
 * 「查询条件」与「数据库回了什么行」是两回事——删掉生产代码的过滤，测试照样绿。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SweepSimilarGapFixTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "sweep-gap");
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, Person.class);
    }

    // ====================================================================
    // ① ProjectService.bindProjectPm —— MEMBER 不得绕过在册/在职校验
    // ====================================================================

    @Mock private ProjectMapper projectMapper;
    @Mock private ProductMapper productMapper;
    @Mock private org.ruoyi.ipd.mapper.StageActionMapper stageActionMapper;
    @Mock private KpiRecordMapper kpiRecordMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private GateEngine gateEngine;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private PersonMapper personMapper;
    @Mock private ISystemConfigService systemConfigService;

    private ProjectService projectService;

    private static final Long OPERATOR = 7L;
    private static final Long GROUP = 7L;

    @BeforeEach
    void setUpProject() {
        projectService = new ProjectService(projectMapper, productMapper, stageActionMapper,
            kpiRecordMapper, auditLogService, gateEngine,
            org.ruoyi.ipd.support.NoopTransactionManager.INSTANCE, null);
        org.ruoyi.ipd.service.impl.DefaultStateMachineGuard guard =
            new org.ruoyi.ipd.service.impl.DefaultStateMachineGuard(null, null);
        guard.initRules();
        projectService.setStateMachineGuard(guard);
        projectService.setProjectMemberMapper(projectMemberMapper);
        projectService.setPersonMapper(personMapper);
        projectService.setSystemConfigService(systemConfigService);

        lenient().when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(null);
        lenient().when(projectMapper.insert(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(555L);
            return 1;
        });
        lenient().when(projectMemberMapper.selectCount(any())).thenReturn(0L);
        lenient().when(systemConfigService.getIntValue("allowance.L3", -1)).thenReturn(2000);
    }

    private Project base() {
        Project p = new Project();
        p.setName("人脸门禁 S 级");
        p.setTemplateType("HARDWARE");
        p.setTargetMarkets("[\"SA\"]");
        p.setMainGroupId(GROUP);
        p.setLevel("S");
        // 新品立项必须选产品线（productLineMapper 未装配 ⇒ enforceProductLine 直接返回）
        p.setTargetSalesAmount(new BigDecimal("5000000"));
        p.setTargetChannelCount(10);
        p.setTargetNps(70);
        p.setTargetSceneCount(5);
        return p;
    }

    private Person person(String type, String employment, String account) {
        return Person.builder().id(OPERATOR).name("OP").personType(type).level("L3")
            .employmentStatus(employment).accountStatus(account).groupId(GROUP).build();
    }

    @Test
    @DisplayName("① MEMBER 分支：人员不存在 ⇒ 拒绝，不写成员行（原实现完全绕过校验）")
    void bindMemberRejectsUnknownPerson() {
        when(personMapper.selectById(OPERATOR)).thenReturn(null);

        assertThatThrownBy(() -> projectService.create(
            base(), OPERATOR, null, null, null, 3L, "SUPER_ADMIN"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("人员不存在");

        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    @DisplayName("① MEMBER 分支：离职/禁用人员 ⇒ 拒绝")
    void bindMemberRejectsResignedOrDisabledPerson() {
        when(personMapper.selectById(OPERATOR)).thenReturn(person("SUPER_ADMIN", "RESIGNED", "ENABLED"));
        assertThatThrownBy(() -> projectService.create(
            base(), OPERATOR, null, null, null, 3L, "SUPER_ADMIN"))
            .hasMessageContaining("离职/禁用");

        when(personMapper.selectById(OPERATOR)).thenReturn(person("SUPER_ADMIN", "ACTIVE", "DISABLED"));
        assertThatThrownBy(() -> projectService.create(
            base(), OPERATOR, null, null, null, 3L, "SUPER_ADMIN"))
            .hasMessageContaining("离职/禁用");

        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    @DisplayName("① MEMBER 分支：在职人员照常写入，且不套 B7 角色词表（MEMBER 本就是非 PM 角色）")
    void bindMemberAcceptsActiveNonPmPersonWithoutRoleVocabularyCheck() {
        when(personMapper.selectById(OPERATOR))
            .thenReturn(person("SUPER_ADMIN", "ACTIVE", "ENABLED"));

        projectService.create(base(), OPERATOR, null, null, null, 3L, "SUPER_ADMIN");

        ArgumentCaptor<ProjectMember> cap = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(cap.capture());
        assertThat(cap.getValue().getRole()).isEqualTo("MEMBER");
        assertThat(cap.getValue().getPersonId()).isEqualTo(OPERATOR);
    }

    // ====================================================================
    // ② KpiSharedReconcileService.leadersOf —— 对账接收人必须按 role 收窄
    // ====================================================================

    @Mock private org.ruoyi.ipd.mapper.KpiSharedConfirmMapper confirmMapper;
    @Mock private org.ruoyi.ipd.mapper.ProductGroupMapper productGroupMapper;
    @Mock private KpiSharedCollectionService collectionService;

    private KpiSharedReconcileService reconcileService;

    @BeforeEach
    void setUpReconcile() {
        reconcileService = new KpiSharedReconcileService(kpiRecordMapper, confirmMapper, projectMapper,
            projectMemberMapper, personMapper, productGroupMapper, collectionService);
    }

    @Test
    @DisplayName("② 对账接收人查询带 role IN (MARKET_PM, RD_PM)（断言查询条件，不断言桩返回值）")
    void leadersOfQueryNarrowsRoleToDualPm() {
        reconcileService.leadersOf(100L);

        ArgumentCaptor<LambdaQueryWrapper<ProjectMember>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMemberMapper).selectList(cap.capture());
        LambdaQueryWrapper<ProjectMember> q = cap.getValue();

        String sql = q.getSqlSegment();
        Collection<Object> params = q.getParamNameValuePairs().values();
        assertThat(sql).contains("role IN");
        assertThat(params).contains("MARKET_PM", "RD_PM");
        // 与 KpiSharedCollectionService#activeMembers 同口径：只看在职成员
        assertThat(sql).contains("exit_date IS NULL");
    }

    // ====================================================================
    // ③ KpiRecordService.recordScore —— mapper 未装配必须 fail-closed
    // ====================================================================

    @Test
    @DisplayName("③ recordScore：KpiRecordMapper 未装配 ⇒ 显式拒绝，不静默丢写后返回 draft")
    void recordScoreFailsClosedWhenMapperNotWired() {
        KpiRecordService bare = new KpiRecordService(null, null, null, null);
        StateMachineGuard guard = Mockito.mock(StateMachineGuard.class);
        bare.setStateMachineGuard(guard);

        org.ruoyi.ipd.domain.KpiRecord draft = new org.ruoyi.ipd.domain.KpiRecord();
        draft.setPersonId(1001L);
        draft.setPeriod("2026-08");

        assertThatThrownBy(() -> bare.recordScore(draft,
            new IpdActor(1001L, "PM", "MARKET_PM", 1L)))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class)
            .hasMessageContaining("写入不可用");
    }

    // ====================================================================
    // ④ GateElementResultService —— 阈值读取失败必须留痕
    // ====================================================================

    @Mock private org.ruoyi.ipd.mapper.GateMapper gateMapper;
    @Mock private org.ruoyi.ipd.mapper.GateElementMapper elementMapper;
    @Mock private org.ruoyi.ipd.mapper.GateElementResultMapper resultMapper;
    @Mock private NotificationService notificationService;
    @Mock private OssFileMapper ossFileMapper;
    @Mock private IBusinessConfigService businessConfigService;

    @Test
    @DisplayName("④ G1-1 阈值读取抛异常时：仍回退默认 5，但必须打 WARN 且带要素码（吞异常无日志=静默改阈值）")
    void minCustomerVerificationsFallbackIsLogged() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "sweep-gate"),
            org.ruoyi.ipd.domain.GateElementResult.class);
        GateElementResultService gateService = new GateElementResultService(gateMapper, elementMapper,
            resultMapper, systemConfigService, auditLogService, notificationService, ossFileMapper);
        ReflectionTestUtils.setField(gateService, "businessConfigService", businessConfigService);

        Gate gate = new Gate();
        gate.setId(5001L);
        gate.setGateCode("G1");
        gate.setStatus("PENDING");
        GateElement element = new GateElement();
        element.setId(7001L);
        element.setGateCode("G1");
        element.setElementCode("G1-1");
        element.setIsVeto("0");
        element.setEnabled("1");
        lenient().when(gateMapper.selectById(5001L)).thenReturn(gate);
        lenient().when(elementMapper.selectById(7001L)).thenReturn(element);
        when(businessConfigService.getInt(any())).thenThrow(new IllegalStateException("config down"));
        lenient().when(systemConfigService.getIntValue(any(), any(Integer.class))).thenReturn(5);

        Logger logger = (Logger) LoggerFactory.getLogger(GateElementResultService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 2 家一手验证 < 默认阈值 5 且无书面意向 ⇒ 应被拒，证明仍走默认阈值 5 这条降级路径
            assertThatThrownBy(() -> gateService.judge(5001L, 7001L, "PASS", null, "ev-1",
                2, 0, null, null, new IpdActor(3001L, "市场PM", "MARKET_PM", 7L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("≥5");

            assertThat(appender.list)
                .anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("G1-1 阈值读取失败")
                    .contains("G1-1")
                    .contains("config down"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // ====================================================================
    // ⑤ IpdReportService —— 可见项目集必须按 exitDate 收窄
    // ====================================================================

    @Mock private org.ruoyi.ipd.mapper.AllowanceLedgerMapper allowanceLedgerMapper;
    @Mock private org.ruoyi.ipd.mapper.ProjectScoreMapper projectScoreMapper;

    @Test
    @DisplayName("⑤ PM 视角可见项目查询带 exit_date IS NULL（已退出成员不该再算参与）")
    void visibleProjectQueryForPmNarrowsExitDate() {
        IpdReportService reportService = new IpdReportService(allowanceLedgerMapper, projectScoreMapper,
            projectMapper, projectMemberMapper, personMapper, auditLogService);

        reportService.listProjectSummaries("2026-08", null, null, 1, 10,
            new IpdActor(1001L, "市场PM", "MARKET_PM", 1L));

        ArgumentCaptor<LambdaQueryWrapper<ProjectMember>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMemberMapper).selectList(cap.capture());
        assertThat(cap.getValue().getSqlSegment()).contains("exit_date IS NULL");
    }

    @Test
    @DisplayName("⑤ 组长视角可见项目查询同样带 exit_date IS NULL（同组人员的已退出成员行不算参与）")
    void visibleProjectQueryForGroupLeaderNarrowsExitDate() {
        IpdReportService reportService = new IpdReportService(allowanceLedgerMapper, projectScoreMapper,
            projectMapper, projectMemberMapper, personMapper, auditLogService);
        Person sameGroup = Person.builder().id(2001L).name("组员").groupId(1L).build();
        when(personMapper.selectList(any())).thenReturn(List.of(sameGroup));
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());

        reportService.listProjectSummaries("2026-08", null, null, 1, 10,
            new IpdActor(1L, "组长", "GROUP_LEADER", 1L));

        ArgumentCaptor<LambdaQueryWrapper<ProjectMember>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMemberMapper, org.mockito.Mockito.atLeastOnce()).selectList(cap.capture());
        assertThat(cap.getAllValues())
            .allSatisfy(q -> assertThat(q.getSqlSegment()).contains("exit_date IS NULL"));
    }
}