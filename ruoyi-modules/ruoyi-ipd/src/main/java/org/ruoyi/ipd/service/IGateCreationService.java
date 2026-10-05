package org.ruoyi.ipd.service;

import org.ruoyi.ipd.domain.Gate;

/**
 * IGateCreationService 接口（paiban-05 接口化，实现见 {@link GateCreationService}）。
 */
public interface IGateCreationService {

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    Gate autoCreateGate(Long projectId, String gateCode, Long operatorId);

}
