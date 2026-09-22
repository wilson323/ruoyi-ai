package org.ruoyi.ipd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;

/**
 * IAiGenerationService 接口（paiban-05 接口化，实现见 {@link AiGenerationService}）。
 */
public interface IAiGenerationService {

    /** * 生成并登记 v1。任何失败路径均写 AI_GENERATE_FAILED 审计（排查/对账）， */
    /** * 成功路径写 AI_GENERATE 审计（AC-AI-09：token 消耗与耗时）。 */
    /** * */
    /** * @return 版本链首环（versionNo=1，status=GENERATED 待审核，含 model/token 用量） */
    AiDocument generate(IpdActor actor, AiGenerateReq req);

}
