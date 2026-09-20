package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.Contribution;

/**
 * 贡献度评定 Mapper（P3-6.2；BR-INC-09 / AC-INC-25~28）
 */
@Mapper
public interface ContributionMapper extends BaseMapperPlus<Contribution, Contribution> {
}