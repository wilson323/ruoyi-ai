package org.ruoyi.ipd.audit;

/**
 * IPD 审计 entityType 统一命名（审计 AOP 改造设计-20260909 §3.4 附带治理项）。
 * <p>背景：存量调用点 entityType 命名不统一——{@code persons}（复数）/ {@code receipt_ledger}（单数）/
 * {@code person_sync_jobs}（复数）/ HandoverService 内 {@code handover} 与 {@code person} 混用。
 * 本枚举把<b>现值</b>固化为常量供新调用点引用；<b>不改写存量字符串</b>——entityType 进哈希
 * （canonicalOf），改存量字符串只影响新行不影响历史，但会破坏前端/验收对现值的断言，
 * 统一改名须单独裁决（现值即契约）。
 */
public final class IpdEntityType {

    private IpdEntityType() { }

    /** 现值即契约：与存量调用点字面量一一对应，新增实体时在此登记。 */
    public static final String PERSONS = "persons";
    /**
     * <b>回款台账常量——保留但已无代码引用，值绝对不可改。</b>
     *
     * <p>回款台账（receipt_ledgers）已随「算钱」层下线，但本常量<b>刻意保留</b>：
     * {@code entityType} 进审计哈希链（{@code canonicalOf}），库中已有 49 条
     * {@code entity_type='receipt_ledger'} 的历史审计行。删除或改写该常量会让这 49 条
     * 历史行校验失败、审计链断裂，且属于不可逆的合规风险。
     * 如 owner 日后确需清理，必须先单独裁决审计链迁移方案，不得在本层顺手删。
     */
    public static final String RECEIPT_LEDGER = "receipt_ledger";
    public static final String PERSON_SYNC_JOBS = "person_sync_jobs";
    public static final String HR_SYNC = "hr_sync";
    /**
     * 系数变更申请常量——同 {@link #RECEIPT_LEDGER}：功能已下线，常量保留以维持
     * 历史审计行（coefficient_change_requests）的哈希链可校验性。
     */
    public static final String COEFFICIENT_CHANGE_REQUESTS = "coefficient_change_requests";
    public static final String LAUNCH_DATE_CHANGE_REQUESTS = "launch_date_change_requests";
    public static final String PROJECTS = "projects";
    public static final String AUDIT_LOGS = "audit_logs";
    /** R221（2026-09-26）：AI 代理执行任务队列实体类型（ai_agent_tasks 表）。 */
    public static final String AI_AGENT_TASK = "ai_agent_task";
}
