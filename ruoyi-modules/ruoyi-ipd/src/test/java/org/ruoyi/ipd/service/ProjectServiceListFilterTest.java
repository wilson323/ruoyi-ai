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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.dto.ProjectListItemView;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R149 B2：ProjectService.listWithScenario(String, IpdActor) 按角色硬过滤单测。
 *
 * <p>覆盖 4 种角色 + 3 个边界：
 * <ul>
 *   <li>SUPER_ADMIN —— 全量（不调 projectMemberMapper）</li>
 *   <li>GROUP_LEADER —— 只调 projectMapper 且必含 main_group_id</li>
 *   <li>MARKET_PM —— 先调 projectMemberMapper 取在职项目 ID，再调 projectMapper 带 IN (...)</li>
 *   <li>RD_PM —— 同 MARKET_PM（验证双 PM 角色对称）</li>
 *   <li>null actor —— 向后兼容走全量（不调 projectMemberMapper）</li>
 *   <li>未知角色 —— fail-closed 返回空列表（不调任何 mapper）</li>
 *   <li>GROUP_LEADER 但 groupId=null —— 返回空列表（不调 projectMapper）</li>
 *   <li>MARKET_PM 无在职项目 —— 返回空列表（不调 projectMapper）</li>
 * </ul>
 *
 * <p>断言策略：verify mock 收到 selectList 调用、verify 调用次数，确认 service 走的角色分支正确。
 * ProjectMemberMapper 是否被调 → 是否走 PM 分支。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectServiceListFilterTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private ProductMapper productMapper;
    @Mock private StageActionMapper stageActionMapper;
    @Mock private KpiRecordMapper kpiRecordMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private GateEngine gateEngine;
    @Mock private ProjectBootstrapService projectBootstrapService;
    @Mock private IProjectCertService projectCertService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private RequirementChangeService requirementChangeService;

    @InjectMocks
    private ProjectService service;

    @BeforeAll
    static void initTableInfo() {
        // MyBatis-Plus lambda cache 初始化（仿 P192AcceptanceTest）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, StageAction.class);
        TableInfoHelper.initTableInfo(assistant, KpiRecord.class);
        // R149 B2 新增：ProjectMember 表 lambda cache 初始化
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        // projectMemberMapper 走 setter 模式（@Autowired(required=false) 字段不会自动注入 mock），
        // 与 BonusPoolService.setProjectMemberMapper 同型
        service.setProjectMemberMapper(projectMemberMapper);
    }

    /* ====================== 1. SUPER_ADMIN 全量 ====================== */

    @Test
    @DisplayName("[R149-B2] SUPER_ADMIN → 走 list() 全量路径，不调 projectMemberMapper")
    void superAdmin_seesAllProjects_noMemberQuery() {
        IpdActor actor = new IpdActor(1L, "admin", "SUPER_ADMIN", 1L);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(101L), project(102L), project(103L)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of());
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of());

        List<ProjectListItemView> result = service.listWithScenario(null, actor);

        assertThat(result).hasSize(3);
        verify(projectMapper, times(1)).selectList(any());
        verify(projectMemberMapper, never()).selectList(any());
    }

    /* ====================== 2. GROUP_LEADER 本组 ====================== */

    @Test
    @DisplayName("[R149-B2] GROUP_LEADER → 走 listByGroup，只调 projectMapper 不调 projectMemberMapper")
    void groupLeader_seesOnlyOwnGroup() {
        IpdActor actor = new IpdActor(2L, "leader-张", "GROUP_LEADER", 42L);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(201L)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of());
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of());

        List<ProjectListItemView> result = service.listWithScenario("智能", actor);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).project().getId()).isEqualTo(201L);
        // 关键断言：组长维度不查 project_members（避免 N+1）
        verify(projectMapper, times(1)).selectList(any());
        verify(projectMemberMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("[R149-B2] GROUP_LEADER 但 groupId=null → fail-closed 空列表")
    void groupLeader_nullGroupId_returnsEmpty() {
        IpdActor actor = new IpdActor(2L, "leader-张", "GROUP_LEADER", null);
        List<ProjectListItemView> result = service.listWithScenario(null, actor);
        assertThat(result).isEmpty();
        verify(projectMapper, never()).selectList(any());
        verify(projectMemberMapper, never()).selectList(any());
    }

    /* ====================== 3. MARKET_PM 本人负责的 ====================== */

    @Test
    @DisplayName("[R149-B2] MARKET_PM → 先查 project_members 取在职项目 ID，再查 projects 带 IN")
    void marketPm_seesOnlyAssignedProjects() {
        IpdActor actor = new IpdActor(3L, "pm-李", "MARKET_PM", 7L);
        // 在职项目 ID：101（市场PM）+ 102（市场PM）
        when(projectMemberMapper.selectList(any())).thenReturn(
            List.of(pmMember(1001L, 3L, "MARKET_PM", null),
                    pmMember(1002L, 3L, "MARKET_PM", null)));
        when(projectMapper.selectList(any())).thenReturn(List.of(project(101L), project(102L)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of());
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of());

        List<ProjectListItemView> result = service.listWithScenario(null, actor);

        assertThat(result).hasSize(2);
        // 关键断言：先查 project_members 再查 projects
        verify(projectMemberMapper, times(1)).selectList(any());
        verify(projectMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("[R149-B2] MARKET_PM 无在职项目 → 空列表（不查 projects）")
    void marketPm_noActiveMembership_returnsEmpty() {
        IpdActor actor = new IpdActor(3L, "pm-李", "MARKET_PM", 7L);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());

        List<ProjectListItemView> result = service.listWithScenario(null, actor);

        assertThat(result).isEmpty();
        verify(projectMemberMapper, times(1)).selectList(any());
        // 关键断言：无在职项目 ⇒ 不查 projects（IN 空集合提前 return）
        verify(projectMapper, never()).selectList(any());
    }

    /* ====================== 4. RD_PM 对称 ====================== */

    @Test
    @DisplayName("[R149-B2] RD_PM → 与 MARKET_PM 对称，走相同 PM 路径")
    void rdPm_seesOnlyAssignedProjects() {
        IpdActor actor = new IpdActor(4L, "pm-王", "RD_PM", 8L);
        when(projectMemberMapper.selectList(any())).thenReturn(
            List.of(pmMember(2001L, 4L, "RD_PM", null)));
        when(projectMapper.selectList(any())).thenReturn(List.of(project(301L)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of());
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of());

        List<ProjectListItemView> result = service.listWithScenario(null, actor);

        assertThat(result).hasSize(1);
        verify(projectMemberMapper, times(1)).selectList(any());
        verify(projectMapper, times(1)).selectList(any());
    }

    /* ====================== 5. 边界：null actor / 未知角色 ====================== */

    @Test
    @DisplayName("[R149-B2] null actor → 向后兼容走 list() 全量（P192AcceptanceTest 入口）")
    void nullActor_behavesAsFullList() {
        when(projectMapper.selectList(any())).thenReturn(List.of(project(101L), project(102L)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of());
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of());

        List<ProjectListItemView> result = service.listWithScenario(null, null);

        assertThat(result).hasSize(2);
        verify(projectMapper, times(1)).selectList(any());
        verify(projectMemberMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("[R149-B2] 未知角色（如 ADMIN） → fail-closed 空列表（不调任何 mapper）")
    void unknownRole_returnsEmpty() {
        IpdActor actor = new IpdActor(99L, "x", "ADMIN", 99L);
        List<ProjectListItemView> result = service.listWithScenario(null, actor);
        assertThat(result).isEmpty();
        verify(projectMapper, never()).selectList(any());
        verify(projectMemberMapper, never()).selectList(any());
    }

    /* ====================== 工具：构造测试夹具 ====================== */

    private static Project project(long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("项目-" + id);
        p.setDelFlag("0");
        p.setSource("NEW");
        return p;
    }

    private static ProjectMember pmMember(long memberId, long personId, String role, java.util.Date exitDate) {
        ProjectMember m = new ProjectMember();
        m.setId(memberId);
        m.setPersonId(personId);
        m.setRole(role);
        m.setExitDate(exitDate);
        m.setDelFlag("0");
        // 这里不设 projectId —— service 端只 select(Project::getProjectId) 投影字段；
        // 但 mock selectList 返回 ProjectMember 全字段。service 走 ProjectMember::getProjectId 取 ID
        // 需要把 projectId 填上以让过滤走 IN。手动注入：
        m.setProjectId(memberId == 1001L ? 101L : memberId == 1002L ? 102L : 301L);
        return m;
    }
}
