package org.ruoyi.ipd.mapper;

import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.KpiRawRecord;

/**
 * KPI 原始数据 Mapper（A2 KPI P1 期补口；table = kpi_raw_records，R149 batch2a）
 *
 * <p>复用 MyBatis-Plus {@link BaseMapperPlus} 提供默认 CRUD；
 * 唯一性 / 列表查询走 Service 层 {@code LambdaQueryWrapper} 组装。
 */
public interface KpiRawRecordMapper extends BaseMapperPlus<KpiRawRecord, KpiRawRecord> {
}
