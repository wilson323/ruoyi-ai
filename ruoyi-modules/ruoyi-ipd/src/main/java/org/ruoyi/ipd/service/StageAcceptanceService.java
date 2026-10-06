package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 大阶段和小阶段共用的验收。
 * 小阶段提交仍走动作状态机的 DONE；本类只写入已有的确认人。
 * 大阶段提交写 project_stages，批准仍走原来的阶段推进。
 */
@Service
@RequiredArgsConstructor
public class StageAcceptanceService {

    private java.time.Clock clock = java.time.Clock.systemUTC();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClock(java.time.Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    StageAcceptanceService withClock(java.time.Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
        return this;
    }

    static final String PENDING_ACCEPT = "PENDING_ACCEPT";

    private org.ruoyi.ipd.mapper.ProjectMemberMapper projectMemberMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public void setProjectMemberMapper(org.ruoyi.ipd.mapper.ProjectMemberMapper mapper) {
        this.projectMemberMapper = java.util.Objects.requireNonNull(mapper);
    }

    private void requireProjectOperator(Project project, IpdActor actor) {
        if (projectMemberMapper == null && (actor == null || !"SUPER_ADMIN".equals(actor.role()))) {
            throw new org.ruoyi.ipd.common.IpdBusinessException(
                org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN, "非项目成员，无权访问");
        }
        org.ruoyi.ipd.security.IpdIdorGuard.requireProjectMemberOrSuperAdmin(
            actor, project.getId(), projectMemberMapper, projectMapper);
    }

    private final StageActionMapper stageActionMapper;
    private final ProjectStageMapper projectStageMapper;
    private final ProjectMapper projectMapper;
    private final ProductMapper productMapper;
    private final ProductLineMapper productLineMapper;
    private final GateEngine gateEngine;
    private final IAuditLogService auditLogService;

    /**
     * 产线负责人批准一个已提交的小阶段。
     *
     * @param actionId 动作实例
     * @param actor 当前登录人
     * @return 写上确认人之后的动作
     */
    @Transactional(rollbackFor = Exception.class)
    public StageAction acceptAction(Long actionId, IpdActor actor) {
        StageAction action = stageActionMapper.selectOne(new LambdaQueryWrapper<StageAction>()
            .eq(StageAction::getId, actionId).last("FOR UPDATE"));
        if (action == null) {
            throw new ServiceException("动作实例不存在: " + actionId);
        }
        if (!"DONE".equals(action.getStatus())) {
            throw new ServiceException("须先提交验收，才能由产线负责人批准");
        }
        if (action.getConfirmedBy() != null) {
            if (action.getConfirmedBy().equals(actor == null ? null : actor.id())) {
                return action;
            }
            throw new ServiceException("该动作已由产线负责人批准");
        }
        Project project = requireWritableProject(action.getProjectId());
        assertApprover(project, action.getUpdateBy(), actor);
        action.setConfirmedBy(actor.id());
        action.setConfirmedAt(Date.from(clock.instant()));
        if (stageActionMapper.updateById(action) != 1) {
            throw new ServiceException("批准失败，请刷新后重试");
        }
        auditLogService.append(AuditLog.builder()
            .operatorName(String.valueOf(actor.id()))
            .operatorRole(actor.role())
            .action("ACCEPT_STAGE_ACTION")
            .entityType("STAGE_ACTION")
            .entityId(action.getId())
            .reason("产线负责人批准小阶段")
            .build());
        return action;
    }

    /**
     * 提交人提交当前大阶段的验收。必做小阶段须已批准。
     *
     * @param projectId 项目
     * @param actor 提交人
     * @return 待批准的阶段行
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectStage submitStage(Long projectId, IpdActor actor) {
        if (actor == null || actor.id() == null) {
            throw new ServiceException("未认证或凭证失效");
        }
        Project project = requireWritableProject(projectId);
        requireProjectOperator(project, actor);
        ProjectStage stage = requireStage(projectId, project.getCurrentStage());
        if ("DONE".equals(stage.getStatus())) {
            throw new ServiceException("本阶段验收已通过");
        }
        if (PENDING_ACCEPT.equals(stage.getStatus()) && actor.id().equals(stage.getUpdateBy())) {
            return stage;
        }
        gateEngine.check(project, project.getCurrentStage());
        stage.setStatus(PENDING_ACCEPT);
        stage.setUpdateBy(actor.id());
        stage.setCompletedAt(null);
        projectStageMapper.updateById(stage);
        projectStageMapper.update(null, new UpdateWrapper<ProjectStage>()
            .eq("id", stage.getId())
            .set("completed_at", null));
        auditLogService.append(AuditLog.builder()
            .operatorName(String.valueOf(actor.id()))
            .operatorRole(actor.role())
            .action("SUBMIT_STAGE_ACCEPTANCE")
            .entityType("PROJECT_STAGE")
            .entityId(stage.getId())
            .reason("提交人提交大阶段验收")
            .build());
        return stage;
    }

    /**
     * 阶段推进前确认：当前大阶段已提交，且调用人是产线负责人，不是提交人。
     *
     * @param project 项目
     * @param operatorId 调用人
     * @param actorRole 调用人角色
     */
    public void assertBigStageApprovable(Project project, Long operatorId, String actorRole) {
        ProjectStage stage = requireStage(project.getId(), project.getCurrentStage());
        if (!PENDING_ACCEPT.equals(stage.getStatus())) {
            throw new ServiceException("须先由提交人提交本阶段验收");
        }
        assertApprover(project, stage.getUpdateBy(), new IpdActor(operatorId, null, actorRole, null));
    }

    /**
     * 阶段推进成功后，把当前大阶段标成已通过。
     *
     * @param projectId 项目
     * @param stageCode 刚离开的阶段
     */
    public void completeBigStage(Long projectId, String stageCode) {
        ProjectStage stage = requireStage(projectId, stageCode);
        stage.setStatus("DONE");
        stage.setCompletedAt(Date.from(clock.instant()));
        projectStageMapper.updateById(stage);
    }

    /**
     * Gate 放行前，该 Gate 对应大阶段的必做小阶段必须已经批准。
     *
     * @param gate 待放行的 Gate
     */
    public void assertGateActionsAccepted(Gate gate) {
        Project project = projectMapper.selectById(gate.getProjectId());
        if (project == null) {
            throw new ServiceException("项目不存在: " + gate.getProjectId());
        }
        String stage = ActionCatalog.stageOfGate(gate.getGateCode());
        if (stage == null) {
            return;
        }
        gateEngine.check(project, stage);
    }

    private Project requireWritableProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException("项目不存在: " + projectId);
        }
        if ("SUSPENDED".equals(project.getStatus()) || "ARCHIVED".equals(project.getStatus())) {
            throw new ServiceException("暂停/归档项目禁止变更动作状态");
        }
        return project;
    }

    private ProjectStage requireStage(Long projectId, String stageCode) {
        ProjectStage stage = projectStageMapper.selectOne(new LambdaQueryWrapper<ProjectStage>()
            .eq(ProjectStage::getProjectId, projectId)
            .eq(ProjectStage::getStageCode, stageCode)
            .eq(ProjectStage::getDelFlag, "0"));
        if (stage == null) {
            throw new ServiceException("项目阶段未建立: " + stageCode);
        }
        return stage;
    }

    /**
     * 没有产品线负责人时只有超管能批。有负责人时只有该人能批，提交人不能批自己的。
     *
     * <p>2026-10-06 修复：产线负责人按本类语义就是验收批准人，但组长无法成为项目成员
     * （AC-TEAM-10 只允许双 PM 绑定），若先走「项目成员/超管」守卫会把负责人永久拦死，
     * 形成无人能批的死锁（E2E 实测：PRJ-2026-904 C04 待产线负责人批准，负责人点击
     * 403 非项目成员）。因此先按权威的 product_lines.leader_person_id 判定，负责人
     * 本人绕过成员守卫，其余人仍需成员/超管身份。</p>
     */
    private void assertApprover(Project project, Long submitterId, IpdActor actor) {
        if (actor == null || actor.id() == null) {
            throw new ServiceException("未认证或凭证失效");
        }
        Long leaderId = lineLeaderId(project);
        boolean actorIsLineLeader = leaderId != null && leaderId.equals(actor.id());
        if (!actorIsLineLeader) {
            requireProjectOperator(project, actor);
        }
        if (submitterId != null && submitterId.equals(actor.id())) {
            throw new ServiceException("提交人不能批准自己提交的验收");
        }
        if (leaderId == null) {
            if (!"SUPER_ADMIN".equals(actor.role())) {
                throw new ServiceException("该产品线没有负责人，只有超管能批准");
            }
            return;
        }
        if (!leaderId.equals(actor.id())) {
            throw new ServiceException("只有该产品线负责人能批准");
        }
    }

    private Long lineLeaderId(Project project) {
        if (project.getProductId() == null) {
            return null;
        }
        Product product = productMapper.selectById(project.getProductId());
        if (product == null || product.getProductLineId() == null) {
            return null;
        }
        ProductLine line = productLineMapper.selectById(product.getProductLineId());
        if (line == null || !"ACTIVE".equals(line.getStatus())) {
            return null;
        }
        return line.getLeaderPersonId();
    }
}
