package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectCertItem;
import org.ruoyi.ipd.dto.GateChecklistView;
import org.ruoyi.ipd.dto.LegacyImportReq;
import org.ruoyi.ipd.dto.LegacyImportResult;
import org.ruoyi.ipd.dto.LegacyImportRowResult;
import org.ruoyi.ipd.dto.ProjectCertListView;
import org.ruoyi.ipd.dto.ProjectCertManualReq;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.GateEngine;
import org.ruoyi.ipd.service.LaunchDateChangeService;
import org.ruoyi.ipd.service.GateCreationService;
import org.ruoyi.ipd.service.GateReviewService;
import org.ruoyi.ipd.service.LegacyImportService;
import org.ruoyi.ipd.service.IProjectCertService;
import org.ruoyi.ipd.domain.ProjectStage;
import org.ruoyi.ipd.service.ProjectService;
import org.ruoyi.ipd.service.ProjectStartService;
import org.ruoyi.ipd.service.StageAcceptanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 项目接口 /api/v1/projects（TS-09 统一响应 code=0）
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StageAcceptanceService stageAcceptanceService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProjectStartService projectStartService;
    private final GateEngine gateEngine;
    private final IProjectCertService projectCertService;
    private final LegacyImportService legacyImportService;
    private final LaunchDateChangeService launchDateChangeService;
    private final GateCreationService gateCreationService;
    private final GateReviewService gateReviewService;
    private final org.ruoyi.ipd.service.IStageActionService stageActionService;
    private final IpdPermission ipdPermission;
    /**
     * 项目归属守卫所需（守卫3 {@link IpdIdorGuard#requireProjectMemberOrSuperAdmin}）。
     *
     * <p>2026-10-03 归属收口：本控制器原有一组写口只调 {@code requireInternal()}（仅证明已登录），
     * 项目 id 由客户端指定，任何持状态变更权限码的内部用户都能操作别人项目的认证项 / Gate /
     * 上市日期。豁免登记见 {@code scripts/ownership-gate-exempt.txt}。
     *
     * <p><b>为什么是守卫3（在职项目成员）而不是守卫6（{@code assertSameGroupIpd} 同组）</b>：
     * 业务规则 BR-ORG-06 三层权限矩阵（{@code docs/ipd-系统说明/外部资源/IPD系统_AI开发主Prompt_v3.md}）
     * 「编辑项目」一行是普通PM「仅本人负责」/ 组长「仅本人名下」，<b>不含组维</b>；
     * 而 BR-ORG-01 定义「主组＝市场PM 所在组、协同组＝研发PM 所在组」，即研发PM 天然不在主组。
     * 若用「同组」，认证清单的法定责任人（研发PM，见六阶段动作清单 P10/V02）会被 403 挡在门外——
     * 那是把功能弄坏、而表面像修好了安全问题。守卫3 放行协同组在职成员，与矩阵「本人负责」同向。
     */
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    /**
     * 查询项目列表（P1-9.2：含 scenarioDaysRemaining + critical 派生字段）。
     * <p>R149 B2 升级：按角色硬过滤（服务端权威）
     * <ul>
     *   <li>SUPER_ADMIN：全部</li>
     *   <li>GROUP_LEADER：本组（{@code projects.main_group_id = actor.groupId}）</li>
     *   <li>MARKET_PM / RD_PM：本人在职参与的项目（{@code project_members} 在职，不限项目内角色；D1 修复后与详情口径对齐）</li>
     * </ul>
     * 提示横幅由前端维持（前端不改）；即使前端绕过横幅，本接口只返回授权范围。
     * 需 ipd:project:list 权限。
     */
    @GetMapping
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<org.ruoyi.ipd.dto.ProjectListItemView>> list(@RequestParam(required = false) String keyword) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(projectService.listWithScenario(keyword, actor));
    }

    /**
     * 查询项目详情，需 ipd:project:query 权限。
     * <p>AC-AUTH-09（看板卡 96b7b157）：详情读经 {@link ProjectService#getVisibleById} 可见性谓词
     * （超管/组长本组/在册成员），堵住同组非成员 PM 越权读他人项目全量详情的 IDOR 读腿。
     */
    @GetMapping("/{id}")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> get(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(projectService.getVisibleById(id, actor));
    }

    /**
     * P1-5.2：本阶段门禁必做集可解释清单（逐项原因 + 配置版本）。
     *
     * @param id    项目 ID
     * @param stage 可选阶段；缺省用项目 currentStage
     * @return 清单视图
     */
    @GetMapping("/{id}/gate-checklist")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<GateChecklistView> gateChecklist(@PathVariable Long id,
                                                          @RequestParam(required = false) String stage) {
        IpdActor actor = ipdPermission.requireInternal();
        Project project = projectService.getVisibleById(id, actor);
        return ApiV1Response.ok(gateEngine.explainChecklist(project, stage));
    }

    /** 创建项目，需 ipd:project:add 权限 */
    @PostMapping
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_CREATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> create(@RequestBody org.ruoyi.ipd.dto.ProjectCreateReq req) {
        // CODE-01：白名单 DTO，code/currentStage/status/source 由服务端定，客户端不可注入
        IpdActor actor = ipdPermission.requireProjectCreator();
        // 主组可选（2026-09-11 owner 拍板）：未选时后端权威自动归属操作人所在产品组（BR-ORG-01）
        return ApiV1Response.ok(projectService.create(
            req.toEntity(), actor.id(), actor.groupId(), req.marketPmId(), req.rdPmId(),
            req.productLineId(), actor.role()));
    }

    /** 变更项目状态，需 ipd:project:edit 权限 */
    @PostMapping("/{id}/status")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> changeStatus(@PathVariable Long id, @RequestParam String target) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(projectService.changeStatus(id, target, actor.id(), actor.groupId(), actor.role()));
    }

    /**
     * 提交人提交当前大阶段验收。阶段编码取项目当前阶段，不接受客户端指定。
     *
     * @param id 项目
     * @return 待产线负责人批准的阶段行
     */
    @PostMapping("/{id}/stage-acceptance")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<ProjectStage> submitStageAcceptance(@PathVariable Long id) {
        if (stageAcceptanceService == null) {
            throw new org.ruoyi.common.core.exception.ServiceException("阶段验收未启用");
        }
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(stageAcceptanceService.submitStage(id, actor));
    }

    /** 产品线负责人批准开工。没有负责人时只有超管能批。 */
    @PostMapping("/{id}/approve-start")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> approveStart(@PathVariable Long id) {
        if (projectStartService == null) {
            throw new org.ruoyi.common.core.exception.ServiceException("开工审批未启用");
        }
        return ApiV1Response.ok(projectStartService.approve(id, ipdPermission.requireInternal()));
    }

    /** 产品线负责人拒绝开工。项目保留，不另建。 */
    @PostMapping("/{id}/reject-start")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> rejectStart(@PathVariable Long id) {
        if (projectStartService == null) {
            throw new org.ruoyi.common.core.exception.ServiceException("开工审批未启用");
        }
        return ApiV1Response.ok(projectStartService.reject(id, ipdPermission.requireInternal()));
    }

    /** 创建人再次提交开工。 */
    @PostMapping("/{id}/resubmit-start")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_CREATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> resubmitStart(@PathVariable Long id) {
        if (projectStartService == null) {
            throw new org.ruoyi.common.core.exception.ServiceException("开工审批未启用");
        }
        return ApiV1Response.ok(projectStartService.resubmit(id, ipdPermission.requireInternal()));
    }

    /** 推进项目阶段，需 ipd:project:edit 权限 */
    @PostMapping("/{id}/advance-stage")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> advanceStage(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(projectService.advanceStage(id, actor.id(), actor.groupId(), actor.role()));
    }

    /**
     * P1-2.2：DRAFT 期内更新四基准；立项后锁定。
     *
     * @param id  项目
     * @param req 四基准白名单
     * @return 更新后项目
     */
    @PostMapping("/{id}/baselines")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> updateBaselines(@PathVariable Long id,
                                                  @RequestBody BaselinePatchReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        Project patch = Project.builder()
            .targetSalesAmount(req.targetSalesAmount())
            .targetChannelCount(req.targetChannelCount())
            .targetNps(req.targetNps())
            .targetSceneCount(req.targetSceneCount())
            .build();
        return ApiV1Response.ok(projectService.updateBaselines(id, patch, actor.id(), actor.groupId(), actor.role()));
    }

    /** 四基准补丁。 */
    public record BaselinePatchReq(
        java.math.BigDecimal targetSalesAmount,
        Integer targetChannelCount,
        Integer targetNps,
        Integer targetSceneCount) {
    }

    /**
     * P1-9.1：存量单条导入（超管）；历史缺失标记不阻断后续。
     *
     * @param req 白名单
     * @return 导入结果
     */
    @PostMapping("/legacy-import")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_CREATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<LegacyImportResult> legacyImport(@RequestBody LegacyImportReq req) {
        IpdActor actor = ipdPermission.requireAdmin();
        return ApiV1Response.ok(legacyImportService.importOne(req, actor.id()));
    }

    /**
     * P1-9.1：存量批量导入；错误行隔离，不回滚已成功行。
     *
     * @param rows 行列表
     * @return 逐行结果
     */
    @PostMapping("/legacy-import/batch")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_CREATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<LegacyImportRowResult>> legacyImportBatch(@RequestBody List<LegacyImportReq> rows) {
        IpdActor actor = ipdPermission.requireAdmin();
        return ApiV1Response.ok(legacyImportService.importBatch(rows, actor.id()));
    }

    /**
     * P1 / §5.1 HIGH-1.1：L08 上市日期初次录入（独立端点）——
     * 仅 DRAFT|CONFIRMED|TEAMING|ACTIVE 状态可调；写 INITIAL_LAUNCH_DATE 审计。
     * launch_date 已存在则拒绝（走双签流程）。
     */
    @PostMapping("/{id}/launch-date")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Project> recordLaunchDate(
            @PathVariable Long id,
            @RequestBody LaunchDateRecordReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        // 归属守卫（守卫3）：见 projectMapper 字段处说明——用「同组」会挡住院PM 这个法定责任人。
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, id, projectMemberMapper, projectMapper);
        Date date = Date.from(req.launchDate().atStartOfDay(ZoneId.systemDefault()).toInstant());
        return ApiV1Response.ok(launchDateChangeService.initialRecord(id, date, req.reason(), actor.id()));
    }

    /** L08 上市日期初次录入入参（独立于双签流程）。 */
    public record LaunchDateRecordReq(
            @NotNull @JsonFormat(pattern = "yyyy-MM-dd") LocalDate launchDate,
            @NotBlank @Size(max = 500) String reason) {
    }

    /**
     * P1 / §5.3 HIGH-1.1：Gate 评审创建入口 —— 独立端点 POST /api/v1/projects/{id}/gates?gateCode=。
     * <p><b>2026-10-07 F6 口径变更</b>：原「每 14 天最多创建 1 次」的时间冷却已废除——那把 G3
     * 的双周复评周期误当成了禁建窗口，导致 G3 永远建不出来。改为**在途去重**：同 project+gateCode
     * 已有 status='PENDING' 的行才拒建。写 GATE_AUTO_CREATE 审计。
     * <p>本端点是手工入口；自动入口在动作流转到 DONE 时触发（owner 2026-10-07 拍板「本阶段
     * 动作做完后建」，非阶段推进后立即建），二者共用 GateCreationService.autoCreateGate。
     */
    @PostMapping("/{id}/gates")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Gate> autoCreateGate(
            @PathVariable Long id,
            @RequestParam String gateCode) {
        IpdActor actor = ipdPermission.requireInternal();
        // 归属守卫（守卫3）：G3 主导方含研发PM，同样不得用「同组」。G4 主导方已于 2026-10-07 改归市场 PM。
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, id, projectMemberMapper, projectMapper);
        return ApiV1Response.ok(gateCreationService.autoCreateGate(id, gateCode, actor.id()));
    }

    /**
     * P0-10.23 补齐（R30 生产就绪）：项目维度 Gate 列表（替代前端手输 Gate 编号；
     * 原型 /api/key-gates?projectId= 正式落地）。与 POST /{id}/gates 对称，只读。
     *
     * @param id 项目 ID
     * @return 该项目全部未删 Gate（id 降序；空列表 = 尚无 Gate）
     */
    @GetMapping("/{id}/gates")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<Gate>> listProjectGates(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(gateReviewService.listByProject(id, actor));
    }

    /**
     * P3-6.1 契约（R128 P0 #2 补端点，SSOT=scripts/check-e2e-fe-be.sh L167）：
     * {@code GET /api/v1/projects/{id}/stages} → 200 + data.stages[].id/name。
     *
     * <p>鉴权与 {@code GET /{id}} 详情同口径：{@code ipd:project:query} 注解闸 +
     * {@link ProjectService#getVisibleById} 可见性谓词（AC-AUTH-09，堵 IDOR 读腿）。
     * 数据源 = 真库 project_stages（IStageActionService 只读查询）；无阶段返回空数组。
     */
    @GetMapping("/{id}/stages")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<ProjectStagesView> listStages(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        Project project = projectService.getVisibleById(id, actor);
        List<ProjectStageView> stages = stageActionService.listStagesByProject(project.getId()).stream()
            .map(s -> new ProjectStageView(String.valueOf(s.getId()), s.getStageName(),
                s.getStageCode(), s.getStatus(), s.getSortOrder()))
            .toList();
        return ApiV1Response.ok(new ProjectStagesView(stages));
    }

    /** 阶段视图（契约 stages[].id/name；id 字符串化守大数精度铁律，附 code/status/sortOrder 供前端渲染）。 */
    public record ProjectStageView(String id, String name, String code, String status, Integer sortOrder) { }

    /** 阶段清单包装（data.stages[]，与契约字面「含 stages[].id/name」对齐）。 */
    public record ProjectStagesView(List<ProjectStageView> stages) { }
    /**
     * P1-7.1：项目认证清单（含未知市场提示）。
     *
     * @param id 项目 ID
     * @return 清单视图
     */
    @GetMapping("/{id}/cert-items")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<ProjectCertListView> listCertItems(@PathVariable Long id) {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(projectCertService.listView(id));
    }

    /**
     * P1-7.1：按当前目标市场重新带出模板项（只增不重置 DONE）。
     *
     * @param id 项目
     * @return 新增条数
     */
    @PostMapping("/{id}/cert-items/sync")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Integer> syncCertItems(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        Project project = projectService.getById(id);
        // R212-⑤（看板卡 dbe1b6a7）：下传 actor，由服务层做组归属断言（跨组 → 30001/403）
        return ApiV1Response.ok(projectCertService.syncFromProjectAuthorized(project, actor));
    }

    /**
     * AC-PROD-12：手工补充认证项。
     *
     * @param id  项目
     * @param req 白名单
     * @return 新建项
     */
    @PostMapping("/{id}/cert-items")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<ProjectCertItem> addCertItem(@PathVariable Long id,
                                                      @RequestBody ProjectCertManualReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        // 归属守卫（守卫3）：认证清单 P10/V02 责任人＝研发PM（在协同组），禁止用「同组」。
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, id, projectMemberMapper, projectMapper);
        return ApiV1Response.ok(projectCertService.addManual(id, req, actor.id()));
    }

    /**
     * 更新项目认证项状态（DONE 后不被 sync 重置）。
     *
     * @param id     项目
     * @param itemId 清单项
     * @param target 目标状态
     * @return 更新后项
     */
    @PostMapping("/{id}/cert-items/{itemId}/status")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<ProjectCertItem> changeCertStatus(@PathVariable Long id,
                                                           @PathVariable Long itemId,
                                                           @RequestParam String target) {
        IpdActor actor = ipdPermission.requireInternal();
        // 归属守卫（守卫3）：同上，认证清单责任人为研发PM。
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, id, projectMemberMapper, projectMapper);
        return ApiV1Response.ok(projectCertService.changeStatus(id, itemId, target, actor.id()));
    }
}
