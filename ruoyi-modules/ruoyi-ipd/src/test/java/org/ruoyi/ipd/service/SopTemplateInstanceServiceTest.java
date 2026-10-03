package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.SopTemplate;
import org.ruoyi.ipd.domain.SopTemplateInstance;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.SopTemplateInstanceMapper;
import org.ruoyi.ipd.mapper.SopTemplateMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-3.3 SOP 实例快照生命周期（BR-IPD-SOP-03）：实例化版本自增、旧 ACTIVE 自动 SUPERSEDED、
 * 项目成员守卫（fail-closed 不泄漏存在性）、SUPERSEDED 手工运维路径与终态保护。
 *
 * <p>与 {@link SopTemplateServiceTest}（版本链）互补：本类只覆盖 {@code instantiations} 侧，
 * 不改动既有测试文件。
 */
@Tag("dev")
class SopTemplateInstanceServiceTest {

    private SopTemplateMapper templateMapper;
    private SopTemplateInstanceMapper instanceMapper;
    private IAuditLogService auditLogService;
    private ProjectMemberMapper projectMemberMapper;
    private ProjectMapper projectMapper;
    private SopTemplateService service;

    private static final IpdActor ADMIN = new IpdActor(1L, "admin", "SUPER_ADMIN", 100L);
    private static final IpdActor MARKET_PM = new IpdActor(2L, "market", "MARKET_PM", 100L);
    private static final IpdActor RD_PM = new IpdActor(3L, "rd", "RD_PM", 100L);
    private static final IpdActor GROUP_LEADER = new IpdActor(4L, "leader", "GROUP_LEADER", 100L);
    private static final IpdActor GUEST = new IpdActor(5L, "guest", "GUEST", 100L);

