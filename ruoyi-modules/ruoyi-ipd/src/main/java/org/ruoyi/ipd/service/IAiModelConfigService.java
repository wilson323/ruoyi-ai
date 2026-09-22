package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import org.ruoyi.common.encrypt.utils.EncryptUtils;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.dto.AiModelSaveReq;
import org.ruoyi.ipd.dto.AiModelView;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.service.ai.AiTestResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IAiModelConfigService 接口（paiban-05 接口化，实现见 {@link AiModelConfigService}）。
 */
public interface IAiModelConfigService {

    /** 列表（脱敏）。 */
    List<AiModelView> list();

    /** 详情（脱敏）。 */
    AiModelView get(Long id);

    /** 当前生效配置（内部接线用，含解密密钥；仅供 service 层 AI 调用方，不进 controller）。 */
    AiModelConfig currentEnabled();

    /** 创建：apiKey 明文进、密文出（AC-AI-01 全字段可配）。 */
    AiModelView create(AiModelSaveReq req, String operator);

    /** 更新：apiKey=null 表示不改密钥（不触碰密文列）。 */
    AiModelView update(Long id, AiModelSaveReq req, String operator);

    /** 启用（全局唯一生效）：先清其他行再置本行。 */
    AiModelView enable(Long id, String operator);

    /** * 连接测试（BR-AI-PROV-01/02/03/05）： */
    /** * <ol> */
    /** *   <li>SSRF 前置（保留 SEC-REV-04 黑名单语义）</li> */
    /** *   <li>按 provider 走 {@link ProviderRegistry} 派发到对应 Tester</li> */
    /** *   <li>Tester 返回结构化 AiTestResult；service 把 errorMessage 二次 mask 后拼到 maskedKey 字段， */
    /** *       保持前端契约不变（maskedKey 必含 "ok" 或 "fail" 关键词）</li> */
    /** *   <li>失败消息白名单化——绝不含 apiKey/密文/请求头</li> */
    /** * </ol> */
    /** * 注：解密 apiKey 仅内存内消费；既不回显，也不入审计 afterData。 */
    AiModelView testConnect(Long id, String operator);

    /** * P4-2.1：按 provider 走 ProviderRegistry 派发 Tester（带 SSRF 前置 + 二次 mask）。 */
    /** * <p> */
    /** * 返回结构化结果——上层若需自己组装 AiModelView 可用；现有 controller 仍调 {@link #testConnect} 走默认 message 路径。 */
    /** * 测试可独立验证派发与 mask 行为。 */
    AiTestResult testConnectWithProvider(Long id);

    /** 内部：解密 api_key 供 AI 调用方使用（严禁出现在任何响应/日志）。 */
    String decryptApiKey(AiModelConfig config);

}
