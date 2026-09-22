package org.ruoyi.ipd.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * KPI 功能指标量表录入 / 更新请求（A2 P1，R148.1 §2.2）。
 *
 * <p>PUT /api/v1/kpi/functional-metrics 的 body；以 (projectId, metricCode, period)
 * 为幂等键做 upsert（同键覆盖，不追加行）。
 *
 * <p>metricValue / targetValue 允许为空——为空表示「待补充」，不等同 0 分
 * （DOC-01 §4：功能指标不因默认 0 而把无数据当 0）；但两者不可同时为空。
 */
public record UpsertKpiFunctionalMetricReq(
    @NotNull(message = "projectId 不能为空")
    Long projectId,
    @NotBlank(message = "metricCode 不能为空")
    @Size(max = 50, message = "metricCode 长度不能超过 50")
    String metricCode,
    @NotBlank(message = "period 不能为空")
    @Size(max = 20, message = "period 长度不能超过 20")
    String period,
    @DecimalMin(value = "0.0", message = "metricValue 不能为负数")
    BigDecimal metricValue,
    @DecimalMin(value = "0.0", message = "targetValue 不能为负数")
    BigDecimal targetValue,
    @Size(max = 50, message = "scaleVersion 长度不能超过 50")
    String scaleVersion,
    @Size(max = 500, message = "remark 长度不能超过 500")
    String remark
) {
}
