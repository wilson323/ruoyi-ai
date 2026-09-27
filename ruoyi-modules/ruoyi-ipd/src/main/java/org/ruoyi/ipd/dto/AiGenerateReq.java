package org.ruoyi.ipd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * P4-2.2 AI 生成请求（AC-AI-02：PM 录入原始资料调用 AI）。
 * prompt = PM 录入的原始资料/生成指令；生成结果登记为版本链 v1（status=GENERATED 待审核）。
 * 校验以 service 层为准（与 CreateReq 同惯例，注解作契约文档）。
 * <p>AI-P1-1：新增可选字段 promptType（PRD/MRD/BRD/CHARTER/TEST_REPORT/RELEASE_NOTE/REVIEW，
 * 值域见 {@link org.ruoyi.ipd.domain.PromptType}）。为空 = 裸 prompt 直传老逻辑
 * （向后兼容验收点：老前端不传该字段零感知）；非法值由 service 层拒绝（PARAM_INVALID）。
 * 保留 4 参兼容构造器，既有调用点（aiexec GenerateExecutor 等）零改动。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiGenerateReq(@NotNull Long projectId,
                            @Size(max = 32) String docType,
                            @NotBlank @Size(max = 200) String title,
                            @NotBlank @Size(max = 30000) String prompt,
                            @Size(max = 32) String promptType) {

    /** 向后兼容构造器：不传 promptType ⇒ null ⇒ 裸 prompt 直传老逻辑（AI-P1-1 兼容验收点）。 */
    public AiGenerateReq(Long projectId, String docType, String title, String prompt) {
        this(projectId, docType, title, prompt, null);
    }
}
