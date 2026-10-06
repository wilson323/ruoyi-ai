package org.ruoyi.ipd.agent.service;

import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest.PackEntry;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.IpdCopilotAccess;

import java.util.List;
import java.util.Objects;

/**
 * 项目智能体能力目录（合同 #1）：服务端按 Person/项目返回可运行选项与不可用原因。
 *
 * <p>开关关闭时仍返回完整目录，但所有能力包 available=false、unavailableReason 明确为
 * {@link ProjectAgentConstants#REASON_DISABLED}，不静默降级到副驾；Skill/工具/模型各自的
 * 可用性照常如实返回（便于部署前核对安装内容）。本接口不读写 ipd_agent_* 表。
 */
public class ProjectAgentCapabilityService {

    private final boolean enabled;
    private final IpdCopilotAccess access;
    private final CapabilityManifest manifest;
    private org.ruoyi.ipd.agent.catalog.ProjectAgentPackCatalog packCatalog;
    private final ProjectAgentSkillCatalog skillCatalog;
    private final ProjectAgentToolCatalog toolCatalog;
    private final ProjectAgentModelCatalog modelCatalog;

    /**
     * @param enabled 开关
     * @param access 项目访问守卫
     * @param manifest 内置清单
     * @param skillCatalog Skill 目录
     * @param toolCatalog 工具目录
     * @param modelCatalog 模型目录
     */
    public ProjectAgentCapabilityService(boolean enabled, IpdCopilotAccess access, CapabilityManifest manifest,
                                         ProjectAgentSkillCatalog skillCatalog, ProjectAgentToolCatalog toolCatalog,
                                         ProjectAgentModelCatalog modelCatalog) {
        this.enabled = enabled;
        this.access = Objects.requireNonNull(access, "access");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
        this.modelCatalog = Objects.requireNonNull(modelCatalog, "modelCatalog");
    }

    public ProjectAgentCapabilityService(boolean enabled, IpdCopilotAccess access, CapabilityManifest manifest,
            ProjectAgentSkillCatalog skills, ProjectAgentToolCatalog tools, ProjectAgentModelCatalog models,
            org.ruoyi.ipd.agent.catalog.ProjectAgentPackCatalog packs) {
        this(enabled, access, manifest, skills, tools, models);
        this.packCatalog = packs;
    }

    /**
     * 查询能力目录（先做项目可见性校验）。
     *
     * @param actor 会话身份
     * @param projectId 项目 ID
     * @return 能力包 + 模型
     */
    public ProjectAgentViews.Capabilities capabilities(IpdActor actor, Long projectId) {
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 必填");
        }
        String tenantId = access.requireVisible(actor, projectId);
        List<ProjectAgentViews.Model> models = modelCatalog.statuses().stream()
            .map(m -> new ProjectAgentViews.Model(m.id(), m.name(), m.available(), m.reason()))
            .toList();
        boolean anyModel = models.stream().anyMatch(ProjectAgentViews.Model::available);
        var scopedSkills = skillCatalog.statuses(tenantId, projectId, actor.id());
        var reviewed = scopedSkills.stream()
            .filter(s -> manifest.skill(s.name()).isEmpty())
            .map(s -> new ProjectAgentViews.Skill(s.name(), s.version(), s.sha256(), s.available(), s.reason())).toList();
        var byName = scopedSkills.stream().collect(java.util.stream.Collectors.toMap(
            ProjectAgentSkillCatalog.SkillStatus::name, java.util.function.Function.identity()));
        List<ProjectAgentViews.Pack> packs = (packCatalog == null ? manifest.packs() : packCatalog.packs(tenantId)).stream().map(p -> pack(p, anyModel, reviewed, byName)).toList();
        return new ProjectAgentViews.Capabilities(packs, models);
    }

    private ProjectAgentViews.Pack pack(PackEntry entry, boolean anyModel, List<ProjectAgentViews.Skill> reviewed,
            java.util.Map<String, ProjectAgentSkillCatalog.SkillStatus> scopedSkills) {
        List<ProjectAgentViews.Skill> requiredSkills = entry.skills().stream()
            .map(name -> scopedSkills.getOrDefault(name, skillCatalog.status(name)))
            .map(s -> new ProjectAgentViews.Skill(s.name(), s.version(), s.sha256(), s.available(), s.reason()))
            .toList();
        List<ProjectAgentViews.Tool> tools = ProjectAgentToolCatalog.executionToolIds(entry.tools()).stream().map(toolCatalog::status)
            .map(t -> new ProjectAgentViews.Tool(t.id(), t.name(), t.readOnly(), t.available(), t.reason()))
            .toList();
        String reason = unavailableReason(requiredSkills, tools.stream()
            .filter(tool -> entry.tools().contains(tool.id())
                // render_html_page 为按需可选工具：不就绪只让该工具不可勾选（planner 勾选即拒、
                // 目录行如实展示原因），不把整包拖成不可用；其余包内工具仍是必备。
                && !ProjectAgentToolCatalog.HTML_PAGE_RENDER.equals(tool.id()))
            .toList(), anyModel);
        var skills = new java.util.ArrayList<>(requiredSkills);
        skills.addAll(reviewed);
        return new ProjectAgentViews.Pack(entry.code(), entry.version(), entry.name(), entry.description(),
            entry.stages() == null ? List.of() : entry.stages(),
            entry.actionCodes() == null ? List.of() : entry.actionCodes(),
            reason == null, reason, skills, tools);
    }

    private String unavailableReason(List<ProjectAgentViews.Skill> skills, List<ProjectAgentViews.Tool> tools,
                                     boolean anyModel) {
        if (!enabled) {
            return ProjectAgentConstants.REASON_DISABLED;
        }
        for (ProjectAgentViews.Skill skill : skills) {
            if (!skill.available()) {
                return "必需 Skill 不可用：" + skill.name() + "（" + skill.reason() + "）";
            }
        }
        for (ProjectAgentViews.Tool tool : tools) {
            if (!tool.available()) {
                return "必需工具不可用：" + tool.id() + "（" + tool.reason() + "）";
            }
        }
        if (!anyModel) {
            return "无可用模型配置";
        }
        return null;
    }
}
