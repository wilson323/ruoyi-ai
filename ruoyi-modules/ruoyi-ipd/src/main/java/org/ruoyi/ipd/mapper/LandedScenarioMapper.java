package org.ruoyi.ipd.mapper;

import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.LandedScenario;

/**
 * 落地场景 Mapper（A4 落地场景登记；table = landed_scenarios，R149 batch2a）
 *
 * <p>复用 MyBatis-Plus {@link BaseMapperPlus} 提供默认 CRUD；
 * 列表查询走 Service 层 {@code LambdaQueryWrapper} 组装。
 */
public interface LandedScenarioMapper extends BaseMapperPlus<LandedScenario, LandedScenario> {
}
