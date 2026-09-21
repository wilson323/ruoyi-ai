package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.HrOrganization;

/**
 * HR 组织 Mapper（FA-HR-Sync R149-v1 D6）。
 *
 * <p>对应表 {@code hr_organizations}（HR 真源公司组织树；独立于 product_groups）。
 */
@Mapper
public interface HrOrganizationMapper extends BaseMapperPlus<HrOrganization, HrOrganization> {
}
