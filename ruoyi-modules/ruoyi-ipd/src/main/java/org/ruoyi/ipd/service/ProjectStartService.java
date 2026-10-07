package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开工审批。创建仍在 {@link ProjectService}，这里只处理批准、拒绝和再次提交。
 *
 * <p>2026-10-07：批准开工前加双PM 成对校验（AC-TEAM-10 / BR-TEAM-10）。
 * 单条绑定校验在 {@code ProjectMemberServiceImpl.bindMember}，但「一个项目同时有在职市场PM 和研发PM」
 * 这一条全局无任何代码保证——已实证零成员项目能进 TEAMING，到 G1/G5 双签时缺失侧永远无签署人、
 * Gate 永久 PENDING。此处是唯一能真正拦住进入 TEAMING 的点（阶段推进只要求「任意成员」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStartService {

    private final ProjectMapper projectMapper;
    private final ProductMapper productMapper;
    private final ProductLineMapper lineMapper;
    private final ProjectBootstrapService projectBootstrapService;
    private final IProjectCertService projectCertService;
    private final IAuditLogService auditLogService;
    private final StateMachineGuard stateMachineGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductRetirementService retirementService;

    /**
     * 双PM 成对校验所需成员表。setter 注入（不扩构造签名，保住 P131DatabaseIntegrationTest
     * 等既有 7 参构造入口）；生产 Spring 必装配，缺失时 {@link #assertDualPmBound} 降级放行并告警。
     */
    @Autowired(required = false)
    private ProjectMemberMapper projectMemberMapper;

    public void setProjectMemberMapper(ProjectMemberMapper projectMemberMapper) {
        this.projectMemberMapper = projectMemberMapper;
    }

    public void setProductRetirementService(ProductRetirementService retirementService) {
        this.retirementService = retirementService;
    }

    /**
     * 批准开工。没有负责人时只有超管能批；已有负责人时超管不能代批。
     *
     * @param projectId 待开工项目
     * @param actor     当前操作人
     * @return 进入组队并已生成六阶段的项目
     */
    @Transactional(rollbackFor = Exception.class)
    public Project approve(Long projectId, IpdActor actor) {
        Project project = requirePending(projectId);
        ProductLine line = requireLine(project);
        assertApprover(line, actor);
        assertDualPmBound(project.getId());
        if (project.getProductId() != null) {
            if (productMapper.isRetirementLockedForUpdate(project.getProductId())) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该产品已退市并只读，关联项目不能批准开工");
            }
            if (retirementService != null && (retirementService.isOrderStopped(project.getProductId()) || retirementService.isProductionStopped(project.getProductId()))) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该产品订货或生产已截止，关联项目不能批准开工");
            }
        }
        if (project.getProductId() == null) {
            Product created = new Product();
            created.setProductCode("INRD-" + project.getId());
            created.setProductName(project.getName());
            created.setStatus(Product.ST_IN_RD);
            created.setSource(Product.SRC_PM_NEW);
            created.setProductLineId(line.getId());
            created.setTenantId(line.getTenantId() == null ? "000000" : line.getTenantId());
            created.setDelFlag("0");
            created.setCreateBy(actor.id());
            productMapper.insert(created);
            created.setProjectId(project.getId());
            productMapper.updateById(created);
            project.setProductId(created.getId());
        }
        stateMachineGuard.preCheck("project", "PENDING_START", "TEAMING", "approveStart");
        project.setStatus("TEAMING");
        project.setCurrentStage("CONCEPT");
        if (projectMapper.updateById(project) != 1) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目已变更，请刷新后重试");
        }
        projectBootstrapService.bootstrap(project.getId(), actor.id());
        projectCertService.syncFromProject(project, actor.id());
        auditLogService.append(actor, "PROJECT_START_APPROVE", "projects", project.getId(),
            "line=" + line.getId());
        return project;
    }

    /**
     * 拒绝开工。项目保留，创建人可改范围后再次提交。
     *
     * @param projectId 待开工项目
     * @param actor     当前操作人
     * @return 已拒绝开工的项目
     */
    @Transactional(rollbackFor = Exception.class)
    public Project reject(Long projectId, IpdActor actor) {
        Project project = requirePending(projectId);
        ProductLine line = requireLine(project);
        assertApprover(line, actor);
        stateMachineGuard.preCheck("project", "PENDING_START", "START_REJECTED", "rejectStart");
        project.setStatus("START_REJECTED");
        if (projectMapper.updateById(project) != 1) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目已变更，请刷新后重试");
        }
        auditLogService.append(actor, "PROJECT_START_REJECT", "projects", project.getId(),
            "line=" + line.getId());
        return project;
    }

    /**
     * 创建人在同一项目上再次提交开工。
     *
     * @param projectId 已拒绝的项目
     * @param actor     必须是创建人
     * @return 重新待开工的项目
     */
    @Transactional(rollbackFor = Exception.class)
    public Project resubmit(Long projectId, IpdActor actor) {
        Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
            .eq(Project::getId, projectId).last("FOR UPDATE"));
        if (project == null || !"START_REJECTED".equals(project.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目不在可再次提交开工的状态");
        }
        if (actor == null || actor.id() == null || !actor.id().equals(project.getCreateBy())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "只有创建人可以再次提交开工");
        }
        stateMachineGuard.preCheck("project", "START_REJECTED", "PENDING_START", "resubmitStart");
        project.setStatus("PENDING_START");
        if (projectMapper.updateById(project) != 1) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目已变更，请刷新后重试");
        }
        auditLogService.append(actor, "PROJECT_START_RESUBMIT", "projects", project.getId(), null);
        return project;
    }

    private Project requirePending(Long projectId) {
        Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
            .eq(Project::getId, projectId).last("FOR UPDATE"));
        if (project == null || !"PENDING_START".equals(project.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目不在待开工状态");
        }
        return project;
    }

    private ProductLine requireLine(Project project) {
        Long lineId = projectMapper.findProductLineId(project.getId());
        if (lineId == null && project.getProductId() != null) {
            Product product = productMapper.selectById(project.getProductId());
            lineId = product == null ? null : product.getProductLineId();
        }
        ProductLine line = lineId == null ? null : lineMapper.selectById(lineId);
        if (line == null || "1".equals(line.getDelFlag())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目没有可审批的产品线");
        }
        return line;
    }

    /**
     * 双PM 成对校验（AC-TEAM-10 / BR-TEAM-10）：进入 TEAMING 前该项目必须已有
     * {@code exit_date IS NULL AND del_flag='0'} 的 MARKET_PM 与 RD_PM 各 ≥1 名。
     *
     * <p>软删口径与 {@code ProjectMemberServiceImpl.listActiveMembers} 同（{@code @TableLogic} 自动追加
     * {@code del_flag='0'}），与 AllowanceService 取数口径一致。
     *
     * <p>缺任一角色 ⇒ 抛 STATE_CONFLICT 且指名缺失角色；此时状态机 preCheck 尚未执行、项目状态未改、
     * 未生成产品/阶段图，事务回滚后项目仍为 PENDING_START。
     *
     * <p>装配缺失降级：{@code projectMemberMapper} 为 null 时无法判定——本仓既有 fail-closed 先例是拒绝
     * （{@code ProjectService.getVisibleById}），但 ProjectStartService 的 7 参构造被
     * {@code P131DatabaseIntegrationTest} 直接 {@code new} 且不装配 mapper，强拒会让该集成用例编译期就断。
     * <b>2026-10-07 已改为 fail-closed</b>：mapper 缺失时直接拒，不再告警放行。
     */
    private void assertDualPmBound(Long projectId) {
        if (projectMemberMapper == null) {
            // fail-closed（2026-10-07 改）：mapper 缺失意味着「判不出来」，不是「没问题」。
            // 放行等于把「这道闸根本没装」伪装成「查过了、双PM 齐」——失败长得像成功。
            // 「某个集成测试用例会断」不是放宽守卫的理由，那是测试该跟着改。
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "双PM 校验不可用（成员表访问未装配），不能批准开工");
        }
        boolean market = hasActiveRole(projectId, "MARKET_PM");
        boolean rd = hasActiveRole(projectId, "RD_PM");
        if (market && rd) {
            return;
        }
        String missing = market ? "研发PM（RD_PM）" : (rd ? "市场PM（MARKET_PM）" : "市场PM（MARKET_PM）与研发PM（RD_PM）");
        throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
            "未完成双PM 组队：项目缺少在任" + missing + "，不能批准开工");
    }

    /** 该项目是否存在指定角色的在职成员。 */
    private boolean hasActiveRole(Long projectId, String role) {
        Long count = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getRole, role)
            .isNull(ProjectMember::getExitDate));
        return count != null && count > 0;
    }

    private void assertApprover(ProductLine line, IpdActor actor) {
        if (actor == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权批准开工");
        }
        Long leader = line.getLeaderPersonId();
        if (leader == null) {
            if (!"SUPER_ADMIN".equals(actor.role())) {
                throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "没有负责人时只有系统管理员能批准开工");
            }
            return;
        }
        if (!leader.equals(actor.id())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "只有产品线负责人能批准开工");
        }
    }
}
