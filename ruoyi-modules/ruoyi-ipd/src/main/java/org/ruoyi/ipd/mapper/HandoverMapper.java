package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.HandoverRecord;

/**
 * 移交记录 Mapper（P2-7.1；表 handover_records 为 P0 铺设，P2-7.1 起用）。
 */
@Mapper
public interface HandoverMapper extends BaseMapperPlus<HandoverRecord, HandoverRecord> {
}
