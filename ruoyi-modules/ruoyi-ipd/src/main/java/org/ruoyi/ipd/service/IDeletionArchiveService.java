package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.domain.DeletionRequest;

/**
 * IDeletionArchiveService 接口（paiban-05 接口化，实现见 {@link DeletionArchiveService}）。
 */
public interface IDeletionArchiveService {

    /** * 归档区列表：DELETED 状态 + remark 非 PURGED 前缀（仅超管）。 */
    /** * */
    /** * <p>DEF-8（2026-09-05）：原实现只用 {@code notLike(remark, PURGED_MARK)}，而 SQL 是**三值逻辑**—— */
    /** * {@code NULL NOT LIKE '%x%'} 求值为 NULL（非 TRUE），WHERE 不成立。remark 默认为 NULL */
    /** * （submit/终审均不写 remark），于是归档区**恒空**：真库实证 2 行 DELETED 且 remark IS NULL， */
    /** * {@code SUM(remark NOT LIKE '%PURGED%')}=NULL（0 行通过）而正确语义应为 2 行 → */
    /** * AC-DEL-02「数据移入归档区」的可见性完全失效（purge 入口也因此永远拿不到候选）。 */
    /** * 修复 = 显式放行 NULL：{@code (remark IS NULL OR remark NOT LIKE '%PURGED_BY_SUPER_ADMIN:%')}。 */
    /** * */
    /** * @return 未清除的已删除申请 */
    List<DeletionRequest> listArchive();

    /**
     * 带二次确认的清除。confirmTail 必须等于申请编号，clearedReason 必填。
     */
    DeletionRequest purge(Long requestId, String confirmTail, String clearedReason);

}
