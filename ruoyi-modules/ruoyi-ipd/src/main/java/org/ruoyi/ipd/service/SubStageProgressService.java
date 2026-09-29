package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 项目小阶段游标；六大阶段仍由 ProjectService 独立推进。 */
@Service
@RequiredArgsConstructor
public class SubStageProgressService {

    private final ProjectMapper projectMapper;
    private final IpdSubStageService subStageService;
    private final SubStageGateService gateService;
    private final IAuditLogService auditLogService;

    public Progress current(Long projectId) {
        return view(requireProject(projectId), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public Progress advance(Long projectId, String targetCode, Long expectedVersion, Long actorId) {
        if (targetCode == null || targetCode.isBlank() || expectedVersion == null || expectedVersion < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "targetSubStageCode 与非负 expectedVersion 必填");
        }
        Project project = requireProject(projectId);
        List<IpdSubStage> ordered = subStageService.listAll().stream()
            .filter(s -> !"1".equals(s.getIsResident()))
            .toList();
        int target = indexOf(ordered, targetCode);
        if (target < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "目标小阶段不存在或为常驻节点: " + targetCode);
        }
        if (targetCode.equals(project.getCurrentSubStageCode())) {
            return view(project, true); // 同一目标重放不重复推进、审计或门禁副作用
        }
        if (project.getSubStageVersion() == null || !expectedVersion.equals(project.getSubStageVersion())) {
            throw conflict("小阶段版本已变化，请回读后重试");
        }
        if ("SUSPENDED".equals(project.getStatus()) || "ARCHIVED".equals(project.getStatus())) {
            throw conflict("暂停或归档项目不可推进小阶段");
        }
        IpdSubStage next = ordered.get(target);
        if (!next.getStageCode().equals(project.getCurrentStage())) {
            throw conflict("目标小阶段与项目当前大阶段不一致");
        }
        int current = indexOf(ordered, project.getCurrentSubStageCode());
        if (project.getCurrentSubStageCode() == null) {
            // 存量项目可从其已持久化的大阶段首节点开始；不推断此前小阶段已完成。
            if (target > 0 && next.getStageCode().equals(ordered.get(target - 1).getStageCode())) {
                throw conflict("尚未开始引导，只能进入当前大阶段首个小阶段");
            }
        } else if (current < 0 || target != current + 1) {
            throw conflict("小阶段只能按目录顺序推进一个节点");
        }

        gateService.assertAdvanceAllowed(projectId, targetCode, !"LEGACY".equals(project.getSource()));
        int changed = projectMapper.advanceSubStage(projectId, project.getTenantId(), project.getCurrentStage(),
            project.getCurrentSubStageCode(), targetCode, expectedVersion, actorId);
        if (changed != 1) {
            Project latest = requireProject(projectId);
            if (targetCode.equals(latest.getCurrentSubStageCode())) {
                return view(latest, true);
            }
            throw conflict("小阶段已被并发推进，请回读后重试");
        }
        Project persisted = requireProject(projectId);
        if (!targetCode.equals(persisted.getCurrentSubStageCode())
            || persisted.getSubStageVersion() == null
            || persisted.getSubStageVersion() != expectedVersion + 1) {
            throw conflict("小阶段推进回读不一致");
        }
        auditLogService.append(AuditLog.builder()
            .operatorId(actorId)
            .action("PROJECT_SUB_STAGE_ADVANCE")
            .entityType("projects")
            .entityId(projectId)
            .tenantId(project.getTenantId())
            .beforeData(AuditEventData.json("currentSubStageCode", project.getCurrentSubStageCode(),
                "version", expectedVersion))
            .afterData(AuditEventData.json("currentSubStageCode", targetCode,
                "version", persisted.getSubStageVersion(), "gateResult", "PASSED"))
            .reason("项目小阶段推进")
            .build());
        return view(persisted, false);
    }

    private Project requireProject(Long projectId) {
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 必填");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    private static int indexOf(List<IpdSubStage> ordered, String code) {
        if (code == null) return -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (code.equals(ordered.get(i).getCode())) return i;
        }
        return -1;
    }

    private static IpdBusinessException conflict(String message) {
        return new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, message);
    }

    private static Progress view(Project project, boolean replayed) {
        return new Progress(String.valueOf(project.getId()), project.getCurrentStage(),
            project.getCurrentSubStageCode(), project.getSubStageVersion(),
            project.getLastSubStageGateResult(), replayed);
    }

    public record Progress(String projectId, String currentStage, String currentSubStageCode,
                           Long version, String gateResult, boolean replayed) { }
}
