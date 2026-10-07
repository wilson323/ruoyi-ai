package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.support.NoopTransactionManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;

/**
 * 项目服务单测：编码生成/系数区间/1:1/状态机/阶段推进
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private GateEngine gateEngine;
    @Mock
    private org.ruoyi.ipd.mapper.StageActionMapper stageActionMapper;
    @Mock
    private org.ruoyi.ipd.mapper.KpiRecordMapper kpiRecordMapper;
    @Mock
    private org.ruoyi.ipd.mapper.ProjectMemberMapper projectMemberMapper;
    @org.mockito.Mock private org.ruoyi.ipd.mapper.PersonMapper personMapper;
    @org.mockito.Mock private org.ruoyi.ipd.service.ISystemConfigService systemConfigService;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectMapper, productMapper, stageActionMapper, kpiRecordMapper,
            auditLogService, gateEngine,
            NoopTransactionManager.INSTANCE,
            null /* P2-6.2：未挂载需求变更单 service 时跳过 hasOpenChange 门禁 */);
        // D-1 接线适配：注入真实守卫（种子规则 + fail-closed 迁移闸）
        DefaultStateMachineGuard d1Guard = new DefaultStateMachineGuard(null, null);
        d1Guard.initRules();
        service.setStateMachineGuard(d1Guard);
        service.setProjectMemberMapper(projectMemberMapper);
        lenient().when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        // 2026-10-07：绑定成员时会按人员等级锁津贴基准，缺 personMapper/systemConfigService
        // 一律 fail-closed 拒绝（此前是静默跳过，只靠 DB 的 locked_level NOT NULL 兜住——
        // 意味着「跳过」在真实库里根本插不进去，这三条用例过去是绿的，只因 mapper 是假的）。
        service.setPersonMapper(personMapper);
        service.setSystemConfigService(systemConfigService);
        lenient().when(personMapper.selectById(any())).thenAnswer(inv -> {
            Person p = new Person();
            p.setId(inv.getArgument(0));
            p.setName("PM"); p.setPersonType("MARKET_PM"); p.setLevel("L3");
            p.setEmploymentStatus("ACTIVE"); p.setAccountStatus("ENABLED");
            return p;
        });
        lenient().when(systemConfigService.getIntValue(any(), any(Integer.class))).thenReturn(2000);
    }

    private Project base(String level, String coefficient, String reason) {
        Project p = new Project();
        p.setName("人脸门禁 S 级");
        p.setProductId(50L);
        p.setTemplateType("HARDWARE");
        p.setTargetMarkets("[\"SA\"]");
        p.setMainGroupId(7L);
        p.setLevel(level);
        p.setLevelCoefficient(coefficient == null ? null : new BigDecimal(coefficient));
        p.setLevelCoefficientReason(reason);
        p.setTargetSalesAmount(new BigDecimal("5000000"));
        p.setTargetChannelCount(10);
        p.setTargetNps(70);
        p.setTargetSceneCount(5);
        return p;
    }

    private Product product50() {
        Product product = new Product();
        product.setId(50L);
        product.setProductName("人脸门禁");
        product.setDelFlag("0");
        return product;
    }

    @Test
    @DisplayName("编码生成：年内最大序号 +1（含软删行，绕 @TableLogic），空年为 001")
    void nextCode() {
        // R179-P0（2026-09-22）：nextCode 改调原生 SQL selectMaxCodeSeqByYear——
        // MP selectList 受 @TableLogic 拦截对软删行不可见，序号回退撞物理 uk_projects_code
        // （实测软删 PRJ-2026-033 后创建项目 API 整体不可用）；取号必须含软删行。
        String prefix = "PRJ-" + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + "-";
        when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(3);
        assertThat(service.nextCode()).isEqualTo(prefix + "004");

        when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(null);
        assertThat(service.nextCode()).endsWith("-001");
    }

    @Test
    @DisplayName("创建成功：待开工，首个项目回填产品指针，不生成阶段")
    void createOk() {
        Product product = product50();
        when(productMapper.selectById(50L)).thenReturn(product);
        when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(null);

        Project created = service.create(base("S", null, null), 1L, 7L);

        assertThat(created.getCode()).matches("PRJ-\\d{4}-\\d{3}");
        assertThat(created.getCurrentStage()).isNull();
        assertThat(created.getStatus()).isEqualTo("PENDING_START");
        assertThat(created.getLevelCoefficient()).isEqualByComparingTo("1.5");
        assertThat(product.getProjectId()).isEqualTo(created.getId());
        verify(auditLogService).append(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("系数校验：S 越下界 1.4 / B 越上界 0.9 / A 带非 1.0 系数 → 全拒")
    void coefficientRange() {
        assertThatThrownBy(() -> service.create(base("S", "1.4", "x"), 1L, 7L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("1.5");
        assertThatThrownBy(() -> service.create(base("B", "0.9", "x"), 1L, 7L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("0.8");
        assertThatThrownBy(() -> service.create(base("A", "1.2", null), 1L, 7L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("A 级");
    }

    @Test
    @DisplayName("AC-INC-15c：S 非默认须走流程；默认 1.5 可无理由")
    void reasonAndCoefficientRequired() {
        assertThatThrownBy(() -> service.create(base("S", "1.8", "旗舰"), 1L, 7L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("AC-INC-15c");
        when(productMapper.selectById(50L)).thenReturn(product50());
        when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(null);
        assertThat(service.create(base("S", null, null), 1L, 7L).getLevelCoefficient())
            .isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("同一产品可以再立一个项目，且不覆盖产品上的首个项目指针")
    void secondProjectKeepsFirstPointer() {
        Product product = product50();
        product.setProjectId(88L);
        when(productMapper.selectById(50L)).thenReturn(product);
        when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(null);

        Project created = service.create(base("S", null, null), 1L, 7L);

        assertThat(created.getStatus()).isEqualTo("PENDING_START");
        assertThat(product.getProjectId()).isEqualTo(88L);
    }

    @Test
    @DisplayName("状态机：DRAFT→ACTIVE 拒绝；DRAFT→TEAMING 通过；ARCHIVED 无出边")
    void statusMachine() {
        Project draft = new Project();
        draft.setId(9L);
        draft.setName("x");
        draft.setStatus("DRAFT");
        draft.setDelFlag("0");
        draft.setMainGroupId(1L);
        when(projectMapper.selectById(9L)).thenReturn(draft);

        assertThatThrownBy(() -> service.changeStatus(9L, "ACTIVE", 1L, 1L, "MARKET_PM"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("非法迁移");
        assertThat(service.changeStatus(9L, "TEAMING", 1L, 1L, "MARKET_PM").getStatus()).isEqualTo("TEAMING");

        Project archived = new Project();
        archived.setId(10L);
        archived.setName("y");
        archived.setStatus("ARCHIVED");
        archived.setDelFlag("0");
        archived.setMainGroupId(1L);
        when(projectMapper.selectById(10L)).thenReturn(archived);
        assertThatThrownBy(() -> service.changeStatus(10L, "ACTIVE", 1L, 1L, "MARKET_PM"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("已归档");  // ZK-IPD §二.10：归档只读 guard 优先于状态机迁移
    }

    @Test
    @DisplayName("阶段推进：CONCEPT→PLAN 线性；VALID 无上市日期进 LAUNCH 拒绝")
    void stageAdvance() {
        Project concept = new Project();
        concept.setId(9L);
        concept.setName("x");
        concept.setStatus("ACTIVE");
        concept.setCurrentStage("CONCEPT");
        concept.setDelFlag("0");
        concept.setMainGroupId(1L);
        when(projectMapper.selectById(9L)).thenReturn(concept);
        assertThat(service.advanceStage(9L, 1L, 1L, "MARKET_PM").getCurrentStage()).isEqualTo("PLAN");

        Project valid = new Project();
        valid.setId(11L);
        valid.setName("z");
        valid.setStatus("ACTIVE");
        valid.setCurrentStage("VALID");
        valid.setDelFlag("0");
        valid.setMainGroupId(1L);
        when(projectMapper.selectById(11L)).thenReturn(valid);
        assertThatThrownBy(() -> service.advanceStage(11L, 1L, 1L, "MARKET_PM"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("上市日期");
    }


    @Test
    void sameGroupNonMemberCannotAdvanceStage() {
        Project project = new Project();
        project.setId(9L);
        project.setStatus("ACTIVE");
        project.setCurrentStage("CONCEPT");
        project.setDelFlag("0");
        project.setMainGroupId(1L);
        when(projectMapper.selectById(9L)).thenReturn(project);
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> service.advanceStage(9L, 2L, 1L, "GROUP_LEADER"))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("非项目成员");
        org.mockito.Mockito.verifyNoInteractions(gateEngine);
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    /* ----------------- ZK-IPD §二.10 归档后只读下沉到 service 层 ----------------- */

    @Test
    @DisplayName("ZK-IPD §二.10：归档项目 updateBaselines 拒绝（service 层下沉）")
    void archivedProjectUpdateBaselinesRejected() {
        Project archived = new Project();
        archived.setId(20L);
        archived.setName("archived-proj");
        archived.setStatus("ARCHIVED");
        archived.setDelFlag("0");
        archived.setMainGroupId(1L);
        archived.setTargetSalesAmount(new BigDecimal("100"));
        archived.setTargetChannelCount(1);
        archived.setTargetNps(50);
        archived.setTargetSceneCount(1);
        when(projectMapper.selectById(20L)).thenReturn(archived);
        Project patch = new Project();
        patch.setTargetSalesAmount(new BigDecimal("999999"));
        assertThatThrownBy(() -> service.updateBaselines(20L, patch, 1L, 1L, "MARKET_PM"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("已归档");
    }

    @Test
    @DisplayName("ZK-IPD §二.10：归档项目 advanceStage 拒绝（service 层下沉）")
    void archivedProjectAdvanceStageRejected() {
        Project archived = new Project();
        archived.setId(21L);
        archived.setName("archived-proj");
        archived.setStatus("ARCHIVED");
        archived.setDelFlag("0");
        archived.setMainGroupId(1L);
        archived.setCurrentStage("LIFECYCLE");
        when(projectMapper.selectById(21L)).thenReturn(archived);
        assertThatThrownBy(() -> service.advanceStage(21L, 1L, 1L, "MARKET_PM"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("已归档");
    }

    @Test
    @DisplayName("ZK-IPD §二.10：归档项目可读 getById 不抛异常")
    void archivedProjectReadable() {
        Project archived = new Project();
        archived.setId(22L);
        archived.setName("archived");
        archived.setStatus("ARCHIVED");
        archived.setDelFlag("0");
        archived.setMainGroupId(1L);
        when(projectMapper.selectById(22L)).thenReturn(archived);
        // getById 是只读，不应抛异常
        assertThat(service.getById(22L).getStatus()).isEqualTo("ARCHIVED");
    }

    /* ----------------- AC-AUTH-09（看板卡 96b7b157）getVisibleById 可见性谓词 ----------------- */

    private Project visibleProject(long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("idor-probe");
        p.setStatus("ACTIVE");
        p.setDelFlag("0");
        p.setMainGroupId(7L);
        return p;
    }

    @Test
    @DisplayName("AC-AUTH-09：同组非成员 MARKET_PM 读他人项目 → FORBIDDEN(30001)，IDOR 读腿闭合")
    void getVisibleByIdSameGroupNonMemberRejected() {
        when(projectMapper.selectById(9L)).thenReturn(visibleProject(9L));
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        IpdActor actor = new IpdActor(7L, "pm-a", "MARKET_PM", 7L);
        assertThatThrownBy(() -> service.getVisibleById(9L, actor))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("无权访问该项目")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("AC-AUTH-09：外组组长（GROUP_LEADER 组≠mainGroupId）→ FORBIDDEN")
    void getVisibleByIdForeignGroupLeaderRejected() {
        when(projectMapper.selectById(9L)).thenReturn(visibleProject(9L));
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        IpdActor actor = new IpdActor(8L, "leader-b", "GROUP_LEADER", 99L);
        assertThatThrownBy(() -> service.getVisibleById(9L, actor))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("无权访问该项目");
    }

    @Test
    @DisplayName("AC-AUTH-09：组长读本组项目（groupId==mainGroupId）→ 放行")
    void getVisibleByIdOwnGroupLeaderAllowed() {
        when(projectMapper.selectById(9L)).thenReturn(visibleProject(9L));
        IpdActor actor = new IpdActor(8L, "leader-a", "GROUP_LEADER", 7L);
        assertThat(service.getVisibleById(9L, actor).getId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("AC-AUTH-09：超管读任意项目 → 放行（不查成员表）")
    void getVisibleByIdSuperAdminAllowed() {
        when(projectMapper.selectById(9L)).thenReturn(visibleProject(9L));
        IpdActor actor = new IpdActor(1L, "root", "SUPER_ADMIN", 3L);
        assertThat(service.getVisibleById(9L, actor).getId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("AC-AUTH-09：在册成员（project_members 命中）读自己项目 → 放行")
    void getVisibleByIdActiveMemberAllowed() {
        when(projectMapper.selectById(9L)).thenReturn(visibleProject(9L));
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        IpdActor actor = new IpdActor(7L, "pm-member", "MARKET_PM", 42L);
        assertThat(service.getVisibleById(9L, actor).getId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("AC-AUTH-09：项目不存在与无权限统一 FORBIDDEN 文案，不泄漏存在性")
    void getVisibleByIdMissingProjectNoExistenceLeak() {
        when(projectMapper.selectById(404L)).thenReturn(null);
        IpdActor actor = new IpdActor(7L, "pm-a", "MARKET_PM", 7L);
        assertThatThrownBy(() -> service.getVisibleById(404L, actor))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("无权访问该项目");
    }

    @Test
    @DisplayName("AC-AUTH-09：projectMemberMapper 未注入（旧构造器形态）非超管一律拒，fail-closed")
    void getVisibleByIdFailsClosedWithoutMemberMapper() {
        ProjectService bare = new ProjectService(projectMapper, productMapper, stageActionMapper,
            kpiRecordMapper, auditLogService, gateEngine, NoopTransactionManager.INSTANCE, null);
        // D-1 接线适配：注入真实守卫（种子规则 + fail-closed 迁移闸）
        DefaultStateMachineGuard d1Guard = new DefaultStateMachineGuard(null, null);
        d1Guard.initRules();
        bare.setStateMachineGuard(d1Guard);
        when(projectMapper.selectById(9L)).thenReturn(visibleProject(9L));
        IpdActor actor = new IpdActor(7L, "pm-a", "MARKET_PM", 7L);
        assertThatThrownBy(() -> bare.getVisibleById(9L, actor))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("无权访问该项目");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void retiredProductCannotGainAnotherProjectOrFirstProjectPointer(boolean initiallyLocked) {
        Product product = product50();
        product.setRetirementLocked(initiallyLocked ? "1" : "0");
        when(productMapper.selectById(50L)).thenReturn(product);
        if (!initiallyLocked) when(productMapper.isRetirementLockedForUpdate(50L)).thenReturn(true);
        assertThatThrownBy(() -> service.create(base("S", null, null), 1L, 7L))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("不能新建关联项目");
        verify(projectMapper, never()).insert(any(Project.class));
        verify(productMapper, never()).updateById(any(Product.class));
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
        assertThat(product.getProjectId()).isNull();
    }

}