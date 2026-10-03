package org.ruoyi.ipd.agent.kernel;

import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import org.ruoyi.ipd.agent.service.ProjectAgentAguiPauseResumeService.ChildResume;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 一次运行交给内核的完整输入（创建时冻结，只在内存传递；模型凭据不落库）。
 *
 * @param runId 运行 ID（四维隔离键 session 段）
 * @param projectId 已授权项目（工具项目范围硬绑定）
 * @param tenantId 可信租户
 * @param personId 发起人（四维隔离键 user 段）
 * @param actionCode 动作编码（可空）
 * @param message 用户输入
 * @param skills 已通过完整性校验的 Skill 正文
 * @param toolIds 选定工具 ID
 * @param model 选定模型装配请求
 * @param timeout 整轮超时
 * @param requirementId 分拣需求单，可空；成功后优先按运行开始时的目录编码回写
 * @param catalogAppendix 分拣时附上的需求标题和目录编码，可空
 * @param projectFacts 授权后从项目与产品记录读出的事实；无映射时为空，不含用户原话
 */
public record ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                                  String actionCode, String message, List<LoadedSkill> skills,
                                  List<String> toolIds, KernelModelRequest model, Duration timeout,
                                  Long requirementId, String catalogAppendix, String projectFacts, RunAgentInput aguiInput, List<Msg> serverResumeMessages,
                                  List<String> executionToolIds, List<ChildResume> serverChildResumes, FrozenModels frozenModels) {

    /** In-memory resolved requests; absence is only for historical direct test/API compatibility. */
    public record FrozenModels(KernelModelRequest primary, KernelModelRequest fallback,
            org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity primaryIdentity,
            org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity fallbackIdentity) {
        public FrozenModels(KernelModelRequest primary, KernelModelRequest fallback) { this(primary, fallback, null, null); }
        public FrozenModels {
            Objects.requireNonNull(primary, "primary model");
            if (primaryIdentity != null) {
                if (!primaryIdentity.equals(org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity.of(primaryIdentity.modelConfigId(), primary))
                    || (fallback == null) != (fallbackIdentity == null)
                    || (fallbackIdentity != null && !fallbackIdentity.equals(org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity.of(fallbackIdentity.modelConfigId(), fallback)))) {
                    throw new IllegalArgumentException("冻结模型身份与实际请求不一致");
                }
            } else if (fallbackIdentity != null) {
                throw new IllegalArgumentException("主模型身份缺失");
            }
        }
    }
    public ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                               String actionCode, String message, List<LoadedSkill> skills,
                               List<String> toolIds, KernelModelRequest model, Duration timeout,
                               Long requirementId, String catalogAppendix, String projectFacts,
                               RunAgentInput aguiInput, List<Msg> serverResumeMessages,
                               List<String> executionToolIds, List<ChildResume> serverChildResumes) {
        this(runId, projectId, tenantId, personId, actionCode, message, skills, toolIds, model, timeout,
            requirementId, catalogAppendix, projectFacts, aguiInput, serverResumeMessages,
            executionToolIds, serverChildResumes, null);
    }
    public ProjectAgentRunSpec withFrozenModels(KernelModelRequest primary, KernelModelRequest fallback,
                                                Long primaryId, Long fallbackId) {
        var identities = new FrozenModels(primary, fallback,
            org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity.of(primaryId, primary),
            fallback == null ? null : org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity.of(fallbackId, fallback));
        return new ProjectAgentRunSpec(runId, projectId, tenantId, personId, actionCode, message, skills,
            toolIds, model, timeout, requirementId, catalogAppendix, projectFacts, aguiInput,
            serverResumeMessages, executionToolIds, serverChildResumes, identities);
    }
    public ProjectAgentRunSpec withFrozenModels(KernelModelRequest primary, KernelModelRequest fallback) {
        return new ProjectAgentRunSpec(runId, projectId, tenantId, personId, actionCode, message, skills,
            toolIds, model, timeout, requirementId, catalogAppendix, projectFacts, aguiInput,
            serverResumeMessages, executionToolIds, serverChildResumes, new FrozenModels(primary, fallback));
    }

    /** 兼容原执行集合规格；子恢复数据只来自服务器审批消费。 */
    public ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                               String actionCode, String message, List<LoadedSkill> skills,
                               List<String> toolIds, KernelModelRequest model, Duration timeout,
                               Long requirementId, String catalogAppendix, String projectFacts,
                               RunAgentInput aguiInput, List<Msg> serverResumeMessages, List<String> executionToolIds) {
        this(runId, projectId, tenantId, personId, actionCode, message, skills, toolIds, model, timeout,
            requirementId, catalogAppendix, projectFacts, aguiInput, serverResumeMessages, executionToolIds, null);
    }

    /** 兼容旧完整规格；null 表示历史未冻结执行集合，不推断历史工具注册证据。 */
    public ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                               String actionCode, String message, List<LoadedSkill> skills,
                               List<String> toolIds, KernelModelRequest model, Duration timeout,
                               Long requirementId, String catalogAppendix, String projectFacts,
                               RunAgentInput aguiInput, List<Msg> serverResumeMessages) {
        this(runId, projectId, tenantId, personId, actionCode, message, skills, toolIds, model, timeout,
            requirementId, catalogAppendix, projectFacts, aguiInput, serverResumeMessages, null);
    }

    /** 兼容仅官方创建输入的规格。恢复消息只能由服务器消费检查点后赋值。 */
    public ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                               String actionCode, String message, List<LoadedSkill> skills,
                               List<String> toolIds, KernelModelRequest model, Duration timeout,
                               Long requirementId, String catalogAppendix, String projectFacts, RunAgentInput aguiInput) {
        this(runId, projectId, tenantId, personId, actionCode, message, skills, toolIds, model, timeout,
            requirementId, catalogAppendix, projectFacts, aguiInput, null);
    }

    /** 兼容既有完整规格，旧运行不携带 AG-UI 输入。 */
    public ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                               String actionCode, String message, List<LoadedSkill> skills,
                               List<String> toolIds, KernelModelRequest model, Duration timeout,
                               Long requirementId, String catalogAppendix, String projectFacts) {
        this(runId, projectId, tenantId, personId, actionCode, message, skills, toolIds, model, timeout,
            requirementId, catalogAppendix, projectFacts, null);
    }

    /**
     * 兼容未带目录摘录的调用。
     */
    public ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                               String actionCode, String message, List<LoadedSkill> skills,
                               List<String> toolIds, KernelModelRequest model, Duration timeout,
                               Long requirementId) {
        this(runId, projectId, tenantId, personId, actionCode, message, skills, toolIds, model, timeout,
            requirementId, null, null);
    }

    /**
     * 兼容未带需求单的调用。
     */
    public ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                               String actionCode, String message, List<LoadedSkill> skills,
                               List<String> toolIds, KernelModelRequest model, Duration timeout) {
        this(runId, projectId, tenantId, personId, actionCode, message, skills, toolIds, model, timeout,
            null, null, null);
    }

    /**
     * 换上分拣目录摘录，其余字段不变。
     *
     * @param catalogAppendix 需求标题和可写回编码
     * @return 新的运行输入
     */
    public ProjectAgentRunSpec withCatalog(String catalogAppendix) {
        return new ProjectAgentRunSpec(runId, projectId, tenantId, personId, actionCode, message, skills,
            toolIds, model, timeout, requirementId, catalogAppendix, projectFacts, aguiInput, serverResumeMessages, executionToolIds, serverChildResumes, frozenModels);
    }

    /**
     * 附上授权后读出的项目事实，其余字段保持冻结值。
     *
     * @param facts 已格式化的项目事实；无映射时为空
     * @return 带项目事实的新规格
     */
    public ProjectAgentRunSpec withProjectFacts(String facts) {
        return new ProjectAgentRunSpec(runId, projectId, tenantId, personId, actionCode, message, skills,
            toolIds, model, timeout, requirementId, catalogAppendix, facts, aguiInput, serverResumeMessages, executionToolIds, serverChildResumes, frozenModels);
    }

    /** 保留官方完整输入；此字段本身不授予业务权限。 */
    public ProjectAgentRunSpec withAguiInput(RunAgentInput input) {
        return new ProjectAgentRunSpec(runId, projectId, tenantId, personId, actionCode, message, skills,
            toolIds, model, timeout, requirementId, catalogAppendix, projectFacts, input, serverResumeMessages, executionToolIds, serverChildResumes, frozenModels);
    }

    /** 经原运行归属、审批及检查点 CAS 校验后得到的官方消息，不接受客户端 DTO 直接赋值。 */
    public ProjectAgentRunSpec withServerResumeMessages(List<Msg> messages) {
        return new ProjectAgentRunSpec(runId, projectId, tenantId, personId, actionCode, message, skills,
            toolIds, model, timeout, requirementId, catalogAppendix, projectFacts, aguiInput,
            List.copyOf(Objects.requireNonNull(messages, "server resume messages")), executionToolIds, serverChildResumes, frozenModels);
    }

    /** 服务器从原配置快照传入；与业务能力包 toolIds 分开，不接受客户端直接授权。 */
    public ProjectAgentRunSpec withExecutionToolIds(List<String> ids) {
        return new ProjectAgentRunSpec(runId, projectId, tenantId, personId, actionCode, message, skills,
            toolIds, model, timeout, requirementId, catalogAppendix, projectFacts, aguiInput,
            serverResumeMessages, ids, serverChildResumes, frozenModels);
    }

    /** 仅服务器审批消费后的子确认消息；不向创建 DTO 开放。 */
    public ProjectAgentRunSpec withServerChildResumes(List<ChildResume> resumes) {
        return new ProjectAgentRunSpec(runId, projectId, tenantId, personId, actionCode, message, skills,
            toolIds, model, timeout, requirementId, catalogAppendix, projectFacts, aguiInput,
            serverResumeMessages, executionToolIds, List.copyOf(Objects.requireNonNull(resumes, "server child resumes")), frozenModels);
    }

    /** 规范化并校验必填项。 */
    public ProjectAgentRunSpec {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(personId, "personId");
        Objects.requireNonNull(model, "model");
        if (frozenModels != null && !model.equals(frozenModels.primary())) {
            throw new IllegalArgumentException("Frozen primary model does not match run input");
        }
        serverResumeMessages = serverResumeMessages == null ? null : List.copyOf(serverResumeMessages);
        executionToolIds = executionToolIds == null ? null : List.copyOf(executionToolIds);
        serverChildResumes = serverChildResumes == null ? List.of() : List.copyOf(serverChildResumes);
        skills = skills == null ? List.of() : List.copyOf(skills);
        toolIds = toolIds == null ? List.of() : List.copyOf(toolIds);
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
    }

    /** 凭据脱敏（KernelModelRequest 自身已脱敏，不输出用户原文）。 */
    @Override
    public String toString() {
        return "ProjectAgentRunSpec[runId=" + runId + ", projectId=" + projectId + ", actionCode=" + actionCode
            + ", skills=" + skills.stream().map(LoadedSkill::name).toList() + ", toolIds=" + toolIds
            + ", model=" + model + "]";
    }
}
