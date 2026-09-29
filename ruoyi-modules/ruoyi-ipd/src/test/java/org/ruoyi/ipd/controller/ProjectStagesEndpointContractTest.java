package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectStage;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.IStageActionService;
import org.ruoyi.ipd.service.ProjectService;
import org.ruoyi.ipd.service.StageActionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-6.1 契约单测（SSOT=scripts/check-e2e-fe-be.sh L167）：
 * {@code GET /api/v1/projects/{id}/stages} → 200 + code=0 + data.stages[].id/name。
 *
 * <p>锁定维度：URL 绑定与鉴权注解（含 IDOR 读腿 getVisibleById）、包络与视图映射、
 * 空态、未认证 fail-closed、JSON 形状（id 字符串化）、服务层查询链（project_id+del_flag+稳定排序）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectStagesEndpointContractTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private IStageActionService stageActionService;
    @Mock
    private IpdPermission ipdPermission;

    @InjectMocks
    private ProjectController controller;

    private static final Long PROJECT_ID = 9140001L;

    /** 真库 project_stages 行的真实形状（P1-3.1 bootstrap 六阶段，非随机组合）。 */
    private static ProjectStage stage(long id, String code, String name, int sort, String status) {
        return ProjectStage.builder()
            .id(id).projectId(PROJECT_ID).stageCode(code).stageName(name)
            .sortOrder(sort).status(status).tenantId("000000").delFlag("0")
            .build();
    }

    private static List<ProjectStage> sixStages() {
        return List.of(
            stage(915000000000000001L, "CONCEPT", "概念", 1, "DONE"),
            stage(915000000000000002L, "PLAN", "计划", 2, "IN_PROGRESS"),
            stage(915000000000000003L, "DEV", "开发", 3, "NOT_STARTED"),
            stage(915000000000000004L, "VALID", "验证", 4, "NOT_STARTED"),
            stage(915000000000000005L, "LAUNCH", "发布", 5, "NOT_STARTED"),
            stage(915000000000000006L, "LIFECYCLE", "生命周期", 6, "NOT_STARTED"));
    }

    @Test
    @DisplayName("[P3-6.1-1] URL 契约锁：GET /api/v1/projects/{id}/stages + ipd:project:query 注解 + @PathVariable id")
    void urlBinding_and_authAnnotation_locked() throws Exception {
        RequestMapping cls = ProjectController.class.getAnnotation(RequestMapping.class);
        assertThat(cls.value()).contains("/api/v1/projects");
        Method m = ProjectController.class.getDeclaredMethod("listStages", Long.class);
        GetMapping get = m.getAnnotation(GetMapping.class);
        assertThat(get).isNotNull();
        assertThat(get.value()).containsExactly("/{id}/stages");
        SaCheckPermission sa = m.getAnnotation(SaCheckPermission.class);
        assertThat(sa).isNotNull();
        assertThat(sa.value()).containsExactly(IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY);
        assertThat(sa.type()).isEqualTo(IpdAuthSession.LOGIN_TYPE);
        assertThat(m.getParameterAnnotations()[0][0]).isInstanceOf(PathVariable.class);
    }

    @Test
    @DisplayName("[P3-6.1-2] 正常返回：code=0 + data.stages[].id/name（六阶段真实形状，IDOR 读腿=getVisibleById）")
    void stages_returns_envelope_with_id_name() {
        IpdActor actor = new IpdActor(900101L, "ipd-admin", "SUPER_ADMIN", null);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.getVisibleById(PROJECT_ID, actor))
            .thenReturn(Project.builder().id(PROJECT_ID).name("契约项目").build());
        when(stageActionService.listStagesByProject(PROJECT_ID)).thenReturn(sixStages());

        ApiV1Response<ProjectController.ProjectStagesView> resp = controller.listStages(PROJECT_ID);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getMessage()).isNotBlank();
        assertThat(resp.getData().stages()).hasSize(6);
        assertThat(resp.getData().stages()).extracting(ProjectController.ProjectStageView::id)
            .containsExactly("915000000000000001", "915000000000000002", "915000000000000003",
                "915000000000000004", "915000000000000005", "915000000000000006");
        assertThat(resp.getData().stages()).extracting(ProjectController.ProjectStageView::name)
            .containsExactly("概念", "计划", "开发", "验证", "发布", "生命周期");
        verify(projectService).getVisibleById(PROJECT_ID, actor);
    }

    @Test
    @DisplayName("[P3-6.1-3] 空态：项目未 bootstrap 六阶段 → data.stages=[] 不抛（code=0）")
    void stages_empty_when_project_has_no_stage_rows() {
        IpdActor actor = new IpdActor(900103L, "ipd-market", "MARKET_PM", 10L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.getVisibleById(PROJECT_ID, actor))
            .thenReturn(Project.builder().id(PROJECT_ID).name("新项目").build());
        when(stageActionService.listStagesByProject(PROJECT_ID)).thenReturn(List.of());

        ApiV1Response<ProjectController.ProjectStagesView> resp = controller.listStages(PROJECT_ID);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData().stages()).isEmpty();
    }

    @Test
    @DisplayName("[P3-6.1-4] 未认证 fail-closed：requireInternal 抛 401 透传，下游零调用")
    void stages_requires_internal_session() {
        when(ipdPermission.requireInternal())
            .thenThrow(new IpdPermissionException(401, ApiV1ErrorCode.UNAUTHORIZED));

        assertThatThrownBy(() -> controller.listStages(PROJECT_ID))
            .isInstanceOf(IpdPermissionException.class);
        verify(projectService, never()).getVisibleById(anyLong(), any());
        verify(stageActionService, never()).listStagesByProject(anyLong());
    }

    @Test
    @DisplayName("[P3-6.1-5] 不可见项目（IDOR 读腿）：getVisibleById 抛 30001 透传，阶段查询零调用")
    void stages_invisible_project_short_circuits() {
        IpdActor actor = new IpdActor(900104L, "ipd-rd", "RD_PM", 20L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.getVisibleById(PROJECT_ID, actor))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该项目"));

        assertThatThrownBy(() -> controller.listStages(PROJECT_ID))
            .isInstanceOf(IpdBusinessException.class);
        verify(stageActionService, never()).listStagesByProject(anyLong());
    }

    @Test
    @DisplayName("[P3-6.1-6] JSON 形状锁：data.stages[0].id 为字符串（大数精度铁律）+ name 可渲染")
    void stages_json_shape_string_ids() throws Exception {
        var view = new ProjectController.ProjectStagesView(
            sixStages().stream()
                .map(s -> new ProjectController.ProjectStageView(String.valueOf(s.getId()),
                    s.getStageName(), s.getStageCode(), s.getStatus(), s.getSortOrder()))
                .toList());
        JsonNode root = new ObjectMapper().valueToTree(ApiV1Response.ok(view));
        assertThat(root.path("code").asInt()).isEqualTo(0);
        JsonNode first = root.path("data").path("stages").get(0);
        assertThat(first.path("id").isTextual()).isTrue();
        assertThat(first.path("id").asText()).isEqualTo("915000000000000001");
        assertThat(first.path("name").asText()).isEqualTo("概念");
    }

    // ------------------------------------------------------------------
    // 服务层查询链锁（Qa04SoftDeleteFilterTest 范式：捕获 wrapper 还原 SQL 片段）
    // ------------------------------------------------------------------

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
            new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProjectStage.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("[P3-6.1-7] 查询链锁：listStagesByProject = project_id + del_flag='0' + ORDER BY sort_order,id")
    void listStagesByProject_query_chain_locked() {
        ProjectStageMapper stageMapper = Mockito.mock(ProjectStageMapper.class);
        StageActionService svc = new StageActionService(null, null, null, stageMapper, null);
        when(stageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(sixStages());

        List<ProjectStage> rows = svc.listStagesByProject(PROJECT_ID);

        assertThat(rows).hasSize(6);
        ArgumentCaptor<LambdaQueryWrapper<ProjectStage>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(stageMapper).selectList(captor.capture());
        LambdaQueryWrapper<ProjectStage> w = captor.getValue();
        assertThat(w.getSqlSegment())
            .contains("project_id =").contains("del_flag =")
            .contains("ORDER BY sort_order ASC,id ASC");
        assertThat(w.getParamNameValuePairs().values()).contains(PROJECT_ID, "0");
    }
}
