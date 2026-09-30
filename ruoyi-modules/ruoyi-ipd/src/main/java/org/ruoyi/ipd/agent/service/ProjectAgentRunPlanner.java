package org.ruoyi.ipd.agent.service;

import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest.PackEntry;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews.ConfigSnapshot;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews.SkillRef;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.service.IpdActionSkillMapService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 创建运行前的纯校验与配置冻结（无任何写入）。
 *
 * <p>校验分两段：{@link #validateShape} 只看请求形状（幂等命中前执行，保证重复提交语义稳定）；
 * {@link #plan} 对照能力包/Skill/工具/模型目录的当前可用性（仅新建运行时执行）。
 * 任何一项不满足都在写库前拒绝，保证零写入。
 *
 * <p>Skill 来源：请求显式 {@code skillNames} ∪ 本轮 {@code actionCode} 在
 * {@code ipd_action_skill_map} 的绑定（每次查库）。动作绑定名仅在
 * classpath {@code ipd-skills/&lt;name&gt;/SKILL.md} 真实可加载时注入系统提示；
 * 无文件/校验失败则跳过，不编造。NULL 绑定不注入任何动作技能。
 */
public class ProjectAgentRunPlanner {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_\\-]{8,64}");

    /** 校验通过后的冻结计划。 */
    public record RunPlan(PackEntry pack, Long modelConfigId, KernelModelRequest model, List<LoadedSkill> skills,
                          List<String> toolIds, String actionCode, ConfigSnapshot snapshot) { }

    private final CapabilityManifest manifest;
    private final ProjectAgentSkillCatalog skillCatalog;
    private final ProjectAgentToolCatalog toolCatalog;
    private final ProjectAgentModelCatalog modelCatalog;
    private final IpdActionSkillMapService skillMapService;

    /**
     * @param manifest 内置清单
     * @param skillCatalog Skill 目录
     * @param toolCatalog 工具目录
     * @param modelCatalog 模型目录
     * @param skillMapService 动作→技能映射（每次查库）
     */
    public ProjectAgentRunPlanner(CapabilityManifest manifest, ProjectAgentSkillCatalog skillCatalog,
                                  ProjectAgentToolCatalog toolCatalog, ProjectAgentModelCatalog modelCatalog,
                                  IpdActionSkillMapService skillMapService) {
        this.manifest = manifest;
        this.skillCatalog = skillCatalog;
        this.toolCatalog = toolCatalog;
        this.modelCatalog = modelCatalog;
        this.skillMapService = skillMapService;
    }

    /**
     * 测试兼容：无动作映射服务时不按 actionCode 自动绑技能。
     *
     * @param manifest 内置清单
     * @param skillCatalog Skill 目录
     * @param toolCatalog 工具目录
     * @param modelCatalog 模型目录
     */
    public ProjectAgentRunPlanner(CapabilityManifest manifest, ProjectAgentSkillCatalog skillCatalog,
                                  ProjectAgentToolCatalog toolCatalog, ProjectAgentModelCatalog modelCatalog) {
        this(manifest, skillCatalog, toolCatalog, modelCatalog, null);
    }

    /**
     * 请求形状校验（必填、长度、幂等键格式、ID 格式）。
     *
     * @param req 请求
     * @throws IpdBusinessException PARAM_INVALID
     */
    public void validateShape(AgentRunCreateReq req) {
        if (req == null) {
            throw invalid("请求体不能为空");
        }
        if (isBlank(req.capabilityPackCode()) || isBlank(req.capabilityPackVersion())) {
            throw invalid("capabilityPackCode 与 capabilityPackVersion 必填");
        }
        parseModelConfigId(req.modelConfigId());
        if (isBlank(req.message())) {
            throw invalid("message 必填");
        }
        if (req.message().length() > ProjectAgentConstants.MESSAGE_MAX_CHARS) {
            throw invalid("message 超过 " + ProjectAgentConstants.MESSAGE_MAX_CHARS + " 字");
        }
        if (req.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(req.idempotencyKey()).matches()) {
            throw invalid("idempotencyKey 须为 8~64 位字母、数字、下划线或连字符");
        }
    }

    /**
     * 对照目录冻结配置。
     *
     * @param req 已通过形状校验的请求
     * @return 冻结计划
     * @throws IpdBusinessException PARAM_INVALID（不属于该能力包）/ STATE_CONFLICT（当前不可用）
     */
    public RunPlan plan(AgentRunCreateReq req) {
        PackEntry pack = manifest.pack(req.capabilityPackCode(), req.capabilityPackVersion())
            .orElseThrow(() -> invalid("能力包不存在：" + req.capabilityPackCode() + "@" + req.capabilityPackVersion()));
        String actionCode = isBlank(req.actionCode()) ? null : req.actionCode().trim();
        if (actionCode != null && !pack.actionCodes().contains(actionCode)) {
            throw invalid("动作 " + actionCode + " 不在能力包适用范围内");
        }
        List<String> explicitNames = distinct(req.skillNames());
        List<String> actionBoundNames = resolveActionBoundSkillNames(actionCode);
        List<LoadedSkill> skills = loadSkills(pack, explicitNames, actionBoundNames);
        List<String> toolIds = distinct(req.toolIds());
        for (String toolId : toolIds) {
            if (!pack.tools().contains(toolId)) {
                throw invalid("工具不属于该能力包：" + toolId);
            }
            ProjectAgentToolCatalog.ToolStatus status = toolCatalog.status(toolId);
            if (!status.available()) {
                throw conflict("工具不可用：" + toolId + "（" + status.reason() + "）");
            }
        }
        Long modelConfigId = parseModelConfigId(req.modelConfigId());
        KernelModelRequest model = modelCatalog.resolve(modelConfigId).orElseThrow(() -> conflict(
            "模型不可用：" + req.modelConfigId() + "（" + modelCatalog.status(modelConfigId).reason() + "）"));
        ConfigSnapshot snapshot = new ConfigSnapshot(pack.code(), pack.version(), String.valueOf(modelConfigId),
            skills.stream().map(s -> new SkillRef(s.name(), s.sha256())).toList(), toolIds);
        return new RunPlan(pack, modelConfigId, model, List.copyOf(skills), toolIds, actionCode, snapshot);
    }

    /**
     * 从动作映射表读取 skill_names（每次查库）；NULL/空 → 空列表。
     *
     * @param actionCode 动作编码
     * @return 绑定名列表
     */
    List<String> resolveActionBoundSkillNames(String actionCode) {
        if (actionCode == null || skillMapService == null) {
            return List.of();
        }
        IpdActionSkillMap row = skillMapService.findByActionCode(actionCode);
        if (row == null) {
            return List.of();
        }
        return IpdActionSkillMapService.parseSkillNames(row.getSkillNames());
    }

    /**
     * 合并显式选定与动作绑定技能并加载正文。
     *
     * <p>显式名须属于能力包且必须可加载（失败 fail-loud）；动作绑定名仅在
     * classpath 真实可加载时打入，缺失则跳过不编造。
     *
     * @param pack 能力包
     * @param explicitNames 请求 skillNames
     * @param actionBoundNames 动作映射表绑定名
     * @return 已加载技能
     */
    List<LoadedSkill> loadSkills(PackEntry pack, List<String> explicitNames, List<String> actionBoundNames) {
        LinkedHashSet<String> explicit = new LinkedHashSet<>(explicitNames);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.addAll(explicitNames);
        ordered.addAll(actionBoundNames);
        List<LoadedSkill> skills = new ArrayList<>();
        for (String name : ordered) {
            boolean fromRequest = explicit.contains(name);
            if (fromRequest && !pack.skills().contains(name)) {
                throw invalid("Skill 不属于该能力包：" + name);
            }
            Optional<LoadedSkill> loaded = skillCatalog.load(name);
            if (loaded.isEmpty()) {
                if (fromRequest) {
                    throw conflict("Skill 不可用：" + name + "（" + skillCatalog.status(name).reason() + "）");
                }
                continue;
            }
            skills.add(loaded.get());
        }
        return skills;
    }

    /**
     * 规范化请求摘要（同幂等键不同请求体判冲突）。字段顺序固定，列表去重排序。
     *
     * @param projectId 项目
     * @param req 请求
     * @return SHA-256
     */
    public static String requestDigest(Long projectId, AgentRunCreateReq req) {
        String canonical = String.join("\n",
            String.valueOf(projectId), req.capabilityPackCode(), req.capabilityPackVersion(),
            req.modelConfigId().trim(), String.join(",", sorted(req.skillNames())),
            String.join(",", sorted(req.toolIds())), isBlank(req.actionCode()) ? "" : req.actionCode().trim(),
            req.message(), isBlank(req.productLineId()) ? "" : req.productLineId().trim());
        return ProjectAgentSkillCatalog.sha256Hex(canonical);
    }

    private static Long parseModelConfigId(String raw) {
        if (isBlank(raw)) {
            throw invalid("modelConfigId 必填");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid("modelConfigId 格式错误");
        }
    }

    private static List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String v : values) {
            if (!isBlank(v)) {
                set.add(v.trim());
            }
        }
        return List.copyOf(set);
    }

    private static List<String> sorted(List<String> values) {
        return distinct(values).stream().sorted().toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IpdBusinessException invalid(String message) {
        return new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, message);
    }

    private static IpdBusinessException conflict(String message) {
        return new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, message);
    }
}
