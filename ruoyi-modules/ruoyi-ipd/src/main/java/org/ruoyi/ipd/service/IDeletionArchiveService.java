package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** * 二次确认清除：IPD 超管会话 → 原子更新 remark → 写 PURGE 审计。 */
    /** * */
    /** * @param requestId 删除申请 ID */
    /** * @return 更新后的申请 */
    DeletionRequest purge(Long requestId);

}
