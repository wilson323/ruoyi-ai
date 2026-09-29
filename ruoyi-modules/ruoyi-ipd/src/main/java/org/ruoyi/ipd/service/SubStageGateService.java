package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小阶段推进门禁（§2.8 不变量④，主计划 §0.1 业务规则）：
 * 目标小阶段之前的顺序小阶段中，is_blocking=1 的动作未完成即拒绝推进（40001 GATE_NOT_PASSED，fail-closed）。
 *
 * <p>已满足 = DONE/NA 或 history_mark=HISTORICAL_MISSING（历史缺失不伪造 DONE，门禁视为已满足——
 * 与 StageActionService 语义一致）；仅历史项目的实例缺行不拦。
 * <p>常驻小阶段（is_resident=1，KPI-S1）与未知码不可作为推进目标（10001 PARAM_INVALID）。
 * <p>本服务只读 stage_actions；项目游标由 SubStageProgressService 原子推进。
 */
@Service
@RequiredArgsConstructor
public class SubStageGateService {

    /** 已满足状态集：完成或豁免 */
    static final Set<String> SETTLED_STATUSES = Set.of("DONE", "NA");
    /** 历史缺失标记（StageActions.history_mark 既有值） */
    static final String HISTORY_MISSING = "HISTORICAL_MISSING";

    private final IpdSubStageService subStageService;
    private final IpdActionSkillMapService skillMapService;
    private final StageActionMapper stageActionMapper;

    /** 推进前校验：前序阻断动作未完成则抛 40001（message 点名全部待完成动作码）。 */
    public void assertAdvanceAllowed(Long projectId, String targetSubStageCode) {
        List<String> pending = pendingBlockingActions(projectId, targetSubStageCode);
        if (!pending.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.GATE_NOT_PASSED,
                "小阶段门禁未通过：阻断动作未完成 " + String.join(",", pending));
        }
    }

    /** 新项目对阻断动作缺行也拒绝；历史项目保留已有的缺行豁免。 */
    public void assertAdvanceAllowed(Long projectId, String targetSubStageCode, boolean requireRows) {
        List<String> pending = pendingBlockingActions(projectId, targetSubStageCode, requireRows);
        if (!pending.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.GATE_NOT_PASSED,
                "小阶段门禁未通过：阻断动作未完成 " + String.join(",", pending));
        }
    }

    /** 待完成阻断动作码（升序、去重语义由映射唯一性保证）；放行返回空列表。 */
    List<String> pendingBlockingActions(Long projectId, String targetSubStageCode) {
        return pendingBlockingActions(projectId, targetSubStageCode, false);
    }

    private List<String> pendingBlockingActions(Long projectId, String targetSubStageCode,
                                                boolean requireRows) {
        if (projectId == null || targetSubStageCode == null || targetSubStageCode.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "projectId 与 targetSubStageCode 必填");
        }
        List<IpdSubStage> ordered = subStageService.listAll().stream()
            .filter(s -> !"1".equals(s.getIsResident()))
            .toList();
        int targetIdx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getCode().equals(targetSubStageCode)) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "目标小阶段不存在或为常驻小阶段（常驻不参与顺序推进）: " + targetSubStageCode);
        }
        Set<String> beforeCodes = new HashSet<>();
        for (int i = 0; i < targetIdx; i++) {
            beforeCodes.add(ordered.get(i).getCode());
        }
        if (beforeCodes.isEmpty()) {
            return List.of();
        }

        Map<String, StageAction> byCode = new HashMap<>();
        List<StageAction> rows = stageActionMapper.selectList(
            new LambdaQueryWrapper<StageAction>().eq(StageAction::getProjectId, projectId));
        for (StageAction row : rows) {
            // Z01-Z05 别名归一为权威码（P1-8.2 / AC-IPD-17）
            byCode.put(ActionCatalog.resolveCode(row.getActionCode()), row);
        }

        List<String> pending = new ArrayList<>();
        for (IpdActionSkillMap m : skillMapService.listAll()) {
            if (!beforeCodes.contains(m.getSubStageCode())) {
                continue;
            }
            StageAction row = byCode.get(ActionCatalog.resolveCode(m.getActionCode()));
            if (row == null) {
                if (requireRows && ActionCatalog.byCode(m.getActionCode()).blocking()) {
                    pending.add(m.getActionCode() + "(缺行)");
                }
                continue;
            }
            if (!"1".equals(row.getIsBlocking())) {
                continue;
            }
            if (SETTLED_STATUSES.contains(row.getStatus())) {
                continue;
            }
            if (HISTORY_MISSING.equals(row.getHistoryMark())) {
                if (requireRows) {
                    pending.add(m.getActionCode() + "(历史豁免仅存量)");
                }
                continue;
            }
            pending.add(m.getActionCode());
        }
        Collections.sort(pending);
        return pending;
    }
}
