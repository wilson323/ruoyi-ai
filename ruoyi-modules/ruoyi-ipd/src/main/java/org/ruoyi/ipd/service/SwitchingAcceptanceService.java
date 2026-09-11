package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.HandoverRecord;
import org.ruoyi.ipd.domain.NegativeFeedback;
import org.ruoyi.ipd.domain.ProjectScore;
import org.ruoyi.ipd.domain.SwitchingAcceptance;
import org.ruoyi.ipd.dto.SwitchingAcceptanceReport;
import org.ruoyi.ipd.dto.SwitchingAcceptanceReport.CheckResult;
import org.ruoyi.ipd.dto.SwitchingAcceptanceUnlockReq;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.mapper.ProjectScoreMapper;
import org.ruoyi.ipd.mapper.SwitchingAcceptanceMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * P3-7.1 月度账务切换验收服务（BR-INC-12；AC-INC-50/51）
 *
 * <p>核心规则：
 * <ul>
 *   <li>AC-INC-50：对账差异率 < 1% 才允许 lock</li>
 *   <li>AC-INC-51：锁定月份所有账务写操作返回 SWITCHING_LOCKED（联动 Allowance / Bonus / NF / Contribution）</li>
 *   <li>5 类校验：ALLOWANCE_LOCKED_MATCH / BONUS_POOL_RATE / CONTRIB_TIER_RANGE / NF_REENTRY_GUARD / KPI_BONUS_LINKAGE</li>
 * </ul>
 *
 * <p>状态：每 D 一 一 对账记录（uk_switching_month）；run / lock / unlock 状态机。
 * <p>本期简化：5 类校验中只有"格式 / 存在性"判定；具体数值由外部数据驱动（run 时拉取）。
 * 真实数据走 AllowanceService / BonusPoolService / NegativeFeedbackService / ContributionService 接口。
 */
