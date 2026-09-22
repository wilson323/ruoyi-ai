package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.dto.AuditEntryVO;
import org.ruoyi.ipd.dto.DataDeletionRequestDTO;
import org.ruoyi.ipd.dto.DataDeletionRequestVO;
import org.ruoyi.ipd.dto.DataRetentionRuleVO;
import org.ruoyi.ipd.dto.PermissionSeparationVO;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IComplianceService 接口（paiban-05 接口化，实现见 {@link ComplianceService}）。
 */
public interface IComplianceService {

    /** * AC-COMP-01：返回数据保留规则清单（按 sys_config + 默认兜底）。 */
    /** * <p>预置规则覆盖：projects / requirements / persons / audit_logs / deletion_requests / ai_documents。 */
    /** * */
    /** * @return 规则列表 */
    List<DataRetentionRuleVO> getRetentionRules();

    /** * AC-COMP-02 / AC-COMP-03：创建数据删除请求。 */
    /** * <p>步骤：1) 校验 resource 存在 → 2) 计算 30 天 deadline → 3) 写审计 → 4) 返回 VO。 */
    /** * <p>本方法仅创建请求记录；真实删除仍走 DeletionRequestController（避免越权直删）。 */
    /** * */
    /** * @param dto   入参 */
    /** * @param actor 当前操作人 */
    /** * @return VO（含 deadlineAt） */
    DataDeletionRequestVO createDeletionRequest(DataDeletionRequestDTO dto, IpdActor actor);

    /** * AC-COMP-04：按 resourceType+resourceId 查询审计链。 */
    /** * <p>「同组/本人/全局」范围规则由 service 透明按 actor 角色解析（与 AuditLogController 一致）。 */
    /** * */
    /** * @param resourceType 资源类型 */
    /** * @param resourceId   资源 ID */
    /** * @param actor        当前操作人 */
    /** * @param pageNo       页码 */
    /** * @param pageSize     每页条数（最大 200） */
    /** * @return 审计条目页 */
    IPage<AuditEntryVO> getAuditTrail(
        String resourceType,
        Long resourceId,
        IpdActor actor,
        int pageNo,
        int pageSize
    );

    /** * AC-COMP-05：检测用户 R/W 权限分离。 */
    /** * <p>语义：以 person_type 为唯一来源，超管/组长 = R+W 同源 → conflict=true。 */
    /** * <p>MARKET_PM / RD_PM = 仅 R，conflict=false（合规要求下「读合规不算权」）。 */
    /** * */
    /** * @param userId 用户 ID */
    /** * @return 判定 VO */
    PermissionSeparationVO checkPermissionSeparation(Long userId);

}
