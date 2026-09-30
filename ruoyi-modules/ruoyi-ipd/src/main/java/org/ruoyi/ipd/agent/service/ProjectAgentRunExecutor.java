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
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

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

    /**
     * 绑定既有用量账本。未绑定时模型步骤仍写入运行事件，但不落账。
     *
     * @param usageLedger 用量账本
     */
    public void setUsageLedger(AiModelUsageLedgerService usageLedger) {
        this.usageLedger = usageLedger;
    }

    /**
     * 有账本时在内核出口外包一层，把模型结束步骤的 token 写入既有账本。
     *
     * @param handle 运行句柄
     * @param run 运行行
     * @return 交给内核的事件出口
     */
    private ProjectAgentEventSink usageSink(ProjectAgentRunHandle handle, IpdAgentRun run) {
        if (usageLedger == null || run.getModelConfigId() == null) {
            return handle;
        }
        return new ProjectAgentUsageSink(handle, usageLedger, run.getModelConfigId(),
            run.getPersonId() == null ? null : String.valueOf(run.getPersonId()),
            String.valueOf(run.getId()));
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
        try {
            scheduler.schedule(() -> start(run, spec));
        } catch (RejectedExecutionException rejected) {
            log.warn("project_agent operation=SUBMIT status=REJECTED runId={}", run.getId());
            failPending(run, "AGENT_BUSY");
            release();
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
        store.appendEvent(ProjectAgentRunEvents.of(run.getId(), run.getTenantId(), run.getPersonId(), seq, type,
            toJson(payload), now));
        return true;
    }

    /**
     * 启动：CAS 进入 RUNNING，写 RUN_STARTED / SKILL_LOADED / 意图。
     * 需要澄清，或需要计划且未绑定动作时停在 WAITING_APPROVAL，不调用内核、不写产物。
     * 消息已是「按已确认计划执行」且带至少一条步骤时，没有动作也直接执行内核。
     */
    void start(IpdAgentRun run, ProjectAgentRunSpec spec) {
        Date now = new Date(clock.getAsLong());
        if (!store.transition(run.getId(), EnumSet.of(AgentRunStatus.PENDING), AgentRunStatus.RUNNING, null, now)) {
            release();
            return;
        }
        run.setStatus(AgentRunStatus.RUNNING.name());
        ProjectAgentRunHandle handle = new ProjectAgentRunHandle(run, store, artifactStore, mapper, clock, () -> {
            handles.remove(run.getId());
            release();
        });
        handles.put(run.getId(), handle);
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("agentId", run.getAgentId());
        started.put("capabilityPackCode", run.getCapabilityPackCode());
        started.put("capabilityPackVersion", run.getCapabilityPackVersion());
        started.put("modelConfigId", String.valueOf(run.getModelConfigId()));
        started.put("actionCode", run.getActionCode());
        handle.append(AgentEventType.RUN_STARTED, started);
        for (LoadedSkill skill : spec.skills()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", skill.name());
            detail.put("version", skill.version());
            detail.put("sha256", skill.sha256());
            handle.onStep("SKILL_LOADED", detail);
        }
        ProjectAgentIntent.Decision decision = publishIntent(handle, spec);
        if (handle.isClosed()) {
            return;
        }
        if (shouldAwaitUser(decision, spec.actionCode(), spec.message())) {
            awaitUser(handle, run, spec, decision);
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
        if (!store.transition(run.getId(), EnumSet.of(AgentRunStatus.RUNNING),
            AgentRunStatus.WAITING_APPROVAL, null, now)) {
            return;
        }
        run.setStatus(AgentRunStatus.WAITING_APPROVAL.name());
        boolean clarification = decision.needsClarification()
            && decision.questions() != null && !decision.questions().isEmpty();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", clarification ? "CLARIFICATION" : "PLAN_CONFIRM");
        handle.onStep("AWAIT_USER", payload);
        scheduleAwaitTimeout(handle, run.getId(), spec);
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
            return "{}";
        }
    }
}
