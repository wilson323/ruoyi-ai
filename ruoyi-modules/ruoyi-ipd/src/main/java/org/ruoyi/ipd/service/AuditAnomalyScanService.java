package org.ruoyi.ipd.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.vo.AuditOperatorWindowStats;
import org.springframework.stereotype.Service;

/**
 * AI-P3 #7 审计异常检测（卡面硬约束：只报不拦，零写路径）。
 *
 * <p>按滚动窗口只读扫 audit_logs，跑两条最小启发式：
 * <ul>
 *   <li>规则 A「异常时间批量修改」：同一操作人窗口内写型动作（CREATE/UPDATE/DELETE/APPROVE/REJECT）
 *       ≥ {@link #BULK_WRITE_THRESHOLD} 条；</li>
 *   <li>规则 B「非常规角色敏感操作」：非 SUPER_ADMIN（含角色缺失）执行
 *       PERMANENT_DELETE / REBUILD_CHAIN / TRANSFER_SUPER_ADMIN ≥ 1 条即报。</li>
 * </ul>
 * 命中后向全体在任超管各发一条 FYI 站内通知（复用 {@link NotificationService#publishDaily}
 * outbox 幂等，同日同操作人同规则不重发），不阻断任何业务写路径。
 *
 * <p>阈值/动作目录用常量而非配置：卡面要求「别过度配置化」，先跑最小可用，
 * 误报率经真库观察后再决定是否升级 ipd.* 可配。
 *
 * <p>租户口径：跨租户全量扫描（检测侧保守多报，通知按 receiver 隔离不影响可见性）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditAnomalyScanService {

    /** 扫描窗口（小时）：每日 10:05 job 回看 24h，窗口=job 间隔，无缝且不重复计数。 */
    static final int WINDOW_HOURS = 24;
    /** 规则 A 阈值：窗口内写型动作数。50 = 经验起步值（正常批量操作日频远低于此，真库观察后调）。 */
    static final long BULK_WRITE_THRESHOLD = 50L;
    /** 规则 B 阈值：非超管敏感动作数，1 即报（权限异常零容忍口径，只报不拦所以可激进）。 */
    static final long SENSITIVE_OFFROLE_THRESHOLD = 1L;

    /** 通知去重源类型（dedup_key = sourceType:eventType:sourceId:receiverId:day）。 */
    static final String SRC_AUDIT_ANOMALY = "audit_anomaly";
    static final String EVENT_BULK_WRITE = NotificationService.Types.AUDIT_ANOMALY_BULK_WRITE;
    static final String EVENT_SENSITIVE_OFFROLE = NotificationService.Types.AUDIT_ANOMALY_SENSITIVE_OFFROLE;

    private final AuditLogMapper auditLogMapper;
    private final PersonMapper personMapper;
    private final NotificationService notificationService;

    /** 一条命中：规则码 + 涉事操作人聚合画像（operatorId 为 null 时以 0 作通知 sourceId）。 */
    record Anomaly(String eventType, Long operatorId, String operatorName, String operatorRole, long count) {
        Long sourceId() { return operatorId == null ? 0L : operatorId; }
        String who() {
            String name = operatorName == null || operatorName.isBlank() ? "id=" + sourceId() : operatorName;
            String role = operatorRole == null || operatorRole.isBlank() ? "（角色缺失）" : operatorRole;
            return name + "/" + role;
        }
    }

    /**
     * 单轮扫描：聚合 → 判定 → 通知超管。返回命中数（供调度日志与验收断言）。
     * 通知失败由 publishDaily 的 dedup/outbox 语义兜底，此处不吞异常——job 层记 WARN 即可。
     */
    public int scanAndNotify(Date now) {
        Date windowStart = new Date(now.getTime() - WINDOW_HOURS * 3600_000L);
        List<Anomaly> anomalies = detect(auditLogMapper.selectOperatorWindowStats(windowStart, now));
        if (anomalies.isEmpty()) {
            return 0;
        }
        List<Person> admins = activeSuperAdmins();
        if (admins.isEmpty()) {
            // 与 HandoverOverdueScanner 同口径：无在任超管只 WARN 不抛，检测计数仍返回供日志观察
            log.warn("AI-P3#7 审计异常命中 {} 条但无在任超管可通知（persons 表 SUPER_ADMIN 全不 ACTIVE？）",
                anomalies.size());
            return anomalies.size();
        }
        for (Anomaly a : anomalies) {
            String title = EVENT_BULK_WRITE.equals(a.eventType())
                ? "审计异常：短时间大量写入 " + a.count() + " 条（" + a.who() + "）"
                : "审计异常：非常规角色敏感操作 " + a.count() + " 次（" + a.who() + "）";
            String content = "AI-P3#7 定时扫描 audit_logs 近 " + WINDOW_HOURS + "h 命中规则 " + a.eventType()
                + "：操作人 " + a.who() + "，计数 " + a.count() + "。只报不拦，请人工复核审计流水。";
            for (Person admin : admins) {
                notificationService.publishDaily(admin.getId(), a.eventType(), NotificationService.KIND_FYI,
                    SRC_AUDIT_ANOMALY, a.sourceId(), title, content, "/ipd/audit-logs", now);
            }
        }
        log.info("AI-P3#7 审计异常扫描：命中 {} 条，已通知超管 {} 人", anomalies.size(), admins.size());
        return anomalies.size();
    }

    /** 纯函数判定（包内可见供单测直喂聚合样本）：规则 A/B 各自独立成条，同人双规则双报。 */
    static List<Anomaly> detect(List<AuditOperatorWindowStats> rows) {
        List<Anomaly> hits = new ArrayList<>();
        for (AuditOperatorWindowStats r : rows) {
            if (r.getWriteCount() >= BULK_WRITE_THRESHOLD) {
                hits.add(new Anomaly(EVENT_BULK_WRITE, r.getOperatorId(),
                    r.getOperatorName(), r.getOperatorRole(), r.getWriteCount()));
            }
            if (r.getSensitiveOffRoleCount() >= SENSITIVE_OFFROLE_THRESHOLD) {
                hits.add(new Anomaly(EVENT_SENSITIVE_OFFROLE, r.getOperatorId(),
                    r.getOperatorName(), r.getOperatorRole(), r.getSensitiveOffRoleCount()));
            }
        }
        return hits;
    }

    /** 在任超管（与 GuestDemandService.activeSuperAdmins 同口径：ACTIVE 双状态 + 未软删）。 */
    private List<Person> activeSuperAdmins() {
        return personMapper.selectList(new LambdaQueryWrapper<Person>()
            .eq(Person::getPersonType, "SUPER_ADMIN")
            .eq(Person::getEmploymentStatus, "ACTIVE")
            .eq(Person::getAccountStatus, "ACTIVE")
            .eq(Person::getDelFlag, "0"));
    }
}
