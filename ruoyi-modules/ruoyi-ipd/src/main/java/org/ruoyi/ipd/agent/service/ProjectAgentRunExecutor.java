package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.kernel.ProjectAgentEventSink;
import org.ruoyi.ipd.agent.kernel.ProjectAgentIntent;
import org.ruoyi.ipd.agent.kernel.ProjectAgentKernel;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import org.ruoyi.ipd.agent.kernel.ProjectAgentUsageSink;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 运行执行器：并发额度、异步启动、本机运行句柄登记。
 *
 * <p>额度在创建运行（写库）之前预留，满额直接拒绝且零写入；额度在句柄收口时恰释放一次。
 * 启动时先 CAS PENDING→RUNNING，失败（已被取消）即放弃、不写任何事件。
 * 不向 {@code ai_agent_tasks} 写入（避免 AiExecutionEngine 按 status 扫描误拾），
 * 进程重启后遗留的 RUNNING/CANCEL_REQUESTED 由 W2 对账器收口（见运行合同 §6）。
 */
public class ProjectAgentRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgentRunExecutor.class);

    private final AgentRunStore store;
    private final ArtifactVersionStore artifactStore;
    private final ProjectAgentKernel kernel;
    private final ObjectMapper mapper;
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final Semaphore permits;
    private final ConcurrentMap<Long, ProjectAgentRunHandle> handles = new ConcurrentHashMap<>();
    private AiModelUsageLedgerService usageLedger;
    private DemandCatalogBinder demandBinder;
    private TransactionTemplate finishTransaction;
    private ProjectAgentRunOwnership ownership;
    private final ConcurrentMap<Long, OwnedRun> pendingOwnership = new ConcurrentHashMap<>();
    private record OwnedRun(ProjectAgentRunOwnership.Lease lease, int epoch) { }

    /**
     * @param store 持久化端口
     * @param artifactStore 产物版本存储（可空则不落 ARTIFACT）
     * @param kernel 执行内核（开关关闭时可为 null，此时任何启动都收口为 FAILED）
     * @param mapper JSON 映射器
     * @param scheduler 启动调度器（生产 boundedElastic）
     * @param clock 毫秒时钟
     * @param maxConcurrentRuns 本机并发运行上限
     */
    public ProjectAgentRunExecutor(AgentRunStore store, ArtifactVersionStore artifactStore,
                                   ProjectAgentKernel kernel, ObjectMapper mapper,
                                   Scheduler scheduler, LongSupplier clock, int maxConcurrentRuns) {
        this.store = store;
        this.artifactStore = artifactStore;
        this.kernel = kernel;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.clock = clock;
        this.permits = new Semaphore(Math.max(1, maxConcurrentRuns));
    }

    /** 同数据源事务用于状态、产物和终态事件的原子提交。 */
    public void setFinishTransaction(TransactionTemplate transaction) {
        this.finishTransaction = transaction;
    }

    public void setOwnership(ProjectAgentRunOwnership ownership) { this.ownership = ownership; }

    /**
     * 绑定既有用量账本。未绑定时模型步骤仍写入运行事件，但不落账。
     *
     * @param usageLedger 用量账本
     */
    public void setUsageLedger(AiModelUsageLedgerService usageLedger) {
        this.usageLedger = usageLedger;
    }

    /**
     * 绑定需求回写。未绑定时运行照常结束，不改需求单。
     *
     * @param demandBinder 目录回写
     */
    public void setDemandBinder(DemandCatalogBinder demandBinder) {
        this.demandBinder = demandBinder;
    }

    /**
     * VERIFYING 复检收口成功后的需求回写；语义同执行句柄的 SUCCEEDED 回调（hit 置空，
     * 回写器内按全文兜底匹配）。未绑定回写器或无需求单时不动作。
     *
     * @param requirementId 冻结快照里的需求单 ID
     * @param answer 复检通过时的产物正文
     */
    public void bindDemandOnReverify(Long requirementId, String answer) {
        if (demandBinder != null && requirementId != null) {
            demandBinder.apply(requirementId, answer, null);
        }
    }

    /**
     * 有账本时在内核出口外包一层，把模型结束步骤的 token 写入既有账本。
     *
     * @param handle 运行句柄
     * @param run 运行行
     * @return 交给内核的事件出口
     */
    private ProjectAgentAguiPauseResumeService aguiPauseResume;

    /** Config 在原 RunService 构造后装配，避免另建服务轨或循环构造。 */
    public void setAguiPauseResume(ProjectAgentAguiPauseResumeService service) {
        aguiPauseResume = java.util.Objects.requireNonNull(service);
    }

    private ProjectAgentEventSink usageSink(ProjectAgentRunHandle handle, IpdAgentRun run) {
        if (usageLedger == null || run.getModelConfigId() == null) {
            return handle;
        }
        ProjectAgentUsageSink usage = new ProjectAgentUsageSink(handle, usageLedger, run.getModelConfigId(),
            run.getPersonId() == null ? null : String.valueOf(run.getPersonId()), String.valueOf(run.getId()));
        return new ProjectAgentEventSink() {
            public void requireActiveOwnership() { handle.requireActiveOwnership(); }
            public long executionEpoch() { return handle.executionEpoch(); }
            public void registerTerminalSuccessReceipt(Runnable receipt) { handle.registerTerminalSuccessReceipt(receipt); }
            public void onChildInterrupt(java.util.List<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval> children,long version) {
                handle.onChildInterrupt(children,version);
            }
            public void recordChildCompletion(org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildCompletion completion) { handle.recordChildCompletion(completion); }
            public java.util.List<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildCompletion> loadChildCompletions() { return handle.loadChildCompletions(); }
            public void requireChildResumeConsumed(org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval approval) {
                handle.requireChildResumeConsumed(approval);
            }
            public <T> T withActiveOwnership(java.util.function.Supplier<T> action) { return handle.withActiveOwnership(action); }
            public void registerTemporaryStateCleanup(Runnable cleanup) { handle.registerTemporaryStateCleanup(cleanup); }
            public void releaseTemporaryState() { handle.releaseTemporaryState(); }
            public boolean isPaused() { return handle.isPaused(); }
            public void onAguiInterrupt(Map<String, io.agentscope.core.agui.event.AguiEvent.Interrupt> pending, long version) {
                handle.onAguiInterrupt(pending, version);
            }
            public void onStep(String kind, Map<String, Object> detail) {
                handle.onStep(kind, detail);
                handle.runOwned(() -> usage.recordStepUsage(kind, detail));
            }
            public void onToolCall(String id, String name) { usage.onToolCall(id, name); }
            public void onToolResult(String id, String name, String state) { usage.onToolResult(id, name, state); }
            public void onSource(Map<String, Object> source) { usage.onSource(source); }
            public void onText(String text) { usage.onText(text); }
            public void onFinalText(String text) { usage.onFinalText(text); }
            public void onArtifact(String id, String title, String hash, int version) { usage.onArtifact(id, title, hash, version); }
            public void onArtifactPayload(Map<String, Object> payload) { usage.onArtifactPayload(payload); }
            public void onError(String code) { usage.onError(code); }
            public void onComplete() { usage.onComplete(); }
        };
    }

    /**
     * 兼容旧构造（无产物表时跳过 ARTIFACT）。
     *
     * @param store 持久化端口
     * @param kernel 执行内核
     * @param mapper JSON 映射器
     * @param scheduler 启动调度器
     * @param clock 毫秒时钟
     * @param maxConcurrentRuns 并发上限
     */
    public ProjectAgentRunExecutor(AgentRunStore store, ProjectAgentKernel kernel, ObjectMapper mapper,
                                   Scheduler scheduler, LongSupplier clock, int maxConcurrentRuns) {
        this(store, null, kernel, mapper, scheduler, clock, maxConcurrentRuns);
    }

    /**
     * 预留一个并发额度（写库前调用）。
     *
     * @return true 预留成功
     */
    public boolean tryReserve() {
        return permits.tryAcquire();
    }

    /** 归还未使用的额度（创建失败或幂等命中时）。 */
    public void release() {
        permits.release();
    }

    private java.util.function.Consumer<IpdAgentRun> pausedCheckpointCleanup;

    /** 仅原运行终态持有者清理暂停的官方状态，不依赖原浏览器或旧 JVM。 */
    public void setPausedCheckpointCleanup(java.util.function.Consumer<IpdAgentRun> cleanup) {
        pausedCheckpointCleanup = java.util.Objects.requireNonNull(cleanup);
    }

    /** 先预留额度并持有新 epoch，再由原服务事务校验、消费暂停检查点。 */
    public void resumePrepared(IpdAgentRun run,
            java.util.function.Function<ProjectAgentRunHandle, ProjectAgentRunSpec> prepare) {
        resumePrepared(run,prepare,EnumSet.of(AgentRunStatus.WAITING_APPROVAL));
    }
    public static final class ResumeDeferred extends IllegalStateException {
        ResumeDeferred(Throwable cause) { super("原恢复调度尚未开始，等待原恢复器重接",cause); }
    }
    public void recoverPrepared(IpdAgentRun run,java.util.function.Function<ProjectAgentRunHandle,ProjectAgentRunSpec> prepare) {
        resumePrepared(run,prepare,EnumSet.of(AgentRunStatus.RUNNING));
    }
    private void resumePrepared(IpdAgentRun run,java.util.function.Function<ProjectAgentRunHandle,ProjectAgentRunSpec> prepare,
            java.util.Set<AgentRunStatus> expected) {
        if (kernel == null) throw new IllegalStateException("project agent kernel is unavailable");
        if (!tryReserve()) throw new org.ruoyi.ipd.common.IpdBusinessException(
            org.ruoyi.ipd.common.ApiV1ErrorCode.RATE_LIMITED, "项目智能体并发运行已满，请稍后重试");
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable releaseOnce = () -> { if (released.compareAndSet(false, true)) release(); };
        ProjectAgentRunHandle handle;
        try { handle = claimDetachedHandle(run, expected, releaseOnce); }
        catch (RuntimeException failure) { releaseOnce.run(); throw failure; }
        try {
            ProjectAgentRunSpec spec = handle.withActiveOwnership(() -> {
                ProjectAgentRunSpec prepared = java.util.Objects.requireNonNull(prepare.apply(handle));
                if (!run.getId().equals(prepared.runId()) || !run.getProjectId().equals(prepared.projectId())
                    || !run.getPersonId().equals(prepared.personId())
                    || ((prepared.serverResumeMessages() == null || prepared.serverResumeMessages().isEmpty())
                        && prepared.serverChildResumes().isEmpty())
                    || !AgentRunStatus.RUNNING.name().equals(store.findRun(run.getId()).orElseThrow().getStatus()))
                    throw new IllegalStateException("trusted original-run resume is incomplete");
                return prepared;
            });
            scheduler.schedule(() -> {
                try {
                    ProjectAgentRunSpec execution = spec;
                    if (execution.requirementId() != null && demandBinder != null) {
                        Long requirement = execution.requirementId();
                        var catalog = demandBinder.open(requirement);
                        if (catalog.appendix() != null && !catalog.appendix().isBlank()) execution = execution.withCatalog(catalog.appendix());
                        var hit = catalog.hit();
                        handle.whenSucceeded(text -> demandBinder.apply(requirement, text, hit));
                    }
                    handle.attach(kernel.execute(execution, usageSink(handle, run)));
                } catch (RuntimeException failure) { handle.finish(AgentRunStatus.FAILED, "KERNEL_ERROR"); }
            });
        } catch (RuntimeException failure) {
            IpdAgentRun current = store.findRun(run.getId()).orElse(null);
            if (current != null && AgentRunStatus.RUNNING.name().equals(current.getStatus())) {
                // 消费已提交而调度尚未开始：保留 RUNNING 与私有意图，由原失联恢复器接管。
                handle.onStep("AGUI_RESUME_DISPATCH_FAILED",Map.of("phase","BEFORE_SCHEDULE","errorType",failure.getClass().getName()));
            }
            handle.abandonOwnership();
            if(failure instanceof java.util.concurrent.RejectedExecutionException) throw new ResumeDeferred(failure);
            throw failure;
        }
    }

    /** 暂停后已释放原句柄；取消仍由新的租约持有者在同表写唯一终态。 */
    public boolean finishDetachedCancellation(IpdAgentRun run) {
        if (!AgentRunStatus.CANCEL_REQUESTED.name().equals(run.getStatus()) || handles.containsKey(run.getId())) return false;
        ProjectAgentRunHandle handle = claimDetachedHandle(run, EnumSet.of(AgentRunStatus.CANCEL_REQUESTED), () -> { });
        try { return handle.cancel(); }
        finally { if (!handle.isClosed()) handle.abandonOwnership(); }
    }

    private ProjectAgentRunHandle claimDetachedHandle(IpdAgentRun run, java.util.Set<AgentRunStatus> expected,
            Runnable releaseReservation) {
        ProjectAgentRunOwnership.Lease lease = null;
        if (ownership != null) {
            if (finishTransaction == null) throw new IllegalStateException("owned resume requires transaction");
            lease = ownership.acquire(run.getId()).orElseThrow(ProjectAgentRunOwnership.OwnershipLost::new);
            var acquired = lease;
            try {
                Integer epoch = finishTransaction.execute(tx -> {
                    if (!acquired.held()) throw new ProjectAgentRunOwnership.OwnershipLost();
                    var claimed = store.claimEpoch(run.getId(), run.getVersion(), expected);
                    if (claimed.isEmpty()) throw new ProjectAgentRunOwnership.OwnershipLost();
                    if (!store.appendEvent(ProjectAgentRunEvents.of(run.getId(), run.getTenantId(), run.getPersonId(),
                        store.maxSeq(run.getId()) + 1, AgentEventType.STEP,
                        toJson(Map.of("kind", "EXECUTION_OWNER", "epoch", claimed.get(), "fenceToken", acquired.token())),
                        new Date(clock.getAsLong())))) throw new IllegalStateException("resume owner event rejected");
                    return claimed.get();
                });
                run.setVersion(java.util.Objects.requireNonNull(epoch));
            } catch (RuntimeException failure) { acquired.close(); throw failure; }
        }
        var acquired = lease;
        var monitor = new java.util.concurrent.atomic.AtomicReference<Disposable>();
        var ownHandle = new java.util.concurrent.atomic.AtomicReference<ProjectAgentRunHandle>();
        ProjectAgentRunHandle handle = new ProjectAgentRunHandle(run, store, artifactStore, mapper, clock, () -> {
            handles.remove(run.getId(), ownHandle.get());
            Disposable watcher = monitor.get();
            if (watcher != null) watcher.dispose();
            try { if (acquired != null) acquired.close(); } finally { releaseReservation.run(); }
        });
        ownHandle.set(handle);
        handle.setFinishTransaction(finishTransaction);
        if (acquired != null) handle.setOwnership(acquired, run.getVersion());
        handle.registerTemporaryStateCleanup(() -> {
            // 已批准恢复在 SDK 装配前仍可能派发失败，不能删掉仅有的原检查点与审批证据。
            // SDK 真正开始后注册自己的提交回执保护清理；显式取消仍走原终态清理。
            if (expected.contains(AgentRunStatus.WAITING_APPROVAL) || expected.contains(AgentRunStatus.RUNNING)) return;
            if (pausedCheckpointCleanup == null) throw new IllegalStateException("paused checkpoint cleanup is not configured");
            pausedCheckpointCleanup.accept(run);
        });
        handle.setAguiInterruptHandler((pending, version) -> {
            if (aguiPauseResume == null) throw new IllegalStateException("AG-UI pause service is not configured");
            aguiPauseResume.pause(handle, run.getId(), version, pending);
        });
        handle.setChildInterruptHandler((children, version) -> {
            if (aguiPauseResume == null) throw new IllegalStateException("child pause service is not configured");
            aguiPauseResume.pauseChildren(handle, run.getId(), version, children);
        });
        handle.setChildResumeGuard(approval -> {
            if (aguiPauseResume == null) throw new IllegalStateException("child consumed receipt service is not configured");
            aguiPauseResume.requireConsumedChild(handle, run.getId(), approval);
        });
        handle.setChildCompletionJournal(completion-> {
            if(aguiPauseResume==null) throw new IllegalStateException("child completion journal is not configured");
            aguiPauseResume.recordChildCompletion(handle,run.getId(),completion);
        },()-> {
            if(aguiPauseResume==null) throw new IllegalStateException("child completion reader is not configured");
            return aguiPauseResume.loadChildCompletions(handle,run.getId());
        });
        if (handles.putIfAbsent(run.getId(), handle) != null) {
            handle.abandonOwnership();
            throw new ProjectAgentRunOwnership.OwnershipLost();
        }
        if (acquired != null) {
            try { monitor.set(scheduler.schedulePeriodically(() -> {
                try { handle.requireActiveOwnership(); } catch (RuntimeException lost) { handle.abandonOwnership(); }
            }, 1, 1, TimeUnit.SECONDS)); }
            catch (RuntimeException failure) { handle.abandonOwnership(); throw failure; }
        }
        return handle;
    }

    /**
     * 本机运行句柄。
     *
     * @param runId 运行 ID
     * @return 句柄（不在本机运行为空）
     */
    public Optional<ProjectAgentRunHandle> handle(Long runId) {
        return Optional.ofNullable(handles.get(runId));
    }

    /**
     * 异步启动（调用前已预留额度且运行已以 PENDING 落库）。
     *
     * @param run 运行行
     * @param spec 内核输入
     */
    public void submit(IpdAgentRun run, ProjectAgentRunSpec spec) {
        submitPrepared(run, () -> spec);
    }

    /** 新run与owner协议标记原子落原库；Redis租约先持有，避免其他JVM把创建中运行误关。 */
    public boolean insertReservedRun(IpdAgentRun run) {
        if (ownership == null) return store.insertRun(run);
        if (finishTransaction == null) throw new IllegalStateException("owned creation requires transaction");
        if (run.getId() == null) run.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        var lease = ownership.acquire(run.getId()).orElseThrow(ProjectAgentRunOwnership.OwnershipLost::new);
        try {
            run.setVersion(1);
            boolean inserted = Boolean.TRUE.equals(finishTransaction.execute(tx -> {
                if (!lease.held()) throw new ProjectAgentRunOwnership.OwnershipLost();
                if (!store.insertRun(run)) return false;
                if (!store.appendEvent(ProjectAgentRunEvents.of(run.getId(), run.getTenantId(), run.getPersonId(), 1,
                    AgentEventType.STEP, toJson(Map.of("kind", "EXECUTION_OWNER", "epoch", 1, "fenceToken", lease.token())),
                    new Date(clock.getAsLong())))) throw new IllegalStateException("new run ownership event rejected");
                return true;
            }));
            if (inserted) pendingOwnership.put(run.getId(), new OwnedRun(lease, 1));
            else lease.close();
            return inserted;
        } catch (RuntimeException failure) { lease.close(); throw failure; }
    }

    /** 接手已插入运行和额度，输入构造异常同样走事务失败收口。 */
    public void submitPrepared(IpdAgentRun run, Supplier<ProjectAgentRunSpec> prepare) {
        AtomicBoolean returned = new AtomicBoolean();
        Runnable releaseOnce = () -> {
            if (returned.compareAndSet(false, true)) {
                release();
            }
        };
        try {
            if (ownership != null && !pendingOwnership.containsKey(run.getId())) {
                OwnedRun owned = claimPendingOwnership(run);
                if (owned == null) { releaseOnce.run(); return; }
                pendingOwnership.put(run.getId(), owned);
            }
            ProjectAgentRunSpec spec = prepare.get();
            scheduler.schedule(() -> {
                try {
                    start(run, spec, releaseOnce);
                } catch (RuntimeException startFailure) {
                    settleSubmissionFailure(run, releaseOnce, "KERNEL_ERROR", startFailure);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            settleSubmissionFailure(run, releaseOnce, schedulingFailure instanceof RejectedExecutionException
                ? "AGENT_BUSY" : "KERNEL_ERROR", schedulingFailure);
        }
    }

    /** 调度/启动异常只在持久化终态获确认后归还这次额度，防止重复释放。 */
    private void settleSubmissionFailure(IpdAgentRun run, Runnable releaseOnce, String errorCode,
                                         RuntimeException failure) {
        log.error("project_agent operation=SUBMIT_OR_START status=FAILED runId={} errorType={}",
            run.getId(), failure.getClass().getName());
        try {
            if (ownership != null) {
                ProjectAgentRunHandle existing = handles.get(run.getId());
                if (existing != null) existing.finish(AgentRunStatus.FAILED, errorCode);
                else {
                    OwnedRun owned = pendingOwnership.remove(run.getId());
                    if (owned != null) {
                        try {
                            finishTransaction.executeWithoutResult(tx -> {
                                if (!store.lockEpoch(run.getId(), owned.epoch()) || !owned.lease().held())
                                    throw new ProjectAgentRunOwnership.OwnershipLost();
                                finishPendingOnce(run, AgentRunStatus.FAILED, errorCode);
                            });
                        } finally { try { owned.lease().close(); } finally { releaseOnce.run(); } }
                    } else { releaseOnce.run(); }
                }
                return;
            }
            if (finishPending(run, AgentRunStatus.FAILED, errorCode)) {
                releaseOnce.run();
                return;
            }
            IpdAgentRun current = store.findRun(run.getId())
                .orElseThrow(() -> new IllegalStateException("submitted run disappeared"));
            if (AgentRunStatus.valueOf(current.getStatus()).isTerminal()) {
                if (store.terminalSeq(run.getId()).isPresent()) {
                    releaseOnce.run();
                }
                return;
            }
            ProjectAgentRunHandle handle = handles.get(run.getId());
            if (handle == null) {
                handle = new ProjectAgentRunHandle(current, store, artifactStore, mapper, clock, () -> {
                    handles.remove(run.getId());
                    releaseOnce.run();
                });
                handle.setFinishTransaction(finishTransaction);
                handles.put(run.getId(), handle);
            }
            handle.finish(AgentRunStatus.FAILED, errorCode);
        } catch (RuntimeException persistenceFailure) {
            log.error("project_agent operation=SUBMIT_OR_START status=UNRESOLVED runId={} errorType={}",
                run.getId(), persistenceFailure.getClass().getName());
        }
    }

    /**
     * PENDING 运行直接收口（取消/调度拒绝），精确 CAS PENDING→target，胜者写唯一终态事件。
     *
     * @param run 运行行
     * @param target CANCELLED 或 FAILED
     * @param errorCode FAILED 时的错误码
     * @return true 本次调用完成收口
     */
    public boolean finishPending(IpdAgentRun run, AgentRunStatus target, String errorCode) {
        if (finishTransaction != null) {
            return Boolean.TRUE.equals(finishTransaction.execute(status -> finishPendingOnce(run, target, errorCode)));
        }
        return finishPendingOnce(run, target, errorCode);
    }

    private boolean finishPendingOnce(IpdAgentRun run, AgentRunStatus target, String errorCode) {
        Date now = new Date(clock.getAsLong());
        if (!store.transition(run.getId(), EnumSet.of(AgentRunStatus.PENDING), target, errorCode, now)) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentEventType type;
        if (target == AgentRunStatus.FAILED) {
            type = AgentEventType.ERROR;
            payload.put("errorCode", errorCode);
            payload.put("message", ProjectAgentErrorTexts.textOf(errorCode));
        } else {
            type = AgentEventType.RUN_FINISHED;
            payload.put("status", target.name());
        }
        long seq = store.maxSeq(run.getId()) + 1;
        if (!store.appendEvent(ProjectAgentRunEvents.of(run.getId(), run.getTenantId(), run.getPersonId(), seq, type,
            toJson(payload), now))) {
            throw new IllegalStateException("pending terminal event insert rejected");
        }
        return true;
    }

    /** 调度前已建立租约及原事件owner证据，排队期间崩溃也能安全辨识。 */
    private OwnedRun claimPendingOwnership(IpdAgentRun run) {
        var lease = ownership.acquire(run.getId()).orElse(null);
        if (lease == null) return null;
        try {
            if (finishTransaction == null) throw new IllegalStateException("owned execution requires transaction");
            OwnedRun claimed = finishTransaction.execute(tx -> {
                if (!lease.held()) throw new ProjectAgentRunOwnership.OwnershipLost();
                var epoch = store.claimEpoch(run.getId(), run.getVersion(), EnumSet.of(AgentRunStatus.PENDING));
                if (epoch.isEmpty()) return null;
                if (!store.appendEvent(ProjectAgentRunEvents.of(run.getId(), run.getTenantId(), run.getPersonId(),
                    store.maxSeq(run.getId()) + 1, AgentEventType.STEP,
                    toJson(Map.of("kind", "EXECUTION_OWNER", "epoch", epoch.get(), "fenceToken", lease.token())), new Date(clock.getAsLong()))))
                    throw new IllegalStateException("execution owner event rejected");
                run.setVersion(epoch.get());
                return new OwnedRun(lease, epoch.get());
            });
            if (claimed == null) lease.close();
            return claimed;
        } catch (RuntimeException failure) { lease.close(); throw failure; }
    }

    /**
     * 启动：CAS 进入 RUNNING，写 RUN_STARTED / SKILL_SELECTED / 意图。
     * RUN_STARTED 带上本次已装好的项目事实，供回读核对退回意见。
     * 需要澄清，或需要计划且未绑定动作时停在 WAITING_APPROVAL，不调用内核、不写产物。
     * 消息已是「按已确认计划执行」且带至少一条步骤时，没有动作也直接执行内核。
     */
    void start(IpdAgentRun run, ProjectAgentRunSpec spec) {
        start(run, spec, this::release);
    }

    private void start(IpdAgentRun run, ProjectAgentRunSpec spec, Runnable releaseReservation) {
        Date now = new Date(clock.getAsLong());
        ProjectAgentRunOwnership.Lease lease = null;
        if (ownership != null) {
            OwnedRun owned = pendingOwnership.remove(run.getId());
            if (owned == null) owned = claimPendingOwnership(run);
            if (owned == null) { releaseReservation.run(); return; }
            lease = owned.lease();
            var starting = owned;
            try {
                boolean started = Boolean.TRUE.equals(finishTransaction.execute(tx -> {
                    if (!store.lockEpoch(run.getId(), starting.epoch()) || !starting.lease().held())
                        throw new ProjectAgentRunOwnership.OwnershipLost();
                    return store.transition(run.getId(), EnumSet.of(AgentRunStatus.PENDING), AgentRunStatus.RUNNING, null, now);
                }));
                if (!started) { lease.close(); releaseReservation.run(); return; }
            } catch (RuntimeException failure) { lease.close(); throw failure; }
        } else if (!store.transition(run.getId(), EnumSet.of(AgentRunStatus.PENDING), AgentRunStatus.RUNNING, null, now)) {
            releaseReservation.run();
            return;
        }
        run.setStatus(AgentRunStatus.RUNNING.name());
        var acquired = lease;
        var monitor = new java.util.concurrent.atomic.AtomicReference<Disposable>();
        ProjectAgentRunHandle handle = new ProjectAgentRunHandle(run, store, artifactStore, mapper, clock, () -> {
            handles.remove(run.getId());
            Disposable watcher = monitor.get();
            if (watcher != null) watcher.dispose();
            try { if (acquired != null) acquired.close(); } finally { releaseReservation.run(); }
        });
        handle.setFinishTransaction(finishTransaction);
        handle.setAguiInterruptHandler((pending, version) -> {
            if (aguiPauseResume == null) throw new IllegalStateException("AG-UI pause service is not configured");
            aguiPauseResume.pause(handle, run.getId(), version, pending);
        });
        handle.setChildInterruptHandler((children, version) -> {
            if (aguiPauseResume == null) throw new IllegalStateException("child pause service is not configured");
            aguiPauseResume.pauseChildren(handle, run.getId(), version, children);
        });
        handle.setChildResumeGuard(approval -> {
            if (aguiPauseResume == null) throw new IllegalStateException("child consumed receipt service is not configured");
            aguiPauseResume.requireConsumedChild(handle, run.getId(), approval);
        });
        handle.setChildCompletionJournal(completion-> {
            if(aguiPauseResume==null) throw new IllegalStateException("child completion journal is not configured");
            aguiPauseResume.recordChildCompletion(handle,run.getId(),completion);
        },()-> {
            if(aguiPauseResume==null) throw new IllegalStateException("child completion reader is not configured");
            return aguiPauseResume.loadChildCompletions(handle,run.getId());
        });
        handles.put(run.getId(), handle);
        if (acquired != null) {
            handle.setOwnership(acquired, run.getVersion());
            try { monitor.set(scheduler.schedulePeriodically(() -> {
                try { handle.runOwned(() -> { }); } catch (RuntimeException lost) {
                    log.warn("project_agent operation=OWNER_WATCH status=LOST runId={} errorType={}", run.getId(), lost.getClass().getName());
                }
            }, 1, 1, TimeUnit.SECONDS)); } catch (RuntimeException unavailable) {
                handle.abandonOwnership();
                throw unavailable;
            }
        }
        try {
            DemandCatalogBinder.CatalogHit catalogHit = null;
            if (spec.requirementId() != null && demandBinder != null) {
                Long requirementId = spec.requirementId();
                DemandCatalogBinder.BindContext catalog = demandBinder.open(requirementId);
                if (catalog.appendix() != null && !catalog.appendix().isBlank()) {
                    spec = spec.withCatalog(catalog.appendix());
                }
                catalogHit = catalog.hit();
                DemandCatalogBinder.CatalogHit hit = catalogHit;
                handle.whenSucceeded(text -> demandBinder.apply(requirementId, text, hit));
            }
            Map<String, Object> started = new LinkedHashMap<>();
            started.put("agentId", run.getAgentId());
            started.put("capabilityPackCode", run.getCapabilityPackCode());
            started.put("capabilityPackVersion", run.getCapabilityPackVersion());
            started.put("modelConfigId", String.valueOf(run.getModelConfigId()));
            started.put("actionCode", run.getActionCode());
            if (spec.requirementId() != null) {
                started.put("requirementId", String.valueOf(spec.requirementId()));
            }
            if (catalogHit != null && catalogHit.lineCode() != null && !catalogHit.lineCode().isBlank()) {
                started.put("lineCode", catalogHit.lineCode());
            }
            if (catalogHit != null && catalogHit.productCode() != null && !catalogHit.productCode().isBlank()) {
                started.put("productCode", catalogHit.productCode());
            }
            if (spec.projectFacts() != null && !spec.projectFacts().isBlank()) {
                started.put("projectFacts", spec.projectFacts());
            }
            handle.append(AgentEventType.RUN_STARTED, started);
            for (LoadedSkill skill : spec.skills()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("name", skill.name());
                detail.put("version", skill.version());
                detail.put("sha256", skill.sha256());
                handle.onStep("SKILL_SELECTED", detail);
            }
            ProjectAgentIntent.Decision decision = publishIntent(handle, spec);
            if (handle.isClosed()) {
                return;
            }
            if (shouldAwaitUser(decision, spec.actionCode(), spec.message())) {
                awaitUser(handle, run, spec, decision);
                return;
            }
        } catch (RuntimeException initializationFailure) {
            log.error("project_agent operation=INITIALIZE status=FAILED runId={} errorType={}",
                run.getId(), initializationFailure.getClass().getName());
            handle.finish(AgentRunStatus.FAILED, "KERNEL_ERROR");
            return;
        }
        if (kernel == null) {
            handle.finish(AgentRunStatus.FAILED, "KERNEL_ERROR");
            return;
        }
        try {
            Disposable subscription = kernel.execute(spec, usageSink(handle, run));
            handle.attach(subscription);
        } catch (RuntimeException e) {
            log.error("project_agent operation=EXECUTE status=FAILED runId={} errorType={}",
                run.getId(), e.getClass().getName());
            handle.finish(AgentRunStatus.FAILED, "KERNEL_ERROR");
        }
    }

    /**
     * 模型开口前写入意图判断。与系统提示词共用同一套 decide。
     *
     * @param handle 运行句柄
     * @param spec 运行输入，技能正文来自其中已加载技能
     * @return 意图结论
     */
    private ProjectAgentIntent.Decision publishIntent(ProjectAgentRunHandle handle, ProjectAgentRunSpec spec) {
        List<String> skillBodies = spec.skills().stream().map(LoadedSkill::content).toList();
        ProjectAgentIntent.Decision decision = ProjectAgentIntent.decide(
            spec.message(), spec.actionCode(), skillBodies);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "意图判断");
        payload.put("detail", decision.summary());
        payload.put("needsPlan", decision.needsPlan());
        payload.put("needsClarification", decision.needsClarification());
        payload.put("questions", decision.questions());
        payload.put("steps", decision.steps());
        handle.onStep("INTENT", payload);
        return decision;
    }

    /**
     * 问题列表非空才算澄清。空列表按直答。自由计划指需要计划且没有绑定动作。
     * 没有原文时不把本轮当成已确认计划。
     *
     * @param decision 意图结论
     * @param actionCode 绑定动作，可空
     * @return 是否应停在等待用户
     */
    static boolean shouldAwaitUser(ProjectAgentIntent.Decision decision, String actionCode) {
        return shouldAwaitUser(decision, actionCode, null);
    }

    /**
     * 已确认且至少一条步骤时不再等待，即使没有绑定动作。只有确认开头、没有步骤时仍等待。
     *
     * @param decision 意图结论
     * @param actionCode 绑定动作，可空
     * @param message 用户原文，可空
     * @return 是否应停在等待用户
     */
    static boolean shouldAwaitUser(ProjectAgentIntent.Decision decision, String actionCode, String message) {
        boolean unbound = actionCode == null || actionCode.isBlank();
        List<String> confirmed = unbound ? ProjectAgentIntent.confirmedSteps(message) : null;
        if (confirmed != null && !confirmed.isEmpty()) {
            return false;
        }
        boolean clarification = decision.needsClarification()
            && decision.questions() != null && !decision.questions().isEmpty();
        boolean freePlan = decision.needsPlan() && unbound;
        return clarification || freePlan;
    }

    /**
     * 把运行停在等待用户：CAS 失败（已被取消）不覆盖终态，也不调用内核。
     *
     * @param handle 运行句柄
     * @param run 运行行
     * @param spec 运行输入，超时取其中的整轮时限
     * @param decision 意图结论，用于区分澄清和自由计划
     */
    private void awaitUser(ProjectAgentRunHandle handle, IpdAgentRun run, ProjectAgentRunSpec spec,
                           ProjectAgentIntent.Decision decision) {
        Date now = new Date(clock.getAsLong());
        final boolean[] changed = {false};
        handle.runOwned(() -> changed[0] = store.transition(run.getId(), EnumSet.of(AgentRunStatus.RUNNING),
            AgentRunStatus.WAITING_APPROVAL, null, now));
        if (!changed[0]) return;
        run.setStatus(AgentRunStatus.WAITING_APPROVAL.name());
        boolean clarification = decision.needsClarification()
            && decision.questions() != null && !decision.questions().isEmpty();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", clarification ? "CLARIFICATION" : "PLAN_CONFIRM");
        handle.onStep("AWAIT_USER", payload);
        if (ownership != null) handle.pauseForApproval();
        else scheduleAwaitTimeout(handle, run.getId(), spec);
    }

    /**
     * 等待超时沿用整轮时限，收口 FAILED / RUN_TIMEOUT，不再补调工具。
     * 测试用的立即调度器没有延迟 schedule，忽略即可，生产 boundedElastic 会真正挂上。
     *
     * @param handle 运行句柄
     * @param runId 运行编号
     * @param spec 运行输入
     */
    private void scheduleAwaitTimeout(ProjectAgentRunHandle handle, Long runId, ProjectAgentRunSpec spec) {
        long delay = spec.timeout().toMillis();
        try {
            scheduler.schedule(() -> finishIfStillWaiting(handle, runId), delay, TimeUnit.MILLISECONDS);
        } catch (UnsupportedOperationException | RejectedExecutionException ex) {
            log.warn("project_agent operation=AWAIT_TIMEOUT status=SKIPPED runId={} errorType={}",
                runId, ex.getClass().getName());
        }
    }

    /** 只在仍停在 WAITING_APPROVAL 时超时失败，避免盖掉取消或已经终态的运行。 */
    private void finishIfStillWaiting(ProjectAgentRunHandle handle, Long runId) {
        if (handle.isClosed()) {
            return;
        }
        Optional<IpdAgentRun> current = store.findRun(runId);
        if (current.isEmpty()
            || !AgentRunStatus.WAITING_APPROVAL.name().equals(current.get().getStatus())) {
            return;
        }
        handle.finish(AgentRunStatus.FAILED, "RUN_TIMEOUT");
    }

    private void failPending(IpdAgentRun run, String errorCode) {
        finishPending(run, AgentRunStatus.FAILED, errorCode);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event serialization failed", e);
        }
    }
}
