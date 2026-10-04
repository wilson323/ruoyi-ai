package org.ruoyi.ipd.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;

/**
 * 未挂产品线的游客需求，接到未指定产品线空间固定分拣项目上的现有创建运行。
 *
 * <p>不新建发送口。按用途选择分拣包，消费既有唯一生效主模型。创建失败保存可见原因，不让游客提交失败。
 */
public class DemandTriageRun {

    /** 合同固定分拣项目。 */
    static final long TRIAGE_PROJECT_ID = 9190003L;

    private static final Logger log = LoggerFactory.getLogger(DemandTriageRun.class);

    private final ProjectMapper projects;
    private final ProjectMemberMapper members;
    private final PersonMapper persons;
    private final ProjectAgentModelCatalog models;
    private final CapabilityManifest manifest;
    private final ProjectAgentRunService runs;
    private RequirementMapper requirements;
    private AgentRunStore store;
    private org.ruoyi.ipd.agent.catalog.ProjectAgentPackCatalog packCatalog;

    @org.springframework.beans.factory.annotation.Autowired
    public void withPackCatalog(org.ruoyi.ipd.agent.catalog.ProjectAgentPackCatalog packCatalog) {
        this.packCatalog = java.util.Objects.requireNonNull(packCatalog);
    }

    /** 原需求行只记录分拣尝试与创建失败；运行状态仍由原运行存储负责。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void withRecovery(RequirementMapper requirements, AgentRunStore store) {
        this.requirements = java.util.Objects.requireNonNull(requirements);
        this.store = java.util.Objects.requireNonNull(store);
    }

    public record TriageStatus(String state, String message, String runId, boolean retryable) { }

    /** 调用者先在原需求入口完成权限校验；不使用游客查询码授予重试权限。 */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public TriageStatus retry(Long requirementId) {
        if (requirements == null) throw new IllegalStateException("需求分拣持久化未接通");
        Requirement current = requirements.selectById(requirementId);
        if (current == null) throw new org.ruoyi.ipd.common.IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.NOT_FOUND, "需求不存在");
        TriageStatus before = status(current);
        if (!before.retryable()) throw new org.ruoyi.ipd.common.IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT, before.message());
        start(current, true);
        return status(requirements.selectById(requirementId));
    }

    /** 状态读取不推进业务、不批准等待中的运行。 */
    public TriageStatus status(Requirement requirement) {
        if (requirement == null) return new TriageStatus("MISSING", "需求不存在", null, false);
        if (requirement.getProductLineId() != null) return new TriageStatus("BOUND", "已确定产品线", null, false);
        if (requirement.getTriageRunId() != null && store != null) {
            var run = store.findRun(requirement.getTriageRunId()).orElse(null);
            if (run == null) return new TriageStatus("FAILED", "关联运行未找到，请重试分拣", null, true);
            AgentRunStatus state = AgentRunStatus.valueOf(run.getStatus());
            boolean retry = state == AgentRunStatus.FAILED || state == AgentRunStatus.CANCELLED;
            String message = switch (state) {
                case FAILED -> "分拣运行失败，可重试并查看原运行原因";
                case CANCELLED -> "分拣运行已取消，可重试";
                case SUCCEEDED -> "分拣已完成，但尚无足够依据确定产品线";
                case WAITING_APPROVAL -> "分拣正在等待负责人确认";
                case VERIFYING -> "分拣结果正在核验";
                default -> "分拣运行进行中";
            };
            return new TriageStatus(state.name(), message, String.valueOf(run.getId()), retry);
        }
        String state = requirement.getTriageStatus();
        if ("STARTING".equals(state)) {
            var existing = recoverRun(requirement);
            if (existing != null) {
                Requirement projection = Requirement.builder().id(requirement.getId()).triageRunId(existing.getId()).build();
                return status(projection);
            }
            return new TriageStatus(state, "分拣创建回执待恢复；重试将继续同一次尝试", null, true);
        }
        boolean failed = "FAILED".equals(state);
        return new TriageStatus(failed ? "FAILED" : "NOT_STARTED",
            failed ? requirement.getTriageError() : "尚未开始分拣", null, true);
    }

    /**
     * @param projects 项目
     * @param members 项目成员
     * @param persons 人员
     * @param models 模型目录
     * @param manifest 能力包清单
     * @param runs 现有创建运行
     */
    public DemandTriageRun(ProjectMapper projects, ProjectMemberMapper members, PersonMapper persons,
                           ProjectAgentModelCatalog models, CapabilityManifest manifest,
                           ProjectAgentRunService runs) {
        this.projects = projects;
        this.members = members;
        this.persons = persons;
        this.models = models;
        this.manifest = manifest;
        this.runs = runs;
    }

    /**
     * 把这张未挂线需求交给分拣项目的现有创建运行。
     *
     * @param requirement 刚写入的需求单
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void attach(Requirement requirement) {
        if (requirement == null || requirement.getId() == null || requirement.getProductLineId() != null) {
            return;
        }
        try {
            start(requirement, false);
        } catch (RuntimeException ex) {
            // 游客提交已经完成；持久回执故障不撤销已提交需求，也不打印底层异常正文。
            log.warn("demand_triage operation=ATTACH status=RECEIPT_FAILED requirementId={} errorType={}",
                requirement.getId(), ex.getClass().getName());
        }
    }

    private void start(Requirement requirement, boolean retry) {
        int oldAttempt = requirement.getTriageAttempt() == null ? 0 : requirement.getTriageAttempt();
        boolean recovering = retry && "STARTING".equals(requirement.getTriageStatus());
        int attempt = recovering ? oldAttempt : retry ? oldAttempt + 1 : Math.max(1, oldAttempt);
        var oldRun = recovering ? recoverRun(requirement) : null;
        if (oldRun != null) {
            persist(requirement.getId(), attempt, "CREATED", null, oldRun.getId());
            return;
        }
        IpdActor selectedActor = null;
        RuntimeException actorFailure = null;
        try {
            selectedActor = recovering ? savedActor(requirement.getTriagePersonId()) : actor();
        } catch (RuntimeException ex) {
            actorFailure = ex;
        }
        if (requirements != null) {
            if (!retry && oldAttempt > 0) return;
            LambdaUpdateWrapper<Requirement> claim = new LambdaUpdateWrapper<Requirement>()
                .eq(Requirement::getId, requirement.getId()).isNull(Requirement::getProductLineId);
            if (requirement.getTriageAttempt() == null) claim.isNull(Requirement::getTriageAttempt);
            else claim.eq(Requirement::getTriageAttempt, oldAttempt);
            claim.set(Requirement::getTriageAttempt, attempt)
                .set(Requirement::getTriagePersonId, selectedActor == null ? null : selectedActor.id())
                .set(Requirement::getTriageStatus, "STARTING")
                .set(Requirement::getTriageError, null).set(Requirement::getTriageRunId, null);
            if (requirements.update(null, claim) != 1) {
                if (retry) throw new org.ruoyi.ipd.common.IpdBusinessException(
                    org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT, "需求分拣已变化，请回读后重试");
                return;
            }
        }
        String failure = "分拣运行创建失败，请检查分拣项目权限及运行配置后重试";
        ProjectAgentViews.RunStatus created;
        try {
            if (actorFailure != null) throw actorFailure;
            AgentRunCreateReq req = requestFor(requirement, attempt);
            if (selectedActor == null) throw new TriageConfigurationException("分拣项目没有有效执行成员，请配置后重试");
            created = runs.create(selectedActor, TRIAGE_PROJECT_ID, req);
            if (created == null || created.runId() == null) throw new IllegalStateException("missing run receipt");
        } catch (RuntimeException ex) {
            if (ex instanceof TriageConfigurationException) failure = ex.getMessage();
            persist(requirement.getId(), attempt, "FAILED", failure, null);
            log.warn("demand_triage operation=CREATE status=FAILED requirementId={} errorType={}",
                requirement.getId(), ex.getClass().getName());
            return;
        }
        // 创建已成功，回执失败不能改成创建失败；保留 STARTING 和原幂等身份供恢复。
        persist(requirement.getId(), attempt, "CREATED", null, Long.valueOf(created.runId()));
    }

    private void persist(Long id, int attempt, String state, String error, Long runId) {
        if (requirements == null) return;
        int changed = requirements.update(null, new LambdaUpdateWrapper<Requirement>()
            .eq(Requirement::getId, id).eq(Requirement::getTriageAttempt, attempt)
            .set(Requirement::getTriageStatus, state).set(Requirement::getTriageError, error)
            .set(Requirement::getTriageRunId, runId));
        if (changed != 1) throw new org.ruoyi.ipd.common.IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT, "需求分拣回执写入冲突");
    }

    private static final class TriageConfigurationException extends IllegalStateException {
        private TriageConfigurationException(String message) { super(message); }
    }

    private AgentRunCreateReq requestFor(Requirement requirement, int attempt) {
        Project project = projects.selectById(TRIAGE_PROJECT_ID);
        if (project == null) throw new TriageConfigurationException("固定分拣项目不存在，请配置后重试");
        List<CapabilityManifest.PackEntry> configured = packCatalog == null ? manifest.packs()
            : packCatalog.packs(project.getTenantId());
        List<CapabilityManifest.PackEntry> packs = configured.stream()
            .filter(p -> "demand-triage".equals(p.code())).toList();
        if (packs.isEmpty()) packs = configured.stream()
            .filter(p -> "market-research".equals(p.code())).toList();
        if (packs.size() != 1) throw new TriageConfigurationException("需求分拣能力包未配置或存在多个版本，请保留唯一配置后重试");
        CapabilityManifest.PackEntry pack = packs.get(0);
        List<ProjectAgentModelCatalog.ModelStatus> available = models.statuses().stream()
            .filter(ProjectAgentModelCatalog.ModelStatus::available)
            .toList();
        if (available.size() != 1) throw new TriageConfigurationException("请启用唯一的主对话模型后重试分拣");
        String message = messageOf(requirement);
        if (message.isBlank()) throw new TriageConfigurationException("需求缺少标题与内容，无法分拣");
        List<String> tools = List.of();
        if (pack.tools() != null && pack.tools().contains(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)) {
            tools = List.of(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH);
        }
        return new AgentRunCreateReq(pack.code(), pack.version(), available.get(0).id(),
            List.of(), tools, null, message, key(requirement.getId(), attempt), null,
            String.valueOf(requirement.getId()));
    }

    private static String key(Long requirementId, int attempt) {
        return "demand-" + requirementId + (attempt <= 1 ? "" : "-retry-" + attempt);
    }

    private org.ruoyi.ipd.agent.domain.IpdAgentRun recoverRun(Requirement requirement) {
        if (store == null || requirement.getTriagePersonId() == null || requirement.getTriageAttempt() == null) return null;
        Project project = projects.selectById(TRIAGE_PROJECT_ID);
        if (project == null) return null;
        return store.findByIdempotencyKey(project.getTenantId(), requirement.getTriagePersonId(),
            key(requirement.getId(), requirement.getTriageAttempt())).orElse(null);
    }

    private IpdActor savedActor(Long personId) {
        Person person = personId == null ? null : persons.selectById(personId);
        return person == null ? null : new IpdActor(person.getId(), person.getName(), person.getPersonType(), person.getGroupId());
    }

    private IpdActor actor() {
        List<ProjectMember> rows = members.selectList(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, TRIAGE_PROJECT_ID)
            .isNull(ProjectMember::getExitDate)
            .orderByAsc(ProjectMember::getJoinDate)
            .orderByAsc(ProjectMember::getId)
            .last("LIMIT 1"));
        if (rows == null || rows.isEmpty() || rows.get(0).getPersonId() == null) {
            return null;
        }
        Person person = persons.selectById(rows.get(0).getPersonId());
        if (person == null || person.getId() == null) {
            return null;
        }
        return new IpdActor(person.getId(), person.getName(), person.getPersonType(), person.getGroupId());
    }

    private static String messageOf(Requirement requirement) {
        String title = requirement.getTitle() == null ? "" : requirement.getTitle().trim();
        String content = requirement.getContent() == null ? "" : requirement.getContent().trim();
        String message = title.isEmpty() ? content : content.isEmpty() ? title : title + "\n" + content;
        if (message.length() > ProjectAgentConstants.MESSAGE_MAX_CHARS) {
            return message.substring(0, ProjectAgentConstants.MESSAGE_MAX_CHARS);
        }
        return message;
    }
}
