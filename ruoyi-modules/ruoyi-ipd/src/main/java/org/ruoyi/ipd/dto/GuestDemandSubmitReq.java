package org.ruoyi.ipd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * P4-1.1 游客需求提交请求（页38 字段模型；TS-06 requirements）。
 * <p>白名单 DTO：未知字段一律忽略，表单不含「负责人」字段（由系统路由，BR-REQ-02）。</p>
 *
 * <p>校验对齐 GuestDemandService.validate() 兜底契约（4 个非蜜罐必填字段 + 2 个可空长度上限）；
 * productId 允许 null（"三情形选择：其他/不确定"路由模式 AC-PROD-08，service.submit() line 119 显式判断）；
 * 蜜罐字段 website 不加注解（由 service 用作蜜罐识别，BR-REQ-04）。Controller 已加 @Valid，
 * 校验在反序列化阶段触发，错误位置贴近用户最近点（HTTP 层）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuestDemandSubmitReq(
    @NotBlank @Size(min = 2, max = 120) String customerName,
    @NotBlank @Size(min = 2, max = 64) String feedbackPerson,
    @Size(max = 128) String contact,
    Long productId,
    @Size(max = 64) String rawModel,
    @NotBlank @Size(min = 6, max = 4000) String functionalRequirement,
    String website
) {
}