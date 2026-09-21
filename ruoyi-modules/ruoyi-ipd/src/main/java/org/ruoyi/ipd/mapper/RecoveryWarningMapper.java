package org.ruoyi.ipd.mapper;

import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.RecoveryWarning;

/**
 * 90 日回款预警 Mapper（C1；table = recovery_warnings，R149 batch2a）
 *
 * <p>复用 MyBatis-Plus {@link BaseMapperPlus} 提供默认 CRUD；
 * 列表查询 / 幂等去重走 Service 层 {@code LambdaQueryWrapper} 组装。
 */
public interface RecoveryWarningMapper extends BaseMapperPlus<RecoveryWarning, RecoveryWarning> {
}
