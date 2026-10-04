package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.Product;

/**
 * 产品 Mapper
 */
@Mapper
public interface ProductMapper extends BaseMapperPlus<Product, Product> {
    /** Caller runs inside the existing write transaction. Lock the row even when currently writable. */
    @org.apache.ibatis.annotations.Select("SELECT CASE WHEN retirement_locked='1' THEN TRUE ELSE FALSE END "
        + "FROM products WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    boolean isRetirementLockedForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);
    /** Sole retirement approval consumer; ordinary entity edits cannot modify these columns. */
    @org.apache.ibatis.annotations.Update("UPDATE products SET retirement_locked='1', retired_at=#{approvedAt} "
        + "WHERE id=#{id} AND tenant_id=#{tenantId} AND del_flag='0' AND retirement_locked='0'")
    int approveRetirement(@org.apache.ibatis.annotations.Param("id") Long id,
        @org.apache.ibatis.annotations.Param("tenantId") String tenantId,
        @org.apache.ibatis.annotations.Param("approvedAt") java.util.Date approvedAt);
}
