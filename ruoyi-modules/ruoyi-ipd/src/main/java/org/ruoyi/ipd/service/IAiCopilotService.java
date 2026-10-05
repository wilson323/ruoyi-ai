package org.ruoyi.ipd.service;

import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.ipd.dto.AiCopilotResp;
import org.ruoyi.ipd.security.IpdActor;

/**
 * IAiCopilotService 接口（paiban-05 接口化，实现见 {@link AiCopilotService}）。
 */
public interface IAiCopilotService {

    /** 测试口：注入固定时钟。 */
    AiCopilotResp chat(IpdActor actor, AiCopilotReq req);

}
