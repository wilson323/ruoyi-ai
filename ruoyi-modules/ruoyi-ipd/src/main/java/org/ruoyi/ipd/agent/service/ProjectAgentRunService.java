package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.service.ProjectAgentRunPlanner.RunPlan;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews.ConfigSnapshot;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.AiDocumentService;
import org.ruoyi.ipd.service.IpdCopilotAccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 项目智能体运行服务（合同 #2–#5 + 产物 apply）。
 *
 * <p>权限：每次请求经 {@link IpdCopilotAccess#requireVisible} 重读 Person；运行只对发起人本人可见。
 * 产物 apply 只调 {@link AiDocumentService#createGeneratedAuthorized}，不改其审核语义。
 */
public class ProjectAgentRunService {

    private final boolean enabled;
    private final IpdCopilotAccess access;
    private final ProjectAgentRunPlanner planner;
    private final AgentRunStore store;
    private final ArtifactVersionStore artifactStore;
    private final AiDocumentService documentService;
    private final ProjectMapper projectMapper;
    private final ProductMapper productMapper;
    private final ProjectAgentRunExecutor executor;
    private final ObjectMapper mapper;
    private final LongSupplier clock;
    private final Duration runTimeout;

    /**
     * 生产构造（产物 apply + 可选产品线校验）。
     *
     * @param enabled 开关
     * @param access 访问守卫
     * @param planner 规划器
     * @param store 运行存储
     * @param artifactStore 产物版本存储
     * @param documentService 文档服务
     * @param projectMapper 项目 Mapper
     * @param productMapper 产品 Mapper
     * @param executor 执行器
     * @param mapper JSON
     * @param clock 时钟
     * @param runTimeout 超时
     */
    public ProjectAgentRunService(boolean enabled, IpdCopilotAccess access, ProjectAgentRunPlanner planner,
                                  AgentRunStore store, ArtifactVersionStore artifactStore,
                                  AiDocumentService documentService, ProjectMapper projectMapper,
                                  ProductMapper productMapper, ProjectAgentRunExecutor executor,
                                  ObjectMapper mapper, LongSupplier clock, Duration runTimeout) {
        this.enabled = enabled;
        this.access = Objects.requireNonNull(access, "access");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.store = Objects.requireNonNull(store, "store");
        this.artifactStore = artifactStore;
        this.documentService = documentService;
        this.projectMapper = projectMapper;
        this.productMapper = productMapper;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runTimeout = runTimeout == null ? Duration.ofMinutes(5) : runTimeout;
    }

    /**
     * 测试兼容构造（无产物 apply / 无产品线校验）。
     *
     * @param enabled 开关
     * @param access 访问守卫
     * @param planner 规划器
     * @param store 运行存储
     * @param executor 执行器
     * @param mapper JSON
     * @param clock 时钟
     * @param runTimeout 超时
     */
    public ProjectAgentRunService(boolean enabled, IpdCopilotAccess access, ProjectAgentRunPlanner planner,
                                  AgentRunStore store, ProjectAgentRunExecutor executor, ObjectMapper mapper,
                                  LongSupplier clock, Duration runTimeout) {
        this(enabled, access, planner, store, null, null, null, null, executor, mapper, clock, runTimeout);
    }

    /**
     * 创建运行（幂等：同人同键同请求返回原运行；同键不同请求体 STATE_CONFLICT）。
     *
     * @param actor 会话身份
     * @param projectId 路径项目
     * @param req 请求
     * @return runId + status
     */
    public ProjectAgentViews.RunStatus create(IpdActor actor, Long projectId, AgentRunCreateReq req) {
        requireEnabled();
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 必填");
        }
        String tenantId = access.requireVisible(actor, projectId);
        planner.validateShape(req);
        validateProductLineIfPresent(projectId, req.productLineId());
        String digest = ProjectAgentRunPlanner.requestDigest(projectId, req);
        IpdAgentRun existing = store.findByIdempotencyKey(tenantId, actor.id(), req.idempotencyKey()).orElse(null);
        if (existing != null) {
            return replay(existing, digest);
        }
        RunPlan plan = planner.plan(req);
        if (!executor.tryReserve()) {
            throw new IpdBusinessException(ApiV1ErrorCode.RATE_LIMITED, "项目智能体并发运行已满，请稍后重试");
        }
        IpdAgentRun run = newRun(actor, tenantId, projectId, req, plan, digest);
        if (!store.insertRun(run)) {
            executor.release();
            IpdAgentRun raced = store.findByIdempotencyKey(tenantId, actor.id(), req.idempotencyKey())
                .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行创建冲突，请重试"));
            return replay(raced, digest);
        }
        ProjectAgentViews.RunStatus created =
            new ProjectAgentViews.RunStatus(ProjectAgentViews.id(run.getId()), run.getStatus());
        executor.submit(run, new ProjectAgentRunSpec(run.getId(), projectId, tenantId, actor.id(),
            plan.actionCode(), req.message(), plan.skills(), plan.toolIds(), plan.model(), runTimeout));
        return created;
    }

    /**
     * 将产物最新 DRAFT 版本应用到项目文档并回写 documentId。
     *
     * @param actor 会话身份
     * @param runId 运行 ID
     * @param artifactId 逻辑产物 ID
     * @return apply 结果；indexStatus 如实返回，禁止伪造 READY
     */
    public ProjectAgentViews.ArtifactApply applyArtifact(IpdActor actor, Long runId, String artifactId) {
        requireEnabled();
        if (artifactStore == null || documentService == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物应用未装配");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "artifactId 必填");
        }
        IpdAgentRun run = requireOwnRun(actor, runId);
        IpdAgentArtifactVersion version = artifactStore.findLatest(runId, artifactId.trim())
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "产物版本不存在"));
        if (IpdAgentArtifactVersion.STATUS_APPLIED.equals(version.getStatus()) && version.getDocumentId() != null) {
            return appliedView(run, version, version.getDocumentId(), AiDocumentService.STATUS_GENERATED,
                ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
        }
        if (!IpdAgentArtifactVersion.STATUS_DRAFT.equals(version.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物版本不可应用");
        }
        String docType = run.getActionCode() == null || run.getActionCode().isBlank()
            ? "PROJECT_AGENT_ARTIFACT" : run.getActionCode().trim();
        AiDocument doc = documentService.createGeneratedAuthorized(actor, run.getProjectId(), docType,
            version.getTitle(), version.getContent(), null, null, null);
        if (!artifactStore.markApplied(version.getId(), doc.getId())) {
            IpdAgentArtifactVersion latest = artifactStore.findById(version.getId())
                .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物应用冲突，请重试"));
            if (latest.getDocumentId() == null) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物应用冲突，请重试");
            }
            return appliedView(run, latest, latest.getDocumentId(), AiDocumentService.STATUS_GENERATED,
                ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
        }
        return appliedView(run, version, doc.getId(), doc.getStatus(),
            ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
    }

    /**
     * 运行详情（仅发起人本人）。
     *
     * @param actor 会话身份
     * @param runId 运行 ID
     * @return 运行视图
     */
    public ProjectAgentViews.Run get(IpdActor actor, Long runId) {
        requireEnabled();
        return toView(requireOwnRun(actor, runId));
    }

    /**
     * 本人在该项目下的运行列表。空搜索词返回最近运行。
     *
     * @param actor 会话身份
     * @param projectId 项目
     * @param q 搜索词，可空
     * @param status 精确状态，可空
     * @param actionCode 精确动作，可空
     * @param cursor 上一页最后的 runId，可空
     * @param limit 条数
     * @return 摘要列表
     */
    public List<ProjectAgentViews.RunItem> list(IpdActor actor, Long projectId, String q, String status,
                                                String actionCode, Long cursor, Integer limit) {
        requireEnabled();
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 必填");
        }
        String tenantId = access.requireVisible(actor, projectId);
        return ProjectAgentRunListing.page(store, artifactStore, tenantId, projectId, actor.id(),
            status, actionCode, q, cursor, limit);
    }

    /**
     * 事件增量（seq &gt; afterSeq）。
     *
     * @param actor 会话身份
     * @param runId 运行 ID
     * @param afterSeq 游标
     * @return 事件页
     */
    public ProjectAgentViews.Events events(IpdActor actor, Long runId, Long afterSeq) {
        requireEnabled();
        long cursor = afterSeq == null ? 0L : afterSeq;
        if (cursor < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "afterSeq 不能为负数");
        }
        requireOwnRun(actor, runId);
        List<IpdAgentRunEvent> rows = store.listEvents(runId, cursor, ProjectAgentConstants.EVENTS_PAGE_LIMIT);
        long nextSeq = cursor;
        List<ProjectAgentViews.Event> events = new ArrayList<>(rows.size());
        for (IpdAgentRunEvent row : rows) {
            events.add(new ProjectAgentViews.Event(row.getSeq(), row.getEventType(), parse(row.getPayload()),
                ProjectAgentViews.iso(row.getCreateTime())));
            nextSeq = Math.max(nextSeq, row.getSeq());
        }
        long delivered = nextSeq;
        boolean terminal = store.terminalSeq(runId).map(seq -> seq <= delivered).orElse(false);
        return new ProjectAgentViews.Events(events, nextSeq, terminal);
    }

    /**
     * 取消运行。
     *
     * @param actor 会话身份
     * @param runId 运行 ID
     * @return runId + 当前状态
     */
    public ProjectAgentViews.RunStatus cancel(IpdActor actor, Long runId) {
        requireEnabled();
        IpdAgentRun run = requireOwnRun(actor, runId);
        AgentRunStatus status = AgentRunStatus.valueOf(run.getStatus());
        if (status == AgentRunStatus.PENDING && executor.finishPending(run, AgentRunStatus.CANCELLED, null)) {
            return currentStatus(runId);
        }
        status = AgentRunStatus.valueOf(reload(runId).getStatus());
        if (AgentRunStatus.CANCELLABLE.contains(status)) {
            store.transition(runId, EnumSet.of(status), AgentRunStatus.CANCEL_REQUESTED, null,
                new Date(clock.getAsLong()));
        }
        if (AgentRunStatus.CANCEL_REQUESTED.name().equals(reload(runId).getStatus())) {
            executor.handle(runId).ifPresent(ProjectAgentRunHandle::cancel);
        }
        return currentStatus(runId);
    }

    /**
     * 可选 productLineId：传了则经 Project→Product 校验归属；不传保持现状。
     *
     * @param projectId 路径项目
     * @param rawLineId 请求产品线 ID（可空）
     */
    private void validateProductLineIfPresent(Long projectId, String rawLineId) {
        if (rawLineId == null || rawLineId.isBlank()) {
            return;
        }
        if (projectMapper == null || productMapper == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产品线校验未装配");
        }
        Long lineId;
        try {
            lineId = Long.parseLong(rawLineId.trim());
        } catch (NumberFormatException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "productLineId 格式错误");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getProductId() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "项目未关联产品，无法校验产品线");
        }
        Product product = productMapper.selectById(project.getProductId());
        if (product == null || !lineId.equals(product.getProductLineId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "产品线与项目归属不符");
        }
    }

    private ProjectAgentViews.ArtifactApply appliedView(IpdAgentRun run, IpdAgentArtifactVersion version,
                                                        Long documentId, String documentStatus,
                                                        String indexStatus) {
        return new ProjectAgentViews.ArtifactApply(
            ProjectAgentViews.id(run.getId()),
            version.getArtifactId(),
            ProjectAgentViews.id(version.getId()),
            version.getVersionNo() == null ? 1 : version.getVersionNo(),
            ProjectAgentViews.id(documentId),
            documentStatus,
            indexStatus,
            AiDocumentService.statusLabel(documentStatus));
    }

    private ProjectAgentViews.RunStatus replay(IpdAgentRun existing, String digest) {
        if (!Objects.equals(existing.getRequestDigest(), digest)) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "幂等键已用于不同的请求");
        }
        return new ProjectAgentViews.RunStatus(ProjectAgentViews.id(existing.getId()), existing.getStatus());
    }

    private IpdAgentRun newRun(IpdActor actor, String tenantId, Long projectId, AgentRunCreateReq req,
                               RunPlan plan, String digest) {
        IpdAgentRun run = IpdAgentRun.builder()
            .tenantId(tenantId)
            .projectId(projectId)
            .personId(actor.id())
            .agentId(ProjectAgentConstants.AGENT_ID)
            .status(AgentRunStatus.PENDING.name())
            .actionCode(plan.actionCode())
            .capabilityPackCode(plan.pack().code())
            .capabilityPackVersion(plan.pack().version())
            .modelConfigId(plan.modelConfigId())
            .configSnapshot(toJson(plan.snapshot()))
            .idempotencyKey(req.idempotencyKey())
            .requestDigest(digest)
            .inputDigest(ProjectAgentSkillCatalog.sha256Hex(req.message()))
            .inputChars(req.message().length())
            .version(0)
            .delFlag("0")
            .build();
        run.setCreateTime(new Date(clock.getAsLong()));
        run.setCreateBy(actor.id());
        run.setUpdateBy(actor.id());
        return run;
    }

    private IpdAgentRun requireOwnRun(IpdActor actor, Long runId) {
        IpdAgentRun run = store.findRun(runId)
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "运行不存在"));
        String tenantId = access.requireVisible(actor, run.getProjectId());
        if (!Objects.equals(tenantId, run.getTenantId()) || !Objects.equals(actor.id(), run.getPersonId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "运行不存在");
        }
        return run;
    }

    private IpdAgentRun reload(Long runId) {
        return store.findRun(runId)
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "运行不存在"));
    }

    private ProjectAgentViews.RunStatus currentStatus(Long runId) {
        return new ProjectAgentViews.RunStatus(ProjectAgentViews.id(runId), reload(runId).getStatus());
    }

    private ProjectAgentViews.Run toView(IpdAgentRun run) {
        ConfigSnapshot snapshot = null;
        try {
            snapshot = run.getConfigSnapshot() == null ? null
                : mapper.readValue(run.getConfigSnapshot(), ConfigSnapshot.class);
        } catch (JsonProcessingException ignored) {
            // 快照损坏时返回 null，不阻断详情查询。
        }
        return new ProjectAgentViews.Run(ProjectAgentViews.id(run.getId()), ProjectAgentViews.id(run.getProjectId()),
            run.getAgentId(), run.getStatus(), run.getActionCode(), snapshot, run.getErrorCode(),
            ProjectAgentViews.iso(run.getCreateTime()), ProjectAgentViews.iso(run.getFinishedAt()));
    }

    private Object parse(String payload) {
        try {
            return payload == null ? Map.of() : mapper.readValue(payload, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR);
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, ProjectAgentConstants.REASON_DISABLED);
        }
    }
}
