package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

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
 *   <li>AC-INC-05：绩效综合 < 60 当月停发（BR-INC-11）</li>
 *   <li>AC-INC-07：附加项目连续 60 天无产出（动作/交付物/记录/Gate 四类并集）⇒ 触发待确认停发单</li>
 *   <li>AC-INC-08：主项目（非附加）无产出 ⇒ 不触发停发</li>
 *   <li>幂等：(personId, projectId, month) 唯一，重复执行不重复台账（P3-3.3）</li>
 *   <li>P3-3.3：月中移交当月按月初人员、新人次月生效；退出次月停发</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AllowanceService {

    private final AllowanceLedgerMapper allowanceLedgerMapper;
    private final ProjectMemberMapper projectMemberMapper;

    /** P0-7：写路径审计（nullable setter 注入兼容旧测试 2 参构造；生产由 Spring 装配）。 */
    private AuditLogService auditLogService;

    @Autowired(required = false)
    public void setAuditLogService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 可注入时钟（裸时钟守卫禁一：审计时间戳走业务时钟；测试固定时刻消除摇摆）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

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
    private static final BigDecimal DEFAULT_CAP_MULTIPLIER = new BigDecimal("2");

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
     * AC-INC-03/04：月度多项目叠加 + 2 倍封顶
     * 实发 = MIN(Σ(项目津贴), 2 × 基准额)
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
        BigDecimal cap = base.multiply(DEFAULT_CAP_MULTIPLIER);
        return sum.min(cap);
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
     * P3-3.2 AC-INC-07/08：附加项目连续 60 天无产出判定。
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
            return "STOP_NO_OUTPUT_60_DAYS";
        }
        long diffMs = asOfDate.getTime() - lastActivityDate.getTime();
        long days = diffMs / (24L * 60 * 60 * 1000);
        if (days >= NO_OUTPUT_DAYS_THRESHOLD) {
            return "STOP_NO_OUTPUT_60_DAYS";
        }
        return null;
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
}
