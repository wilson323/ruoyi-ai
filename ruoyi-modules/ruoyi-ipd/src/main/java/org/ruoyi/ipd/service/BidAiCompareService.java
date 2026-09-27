package org.ruoyi.ipd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.BidResponse;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.mapper.BidResponseMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI-P2-2 #3：遴选对比汇总（纯只读分析；招投标 AI 三件套本次仅落本件）。
 *
 * <ul>
 *   <li>同一招标下多份应标喂 {@link AiGateway} 单轮（复用现有 AI 通道，不新建），产出四维
 *       对照表（工期/资源/风险承诺/方案匹配度）+ 差异高亮；结果仅展示，前端置于 bid-select 页；</li>
 *   <li>红线（ROOT-R6-D1-WHITE-B）：遴选决策仍是 confirmToken 两阶段人工流（/pre-select-token +
 *       /select），本类对 bid_responses/bid_invitations（含 confirmToken 列）零写入、零通知；</li>
 *   <li>审计：AI_BID_COMPARE 单行三件套 aiAssisted/aiModel/aiRole=summarize（AI-P1-3 白名单
 *       既有值）；BR-AI-04 prompt 与模型输出原文不落库。</li>
 * </ul>
 */
@Service
public class BidAiCompareService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对比份数下/上限：<2 无对比意义（400），>5 防 prompt 爆炸（卡面「多份」保守封顶）。 */
    static final int MIN_RESPONSES = 2;
    static final int MAX_RESPONSES = 5;
    static final int MAX_TOKENS = 1200;
    static final int COMPARE_TIMEOUT_MS = 30_000;
    /** 固定对比维度（与卡面四项一致）；模型输出缺维即拒（不静默补表）。 */
    static final List<String> DIMENSIONS = List.of("工期", "资源", "风险承诺", "方案匹配度");

    private final BidInvitationMapper bidInvitationMapper;
    private final BidResponseMapper bidResponseMapper;
    private final IAiModelConfigService modelConfigService;
    private final AiGateway aiGateway;
    private final IAuditLogService auditLogService;

    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public BidAiCompareService(BidInvitationMapper bidInvitationMapper,
                               BidResponseMapper bidResponseMapper,
                               IAiModelConfigService modelConfigService,
                               AiGateway aiGateway,
                               IAuditLogService auditLogService) {
        this.bidInvitationMapper = bidInvitationMapper;
        this.bidResponseMapper = bidResponseMapper;
        this.modelConfigService = modelConfigService;
        this.aiGateway = aiGateway;
        this.auditLogService = auditLogService;
    }

    /** 测试口：注入固定时钟（同 AiCopilotService.withClock 惯例，裸时钟守卫配套）。 */
    BidAiCompareService withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /** 单维度行：cells key = 应标 id（字符串化保 JSON 序列化稳定）。 */
    public record DimensionRow(String dimension, Map<String, String> cells, String difference) {}

    /** 展示契约：对照表 + 差异高亮 + 模型/用量元信息（无任何决策字段——决策恒人工）。 */
    public record CompareView(Long invitationId, String invitationTitle, List<String> responseIds,
                              List<DimensionRow> dimensions, List<String> differences, String model,
                              int promptTokens, int completionTokens, long latencyMs) {}

    /**
     * 只读对比入口：参数闸→招标/应标加载（跨单探测即拒）→单轮 AI→解析对照表→审计→返回。
     * 全程零业务表写入；决策语义一概不出（prompt 明令禁结论/排名）。
     */
    public CompareView compare(IpdActor actor, Long invitationId, List<Long> responseIds) {
        long start = clock.millis();
        List<Long> ids = responseIds == null ? new ArrayList<>()
            : new ArrayList<>(new LinkedHashSet<>(responseIds));
        ids.removeIf(Objects::isNull);
        if (ids.size() < MIN_RESPONSES) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "遴选对比至少需要 2 份不同的应标 id");
        }
        if (ids.size() > MAX_RESPONSES) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "遴选对比单次最多 " + MAX_RESPONSES + " 份应标");
        }
        BidInvitation inv = bidInvitationMapper.selectById(invitationId);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "招标单不存在: " + invitationId);
        }
        Map<Long, BidResponse> byId = new LinkedHashMap<>();
        for (BidResponse row : bidResponseMapper.selectBatchIds(ids)) {
            byId.put(row.getId(), row);
        }
        for (Long rid : ids) {
            BidResponse row = byId.get(rid);
            if (row == null || !invitationId.equals(row.getInvitationId())) {
                throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND,
                    "应标不存在或不属于该招标单: " + rid);
            }
        }
        // 未配置生效模型：currentEnabled 抛既有业务异常直通（本端点宁可失败，不静默造结果）
        AiModelConfig config = modelConfigService.currentEnabled();
        AiChatResult result = aiGateway.chat(
            new AiTestConfig(config.getProvider(), config.getEndpointUrl(),
                modelConfigService.decryptApiKey(config), config.getModelName(), COMPARE_TIMEOUT_MS),
            composePrompt(inv, ids, byId), MAX_TOKENS, new BigDecimal("0.20"));
        long latency = Math.max(0, clock.millis() - start);
        if (!result.success()) {
            audit(actor, invitationId, ids, config.getModelName(), latency, 0, 0,
                "FAIL:" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()));
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "AI 对比暂不可用: " + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()));
        }
        List<DimensionRow> dims;
        List<String> diffs;
        try {
            JsonNode root = JSON.readTree(extractJsonObject(result.content()));
            dims = parseDimensions(root);
            diffs = parseDifferences(root);
        } catch (IpdBusinessException ex) {
            audit(actor, invitationId, ids, config.getModelName(), latency, 0, 0, "FAIL:PARSE");
            throw ex;
        } catch (Exception ex) {
            audit(actor, invitationId, ids, config.getModelName(), latency, 0, 0, "FAIL:PARSE");
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "AI 对比输出解析失败，请重试");
        }
        audit(actor, invitationId, ids, config.getModelName(), latency,
            result.promptTokens(), result.completionTokens(), "ok");
        List<String> idText = new ArrayList<>();
        for (Long rid : ids) {
            idText.add(String.valueOf(rid));
        }
        return new CompareView(invitationId, inv.getTitle(), List.copyOf(idText), dims, diffs,
            config.getModelName(), result.promptTokens(), result.completionTokens(), latency);
    }

    /** Prompt：招标 + 各 responseNote 截断喂入；原文只进模型，不进日志/审计（BR-AI-04）。 */
    static String composePrompt(BidInvitation inv, List<Long> ids, Map<Long, BidResponse> byId) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("你是招投标遴选对比助手。请就同一招标下的以下应标做对照分析。\n");
        sb.append("招标标题: ").append(nullToDash(inv.getTitle())).append('\n');
        sb.append("招标内容: ").append(truncate(inv.getContent(), 1500)).append('\n');
        for (Long rid : ids) {
            BidResponse r = byId.get(rid);
            sb.append("应标[id=").append(rid).append(", 应标人=").append(r.getRdPmId())
                .append("] 方案说明: ").append(truncate(r.getResponseNote(), 2000)).append('\n');
        }
        sb.append("输出要求（只输出一个 JSON 对象，禁止 markdown 围栏与任何其他文字）：");
        sb.append("{\"dimensions\":[{\"dimension\":\"<四选一：")
            .append(String.join("|", DIMENSIONS))
            .append(">\",\"cells\":{\"<应标id>\":\"<该应标在此维度的表现>\"},")
            .append("\"difference\":\"<本维度差异一句话>\"}],\"differences\":[\"<全局差异高亮>\"]}。");
        sb.append("dimensions 必须四项齐全且 cells 覆盖全部应标 id；")
            .append("文中未提及的信息一律填「未提及」，禁止编造；")
            .append("本表仅供人工遴选参考，禁止输出任何遴选结论、排名或推荐。");
        return sb.toString();
    }

    /** 取首个 { 到末个 } 的外层 JSON 对象（容忍模型裹围栏/前后赘语）。 */
    static String extractJsonObject(String content) {
        String text = content == null ? "" : content.trim();
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "AI 对比输出不是合法 JSON，请重试");
        }
        return text.substring(open, close + 1);
    }

    /** 解析 + 校验：维度取白名单去重，四维必须齐全，缺一即拒（不静默补半表）。 */
    static List<DimensionRow> parseDimensions(JsonNode root) {
        List<DimensionRow> rows = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        JsonNode arr = root.path("dimensions");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                String dim = node.path("dimension").asText("");
                if (dim.isEmpty() || !DIMENSIONS.contains(dim) || seen.contains(dim)) {
                    continue;
                }
                Map<String, String> cells = new LinkedHashMap<>();
                node.path("cells").fields().forEachRemaining(e -> cells.put(e.getKey(), e.getValue().asText("")));
                rows.add(new DimensionRow(dim, cells, node.path("difference").asText("")));
                seen.add(dim);
            }
        }
        for (String dim : DIMENSIONS) {
            if (!seen.contains(dim)) {
                throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                    "AI 对比输出缺少维度: " + dim + "，请重试");
            }
        }
        return List.copyOf(rows);
    }

    /** 全局差异高亮：非空白字符串列表；缺省容忍为空表（差异高亮是增强项非硬契约）。 */
    static List<String> parseDifferences(JsonNode root) {
        List<String> out = new ArrayList<>();
        if (root.path("differences").isArray()) {
            for (JsonNode node : root.path("differences")) {
                String s = node.asText("");
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }

    /** 审计行（AI_BID_COMPARE）：三件套 aiRole=summarize（AI-P1-3 白名单既有值）；只记 id/计数/用量，不记原文。 */
    private void audit(IpdActor actor, Long invitationId, List<Long> ids, String model,
                       long latencyMs, int promptTokens, int completionTokens, String status) {
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id()).operatorName(actor.name()).operatorRole(actor.role())
            .action("AI_BID_COMPARE").entityType("bid_invitation").entityId(invitationId)
            .afterData(AuditEventData.json(
                "aiAssisted", true,
                "aiModel", model,
                "aiRole", "summarize",
                "responseIds", ids.stream().map(String::valueOf).collect(Collectors.joining(",")),
                "responseCount", ids.size(),
                "status", status,
                "tokenPrompt", promptTokens,
                "tokenCompletion", completionTokens,
                "latencyMs", latencyMs))
            .createTime(Date.from(clock.instant()))
            .build());
    }

    static String truncate(String s, int max) {
        return s == null ? "-" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
