package org.ruoyi.ipd.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 落地场景登记请求（A4 落地场景登记，R149 batch2a）。
 *
 * <p>用户拍板简化：仅登记（不做双认定），单条录入接口。
 */
public record CreateLandedScenarioReq(
    @NotNull(message = "projectId 不能为空")
    Long projectId,
    @NotBlank(message = "scenarioCode 不能为空")
    @Size(max = 64, message = "scenarioCode 长度不能超过 64")
    String scenarioCode,
    @NotBlank(message = "scenarioName 不能为空")
    @Size(max = 200, message = "scenarioName 长度不能超过 200")
    String scenarioName,
    @NotNull(message = "landedDate 不能为空")
    LocalDate landedDate,
    BigDecimal landedAmount,
    @Size(max = 500, message = "remark 长度不能超过 500")
    String remark
) {
}
