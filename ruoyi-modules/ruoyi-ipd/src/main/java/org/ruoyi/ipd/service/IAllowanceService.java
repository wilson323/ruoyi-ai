package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
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

/**
 * IAllowanceService 接口（paiban-05 接口化，实现见 {@link AllowanceService}）。
 */
public interface IAllowanceService {

    /** P0-7：写路径审计（nullable setter 注入兼容旧测试 2 参构造；生产由 Spring 装配）。 */
    void setAuditLogService(IAuditLogService auditLogService);

    /** 可注入时钟（裸时钟守卫禁一：审计时间戳走业务时钟；测试固定时刻消除摇摆）。 */
    void setClock(java.time.Clock clock);

    /** * 计算单项目津贴（绑定 ProjectMember 时的 lockedAmount 已锁定） */
    /** * 不会乘绩效系数（AC-INC-06） */
    BigDecimal calculateProjectAllowance(ProjectMember member);

    /** * AC-INC-03/04：月度多项目叠加 + 2 倍封顶 */
    /** * 实发 = MIN(Σ(项目津贴), 2 × 基准额) */
    /** * 基准额取当月最高 lockedAmount（即最高级别项目的锁定额） */
    BigDecimal calculateMonthlyAllowance(Long personId, String month);

    /** * AC-INC-04/03：构造台账（不写库） */
    AllowanceLedger buildLedger(
        Long personId,
        String month,
        BigDecimal finalAmount,
        String lockedLevel,
        BigDecimal baseAmount,
        boolean capApplied
    );

    /** * 幂等：personId + projectId + month 唯一 */
    /** * 已存在则跳过（不重复台账） */
    AllowanceLedger recordOrSkip(AllowanceLedger ledger, Long projectId);

    /** * AC-INC-05/07/08：判定当月停发原因（兼容旧契约） */
    String determineStopReason(
        BigDecimal comprehensiveScore,
        boolean noOutput60Days,
        boolean isMainProject
    );

    /** * P3-3.2 AC-INC-05：绩效综合得分 < 60 当月停发。 */
    String determineLowScoreStop(BigDecimal comprehensiveScore);

    /** * P3-3.2 AC-INC-07/08：附加项目连续 60 天无产出判定。 */
    String determineNoOutput60DaysStop(
        Date lastActivityDate,
        Date asOfDate,
        boolean isAdditionalProject
    );

    /** * P3-3.2：判定停发原因（综合绩效分 + 无产出 + 主/附加）三层合一。 */
    String determineStopReasonP332(
        BigDecimal comprehensiveScore,
        Date lastActivityDate,
        Date asOfDate,
        boolean isAdditionalProject
    );

    /** * P3-3.3 AC-INC：津贴台账幂等检查——(personId, projectId, month) 唯一 */
    boolean existsByKey(Long personId, Long projectId, String month);

    /** * P3-3.3 AC-INC：月中移交当月按月初人员判定。 */
    boolean isMemberEffectiveInMonth(Date joinDate, String month);

    /** * P3-3.3 AC-INC：退出当月停发。 */
    /** * 2026-09-09 owner 拍板「当月退出不发」，原「退出次月停发」宽松口径作废（与主流程 */
    /** * calculateMonthlyAllowance 的 isNull(exitDate) 严格口径对齐，双口径并存问题消除）。 */
    /** * <pre> */
    /** *   exitDate == null                          ⇒ active（未退出） */
    /** *   exitDate >= 查询月的次月月初                ⇒ active（退出发生在查询月之后，该月整月在岗） */
    /** *   exitDate 在查询月内或更早（含月末当天退）    ⇒ not active（当月退出当月即停） */
    /** * </pre> */
    boolean isMemberActiveInMonth(Date exitDate, String month);

    /** * P3-3.3 AC-INC：月中移交 + 退出月份归属综合判定（验收契约，P333AcceptanceTest 盯守）。 */
    boolean isMemberActiveForMonth(ProjectMember member, String month);

    /** * P3-3.3 AC-INC：人员-项目-月复合主键冲突检测（并发重算护栏） */
    AllowanceLedger idempotentInsert(AllowanceLedger ledger);

    /** * P3-3.3 AC-INC：人员-项目-月复合主键冲突检测（并发重算护栏） */
    List<AllowanceLedger> listByPerson(Long personId, String month);

}
