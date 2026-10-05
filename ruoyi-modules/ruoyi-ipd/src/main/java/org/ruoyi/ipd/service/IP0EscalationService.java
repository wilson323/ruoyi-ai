package org.ruoyi.ipd.service;

import java.util.Date;
import java.util.List;
import org.ruoyi.ipd.domain.P0EscalationChain;

/**
 * IP0EscalationService 接口（paiban-05 接口化，实现见 {@link P0EscalationService}）。
 */
public interface IP0EscalationService {

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** * 记录一次 P0 超期未升级（幂等 upsert）。 */
    /** * */
    /** * <p>逻辑： */
    /** * <ul> */
    /** *   <li>若 (projectId, p0EventId, status=PENDING) 已有记录：count +1，last_escalation_at 更新</li> */
    /** *   <li>若不存在：新增一行 count=1, status=PENDING</li> */
    /** *   <li>已 ESCALATED/RESOLVED 的不重复触发（避免重复升级）</li> */
    /** * </ul> */
    P0EscalationChain recordP0Unresolved(Long projectId, Long p0EventId, Date nextThresholdAt);

    /** * 扫描待升级链（count >= 2 且 PENDING），触发升级通知给双方组长。 */
    /** * */
    /** * @return 触发的升级数（影响行数） */
    int checkEscalation();

    /** * 按项目列出升级链（端点用）。 */
    List<P0EscalationChain> listByProject(Long projectId);

    /** * 标记某条升级为 RESOLVED（手动关闭；处置完成后调用）。 */
    boolean resolve(Long id, String remark);

    /** * 测试口：根据 (projectId, p0EventId) 取最新一条。 */
    P0EscalationChain findLatest(Long projectId, Long p0EventId);

}
