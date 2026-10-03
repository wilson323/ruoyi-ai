package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.constant.CacheNames;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * AI 文档原始输出与不可丢失版本链服务（P1-10.1；主责 AC-AI-04 / AC-AI-06，BR-AI-03）。
 *
 * <p>版本链模型（append-only）：
 * <ul>
 *   <li>v1 = AI 原始输出（status=GENERATED，parent=NULL）——生成入口由 P4-2 接管，
 *       本服务仅登记版本链首环；</li>
 *   <li>人工改版 {@link #revise} = 基于当前 HEAD 追加 v(n+1)（parent=HEAD.id，status=GENERATED
 *       需重新人工审核）；基准版本非 HEAD → STATE_CONFLICT 明确报错，绝不静默覆盖；</li>
 *   <li>人工审核 {@link #review} = 行内状态流转 GENERATED→REVIEWED + reviewed_by/reviewed_at
 *       落名（BR-AI-03：AI 输出未经审核不生效）——条件 UPDATE，不触碰 content/摘要；</li>
 *   <li>历史不可覆盖：全程零 content/标题/摘要更新通道，修正只能产生新版本；</li>
 *   <li>并发防分叉三层防线：服务层 HEAD 校验 → DB 层 uk_ai_doc_parent 唯一索引 →
 *       DuplicateKeyException 映射 STATE_CONFLICT（与 P0-3.3 insertVersion 同款包络）。</li>
 * </ul>
 *
 * <p>链完整性（AC-AI-06）：{@link #history} 自任意版本行向上走到根（根必须 v1）、再向下
 * 按父指针逐环下探，校验版本号连续 v1..vN 无缺失、父链接无断点；任一断点即 STATE_CONFLICT。
 */
@Service
@Slf4j
public class AiDocumentService {

    /** AI 原始输出/人工改版后待审（库内码；用户可见名见 {@link #LABEL_PENDING_REVIEW}）。 */
    public static final String STATUS_GENERATED = "GENERATED";
    /** GENERATED 的用户可见名。未人工审核不得写成已审核或已生成。 */
    public static final String LABEL_PENDING_REVIEW = "待审核";
    /** 人工审核通过（BR-AI-03） */
    public static final String STATUS_REVIEWED = "REVIEWED";
    /** 审核拒绝——终态，必须重新走 review 流后才能 archive（BR-AI-03 兜底） */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 已归档——终态，仅 REVIEWED 行可归档；归档后不可改版/拒绝/再归档 */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    /**
     * 文档状态的用户可见名。库内码不变；GENERATED 只显示「待审核」。
     *
     * @param status ai_documents.status
     * @return 中文展示名；未知码原样返回，避免另造状态机
     */
    public static String statusLabel(String status) {
        if (STATUS_GENERATED.equals(status)) {
            return LABEL_PENDING_REVIEW;
        }
        if (STATUS_REVIEWED.equals(status)) {
            return "已审核";
        }
        if (STATUS_REJECTED.equals(status)) {
            return "已拒绝";
        }
        if (STATUS_ARCHIVED.equals(status)) {
            return "已归档";
        }
        return status == null ? "" : status;
    }

    /**
     * 读回已落库文档的状态。重复定档必须用这一行，不能把后来的审核结果写成待审核。
     *
     * @param documentId 文档主键
     * @return 库内状态；行不存在或状态为空时为空
     */
    public Optional<String> statusOf(Long documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        AiDocument row = mapper.selectById(documentId);
        if (row == null || row.getStatus() == null || row.getStatus().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(row.getStatus());
    }

    /** AC-AI-03：未审核不可归档的对外文案（"须人工审核确认" 固定字面量） */
    public static final String MSG_REVIEW_REQUIRED = "AI 文档须人工审核确认才可归档";

    /** AC-AI-05：diff 报告结构——仅存差异字段，全文重写由调用方按 contentSha256 自查 */
    public record FieldDiff(String field, String fromValue, String toValue, String fromSha256, String toSha256) {}

    /** AC-AI-05：两版本 diff 报告（包含两条版本行 + 字段级差异列表） */
    public record DiffReport(Long fromVersionId, Integer fromVersionNo,
                             Long toVersionId, Integer toVersionNo,
                             List<FieldDiff> differences) {}

    private final AiDocumentMapper mapper;
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    /** P0-8：状态流转审计（nullable，兼容既有单参构造；生产 Spring 装配）。 */
    private IAuditLogService auditLogService;

    /** AI-STRAT-1（2026-09-11）：审核通过即异步向量化（nullable 同上——单测可只装配主链）。 */
    private AiDocEmbeddingService docEmbeddingService;

    /** R221 Task 10：人审通过自动闭环 hook（nullable 同上——无关联任务时 hook 内部 no-op）。 */
    private AiExecReviewHook aiExecReviewHook;

    /**
     * 项目可见性守卫（nullable：单测可只测主链；生产由 Spring 注入）。
     * W2 产物 apply / 授权写入口经此重读 Person，禁止信任请求侧缓存成员关系。
     */
    private IpdCopilotAccess projectAccess;

    @Autowired(required = false)
    public void setAiExecReviewHook(AiExecReviewHook aiExecReviewHook) {
        this.aiExecReviewHook = aiExecReviewHook;
    }

    @Autowired(required = false)
    public void setAuditLogService(IAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 可选注入文档向量化服务：审核通过后异步写向量；未装配时主链仍可运行。
     *
     * @param docEmbeddingService 向量化服务，可为 null（单测 / 降级）
     */
    @Autowired(required = false)
    public void setDocEmbeddingService(AiDocEmbeddingService docEmbeddingService) {
        this.docEmbeddingService = docEmbeddingService;
    }

    /**
     * 注入项目可见性守卫（产物 apply / 授权写入口依赖）。
     *
     * @param projectAccess 副驾数据范围守卫，可为 null（单测未装配时授权入口会失败）
     */
    @Autowired(required = false)
    public void setProjectAccess(IpdCopilotAccess projectAccess) {
        this.projectAccess = projectAccess;
    }

    /**
     * 校验 actor 对 projectId 可见；每次重读 Person，不做成员关系缓存。
     *
     * @param actor 会话身份
     * @param projectId 目标项目
     * @return 可信租户 ID
     */
    public String requireProjectVisible(IpdActor actor, Long projectId) {
        if (projectAccess == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目可见性守卫未装配");
        }
        return projectAccess.requireVisible(actor, projectId);
    }

    private ProjectService projectReadAccess;

    @Autowired(required = false)
    public void setProjectReadAccess(ProjectService projectReadAccess) {
        this.projectReadAccess = projectReadAccess;
    }

    /** 产物只读复用项目可见规则；真实 Person 和租户仍每次重新核验。 */
    public String requireProjectReadable(IpdActor actor, Long projectId) {
        if (projectAccess == null || projectReadAccess == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目只读守卫未装配");
        }
        String tenantId = projectAccess.requireVisible(actor, null);
        org.ruoyi.ipd.domain.Project project = projectReadAccess.getVisibleById(projectId, actor);
        if (!java.util.Objects.equals(tenantId, project.getTenantId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见");
        }
        return tenantId;
    }

    /** 仅真实流转行审计；幂等短路与并发重读分支不审计（避免同一流转双行）。 */
    private void auditTransition(Long versionId, Long operatorId, String fromStatus,
                                 String toStatus, String comment) {
        if (auditLogService == null) {
            return;
        }
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId)
            .action("AI_DOC_" + toStatus)
            .entityType("ai_documents")
            .entityId(versionId)
            .reason("from=" + fromStatus + ",to=" + toStatus)
            .afterData(AuditEventData.json(
                "fromStatus", fromStatus,
                "toStatus", toStatus,
                "reviewComment", comment))
            .createTime(Date.from(clock.instant()))
            .build());
    }

    public AiDocumentService(AiDocumentMapper mapper) {
        this.mapper = mapper;
    }

    /** 测试口：注入固定时钟（reviewed_at 断言）；生产走系统时钟。 */
    AiDocumentService withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /**
     * 登记 AI 原始输出 v1（版本链首环；AI 生成/模型配置/预算属 P4-2，不在本卡）。
     *
     * @return 落库行（versionNo=1，status=GENERATED，contentSha256 已算）
     */
    @CacheEvict(cacheNames = CacheNames.IPD_AI_DOC_CHAIN, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public AiDocument createGenerated(Long projectId, String docType, String title, String content,
                                      String model, Integer tokenPrompt, Integer tokenCompletion,
                                      Long operatorId) {
        requireArg(projectId != null, "projectId 必填");
        requireArg(title != null && !title.isBlank(), "title 必填");
        requireArg(title.length() <= 200, "title 超长（≤200）");
        requireArg(content != null && !content.isBlank(), "content 必填（AI 原始输出不可为空）");

        AiDocument row = AiDocument.builder()
            .projectId(projectId).docType(docType).title(title).content(content)
            .model(model).tokenPrompt(tokenPrompt).tokenCompletion(tokenCompletion)
            .status(STATUS_GENERATED).parentVersionId(null).versionNo(1)
            .contentSha256(sha256Hex(content))
            .build();
        row.setCreateBy(operatorId);
        mapper.insert(row);
        return row;
    }

    /**
     * 授权登记 AI 原始输出 v1：先校验项目可见性，再落 GENERATED 文档。
     * <p>W2 产物 apply 专用入口；不改审核语义（仍为 GENERATED，索引未就绪由调用方返回 NOT_INDEXED）。
     * 可见性在 insert 前再读一次 Person，防止 preflight 与写入之间的成员撤销窗口。
     *
     * @param actor 会话身份（createBy = actor.id()）
     * @param projectId 项目 ID
     * @param docType 文档类型
     * @param title 标题
     * @param content 正文
     * @param model 模型名（可空）
     * @param tokenPrompt prompt token（可空）
     * @param tokenCompletion completion token（可空）
     * @return 落库行（status=GENERATED）
     */
    @CacheEvict(cacheNames = CacheNames.IPD_AI_DOC_CHAIN, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public AiDocument createGeneratedAuthorized(IpdActor actor, Long projectId, String docType,
                                                String title, String content, String model,
                                                Integer tokenPrompt, Integer tokenCompletion) {
        requireArg(actor != null && actor.id() != null, "actor 必填");
        requireProjectVisible(actor, projectId);
        return createGenerated(projectId, docType, title, content, model,
            tokenPrompt, tokenCompletion, actor.id());
    }

    /** AI 返工沿原文档链追加待审核版本，保留本次模型和用量；陈旧基准拒绝。 */
    @CacheEvict(cacheNames = CacheNames.IPD_AI_DOC_CHAIN, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public AiDocument reviseGeneratedAuthorized(IpdActor actor, Long projectId, String docType,
                                                Long documentId, Long baseVersionId, String title,
                                                String content, String model, Integer tokenPrompt,
                                                Integer tokenCompletion) {
        requireArg(actor != null && actor.id() != null, "actor 必填");
        requireArg(documentId != null && baseVersionId != null, "文档和基准版本必填");
        requireArg(title != null && !title.isBlank() && title.length() <= 200, "标题必填且不超过200字");
        requireArg(content != null && !content.isBlank(), "返工正文必填");
        requireProjectVisible(actor, projectId);
        AiDocument head = lockedHead(documentId);
        if (!Objects.equals(head.getProjectId(), projectId) || !Objects.equals(head.getDocType(), docType)
            || !Objects.equals(head.getId(), baseVersionId) || STATUS_ARCHIVED.equals(head.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "文档基准已变化或不属于本次动作，请重新核对后返工");
        }
        AiDocument next = AiDocument.builder().projectId(projectId).docType(docType).title(title).content(content)
            .model(model).tokenPrompt(tokenPrompt).tokenCompletion(tokenCompletion).status(STATUS_GENERATED)
            .parentVersionId(head.getId()).versionNo(head.getVersionNo() + 1).contentSha256(sha256Hex(content)).build();
        next.setCreateBy(actor.id());
        try { mapper.insert(next); }
        catch (DuplicateKeyException conflict) { throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT); }
        return next;
    }

    /**
     * 人工改版（AC-AI-04：审核通过后修改 ⇒ 生成新版本 v2，v1 保留）。
     * 基准版本必须为当前 HEAD；非 HEAD（并发被他人改过/拿旧版提交）→ STATE_CONFLICT
     * 明确报错，绝不静默覆盖任何历史版本。
     *
     * @param documentId    链上任一版本行 ID（服务自行解析 HEAD）
     * @param baseVersionId 调用方声明的基准版本（乐观锁用途）
     * @param newContent    改版全文
     * @param title         新标题（空则沿用 HEAD）
     * @return 新版本行 v(n+1)（status=GENERATED，需重新人工审核）
     */
    @CacheEvict(cacheNames = CacheNames.IPD_AI_DOC_CHAIN, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public AiDocument revise(Long documentId, Long baseVersionId, String newContent,
                             String title, Long operatorId) {
        requireArg(documentId != null, "documentId 必填");
        requireArg(baseVersionId != null, "baseVersionId 必填（声明基准版本）");
        requireArg(newContent != null && !newContent.isBlank(), "改版内容必填");

        AiDocument head = lockedHead(documentId);
        if (!baseVersionId.equals(head.getId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }

        AiDocument next = AiDocument.builder()
            .projectId(head.getProjectId()).docType(head.getDocType())
            .title(title != null && !title.isBlank() ? title : head.getTitle())
            .content(newContent)
            .model(null)
            .status(STATUS_GENERATED)
            .parentVersionId(head.getId())
            .versionNo(head.getVersionNo() + 1)
            .contentSha256(sha256Hex(newContent))
            .build();
        next.setCreateBy(operatorId);
        try {
            mapper.insert(next);
        } catch (DuplicateKeyException e) {
            // uk_ai_doc_parent：并发双写同一父版本被 DB 拦截 → 明确报冲突，不静默覆盖
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }
        return next;
    }

    /** 重建已有已审核版本的检索缓存，不重新审核或生成文档版本。 */
    public int rebuildIndexAuthorized(IpdActor actor, Long versionId) {
        AiDocument row = mapper.selectById(versionId);
        if (row == null) throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        requireProjectVisible(actor, row.getProjectId());
        if (docEmbeddingService == null) throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
            "文档索引服务未装配");
        return docEmbeddingService.rebuildIndex(row, () -> requireProjectVisible(actor, row.getProjectId()));
    }

    /**
     * 人工审核通过（BR-AI-03）。仅流转 status + 审核落名三列，内容零触碰；
     * 已审核行幂等返回（不覆盖首位审核人）；ARCHIVED 行拒绝（需走归档流程）；
     * R218-D1 修复：REJECTED 行可重新审核通过（reject() javadoc 契约「拒绝后必须重新走
     * 审核流才能归档」；此前条件更新仅匹配 GENERATED，REJECTED 行静默 0 行永久死态）。
     *
     * @return 审核后（或幂等时既有）行
     */
    @CacheEvict(cacheNames = CacheNames.IPD_AI_DOC_CHAIN, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public AiDocument review(Long versionId, Long operatorId) {
        AiDocument row = mapper.selectById(versionId);
        if (row == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        if (STATUS_REVIEWED.equals(row.getStatus())) {
            return row;
        }
        if (STATUS_ARCHIVED.equals(row.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }
        Date now = Date.from(clock.instant());
        String from = row.getStatus(); // R218-D1：审计 before 记真实来源态（GENERATED 或 REJECTED）
        int updated = mapper.update(null, Wrappers.<AiDocument>lambdaUpdate()
            .eq(AiDocument::getId, versionId)
            .in(AiDocument::getStatus, STATUS_GENERATED, STATUS_REJECTED)
            .set(AiDocument::getStatus, STATUS_REVIEWED)
            .set(AiDocument::getReviewedBy, operatorId)
            .set(AiDocument::getReviewedAt, now));
        if (updated > 0) {
            row.setStatus(STATUS_REVIEWED);
            row.setReviewedBy(operatorId);
            row.setReviewedAt(now);
            auditTransition(versionId, operatorId, from, STATUS_REVIEWED, null);
            // AI-STRAT-1：审核通过即触发异步向量化（RAG 资料库入库；幂等分支不重复向量化；
            // 内部 RAG 未配置/失败均只降级不阻塞审核事务）
            // R184-A（2026-09-23）：哨兵日志改用 log.warn/info 级别；System.err 不进 ELK、不分级别。
            // AiModelConfigMapper / AiDocEmbeddingMapper 已加 @InterceptorIgnore(tenantLine="true")
            // 修 tenant_id IS NULL 误过滤；此处只观察 embedAsync 是否被调用即可。
            if (docEmbeddingService != null) {
                log.info("[AI-STRAT-1-SCOPE] review触发embedAsync docId={} status={} embeddingService={}",
                    row.getId(), row.getStatus(), docEmbeddingService.getClass().getSimpleName());
                docEmbeddingService.embedAsync(row);
            } else {
                log.warn("[AI-STRAT-1-SCOPE] docEmbeddingService 未注入，embedAsync 跳过 reviewDocId={} status={}",
                    row.getId(), row.getStatus());
            }
            // R221 Task 10：挂着 SUCCEEDED 任务行的文档审通过 → 自动挂交付物 + DONE + 唤醒后继。
            // 复审 W1：闭环主体在 hook 内 afterCommit 延迟运行（不在本事务内，不会 rollback-only 反噬审核）；
            // 本处 try/catch 只余窄用途——兜注册/无事务内联路径的首读异常，异常对象尾参带堆栈（复审 S3）
            if (aiExecReviewHook != null) {
                try {
                    aiExecReviewHook.onDocumentReviewed(versionId, row.getContent());
                } catch (RuntimeException e) {
                    log.warn("[R221] review 闭环 hook 注册/执行失败（不影响审核结果）docId={}", versionId, e);
                }
            }
            return row;
        }
        // 并发已被他人审核：重读终态返回，不报错不覆盖
        AiDocument fresh = mapper.selectById(versionId);
        return fresh != null ? fresh : row;
    }

    /**
     * AC-AI-03 / BR-AI-02：未审核不可归档。仅流转 status + 归档落名三列；
     * 仅 status=REVIEWED 行可归档（GENERATED/REJECTED 拒绝；ARCHIVED 幂等拒绝）。
     *
     * @param versionId  任一版本行 ID（服务自行定位行）
     * @param operatorId 操作者（写入 archived_by 审计身份）
     * @return 归档后行
     * @throws IpdBusinessException STATE_CONFLICT 未审核 / 已归档 / 已拒绝
     */
    @CacheEvict(cacheNames = CacheNames.IPD_AI_DOC_CHAIN, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public AiDocument archive(Long versionId, Long operatorId) {
        AiDocument row = mapper.selectById(versionId);
        if (row == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        // AC-AI-03：未审核（包括 GENERATED 初次/REJECTED 拒绝后未重审）→ 拒绝
        if (!STATUS_REVIEWED.equals(row.getStatus())) {
            throw new IpdBusinessException(
                ApiV1ErrorCode.STATE_CONFLICT, MSG_REVIEW_REQUIRED);
        }
        Date now = Date.from(clock.instant());
        // 条件 UPDATE：再次防御并发（他人同时归档覆盖 → 0 行受影响 → 重读终态）
        int updated = mapper.update(null, Wrappers.<AiDocument>lambdaUpdate()
            .eq(AiDocument::getId, versionId)
            .eq(AiDocument::getStatus, STATUS_REVIEWED)
            .set(AiDocument::getStatus, STATUS_ARCHIVED)
            .set(AiDocument::getArchivedAt, now)
            .set(AiDocument::getArchivedBy, operatorId));
        if (updated > 0) {
            row.setStatus(STATUS_ARCHIVED);
            row.setArchivedAt(now);
            row.setArchivedBy(operatorId);
            auditTransition(versionId, operatorId, STATUS_REVIEWED, STATUS_ARCHIVED, null);
            return row;
        }
        // 并发已被他人归档：终态自洽返回
        AiDocument fresh = mapper.selectById(versionId);
        if (fresh != null && STATUS_ARCHIVED.equals(fresh.getStatus())) {
            return fresh;
        }
        // 并发期间被 reject / delete 抢走：拒绝
        throw new IpdBusinessException(
            ApiV1ErrorCode.STATE_CONFLICT, MSG_REVIEW_REQUIRED);
    }

    /**
     * 退回修改。当前链头上的待审核稿（GENERATED）或已审核稿（REVIEWED）可退回为 REJECTED。
     * 意见只写在被退回的这一版 {@code review_comment} 上；后续 {@link #revise} 新版本回到
     * GENERATED，不继承本版意见、审核人或已审核状态。待审核稿已不是链头时拒绝过时退回。
     * ARCHIVED 不可退。已是 REJECTED 时原样返回，不覆盖意见、不重复审计。
     *
     * @param versionId  版本行 ID（意见绑定的那一版）
     * @param operatorId 操作者（待审核稿写入 reviewed_by；已拒绝时不覆盖）
     * @param comment    退回意见（必填；落在该版本行）
     * @return 退回后的同一版本行
     */
    @CacheEvict(cacheNames = CacheNames.IPD_AI_DOC_CHAIN, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public AiDocument reject(Long versionId, Long operatorId, String comment) {
        requireArg(comment != null && !comment.isBlank(), "拒绝原因必填");
        AiDocument row = mapper.selectById(versionId);
        if (row == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        if (STATUS_REJECTED.equals(row.getStatus())) {
            return row; // 幂等：已拒绝不覆盖原 reviewComment / reviewedBy
        }
        if (STATUS_ARCHIVED.equals(row.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "已归档行不可拒绝（终态）");
        }
        String from = row.getStatus();
        boolean pending = STATUS_GENERATED.equals(from);
        if (pending) {
            requireCurrentHead(versionId);
        } else if (!STATUS_REVIEWED.equals(from)) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "仅待审核或已审核版本可退回");
        }
        Date now = Date.from(clock.instant());
        var update = Wrappers.<AiDocument>lambdaUpdate()
            .eq(AiDocument::getId, versionId)
            .eq(AiDocument::getStatus, from)
            .set(AiDocument::getStatus, STATUS_REJECTED)
            .set(AiDocument::getReviewComment, comment)
            .set(AiDocument::getReviewedAt, now);
        if (pending) {
            update.set(AiDocument::getReviewedBy, operatorId);
        }
        int updated = mapper.update(null, update);
        if (updated > 0) {
            if (pending) {
                // 写入后链头已变：抛出让本事务回滚，避免过时退回留下意见。
                requireCurrentHead(versionId);
            }
            row.setStatus(STATUS_REJECTED);
            row.setReviewComment(comment);
            row.setReviewedAt(now);
            if (pending) {
                row.setReviewedBy(operatorId);
            }
            auditTransition(versionId, operatorId, from, STATUS_REJECTED, comment);
            return row;
        }
        AiDocument reread = mapper.selectById(versionId);
        if (reread != null && STATUS_REJECTED.equals(reread.getStatus())) {
            return reread;
        }
        throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
    }

    /**
     * 待审核退回只允许打在当前链头上。版本在审核期间被改过则拒绝。
     *
     * @param versionId 调用方声明的版本行
     */
    private void requireCurrentHead(Long versionId) {
        if (!versionId.equals(lockedHead(versionId).getId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "版本已变更，拒绝过时退回");
        }
    }

    /**
     * AC-AI-05：两版本字段级 diff（fromVersionId 任意版本行 ID → toVersionId 任意版本行 ID）。
     * 若能取到 from 所属版本链，则 to 必须同链，否则 STATE_CONFLICT；链数据未装配时保持仅按行对比（兼容旧测）。
     * 差异字段：title/content/contentSha256（review/archived 落名不参与 diff——属审计维度）。
     *
     * @return DiffReport，含两版本行 ID/版本号 + 字段级差异列表（无差异返回空列表）
     */
    public DiffReport diff(Long fromVersionId, Long toVersionId) {
        requireArg(fromVersionId != null, "fromVersionId 必填");
        requireArg(toVersionId != null, "toVersionId 必填");
        AiDocument from = mapper.selectById(fromVersionId);
        AiDocument to = mapper.selectById(toVersionId);
        if (from == null || to == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        List<AiDocument> fromChain = mapper.selectChain(fromVersionId);
        if (fromChain != null && !fromChain.isEmpty()
            && fromChain.stream().noneMatch(r -> toVersionId.equals(r.getId()))) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }
        List<FieldDiff> diffs = new ArrayList<>();
        // title：同链 v(n+1) 若未传 title 应等于 v(n)，此处等价视为无差异
        if (!safeEq(from.getTitle(), to.getTitle())) {
            diffs.add(new FieldDiff("title", from.getTitle(), to.getTitle(), null, null));
        }
        // content：必产生新版本 ⇒ 必有差异
        if (!safeEq(from.getContent(), to.getContent())) {
            diffs.add(new FieldDiff("content", from.getContent(), to.getContent(),
                from.getContentSha256(), to.getContentSha256()));
        }
        // contentSha256：内容摘要差异（content 未变则摘要必同；列独立列便于审计溯源）
        if (!safeEq(from.getContentSha256(), to.getContentSha256())) {
            diffs.add(new FieldDiff("contentSha256",
                from.getContentSha256(), to.getContentSha256(), null, null));
        }
        return new DiffReport(from.getId(), from.getVersionNo(),
            to.getId(), to.getVersionNo(), diffs);
    }

    /**
     * 授权版本对比：校验项目可见性，且 from/to 必须同属路径文档链。
     *
     * @param documentId 路径上的文档版本 ID（用于解析链）
     * @param fromVersionId 对比起点版本行 ID
     * @param toVersionId 对比终点版本行 ID
     * @param actor 会话身份
     * @return 同链字段级 diff
     */
    public DiffReport diffAuthorized(Long documentId, Long fromVersionId, Long toVersionId,
                                     IpdActor actor) {
        requireArg(documentId != null, "documentId 必填");
        requireArg(actor != null && actor.id() != null, "actor 必填");
        List<AiDocument> chain = history(documentId);
        requireProjectReadable(actor, chain.get(0).getProjectId());
        boolean fromOnChain = chain.stream().anyMatch(r -> fromVersionId.equals(r.getId()));
        boolean toOnChain = chain.stream().anyMatch(r -> toVersionId.equals(r.getId()));
        if (!fromOnChain || !toOnChain) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }
        return diff(fromVersionId, toVersionId);
    }

    private static boolean safeEq(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * P1-3：按项目 ID 列 AI 文档链头（页14 项目详情-文档与交付物列表区）。
     * <p>仅返回每个文档链的最新版本（HEAD）行；按 create_time DESC 排序便于列表展示最新动态。
     * 与 {@link #history} 错位：列表区不展开每条链的全版本，由前端调 versions 端点按需加载。
     * <p>只读事务；{@code currentPersonId} 入参预留审计追踪位（与 controller 端 actor.id() 对齐），
     * 暂不做 IDOR 过滤（项目级查询码已限制为内部四角色，SEC-02 由 controller 注解拦截）。
     *
     * @param projectId      项目 ID（必填；由 controller 注解保证必填）
     * @param currentPersonId 当前会话人 ID（审计追踪位；预留后续接审计日志）
     * @return 项目下 AI 文档链头列表（按 create_time DESC）
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<AiDocument> listByProject(Long projectId, String currentPersonId) {
        requireArg(projectId != null, "projectId 必填");
        return mapper.selectList(Wrappers.<AiDocument>lambdaQuery()
            .eq(AiDocument::getProjectId, projectId)
            // 只取链头（parent_version_id IS NULL 即 v1），与 history 的全链视图错位
            .isNull(AiDocument::getParentVersionId)
            .orderByDesc(AiDocument::getCreateTime));
    }

    /**
     * 完整版本链（AC-AI-06：AI 原始输出 v1 + 全部人工修改版本，无一缺失）。
     * 自起点向上走到根（根必须 v1），再自根按父指针逐环下探，校验版本号连续；
     * 链断/跳号/起点不在链上（软删分支）→ STATE_CONFLICT。
     *
     * @return v1..vN 升序全链
     */
        @Cacheable(cacheNames = CacheNames.IPD_AI_DOC_CHAIN, key = "#documentId")
    public List<AiDocument> history(Long documentId) {
        // P1-10.3 / PERF：一次性递归 CTE 取全链（替代原 2N-1 次 SQL）
        List<AiDocument> chain = mapper.selectChain(documentId);
        if (chain == null || chain.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        // 校验版本号连续 + documentId 在链上（保留原 AC-AI-06 链完整性契约）
        if (chain.get(0).getVersionNo() == null || chain.get(0).getVersionNo() != 1) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }
        Long chainProjectId = chain.get(0).getProjectId();
        String chainDocType = chain.get(0).getDocType();
        for (int i = 0; i < chain.size(); i++) {
            AiDocument row = chain.get(i);
            if (i > 0 && row.getVersionNo() != chain.get(i - 1).getVersionNo() + 1) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
            }
            // 同链必须同项目、同文档类型；跨项目/跨类型父链即使版本号连续也视为断裂
            if (!java.util.Objects.equals(chainProjectId, row.getProjectId())
                || !java.util.Objects.equals(chainDocType, row.getDocType())) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
            }
        }
        boolean onChain = chain.stream().anyMatch(r -> documentId.equals(r.getId()));
        if (!onChain) throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        return chain;
    }

    /** 所有链写入先锁不可变链根，再以当前读找链头；普通CTE只用于确定根ID。 */
    private AiDocument lockedHead(Long documentId) {
        List<AiDocument> chain = mapper.selectChain(documentId);
        if (chain == null || chain.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        AiDocument current = mapper.lockVersion(chain.get(0).getId());
        if (current == null || current.getParentVersionId() != null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (seen.add(current.getId())) {
            AiDocument child = mapper.lockChild(current.getId());
            if (child == null) return current;
            if (!java.util.Objects.equals(current.getProjectId(), child.getProjectId())
                    || !java.util.Objects.equals(current.getDocType(), child.getDocType())
                    || child.getVersionNo() == null || current.getVersionNo() == null
                    || child.getVersionNo() != current.getVersionNo() + 1) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
            }
            current = child;
        }
        throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT);
    }

    /**
     * sha256(content) 十六进制摘要（64 字符，UTF-8）。
     */
    public static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }

    private static void requireArg(boolean ok, String message) {
        if (!ok) {
            throw new IpdBusinessException(message);
        }
    }
}