    private static final long TEMPLATE_ID = 10L;
    private static final long PROJECT_ID = 200L;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SopTemplate.class);
        TableInfoHelper.initTableInfo(assistant, SopTemplateInstance.class);
        // IDOR 守卫在 lambdaQuery 中解析 Project/ProjectMember 列名；本类必须自带
        // 这两张表的 TableInfo，否则单独跑 -Dtest=本类 时会依赖其它测试类的静态缓存。
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, org.ruoyi.ipd.domain.ProjectMember.class);

        templateMapper = mock(SopTemplateMapper.class);
        instanceMapper = mock(SopTemplateInstanceMapper.class);
        auditLogService = mock(IAuditLogService.class);
        projectMemberMapper = mock(ProjectMemberMapper.class);
        projectMapper = mock(ProjectMapper.class);

        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(instanceMapper.insert(any(SopTemplateInstance.class))).thenAnswer(inv -> {
            SopTemplateInstance i = inv.getArgument(0);
            if (i.getId() == null) i.setId(System.nanoTime());
            return 1;
        });
        when(instanceMapper.updateById(any(SopTemplateInstance.class))).thenReturn(1);
        when(projectMapper.selectById(anyLong())).thenReturn(projectWithNullTenant());
        when(projectMemberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        service = new SopTemplateService(
            templateMapper, instanceMapper, auditLogService, projectMemberMapper, projectMapper);
    }

    private static Project projectWithNullTenant() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setTenantId(null);
        return p;
    }

    private static SopTemplate publishedTemplate(String actionCode, String tenantId) {
        return SopTemplate.builder()
            .id(TEMPLATE_ID).actionCode(actionCode).title("t").content("c")
            .templateCode("SOP-CONCEPT-DEEP").templateName("深管概念")
            .version(1L).status(SopTemplate.Status.PUBLISHED)
            .category(SopTemplate.Category.MIXED).delFlag("0").tenantId(tenantId)
            .build();
    }

    private static SopTemplateInstance instance(long id, long version, String status) {
        return SopTemplateInstance.builder()
            .id(id).templateId(TEMPLATE_ID).projectId(PROJECT_ID)
            .instanceVersion(version).snapshotJson("{}")
            .instantiatedAt(new Date()).status(status).delFlag("0").build();
    }

    private static ApiV1ErrorCode codeOf(Throwable ex) {
        return ((IpdBusinessException) ex).getErrorCode();
    }

    // ========== listInstancesByProject ==========

    @Test
    @DisplayName("listInstancesByProject：项目在职成员放行，按 instanceVersion 倒序透传")
    void listInstancesByProject_memberPasses() {
        when(instanceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            instance(2L, 2L, SopTemplateInstance.Status.ACTIVE),
            instance(1L, 1L, SopTemplateInstance.Status.SUPERSEDED)));

        List<SopTemplateInstance> rows = service.listInstancesByProject(PROJECT_ID, MARKET_PM);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getInstanceVersion()).isEqualTo(2L);
        assertThat(rows.get(1).getInstanceVersion()).isEqualTo(1L);
        verify(projectMemberMapper).selectCount(any(Wrapper.class));
    }

    @Test
    @DisplayName("[IDOR] listInstancesByProject：非项目成员 → FORBIDDEN 且零查询实例")
    void listInstancesByProject_nonMemberForbidden() {
        when(projectMemberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.listInstancesByProject(PROJECT_ID, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.FORBIDDEN));
        verify(instanceMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("[IDOR] listInstancesByProject：项目不存在 → FORBIDDEN（不泄漏存在性）")
    void listInstancesByProject_projectMissingForbidden() {
        when(projectMapper.selectById(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.listInstancesByProject(PROJECT_ID, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("listInstancesByProject：SUPER_ADMIN 豁免成员校验，零 DB 成员查询")
    void listInstancesByProject_superAdminBypassesMembership() {
        when(instanceMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());

        assertThat(service.listInstancesByProject(PROJECT_ID, ADMIN)).isEmpty();
        verify(projectMapper, never()).selectById(anyLong());
        verify(projectMemberMapper, never()).selectCount(any(Wrapper.class));
    }

    @Test
    @DisplayName("listInstancesByProject：projectId 非法（null/0/负数）→ PARAM_INVALID 零查询")
    void listInstancesByProject_invalidProjectId() {
        for (Long bad : new Long[]{null, 0L, -1L}) {
            assertThatThrownBy(() -> service.listInstancesByProject(bad, MARKET_PM))
                .isInstanceOf(IpdBusinessException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        }
        verify(instanceMapper, never()).selectList(any(Wrapper.class));
    }

    // ========== instantiate ==========

    @Test
    @DisplayName("instantiate：旧 ACTIVE 实例自动 SUPERSEDED + 审计 SUPERSEDE，新实例 version=max+1")
    void instantiate_supersedesExistingActiveAndBumpsVersion() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(publishedTemplate("C01", null));
        SopTemplateInstance oldActive = instance(50L, 1L, SopTemplateInstance.Status.ACTIVE);
        // 第 1 次 selectList = 现存 ACTIVE；第 2 次 = 同项目同模板全量历史
        when(instanceMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(oldActive))
            .thenReturn(List.of(oldActive));

        SopTemplateInstance fresh = service.instantiate(TEMPLATE_ID, PROJECT_ID, MARKET_PM);

        assertThat(oldActive.getStatus()).isEqualTo(SopTemplateInstance.Status.SUPERSEDED);
        verify(instanceMapper).updateById(oldActive);
        assertThat(fresh.getInstanceVersion()).isEqualTo(2L);
        assertThat(fresh.getStatus()).isEqualTo(SopTemplateInstance.Status.ACTIVE);
        assertThat(fresh.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(fresh.getTemplateId()).isEqualTo(TEMPLATE_ID);
        verify(auditLogService).append(org.mockito.ArgumentMatchers.argThat(
            (AuditLog a) -> "SUPERSEDE".equals(a.getAction())));
        verify(auditLogService).append(org.mockito.ArgumentMatchers.argThat(
            (AuditLog a) -> "INSTANTIATE".equals(a.getAction())));
    }

    @Test
    @DisplayName("instantiate：版本号跨历史自增（历史 v1/v2 → 新实例 v3）")
    void instantiate_versionIncrementsAcrossHistory() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(publishedTemplate("C01", null));
        when(instanceMapper.selectList(any(Wrapper.class)))
            .thenReturn(new ArrayList<>())
            .thenReturn(List.of(
                instance(1L, 1L, SopTemplateInstance.Status.SUPERSEDED),
                instance(2L, 2L, SopTemplateInstance.Status.ACTIVE)));

        SopTemplateInstance fresh = service.instantiate(TEMPLATE_ID, PROJECT_ID, MARKET_PM);

        assertThat(fresh.getInstanceVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("instantiate：实例继承模板租户（多租户隔离不被实例化抹平）")
    void instantiate_propagatesTemplateTenant() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(publishedTemplate("C01", "T-42"));
        when(instanceMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());

        SopTemplateInstance fresh = service.instantiate(TEMPLATE_ID, PROJECT_ID, ADMIN);

        assertThat(fresh.getTenantId()).isEqualTo("T-42");
        assertThat(fresh.getInstantiatedBy()).isEqualTo("1");
    }

    @Test
    @DisplayName("instantiate：非 PUBLISHED 模板 → 409 零写入")
    void instantiate_nonPublishedConflict() {
        SopTemplate draft = publishedTemplate("C01", null);
        draft.setStatus(SopTemplate.Status.DRAFT);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(draft);

        assertThatThrownBy(() -> service.instantiate(TEMPLATE_ID, PROJECT_ID, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        verify(instanceMapper, never()).insert(any(SopTemplateInstance.class));
    }

    @Test
    @DisplayName("instantiate：templateId 非法 → PARAM_INVALID 零写入")
    void instantiate_invalidTemplateId() {
        for (Long bad : new Long[]{null, 0L, -5L}) {
            assertThatThrownBy(() -> service.instantiate(bad, PROJECT_ID, MARKET_PM))
                .isInstanceOf(IpdBusinessException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        }
        verify(instanceMapper, never()).insert(any(SopTemplateInstance.class));
    }

    @Test
    @DisplayName("instantiate：projectId 非法 → PARAM_INVALID 零写入")
    void instantiate_invalidProjectId() {
        for (Long bad : new Long[]{null, 0L, -5L}) {
            assertThatThrownBy(() -> service.instantiate(TEMPLATE_ID, bad, MARKET_PM))
                .isInstanceOf(IpdBusinessException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        }
        verify(instanceMapper, never()).insert(any(SopTemplateInstance.class));
    }

    @Test
    @DisplayName("instantiate：GUEST 角色 → FORBIDDEN（未进成员校验）")
    void instantiate_guestForbidden() {
        assertThatThrownBy(() -> service.instantiate(TEMPLATE_ID, PROJECT_ID, GUEST))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.FORBIDDEN));
        verify(instanceMapper, never()).insert(any(SopTemplateInstance.class));
    }

    @Test
    @DisplayName("instantiate：RD_PM 与 GROUP_LEADER 均放行（BR-IPD-SOP-03 三角色）")
    void instantiate_rdPmAndGroupLeaderAllowed() {
        for (IpdActor actor : List.of(RD_PM, GROUP_LEADER)) {
            when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(publishedTemplate("C01", null));
            when(instanceMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());

            SopTemplateInstance fresh = service.instantiate(TEMPLATE_ID, PROJECT_ID, actor);

            assertThat(fresh.getStatus()).isEqualTo(SopTemplateInstance.Status.ACTIVE);
            assertThat(fresh.getInstantiatedBy()).isEqualTo(String.valueOf(actor.id()));
        }
    }

    @Test
    @DisplayName("instantiate：insert 未生成主键 → INTERNAL_ERROR（拒绝写入半成品）")
    void instantiate_insertWithoutPkInternalError() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(publishedTemplate("C01", null));
        when(instanceMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(instanceMapper.insert(any(SopTemplateInstance.class))).thenReturn(1);

        assertThatThrownBy(() -> service.instantiate(TEMPLATE_ID, PROJECT_ID, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.INTERNAL_ERROR));
    }

    // ========== supersedeInstance（超管运维路径） ==========

    @Test
    @DisplayName("supersedeInstance：ACTIVE → SUPERSEDED 并回读最新状态")
    void supersedeInstance_activeToSuperseded() {
        SopTemplateInstance active = instance(60L, 1L, SopTemplateInstance.Status.ACTIVE);
        SopTemplateInstance after = instance(60L, 1L, SopTemplateInstance.Status.SUPERSEDED);
        when(instanceMapper.selectById(60L)).thenReturn(active, after);

        SopTemplateInstance out = service.supersedeInstance(60L, ADMIN);

        assertThat(out.getStatus()).isEqualTo(SopTemplateInstance.Status.SUPERSEDED);
        verify(instanceMapper).updateById(active);
        verify(auditLogService).append(org.mockito.ArgumentMatchers.argThat(
            (AuditLog a) -> "SUPERSEDE".equals(a.getAction())));
    }

    @Test
    @DisplayName("supersedeInstance：实例不存在 → NOT_FOUND")
    void supersedeInstance_notFound() {
        when(instanceMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.supersedeInstance(999L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("supersedeInstance：已软删除（del_flag=1）→ NOT_FOUND 零写入")
    void supersedeInstance_softDeletedNotFound() {
        SopTemplateInstance deleted = instance(61L, 1L, SopTemplateInstance.Status.ACTIVE);
        deleted.setDelFlag("1");
        when(instanceMapper.selectById(61L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.supersedeInstance(61L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verify(instanceMapper, never()).updateById(any(SopTemplateInstance.class));
    }

    @Test
    @DisplayName("supersedeInstance：终态（SUPERSEDED/ARCHIVED）→ 409 幂等保护")
    void supersedeInstance_terminalStateConflict() {
        SopTemplateInstance superseded = instance(62L, 1L, SopTemplateInstance.Status.SUPERSEDED);
        when(instanceMapper.selectById(62L)).thenReturn(superseded);

        assertThatThrownBy(() -> service.supersedeInstance(62L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));

        SopTemplateInstance archived = instance(63L, 1L, SopTemplateInstance.Status.ARCHIVED);
        when(instanceMapper.selectById(63L)).thenReturn(archived);
        assertThatThrownBy(() -> service.supersedeInstance(63L, ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));

        verify(instanceMapper, never()).updateById(any(SopTemplateInstance.class));
    }

    @Test
    @DisplayName("supersedeInstance：instanceId 非法 → PARAM_INVALID 零写入")
    void supersedeInstance_invalidId() {
        for (Long bad : new Long[]{null, 0L, -3L}) {
            assertThatThrownBy(() -> service.supersedeInstance(bad, ADMIN))
                .isInstanceOf(IpdBusinessException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        }
        verify(instanceMapper, never()).updateById(any(SopTemplateInstance.class));
    }

    @Test
    @DisplayName("supersedeInstance：非超管 → FORBIDDEN 零写入（运维豁免只给超管）")
    void supersedeInstance_nonAdminForbidden() {
        assertThatThrownBy(() -> service.supersedeInstance(60L, MARKET_PM))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.FORBIDDEN));
        verify(instanceMapper, never()).selectById(anyLong());
        verify(instanceMapper, never()).updateById(any(SopTemplateInstance.class));
    }

    @Test
    @DisplayName("supersedeInstance：actor 为 null → UNAUTHORIZED（service 层不信任 controller 必传）")
    void supersedeInstance_nullActorUnauthorized() {
        assertThatThrownBy(() -> service.supersedeInstance(60L, null))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.UNAUTHORIZED));
    }
}
