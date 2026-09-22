package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.dto.AuditChainVerifyResult;
import org.ruoyi.ipd.security.IpdActor;

/**
 * IAuditLogService 接口（paiban-05 接口化，实现见 {@link AuditLogServiceImpl}）。
 */
public interface IAuditLogService {

    public AuditLog append(AuditLog draft);

    public void append(IpdActor actor, String action, String entityType, Long entityId, String reason);

    public List<Long> verifyChain();

    public List<Long> verifyChainStrict();

    public AuditChainVerifyResult verifyChainDetailed();

    public long rebuildChain();

    public IPage<AuditLog> listByOperatorIds(List<Long> operatorIds, int pageNo, int pageSize, Long beforeSeq);

    public IPage<AuditLog> listByOperatorIds(List<Long> operatorIds, int pageNo, int pageSize);

    public long countByOperatorIds(List<Long> operatorIds);

}
