package org.ruoyi.ipd.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * KPI 原始数据录入请求（A2 KPI P1 期补口，R149 batch2a）。
 *
 * <p>由组长录入某项目某 KPI 类型某月的原始值；
 * service 层强制唯一性 (kpi_type, project_id, record_period)。
 */
public record CreateKpiRawRecordReq(
    @NotBlank(message = "kpiType 不能为空")
    String kpiType,
    @NotNull(message = "projectId 不能为空")
    Long projectId,
    @NotNull(message = "recordPeriod 不能为空（YYYY-MM-01）")
    LocalDate recordPeriod,
    @NotNull(message = "rawValue 不能为空")
    @DecimalMin(value = "0.0", message = "rawValue 不能为负数")
    BigDecimal rawValue
) {
}
