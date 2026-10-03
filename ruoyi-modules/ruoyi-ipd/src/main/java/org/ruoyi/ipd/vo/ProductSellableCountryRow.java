package org.ruoyi.ipd.vo;

import lombok.Data;

/**
 * product_sellable_countries 的只读投影。只含产品主键、国家码和国家名。
 */
@Data
public class ProductSellableCountryRow {

    private Long productId;

    private String countryCode;

    private String countryName;
}
