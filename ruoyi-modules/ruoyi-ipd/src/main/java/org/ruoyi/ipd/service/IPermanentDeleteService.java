package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.domain.PermanentDeleteAudit;
import org.ruoyi.ipd.security.IpdActor;

/**
 * IPermanentDeleteService 接口（paiban-05 接口化，实现见 {@link PermanentDeleteService}）。
 */
public interface IPermanentDeleteService {

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** * 执行永久清除。 */
    /** * */
    /** * @param actor        当前超管 */
    /** * @param entityType   person|project|kpi_record */
    /** * @param entityId     实体主键 */
    /** * @param confirmCode  必须等于 REQUIRED_CONFIRM_CODE */
    /** * @return audit row id */
    Long execute(
        IpdActor actor,
        String entityType,
        Long entityId,
        String confirmCode
    );

    /** 列出审计（仅超管；前端对账视图用） */
    List<PermanentDeleteAudit> listByEntityType(String entityType, int limit);

}