@Service
@RequiredArgsConstructor
public class SwitchingAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(SwitchingAcceptanceService.class);

    /** month 格式校验：YYYY-MM */
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    /** AC-INC-50：差异率 < 1% 才允许 lock */
    private static final BigDecimal MAX_DIFF_RATE = new BigDecimal("0.0100");

    private final SwitchingAcceptanceMapper switchingAcceptanceMapper;
    private final IpdPermission ipdPermission;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /* ---------- P0-9：真实对账数据源（nullable setter；生产 Spring 装配，单测显式 mock） ---------- */
    private AllowanceLedgerMapper allowanceLedgerMapper;
    private BonusPoolMapper bonusPoolMapper;
    private ProjectScoreMapper projectScoreMapper;
    private NegativeFeedbackMapper negativeFeedbackMapper;
    private ContributionMapper contributionMapper;
    private HandoverMapper handoverMapper;

    @Autowired(required = false) public void setAllowanceLedgerMapper(AllowanceLedgerMapper m) { this.allowanceLedgerMapper = m; }
    @Autowired(required = false) public void setBonusPoolMapper(BonusPoolMapper m) { this.bonusPoolMapper = m; }
    @Autowired(required = false) public void setProjectScoreMapper(ProjectScoreMapper m) { this.projectScoreMapper = m; }
    @Autowired(required = false) public void setNegativeFeedbackMapper(NegativeFeedbackMapper m) { this.negativeFeedbackMapper = m; }
    @Autowired(required = false) public void setContributionMapper(ContributionMapper m) { this.contributionMapper = m; }
    @Autowired(required = false) public void setHandoverMapper(HandoverMapper m) { this.handoverMapper = m; }

    /* ---------- P0-9：月度账务 run/lock/unlock 写路径审计（nullable setter；生产 Spring 装配，单测显式 mock） ---------- */
    private AuditLogService auditLogService;

    @Autowired(required = false)
    public void setAuditLogService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /* ---------- 可注入时钟（裸时钟守卫禁一：审计/报告时间戳走业务时钟；测试可固定） ---------- */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    @Autowired(required = false)
    public void setClock(java.time.Clock clock) {
        this.clock = clock;
    }

    private java.util.Date now() {
        return java.util.Date.from(clock.instant());
    }

    /* ===========================================================
     *  run 对账
     * =========================================================== */

    /**
     * 运行对账（生成报告）。
     * <p>本期实现：5 类校验全跑 + 落 switching_acceptance 表（uk_month 唯一）。
     * <p>如该月已存在 run 记录，则更新（保留历史；不覆盖 lock 状态）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SwitchingAcceptanceReport run(String monthStr) {
        IpdActor actor = ipdPermission.requireInternal();
        validateMonth(monthStr);

        List<CheckResult> checks = runChecks(monthStr);
        boolean allPassed = checks.stream().allMatch(CheckResult::passed);
        BigDecimal diffRate = computeDiffRate(checks);
        boolean passed = allPassed && diffRate.compareTo(MAX_DIFF_RATE) < 0;

        Map<String, Integer> summary = new HashMap<>();
        summary.put("totalChecks", checks.size());
        summary.put("passedChecks", (int) checks.stream().filter(CheckResult::passed).count());
        summary.put("failedChecks", checks.size() - summary.get("passedChecks"));

        SwitchingAcceptanceReport report = SwitchingAcceptanceReport.builder()
            .month(monthStr)
            .ranAt(now())
            .ranBy(actor.id())
            .isLocked(false)
            .diffRate(diffRate)
            .passed(passed)
            .checks(checks)
            .summary(summary)
            .build();

        String reportJson = toJson(report);

        SwitchingAcceptance existing = switchingAcceptanceMapper.selectOne(
            new LambdaQueryWrapper<SwitchingAcceptance>()
                .eq(SwitchingAcceptance::getMonth, monthStr)
                .eq(SwitchingAcceptance::getDelFlag, "0"));
        SwitchingAcceptance entity = existing != null ? existing : new SwitchingAcceptance();
        entity.setMonth(monthStr);
        entity.setRanAt(report.ranAt());
        entity.setRanBy(report.ranBy());
        entity.setReportJson(reportJson);
        entity.setDiffRate(diffRate);
        entity.setPassed(passed);
        if (entity.getId() == null) {
            entity.setDelFlag("0");
            switchingAcceptanceMapper.insert(entity);
        } else if (Boolean.TRUE.equals(entity.getIsLocked())) {
            // 已锁定月份的 run 不更新 report（保护 lock 状态）
            log.warn("[{}] run 时月份 {} 已锁定，跳过 report 更新", actor.id(), monthStr);
            return toReport(entity);
        } else {
            switchingAcceptanceMapper.updateById(entity);
        }

        log.info("[{}] run 月度对账 month={} passed={} diffRate={} totalChecks={}",
            actor.id(), monthStr, passed, diffRate, checks.size());
        auditSwitching(actor, "SWITCHING_RUN", entity,
            "run 月度对账 month=" + monthStr + " diffRate=" + diffRate + " passed=" + passed);
        return toReport(entity);
    }

    /* ===========================================================
     *  lock / unlock 月度锁定
     * =========================================================== */

    /**
     * 月度锁定（仅超管；AC-INC-51 联动锁定月份所有账务写入）。
     * <p>前置条件：run 已执行且 passed=true。
     */
    @Transactional(rollbackFor = Exception.class)
    public SwitchingAcceptanceReport lock(String monthStr) {
        IpdActor actor = ipdPermission.requireAdmin();
        validateMonth(monthStr);

        SwitchingAcceptance entity = requireRecord(monthStr);
        if (!Boolean.TRUE.equals(entity.getPassed())) {
            throw new IpdBusinessException(ApiV1ErrorCode.SWITCHING_DIFF_TOO_LARGE,
                "对账未通过（passed=" + entity.getPassed() + ", diffRate=" + entity.getDiffRate()
                    + "），差异率 ≥ 1% 不允许锁定");
        }
        if (Boolean.TRUE.equals(entity.getIsLocked())) {
            log.info("[{}] 月份 {} 已锁定，幂等返回", actor.id(), monthStr);
            return toReport(entity);
        }

        entity.setIsLocked(true);
        entity.setLockedAt(now());
        entity.setLockedBy(actor.id());
        entity.setUpdateBy(actor.id());
        switchingAcceptanceMapper.updateById(entity);

        log.info("[{}] 锁定月份 {} diffRate={}", actor.id(), monthStr, entity.getDiffRate());
        auditSwitching(actor, "SWITCHING_LOCK", entity,
            "锁定月份 month=" + monthStr + " diffRate=" + entity.getDiffRate());
        return toReport(entity);
    }

    /**
     * 月度解锁（仅超管；事故恢复用）。
     * <p>必须附解锁理由（unlockReason >= 5 字符）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SwitchingAcceptanceReport unlock(String monthStr, SwitchingAcceptanceUnlockReq req) {
        IpdActor actor = ipdPermission.requireAdmin();
        validateMonth(monthStr);
        if (req == null || req.reason() == null || req.reason().length() < 5) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "解锁理由不能少于 5 字符");
        }

        SwitchingAcceptance entity = requireRecord(monthStr);
        if (!Boolean.TRUE.equals(entity.getIsLocked())) {
            log.info("[{}] 月份 {} 未锁定，无需解锁", actor.id(), monthStr);
            return toReport(entity);
        }

        entity.setIsLocked(false);
        entity.setUnlockReason(req.reason());
        entity.setUnlockedAt(now());
        entity.setUnlockedBy(actor.id());
        entity.setUpdateBy(actor.id());
        switchingAcceptanceMapper.updateById(entity);

        log.warn("[{}] 解锁月份 {} reason={}", actor.id(), monthStr, req.reason());
        auditSwitching(actor, "SWITCHING_UNLOCK", entity,
            "解锁月份 month=" + monthStr + " reason=" + req.reason());
        return toReport(entity);
    }

    /* ===========================================================
     *  查询
     * =========================================================== */

    /**
     * 获取月份对账报告（已 run 过则直接返回）。
     */
    public SwitchingAcceptanceReport get(String monthStr) {
        ipdPermission.requireInternal();
        validateMonth(monthStr);
        SwitchingAcceptance entity = switchingAcceptanceMapper.selectOne(
            new LambdaQueryWrapper<SwitchingAcceptance>()
                .eq(SwitchingAcceptance::getMonth, monthStr)
                .eq(SwitchingAcceptance::getDelFlag, "0"));
        if (entity == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.SWITCHING_NOT_RUN,
                "月份 " + monthStr + " 尚未运行对账（SWITCHING_NOT_RUN）");
        }
        return toReport(entity);
    }

    /**
     * 检查月份是否锁定（其他账务 Service 联动调用）。
     * <p>未 run 或未锁定 → 返回 false；run 后 passed=true 但未 lock → 返回 false（可继续写）。
     */
    public boolean isMonthLocked(String monthStr) {
        if (monthStr == null) return false;
        SwitchingAcceptance entity = switchingAcceptanceMapper.selectOne(
            new LambdaQueryWrapper<SwitchingAcceptance>()
                .eq(SwitchingAcceptance::getMonth, monthStr)
                .eq(SwitchingAcceptance::getIsLocked, true)
                .eq(SwitchingAcceptance::getDelFlag, "0"));
        return entity != null;
    }

    /**
     * 列出已 run 月份（按月倒序）。
     */
    public List<SwitchingAcceptanceReport> list() {
        ipdPermission.requireInternal();
        List<SwitchingAcceptance> all = switchingAcceptanceMapper.selectList(
            new LambdaQueryWrapper<SwitchingAcceptance>()
                .eq(SwitchingAcceptance::getDelFlag, "0")
                .orderByDesc(SwitchingAcceptance::getMonth));
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<SwitchingAcceptanceReport> reports = new ArrayList<>(all.size());
        for (SwitchingAcceptance e : all) {
            reports.add(toReport(e));
        }
        return reports;
    }

    /* ===========================================================
     *  内部：5 类校验
     * =========================================================== */

    /**
     * 5 类校验（P0-9 真实对账替换桩实现；2026-09-09 R28）。
     * <ul>
     *   <li>ALLOWANCE_LOCKED_MATCH — 月内台账 final_amount 合计 vs bonus_pools.final_pool 合计（容差 0.01）</li>
     *   <li>KPI_FINALIZED_RATIO — project_scores 月窗口 FINALIZED 占比（无数据空过）</li>
     *   <li>NF_CLOSED_LOOP — triggerMonth 当月 DRAFT/PENDING_DECISION 未闭环必须为 0</li>
     *   <li>CONTRIB_COMPLETENESS — 月内提交评定须到 CONFIRMED 且 tier ∈ [0,1]</li>
     *   <li>HANDOVER_ARCHIVE_COMPLETENESS — 月内 COMPLETED 记录 archived_at 必须全部非空</li>
     * </ul>
     * 数据源缺失 fail-closed（禁假通过）；口径备注：bonus_pools 无 month 列，按 distributedAt 归属月。
     */
    private List<CheckResult> runChecks(String monthStr) {
        List<CheckResult> checks = new ArrayList<>(5);
        checks.add(checkAllowanceVsBonusPool(monthStr));
        checks.add(checkKpiFinalizedRatio(monthStr));
        checks.add(checkNegativeFeedbackClosedLoop(monthStr));
        checks.add(checkContributionCompleteness(monthStr));
        checks.add(checkHandoverArchiveCompleteness(monthStr));
        return checks;
    }

    private CheckResult dataSourceMissing(String name, String mappers) {
        return CheckResult.builder().name(name).passed(false)
            .expected("数据源装配").actual("缺失")
            .note(mappers + " 未注入（fail-closed，禁止假通过）").build();
    }

    /** "yyyy-MM" → [月初, 次月初) 半开区间。 */
    private static java.util.Date[] monthWindow(String month) {
        java.time.YearMonth ym = java.time.YearMonth.parse(month);
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        return new java.util.Date[]{
            java.util.Date.from(ym.atDay(1).atStartOfDay(z).toInstant()),
            java.util.Date.from(ym.plusMonths(1).atDay(1).atStartOfDay(z).toInstant())};
    }

    /** ① 月内台账 final_amount 合计 vs bonus_pools.final_pool 合计（distributedAt 归属月，容差 0.01）。 */
    private CheckResult checkAllowanceVsBonusPool(String month) {
        if (allowanceLedgerMapper == null || bonusPoolMapper == null) {
            return dataSourceMissing("ALLOWANCE_LOCKED_MATCH", "allowanceLedgerMapper/bonusPoolMapper");
        }
        BigDecimal ledgerSum = allowanceLedgerMapper.selectList(new LambdaQueryWrapper<AllowanceLedger>()
                .eq(AllowanceLedger::getMonth, month)
                .eq(AllowanceLedger::getDelFlag, "0"))
            .stream().map(AllowanceLedger::getFinalAmount)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        java.util.Date[] w = monthWindow(month);
        BigDecimal poolSum = bonusPoolMapper.selectList(new LambdaQueryWrapper<BonusPool>()
                .eq(BonusPool::getDelFlag, "0")
                .ge(BonusPool::getDistributedAt, w[0]).lt(BonusPool::getDistributedAt, w[1]))
            .stream().map(BonusPool::getFinalPool)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diff = ledgerSum.subtract(poolSum);
        return CheckResult.builder()
            .name("ALLOWANCE_LOCKED_MATCH")
            .passed(diff.abs().compareTo(new BigDecimal("0.01")) < 0)
            .expected(poolSum).actual(ledgerSum).diff(diff)
            .note("allowance_ledgers.final_amount 合计 vs bonus_pools.final_pool 合计（distributedAt 归属月）")
            .build();
    }

    /** ② project_scores FINALIZED 占比（scoredAt 月窗口）；无数据空过。 */
    private CheckResult checkKpiFinalizedRatio(String month) {
        if (projectScoreMapper == null) {
            return dataSourceMissing("KPI_FINALIZED_RATIO", "projectScoreMapper");
        }
        java.util.Date[] w = monthWindow(month);
        List<ProjectScore> rows = projectScoreMapper.selectList(new LambdaQueryWrapper<ProjectScore>()
            .eq(ProjectScore::getDelFlag, "0")
            .ge(ProjectScore::getScoredAt, w[0]).lt(ProjectScore::getScoredAt, w[1]));
        int total = rows.size();
        long finalized = rows.stream().filter(r -> "FINALIZED".equals(r.getStatus())).count();
        BigDecimal ratio = total == 0 ? BigDecimal.ONE
            : new BigDecimal(finalized).divide(new BigDecimal(total), 4, java.math.RoundingMode.HALF_UP);
        return CheckResult.builder().name("KPI_FINALIZED_RATIO").passed(total == 0 || finalized == total)
            .expected(new BigDecimal("1.0000")).actual(ratio)
            .diff(BigDecimal.ONE.subtract(ratio))
            .note("FINALIZED " + finalized + "/" + total).build();
    }

    /** ③ NF 闭环：triggerMonth=当月，DRAFT/PENDING_DECISION 未闭环必须为 0（EXECUTED/LIFTED/REJECTED 均闭环）。 */
    private CheckResult checkNegativeFeedbackClosedLoop(String month) {
        if (negativeFeedbackMapper == null) {
            return dataSourceMissing("NF_CLOSED_LOOP", "negativeFeedbackMapper");
        }
        List<NegativeFeedback> rows = negativeFeedbackMapper.selectList(
            new LambdaQueryWrapper<NegativeFeedback>()
                .eq(NegativeFeedback::getTriggerMonth, month)
                .eq(NegativeFeedback::getDelFlag, "0"));
        long open = rows.stream().filter(r ->
            "DRAFT".equals(r.getStatus()) || "PENDING_DECISION".equals(r.getStatus())).count();
        return CheckResult.builder().name("NF_CLOSED_LOOP").passed(open == 0)
            .expected(0).actual((int) open).duplicateCount((int) open)
            .note("未闭环=" + open + "，已闭环=" + (rows.size() - open)).build();
    }

    /** ④ Contribution 完整度：月内 submittedAt 记录须到 CONFIRMED 且 tierCoefficient ∈ [0,1]。 */
    private CheckResult checkContributionCompleteness(String month) {
        if (contributionMapper == null) {
            return dataSourceMissing("CONTRIB_COMPLETENESS", "contributionMapper");
        }
        java.util.Date[] w = monthWindow(month);
        List<Contribution> rows = contributionMapper.selectList(new LambdaQueryWrapper<Contribution>()
            .eq(Contribution::getDelFlag, "0")
            .ge(Contribution::getSubmittedAt, w[0]).lt(Contribution::getSubmittedAt, w[1]));
        long unconfirmed = rows.stream().filter(r ->
            !Contribution.ST_CONFIRMED.equals(r.getStatus())).count();
        long tierBad = rows.stream().filter(r -> r.getTierCoefficient() == null
            || r.getTierCoefficient().compareTo(BigDecimal.ZERO) < 0
            || r.getTierCoefficient().compareTo(BigDecimal.ONE) > 0).count();
        return CheckResult.builder().name("CONTRIB_COMPLETENESS")
            .passed(unconfirmed == 0 && tierBad == 0)
            .expected("unconfirmed=0, tier∈[0,1]")
            .actual("unconfirmed=" + unconfirmed + ", tierOutOfRange=" + tierBad)
            .note("月内提交评定完整度").build();
    }

    /** ⑤ Handover 归档完成度：月内 COMPLETED 记录 archived_at 必须全部非空。 */
    private CheckResult checkHandoverArchiveCompleteness(String month) {
        if (handoverMapper == null) {
            return dataSourceMissing("HANDOVER_ARCHIVE_COMPLETENESS", "handoverMapper");
        }
        java.util.Date[] w = monthWindow(month);
        List<HandoverRecord> rows = handoverMapper.selectList(new LambdaQueryWrapper<HandoverRecord>()
            .eq(HandoverRecord::getDelFlag, "0")
            .eq(HandoverRecord::getStatus, "COMPLETED")
            .ge(HandoverRecord::getCompletedAt, w[0]).lt(HandoverRecord::getCompletedAt, w[1]));
        long unarchived = rows.stream().filter(r -> r.getArchivedAt() == null).count();
        return CheckResult.builder().name("HANDOVER_ARCHIVE_COMPLETENESS")
            .passed(unarchived == 0).expected(0).actual((int) unarchived)
            .note("月内完成 " + rows.size() + " 条，未归档 " + unarchived + " 条").build();
    }

    /**
     * 总差异率 = sum(|diff|) / count（非 diff 字段权重为 0）。
     * <p>本期全 diff=0 → 总差异率=0。
     */
    private BigDecimal computeDiffRate(List<CheckResult> checks) {
        if (checks == null || checks.isEmpty()) return BigDecimal.ZERO;
        long total = 0;
        long nonZero = 0;
        for (CheckResult c : checks) {
            total++;
            if (c.diff() != null && c.diff().abs().compareTo(BigDecimal.ZERO) > 0) {
                nonZero++;
            }
        }
        return total == 0 ? BigDecimal.ZERO : new BigDecimal(nonZero).divide(new BigDecimal(total), 4, java.math.RoundingMode.HALF_UP);
    }

    /* ===========================================================
     *  辅助
     * =========================================================== */

    /**
     * P0-9：月度账务写路径审计（run / lock / unlock 三动作全部留痕）。actor 可空（系统路径，强制 null 跳过）。
     * <p>审计字段映射：{@code entityType="switching_acceptance"} + {@code entityId=month hash code}（month 非数字取 hashCode 强转 Long）。
     * <p>审计独立性：业务事务失败不会回滚审计（AuditLogService.append 用 REQUIRES_NEW 独立事务）。
     */
    private void auditSwitching(IpdActor actor, String action, SwitchingAcceptance entity, String reason) {
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.append(AuditLog.builder()
                .operatorId(actor == null ? 0L : actor.id())
                .operatorName(actor == null ? "system" : actor.name())
                .operatorRole(actor == null ? "SYSTEM" : actor.role())
                .action(action)
                .entityType("switching_acceptance")
                .entityId((long) (entity.getMonth() == null ? 0L : Math.abs(entity.getMonth().hashCode())))
                .reason(reason)
                .afterData(AuditEventData.json(
                    "month", entity.getMonth(),
                    "passed", Boolean.TRUE.equals(entity.getPassed()),
                    "isLocked", Boolean.TRUE.equals(entity.getIsLocked()),
                    "diffRate", entity.getDiffRate(),
                    "ranBy", entity.getRanBy(),
                    "lockedBy", entity.getLockedBy(),
                    "unlockedBy", entity.getUnlockedBy()))
                .createTime(now())
                .build());
        } catch (Exception e) {
            // 审计失败不应阻断业务（与 AllowanceService.auditInsert 同型 fail-soft）；日志告警即可
            log.warn("[{}] P0-9 切换验收审计写入失败 action={} month={} err={}",
                actor == null ? 0L : actor.id(), action, entity.getMonth(), e.getMessage());
        }
    }

    private void validateMonth(String monthStr) {
        if (monthStr == null || !MONTH_PATTERN.matcher(monthStr).matches()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "month 格式应为 YYYY-MM（当前=" + monthStr + "）");
        }
    }

    private SwitchingAcceptance requireRecord(String monthStr) {
        SwitchingAcceptance entity = switchingAcceptanceMapper.selectOne(
            new LambdaQueryWrapper<SwitchingAcceptance>()
                .eq(SwitchingAcceptance::getMonth, monthStr)
                .eq(SwitchingAcceptance::getDelFlag, "0"));
        if (entity == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.SWITCHING_NOT_RUN,
                "月份 " + monthStr + " 尚未运行对账");
        }
        return entity;
    }

    private SwitchingAcceptanceReport toReport(SwitchingAcceptance entity) {
        SwitchingAcceptanceReport base = parseJson(entity.getReportJson());
        return SwitchingAcceptanceReport.builder()
            .month(entity.getMonth())
            .ranAt(entity.getRanAt())
            .ranBy(entity.getRanBy())
            .isLocked(Boolean.TRUE.equals(entity.getIsLocked()))
            .lockedAt(entity.getLockedAt())
            .lockedBy(entity.getLockedBy())
            .diffRate(entity.getDiffRate())
            .passed(Boolean.TRUE.equals(entity.getPassed()))
            .checks(base == null ? List.of() : base.checks())
            .summary(base == null ? Map.of() : base.summary())
            .unlockReason(entity.getUnlockReason())
            .unlockedAt(entity.getUnlockedAt())
            .unlockedBy(entity.getUnlockedBy())
            .build();
    }

    private String toJson(SwitchingAcceptanceReport report) {
        try {
            return jsonMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new ServiceException("切换报告序列化失败: " + e.getMessage());
        }
    }

    private SwitchingAcceptanceReport parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return jsonMapper.readValue(json, SwitchingAcceptanceReport.class);
        } catch (Exception e) {
            log.warn("切换报告反序列化失败: {}", e.getMessage());
            return null;
        }
    }
}