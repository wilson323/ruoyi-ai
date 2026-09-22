package org.ruoyi.ipd.service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.SoftDeletable;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * IDeleteAuditService 接口（paiban-05 接口化，实现见 {@link DeleteAuditService}）。
 */
public interface IDeleteAuditService {

    /** * 超管终审通过后，原子执行软删除与审计（P0-6.2）。 */
    /** * <p>调用前请求必须处于 ADMIN_REVIEW；调用后状态变为 DELETED 并填 executedAt。 */
    /** * 任意环节抛异常，状态变更、目标行 UPDATE、审计全部回滚。 */
    /** * */
    /** * @param requestId DeletionRequest.id */
    /** * @param adminId   操作超管 ID（必填） */
    /** * @return 终态 DeletionRequest */
    /** * @throws ServiceException 状态机不匹配 / entity_type 不支持 / 软删除执行失败 */
    DeletionRequest approveAndExecute(Long requestId, Long adminId);

    /** 当前已注册的 entity_type（用于校验/调试） */
    Set<String> supportedEntityTypes();

}
