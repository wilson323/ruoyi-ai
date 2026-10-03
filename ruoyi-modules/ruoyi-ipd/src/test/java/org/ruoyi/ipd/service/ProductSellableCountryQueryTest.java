package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.mapper.ProductSellableCountryMapper;
import org.ruoyi.ipd.vo.ProductSellableCountryRow;
import org.ruoyi.ipd.vo.ProductVO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可销售国家只从传入行组装，不补国家。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProductSellableCountryQueryTest {

    @Mock
    private ProductSellableCountryMapper mapper;

    @InjectMocks
    private ProductSellableCountryQuery query;

    @Test
    @DisplayName("空产品列表不查表")
    void emptyIds_doNotQuery() {
        assertThat(query.listByProductIds(List.of())).isEmpty();
        verify(mapper, never()).selectLiveByProductIds(anyCollection());
    }

    @Test
    @DisplayName("只把表行上的 CN/中国 放进产品视图")
    void mapsOnlyTableRows() {
        ProductSellableCountryRow row = new ProductSellableCountryRow();
        row.setProductId(2105441747105501186L);
        row.setCountryCode("CN");
        row.setCountryName("中国");
        when(mapper.selectLiveByProductIds(anyCollection())).thenReturn(List.of(row));

        var grouped = query.listByProductIds(List.of(2105441747105501186L));
        Product product = new Product();
        product.setId(2105441747105501186L);
        product.setProductCode("ON-SALE");
        ProductVO vo = ProductVO.from(product, grouped.get(2105441747105501186L));

        assertThat(vo.sellableCountries())
            .containsExactly(new ProductVO.SellableCountry("CN", "中国"));
    }
}
