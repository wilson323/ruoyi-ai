package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.KpiSharedConfirm;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.KpiSharedConfirmMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * R232-W14 共担 KPI 数据对账（智能体节点执行者样板，ZK-IPD《IPD业务工作流与完整动作目录》W14-02/03）。
 *
 * <p>确定性交叉复核，五类对账项全部只读、可被同岗真人逐项复算：
 * <ol>
 *   <li>逐项复算：存储明细 (actual,target) → 既有公式重算得分，与存储得分比对（DIFF）；</li>
 *   <li>目标对源表：存储 target 与 projects 当前目标值比对（DRIFT，目标已变更提示重归集）；</li>
 *   <li>综合分复算：存储明细 → {@link KpiSharedCollectionService#weightedScore} 同公式重算，
 *       与 comprehensive_score 比对（DIFF）；</li>
 *   <li>双 PM 同分（AC-KPI-11）：最新 revision 应恰 2 行且得分一致；</li>
 *   <li>K04 来源再判定 + 确认链状态汇总（信息项，OVERDUE 按读时派生，与 KpiSharedConfirmService 同口径）。</li>
 * </ol>
 *
 * <p>铁律：缺值如实标 {@code PENDING_DATA}（W14-02「缺证/无效样本转待补，不得按 0 直接扣分」语义），
 * 不伪造数据、不改写任何业务表；差异处置权留真人组长（台账只报事实，不做裁决）。
 * 公式一律复用 {@link KpiSharedCollectionService}（package 可见化复用，防第二套口径漂移，GatePrep M2 教训）。
 */
@Service
@RequiredArgsConstructor
public class KpiSharedReconcileService {

    /** 对账判定：存储值与确定性复算/源表一致 */
    public static final String VERDICT_MATCH = "MATCH";
    /** 对账判定：存储得分与公式复算不符（真实差异，需人查） */
    public static final String VERDICT_DIFF = "DIFF";
    /** 对账判定：目标/来源与源表现值不一致（漂移，建议确认后重归集） */
    public static final String VERDICT_DRIFT = "DRIFT";
    /** 对账判定：缺值待补（不按 0 伪判，不污染下游） */
    public static final String VERDICT_PENDING = "PENDING_DATA";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TYPE_SHARED = "SHARED";
    private static final BigDecimal FULL_SCORE = new BigDecimal("100");

    private final KpiRecordMapper kpiRecordMapper;
    private final KpiSharedConfirmMapper confirmMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final PersonMapper personMapper;
    private final ProductGroupMapper productGroupMapper;
    /** 公式与实时配置的唯一复用入口（readMinSample/readK04Weight/resolveK04Source/weightedScore 同源） */
    private final KpiSharedCollectionService collectionService;

    /**
     * 对账主入口：项目 + 最近一个有归集记录的周期；无记录 → 上月全项待补（noData 台账，仍是有效产出）。
     * 只读；异常语义交给引擎（项目不存在抛 IpdBusinessException → FAILED 退避，不伪造台账）。
     */
    public ReconcileReport reconcile(Long projectId, Clock clock) {
        Project project = projectId == null ? null : projectMapper.selectById(projectId);
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "对账失败：项目不存在 " + projectId);
        }
        String period = latestCollectedPeriod(projectId);
        if (period == null) {
            String expect = YearMonth.from(LocalDate.now(clock)).minusMonths(1).toString();
            return ReconcileReport.awaitingCollection(projectId, expect);
        }
        List<KpiRecord> periodRows = kpiRecordMapper.selectList(
            Wrappers.<KpiRecord>lambdaQuery()
                .eq(KpiRecord::getProjectId, projectId)
                .eq(KpiRecord::getPeriod, period)
                .eq(KpiRecord::getKpiType, TYPE_SHARED)
                .orderByDesc(KpiRecord::getRevision)
                .orderByDesc(KpiRecord::getId));
        if (periodRows.isEmpty()) {
            return ReconcileReport.awaitingCollection(projectId, period);
        }
        int maxRevision = periodRows.stream().map(KpiRecord::getRevision)
            .filter(Objects::nonNull).max(Integer::compareTo).orElse(1);
        List<KpiRecord> latest = periodRows.stream()
            .filter(r -> Objects.equals(r.getRevision(), maxRevision)).toList();
        KpiRecord primary = latest.get(0);

        List<Item> items = reconcileMetrics(projectId, primary, project);
        String[] total = reconcileTotal(primary);
        String[] dualPm = reconcileDualPm(latest, maxRevision);
        return new ReconcileReport(projectId, period, false, items,
            total[0], total[1], total[2], dualPm[0], dualPm[1],
            listConfirms(projectId, period, Date.from(clock.instant())), maxRevision);
    }

    /** 最近一个存在 SHARED 归集记录的周期；从未归集过返回 null。 */
    String latestCollectedPeriod(Long projectId) {
        KpiRecord row = kpiRecordMapper.selectOne(
            Wrappers.<KpiRecord>lambdaQuery()
                .eq(KpiRecord::getProjectId, projectId)
                .eq(KpiRecord::getKpiType, TYPE_SHARED)
                .orderByDesc(KpiRecord::getPeriod)
                .last("LIMIT 1"));
        return row == null ? null : row.getPeriod();
    }

    /** 逐指标复算 + 目标对源表 + K04 来源再判定（sharedDetail.metrics 数组逐项）。 */
    List<Item> reconcileMetrics(Long projectId, KpiRecord record, Project project) {
        JsonNode metrics;
        try {
            metrics = JSON.readTree(record.getSharedDetail()).path("metrics");
        } catch (Exception ex) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "对账失败：shared_detail 非法 JSON（period=" + record.getPeriod() + "）");
        }
        if (!metrics.isArray() || metrics.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "对账失败：shared_detail.metrics 缺失（record=" + record.getId() + "）");
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode m : metrics) {
            items.add(reconcileOneItem(projectId, m, project));
        }
        return items;
    }

    private Item reconcileOneItem(Long projectId, JsonNode m, Project project) {
        String code = m.path("code").asText("");
        String source = m.path("source").asText("");
        boolean included = m.path("included").asBoolean(true);
        String storedScore = normalizeScore(text(m.path("score")));
        String storedTarget = text(m.path("target"));
        String storedActual = text(m.path("actual"));
        String currentTarget = currentTargetOf(code, project);
        String recomputed;
        String verdict;
        String note;
        if (storedActual == null || storedTarget == null || storedScore == null) {
            return new Item(code, source, storedActual, storedTarget, currentTarget,
                storedScore, null, VERDICT_PENDING, "存储明细缺项，不按 0 伪判（W14-02），转待补");
        }
        BigDecimal actual = parse(storedActual);
        BigDecimal target = parse(storedTarget);
        if (target == null) {
            return new Item(code, source, storedActual, storedTarget, currentTarget,
                storedScore, null, VERDICT_PENDING, "存储目标非法数值: " + storedTarget);
        }
        if ("K03".equals(code)) {
            // 与 collect 同口径：included=false 时存储得分恒 0 且不入分母；NPS 目标分用既有公式复算
            recomputed = included
                ? KpiSharedCollectionService.calculateNpsTargetScore(actual, target)
                    .setScale(2, RoundingMode.HALF_UP).toPlainString()
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP).toPlainString();
            note = "当前样本阈值 nps.minSample=" + collectionService.readMinSample()
                + (included ? "" : "（存储态：样本不足未计入分母）");
        } else {
            BigDecimal achievement = target.signum() == 0
                ? BigDecimal.ZERO
                : actual.divide(target, 6, RoundingMode.HALF_UP).multiply(FULL_SCORE);
            recomputed = KpiSharedCollectionService
                .calculateSharedAchievement(achievement, FULL_SCORE).toPlainString();
            note = "";
        }
        boolean scoreDiff = parse(storedScore) == null
            || parse(storedScore).compareTo(new BigDecimal(recomputed)) != 0;
        boolean targetDrift = currentTarget != null && target.compareTo(parse(currentTarget)) != 0;
        if (scoreDiff) {
            verdict = VERDICT_DIFF;
        } else if (targetDrift || k04SourceDrift(code, source, projectId)) {
            verdict = VERDICT_DRIFT;
        } else {
            verdict = VERDICT_MATCH;
        }
        if (targetDrift) {
            note = (note.isEmpty() ? "" : note + "；") + "projects 现目标=" + currentTarget
                + "，存储目标=" + storedTarget + "（目标已变更，建议确认后重归集）";
        }
        if (k04SourceDrift(code, source, projectId)) {
            note = (note.isEmpty() ? "" : note + "；") + "来源再判定="
                + collectionService.resolveK04Source(projectId) + "，存储来源=" + source;
        }
        return new Item(code, source, storedActual, storedTarget, currentTarget,
            storedScore, recomputed, verdict, note);
    }

    /** projects 源表现值（K01 销售额 / K02 渠道数 / K03 NPS 目标 / K04 规划场景数）。 */
    String currentTargetOf(String code, Project project) {
        return switch (code) {
            case "K01" -> project.getTargetSalesAmount() == null
                ? null : project.getTargetSalesAmount().stripTrailingZeros().toPlainString();
            case "K02" -> project.getTargetChannelCount() == null
                ? null : BigDecimal.valueOf(project.getTargetChannelCount()).toPlainString();
            case "K03" -> project.getTargetNps() == null
                ? null : BigDecimal.valueOf(project.getTargetNps()).toPlainString();
            case "K04" -> project.getTargetSceneCount() == null
                ? null : BigDecimal.valueOf(project.getTargetSceneCount()).toPlainString();
            default -> null;
        };
    }

    /** K04 来源漂移仅当该码存储来源与当前双认定复判不一致时成立。 */
    boolean k04SourceDrift(String code, String storedSource, Long projectId) {
        return "K04".equals(code) && !storedSource.equals(collectionService.resolveK04Source(projectId));
    }

    /** 综合分复算：存储明细（score/weight/included 原样）→ weightedScore 同公式 → 与 comprehensive_score 比对。 */
    String[] reconcileTotal(KpiRecord record) {
        List<KpiSharedCollectionService.MetricResult> stored = new ArrayList<>();
        try {
            for (JsonNode m : JSON.readTree(record.getSharedDetail()).path("metrics")) {
                stored.add(new KpiSharedCollectionService.MetricResult(
                    m.path("code").asText(), m.path("source").asText(),
                    parseOrZero(text(m.path("actual"))), parseOrZero(text(m.path("target"))),
                    parseOrZero(text(m.path("score"))), parseOrZero(text(m.path("weight"))),
                    m.path("included").asBoolean(true), m.path("message").asText("")));
            }
        } catch (Exception ex) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "对账失败：明细复算不可解析");
        }
        BigDecimal recomputed = collectionService.weightedScore(stored);
        BigDecimal storedTotal = record.getComprehensiveScore();
        String verdict = storedTotal != null && storedTotal.compareTo(recomputed) == 0
            ? VERDICT_MATCH : VERDICT_DIFF;
        return new String[]{storedTotal == null ? null : storedTotal.toPlainString(),
            recomputed.toPlainString(), verdict};
    }

    /** 双 PM 同分（AC-KPI-11）：最新 revision 恰 2 行且 comprehensive_score 一致。 */
    String[] reconcileDualPm(List<KpiRecord> latestRows, int revision) {
        if (latestRows.size() != 2) {
            return new String[]{VERDICT_DIFF,
                "最新 revision=" + revision + " 应恰 2 行（双 PM 同分），实际 " + latestRows.size() + " 行"};
        }
        BigDecimal a = latestRows.get(0).getComprehensiveScore();
        BigDecimal b = latestRows.get(1).getComprehensiveScore();
        boolean same = a != null && b != null && a.compareTo(b) == 0;
        return new String[]{same ? VERDICT_MATCH : VERDICT_DIFF,
            same ? "双 PM 同分（revision=" + revision + "）"
                : "双 PM 得分不一致: " + a + " vs " + b};
    }

    /** 确认链现状（K01-K04 双组长签署行；OVERDUE 读时派生，与 KpiSharedConfirmService 同口径）。 */
    List<ConfirmRow> listConfirms(Long projectId, String period, Date now) {
        List<KpiSharedConfirm> rows = confirmMapper.selectList(
            Wrappers.<KpiSharedConfirm>lambdaQuery()
                .eq(KpiSharedConfirm::getProjectId, projectId)
                .eq(KpiSharedConfirm::getPeriod, period)
                .orderByAsc(KpiSharedConfirm::getMetricCode));
        List<ConfirmRow> views = new ArrayList<>();
        for (KpiSharedConfirm row : rows) {
            boolean overdue = KpiSharedConfirmService.ST_PENDING.equals(row.getStatus())
                && row.getDeadlineAt() != null && row.getDeadlineAt().before(now);
            views.add(new ConfirmRow(row.getMetricCode(),
                overdue ? KpiSharedConfirmService.ST_OVERDUE : row.getStatus(),
                row.getFirstConfirmedBy() == null ? null : row.getFirstConfirmedBy().toString(),
                row.getSecondConfirmedBy() == null ? null : row.getSecondConfirmedBy().toString()));
        }
        return views;
    }

    /**
     * 对账结果接收人（产品组长）：项目在职双 PM 所属组的 leader + 主产品组 leader。
     *
     * <p>2026-10-07 校正：原 javadoc 自称「口径与 KpiSharedCollectionService#leadersForProject 一致」，
     * 实际<b>不一致</b>——{@code KpiSharedCollectionService:831-836} 有
     * {@code .in(role, [MARKET_PM, RD_PM])}，此处没有。缺 role 过滤时 MEMBER 行的
     * {@code person.groupId} 会把该组 leader 错误拉进对账接收人。本处补 role 过滤后两侧口径才真的一致。
     * 依据：{@code KpiSharedCollectionService#activeMembers}（同包私有不可达，此处独立实现）。
     */
    public Set<Long> leadersOf(Long projectId) {
        Set<Long> leaders = new LinkedHashSet<>();
        List<ProjectMember> members = projectMemberMapper.selectList(
            Wrappers.<ProjectMember>lambdaQuery()
                .eq(ProjectMember::getProjectId, projectId)
                .isNull(ProjectMember::getExitDate)
                .in(ProjectMember::getRole, List.of("MARKET_PM", "RD_PM")));
        for (ProjectMember member : members) {
            Person pm = member.getPersonId() == null ? null : personMapper.selectById(member.getPersonId());
            if (pm == null || pm.getGroupId() == null) {
                continue;
            }
            ProductGroup group = productGroupMapper.selectById(pm.getGroupId());
            if (group != null && group.getLeaderPersonId() != null) {
                leaders.add(group.getLeaderPersonId());
            }
        }
        Project project = projectMapper.selectById(projectId);
        if (project != null && project.getMainGroupId() != null) {
            ProductGroup main = productGroupMapper.selectById(project.getMainGroupId());
            if (main != null && main.getLeaderPersonId() != null) {
                leaders.add(main.getLeaderPersonId());
            }
        }
        return leaders;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** 台账展示口径：存储分与复算分（恒 2 位小数）同形可比，非法数值保留原文供 DIFF 注记追溯。 */
    private static String normalizeScore(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal parseOrZero(String raw) {
        BigDecimal v = parse(raw);
        return v == null ? BigDecimal.ZERO : v;
    }

    /* ---------- 报告契约（executor 渲染 markdown 的唯一数据源） ---------- */

    public record ReconcileReport(
        Long projectId, String period, boolean noData, List<Item> items,
        String storedTotal, String recomputedTotal, String totalVerdict,
        String dualPmVerdict, String dualPmNote, List<ConfirmRow> confirms, int revision) {

        static ReconcileReport awaitingCollection(Long projectId, String period) {
            List<Item> pending = new ArrayList<>();
            for (String code : List.of("K01", "K02", "K03", "K04")) {
                pending.add(new Item(code, null, null, null, null, null, null,
                    VERDICT_PENDING, "本期尚无归集记录，台账全项待补（不按 0 扣分）"));
            }
            return new ReconcileReport(projectId, period, true, pending,
                null, null, VERDICT_PENDING, VERDICT_PENDING, "无归集行", List.of(), 0);
        }

        /** 台账统计：各判定计数，供 summary 一句话。 */
        public java.util.Map<String, Long> verdictCounts() {
            java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
            items.forEach(i -> counts.merge(i.verdict(), 1L, Long::sum));
            return counts;
        }
    }

    public record Item(String code, String source, String actual, String storedTarget,
                       String currentTarget, String storedScore, String recomputedScore,
                       String verdict, String note) {
    }

    public record ConfirmRow(String metricCode, String status,
                             String firstConfirmedBy, String secondConfirmedBy) {
    }
}
