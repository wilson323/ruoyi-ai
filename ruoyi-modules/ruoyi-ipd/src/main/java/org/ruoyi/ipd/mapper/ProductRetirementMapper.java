package org.ruoyi.ipd.mapper;
import org.apache.ibatis.annotations.*;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.ProductRetirement;
public interface ProductRetirementMapper extends BaseMapperPlus<ProductRetirement,ProductRetirement> {
 @Select("SELECT * FROM product_retirements WHERE product_id=#{productId} AND tenant_id=#{tenantId} AND del_flag='0' FOR UPDATE")
 ProductRetirement findForUpdate(@Param("productId") Long productId,@Param("tenantId") String tenantId);
}
