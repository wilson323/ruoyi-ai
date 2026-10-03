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

/**
 * 未挂产品线的游客需求，接到未指定产品线空间固定分拣项目上的现有创建运行。
 *
 * <p>不新建发送口。能力包或可用模型不是恰好一个时不猜、不创建。创建失败不让游客提交失败。
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
    public void attach(Requirement requirement) {
        if (requirement == null || requirement.getId() == null || requirement.getProductLineId() != null) {
            return;
        }
        try {
            AgentRunCreateReq req = requestFor(requirement);
            if (req == null) {
                return;
            }
            IpdActor actor = actor();
            if (actor == null) {
                return;
            }
            runs.create(actor, TRIAGE_PROJECT_ID, req);
        } catch (RuntimeException ex) {
            log.warn("demand_triage operation=CREATE status=SKIPPED requirementId={} errorType={}",
                requirement.getId(), ex.getClass().getName());
        }
    }

    private AgentRunCreateReq requestFor(Requirement requirement) {
        Project project = projects.selectById(TRIAGE_PROJECT_ID);
        if (project == null) {
            return null;
        }
        if (manifest.packs() == null || manifest.packs().size() != 1) {
            return null;
        }
        CapabilityManifest.PackEntry pack = manifest.packs().get(0);
        List<ProjectAgentModelCatalog.ModelStatus> available = models.statuses().stream()
            .filter(ProjectAgentModelCatalog.ModelStatus::available)
            .toList();
        if (available.size() != 1) {
            return null;
        }
        String message = messageOf(requirement);
        if (message.isBlank()) {
            return null;
        }
        List<String> tools = List.of();
        if (pack.tools() != null && pack.tools().contains(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)) {
            tools = List.of(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH);
        }
        return new AgentRunCreateReq(pack.code(), pack.version(), available.get(0).id(),
            List.of(), tools, null, message, "demand-" + requirement.getId(), null,
            String.valueOf(requirement.getId()));
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
