package org.ruoyi.ipd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiCallScope;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * AI-P2-2 #2：应标完整性检查（提交前自检，纯只读分析）。
 *
 * <ul>
 *   <li>招标要求 + 应标方案说明喂 {@link AiGateway} 单轮（复用既有 AI 通道，不新建），
 *       产出逐条「MET/PARTIAL/MISSING + 证据」检查表 + 总评；结果仅回显供应标人补稿；</li>
 *   <li>红线：零业务表写入——bid_responses/bid_invitations 均不写（检查≠提交，提交仍走
 *       POST /bid-responses 人工流），单测以 never() 锁死；</li>
 *   <li>解析纪律：模型输出必须严格 JSON {@code {checks[{requirement,status,evidence}],summary}}，
 *       空 checks/非法状态值/缺 summary 一律 FAIL:PARSE 拒绝（不静默补表）；</li>
 *   <li>审计：AI_BID_CHECK 单行三件套 aiAssisted/aiModel/aiRole=precheck（AI-P1-3 白名单
 *       既有值）；BR-AI-04 prompt 与模型输出原文不落库。</li>
 * </ul>
 */
@Service
public class BidResponseCheckService {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final int CHECK_MAX_TOKENS = 1200;
    static final int CHECK_TIMEOUT_MS = 30_000;
    /** 应标方案说明上限（AiGenerateReq.prompt 同级量级，防 prompt 爆炸）。 */
    static final int MAX_NOTE_LEN = 30000;
    /** 检查结论值域：达标/部分达标/缺失（模型输出越界即 FAIL:PARSE）。 */
    static final List<String> STATUSES = List.of("MET", "PARTIAL", "MISSING");

    private final BidInvitationMapper bidInvitationMapper;
    private final IAiModelConfigService modelConfigService;
    private final AiGateway aiGateway;
    private final IAuditLogService auditLogService;

    /** 测试口注入固定时钟（同 BidAiCompareService.withClock 惯例）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public BidResponseCheckService(BidInvitationMapper bidInvitationMapper,
                                   IAiModelConfigService modelConfigService,
                                   AiGateway aiGateway,
                                   IAuditLogService auditLogService) {
        this.bidInvitationMapper = bidInvitationMapper;
        this.modelConfigService = modelConfigService;
        this.aiGateway = aiGateway;
        this.auditLogService = auditLogService;
    }

    BidResponseCheckService withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /** 单条检查行：requirement=招标要求条目，status=MET|PARTIAL|MISSING，evidence=覆盖证据/缺失说明。 */
    public record CheckRow(String requirement, String status, String evidence) {}

    /** 展示契约：逐条检查 + 总评 + 模型/用量元信息（无任何提交语义字段——提交恒人工）。 */
    public record CheckView(Long invitationId, String invitationTitle, List<CheckRow> checks,
                            String summary, String model, int tokenPrompt, int completionTokens,
                            long latencyMs) {}

