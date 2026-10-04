package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开工审批。创建仍在 {@link ProjectService}，这里只处理批准、拒绝和再次提交。
 */
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
