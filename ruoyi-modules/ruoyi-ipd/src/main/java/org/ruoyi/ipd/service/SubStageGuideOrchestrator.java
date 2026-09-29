package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.copilotkit.CommandDegrader;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.seed.GuideScriptCatalog;
import org.ruoyi.ipd.seed.GuideScriptCatalog.GuideScript;
import org.ruoyi.ipd.vo.AdvanceGateView;
import org.ruoyi.ipd.vo.GuideSequenceView;
import org.ruoyi.ipd.vo.GuideStepView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 小阶段引导编排器（Track C2）：小阶段 → 动作序列 → 话术 → 降级命令步骤 → 门禁视图。
 *
 * <p>排序口径：ipd_action_skill_map.sortOrder 升序（A2.5 listBySubStage 已排序，编排器不重排）；
 * 门禁口径：复用 {@link SubStageGateService#pendingBlockingActions}（不复制校验逻辑）；
 * 进度口径：stage_actions 单行读（status + history_mark），本服务零写。
 */
@Service
@RequiredArgsConstructor
public class SubStageGuideOrchestrator {

    /** stepState 词表（GuideStepView.stepState 锁定值域）。 */
    static final String STATE_PENDING = "PENDING";
    static final String STATE_NOT_INSTANTIATED = "NOT_INSTANTIATED";

    private final IpdSubStageService subStageService;
    private final IpdActionSkillMapService skillMapService;
    private final SubStageGateService gateService;
    private final StageActionMapper stageActionMapper;

    /** 生成完整引导序列；projectId 为空则进度全 PENDING、门禁按「无项目不拦」放行（查询侧降级，不伪造状态）。 */
    public GuideSequenceView buildSequence(Long projectId, String subStageCode) {
        IpdSubStage current = subStageService.getByCode(subStageCode);
        List<IpdSubStage> all = subStageService.listAll();
        String nextCode = nextSubStageCode(current, all);
        List<IpdActionSkillMap> maps = skillMapService.listBySubStage(subStageCode);
        Map<String, StageAction> progress = loadProgress(projectId, maps);
        List<GuideStepView> steps = new ArrayList<>();
        for (IpdActionSkillMap row : maps) {
            steps.add(buildStep(row, progress.get(row.getActionCode())));
        }
        AdvanceGateView gate = buildGate(projectId, nextCode);
        return new GuideSequenceView(current.getCode(), current.getName(), current.getStageCode(),
            introText(steps, gate, current), steps, gate);
    }

    /** 单动作步骤：目录派生（aiMode/blocking/name）+ C1 话术 + C3 降级 + 进度态。 */
    private GuideStepView buildStep(IpdActionSkillMap row, StageAction progressRow) {
        GuideScript script = GuideScriptCatalog.scriptOf(row.getActionCode());
        ActionDef def = ActionCatalog.byCode(row.getActionCode());   // 脏码 fail-loud（不变量⑤）
        return new GuideStepView(
            def.code(), def.name(), row.getSortOrder(),
            script.bindLevel().name(), def.execMode(),
            script.skillNames(), script.commandChain(),
            CommandDegrader.degradeChain(script.commandChain()),
            script.guidePrompt(), stepStateOf(progressRow),
            def.blocking());
    }

    /** 项目进度装载：一次查询该小阶段全部动作的 stage_actions 行（零写）。 */
    private Map<String, StageAction> loadProgress(Long projectId, List<IpdActionSkillMap> maps) {
        if (projectId == null || maps.isEmpty()) {
            return Map.of();
        }
        List<String> codes = maps.stream().map(IpdActionSkillMap::getActionCode).toList();
        return stageActionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StageAction>()
                    .eq(StageAction::getProjectId, projectId)
                    .in(StageAction::getActionCode, codes))
            .stream().collect(Collectors.toMap(StageAction::getActionCode, r -> r, (a, b) -> a));
    }

    /** 门禁视图：复用 SubStageGateService.pendingBlockingActions（校验逻辑唯一落点）。 */
    private AdvanceGateView buildGate(Long projectId, String nextSubStageCode) {
        if (nextSubStageCode == null) {           // KPI-S1 常驻：无推进目标
            return new AdvanceGateView(null, true, List.of());
        }
        if (projectId == null) {
            return new AdvanceGateView(nextSubStageCode, true, List.of());
        }
        List<String> pending = gateService.pendingBlockingActions(projectId, nextSubStageCode);
        return new AdvanceGateView(nextSubStageCode, pending.isEmpty(), List.copyOf(pending));
    }

    /** 下一小阶段：同 stage 内 sort+1；本 stage 末位 → 下一 stage 最小 sort；KPI-S1（sort=99 常驻）→ null。 */
    private String nextSubStageCode(IpdSubStage current, List<IpdSubStage> all) {
        if ("1".equals(current.getIsResident())) {
            return null;
        }
        String sameStageNext = all.stream()
            .filter(s -> s.getStageCode().equals(current.getStageCode()))
            .filter(s -> s.getSortOrder() > current.getSortOrder())
            .min(java.util.Comparator.comparingInt(IpdSubStage::getSortOrder))
            .map(IpdSubStage::getCode).orElse(null);
        if (sameStageNext != null) {
            return sameStageNext;
        }
        return all.stream()
            .filter(s -> !s.getStageCode().equals(current.getStageCode()))
            .filter(s -> IpdSubStageService.stageRank(s.getStageCode())
                > IpdSubStageService.stageRank(current.getStageCode()))
            .min(java.util.Comparator.comparingInt(s -> IpdSubStageService.stageRank(s.getStageCode()) * 100
                + s.getSortOrder()))
            .map(IpdSubStage::getCode).orElse(null);
    }

    /** 步骤状态（词表锁定）：缺行→NOT_INSTANTIATED；history_mark=HISTORICAL_MISSING 优先；否则 status。 */
    static String stepStateOf(StageAction row) {
        if (row == null) {
            return STATE_NOT_INSTANTIATED;
        }
        if (SubStageGateService.HISTORY_MISSING.equals(row.getHistoryMark())) {
            return SubStageGateService.HISTORY_MISSING;
        }
        return row.getStatus() == null ? STATE_PENDING : row.getStatus();
    }

    /** 开场话术：动作总数 + 阻断提示 + 门禁提示（A4.2 硬编码 intro 的替换物）。 */
    static String introText(List<GuideStepView> steps, AdvanceGateView gate, IpdSubStage current) {
        long blocking = steps.stream().filter(GuideStepView::blocking).count();
        StringBuilder sb = new StringBuilder("小阶段「").append(current.getName()).append("」共 ")
            .append(steps.size()).append(" 个动作，其中阻断动作 ").append(blocking)
            .append(" 个；请按序完成，每个动作的引导话术见 guideSteps。");
        if (gate.advanceAllowed() != null && !gate.advanceAllowed()) {
            sb.append(" 推进已阻断：待完成阻断动作 ")
                .append(String.join("、", gate.pendingBlockingCodes())).append("。");
        }
        return sb.toString();
    }
}