    /**
     * 自检入口：参数闸→招标加载→单轮 AI→严格解析检查表→审计→返回。
     * 全程零业务表写入；解析不过即 FAIL:PARSE 拒绝，不出半张表。
     */
    public CheckView check(IpdActor actor, Long invitationId, String responseNote) {
        long start = clock.millis();
        BidInvitation inv = invitationId == null ? null : bidInvitationMapper.selectById(invitationId);
        if (inv == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "招标单不存在: " + invitationId);
        }
        String note = responseNote == null ? "" : responseNote.trim();
        if (note.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "应标方案说明必填，才能做完整性检查");
        }
        if (note.length() > MAX_NOTE_LEN) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "应标方案说明长度不能超过 " + MAX_NOTE_LEN);
        }
        // 未配置生效模型：currentEnabled 抛既有业务异常直通（宁可失败，不静默造结果）
        AiModelConfig config = modelConfigService.currentEnabled();
        AiChatResult result = aiGateway.chat(
            new AiTestConfig(config.getProvider(), config.getEndpointUrl(),
                modelConfigService.decryptApiKey(config), config.getModelName(), CHECK_TIMEOUT_MS,
                AiCallScope.of(config.getId(), actor, "bid_check")),
            composePrompt(inv, note), CHECK_MAX_TOKENS, new BigDecimal("0.20"));
        long latency = Math.max(0, clock.millis() - start);
        if (!result.success()) {
            audit(actor, invitationId, config.getModelName(), latency, 0, 0,
                "FAIL:" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()));
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "AI 完整性检查暂不可用: " + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()));
        }
        List<CheckRow> rows;
        String summary;
        try {
            JsonNode root = JSON.readTree(extractJsonObject(result.content()));
            rows = parseChecks(root);
            summary = root.path("summary").asText("");
            if (summary.isBlank()) {
                throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                    "AI 检查输出缺少 summary，请重试");
            }
        } catch (IpdBusinessException ex) {
            audit(actor, invitationId, config.getModelName(), latency,
                result.promptTokens(), result.completionTokens(), "FAIL:PARSE");
            throw ex;
        } catch (Exception ex) {
            audit(actor, invitationId, config.getModelName(), latency,
                result.promptTokens(), result.completionTokens(), "FAIL:PARSE");
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "AI 检查输出解析失败，请重试");
        }
        audit(actor, invitationId, config.getModelName(), latency,
            result.promptTokens(), result.completionTokens(), "ok");
        return new CheckView(invitationId, inv.getTitle(), rows, summary, config.getModelName(),
            result.promptTokens(), result.completionTokens(), latency);
    }

    /** Prompt：招标要求 + 应标方案说明喂入；原文只进模型，不进日志/审计（BR-AI-04）。 */
    static String composePrompt(BidInvitation inv, String responseNote) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("你是应标完整性检查助手。请对照招标要求，逐条检查以下应标方案说明的覆盖情况。\n");
        sb.append("招标标题: ").append(nullToDash(inv.getTitle())).append('\n');
        sb.append("招标内容: ").append(truncate(inv.getContent(), 2500)).append('\n');
        sb.append("应标方案说明: ").append(truncate(responseNote, 8000)).append('\n');
        sb.append("输出要求（只输出一个 JSON 对象，禁止 markdown 围栏与任何其他文字）：");
        sb.append("{\"checks\":[{\"requirement\":\"<招标要求条目>\",\"status\":\"<MET|PARTIAL|MISSING>\",")
            .append("\"evidence\":\"<应标说明中的覆盖证据或缺失说明>\"}],\"summary\":\"<总评一句话>\"}。");
        sb.append("要求：按招标内容可拆出的每条要求逐条检查，不得合并或遗漏；")
            .append("status 取值只能是 MET/PARTIAL/MISSING；")
            .append("应标说明未提及的信息一律判 MISSING 并注明，禁止编造证据；")
            .append("本检查仅供应标人提交前自查，禁止输出任何应标结论或推荐。");
        return sb.toString();
    }

    /** 取首个 { 到末个 } 的外层 JSON 对象（容忍模型裹围栏/前后赘语）。 */
    static String extractJsonObject(String content) {
        String text = content == null ? "" : content.trim();
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "AI 检查输出不是合法 JSON，请重试");
        }
        return text.substring(open, close + 1);
    }

    /** 解析 + 校验：checks 必须非空数组，行内 requirement 非空、status 白名单，违一即拒（不静默补表）。 */
    static List<CheckRow> parseChecks(JsonNode root) {
        JsonNode arr = root.path("checks");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "AI 检查输出缺少 checks 表，请重试");
        }
        List<CheckRow> rows = new ArrayList<>();
        for (JsonNode node : arr) {
            String requirement = node.path("requirement").asText("");
            String status = node.path("status").asText("");
            String evidence = node.path("evidence").asText("");
            if (requirement.isBlank() || !STATUSES.contains(status)) {
                throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                    "AI 检查输出存在非法检查行，请重试");
            }
            rows.add(new CheckRow(requirement, status, evidence));
        }
        return List.copyOf(rows);
    }

    /** 审计行（AI_BID_CHECK）：三件套 aiRole=precheck（AI-P1-3 白名单既有值）；只记 id/计数/用量，不记原文。 */
    private void audit(IpdActor actor, Long invitationId, String model,
                       long latencyMs, int promptTokens, int completionTokens, String status) {
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id()).operatorName(actor.name()).operatorRole(actor.role())
            .action("AI_BID_CHECK").entityType("bid_invitation").entityId(invitationId)
            .afterData(AuditEventData.json(
                "aiAssisted", true,
                "aiModel", model == null || model.isBlank() ? "unavailable" : model,
                "aiRole", "precheck",
                "scene", "bid.response-completeness-check",
                "invitationId", invitationId,
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
