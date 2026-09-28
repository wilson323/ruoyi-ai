package org.ruoyi.ipd.service;

import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;

/**
 * AI-P2-2 #1：招标书起草（文档生成类）。
 *
 * <ul>
 *   <li>起草走 {@link IAiGenerationService#generate} 既有治理链（SSRF 防护/月度预算/
 *       Semaphore 限流/RAG 注入/PromptTemplates/瞬时失败重试 1 次/落库 AiDocument v1/
 *       自动 AI_GENERATE 审计 aiRole=draft 七道全复用），不新建 AI 通道、不双轨；</li>
 *   <li>产出登记为版本链首环（docType=BID_INVITATION_DRAFT，status=GENERATED 待审核），
 *       本端点只出草稿不代审——发布仍走 createInvitation/publishInvitation 人工流；</li>
 *   <li>起草口径：输出可发布招标书（项目背景/招标目标/交付要求/工期/验收标准/应标须知）；
 *       未提及内容按行业惯例补齐并集中列「起草说明」待 PM 确认假设，禁止编造具体数字承诺；</li>
 *   <li>失败语义：generate 失败异常直通（自带 AI_GENERATE_FAILED 审计），不吞不降级——
 *       起草失败必须可见，不能拿半成品当草稿。</li>
 * </ul>
 */
@Service
public class BidAiDraftService {

    /** 起草产出登记 AiDocument v1 的 docType 标签（AiGenerateReq.docType ≤32 字符）。 */
    static final String DOC_TYPE = "BID_INVITATION_DRAFT";
    static final int MAX_TITLE_LEN = 200;
    /** PM 原始需求上限：25000（留 ~5000 余量给 prompt 模板，AiGenerateReq.prompt ≤30000）。 */
    static final int MAX_BRIEF_LEN = 25000;

    private final IAiGenerationService aiGenerationService;

    /** 测试口注入固定时钟（同 suggestion/copilot 模式）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public BidAiDraftService(IAiGenerationService aiGenerationService) {
        this.aiGenerationService = aiGenerationService;
    }

    BidAiDraftService withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /** 起草结果视图：草稿已登记版本链（docId 指向 v1），含模型/用量元信息。 */
    public record BidDraftView(Long docId, String title, String content, String model,
                               int tokenPrompt, int tokenCompletion, long latencyMs, String status) {}

    /** 起草入口：参数闸 → 委托 generate() 出草稿 v1 → 整形返回。 */
    public BidDraftView draft(IpdActor actor, Long projectId, String title, String brief) {
        long start = clock.millis();
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 必填");
        }
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "title 必填");
        }
        if (t.length() > MAX_TITLE_LEN) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "title 长度不能超过 " + MAX_TITLE_LEN);
        }
        String b = brief == null ? "" : brief.trim();
        if (b.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "brief（PM 原始需求）必填");
        }
        if (b.length() > MAX_BRIEF_LEN) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "brief 长度不能超过 " + MAX_BRIEF_LEN);
        }
        AiDocument doc = aiGenerationService.generate(actor,
            new AiGenerateReq(projectId, DOC_TYPE, t, composePrompt(t, b)));
        return new BidDraftView(doc.getId(), doc.getTitle(), doc.getContent(), doc.getModel(),
            doc.getTokenPrompt() == null ? 0 : doc.getTokenPrompt(),
            doc.getTokenCompletion() == null ? 0 : doc.getTokenCompletion(),
            Math.max(0, clock.millis() - start), doc.getStatus());
    }

    /** Prompt：只出招标书正文；未提及按行业惯例补齐并集中列「起草说明」待 PM 确认，禁编造数字承诺。 */
    static String composePrompt(String title, String brief) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("你是招标书起草助手。请基于 PM 提供的原始需求，起草一份可发布的招标书正文");
        sb.append("（Markdown 纯文本输出，不要围栏）。\n");
        sb.append("必须包含章节：项目背景、招标目标、交付要求、工期安排、验收标准、应标须知。\n");
        sb.append("要求：\n");
        sb.append("1. 原始需求未提及的内容按行业惯例补齐，并在文末「起草说明」章节逐条列出补齐的假设，供 PM 确认；\n");
        sb.append("2. 禁止编造具体数字承诺（金额、精确工期天数、人数等），需要数字处以「待 PM 确认」占位；\n");
        sb.append("3. 只输出招标书正文，禁止任何对话性文字。\n");
        sb.append("招标标题：").append(title).append('\n');
        sb.append("PM 原始需求：\n").append(brief);
        return sb.toString();
    }
}
