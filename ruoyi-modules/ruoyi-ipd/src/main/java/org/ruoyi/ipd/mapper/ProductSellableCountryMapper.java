package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.ruoyi.ipd.vo.ProductSellableCountryRow;

import java.util.Collection;
import java.util.List;

/**
 * 产品可销售国家只读查询。不提供插入、更新或删除。
 */
@Mapper
public interface ProductSellableCountryMapper {

    /**
     * 按产品主键读取未删除国家行。
     *
     * @param ids 产品主键，调用方保证非空
     * @return 国家码与国家名
     */
    @Select("""
        <script>
        SELECT product_id AS productId, country_code AS countryCode, country_name AS countryName
        FROM product_sellable_countries
        WHERE del_flag = '0' AND product_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY country_code
        </script>
        """)
    List<ProductSellableCountryRow> selectLiveByProductIds(@Param("ids") Collection<Long> ids);
}
