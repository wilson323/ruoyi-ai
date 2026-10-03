package org.ruoyi.ipd.vo;

import org.ruoyi.ipd.domain.Product;

import java.util.Date;
import java.util.List;

/**
 * View: Product 的对外暴露视图，移除内部字段（tenantId / delFlag / createBy / updateBy / createDept / params）。
 * 对应接口：/api/v1/products。sellableCountries 只读，数据只来自 product_sellable_countries。
 */
public record ProductVO(
    Long id,
    String productCode,
    String productName,
    String modelCode,
    String source,
    Long projectId,
    Long groupId,
    String status,
    Date createTime,
    Date updateTime,
    List<SellableCountry> sellableCountries
) {
    /**
     * 可销售国家。只含国家码和国家名，不含证据地址。
     */
    public record SellableCountry(String countryCode, String countryName) {
    }

    /**
     * 不带国家行的视图。国家列表为空，不代表已查过表。
     */
    public static ProductVO from(Product p) {
        return from(p, List.of());
    }

    /**
     * 组装产品视图。国家只取调用方传入的表行，不补默认国家。
     *
     * @param p 产品
     * @param countries 该产品在 product_sellable_countries 的未删除行
     * @return 对外视图
     */
    public static ProductVO from(Product p, List<ProductSellableCountryRow> countries) {
        List<SellableCountry> views = countries == null ? List.of() : countries.stream()
            .filter(row -> row != null && row.getCountryCode() != null)
            .map(row -> new SellableCountry(row.getCountryCode(), row.getCountryName()))
            .toList();
        return new ProductVO(
            p.getId(),
            p.getProductCode(),
            p.getProductName(),
            p.getModelCode(),
            p.getSource(),
            p.getProjectId(),
            p.getGroupId(),
            p.getStatus(),
            p.getCreateTime(),
            p.getUpdateTime(),
            views
        );
    }
}