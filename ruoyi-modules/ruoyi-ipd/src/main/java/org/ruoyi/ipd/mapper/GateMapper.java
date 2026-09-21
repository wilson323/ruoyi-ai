package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.Gate;

/**
 * Gate 实例 Mapper（P0-6.2 SoftDelete / Gate 评审）。
 */
@Mapper
public interface GateMapper extends BaseMapperPlus<Gate, Gate> {
}
