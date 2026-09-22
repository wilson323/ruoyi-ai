package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.ipd.dto.AiCopilotResp;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;

/**
 * IAiCopilotService 接口（paiban-05 接口化，实现见 {@link AiCopilotService}）。
 */
public interface IAiCopilotService {

    /** 测试口：注入固定时钟。 */
    AiCopilotResp chat(IpdActor actor, AiCopilotReq req);

}
