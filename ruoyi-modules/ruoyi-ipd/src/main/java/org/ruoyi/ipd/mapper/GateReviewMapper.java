package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.GateReview;

/**
 * Gate 双签记录 Mapper（P2-5.2 盲签与领域签署；表 gate_reviews，uk gate+reviewer_type+round）。
 */
@Mapper
public interface GateReviewMapper extends BaseMapperPlus<GateReview, GateReview> {
}
