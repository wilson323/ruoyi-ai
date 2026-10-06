package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.factory.ResourceLoaderFactory;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.service.knowledge.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 人工审计「导入终稿」：把系统外定稿的成品文件（docx/pdf/md/txt）解析为正文，
 * 沿既有版本链追加一个待审核版本（复用 {@link AiDocumentService#revise} 的
 * append-only 语义：基准非 HEAD → STATE_CONFLICT，绝不覆盖历史）。
 *
 * <p>导入后的版本与 AI 改版完全同构（status=GENERATED）：仍走原人工审核
 * （BR-AI-03）→ 定档 → 交付/闭环链路，不新增业务闸门、不改审核语义。
 * doc（老版二进制 Word）不在白名单——现有 WordLoader 只支持 OOXML（docx）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiDocumentImportService {

    /** 与全局 spring.servlet.multipart 上限一致（ruoyi-admin application.yml：20MB）。 */
    static final long MAX_BYTES = 20L * 1024 * 1024;

    /** 仅放行可提取正文的体裁；其余扩展名直接拒绝，不走 TextFileLoader 兜底裸读。 */
    static final Set<String> ALLOWED_EXT = Set.of("docx", "pdf", "md", "markdown", "txt");

    private final AiDocumentService aiDocumentService;
    private final ResourceLoaderFactory resourceLoaderFactory;
    private final IAuditLogService auditLogService;

    /**
     * 导入终稿并追加待审核版本。
     *
     * @param actor 会话身份（审核人/改版人，来自真实 Person 会话）
     * @param documentId 链上任一版本行 ID（服务自行解析 HEAD）
     * @param baseVersionId 调用方声明的基准版本（乐观锁用途，必须为当前 HEAD）
     * @param file 终稿文件（docx/pdf/md/txt，≤20MB）
     * @param title 新标题（空则沿用 HEAD）
     * @return 新版本行 v(n+1)（status=GENERATED，需重新人工审核）
     */
    public AiDocument importFinalVersion(IpdActor actor, Long documentId, Long baseVersionId,
                                         MultipartFile file, String title) {
        if (actor == null || actor.id() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "会话身份必填");
        }
        if (title != null && title.length() > 200) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "标题不得超过 200 字");
        }
        if (file == null || file.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "导入文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "导入文件超过 20MB 上限（实际 " + (file.getSize() / 1024 / 1024) + "MB）");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "仅支持 docx / pdf / md / txt 终稿导入（当前为 " + (ext.isEmpty() ? "无扩展名" : ext) + "）");
        }
        // 授权与链校验先于解析：不可见项目不触发文件读取副作用（对齐 generate 入口口径）
        List<AiDocument> chain = aiDocumentService.history(documentId);
        aiDocumentService.requireProjectVisible(actor, chain.get(0).getProjectId());
        boolean onChain = chain.stream().anyMatch(r -> r.getId() != null && r.getId().equals(baseVersionId));
        if (!onChain) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }
        AiDocument head = chain.get(chain.size() - 1);
        if (AiDocumentService.STATUS_ARCHIVED.equals(head.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该文档链已归档收口，不能再导入终稿");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "读取上传文件失败，请重试");
        }
        String content = extractText(ext, bytes, file.getOriginalFilename());
        if (content == null || content.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "未能从该文件解析出正文（可能是扫描件或空文档），请改用可复制文本的版本");
        }

        AiDocument next = aiDocumentService.revise(documentId, baseVersionId, content, title, actor.id());
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id())
            .action("AI_DOC_IMPORT")
            .entityType("ai_documents")
            .entityId(next.getId())
            .reason("导入终稿 v" + next.getVersionNo())
            .afterData(AuditEventData.json(
                "baseVersionId", baseVersionId,
                "fileName", sanitizeForAudit(file.getOriginalFilename()),
                "fileExt", ext,
                "bytes", bytes.length))
            .createTime(new Date())
            .build());
        log.info("[ai-doc-import] documentId={} baseVersionId={} newVersionId={} ext={} actor={}",
            documentId, baseVersionId, next.getId(), ext, actor.id());
        return next;
    }

    /**
     * 按扩展名提取正文：docx/pdf 走本仓既有知识附件同款加载器（POI / PDFBox），
     * md/txt 按 UTF-8 原样读取（去 BOM），保留 Markdown 结构供后续版本链与向量化。
     * 解析异常统一转 PARAM_INVALID 白话提示，不外泄堆栈。
     */
    private String extractText(String ext, byte[] bytes, String fileName) {
        try {
            if ("docx".equals(ext) || "pdf".equals(ext)) {
                ResourceLoader loader = resourceLoaderFactory.getLoaderByFileType(ext);
                return loader.getContent(new ByteArrayInputStream(bytes));
            }
            String raw = new String(bytes, StandardCharsets.UTF_8);
            return raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        } catch (Exception e) {
            log.warn("[ai-doc-import] 解析失败 fileName={} ext={} cause={}", fileName, ext, e.toString());
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "文件解析失败，请确认文件未加密且扩展名与实际格式一致");
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 审计载荷只留文件名主干，去掉路径分隔符，防路径注入进日志消费方。 */
    private static String sanitizeForAudit(String fileName) {
        if (fileName == null) {
            return "";
        }
        String base = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        return base.length() > 120 ? base.substring(0, 120) : base;
    }
}
