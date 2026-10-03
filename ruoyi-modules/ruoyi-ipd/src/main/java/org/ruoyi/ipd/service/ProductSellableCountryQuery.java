package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.mapper.ProductSellableCountryMapper;
import org.ruoyi.ipd.vo.ProductSellableCountryRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按产品读取可销售国家。只查 product_sellable_countries，不写库。
 */
@Service
@RequiredArgsConstructor
public class ProductSellableCountryQuery {

    private final ProductSellableCountryMapper mapper;

    /**
     * 读取一个产品的未删除国家行。
     *
     * @param productId 产品主键；空则返回空列表
     * @return 该产品的国家行，没有则空列表
     */
    public List<ProductSellableCountryRow> listByProductId(Long productId) {
        if (productId == null) {
            return List.of();
        }
        return listByProductIds(List.of(productId)).getOrDefault(productId, List.of());
    }

    /**
     * 批量读取未删除国家行，按产品分组。
     *
     * @param productIds 产品主键集合
     * @return 产品 id 到国家行；没有国家的产品不出现在结果里
     */
    public Map<Long, List<ProductSellableCountryRow>> listByProductIds(Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = productIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ProductSellableCountryRow> rows = mapper.selectLiveByProductIds(ids);
        Map<Long, List<ProductSellableCountryRow>> grouped = new LinkedHashMap<>();
        if (rows == null) {
            return grouped;
        }
        for (ProductSellableCountryRow row : rows) {
            if (row == null || row.getProductId() == null || row.getCountryCode() == null) {
                continue;
            }
            grouped.computeIfAbsent(row.getProductId(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }
}
