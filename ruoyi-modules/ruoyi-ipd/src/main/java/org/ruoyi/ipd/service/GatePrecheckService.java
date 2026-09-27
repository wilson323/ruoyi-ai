package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.dto.AiSuggestReq;
import org.ruoyi.ipd.dto.AiSuggestResp;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI-P2-1（2026-09-27）：Gate 评审材料 AI 预审——可单测自证的后端最小切片。
 *
 * <p>端点语义（卡面硬约束）：预审结果<b>只返回参考，不写 Gate 决策、不阻塞评审</b>
 * （响应固定回带 {@code blocking=false / decisionWritten=false} 双自证旗标，
 * 本类对 gates / gate_reviews 表零写操作，单测以 never() 锁死）。
 *
 * <p>AI 调用链复用而非另起：底层走 {@link AiSuggestionService} 既有
 * {@code gate.precheck-checklist} 场景（L2 R227-C1 已落），本类只做输入装配 +
 * 结构化覆盖统计（确定性代码算，不依赖模型自觉）+ 输出整形。AI 环节失败/降级
 * 不拖垮整体——结构化覆盖部分恒返回，aiChecklist 段标记 degraded
 * （预审可用性优先于模型可用性的卡面语义）。
 *
 * <p>留痕：aiRole 用审计白名单既有值 {@code precheck}（AuditEventData.AI_ROLES，
 * AI-P1-3 门禁），每次预审（含 AI 降级）必落一行 AI_PRECHECK 审计；
 * 原文不落库，只记计数（BR-AI-04 同款口径）。
 *
 * <p>权限口径「评审参与人」：注解层 OPERATION_GATE_REVIEW 权限码把内部角色门
 * （与 GateMaterialController 同源），对象层要求 actor 为 SUPER_ADMIN 或
 * gate 所属项目的 project_members 命中人；不命中 → FORBIDDEN(403)——
 * 注意此处与 copilot/suggest 的「项目不可见 → NOT_FOUND」刻意不同：
 * 预审是 gate 面板动作，403 语义更直白且不泄漏存在性的诉求由权限码层先行兜住。
 *
 * <p>遗留（如实标注，不在本切片假绿）：
 * <ul>
 *   <li>要素基线当前取 gate_element_results 已判定行；「要素清单快照
 *       （gate.element_snapshot）与判定行 diff 出未判定要素」需产品确认口径后另卡落地；</li>
 *   <li>仲裁分歧汇总 {@link #arbitrationDivergences} 仅数据装配草稿（同轮决策不一致
 *       检测），未接 AI 归纳、未暴露 HTTP 端点、未写审计。</li>
 * </ul>
 */
@Slf4j
@Service
public class GatePrecheckService {

    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final GateMapper gateMapper;
    private final GateElementResultMapper elementResultMapper;
    private final GateReviewMapper gateReviewMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final GateMaterialChecker gateMaterialChecker;
    private final AiSuggestionService aiSuggestionService;
    private final IAuditLogService auditLogService;

    /** 测试口注入固定时钟（同 suggestion/copilot 模式）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public GatePrecheckService(GateMapper gateMapper,
                               GateElementResultMapper elementResultMapper,
                               GateReviewMapper gateReviewMapper,
                               ProjectMemberMapper projectMemberMapper,
                               GateMaterialChecker gateMaterialChecker,
                               AiSuggestionService aiSuggestionService,
                               IAuditLogService auditLogService) {
        this.gateMapper = gateMapper;
        this.elementResultMapper = elementResultMapper;
        this.gateReviewMapper = gateReviewMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.gateMaterialChecker = gateMaterialChecker;
        this.aiSuggestionService = aiSuggestionService;
        this.auditLogService = auditLogService;
    }

    GatePrecheckService withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /**
     * 预审主流程：gate 存在性(404) → 参与人(403) → 装配输入，材料全空(400)
     * → 结构化覆盖统计 → 复用 gate.precheck-checklist 出 AI 参考清单 → 审计(precheck)。
     */
    public Map<String, Object> precheck(Long gateId, IpdActor actor) {
        long start = clock.millis();
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "Gate 不存在");
        }
        assertReviewParticipant(actor, gate);

        // 输入装配①：该 Gate 挂载要素的已判定清单（见类注释的基线遗留说明）
        List<GateElementResult> results = elementResultMapper.selectList(
            new LambdaQueryWrapper<GateElementResult>()
                .eq(GateElementResult::getGateId, gateId));
        // 输入装配②：项目级 stage-action 材料齐套性（GateMaterialChecker 既有口径）
        Map<String, Object> materials = gateMaterialChecker.listMaterialStatus(gateId, gate.getProjectId());

        boolean noElementJudged = results.isEmpty();
        boolean noMaterial = !(Boolean) materials.getOrDefault("isReady", false)
            && ((Number) materials.getOrDefault("total", 0)).longValue() == 0;
        if (noElementJudged && noMaterial) {
            // 卡面「材料为空 400」：要素零判定且项目下无任何交付物记录，预审无米下锅
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "Gate 评审材料为空：无要素判定记录且无交付物，无法预审");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int covered = 0;
        int partial = 0;
        int missing = 0;
        for (GateElementResult r : results) {
            String status = switch (r.getResult() == null ? "" : r.getResult()) {
                case "PASS" -> "COVERED";
                case "CONDITIONAL" -> "PARTIAL";
                default -> "MISSING";
            };
            switch (status) {
                case "COVERED" -> covered++;
                case "PARTIAL" -> partial++;
                default -> missing++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("elementId", String.valueOf(r.getElementId()));
            item.put("result", r.getResult());
            item.put("status", status);
            // 证据定位：判定证据附件引用 + 条件说明 + 遗留项（有什么给什么，空则 null）
            item.put("evidenceRef", blankToNull(r.getEvidenceRef()));
            item.put("conditionNote", blankToNull(r.getConditionNote()));
            item.put("leftoverStatus", blankToNull(r.getLeftoverStatus()));
            items.add(item);
        }

        // AI 参考清单：复用既有 gate.precheck-checklist 场景（不另起调用链）；
        // AI 失败/未启用不拖垮预审——结构化部分恒返回，aiChecklist 段标记降级。
        AiSuggestResp suggest;
        try {
            suggest = aiSuggestionService.suggest(actor,
                new AiSuggestReq("gate.precheck-checklist", gate.getProjectId(), gateId, null));
        } catch (IpdBusinessException ex) {
            log.warn("gate precheck AI 降级：gateId={} err={}", gateId, ex.getMessage());
            suggest = AiSuggestResp.degraded("gate.precheck-checklist",
                "AI 预审清单暂不可用（" + (ex.getErrorCode() == null ? "UNKNOWN" : ex.getErrorCode().name())
                    + "），以下为结构化覆盖统计。", clock.millis() - start);
        }

        long latency = clock.millis() - start;
        audit(actor, gateId, covered, partial, missing, suggest, latency);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", results.size());
        summary.put("covered", covered);
        summary.put("partial", partial);
        summary.put("missing", missing);

        Map<String, Object> aiChecklist = new LinkedHashMap<>();
        aiChecklist.put("markdown", suggest.markdown());
        aiChecklist.put("aiModel", suggest.aiModel());
        aiChecklist.put("degraded", suggest.degraded());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateId", String.valueOf(gateId));
        out.put("projectId", String.valueOf(gate.getProjectId()));
        out.put("gateCode", gate.getGateCode());
        out.put("summary", summary);
        out.put("items", items);
        out.put("materials", materials);
        out.put("aiChecklist", aiChecklist);
        // 卡面硬约束自证旗标：只读参考、不阻塞、不写决策
        out.put("blocking", false);
        out.put("decisionWritten", false);
        out.put("latencyMs", latency);
        return out;
    }

    /**
     * 仲裁分歧点汇总——后端草稿（AI-P2-1 后半段，仅数据装配，未接 AI/HTTP/审计）。
     *
     * <p>口径：同一轮（round）内 MARKET_PM 与 RD_PM 已签 decision 不一致即记一个分歧点；
     * 双 APPROVE / 双 REJECT 不算。返回草稿结构 {gateId, round, divergences[]}。
     */
    public Map<String, Object> arbitrationDivergences(Long gateId) {
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "Gate 不存在");
        }
        List<GateReview> reviews = gateReviewMapper.selectList(new LambdaQueryWrapper<GateReview>()
            .eq(GateReview::getGateId, gateId)
            .orderByAsc(GateReview::getRound));
        Map<Integer, Map<String, GateReview>> byRound = new LinkedHashMap<>();
        for (GateReview r : reviews) {
            if (r.getDecision() == null) {
                continue; // 未签行不参与分歧
            }
            byRound.computeIfAbsent(r.getRound() == null ? 0 : r.getRound(), k -> new LinkedHashMap<>())
                .put(r.getReviewerType(), r);
        }
        List<Map<String, Object>> divergences = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, GateReview>> e : byRound.entrySet()) {
            GateReview m = e.getValue().get("MARKET_PM");
            GateReview d = e.getValue().get("RD_PM");
            if (m != null && d != null && !m.getDecision().equals(d.getDecision())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("round", e.getKey());
                row.put("marketDecision", m.getDecision());
                row.put("rdDecision", d.getDecision());
                row.put("marketOpinion", blankToNull(m.getOpinion()));
                row.put("rdOpinion", blankToNull(d.getOpinion()));
                divergences.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateId", String.valueOf(gateId));
        out.put("round", gate.getCurrentRound());
        out.put("divergences", divergences);
        return out;
    }

    /** 评审参与人对象级校验：SA 放行；其余须命中 gate 所属项目 project_members。 */
    private void assertReviewParticipant(IpdActor actor, Gate gate) {
        if (ROLE_SUPER_ADMIN.equals(actor.role())) {
            return;
        }
        Long hit = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, gate.getProjectId())
            .eq(ProjectMember::getPersonId, actor.id()));
        if (hit == null || hit == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "非该 Gate 评审参与人，无权预审");
        }
    }

    /** AI_PRECHECK 审计行（aiRole=precheck 为 AuditEventData 白名单既有值；原文不落库）。 */
    private void audit(IpdActor actor, Long gateId, int covered, int partial, int missing,
                       AiSuggestResp suggest, long latencyMs) {
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id()).operatorName(actor.name()).operatorRole(actor.role())
            .action("AI_PRECHECK").entityType("GATE")
            .afterData(AuditEventData.json(
                "aiAssisted", true,
                "aiModel", suggest.aiModel() == null || suggest.aiModel().isBlank()
                    ? "intent_match" : suggest.aiModel(),
                "aiRole", "precheck",
                "scene", "gate.precheck-checklist",
                "gateId", gateId,
                "covered", covered,
                "partial", partial,
                "missing", missing,
                "degraded", suggest.degraded(),
                "tokenPrompt", suggest.promptTokens(),
                "tokenCompletion", suggest.completionTokens(),
                "latencyMs", latencyMs))
            .build());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
