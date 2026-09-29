package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.copilotkit.SubStageGuideTool;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.IpdActionSkillMapService;
import org.ruoyi.ipd.service.IpdSubStageService;
import org.ruoyi.ipd.service.SubStageGuideOrchestrator;
import org.ruoyi.ipd.service.SubStageProgressService;
import org.ruoyi.ipd.vo.ActionSkillView;
import org.ruoyi.ipd.vo.GuideSequenceView;
import org.ruoyi.ipd.vo.SubStageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 小阶段引导接口 /api/v1/ipd/stage/sub-stages（Track A3/A4/A5 + C2 一次成型）。
 * <p>22 小阶段 + 每动作技能映射（69 归属经 ipd_action_skill_map 只读归并，内存 join 无 N+1）。
 * 响应一律 ApiV1Response code0/message 包络 + 字符串 ID（A0.3 契约）。
 * <p>guideEvents 为零新增端点语义的既有读端点（裁定 D-12）：STATE_DELTA 的 op.path=/subStageGuide，
 * value 增 guideSteps/advanceGate 两键（C2 编排器载荷，向后兼容）。
 */
@RestController
@RequestMapping("/api/v1/ipd/stage/sub-stages")
@RequiredArgsConstructor
public class SubStageController {

    private final IpdSubStageService subStageService;
    private final IpdActionSkillMapService skillMapService;
    private final IpdPermission ipdPermission;
    private final SubStageProgressService progressService;
    private final SubStageGuideOrchestrator guideOrchestrator;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    /** 小阶段引导全量查询（22 行 + 动作技能归并），需 ipd:stage-action:list 权限 */
    @GetMapping
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<SubStageView>> list() {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(buildGuide(subStageService.listAll(), skillMapService.listAll()));
    }

    /** 目录 + 映射内存归并（包级可见，A4 事件下发复用）；调用方保证 listAll 已按序。 */
    static List<SubStageView> buildGuide(List<IpdSubStage> subStages, List<IpdActionSkillMap> maps) {
        Map<String, List<IpdActionSkillMap>> bySub = new LinkedHashMap<>();
        for (IpdActionSkillMap m : maps) {
            bySub.computeIfAbsent(m.getSubStageCode(), k -> new ArrayList<>()).add(m);
        }
        List<SubStageView> out = new ArrayList<>();
        for (IpdSubStage s : subStages) {
            List<ActionSkillView> actions = new ArrayList<>();
            for (IpdActionSkillMap m : bySub.getOrDefault(s.getCode(), List.of())) {
                actions.add(new ActionSkillView(
                    m.getActionCode(),
                    ActionCatalog.byCode(m.getActionCode()).name(),
                    m.getSubStageCode(),
                    IpdActionSkillMapService.parseSkillNames(m.getSkillNames()),
                    m.getSortOrder()));
            }
            out.add(new SubStageView(
                String.valueOf(s.getId()), s.getCode(), s.getName(), s.getStageCode(),
                s.getSortOrder(), s.getIsGate(), s.getGateCode(), s.getSkillHint(),
                s.getOwnerRole(), actions));
        }
        return out;
    }

    /** AG-UI 小阶段引导工具事件序列（A4+C2）：返回完整 AG-UI 事件数组，前端按帧流式播放。 */
    @GetMapping("/guide-events")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<Map<String, Object>>> guideEvents(@RequestParam String subStageCode,
                                                                @RequestParam(required = false) Long projectId) {
        var actor = ipdPermission.requireInternal();
        if (projectId != null) {
            IpdIdorGuard.requireProjectMemberOrSuperAdmin(
                actor, projectId, projectMemberMapper, projectMapper);
        }
        SubStageView guide = buildGuide(subStageService.listAll(), skillMapService.listAll()).stream()
            .filter(v -> v.code().equals(subStageCode))
            .findFirst()
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "小阶段不存在: " + subStageCode));
        List<String> sourceRefs = new ArrayList<>();
        sourceRefs.add("ipd_sub_stage/" + guide.code());
        for (ActionSkillView a : guide.actions()) {
            sourceRefs.add("ipd_action_skill_map/" + a.actionCode());
        }
        // C2 一次成型：intro 换编排器话术 + value 增 guideSteps/advanceGate 两键（D-12 最小增强）
        GuideSequenceView seq = guideOrchestrator.buildSequence(projectId, guide.code());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("subStageCode", guide.code());
        if (projectId != null) {
            value.put("projectId", String.valueOf(projectId));
        }
        value.put("guideSteps", seq.steps());
        value.put("advanceGate", seq.advanceGate());
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("path", "/subStageGuide");
        op.put("value", value);
        List<Object> progressPatch = new ArrayList<>();
        progressPatch.add(op);
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            seq.introText(), guide, sourceRefs, progressPatch);
        return ApiV1Response.ok(events);
    }

    /** 项目级小阶段游标回读；刷新和重启后均以数据库状态为准。 */
    @GetMapping("/progress")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Map<String, Object>> progress(@RequestParam Long projectId) {
        var actor = ipdPermission.requireInternal();
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, projectId, projectMemberMapper, projectMapper);
        return ApiV1Response.ok(progressBody(progressService.current(projectId), false));
    }

    /** 小阶段原子推进；门禁未通过或版本冲突不会报告成功。 */
    @PostMapping("/advance")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION_EXECUTE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Map<String, Object>> advance(@RequestParam Long projectId,
                                                      @RequestParam String targetSubStageCode,
                                                      @RequestParam(required = false) Long expectedVersion) {
        var actor = ipdPermission.requireInternal();
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(
            actor, projectId, projectMemberMapper, projectMapper);
        return ApiV1Response.ok(progressBody(
            progressService.advance(projectId, targetSubStageCode, expectedVersion, actor.id()), true));
    }

    private static Map<String, Object> progressBody(SubStageProgressService.Progress progress, boolean advanced) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", progress.projectId());
        body.put("currentStage", progress.currentStage());
        body.put("currentSubStageCode", progress.currentSubStageCode());
        body.put("version", progress.version());
        body.put("gateResult", progress.gateResult());
        body.put("replayed", progress.replayed());
        body.put("advanced", advanced && !progress.replayed());
        return body;
    }
}
