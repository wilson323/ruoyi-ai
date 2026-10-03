package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.kernel.ProjectAgentPrompt;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import org.ruoyi.ipd.agent.model.AgentEventType;
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
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.AiDocumentService;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.ruoyi.ipd.service.StageActionService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.function.LongSupplier;

/**
 * 项目智能体运行服务（合同 #2–#5 + 产物 apply）。
 *
 * <p>权限：每次请求经 {@link IpdCopilotAccess#requireVisible} 重读 Person；运行只对发起人本人可见。
 * 产物 apply 只调 {@link AiDocumentService#createGeneratedAuthorized}，不改其审核语义。
 */
public class ProjectAgentRunService {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgentRunService.class);

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
    /** 定档时读取本次运行配置的 modelName；未注入则模型名传 null。 */
    private ProjectAgentModelCatalog modelCatalog;
    private org.ruoyi.ipd.mapper.ProductLineNameMapper productLineNames;
    private ProjectAgentAguiPauseResumeService aguiPauseResume;
    private ProjectAgentAguiPauseResumeService.TrustedGuard aguiResumeGuard;
    private org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactAccess artifactAccess;
    private org.springframework.transaction.support.TransactionTemplate verificationTransaction;

    /** 驻留态收口复用原数据库事务；不包围其他执行器的 REQUIRES_NEW 生命周期。 */
    public void setVerificationTransaction(org.springframework.transaction.support.TransactionTemplate transaction) {
        verificationTransaction = Objects.requireNonNull(transaction);
    }

    private <T> T inVerificationTransaction(java.util.function.Supplier<T> action) {
        return verificationTransaction == null ? action.get()
            : verificationTransaction.execute(status -> action.get());
    }

    private IpdAgentRun lockVerifyingRun(IpdActor actor, Long runId) {
        IpdAgentRun observed = requireOwnRun(actor, runId);
        IpdAgentRun run = observed;
        if (verificationTransaction != null) {
            // FOR UPDATE 返回当前行；不能在加锁后重新 selectById 读取事务一级缓存的旧快照。
            run = store.lockRunForVerification(runId)
                .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "运行不存在"));
            run = requireOwnRun(actor, run);
            if (run.getVersion() == null || !Objects.equals(observed.getVersion(), run.getVersion())) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行版本已变化，请刷新");
            }
        }
        if (!AgentRunStatus.VERIFYING.name().equals(run.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行不在校验驻留态");
        }
        return run;
    }

    /** 官方附件的原版本／来源守卫；缺失装配时禁止定档和下载。 */
    public void setArtifactAccess(org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactAccess access) {
        artifactAccess = Objects.requireNonNull(access);
    }

    /** 每次按真实 Person 和原运行重新验证权限，不接受客户端主机路径。 */
    public byte[] downloadArtifact(IpdActor actor, Long runId, Long versionId) {
        requireEnabled();
        requireOwnRun(actor, runId);
        return requireArtifactAccess().download(actor, runId, versionId);
    }

    private org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactAccess requireArtifactAccess() {
        if (artifactAccess == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物来源验证未装配");
        }
        return artifactAccess;
    }


    /** 服务器配置装配真实 SDK 检查点／业务批准校验，不接受浏览器注入。 */
    public void setAguiPauseResume(ProjectAgentAguiPauseResumeService service,
            ProjectAgentAguiPauseResumeService.TrustedGuard guard) {
        aguiPauseResume = Objects.requireNonNull(service);
        aguiResumeGuard = Objects.requireNonNull(guard);
    }

    /** 注入既有产品线服务绑定查询；兼容无数据库的测试装配。 */
    public void setProductLineNames(org.ruoyi.ipd.mapper.ProductLineNameMapper productLineNames) {
        this.productLineNames = productLineNames;
    }
    /** 定档后把文档记为动作交付物；未注入则只落文档。 */
    private StageActionService stageActionService;

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
     * 注入模型目录。不改构造签名；未注入时定档的模型名传 null。
     *
     * @param modelCatalog 已有模型目录，可为 null
     */
    public void setModelCatalog(ProjectAgentModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    /**
     * 注入阶段动作服务。定档成功后把文档记为该动作的交付物，不把动作标完成。
     *
     * @param stageActionService 阶段动作服务，可为 null
     */
    public void setStageActionService(StageActionService stageActionService) {
        this.stageActionService = stageActionService;
    }

    /**
     * 定档文档记入动作交付物。没有动作服务时跳过。
     */
    private void rememberDeliverable(IpdAgentRun run, String title, Long documentId, Long operatorId) {
        if (stageActionService == null || run == null || documentId == null) {
            return;
        }
        stageActionService.recordGeneratedDeliverable(
            run.getProjectId(), run.getActionCode(), title, documentId, operatorId);
    }

    /**
     * 创建运行（幂等：同人同键同请求返回原运行；同键不同请求体 STATE_CONFLICT）。
     *
     * @param actor 会话身份
     * @param projectId 路径项目
     * @param req 请求
     * @return runId + status
     */
    public ProjectAgentViews.RunStatus create(IpdActor actor, Long projectId, AgentRunCreateReq supplied) {
        requireEnabled();
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 必填");
        }
        String tenantId = access.requireVisible(actor, projectId);
        final AgentRunCreateReq req;
        try { req = planner.freezeRequest(supplied); }
        catch (IllegalArgumentException invalidInput) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "AG-UI 输入无法冻结");
        }
        planner.validateShape(req);
        Long requirementId = parseRequirementId(req.requirementId());
        if (requirementId != null && projectId.longValue() != DemandTriageRun.TRIAGE_PROJECT_ID) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "需求分拣只能在固定分拣项目中发起");
        }
        validateProductLineIfPresent(projectId, req.productLineId());
        String digest = ProjectAgentRunPlanner.requestDigest(projectId, req);
        IpdAgentRun existing = store.findByIdempotencyKey(tenantId, actor.id(), req.idempotencyKey()).orElse(null);
        if (existing != null) {
            return replay(existing, digest);
        }
        RunPlan plan = planner.plan(req);
        // 在占额度与落库前验证完整消息及真实前端工具授权；不能写库后才发现非法输入。
        var preparedAgui = org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.freeze(
            planner.bindAguiInput(req, plan, 0L));
        validateRework(actor, projectId, plan.actionCode(), req.previousRunId(), req.targetDocumentId(), req.baseVersionId());
        if (productLineNames != null) {
            planner.validateMcpSelection(productLineNames.selectServiceId(projectId), plan.toolIds());
        }
        String projectFacts = loadProjectFacts(projectId, plan.actionCode(), req.targetDocumentId(), req.baseVersionId());
        IpdAgentRun run = newRun(actor, tenantId, projectId, req, plan, digest);
        if (preparedAgui != null) {
            run.setConfigSnapshot(toJson(plan.snapshot().withServerFrontendTools(preparedAgui.getTools())));
        }
        // 在占用额度前校验完整内核输入；实际 ID 由插入运行时生成。
        new ProjectAgentRunSpec(0L, projectId, tenantId, actor.id(), plan.actionCode(), req.message(),
            plan.skills(), plan.toolIds(), plan.model(), runTimeout, requirementId).withProjectFacts(projectFacts)
            .withAguiInput(preparedAgui).withExecutionToolIds(plan.executionToolIds());
        if (!executor.tryReserve()) {
            throw new IpdBusinessException(ApiV1ErrorCode.RATE_LIMITED, "项目智能体并发运行已满，请稍后重试");
        }
        boolean inserted;
        try {
            inserted = executor.insertReservedRun(run);
        } catch (RuntimeException insertFailure) {
            executor.release();
            throw insertFailure;
        }
        if (!inserted) {
            executor.release();
            IpdAgentRun raced = store.findByIdempotencyKey(tenantId, actor.id(), req.idempotencyKey())
                .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行创建冲突，请重试"));
            return replay(raced, digest);
        }
        ProjectAgentViews.RunStatus created =
            new ProjectAgentViews.RunStatus(ProjectAgentViews.id(run.getId()), run.getStatus());
        executor.submitPrepared(run, () -> new ProjectAgentRunSpec(run.getId(), projectId, tenantId, actor.id(),
            plan.actionCode(), req.message(), plan.skills(), plan.toolIds(), plan.model(), runTimeout,
            requirementId)
            .withProjectFacts(projectFacts)
            .withExecutionToolIds(plan.executionToolIds())
            .withAguiInput(preparedAgui == null ? null : org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.bind(
                preparedAgui, String.valueOf(run.getId()), String.valueOf(run.getId()),
                preparedAgui.getTools().stream().collect(java.util.stream.Collectors.toMap(
                    io.agentscope.core.agui.model.AguiTool::getName, java.util.function.Function.identity())))));
        return created;
    }

    /**
     * 将产物最新 DRAFT 版本应用到项目文档并回写 documentId。
     *
     * <p>未绑定动作，或 {@link ActionCatalog#docTypeOf} 无归类时拒绝，不写文档。
     * 事务内锁定最新版本：已定档则回读已有 documentId，不再是 DRAFT 则不建文档。
     * 条件更新失败回滚本次新建文档。模型名与 token 取本次运行的真实调用；没有则传 null。
     * 状态仍是 GENERATED，索引仍是 NOT_INDEXED。
     *
     * @param actor 会话身份
     * @param runId 运行 ID
     * @param artifactId 逻辑产物 ID
     * @return apply 结果；indexStatus 如实返回，禁止伪造 READY
     */
    // 等待版本行锁后须读见前一事务刚提交的文档及交付物，不能沿用加锁前的RR快照。
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProjectAgentViews.ArtifactApply applyArtifact(IpdActor actor, Long runId, String artifactId) {
        requireEnabled();
        if (artifactStore == null || documentService == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物应用未装配");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "artifactId 必填");
        }
        IpdAgentRun run = requireOwnRun(actor, runId);
        String trimmed = artifactId.trim();
        IpdAgentArtifactVersion seen = artifactStore.findLatestForUpdate(run.getTenantId(), runId, trimmed)
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "产物版本不存在"));
        if (IpdAgentArtifactVersion.STATUS_APPLIED.equals(seen.getStatus()) && seen.getDocumentId() != null) {
            return replayApplied(run, seen, actor.id());
        }
        if (!IpdAgentArtifactVersion.STATUS_DRAFT.equals(seen.getStatus()) || seen.getId() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物版本不可应用");
        }
        requireArtifactAccess().requireDocumentContent(actor, runId, seen);
        String docType = requireArchiveDocType(run);
        return applyDraft(actor, run, seen, docType);
    }

    /**
     * 在数据库版本锁持有的事务内定档；文档创建、关联和交付物登记一起提交。
     *
     * @param actor 会话身份
     * @param run 本次运行
     * @param current 事务内已锁定的最新版本
     * @param docType 目录归类
     * @return apply 结果
     */
    private ProjectAgentViews.ArtifactApply applyDraft(IpdActor actor, IpdAgentRun run,
                                                       IpdAgentArtifactVersion current, String docType) {
        if (IpdAgentArtifactVersion.STATUS_APPLIED.equals(current.getStatus()) && current.getDocumentId() != null) {
            return replayApplied(run, current, actor.id());
        }
        if (!IpdAgentArtifactVersion.STATUS_DRAFT.equals(current.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物版本不可应用");
        }
        RunUsage usage = usageOf(run.getId());
        ConfigSnapshot snapshot = frozenSnapshot(run);
        AiDocument doc;
        if (snapshot != null && snapshot.previousRunId() != null) {
            validateRework(actor, run.getProjectId(), run.getActionCode(), snapshot.previousRunId(),
                snapshot.targetDocumentId(), snapshot.baseVersionId());
            doc = documentService.reviseGeneratedAuthorized(actor, run.getProjectId(), docType,
                reworkId(snapshot.targetDocumentId()), reworkId(snapshot.baseVersionId()),
                current.getTitle(), current.getContent(), modelName(run), usage.promptTokens(), usage.completionTokens());
        } else {
            doc = documentService.createGeneratedAuthorized(actor, run.getProjectId(), docType,
                current.getTitle(), current.getContent(), modelName(run), usage.promptTokens(), usage.completionTokens());
        }
        if (!artifactStore.markApplied(current.getId(), doc.getId())) {
            // 抛出冲突让本事务回滚新建文档；下一次请求在新事务中回读实际关联。
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物应用冲突，请重试");
        }
        rememberDeliverable(run, current.getTitle(), doc.getId(), actor.id());
        return appliedView(run, current, doc.getId(), doc.getStatus(),
            ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
    }

    /**
     * 回读已经定档的文档，不新建。
     *
     * @param run 本次运行
     * @param version 已带 documentId 的版本
     * @param operatorId 操作人
     * @return apply 结果
     */
    private ProjectAgentViews.ArtifactApply replayApplied(IpdAgentRun run, IpdAgentArtifactVersion version,
                                                          Long operatorId) {
        rememberDeliverable(run, version.getTitle(), version.getDocumentId(), operatorId);
        return appliedView(run, version, version.getDocumentId(), readArchiveStatus(version.getDocumentId()),
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

    /** 只读重建服务器冻结配置；额度、原 run 租约与消费 CAS 必须由执行器先取得。 */
    public ProjectAgentRunSpec prepareAguiResume(IpdActor actor, Long runId,
            io.agentscope.core.agui.model.RunAgentInput input) {
        return prepareAguiConfiguration(actor,runId,input,false);
    }
    /** 只读服务端 guard；状态不授予执行权，仍需原租约与私有意图。 */
    public ProjectAgentRunSpec prepareAguiPreflight(IpdActor actor,Long runId,io.agentscope.core.agui.model.RunAgentInput input) {
        var run=requireOwnRun(actor,runId);
        return prepareAguiConfiguration(actor,runId,input,AgentRunStatus.RUNNING.name().equals(run.getStatus()));
    }
    private ProjectAgentRunSpec prepareAguiConfiguration(IpdActor actor,Long runId,
            io.agentscope.core.agui.model.RunAgentInput input,boolean recovery) {
        requireEnabled();
        IpdAgentRun run = requireOwnRun(actor, runId);
        if (!(recovery?AgentRunStatus.RUNNING:AgentRunStatus.WAITING_APPROVAL).name().equals(run.getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "当前运行没有等待回答的中断");
        }
        if (input == null || !String.valueOf(runId).equals(input.getRunId())
                || !String.valueOf(runId).equals(input.getThreadId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "恢复响应与原运行不匹配");
        }
        ConfigSnapshot snapshot = frozenSnapshot(run);
        if (snapshot == null || snapshot.skills() == null || snapshot.toolIds() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "原运行配置无法恢复");
        }
        AgentRunCreateReq frozen = new AgentRunCreateReq(snapshot.capabilityPackCode(), snapshot.capabilityPackVersion(),
            snapshot.modelConfigId(), snapshot.skills().stream().map(ProjectAgentViews.SkillRef::name).toList(),
            snapshot.toolIds(), run.getActionCode(), "", run.getIdempotencyKey(), snapshot.productLineId(),
            snapshot.requirementId(), snapshot.previousRunId(), snapshot.targetDocumentId(), snapshot.baseVersionId());
        RunPlan plan = planner.plan(frozen);
        if (!snapshot.skills().equals(plan.snapshot().skills()) || !snapshot.toolIds().equals(plan.toolIds())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "原运行的技能或工具配置已改变");
        }
        validateProductLineIfPresent(run.getProjectId(), snapshot.productLineId());
        validateRework(actor, run.getProjectId(), run.getActionCode(), snapshot.previousRunId(),
            snapshot.targetDocumentId(), snapshot.baseVersionId());
        if (productLineNames != null) planner.validateMcpSelection(
            productLineNames.selectServiceId(run.getProjectId()), plan.toolIds());
        return new ProjectAgentRunSpec(runId, run.getProjectId(), run.getTenantId(), actor.id(), run.getActionCode(),
            "", plan.skills(), plan.toolIds(), plan.model(), runTimeout, parseRequirementId(snapshot.requirementId()))
            .withProjectFacts(loadProjectFacts(run.getProjectId(), run.getActionCode(), snapshot.targetDocumentId(), snapshot.baseVersionId()))
            .withAguiInput(org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.freeze(input))
            .withExecutionToolIds(snapshot.executionToolIds());
    }

    /** 原失联恢复器入口：重核当前权限、原检查点及尚未开始的工具效果。 */
    public boolean recoverAguiIntent(IpdActor actor,Long runId) {
        requireEnabled();
        if(aguiPauseResume==null || aguiResumeGuard==null) throw new IllegalStateException("原恢复服务未装配");
        IpdAgentRun run=requireOwnRun(actor,runId);
        if(!AgentRunStatus.RUNNING.name().equals(run.getStatus())) return false;
        executor.recoverPrepared(run,handle-> {
            var recovered=aguiPauseResume.loadLatestConsumedIntent(handle,runId);
            var input=org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.decodeRecoveryInput(recovered.intent().normalizedInputJson());
            var prepared=prepareAguiConfiguration(actor,runId,input,true);
            aguiResumeGuard.validate(actor,store.findRun(runId).orElseThrow(),recovered.pause(),input);
            aguiPauseResume.authorizeRecoveredIntent(handle,runId,recovered);
            return prepared.withServerResumeMessages(recovered.messages()).withServerChildResumes(recovered.childResumes());
        });
        return true;
    }

    /** 原运行的中断响应；先由执行器取得额度与租约，最后消费原检查点。 */
    public ProjectAgentViews.RunStatus resume(IpdActor actor, Long runId, Long expectedPauseSeq,
            io.agentscope.core.agui.model.RunAgentInput supplied) {
        requireEnabled();
        IpdAgentRun run = requireOwnRun(actor, runId);
        if (expectedPauseSeq == null || expectedPauseSeq < 1 || supplied == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "中断响应不完整");
        }
        if (aguiPauseResume == null || aguiResumeGuard == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行恢复服务尚未装配");
        }
        final io.agentscope.core.agui.model.RunAgentInput input;
        try { input = org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.freeze(supplied); }
        catch (IllegalArgumentException invalidInput) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "中断响应无法读取");
        }
        // 消费完成的同一响应可安全重试；不同回答不能覆盖原结果。
        if (aguiPauseResume.replayConsumed(actor, runId, expectedPauseSeq, input)) return currentStatus(runId);
        try {
            executor.resumePrepared(run, handle -> {
                ProjectAgentRunSpec prepared = prepareAguiResume(actor, runId, input);
                var consumed = aguiPauseResume.consume(actor, handle, runId, expectedPauseSeq, input, aguiResumeGuard);
                if (!consumed.consumed()) throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "中断已回答");
                return prepared.withAguiInput(consumed.input()).withServerResumeMessages(consumed.messages())
                    .withServerChildResumes(consumed.childResumes());
            });
        } catch (ProjectAgentRunOwnership.OwnershipLost competingOwner) {
            // 再鉴权后只回读同一响应的已消费证据，不能重放工具或覆盖另一次回答。
            requireOwnRun(actor, runId);
            if (aguiPauseResume.replayConsumed(actor, runId, expectedPauseSeq, input)) return currentStatus(runId);
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行正在恢复，请重新读取当前状态");
        }
        return currentStatus(runId);
    }

    /** Guard 只能获取服务器重新授权的前端工具 schema，不能用请求定义冒充目录。 */
    public java.util.Map<String, io.agentscope.core.agui.model.AguiTool> resolveAguiResumeTools(
            IpdActor actor, Long runId, io.agentscope.core.agui.model.RunAgentInput input) {
        prepareAguiPreflight(actor, runId, input);
        IpdAgentRun run = requireOwnRun(actor, runId);
        ConfigSnapshot snapshot = frozenSnapshot(run);
        var req = new AgentRunCreateReq(snapshot.capabilityPackCode(), snapshot.capabilityPackVersion(),
            snapshot.modelConfigId(), snapshot.skills().stream().map(ProjectAgentViews.SkillRef::name).toList(),
            snapshot.toolIds(), run.getActionCode(), "", run.getIdempotencyKey(), snapshot.productLineId(),
            snapshot.requirementId(), snapshot.previousRunId(), snapshot.targetDocumentId(), snapshot.baseVersionId());
        var frozenTools = snapshot.serverFrontendTools();
        if (!input.getTools().isEmpty()) {
            var requested = input.getTools().stream().map(io.agentscope.core.agui.model.AguiTool::getName)
                .collect(java.util.stream.Collectors.toSet());
            var original = frozenTools.stream().map(io.agentscope.core.agui.model.AguiTool::getName)
                .collect(java.util.stream.Collectors.toSet());
            if (requested.size() != input.getTools().size() || !requested.equals(original)) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "恢复不能改变原运行的前端工具");
            }
        }
        var effective = org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.withFrontendTools(input, frozenTools);
        var catalog = planner.authorizedAguiFrontendTools(planner.plan(req), effective);
        try {
            var canonical = org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.freeze(
                org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.bind(effective, String.valueOf(runId),
                    String.valueOf(runId), catalog));
            if (!org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.frontendToolsDigest(frozenTools).equals(
                    org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.frontendToolsDigest(canonical.getTools()))) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "原运行的前端工具定义已改变");
            }
            return canonical.getTools().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                io.agentscope.core.agui.model.AguiTool::getName, java.util.function.Function.identity()));
        } catch (IllegalArgumentException invalidInput) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "前端工具不符合本次授权范围");
        }
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
            events.add(ProjectAgentAguiPublicEvent.project(mapper, new ProjectAgentViews.Event(
                row.getSeq(), row.getEventType(), parse(row.getPayload()), ProjectAgentViews.iso(row.getCreateTime()))));
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
        IpdAgentRun current = reload(runId);
        status = AgentRunStatus.valueOf(current.getStatus());
        if (status == AgentRunStatus.VERIFYING) {
            // 校验驻留态无执行器写入权，取消直接落终态，不经 CANCEL_REQUESTED。
            return inVerificationTransaction(() -> {
                IpdAgentRun locked = lockVerifyingRun(actor, runId);
                finishVerifying(locked, AgentRunStatus.CANCELLED, null);
                return currentStatus(runId);
            });
        }
        boolean detachedAguiPause = hasDurableAguiPause(current);
        if (AgentRunStatus.CANCELLABLE.contains(status)) {
            store.transition(runId, EnumSet.of(status), AgentRunStatus.CANCEL_REQUESTED, null,
                new Date(clock.getAsLong()));
        }
        if (AgentRunStatus.CANCEL_REQUESTED.name().equals(reload(runId).getStatus())) {
            var handle = executor.handle(runId);
            if (handle.isPresent()) handle.get().cancel();
            else if (detachedAguiPause) {
                try { executor.finishDetachedCancellation(reload(runId)); }
                catch (ProjectAgentRunOwnership.OwnershipLost busy) {
                    // 当前 owner 负责已持久化的取消请求；竞争方只回读状态，不重复清理。
                }
            }
        }
        return currentStatus(runId);
    }

    /**
     * 授权后读取项目名、阶段和产品名。同一动作的文档链头若已退回，附上该版意见。
     * 无项目映射时返回空，不把本次用户原话写进事实。
     *
     * @param projectId 已通过可见性校验的项目
     * @param actionCode 本次规划动作
     * @return 项目事实；链头已退回时多一行意见
     */
    private String loadProjectFacts(Long projectId, String actionCode, String targetDocumentId, String baseVersionId) {
        String reworkComment = targetDocumentId == null ? null : commentForRework(documentService,
            projectId, ActionCatalog.docTypeOf(actionCode), reworkId(targetDocumentId), reworkId(baseVersionId));
        if (projectMapper == null) {
            return appendRejectedComment(null, reworkComment);
        }
        Project project = projectMapper.selectById(projectId);
        String name = null;
        String stage = null;
        String productName = null;
        if (project != null) {
            name = project.getName();
            stage = project.getCurrentStage();
            if (project.getProductId() != null && productMapper != null) {
                Product product = productMapper.selectById(project.getProductId());
                if (product != null) {
                    productName = product.getProductName();
                }
            }
        }
        String facts = ProjectAgentPrompt.renderProjectFacts(name, stage, productName, actionCode);
        if (documentService == null) {
            return facts;
        }
        return appendRejectedComment(facts, targetDocumentId == null
            ? commentForAction(documentService, projectId, ActionCatalog.docTypeOf(actionCode)) : reworkComment);
    }

    /**
     * 同一文档类型只认最新链头。链头是已退回且写了意见时返回该意见。
     * 链头仍待审核、已审核或已归档时不带回旧意见。读链失败不编造意见。
     *
     * @param documents 文档服务
     * @param projectId 项目
     * @param docType 动作目录归类；空则不查
     * @return 链头退回意见；没有则为 null
     */
    static String commentForRework(AiDocumentService documents, Long projectId, String docType,
                                    Long targetDocumentId, Long baseVersionId) {
        AiDocument head = documents == null ? null : chainHead(documents, targetDocumentId);
        if (head == null || !Objects.equals(head.getProjectId(), projectId)
            || !Objects.equals(head.getDocType(), docType) || !Objects.equals(head.getId(), baseVersionId)) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "原文档已变化，请重新选择要修订的版本");
        }
        return AiDocumentService.STATUS_REJECTED.equals(head.getStatus()) ? head.getReviewComment() : null;
    }

    static String commentForAction(AiDocumentService documents, Long projectId, String docType) {
        if (documents == null || projectId == null || docType == null || docType.isBlank()) {
            return null;
        }
        List<AiDocument> roots;
        try {
            roots = documents.listByProject(projectId, null);
        } catch (IpdBusinessException ex) {
            return null;
        }
        if (roots == null || roots.isEmpty()) {
            return null;
        }
        AiDocument latest = null;
        for (AiDocument root : roots) {
            if (root == null || root.getId() == null || !docType.equals(root.getDocType())) {
                continue;
            }
            AiDocument head = chainHead(documents, root.getId());
            if (head != null && newerHead(head, latest)) {
                latest = head;
            }
        }
        if (latest == null || !AiDocumentService.STATUS_REJECTED.equals(latest.getStatus())) {
            return null;
        }
        String comment = latest.getReviewComment();
        if (comment == null || comment.isBlank()) {
            return null;
        }
        return comment.trim();
    }

    /**
     * 把退回意见接在项目事实后。意见压成一行，避免撑破事实格式。
     *
     * @param facts 已格式化的项目事实，可空
     * @param comment 链头退回意见，可空
     * @return 原事实，或追加一行后的事实
     */
    static String appendRejectedComment(String facts, String comment) {
        if (comment == null || comment.isBlank()) {
            return facts;
        }
        String line = "退回意见：" + comment.trim().replace('\r', ' ').replace('\n', ' ');
        if (facts == null || facts.isBlank()) {
            return line + "\n";
        }
        return facts.endsWith("\n") ? facts + line + "\n" : facts + "\n" + line + "\n";
    }

    /**
     * 从 v1 根走到当前链头。列表接口只返回根行，退回意见写在被退回的那一版。
     *
     * @param documents 文档服务
     * @param rootId v1 根行
     * @return 链上最后一版；链读失败时为 null
     */
    private static AiDocument chainHead(AiDocumentService documents, Long rootId) {
        try {
            List<AiDocument> chain = documents.history(rootId);
            if (chain == null || chain.isEmpty()) {
                return null;
            }
            return chain.get(chain.size() - 1);
        } catch (IpdBusinessException ex) {
            return null;
        }
    }

    /**
     * 较新的链头优先。时间相同则用较大的文档 ID。
     *
     * @param candidate 候选链头
     * @param current 当前选中；首次比较时为 null
     * @return candidate 应替换 current
     */
    private static boolean newerHead(AiDocument candidate, AiDocument current) {
        if (current == null) {
            return true;
        }
        Date left = candidate.getCreateTime();
        Date right = current.getCreateTime();
        if (left != null && right != null && !left.equals(right)) {
            return left.after(right);
        }
        if (left != null && right == null) {
            return true;
        }
        if (left == null && right != null) {
            return false;
        }
        Long leftId = candidate.getId();
        Long rightId = current.getId();
        if (leftId == null) {
            return false;
        }
        return rightId == null || leftId > rightId;
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

    /**
     * 定档文档类型只取动作目录归类。空白或无归类时拒绝，不把动作码当成文档类型。
     *
     * @param run 本次运行
     * @return 原型文档类型
     */
    private static String requireArchiveDocType(IpdAgentRun run) {
        String action = run.getActionCode() == null ? "" : run.getActionCode().trim();
        if (action.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "未绑定动作，不能定档");
        }
        String docType = ActionCatalog.docTypeOf(action);
        if (docType == null || docType.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该动作没有可定档的文档类型");
        }
        return docType;
    }

    /**
     * 本次运行配置的模型名。目录未注入、配置不存在或名为空时返回 null。
     *
     * @param run 本次运行
     * @return modelName，没有则 null
     */
    private String modelName(IpdAgentRun run) {
        if (modelCatalog == null || run.getModelConfigId() == null) {
            return null;
        }
        String name = modelCatalog.modelNameOf(run.getModelConfigId());
        if (name == null || name.isBlank()) {
            return null;
        }
        return name;
    }

    /**
     * 累加本次运行 STEP 且 kind=MODEL_CALL 事件上的 token。翻完分页。
     * 没有 inputTokens / outputTokens 的一侧保持 null，不写成 0。
     *
     * @param runId 运行 ID
     * @return prompt 与 completion token
     */
    private RunUsage usageOf(Long runId) {
        long prompt = 0L;
        long completion = 0L;
        boolean seenPrompt = false;
        boolean seenCompletion = false;
        long after = 0L;
        int limit = ProjectAgentConstants.EVENTS_PAGE_LIMIT;
        while (true) {
            List<IpdAgentRunEvent> page = store.listEvents(runId, after, limit);
            if (page == null || page.isEmpty()) {
                break;
            }
            long maxSeq = after;
            for (IpdAgentRunEvent row : page) {
                if (row == null) {
                    continue;
                }
                if (row.getSeq() != null && row.getSeq() > maxSeq) {
                    maxSeq = row.getSeq();
                }
                if (!AgentEventType.STEP.name().equals(row.getEventType())) {
                    continue;
                }
                Map<String, Object> payload = asMap(parse(row.getPayload()));
                if (!"MODEL_CALL".equals(payload.get("kind"))) {
                    continue;
                }
                Long input = tokenOrNull(payload.get("inputTokens"));
                Long output = tokenOrNull(payload.get("outputTokens"));
                if (input != null) {
                    seenPrompt = true;
                    prompt = addToken(prompt, input);
                }
                if (output != null) {
                    seenCompletion = true;
                    completion = addToken(completion, output);
                }
            }
            if (page.size() < limit || maxSeq <= after) {
                break;
            }
            after = maxSeq;
        }
        return new RunUsage(fitToken(prompt, seenPrompt), fitToken(completion, seenCompletion));
    }

    /**
     * 重复定档只读已落库文档的状态。行不存在时不把结果写成待审核。
     *
     * @param documentId 产物已绑定的文档
     * @return 库内状态
     */
    private String readArchiveStatus(Long documentId) {
        return documentService.statusOf(documentId)
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "定档文档不存在"));
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

    /**
     * 需求单 ID 可空。有值时必须是十进制整数，不接受别的写法。
     *
     * @param raw 请求里的需求单 ID
     * @return 解析后的 ID；空白为 null
     */
    private static Long parseRequirementId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "requirementId 格式错误");
        }
    }

    /** 返工必须明确指向本人的已结束运行和其已定档文档，不能按标题/类型猜链。 */
    private void validateRework(IpdActor actor, Long projectId, String actionCode,
                                String previousRunId, String targetDocumentId, String baseVersionId) {
        if (previousRunId == null && targetDocumentId == null && baseVersionId == null) return;
        Long previousId = reworkId(previousRunId);
        Long targetId = reworkId(targetDocumentId);
        reworkId(baseVersionId);
        IpdAgentRun previous = requireOwnRun(actor, previousId);
        if (!Objects.equals(previous.getProjectId(), projectId)
            || !Objects.equals(previous.getActionCode(), actionCode)
            || !AgentRunStatus.valueOf(previous.getStatus()).isTerminal()) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "返工须关联本项目同一动作的已结束运行");
        }
        if (artifactStore == null || artifactStore.listByRunIds(List.of(previousId)).stream().noneMatch(
            artifact -> IpdAgentArtifactVersion.STATUS_APPLIED.equals(artifact.getStatus())
                && Objects.equals(targetId, artifact.getDocumentId()))) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "返工文档不属于关联运行的已定档产物");
        }
    }

    private static Long reworkId(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]*")) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "返工须完整提供关联运行、文档和基准版本");
        }
        try { return Long.valueOf(raw); }
        catch (NumberFormatException invalid) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "返工关联编号格式错误");
        }
    }

    private ConfigSnapshot frozenSnapshot(IpdAgentRun run) {
        if (run.getConfigSnapshot() == null) return null;
        try { return mapper.readValue(run.getConfigSnapshot(), ConfigSnapshot.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行冻结配置不可读取");
        }
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
            .inputDigest(req.aguiInput() == null ? ProjectAgentSkillCatalog.sha256Hex(req.message())
                : org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.digest(req.aguiInput()))
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
        return requireOwnRun(actor, run);
    }

    private IpdAgentRun requireOwnRun(IpdActor actor, IpdAgentRun run) {
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

    /**
     * 校验驻留态复检（Quality 域 V-2）：只对本人 VERIFYING 运行重跑机器校验。
     * 无 BLOCK 缺口即转 SUCCEEDED；仍有缺口保持 VERIFYING（幂等，可重复复检）。
     *
     * @param actor 会话身份
     * @param runId 运行 ID
     * @return runId + 当前状态
     */
    public ProjectAgentViews.RunStatus reverify(IpdActor actor, Long runId) {
        return inVerificationTransaction(() -> reverifyLocked(actor, runId));
    }

    private ProjectAgentViews.RunStatus reverifyLocked(IpdActor actor, Long runId) {
        requireEnabled();
        if (artifactStore == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物校验未装配");
        }
        IpdAgentRun run = lockVerifyingRun(actor, runId);
        IpdAgentArtifactVersion latest = latestArtifact(runId);
        if (latest == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "校验驻留态缺少产物版本");
        }
        ProjectAgentArtifactVerifier.Verdict verdict =
            new ProjectAgentArtifactVerifier().evaluate(run.getActionCode(), latest.getContent());
        // 竞态收窄：校验与写 STEP 之间可能并发取消；写前二次确认仍驻留，避免终态事件后出现孤儿 STEP。
        if (verificationTransaction == null && !AgentRunStatus.VERIFYING.name().equals(reload(runId).getStatus())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行状态已变化，请刷新");
        }
        // 复检证据先行、终态事件最后：终态事件出现后前端停止轮询，其后写入的事件不再送达。
        appendVerifyingStep(run, verdict);
        if (!verdict.hasBlockingGaps()) {
            finishVerifying(run, AgentRunStatus.SUCCEEDED, latest.getContent());
        }
        return currentStatus(runId);
    }

    /** VERIFYING 收口：CAS 迁移 + 终态事件 + 成功时补需求回写；状态被并发改变时拒绝。 */
    private void finishVerifying(IpdAgentRun run, AgentRunStatus target, String successBody) {
        if (!store.transition(run.getId(), EnumSet.of(AgentRunStatus.VERIFYING), target, null,
            new Date(clock.getAsLong()))) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行状态已变化，请刷新");
        }
        if (target == AgentRunStatus.SUCCEEDED) {
            bindDemandAfterReverify(run, successBody);
        }
        appendTerminalEvent(run, AgentEventType.RUN_FINISHED, Map.of("status", target.name()));
    }

    /**
     * 终态事件写入：seq 撞唯一键时按新鲜 maxSeq 有界重试。CAS 已提交后写不进是
     * 「终态无终态事件」的不可自愈态（前端按终态事件停轮询），重试耗尽抛错，由原事务回滚状态、复检事件和需求回写。
     */
    private void appendTerminalEvent(IpdAgentRun run, AgentEventType type, Map<String, Object> payload) {
        for (int attempt = 0; attempt < 3; attempt++) {
            IpdAgentRunEvent event = ProjectAgentRunEvents.of(run.getId(), run.getTenantId(), run.getPersonId(),
                store.maxSeq(run.getId()) + 1, type, toJson(payload), new Date(clock.getAsLong()));
            if (store.appendEvent(event)) {
                return;
            }
        }
        throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "运行结果暂未保存，请重试");
    }

    /**
     * 复检收口成功后补做需求回写。首跑路径在执行句柄的 SUCCEEDED 回调触发；VERIFYING
     * 驻留销毁句柄后由这里按冻结快照重建上下文（hit 置空，回写器内按全文兜底），失败回滚复检事务。
     */
    private void bindDemandAfterReverify(IpdAgentRun run, String successBody) {
        ConfigSnapshot snapshot = frozenSnapshot(run);
        String requirementId = snapshot == null ? null : snapshot.requirementId();
        if (requirementId != null && successBody != null) {
            executor.bindDemandOnReverify(Long.valueOf(requirementId), successBody);
        }
    }

    /** 复检结果挂 STEP，与首次校验（RunHandle#writeVerifyGapsStep）同构，recheck=true 区分。 */
    private void appendVerifyingStep(IpdAgentRun run, ProjectAgentArtifactVerifier.Verdict verdict) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "VERIFY_GAPS");
        payload.put("verdict", verdict.verdict());
        payload.put("title", "产物校验复检");
        payload.put("detail", "复检结论：" + verdict.summary());
        List<Map<String, Object>> checks = new ArrayList<>(verdict.gaps().size());
        for (ProjectAgentArtifactVerifier.Gap gap : verdict.gaps()) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("id", gap.id());
            check.put("status", gap.status());
            check.put("severity", gap.severity().name());
            check.put("evidencePath", gap.evidencePath());
            check.put("gapSummary", gap.gapSummary());
            checks.add(check);
        }
        payload.put("checks", checks);
        payload.put("recheck", true);
        appendEvent(run, AgentEventType.STEP, payload);
    }

    private void appendEvent(IpdAgentRun run, AgentEventType type, Map<String, Object> payload) {
        IpdAgentRunEvent event = ProjectAgentRunEvents.of(run.getId(), run.getTenantId(), run.getPersonId(),
            store.maxSeq(run.getId()) + 1, type, toJson(payload), new Date(clock.getAsLong()));
        if (!store.appendEvent(event)) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "事件序号冲突，请重试");
        }
    }

    /** 校验驻留态产物行取 versionNo 最大（并列取 id 最大）；无行返回 null。 */
    private IpdAgentArtifactVersion latestArtifact(Long runId) {
        IpdAgentArtifactVersion latest = null;
        for (IpdAgentArtifactVersion row : artifactStore.listByRunIds(List.of(runId))) {
            if (latest == null || row.getVersionNo() > latest.getVersionNo()
                || (row.getVersionNo().intValue() == latest.getVersionNo().intValue()
                    && row.getId() > latest.getId())) {
                latest = row;
            }
        }
        return latest;
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
            ProjectAgentViews.iso(run.getCreateTime()), ProjectAgentViews.iso(run.getFinishedAt()),
            artifactStore == null ? List.of() : artifactStore.listByRunIds(List.of(run.getId())).stream()
                .filter(a -> IpdAgentArtifactVersion.STATUS_APPLIED.equals(a.getStatus()) && a.getDocumentId() != null)
                .map(a -> new ProjectAgentViews.ArtifactArchive(a.getArtifactId(), ProjectAgentViews.id(a.getDocumentId())))
                .toList(), waitingPauseSeq(run));
    }

    /** 只对当前等待状态回读原持久等待序号；历史等待不能截断原运行回放。 */
    /** 只有原运行已提交的 AG-UI 暂停凭据允许无本机句柄的取消收口。 */
    private boolean hasDurableAguiPause(IpdAgentRun run) {
        Long pauseSeq = waitingPauseSeq(run);
        if (pauseSeq == null) return false;
        var rows = store.listEvents(run.getId(), pauseSeq - 1, 1);
        if (rows.size() != 1 || rows.get(0).getSeq() != pauseSeq) return false;
        try {
            var data = mapper.readTree(rows.get(0).getPayload());
            return "AGUI_INTERRUPT".equals(data.path("reason").asText())
                && data.path("pauseEpoch").asLong() > 0
                && data.path("checkpointVersion").asLong(-1) >= 0
                && String.valueOf(run.getId()).equals(data.path("runId").asText())
                && String.valueOf(run.getId()).equals(data.path("threadId").asText())
                && String.valueOf(run.getPersonId()).equals(data.path("ownerPersonId").asText())
                && data.path("interrupts").isObject() && !data.path("interrupts").isEmpty();
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("持久中断检查点无法读取", invalid);
        }
    }

    private Long waitingPauseSeq(IpdAgentRun run) {
        if (!AgentRunStatus.WAITING_APPROVAL.name().equals(run.getStatus())) return null;
        Long pending = null;
        long cursor = 0;
        while (true) {
            var page = store.listEvents(run.getId(), cursor, ProjectAgentConstants.EVENTS_PAGE_LIMIT);
            if (page.isEmpty()) return pending;
            for (var row : page) {
                if (row.getSeq() <= cursor) throw new IllegalStateException("事件游标未前进");
                cursor = row.getSeq();
                if (!AgentEventType.STEP.name().equals(row.getEventType())) continue;
                try {
                    var data = mapper.readTree(row.getPayload());
                    if ("AWAIT_USER".equals(data.path("kind").asText())) pending = row.getSeq();
                    if ("AGUI_RESUMED".equals(data.path("kind").asText()) && pending != null
                            && data.path("pauseSeq").asLong() == pending) pending = null;
                } catch (JsonProcessingException invalidEvent) {
                    throw new IllegalStateException("等待事件无法读取", invalidEvent);
                }
            }
        }
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

    /**
     * 把事件载荷转成字符串键的 Map。损坏或非对象载荷视为空。
     *
     * @param parsed {@link #parse} 的结果
     * @return 载荷；无法读取时为空 Map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object parsed) {
        if (parsed instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }

    /**
     * 读取事件里的 token。字段缺失或不是非负整数时返回 null，不把缺失当成 0。
     *
     * @param value 载荷字段
     * @return token；不可用时 null
     */
    private static Long tokenOrNull(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        long token = number.longValue();
        if (token < 0) {
            return null;
        }
        return token;
    }

    /**
     * 累加 token，超出文档整型列时拒绝定档。
     *
     * @param sum 已累加值
     * @param delta 本事件用量
     * @return 新合计
     */
    private static long addToken(long sum, long delta) {
        try {
            long next = Math.addExact(sum, delta);
            if (next > Integer.MAX_VALUE) {
                throw new ArithmeticException("token overflow");
            }
            return next;
        } catch (ArithmeticException ex) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "本次运行 token 用量超出文档列范围");
        }
    }

    /**
     * 没有看到字段时保持 null。
     *
     * @param sum 合计
     * @param seen 是否出现过该字段
     * @return 文档列上的 token
     */
    private static Integer fitToken(long sum, boolean seen) {
        if (!seen) {
            return null;
        }
        return (int) sum;
    }

    /** 定档写入的 token。缺用量的一侧为 null。 */
    private record RunUsage(Integer promptTokens, Integer completionTokens) {
    }
}
