package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * PLAN-KB-AUTO 组件① AuditLogEventListener：识别关键审计事件（action 前缀 GATE_ / KPI_ /
 * INCENTIVE_）→ 复用现有 {@link AiDocEmbeddingService} 收录其摘要进知识库（AI-STRAT-1
 * 向量化链，不新写向量链路）。本切片只做组件①；②KnowledgeAutoArchiver/③EvoMap SDK/
 * ④增量 cron 未做，见各类注释与交付报告。
 *
 * <p>挂点现查：{@code AuditLogServiceImpl.append} 无事件发布，且 {@code IpdAuditAspect}
 * javadoc 明文「禁止仿 LogAspect 的 publishEvent 异步」（锚行悲观锁 + REQUIRES_NEW
 * 串行语义不可破坏）——故不在核心审计链路强塞改动，本类暴露可调用的
 * {@link #archiveIfKey(AuditLog)}；「谁在何时调用它」留遗留（候选：append 后
 * afterCommit 回调，或组件④增量 cron 扫 audit_logs 驱动）。
 *
 * <p>前缀如实汇报：GATE_ / KPI_ 系主代码真实动作（现查命中 GATE_AUTO_CREATE、GATE_SIGN、
 * GATE_APPROVE、GATE_REJECT、GATE_REOPEN、GATE_ELEMENT_JUDGE、GATE_SUBMIT、
 * KPI_SHARED_COLLECT、KPI_SHARED_CONFIRM 等）；INCENTIVE_ 系主代码 0 命中（卡面假设，
 * grep 证据见交付报告）——保留为前缀匹配位，无动作可匹配即永不触发，无副作用。
 *
 * <p>幂等（卡面「不重复已处理」）：全模块现查无现成已处理标记机制，本切片不建新表
 * （不动 tenant.excludes 红线）。复用 ai_documents：摘要内嵌审计 seq（hash 链锚行锁
 * 原子分配，全局唯一）→ content_sha256 即去重键，重复事件按 (docType, contentSha256)
 * 命中即 DUPLICATE 短路，不重复落文档、不重复向量化。并发唯一索引加固留遗留。
 *
 * <p>BR-AI-04 对齐：摘要只进业务库 ai_documents 与向量表 ai_doc_embeddings（检索时进
 * prompt），不含 before/after 载荷原文，不进日志（只记 seq/action/docId 坐标）。
 */
@Slf4j
@Service
public class AuditLogEventListener {

    /** 知识库关键事件条目的 doc_type（检索侧可按类型过滤，与既有 PRD 等类型无冲突）。 */
    public static final String DOC_TYPE_KEY_EVENT = "KEY_EVENT";

    /** 关键事件动作前缀（卡面三族；INCENTIVE_ 现查不存在，保留占位，见类注释）。 */
    static final List<String> KEY_ACTION_PREFIXES = List.of("GATE_", "KPI_", "INCENTIVE_");

    private final AiDocumentMapper documentMapper;
    private final AiDocEmbeddingService embeddingService;

    public AuditLogEventListener(AiDocumentMapper documentMapper,
                                 AiDocEmbeddingService embeddingService) {
        this.documentMapper = documentMapper;
        this.embeddingService = embeddingService;
    }

    /** 收录结果（调用方据此留痕/断言；任何情形都不抛异常阻断业务侧）。 */
    public enum Outcome {
        /** 关键事件且未处理过：已落 KEY_EVENT 文档并触发向量化。 */
        ARCHIVED,
        /** 幂等命中：同一事件（seq → content_sha256）此前已收录，本次零副作用。 */
        DUPLICATE,
        /** 动作不匹配关键前缀：零副作用。 */
        NOT_KEY_EVENT,
        /** 事件残缺（null / 缺 seq / 缺 action）：seq 是幂等锚，缺失即拒收。 */
        INVALID_EVENT
    }

    /**
     * 给定一条审计事件：动作匹配关键前缀则收录摘要进知识库（异步向量化，复用
     * {@link AiDocEmbeddingService#embedAsync}），幂等见类注释。
     *
     * @param event 已落库的审计行（须含 seq——hash 链原子分配的全局唯一幂等锚）
     * @return 收录结果四态；本方法不抛业务异常（向量化自身失败在 embedAsync 内降级）
     */
    public Outcome archiveIfKey(AuditLog event) {
        if (event == null || event.getSeq() == null
            || event.getAction() == null || event.getAction().isBlank()) {
            return Outcome.INVALID_EVENT;
        }
        if (!isKeyEvent(event.getAction())) {
            return Outcome.NOT_KEY_EVENT;
        }
        String summary = buildSummary(event);
        String sha = AiDocumentService.sha256Hex(summary);
        Long existed = documentMapper.selectCount(new LambdaQueryWrapper<AiDocument>()
            .eq(AiDocument::getDocType, DOC_TYPE_KEY_EVENT)
            .eq(AiDocument::getContentSha256, sha));
        if (existed != null && existed > 0) {
            log.info("[PLAN-KB-AUTO] 幂等命中，不重复收录: seq={} action={}",
                event.getSeq(), event.getAction());
            return Outcome.DUPLICATE;
        }
        AiDocument doc = AiDocument.builder()
            // 审计事件不带项目维度；projectId 解析（entityType/entityId → project）归组件②
            .projectId(null)
            .docType(DOC_TYPE_KEY_EVENT)
            .title(buildTitle(event))
            .content(summary)
            .status(AiDocumentService.STATUS_GENERATED)
            .versionNo(1)
            .contentSha256(sha)
            .build();
        doc.setCreateBy(event.getOperatorId());
        documentMapper.insert(doc);
        embeddingService.embedAsync(doc);
        log.info("[PLAN-KB-AUTO] 关键事件已收录知识库: seq={} action={} docId={}",
            event.getSeq(), event.getAction(), doc.getId());
        return Outcome.ARCHIVED;
    }

    /** 关键事件识别：action 以任一关键前缀开头（大小写敏感——库内动作码均为大写蛇形）。 */
    static boolean isKeyEvent(String action) {
        for (String prefix : KEY_ACTION_PREFIXES) {
            if (action.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 标题 ≤200（ai_documents 既有校验上限，createGenerated 同源约束）。 */
    private static String buildTitle(AuditLog event) {
        String title = "【关键事件】" + event.getAction() + " "
            + (event.getEntityType() == null ? "-" : event.getEntityType())
            + "#" + (event.getEntityId() == null ? "-" : event.getEntityId());
        return title.length() > 200 ? title.substring(0, 200) : title;
    }

    /**
     * 摘要固定格式（package-private 供单测）：同一事件重放产出逐字节一致——幂等依赖
     * sha256(本串) 稳定。含 seq（去重锚）；时间截秒（与审计 hash 链 DEF-4 秒级对称
     * 同语义）；reason 截 500 防长文本淹没切片；不携带 before/after 载荷原文。
     */
    static String buildSummary(AuditLog event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("审计seq=").append(event.getSeq())
          .append("｜动作=").append(event.getAction())
          .append("｜实体=").append(event.getEntityType() == null ? "-" : event.getEntityType())
          .append('#').append(event.getEntityId() == null ? "-" : event.getEntityId())
          .append("｜操作人=").append(event.getOperatorName() == null ? "-" : event.getOperatorName());
        if (event.getOperatorRole() != null && !event.getOperatorRole().isBlank()) {
            sb.append('(').append(event.getOperatorRole()).append(')');
        }
        sb.append("｜时间=").append(formatSecond(event.getCreateTime()));
        String reason = event.getReason();
        if (reason != null && !reason.isBlank()) {
            sb.append("｜说明=").append(reason.length() > 500 ? reason.substring(0, 500) : reason);
        }
        return sb.toString();
    }

    /** 截秒格式化（DEF-4 同款语义：毫秒不进摘要，避免同事件重放 sha 漂移）。 */
    private static String formatSecond(Date time) {
        long ms = time == null ? 0L : time.getTime() / 1000L * 1000L;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }
}
