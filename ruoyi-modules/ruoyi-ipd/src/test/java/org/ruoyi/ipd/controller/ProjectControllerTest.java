package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.dto.ProjectListItemView;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.GateCreationService;
import org.ruoyi.ipd.service.GateEngine;
import org.ruoyi.ipd.service.GateReviewService;
import org.ruoyi.ipd.service.LaunchDateChangeService;
import org.ruoyi.ipd.service.LegacyImportService;
import org.ruoyi.ipd.service.ProjectCertService;
import org.ruoyi.ipd.service.ProjectService;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R149 B2：ProjectController.list() 按角色硬过滤端点单测。
 *
 * <p>本测试聚焦「controller → service 的 actor 传递链完整性」——
 * 验证 controller.list() 把 {@link IpdPermission#requireInternal()} 拿到的当前 actor
 * 原样传给 {@link ProjectService#listWithScenario(String, IpdActor)}，由 service 内做角色硬过滤。
 * <p>真实过滤行为（按 main_group_id / project_members.role+exit_date 等 SQL 路径）
 * 由 {@code ProjectServiceListFilterTest} 用 Mockito 模拟 ProjectMapper + ProjectMemberMapper 验证。
 *
 * <p>覆盖 3 种角色 + 1 个 null actor 兼容路径：
 * <ul>
 *   <li>SUPER_ADMIN —— 全量列表（service 拿到 actor.role=SUPER_ADMIN）</li>
 *   <li>GROUP_LEADER —— actor.groupId 透传</li>
 *   <li>MARKET_PM —— actor.id 透传（service 内部会按 project_members 取在职项目）</li>
 *   <li>RD_PM —— 同 MARKET_PM（验证双 PM 角色对称）</li>
 * </ul>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private GateEngine gateEngine;
    @Mock
    private ProjectCertService projectCertService;
    @Mock
    private LegacyImportService legacyImportService;
    @Mock
    private LaunchDateChangeService launchDateChangeService;
    @Mock
    private GateCreationService gateCreationService;
    @Mock
    private GateReviewService gateReviewService;
    @Mock
    private IpdPermission ipdPermission;

    @InjectMocks
    private ProjectController controller;

    /* ====================== 1. SUPER_ADMIN 全量 ====================== */

    @Test
    @DisplayName("[R149-B2] SUPER_ADMIN → controller 把 actor 透传给 service.listWithScenario 双参")
    void list_superAdmin_passesActorToService() {
        IpdActor actor = new IpdActor(1L, "admin", "SUPER_ADMIN", 1L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.listWithScenario(isNull(), eq(actor)))
            .thenReturn(List.of(itemView(101L), itemView(102L), itemView(103L)));

        ApiV1Response<List<ProjectListItemView>> resp = controller.list(null);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).hasSize(3);
        // 关键断言：service.listWithScenario 拿到 actor 原样（role=SUPER_ADMIN），不在 controller 改写
        verify(projectService, times(1)).listWithScenario(isNull(), eq(actor));
    }

    /* ====================== 2. GROUP_LEADER 本组 ====================== */

    @Test
    @DisplayName("[R149-B2] GROUP_LEADER → controller 把 actor（含 groupId=42）原样透传")
    void list_groupLeader_passesActorWithGroupId() {
        IpdActor actor = new IpdActor(2L, "leader-张", "GROUP_LEADER", 42L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.listWithScenario(eq("智能"), eq(actor)))
            .thenReturn(List.of(itemView(201L)));

        ApiV1Response<List<ProjectListItemView>> resp = controller.list("智能");

        assertThat(resp.getData()).hasSize(1);
        // 关键断言：service 拿到 actor.role=GROUP_LEADER + groupId=42，由 service 内部按 main_group_id 过滤
        ArgumentCaptor<IpdActor> captor = ArgumentCaptor.forClass(IpdActor.class);
        verify(projectService, times(1)).listWithScenario(eq("智能"), captor.capture());
        IpdActor captured = captor.getValue();
        assertThat(captured.role()).isEqualTo("GROUP_LEADER");
        assertThat(captured.groupId()).isEqualTo(42L);
        assertThat(captured.id()).isEqualTo(2L);
    }

    /* ====================== 3. MARKET_PM / RD_PM 本人负责的 ====================== */

    @Test
    @DisplayName("[R149-B2] MARKET_PM → controller 把 actor（含 id=3）原样透传")
    void list_marketPm_passesActorWithId() {
        IpdActor actor = new IpdActor(3L, "pm-李", "MARKET_PM", 7L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.listWithScenario(isNull(), eq(actor)))
            .thenReturn(List.of(itemView(301L), itemView(302L)));

        ApiV1Response<List<ProjectListItemView>> resp = controller.list(null);

        assertThat(resp.getData()).hasSize(2);
        // 关键断言：service 拿到 actor.role=MARKET_PM + id=3，由 service 内部按 project_members 在职 role 过滤
        ArgumentCaptor<IpdActor> captor = ArgumentCaptor.forClass(IpdActor.class);
        verify(projectService, times(1)).listWithScenario(isNull(), captor.capture());
        IpdActor captured = captor.getValue();
        assertThat(captured.role()).isEqualTo("MARKET_PM");
        assertThat(captured.id()).isEqualTo(3L);
    }

    @Test
    @DisplayName("[R149-B2] RD_PM → controller 把 actor 透传（与 MARKET_PM 对称）")
    void list_rdPm_passesActor() {
        IpdActor actor = new IpdActor(4L, "pm-王", "RD_PM", 8L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.listWithScenario(isNull(), eq(actor)))
            .thenReturn(List.of(itemView(401L)));

        ApiV1Response<List<ProjectListItemView>> resp = controller.list(null);

        assertThat(resp.getData()).hasSize(1);
        ArgumentCaptor<IpdActor> captor = ArgumentCaptor.forClass(IpdActor.class);
        verify(projectService, times(1)).listWithScenario(isNull(), captor.capture());
        assertThat(captor.getValue().role()).isEqualTo("RD_PM");
    }

    /* ====================== 工具：构造测试夹具 ====================== */

    private static ProjectListItemView itemView(long id) {
        Project p = new Project();
        p.setId(id);
        p.setName("项目-" + id);
        p.setDelFlag("0");
        // source=NEW ⇒ scenarioDaysRemaining=null/critical=null（与 P192AcceptanceTest 一致）
        p.setSource("NEW");
        return new ProjectListItemView(p, null, null, null);
    }
}
