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
import org.ruoyi.ipd.agent.kernel.ProductLineMcpQuery;
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
 * 无文件或摘要不符则拒绝本次运行，不跳过、不编造。NULL 绑定不注入任何动作技能。
 */
public class ProjectAgentRunPlanner {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_\\-]{8,64}");

    /** 校验通过后的冻结计划。 */
    public record RunPlan(PackEntry pack, Long modelConfigId, KernelModelRequest model, List<LoadedSkill> skills,
                          List<String> toolIds, String actionCode, ConfigSnapshot snapshot, KernelModelRequest fallbackModel, Long fallbackModelConfigId) {
        public RunPlan(PackEntry pack, Long modelConfigId, KernelModelRequest model, List<LoadedSkill> skills,
                       List<String> toolIds, String actionCode, ConfigSnapshot snapshot, KernelModelRequest fallbackModel) {
            this(pack, modelConfigId, model, skills, toolIds, actionCode, snapshot, fallbackModel, null);
        }
        public RunPlan(PackEntry pack, Long modelConfigId, KernelModelRequest model, List<LoadedSkill> skills,
                       List<String> toolIds, String actionCode, ConfigSnapshot snapshot) {
            this(pack, modelConfigId, model, skills, toolIds, actionCode, snapshot, null);
        }
        /** 与业务选择分离；null 表示历史快照，不自动扩权。 */
        public List<String> executionToolIds() { return snapshot.executionToolIds(); }
    }

    private final CapabilityManifest manifest;
    private final ProjectAgentSkillCatalog skillCatalog;
    private final ProjectAgentToolCatalog toolCatalog;
    private final ProjectAgentModelCatalog modelCatalog;
    private final IpdActionSkillMapService skillMapService;
    private java.util.function.BiFunction<RunPlan, io.agentscope.core.agui.model.RunAgentInput,
        java.util.Map<String, io.agentscope.core.agui.model.AguiTool>> aguiFrontendToolResolver;

    /** 装配真实服务器前端工具目录；浏览器 schema 不能作为授权目录。 */
    public void setAguiFrontendToolResolver(java.util.function.BiFunction<RunPlan,
            io.agentscope.core.agui.model.RunAgentInput,
            java.util.Map<String, io.agentscope.core.agui.model.AguiTool>> resolver) {
        aguiFrontendToolResolver = java.util.Objects.requireNonNull(resolver);
    }

    public AgentRunCreateReq freezeRequest(AgentRunCreateReq req) {
        if (req == null) return null;
        return new AgentRunCreateReq(req.capabilityPackCode(), req.capabilityPackVersion(), req.modelConfigId(),
            req.skillNames() == null ? null : java.util.Collections.unmodifiableList(new ArrayList<>(req.skillNames())),
            req.toolIds() == null ? null : java.util.Collections.unmodifiableList(new ArrayList<>(req.toolIds())), req.actionCode(),
            req.message() == null ? "" : req.message(), req.idempotencyKey(), req.productLineId(),
            req.requirementId(), req.previousRunId(), req.targetDocumentId(), req.baseVersionId(),
            org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.freeze(req.aguiInput()));
    }

    public io.agentscope.core.agui.model.RunAgentInput bindAguiInput(AgentRunCreateReq req,
            RunPlan plan, Long serverRunId) {
        if (req.aguiInput() == null) return io.agentscope.core.agui.model.RunAgentInput.builder()
            .threadId(String.valueOf(serverRunId)).runId(String.valueOf(serverRunId))
            .tools(List.of(org.ruoyi.ipd.agent.kernel.ProjectAgentOutputContract.clarificationTool())).build();
        var catalog = authorizedAguiFrontendTools(plan, req.aguiInput());
        try {
            var bound = org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.bind(req.aguiInput(),
                String.valueOf(serverRunId), String.valueOf(serverRunId), catalog);
            org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.messages(bound, java.util.Map.of());
            var tools = new java.util.ArrayList<>(bound.getTools());
            tools.removeIf(t -> org.ruoyi.ipd.agent.kernel.ProjectAgentOutputContract.CLARIFICATION_TOOL.equals(t.getName()));
            tools.add(org.ruoyi.ipd.agent.kernel.ProjectAgentOutputContract.clarificationTool());
            return org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.withFrontendTools(bound, List.copyOf(tools));
        } catch (IllegalArgumentException invalidInput) {
            throw invalid("AG-UI 输入不符合当前运行的协议或授权范围");
        }
    }

    public java.util.Map<String, io.agentscope.core.agui.model.AguiTool> authorizedAguiFrontendTools(
            RunPlan plan, io.agentscope.core.agui.model.RunAgentInput input) {
        java.util.Map<String, io.agentscope.core.agui.model.AguiTool> catalog = java.util.Map.of();
        if (input.getTools().stream().anyMatch(t -> !org.ruoyi.ipd.agent.kernel.ProjectAgentOutputContract.CLARIFICATION_TOOL.equals(t.getName()))) {
            if (aguiFrontendToolResolver == null) throw conflict("前端工具授权目录尚未装配");
            catalog = aguiFrontendToolResolver.apply(plan, input);
            if (catalog == null) throw conflict("前端工具授权目录不可用");
        }
        var result = new java.util.LinkedHashMap<>(catalog);
        result.put(org.ruoyi.ipd.agent.kernel.ProjectAgentOutputContract.CLARIFICATION_TOOL,
            org.ruoyi.ipd.agent.kernel.ProjectAgentOutputContract.clarificationTool());
        return java.util.Map.copyOf(result);
    }

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
        if (isBlank(req.message()) && (req.aguiInput() == null || !req.aguiInput().hasMessages())) {
            throw invalid("message 必填");
        }
        if (req.message() != null && req.message().length() > ProjectAgentConstants.MESSAGE_MAX_CHARS) {
            throw invalid("message 超过 " + ProjectAgentConstants.MESSAGE_MAX_CHARS + " 字");
        }
        if (req.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(req.idempotencyKey()).matches()) {
            throw invalid("idempotencyKey 须为 8~64 位字母、数字、下划线或连字符");
        }
        if (req.aguiInput() != null && !req.aguiInput().getResume().isEmpty()) {
            throw invalid("中断响应须恢复原运行，不能创建另一运行");
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
        return plan(req, null, null, null, null);
    }

    /** 新运行仅可显式选择当前用户、当前项目已发布的技能。 */
    public RunPlan plan(AgentRunCreateReq req, String tenantId, Long projectId, Long personId) {
        return plan(req, tenantId, projectId, personId, null);
    }

    /** 恢复使用原摘要定位不可变发布版本；不能用最新技能替换原运行。 */
    public RunPlan plan(AgentRunCreateReq req, String tenantId, Long projectId, Long personId,
                        List<SkillRef> frozenSkills) {
        PackEntry pack = manifest.pack(req.capabilityPackCode(), req.capabilityPackVersion())
            .orElseThrow(() -> invalid("能力包不存在：" + req.capabilityPackCode() + "@" + req.capabilityPackVersion()));
        String actionCode = isBlank(req.actionCode()) ? null : req.actionCode().trim();
        if (actionCode != null && !pack.actionCodes().contains(actionCode)) {
            throw invalid("动作 " + actionCode + " 不在能力包适用范围内");
        }
        List<String> explicitNames;
        List<String> actionBoundNames;
        if (frozenSkills == null) {
            explicitNames = distinct(req.skillNames());
            actionBoundNames = resolveActionBoundSkillNames(actionCode);
        } else {
            // 恢复只使用原运行的配置；当前动作映射仅决定新运行，不是业务授权依据。
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (SkillRef frozen : frozenSkills) {
                if (frozen == null || isBlank(frozen.name()) || frozen.sha256() == null
                    || !frozen.sha256().matches("[a-fA-F0-9]{64}") || !names.add(frozen.name())) {
                    throw conflict("原运行的技能冻结身份无法核验");
                }
            }
            explicitNames = List.copyOf(names);
            actionBoundNames = List.of();
        }
        List<LoadedSkill> skills = loadSkills(pack, explicitNames, actionBoundNames, tenantId, projectId, personId, frozenSkills);
        List<String> toolIds = distinct(req.toolIds());
        List<String> selectableToolIds = ProjectAgentToolCatalog.executionToolIds(pack.tools());
        for (String toolId : toolIds) {
            if (!selectableToolIds.contains(toolId)) {
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
        var fallbackSelection = modelCatalog.resolveFallbackSelection(model).orElse(null);
        KernelModelRequest fallback = fallbackSelection == null ? null : fallbackSelection.request();
        Long fallbackId = fallbackSelection == null ? null : fallbackSelection.modelConfigId();
        ConfigSnapshot snapshot = new ConfigSnapshot(pack.code(), pack.version(), String.valueOf(modelConfigId),
            skills.stream().map(s -> new SkillRef(s.name(), s.sha256())).toList(), toolIds,
            req.previousRunId(), req.targetDocumentId(), req.baseVersionId(),
            org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.digest(req.aguiInput()),
            req.requirementId(), req.productLineId())
            .withExecutionToolIds(ProjectAgentToolCatalog.executionToolIds(toolIds))
            .withModelFingerprint(org.ruoyi.ipd.agent.model.ProjectAgentModelFingerprint.capture(modelConfigId, model, fallbackId, fallback))
            .withModelIdentityVersion(1, fallbackId == null ? null : fallbackId.toString())
            .withOutputContractVersion(org.ruoyi.ipd.agent.kernel.ProjectAgentOutputContract.VERSION);
        return new RunPlan(pack, modelConfigId, model, List.copyOf(skills), toolIds, actionCode, snapshot, fallback, fallbackId);
    }

    /** 创建前复用运行装配的候选规则；服务标识必须来自服务端项目归属查询。 */
    public void validateMcpSelection(String storedServiceId, List<String> toolIds) {
        new ProductLineMcpQuery().select(storedServiceId, toolIds);
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
     * <p>显式名须属于能力包且必须可加载（失败 fail-loud）；动作绑定名同样必须经清单校验加载，缺失或摘要不符时拒绝运行。
     *
     * @param pack 能力包
     * @param explicitNames 请求 skillNames
     * @param actionBoundNames 动作映射表绑定名
     * @return 已加载技能
     */
    List<LoadedSkill> loadSkills(PackEntry pack, List<String> explicitNames, List<String> actionBoundNames) {
        return loadSkills(pack, explicitNames, actionBoundNames, null, null, null, null);
    }

    private List<LoadedSkill> loadSkills(PackEntry pack, List<String> explicitNames, List<String> actionBoundNames,
            String tenantId, Long projectId, Long personId, List<SkillRef> frozenSkills) {
        LinkedHashSet<String> explicit = new LinkedHashSet<>(explicitNames);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.addAll(explicitNames);
        ordered.addAll(actionBoundNames);
        List<LoadedSkill> skills = new ArrayList<>();
        for (String name : ordered) {
            boolean fromRequest = explicit.contains(name);
            String digest = null;
            if (frozenSkills != null) {
                digest = frozenSkills.stream().filter(ref -> name.equals(ref.name())).map(SkillRef::sha256)
                    .findFirst().orElseThrow(() -> conflict("原运行没有冻结该技能：" + name));
            }
            boolean scoped = tenantId != null && projectId != null && personId != null;
            // 内置技能继续受能力包约束；包外名字必须是本人本项目已发布的技能，不能按名字放行。
            if (fromRequest && !pack.skills().contains(name)
                && (!scoped || skillCatalog.load(name).isPresent())) {
                throw invalid("Skill 不属于该能力包：" + name);
            }
            Optional<LoadedSkill> loaded = scoped
                ? skillCatalog.load(name, tenantId, projectId, personId, digest) : skillCatalog.load(name);
            if (fromRequest && !pack.skills().contains(name) && loaded.isEmpty()) {
                throw invalid("该技能尚未由你在当前项目审核发布：" + name);
            }
            if (loaded.isEmpty()) {
                throw conflict("Skill 不可用：" + name + "（" + skillCatalog.status(name).reason() + "）");
            }
            if (digest != null && !digest.equals(loaded.get().sha256())) {
                throw conflict("原运行的技能摘要无法核验：" + name);
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
            req.message(), isBlank(req.productLineId()) ? "" : req.productLineId().trim(),
            isBlank(req.requirementId()) ? "" : req.requirementId().trim());
        // 不带返工关联时保持既有摘要，历史幂等请求仍可回放。
        if (req.previousRunId() != null || req.targetDocumentId() != null || req.baseVersionId() != null) {
            canonical += "\n" + String.valueOf(req.previousRunId()) + "\n" + String.valueOf(req.targetDocumentId())
                + "\n" + String.valueOf(req.baseVersionId());
        }
        if (req.aguiInput() != null) {
            canonical += "\nagui:" + org.ruoyi.ipd.agent.kernel.ProjectAgentAguiInput.digest(req.aguiInput());
        }
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
