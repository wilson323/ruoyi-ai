package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.dto.GateChecklistItem;
import org.ruoyi.ipd.dto.GateChecklistView;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 阶段门禁引擎（BR-IPD-06，主 Prompt v3 L560 唯一权威口径）：
 * 当前阶段阻断性动作未完成 → 禁止跳转下一阶段。
 *
 * <p>P1-5.1：只判当前阶段应做集；缺失/未实例化必做动作拒绝；未来阶段不阻塞。
 * <p>P1-5.2：S/A/B 必做集配置校验与可解释清单（AC-IPD-10/11）。
 *
 * 必做集按项目等级裁剪：
 * <ul>
 *   <li>S 级：全部阻断动作（38 个）</li>
 *   <li>A 级：超管配置 gate.a_level_block_codes（trim/去重/未知码拒绝）；未配置从严回落 S</li>
 *   <li>B 级：ActionCatalog.B_LEVEL_BLOCKING_CODES（权威 10 项，不硬凑 14）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GateEngine {

    /** A 级必做集超管配置键 */
    public static final String A_LEVEL_CONFIG_KEY = "gate.a_level_block_codes";

    private final StageActionMapper stageActionMapper;
    private final ISystemConfigService systemConfigService;

    /**
     * F3：Gate 评审判决查询用 mapper。
     *
     * <p><b>为何是可选注入</b>：{@code GateEngine} 现有 2 参构造被 {@code GateEngineTest} 等
     * 兄弟测试直接 {@code new}，若改为必需注入会让这些测试全部编译/装配失败。故走
     * {@code @Autowired(required=false)} + setter 两条路：生产由 Spring 注入，
     * 未装配时 {@link #evaluateStageExitGate} 一律返回「不拦截」（存量零影响）。
     */
    @Autowired(required = false)
    private GateMapper gateMapper;

    @Autowired(required = false)
    private GateElementResultMapper gateElementResultMapper;

    /** 装配 Gate 实例 mapper（F3）。 */
    public void setGateMapper(GateMapper gateMapper) {
        this.gateMapper = gateMapper;
    }

    /** 装配 Gate 要素判定 mapper（F3）。 */
    public void setGateElementResultMapper(GateElementResultMapper gateElementResultMapper) {
        this.gateElementResultMapper = gateElementResultMapper;
    }

    /**
     * 校验当前阶段门禁：未完成或未实例化的必做动作非空即拒绝。
     *
     * @param project      项目（含等级）
     * @param currentStage 当前阶段编码 CONCEPT|PLAN|…
     */
    public void check(Project project, String currentStage) {
        if (currentStage == null || currentStage.isBlank()) {
            throw new ServiceException("当前阶段不能为空");
        }
        Set<String> requiredHere = requiredCodesForStage(project.getLevel(), currentStage);
        if (requiredHere.isEmpty()) {
            return;
        }
        Map<String, StageAction> byCode = loadByCode(project.getId(), requiredHere);
        List<String> unfinished = new ArrayList<>();
        for (String code : requiredHere) {
            StageAction action = byCode.get(code);
            if (action == null) {
                ActionDef def = ActionCatalog.byCode(code);
                unfinished.add(code + " " + def.name() + "（未实例化）");
                continue;
            }
            if (!actionAccepted(action, historyExempt(project, action))) {
                String wait = "DONE".equals(action.getStatus()) && action.getConfirmedBy() == null
                    ? "（待产线负责人批准）" : "";
                unfinished.add(code + " " + action.getActionName() + wait);
            }
        }
        if (!unfinished.isEmpty()) {
            throw new ServiceException("以下必需动作还没完成，暂时不能进入下一阶段 —— "
                + String.join("；", unfinished));
        }
    }

    /**
     * F3：阶段出口 Gate 评审判决结果。
     *
     * @param blocking          true=不允许推进到下一阶段
     * @param gateCode          阶段绑定的 Gate 码（G1..G5）；无绑定为 null
     * @param gateStatus        该 Gate 最新一轮的 status；无行/未装配为 null
     * @param openLeftoverCount 该 Gate 下 leftoverStatus=OPEN 的遗留项数
     * @param message           中文拦截原因（放行时为说明文案）
     */
    public record StageGateVerdict(boolean blocking, String gateCode, String gateStatus,
                                   int openLeftoverCount, String message) {
    }

    /** Gate 状态：评审否决 */
    public static final String GATE_STATUS_REJECTED = "REJECTED";
    /** Gate 状态：双签超时弃权（无放行依据） */
    public static final String GATE_STATUS_ABSTAINED_TIMEOUT = "ABSTAINED_TIMEOUT";
    /** 遗留项未关闭 */
    public static final String LEFTOVER_STATUS_OPEN = "OPEN";

    /**
     * F3：阶段推进前的 Gate 评审判决——评审否决或存在未关闭遗留项时禁止推进阶段。
     *
     * <p><b>与 {@link #check} 的分工</b>：{@code check} 判的是「必做动作是否做完」（阶段内动作门禁），
     * 本方法判的是「本阶段出口 Gate 评审的结论」（阶段间评审门禁）。{@code check} 有第三处调用方
     * {@code StageAcceptanceService}，改它会连累，故独立成方法，只在 {@code ProjectService.advanceStage} 调用。
     *
     * <p><b>拦截条件（owner 拍板口径，不得放宽）</b>——仅两种：
     * <ol>
     *   <li>该 Gate 最新一轮 status ∈ {REJECTED, ABSTAINED_TIMEOUT}；</li>
     *   <li>该 Gate 下存在 leftoverStatus=OPEN 的未关闭遗留项。</li>
     * </ol>
     * <b>PENDING 不拦</b>（评审尚未开始不是「被判失败」）。
     *
     * <p><b>放行条件（存量零影响）</b>：本阶段无 Gate 绑定（如 VALID 阶段目录里没有挂 Gate 的动作）、
     * {@code gates} 表无该项目的 Gate 行、mapper 未装配（单测/裁剪部署）、projectId 为空——
     * 一律返回不拦截。本仓 {@code gates} 表现 0 行，故上线对存量 300 项目无行为变化。
     *
     * @param projectId    项目 ID
     * @param currentStage 当前（即将退出的）阶段编码
     * @return 判定结果，永不为 null
     */
    public StageGateVerdict evaluateStageExitGate(Long projectId, String currentStage) {
        String gateCode = ActionCatalog.gateOfStage(currentStage);
        if (gateCode == null) {
            return new StageGateVerdict(false, null, null, 0,
                "阶段 " + currentStage + " 无出口 Gate 绑定，跳过评审判决");
        }
        if (projectId == null || gateMapper == null) {
            return new StageGateVerdict(false, gateCode, null, 0,
                "Gate " + gateCode + " 未查到评审记录（Gate 数据未装配），放行");
        }
        Gate gate = latestGate(projectId, gateCode);
        if (gate == null) {
            return new StageGateVerdict(false, gateCode, null, 0,
                "Gate " + gateCode + " 尚无评审记录，放行");
        }
        String status = gate.getStatus();
        if (GATE_STATUS_REJECTED.equals(status) || GATE_STATUS_ABSTAINED_TIMEOUT.equals(status)) {
            return new StageGateVerdict(true, gateCode, status, 0,
                "Gate " + gateCode + " 评审结论为 " + status + "，需先完成评审或重新发起评审才能推进阶段");
        }
        int openLeft = countOpenLeftover(gate.getId());
        if (openLeft > 0) {
            return new StageGateVerdict(true, gateCode, status, openLeft,
                "Gate " + gateCode + " 还有 " + openLeft + " 项未关闭的评审遗留项，需先关闭遗留项才能推进阶段");
        }
        return new StageGateVerdict(false, gateCode, status, openLeft,
            "Gate " + gateCode + " 评审结论为 " + (status == null ? "无状态" : status)
                + "，无未关闭遗留项，放行");
    }

    /** 取该项目该 Gate 的最新一轮（按 id 倒序取首行）；查询异常一律按「无记录」处理。 */
    private Gate latestGate(Long projectId, String gateCode) {
        try {
            List<Gate> rows = gateMapper.selectList(new LambdaQueryWrapper<Gate>()
                .eq(Gate::getProjectId, projectId)
                .eq(Gate::getGateCode, gateCode)
                .orderByDesc(Gate::getId));
            return (rows == null || rows.isEmpty()) ? null : rows.get(0);
        } catch (RuntimeException ex) {
            log.warn("[gate-engine] 查询 Gate 失败，按无记录放行: projectId={}, gateCode={}", projectId, gateCode, ex);
            return null;
        }
    }

    /** 该 Gate 下 leftoverStatus=OPEN 的遗留项计数；查询异常一律按 0 处理。 */
    private int countOpenLeftover(Long gateId) {
        if (gateId == null || gateElementResultMapper == null) {
            return 0;
        }
        try {
            Long n = gateElementResultMapper.selectCount(new LambdaQueryWrapper<GateElementResult>()
                .eq(GateElementResult::getGateId, gateId)
                .eq(GateElementResult::getLeftoverStatus, LEFTOVER_STATUS_OPEN));
            return n == null ? 0 : n.intValue();
        } catch (RuntimeException ex) {
            log.warn("[gate-engine] 查询遗留项失败，按 0 放行: gateId={}", gateId, ex);
            return 0;
        }
    }

    /**
     * P1-5.2：返回本阶段必做清单 + 逐项原因 + 配置版本。
     *
     * @param project 项目
     * @param stage   阶段；空则用 currentStage
     * @return 可解释视图
     */
    public GateChecklistView explainChecklist(Project project, String stage) {
        if (project == null || project.getId() == null) {
            throw new ServiceException("项目不能为空");
        }
        String st = (stage == null || stage.isBlank()) ? project.getCurrentStage() : stage;
        if (st == null || st.isBlank()) {
            throw new ServiceException("阶段不能为空");
        }
        String level = project.getLevel() == null ? "S" : project.getLevel();
        String configVersion = configVersionOf(level);
        Set<String> required = requiredCodesForStage(level, st);
        Map<String, StageAction> byCode = loadByCode(project.getId(), required);
        List<GateChecklistItem> items = new ArrayList<>();
        for (String code : required) {
            ActionDef def = ActionCatalog.byCode(code);
            StageAction action = byCode.get(code);
            if (action == null) {
                items.add(new GateChecklistItem(code, def.name(), st, null, false,
                    "必做未实例化；来源=" + sourceLabel(level, code)));
                continue;
            }
            boolean exempt = historyExempt(project, action);
            boolean ok = actionAccepted(action, exempt);
            String reason;
            if (LegacyImportService.HISTORY_MISSING.equals(action.getHistoryMark()) && exempt) {
                reason = "历史缺失（BR-PROD-03）；来源=" + sourceLabel(level, code);
            } else if (LegacyImportService.HISTORY_MISSING.equals(action.getHistoryMark())) {
                reason = "历史缺失标记不在豁免范围（动作阶段不早于申报阶段 "
                    + project.getDeclaredStage() + "），按未完成处理；来源=" + sourceLabel(level, code);
            } else if ("DONE".equals(action.getStatus()) && action.getConfirmedBy() == null) {
                reason = "已提交，待产线负责人批准；来源=" + sourceLabel(level, code);
            } else if (ok) {
                reason = "已满足（" + action.getStatus() + "）；来源=" + sourceLabel(level, code);
            } else {
                reason = "未完成 status=" + action.getStatus() + "；来源=" + sourceLabel(level, code);
            }
            items.add(new GateChecklistItem(code, def.name(), st, action.getStatus(), ok, reason));
        }
        return new GateChecklistView(project.getId(), level, st, configVersion, items);
    }

    /**
     * P1（owner 2026-09-05 指令项1d）：HISTORICAL_MISSING 的豁免范围收窄。
     *
     * <p>BR-PROD-03 的原始语义是「legacy 导入时，<b>申报阶段之前</b>的动作确实没在本系统做过，
     * 允许以历史缺失标记替代 DONE」。而 {@code LegacyImportService.markPastStages} 也只对
     * {@code isStageBefore(def.stage(), declared)} 成立的动作打这个标记。
     *
     * <p>但门禁侧此前<b>只认标志位、不认它是否还在申报范围内</b>：一旦有行被（误）打上
     * HISTORICAL_MISSING——包括申报阶段之后才该做的动作、以及非 legacy 项目（declaredStage 为空）
     * 的行——阻断就永久失效，等于给一个字符串开了免检通道。
     *
     * <p>现改为由权威事实（项目申报阶段 + 目录中动作所属阶段）反推证明，与打标记侧同一判据：
     * <ul>
     *   <li>标志位不是 HISTORICAL_MISSING → 不豁免（不变）</li>
     *   <li>declaredStage 为空/空白（非 legacy 项目）→ 不豁免，fail-closed</li>
     *   <li>动作阶段不早于申报阶段 → 不豁免（本卡新增的可拦截面）</li>
     * </ul>
     *
     * <p>诚实边界：本改动是<b>纵深防御收窄</b>而非已复现的线上缺陷——走正常 legacy 导入链路时
     * 打标记的范围与豁免范围当前一致，因此 {@link #check} 的 MISSING 分支在生产不可达（与 O1 同族）。
     * 真正的差别在直接写库 / 数据修复 / 未来新增打标记入口时才会暴露。
     *
     * @param project 项目（取 declaredStage）
     * @param action  阶段动作行
     * @return true 仅当该行的历史缺失标记落在申报范围内
     */
    /**
     * 动作是否已通过验收。不适用和历史缺失豁免保持原口径；完成还必须有产线负责人。
     *
     * @param action 动作实例
     * @param exempt 历史缺失是否落在申报范围内
     * @return 可以计入阶段门禁时为 true
     */
    private static boolean actionAccepted(StageAction action, boolean exempt) {
        if (exempt || "NA".equals(action.getStatus())) {
            return true;
        }
        return "DONE".equals(action.getStatus()) && action.getConfirmedBy() != null;
    }

    private boolean historyExempt(Project project, StageAction action) {
        if (!LegacyImportService.HISTORY_MISSING.equals(action.getHistoryMark())) {
            return false;
        }
        String declared = project == null ? null : project.getDeclaredStage();
        if (declared == null || declared.isBlank()) {
            return false;
        }
        ActionDef def = findDefOrNull(action.getActionCode());
        return def != null && LegacyImportService.isStageBefore(def.stage(), declared);
    }

    /**
     * 目录定义的宽容查找：未知编码返回 null 而非抛 {@link ActionCatalog#byCode} 的
     * IllegalArgumentException。这里必须宽容——未知码本就该走「未完成」分支被阻断，
     * 不该在豁免判定阶段把整个门禁调用炸掉。
     */
    private static ActionDef findDefOrNull(String code) {
        String resolved = ActionCatalog.resolveCode(code);
        return ActionCatalog.ALL.stream()
            .filter(a -> a.code().equals(resolved))
            .findFirst()
            .orElse(null);
    }

    /**
     * 等级必做集 ∩ 当前阶段目录动作。
     *
     * @param projectLevel 项目等级
     * @param stage        当前阶段
     * @return 本阶段应检查的编码集（保序）
     */
    public Set<String> requiredCodesForStage(String projectLevel, String stage) {
        Set<String> stageCodes = ActionCatalog.byStage(stage).stream()
            .map(ActionDef::code)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return requiredCodes(projectLevel).stream()
            .filter(stageCodes::contains)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 按项目等级解析必做动作编码集（纯函数可测） */
    public Set<String> requiredCodes(String projectLevel) {
        String lv = projectLevel == null ? "S" : projectLevel;
        return switch (lv) {
            case "B" -> bLevelCodes();
            case "A" -> {
                String conf = systemConfigService.getValue(A_LEVEL_CONFIG_KEY, "");
                yield conf.isBlank() ? sLevelCodes() : normalizeALevelConfig(conf);
            }
            default -> sLevelCodes();
        };
    }

    /**
     * A 级配置规范化：trim、去重、别名归一；未知码拒绝。
     * 空串返回空集（调用方决定是否回落 S）。
     *
     * @param raw 逗号分隔配置
     * @return 保序编码集
     */
    public static Set<String> normalizeALevelConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (part == null) {
                continue;
            }
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            String code = ActionCatalog.resolveCode(token);
            try {
                ActionCatalog.byCode(code);
            } catch (IllegalArgumentException ex) {
                throw new ServiceException("A 级必做集含未知动作码: " + token);
            }
            out.add(code);
        }
        return out;
    }

    /**
     * 写配置前校验并返回规范化 CSV（供 ISystemConfigService 使用）。
     *
     * @param raw 原始值
     * @return 规范化逗号串；空输入返回 ""
     */
    public static String validateAndNormalizeALevelConfigValue(String raw) {
        Set<String> codes = normalizeALevelConfig(raw);
        return String.join(",", codes);
    }

    /**
     * R8-AUTO-8 [PERF-01]：loadByCode 加 .in(actionCode, requiredCodes) 过滤。
     * 100 并发下 S 级项目 69 行全表拉取降到 ≤38 行必做集，配合 idx_sa_project_code 索引
     * （参考 Round 8 R8-PERF-08 SQL）消除 filesort 与 100×6ms 阻塞池。
     *
     * @param projectId     项目 ID
     * @param requiredCodes 本阶段必做集（resolveCode 后）；空集合返回空 Map
     * @return code → action 映射
     */
    private Map<String, StageAction> loadByCode(Long projectId, Set<String> requiredCodes) {
        if (requiredCodes == null || requiredCodes.isEmpty()) {
            return Map.of();
        }
        List<StageAction> actions = stageActionMapper.selectList(
            new LambdaQueryWrapper<StageAction>()
                .eq(StageAction::getProjectId, projectId)
                .in(StageAction::getActionCode, requiredCodes));
        Map<String, StageAction> byCode = new LinkedHashMap<>();
        for (StageAction action : actions) {
            if (action.getActionCode() != null) {
                byCode.putIfAbsent(ActionCatalog.resolveCode(action.getActionCode()), action);
            }
        }
        return byCode;
    }

    private String configVersionOf(String level) {
        return switch (level == null ? "S" : level) {
            case "A" -> {
                String conf = systemConfigService.getValue(A_LEVEL_CONFIG_KEY, "");
                if (conf.isBlank()) {
                    yield "fallback:S-blocking-38";
                }
                yield "A:" + String.join(",", normalizeALevelConfig(conf));
            }
            case "B" -> "B:catalog-" + ActionCatalog.B_LEVEL_BLOCKING_CODES.size();
            default -> "S:blocking-38";
        };
    }

    private String sourceLabel(String level, String code) {
        return switch (level == null ? "S" : level) {
            case "A" -> {
                String conf = systemConfigService.getValue(A_LEVEL_CONFIG_KEY, "");
                yield conf.isBlank() ? "S级阻断全集回落" : "超管配置 " + A_LEVEL_CONFIG_KEY;
            }
            case "B" -> ActionCatalog.B_LEVEL_BLOCKING_CODES.contains(code)
                ? "B级权威清单" : "B级权威清单";
            default -> "S级阻断动作";
        };
    }

    private Set<String> sLevelCodes() {
        return ActionCatalog.ALL.stream()
            .filter(ActionDef::blocking)
            .map(ActionDef::code)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> bLevelCodes() {
        return new LinkedHashSet<>(ActionCatalog.B_LEVEL_BLOCKING_CODES);
    }
}
