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
    public static final String RECEIPT_LEDGER = "receipt_ledger";
    public static final String PERSON_SYNC_JOBS = "person_sync_jobs";
    public static final String HR_SYNC = "hr_sync";
    public static final String COEFFICIENT_CHANGE_REQUESTS = "coefficient_change_requests";
    public static final String LAUNCH_DATE_CHANGE_REQUESTS = "launch_date_change_requests";
    public static final String PROJECTS = "projects";
    public static final String AUDIT_LOGS = "audit_logs";
}
