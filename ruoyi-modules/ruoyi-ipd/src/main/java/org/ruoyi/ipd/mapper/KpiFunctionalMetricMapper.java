package org.ruoyi.ipd.mapper;

import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.KpiFunctionalMetric;

/**
 * KPI 功能指标量表 Mapper（A2 P1；table = kpi_functional_metrics）。
 *
 * <p>复用 MyBatis-Plus {@link BaseMapperPlus} 默认 CRUD；
 * 列表 / upsert 定位（project + metricCode + period）走 Service 层 {@code LambdaQueryWrapper}。
 */
public interface KpiFunctionalMetricMapper extends BaseMapperPlus<KpiFunctionalMetric, KpiFunctionalMetric> {
}
