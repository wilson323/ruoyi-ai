package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.NegativeFeedback;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 月度津贴台账服务（P3-3.1/3.2/3.3；AC-INC-03/04/05/06/07/08；BR-INC-02/03/11）
 *
 * <p>核心规则：
 * <ul>
 *   <li>AC-INC-03：L3 级 PM 同时绑定 4 个项目 ⇒ 2000×4=8000，封顶 2000×2=4000，实发 4000</li>
 *   <li>AC-INC-04：绑定 2 个项目 ⇒ 2000×2=4000，未超封顶，全额发放</li>
 *   <li>AC-INC-06：津贴不乘绩效系数（与奖金分离）</li>
 *   <li>BR-INC-02：评级在绑定 ProjectMember 时锁定（lockedLevel/lockedAmount 来自 P2-4.1）</li>
 *   <li>BR-INC-03：多项目叠加，封顶 2×基准额 capMultiplier</li>
 *   <li>AC-INC-05：绩效综合 < 60 当月停发（BR-INC-11）——<b>停发腿，owner 2026-10-07 保持不变</b></li>
 *   <li>AC-INC-07：附加项目连续 60 天无产出（动作/交付物/记录/Gate 四类并集）⇒
 *       <b>owner 2026-10-07 翻案：改为「仅提醒」，照常发钱、不写停发标记</b>
 *       （原口径为停发，见 {@link #determineNoOutput60DaysStop}）</li>
 *   <li>AC-INC-08：主项目（非附加）无产出 ⇒ 不触发提醒</li>
 *   <li>BR-INC-03（2026-10-07 真削减）：封顶倍数 {@code allowance.capMultiplier}（默认 2）
 *       真正削减 final_amount，不再只是打 capApplied 标记</li>
 *   <li>BR-INC-10（2026-10-07 接线）：负反馈入金额——主责 STOP_ALLOWANCE ⇒ ×0，
 *       连带 HALVE_ALLOWANCE ⇒ ×0.5</li>
 *   <li>幂等：(personId, projectId, month) 唯一，重复执行不重复台账（P3-3.3）</li>
 *   <li>P3-3.3：月中移交当月按月初人员、新人次月生效；退出次月停发</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AllowanceService implements IAllowanceService {

    private final AllowanceLedgerMapper allowanceLedgerMapper;
    private final ProjectMemberMapper projectMemberMapper;

    /** P0-7：写路径审计（nullable setter 注入兼容旧测试 2 参构造；生产由 Spring 装配）。 */
    private IAuditLogService auditLogService;

    @Autowired(required = false)
    public void setAuditLogService(IAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * R219 卡④（ef20c06a）：月度台账生成链的低分停发数据源（可选注入）。
     * 缺失时 generateMonthlyLedgers 不判停发腿——宁可少停不误停，与 P3-3.2 保守口径一致。
     */
    private KpiRecordMapper kpiRecordMapper;

    @Autowired(required = false)
    public void setKpiRecordMapper(KpiRecordMapper kpiRecordMapper) {
        this.kpiRecordMapper = kpiRecordMapper;
    }

    /**
     * R219 收尾波（2026-09-26 owner 授权三项落地）：AC-INC-07 NO_OUTPUT_60_DAYS 腿的
     * 四类并集活动数据源（动作/交付物/记录/Gate，可选注入，同 kpiRecordMapper 保守口径
     * ——任一缺失跳过该类信号，少算不误停）。
     */
    private StageActionMapper stageActionMapper;
    private DeliverableMapper deliverableMapper;
    private GateReviewMapper gateReviewMapper;

    @Autowired(required = false)
    public void setActivityMappers(StageActionMapper stageActionMapper,
                                   DeliverableMapper deliverableMapper,
                                   GateReviewMapper gateReviewMapper) {
        this.stageActionMapper = stageActionMapper;
        this.deliverableMapper = deliverableMapper;
        this.gateReviewMapper = gateReviewMapper;
    }

    /** 可注入时钟（裸时钟守卫禁一：审计时间戳走业务时钟；测试固定时刻消除摇摆）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    /* ======================================================================
     *  2026-10-07 ④刀「津贴按原需求」第 1 批：三项死配置接线 + 负反馈入金额 + 金额变更审计
     *  全部沿用本类既有「required=false 可选注入 + 缺失则退化」范式（见 kpiRecordMapper），
     *  保证裸 2 参构造的存量单测不破；缺数据源一律走「不动钱」的保守方向。
     * ====================================================================== */

    /** {@code allowance.capMultiplier} 读入口；未装配 ⇒ 回落 {@link #DEFAULT_CAP_MULTIPLIER}。 */
    private ISystemConfigService systemConfigService;

    @Autowired(required = false)
    public void setSystemConfigService(ISystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /** 负反馈（BR-INC-10）数据源；未装配 ⇒ 不做负反馈金额折减。 */
    private NegativeFeedbackMapper negativeFeedbackMapper;

    @Autowired(required = false)
    public void setNegativeFeedbackMapper(NegativeFeedbackMapper negativeFeedbackMapper) {
        this.negativeFeedbackMapper = negativeFeedbackMapper;
    }

    /** 提醒通知出口（无产出「仅提醒」腿用）；未装配 ⇒ 只算不提醒，不影响金额。 */
    private INotificationService notificationService;

    @Autowired(required = false)
    public void setNotificationService(INotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 无产出「仅提醒」腿的通知事件类型（不改 NotificationService.Types：本类无权改那份文件）。 */
    public static final String EVENT_NO_OUTPUT_REMINDER = "ALLOWANCE_NO_OUTPUT_REMINDER";

    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }

    private Date now() { return Date.from(clock.instant()); }

    /** 金额台账写入审计（无登录态上下文 → 系统操作人，与 RequirementChangeService.SYSTEM_ACTOR 同型）。 */
    private void auditInsert(AllowanceLedger ledger, String action) {
        if (auditLogService == null) {
            return;
        }
        auditLogService.append(AuditLog.builder()
            .operatorId(0L).operatorName("system").operatorRole("SYSTEM")
            .action(action)
            .entityType("allowance_ledgers")
            .entityId(ledger.getId())
            .reason("personId=" + ledger.getPersonId() + ",projectId=" + ledger.getProjectId()
                + ",month=" + ledger.getMonth())
            .afterData(AuditEventData.json(
                "personId", ledger.getPersonId(),
                "projectId", ledger.getProjectId(),
                "month", ledger.getMonth(),
                "finalAmount", ledger.getFinalAmount(),
                "lockedLevel", ledger.getLockedLevel(),
                "baseAmount", ledger.getBaseAmount(),
                "capApplied", ledger.getCapApplied()))
            .createTime(now())
            .build());
    }

    /** AC-INC-03/04：默认 2 倍封顶 */
    public static final BigDecimal DEFAULT_CAP_MULTIPLIER = new BigDecimal("2");

    /** 封顶倍数配置键（sys_configs.config_key，种子见 docs/script/sql/update/2026-09-04-ipd-p0-config-seed.sql）。 */
    public static final String CONFIG_CAP_MULTIPLIER = "allowance.capMultiplier";

    /**
     * 2026-10-07（A4-27/29 真削减）解析封顶倍数。
     *
     * <p>此前 {@code allowance.capMultiplier} 是零调用方的死配置：本类硬编码 2，
     * 台账只打 capApplied 标记、final_amount 仍按各行 lockedAmount 落全额，
     * 「封顶」在写库链路上从未真正削减过钱。现改为读配置。
     *
     * <p>口径（保守方向，宁可少封不可多封）：配置服务未装配 / 键缺失 / 值非法（≤0）
     * 一律回落 {@link #DEFAULT_CAP_MULTIPLIER}。
     */
    public BigDecimal resolveCapMultiplier() {
        if (systemConfigService == null) {
            return DEFAULT_CAP_MULTIPLIER;
        }
        String raw;
        try {
            raw = systemConfigService.getValue(CONFIG_CAP_MULTIPLIER, DEFAULT_CAP_MULTIPLIER.toPlainString());
        } catch (RuntimeException e) {
            return DEFAULT_CAP_MULTIPLIER;
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CAP_MULTIPLIER;
        }
        BigDecimal v;
        try {
            v = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_CAP_MULTIPLIER;
        }
        return v.compareTo(BigDecimal.ZERO) > 0 ? v : DEFAULT_CAP_MULTIPLIER;
    }

    /**
     * AC-INC-03/04：月度多项目叠加 + 封顶（封顶倍数取 {@code allowance.capMultiplier}）
     * 实发 = MIN(Σ(项目津贴), capMultiplier × 基准额)
     * 基准额取当月最高 lockedAmount（即最高级别项目的锁定额）
     */
    public BigDecimal calculateMonthlyAllowance(Long personId, String month) {
        List<ProjectMember> members = projectMemberMapper.selectList(
            new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getPersonId, personId)
                .isNull(ProjectMember::getExitDate)); // 排除已退出（2026-09-09 owner 拍板：当月退出当月不发，当前在职过滤与此口径一致）
        if (members == null || members.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (ProjectMember m : members) {
            BigDecimal amt = calculateProjectAllowance(m);
            sum = sum.add(amt);
            if (amt.compareTo(base) > 0) {
                base = amt;
            }
        }
        BigDecimal cap = base.multiply(resolveCapMultiplier());
        return sum.min(cap);
    }

    /** P3-3.2 AC-INC-05/07：绩效低于 60 停发阈值 */
    public static final BigDecimal SCORE_STOP_THRESHOLD = new BigDecimal("60");

    /** P3-3.2 AC-INC-07：附加项目连续无产出天数阈值 */
    public static final int NO_OUTPUT_DAYS_THRESHOLD = 60;

    /**
     * 计算单项目津贴（绑定 ProjectMember 时的 lockedAmount 已锁定）
     * 不会乘绩效系数（AC-INC-06）
     */
    public BigDecimal calculateProjectAllowance(ProjectMember member) {
        if (member == null || member.getLockedAmount() == null) {
            return BigDecimal.ZERO;
        }
        return member.getLockedAmount();
    }

    /**
     * AC-INC-04/03：构造台账（不写库）
     */
    public AllowanceLedger buildLedger(Long personId, String month, BigDecimal finalAmount,
                                       String lockedLevel, BigDecimal baseAmount, boolean capApplied) {
        return AllowanceLedger.builder()
            .personId(personId)
            .month(month)
            .finalAmount(finalAmount)
            .lockedLevel(lockedLevel)
            .baseAmount(baseAmount)
            .capApplied(capApplied ? "1" : "0")
            .build();
    }

    /**
     * 幂等：personId + projectId + month 唯一
     * 已存在则跳过（不重复台账）
     */
    @Transactional(rollbackFor = Exception.class)
    public AllowanceLedger recordOrSkip(AllowanceLedger ledger, Long projectId) {
        Long existing = allowanceLedgerMapper.selectCount(
            new LambdaQueryWrapper<AllowanceLedger>()
                .eq(AllowanceLedger::getPersonId, ledger.getPersonId())
                .eq(AllowanceLedger::getProjectId, projectId)
                .eq(AllowanceLedger::getMonth, ledger.getMonth()));
        if (existing != null && existing > 0) {
            return null; // 幂等：跳过
        }
        ledger.setProjectId(projectId);
        allowanceLedgerMapper.insert(ledger);
        auditInsert(ledger, "ALLOWANCE_LEDGER_INSERT");
        return ledger;
    }

    /**
     * AC-INC-05/07/08：判定当月停发原因（兼容旧契约）
     */
    public String determineStopReason(BigDecimal comprehensiveScore, boolean noOutput60Days,
                                      boolean isMainProject) {
        if (isMainProject && noOutput60Days) {
            return null;
        }
        if (comprehensiveScore != null && comprehensiveScore.compareTo(SCORE_STOP_THRESHOLD) < 0) {
            return "SCORE_BELOW_60";
        }
        if (noOutput60Days) {
            return "NO_OUTPUT_60_DAYS";
        }
        return null;
    }

    /**
     * P3-3.2 AC-INC-05：绩效综合得分 < 60 当月停发。
     */
    public String determineLowScoreStop(BigDecimal comprehensiveScore) {
        if (comprehensiveScore == null) {
            return null;
        }
        if (comprehensiveScore.compareTo(SCORE_STOP_THRESHOLD) < 0) {
            return "STOP_SCORE_BELOW_60";
        }
        return null;
    }

    /**
     * 无产出「仅提醒」标记（owner 2026-10-07 翻案后的唯一返回值）。
     *
     * <p>原口径为 {@code "STOP_NO_OUTPUT_60_DAYS"}（停发）。翻案后：
     * <b>无产出不停发</b>——final_amount 照常按项目锁定额发放、不写 stop_start_date，
     * 该标记只用于驱动提醒通知，绝不允许出现在台账的 stop_reason / stop_start_date 上，
     * 避免台账里的「停发」语义被稀释成「提醒」。
     */
    public static final String REMIND_NO_OUTPUT_60_DAYS = "REMIND_NO_OUTPUT_60_DAYS";

    /**
     * P3-3.2 AC-INC-07/08：附加项目连续 60 天无产出判定。
     *
     * <p><b>owner 2026-10-07 业务翻案：原口径为停发（返回 {@code "STOP_NO_OUTPUT_60_DAYS"}
     * 并把 final_amount 打成 0），现改为「仅提醒」</b>——返回值语义从「停发原因」改为
     * 「提醒标记」，调用方据此发通知，但<b>不得</b>据此停发或写 stop_start_date。
     * 低分腿（{@link #determineLowScoreStop}）不受本次翻案影响，仍然停发。
     */
    public String determineNoOutput60DaysStop(Date lastActivityDate, Date asOfDate,
                                              boolean isAdditionalProject) {
        if (!isAdditionalProject) {
            return null;
        }
        if (asOfDate == null) {
            return null;
        }
        if (lastActivityDate == null) {
            return REMIND_NO_OUTPUT_60_DAYS;
        }
        long diffMs = asOfDate.getTime() - lastActivityDate.getTime();
        long days = diffMs / (24L * 60 * 60 * 1000);
        if (days >= NO_OUTPUT_DAYS_THRESHOLD) {
            return REMIND_NO_OUTPUT_60_DAYS;
        }
        return null;
    }

    /**
     * AC-INC-07 主/附加项目判定：member_type=ADDITIONAL 为附加；
     * null/PRIMARY/CORE/FORMAL 均视为主项目（保守不误停，AC-INC-08）。
     */
    static boolean isAdditionalMember(ProjectMember m) {
        return m != null && "ADDITIONAL".equals(m.getMemberType());
    }

    /**
     * R219 收尾波：三类活动专用 mapper（动作/交付物/Gate）至少装配一个才判 NO_OUTPUT 腿——
     * 全缺失 ⇒ 数据源不可用，整腿跳过（同 kpiRecordMapper 缺失不判低分腿的保守口径，AC-INC-07）。
     */
    boolean hasAnyActivitySource() {
        return stageActionMapper != null || deliverableMapper != null || gateReviewMapper != null;
    }

    /**
     * R219 收尾波：AC-INC-07 四类并集活动数据源——该人该项目在
     * 动作确认（confirmed_at）/交付物上传（uploaded_at）/KPI 评分（scored_at）/Gate 签署（signed_at）
     * 四类信号上的最近时间。任一 mapper 未装配则跳过该类（少算一类 ⇒ 更少停发，保守方向）。
     * 全未装配时本方法返回 null，但 "null=从未活动" 仅在数据源可用时有意义——
     * 调用方必须先过 {@link #hasAnyActivitySource()} 守卫，防把 "查不到" 误判为 "从未活动" 而停发。
     */
    Date resolveLastActivityDate(Long personId, Long projectId) {
        Date last = null;
        if (stageActionMapper != null) {
            StageAction sa = stageActionMapper.selectOne(new LambdaQueryWrapper<StageAction>()
                .eq(StageAction::getProjectId, projectId)
                .eq(StageAction::getConfirmedBy, personId)
                .isNotNull(StageAction::getConfirmedAt)
                .orderByDesc(StageAction::getConfirmedAt)
                .last("LIMIT 1"));
            last = maxDate(last, sa == null ? null : sa.getConfirmedAt());
        }
        if (deliverableMapper != null) {
            Deliverable d = deliverableMapper.selectOne(new LambdaQueryWrapper<Deliverable>()
                .eq(Deliverable::getProjectId, projectId)
                .eq(Deliverable::getUploadedBy, personId)
                .isNotNull(Deliverable::getUploadedAt)
                .orderByDesc(Deliverable::getUploadedAt)
                .last("LIMIT 1"));
            last = maxDate(last, d == null ? null : d.getUploadedAt());
        }
        if (kpiRecordMapper != null) {
            KpiRecord k = kpiRecordMapper.selectOne(new LambdaQueryWrapper<KpiRecord>()
                .eq(KpiRecord::getProjectId, projectId)
                .eq(KpiRecord::getPersonId, personId)
                .isNotNull(KpiRecord::getScoredAt)
                .orderByDesc(KpiRecord::getScoredAt)
                .last("LIMIT 1"));
            last = maxDate(last, k == null ? null : k.getScoredAt());
        }
        if (gateReviewMapper != null) {
            GateReview g = gateReviewMapper.selectOne(new LambdaQueryWrapper<GateReview>()
                .eq(GateReview::getProjectId, projectId)
                .eq(GateReview::getReviewerId, personId)
                .isNotNull(GateReview::getSignedAt)
                .orderByDesc(GateReview::getSignedAt)
                .last("LIMIT 1"));
            last = maxDate(last, g == null ? null : g.getSignedAt());
        }
        return last;
    }

    private static Date maxDate(Date a, Date b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.after(b) ? a : b;
    }

    /**
     * P3-3.2：判定停发原因（综合绩效分 + 无产出 + 主/附加）三层合一。
     */
    public String determineStopReasonP332(BigDecimal comprehensiveScore,
                                          Date lastActivityDate, Date asOfDate,
                                          boolean isAdditionalProject) {
        if (!isAdditionalProject && lastActivityDate == null) {
            return null;
        }
        String scoreStop = determineLowScoreStop(comprehensiveScore);
        if (scoreStop != null) {
            return scoreStop;
        }
        return determineNoOutput60DaysStop(lastActivityDate, asOfDate, isAdditionalProject);
    }

    /**
     * P3-3.3 AC-INC：津贴台账幂等检查——(personId, projectId, month) 唯一
     */
    public boolean existsByKey(Long personId, Long projectId, String month) {
        Long cnt = allowanceLedgerMapper.selectCount(
            new LambdaQueryWrapper<AllowanceLedger>()
                .eq(AllowanceLedger::getPersonId, personId)
                .eq(AllowanceLedger::getProjectId, projectId)
                .eq(AllowanceLedger::getMonth, month));
        return cnt != null && cnt > 0;
    }

    /**
     * P3-3.3 AC-INC：月中移交当月按月初人员判定。
     */
    public boolean isMemberEffectiveInMonth(Date joinDate, String month) {
        if (joinDate == null) {
            return false;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        String joinStr = sdf.format(joinDate);
        String monthStart = month + "-01";
        return joinStr.compareTo(monthStart) <= 0;
    }

    /**
     * P3-3.3 AC-INC：退出当月停发。
     * 2026-09-09 owner 拍板「当月退出不发」，原「退出次月停发」宽松口径作废（与主流程
     * calculateMonthlyAllowance 的 isNull(exitDate) 严格口径对齐，双口径并存问题消除）。
     * <pre>
     *   exitDate == null                          ⇒ active（未退出）
     *   exitDate >= 查询月的次月月初                ⇒ active（退出发生在查询月之后，该月整月在岗）
     *   exitDate 在查询月内或更早（含月末当天退）    ⇒ not active（当月退出当月即停）
     * </pre>
     */
    public boolean isMemberActiveInMonth(Date exitDate, String month) {
        if (exitDate == null) {
            return true;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        String exitStr = sdf.format(exitDate);
        // 退出在查询月结束之后（次月月初及以后）⇒ 该月整月在岗仍 active；当月内退出（含月末当天）即停
        return exitStr.compareTo(nextMonthStart(month)) >= 0;
    }

    /** month 格式 yyyy-MM → 次月月初 yyyy-MM-01 字符串（用于字符串字典序比较）。 */
    private static String nextMonthStart(String month) {
        String[] parts = month.split("-");
        int year = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        if (m == 12) {
            return (year + 1) + "-01-01";
        }
        return String.format("%d-%02d-01", year, m + 1);
    }

    // 2026-09-09 二次勘误（当日回滚）：上轮以「全仓零 caller」为由删除本方法系误判——
    // 验证 grep 被 head -8 截断，漏看了 P333AcceptanceTest 对它的 7 处调用（带 @Tag("dev") 真跑）。
    // 它是 P3-3.3 AC-INC 的验收契约方法，恢复。双口径冲突已于 2026-09-09 owner 拍板解决：
    // 「当月退出不发」——退出侧 isMemberActiveInMonth 已改为月末在岗口径，与主流程一致。

    /**
     * P3-3.3 AC-INC：月中移交 + 退出月份归属综合判定（验收契约，P333AcceptanceTest 盯守）。
     */
    public boolean isMemberActiveForMonth(ProjectMember member, String month) {
        if (member == null) {
            return false;
        }
        if (member.getExitDate() != null) {
            return isMemberActiveInMonth(member.getExitDate(), month);
        }
        return isMemberEffectiveInMonth(member.getJoinDate(), month);
    }

    /**
     * P3-3.3 AC-INC：人员-项目-月复合主键冲突检测（并发重算护栏）
     */
    @Transactional(rollbackFor = Exception.class)
    public AllowanceLedger idempotentInsert(AllowanceLedger ledger) {
        if (ledger == null) {
            throw new IpdBusinessException("津贴草稿不能为空");
        }
        if (existsByKey(ledger.getPersonId(), ledger.getProjectId(), ledger.getMonth())) {
            return null;
        }
        allowanceLedgerMapper.insert(ledger);
        auditInsert(ledger, "ALLOWANCE_LEDGER_INSERT");
        return ledger;
    }

    public List<AllowanceLedger> listByPerson(Long personId, String month) {
        return allowanceLedgerMapper.selectList(
            new LambdaQueryWrapper<AllowanceLedger>()
                .eq(AllowanceLedger::getPersonId, personId)
                .eq(AllowanceLedger::getMonth, month));
    }

    /* ======================================================================
     *  2026-10-07 ④刀：金额变更审计 + 负反馈入金额 + 无产出提醒通知
     * ====================================================================== */

    /**
     * A4-56 / A4-61：任何导致 {@code final_amount} 与项目锁定额产生偏离的计算分支
     * （封顶削减 / 负反馈折减 / 低分停发）都要留审计，含变更前后两个金额。
     *
     * <p>注意与 {@link #auditInsert} 的分工：auditInsert 记「这一行写进去了」，
     * 本方法记「这一行的钱被谁改了、为什么」；两者都会写，缺一不可。
     */
    private void auditAdjustment(AllowanceLedger ledger, String action, String reason) {
        if (auditLogService == null) {
            return;
        }
        auditLogService.append(AuditLog.builder()
            .operatorId(0L).operatorName("system").operatorRole("SYSTEM")
            .action(action)
            .entityType("allowance_ledgers")
            .entityId(ledger.getId())
            .reason(reason)
            .afterData(AuditEventData.json(
                "personId", ledger.getPersonId(),
                "projectId", ledger.getProjectId(),
                "month", ledger.getMonth(),
                "baseAmount", ledger.getBaseAmount(),
                "finalAmount", ledger.getFinalAmount(),
                "capApplied", ledger.getCapApplied(),
                "stopReason", ledger.getStopReason()))
            .createTime(now())
            .build());
    }

    /** BR-INC-10 负反馈执行码。 */
    private static final String NF_EXEC_STOP = "STOP_ALLOWANCE";
    private static final String NF_EXEC_HALVE = "HALVE_ALLOWANCE";

    /** 负反馈折减因子：主责停发 ×0，连带减半 ×0.5，无负反馈 ×1。 */
    private static final BigDecimal NF_FACTOR_HALVE = new BigDecimal("0.5");

    /**
     * BR-INC-10（A4-43 接线）：解析某人在某项目上的负反馈金额折减因子。
     *
     * <p>口径：查 {@code negative_feedbacks} 中 status=EXECUTED 且未 LIFTED（LIFTED 状态
     * 本身即被 status 过滤掉）的记录；主责人取 mainExecution，连带人取 relatedExecution。
     * 同人多条取<b>最强</b>（因子最小，×0 优先于 ×0.5）。
     *
     * <p>生效月份：triggerMonth 为空视为不限月；非空时只在该月生效。
     * 数据源未装配 ⇒ 返回 1（不动钱，保守方向）。
     */
    BigDecimal resolveNegativeFeedbackFactor(Long personId, Long projectId, String month,
                                             Map<Long, List<NegativeFeedback>> cache) {
        if (negativeFeedbackMapper == null || personId == null || projectId == null) {
            return BigDecimal.ONE;
        }
        List<NegativeFeedback> list = cache.computeIfAbsent(projectId, pid -> {
            List<NegativeFeedback> rows = negativeFeedbackMapper.selectList(
                new LambdaQueryWrapper<NegativeFeedback>()
                    .eq(NegativeFeedback::getProjectId, pid)
                    .eq(NegativeFeedback::getStatus, "EXECUTED"));
            return rows == null ? List.of() : rows;
        });
        BigDecimal factor = BigDecimal.ONE;
        for (NegativeFeedback nf : list) {
            String tm = nf.getTriggerMonth();
            if (tm != null && !tm.isBlank() && month != null && !tm.equals(month)) {
                continue;
            }
            if (personId.equals(nf.getMainPersonId()) && NF_EXEC_STOP.equals(nf.getMainExecution())) {
                factor = BigDecimal.ZERO;
                break; // ×0 是上界，无可再强
            }
            if (personId.equals(nf.getRelatedPersonId()) && NF_EXEC_HALVE.equals(nf.getRelatedExecution())
                && factor.compareTo(NF_FACTOR_HALVE) > 0) {
                factor = NF_FACTOR_HALVE;
            }
        }
        return factor;
    }

    /**
     * 无产出「仅提醒」腿的通知出口。
     *
     * <p>宿主 {@code generateMonthlyLedgers} 带事务，而 publish 自带 REQUIRED 传播：
     * 直接在事务内调用一旦内部抛异常会把宿主事务打成 rollback-only，宿主 catch 不住。
     * 故有活跃事务时挂 afterCommit，通知失败只 WARN，绝不反噬钱账主链。
     */
    private void publishNoOutputReminder(AllowanceLedger ledger) {
        if (notificationService == null || ledger.getPersonId() == null) {
            return;
        }
        Long sourceId = ledger.getId() == null ? ledger.getProjectId() : ledger.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublishNoOutputReminder(ledger, sourceId);
                }
            });
        } else {
            doPublishNoOutputReminder(ledger, sourceId);
        }
    }

    private void doPublishNoOutputReminder(AllowanceLedger ledger, Long sourceId) {
        try {
            notificationService.publish(ledger.getPersonId(), EVENT_NO_OUTPUT_REMINDER,
                NotificationService.KIND_ACTION, "allowance_ledgers", sourceId,
                "津贴提醒：附加项目连续 60 天无产出",
                "账期 " + ledger.getMonth() + "：附加项目已连续 60 天无产出活动"
                    + "（按 2026-10-07 owner 拍板，本次仅提醒、不停发，津贴照常发放），请尽快推进。",
                null);
        } catch (RuntimeException e) {
            // 提醒失败绝不影响台账落库与金额
        }
    }

    /** 金额四舍五入到分（台账 decimal(10,2)）。 */
    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * R219 卡④（ef20c06a）：按月全量生成津贴台账（auto-scan 从「只计数」补齐为「先生成后计数」）。
     *
     * <p>编排口径：
     * <ul>
     *   <li>候选人：project_members 未退出（isNull exitDate），逐条再过
     *       {@link #isMemberActiveForMonth}（入职晚于当月/当月内退出均跳过）；</li>
     *   <li>幂等：{@link #existsByKey} 命中则跳过，重复扫描不重复成账（P3-3.3）；</li>
     *   <li>金额（A4-27/29 真削减）：每人当月封顶额 = capMultiplier × 最高锁定额，
     *       Σ 超过则<b>按比例削减各行 final_amount</b>（Σ ≤ 封顶额时系数为 1，金额逐行不变）；
     *       capApplied 仍按人当月维度打标，与实际是否削减同源同口径；</li>
     *   <li>负反馈（A4-43 接线 BR-INC-10）：主责 STOP_ALLOWANCE ⇒ ×0，连带 HALVE_ALLOWANCE ⇒ ×0.5，
     *       在封顶之后叠加；负反馈数据源未装配 ⇒ 不折减；</li>
     *   <li>停发腿（2026-10-07 owner 翻案后）：<b>只剩低分腿</b>（当月 FINALIZED KPI 综合分 &lt; 60
     *       → STOP_SCORE_BELOW_60，finalAmount=0）；无产出腿（附加项目四类并集活动空/超 60 天，
     *       AC-INC-07/08）<b>改为仅提醒</b>——照常发钱、不写 stop_reason/stop_start_date，
     *       仅发 {@link #EVENT_NO_OUTPUT_REMINDER} 通知；</li>
     *   <li>kpiRecordMapper 未注入不判低分腿；活动类 mapper 未注入跳过该类信号，防误提醒；</li>
     *   <li>金额变更审计（A4-56/61）：封顶削减 / 负反馈折减 / 低分停发三条分支各自写审计记录。</li>
     * </ul>
     *
     * @param month 账期 yyyy-MM
     * @return 新生成的台账行数（幂等命中不计）
     */
    @Transactional(rollbackFor = Exception.class)
    public int generateMonthlyLedgers(String month) {
        if (month == null || !month.matches("\\d{4}-\\d{2}")) {
            throw new IpdBusinessException("month 格式应为 yyyy-MM: " + month);
        }
        List<ProjectMember> candidates = projectMemberMapper.selectList(
            new LambdaQueryWrapper<ProjectMember>().isNull(ProjectMember::getExitDate));
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        BigDecimal capMultiplier = resolveCapMultiplier();

        // 按人预聚合：cap 判定与比例削减用（Σ 与最高锁定额）
        Map<Long, BigDecimal[]> sumAndBase = new HashMap<>();
        for (ProjectMember m : candidates) {
            if (m.getPersonId() == null || m.getProjectId() == null
                || !isMemberActiveForMonth(m, month)) {
                continue;
            }
            BigDecimal amt = calculateProjectAllowance(m);
            BigDecimal[] sb = sumAndBase.computeIfAbsent(m.getPersonId(),
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            sb[0] = sb[0].add(amt);
            if (amt.compareTo(sb[1]) > 0) {
                sb[1] = amt;
            }
        }

        Map<Long, Boolean> lowScoreByPerson = new HashMap<>();
        Map<Long, List<NegativeFeedback>> nfCache = new HashMap<>();
        int created = 0;
        for (ProjectMember m : candidates) {
            if (m.getPersonId() == null || m.getProjectId() == null
                || !isMemberActiveForMonth(m, month)) {
                continue;
            }
            if (existsByKey(m.getPersonId(), m.getProjectId(), month)) {
                continue; // 幂等：已成账不重复
            }
            BigDecimal amt = calculateProjectAllowance(m);
            BigDecimal[] sb = sumAndBase.get(m.getPersonId());
            BigDecimal sum = sb == null ? amt : sb[0];
            BigDecimal base = sb == null ? amt : sb[1];
            BigDecimal capLine = base.multiply(capMultiplier);
            boolean capApplied = amt.signum() > 0 && sum.compareTo(capLine) > 0;
            // 真削减：Σ 超封顶线时按 Σ:capLine 比例削减本行，未超则系数为 1（金额逐行不变）
            BigDecimal capRatio = (capApplied && sum.signum() > 0)
                ? capLine.divide(sum, 8, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

            BigDecimal nfFactor = resolveNegativeFeedbackFactor(
                m.getPersonId(), m.getProjectId(), month, nfCache);
            boolean lowScore = isLowScoreForMonth(m.getPersonId(), month, lowScoreByPerson);

            BigDecimal finalAmount = money(amt.multiply(capRatio).multiply(nfFactor));
            if (lowScore) {
                finalAmount = BigDecimal.ZERO;
            }
            AllowanceLedger ledger = buildLedger(m.getPersonId(), month, finalAmount,
                m.getLockedLevel(), amt, capApplied);
            if (lowScore) {
                ledger.setStopReason("STOP_SCORE_BELOW_60");
                ledger.setStopStartDate(now());
            }

            if (recordOrSkip(ledger, m.getProjectId()) != null) {
                created++;
                if (finalAmount.compareTo(money(amt)) != 0) {
                    auditAdjustment(ledger, "ALLOWANCE_FINAL_AMOUNT_ADJUSTED",
                        adjustmentReason(capApplied, capLine, nfFactor, lowScore));
                }
                // 无产出「仅提醒」腿（2026-10-07 owner 翻案）：仅对本轮新生成的台账发提醒，
                // 已成账跳过 ⇒ 提醒与台账同步幂等（发布 dedupKey 亦含台账 id，重复扫描不重发）。
                // 低分已停发则不再扫四类并集（省扫描且低分优先）；活动数据源全缺失 ⇒ 整腿跳过，
                // 防 "查不到" 被误判 "从未活动" 而误提醒。
                if (!lowScore && isAdditionalMember(m) && hasAnyActivitySource()) {
                    String noOutputRemind = determineNoOutput60DaysStop(
                        resolveLastActivityDate(m.getPersonId(), m.getProjectId()), now(), true);
                    if (noOutputRemind != null) {
                        publishNoOutputReminder(ledger);
                    }
                }
            }
        }
        return created;
    }

    /** A4-56/61：金额偏离原因（进审计 reason 字段，供事后追责对账）。 */
    private static String adjustmentReason(boolean capApplied, BigDecimal capLine,
                                           BigDecimal nfFactor, boolean lowScore) {
        StringBuilder sb = new StringBuilder();
        if (capApplied) {
            sb.append("CAP_TRIM:按封顶线 ").append(capLine.toPlainString()).append(" 比例削减;");
        }
        if (nfFactor.compareTo(BigDecimal.ONE) != 0) {
            sb.append("NEGATIVE_FEEDBACK:折减系数 ").append(nfFactor.toPlainString()).append(";");
        }
        if (lowScore) {
            sb.append("LOW_SCORE_STOP:绩效综合分<60 停发;");
        }
        return sb.length() == 0 ? "NO_ADJUST" : sb.toString();
    }

    /** 当月 FINALIZED KPI 综合分 < 60 判定（mapper 缺失/无记录/非 FINALIZED 均视为不停发）。 */
    private boolean isLowScoreForMonth(Long personId, String month, Map<Long, Boolean> cache) {
        if (kpiRecordMapper == null) {
            return false;
        }
        return cache.computeIfAbsent(personId, pid -> {
            List<KpiRecord> records = kpiRecordMapper.selectList(
                new LambdaQueryWrapper<KpiRecord>()
                    .eq(KpiRecord::getPersonId, pid)
                    .eq(KpiRecord::getPeriod, month)
                    .eq(KpiRecord::getStatus, "FINALIZED"));
            if (records == null || records.isEmpty()) {
                return false;
            }
            return determineLowScoreStop(records.get(0).getComprehensiveScore()) != null;
        });
    }
}
