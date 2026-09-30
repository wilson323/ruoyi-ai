package org.ruoyi.ipd.agent.kernel;

import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;

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
 */
public record ProjectAgentRunSpec(Long runId, Long projectId, String tenantId, Long personId,
                                  String actionCode, String message, List<LoadedSkill> skills,
                                  List<String> toolIds, KernelModelRequest model, Duration timeout) {

    /** 规范化并校验必填项。 */
    public ProjectAgentRunSpec {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(personId, "personId");
        Objects.requireNonNull(model, "model");
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
