package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.domain.CorrectionLog;
import org.ruoyi.ipd.security.IpdActor;

/**
 * ICorrectionLogService 接口（paiban-05 接口化，实现见 {@link CorrectionLogServiceImpl}）。
 */
public interface ICorrectionLogService {

    public void record(IpdActor actor, String entityType, Long entityId, String fieldName, String oldValue, String newValue, String reason);

    public List<CorrectionLog> listByEntity(String entityType, Long entityId, IpdActor actor);

}
