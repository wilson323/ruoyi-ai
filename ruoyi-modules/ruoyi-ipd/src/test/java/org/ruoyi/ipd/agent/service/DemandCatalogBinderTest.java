package org.ruoyi.ipd.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class DemandCatalogBinderTest {

    @Mock RequirementMapper requirements;
    @Mock ProductLineMapper lines;
    @Mock ProductMapper products;

    @BeforeAll
    static void tableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProductLine.class);
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, Requirement.class);
    }

    @Test
    void uniqueLineAndProductFillEmptyDemand() {
        Requirement requirement = Requirement.builder().id(7L).build();
        when(requirements.selectOne(any())).thenReturn(requirement);
        when(lines.selectList(any())).thenReturn(List.of(ProductLine.builder()
            .id(19L).lineCode("catalog-access").status("ACTIVE").build()));
        when(products.selectList(any())).thenReturn(List.of(), List.of(Product.builder()
            .id(31L).productCode("ZK-X").productLineId(19L).build()));
        when(requirements.update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenReturn(1);
        binder().apply(7L, "结论\n产品线：catalog-access\n产品：ZK-X\n", null, "000000");
        assertThat(requirement.getProductLineId()).isEqualTo(19L);
        assertThat(requirement.getProductId()).isEqualTo(31L);
        verify(requirements).update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    @Test
    void ambiguousOrUnknownDoesNotWrite() {
        Pattern lineMark = Pattern.compile("(?m)^产品线[：:]\\s*(\\S+)\\s*$");
        assertThat(DemandCatalogBinder.singleCode(lineMark, "产品线：A\n产品线：B")).isNull();
        assertThat(DemandCatalogBinder.singleCode(lineMark, "产品线：未取得")).isNull();
        Requirement requirement = Requirement.builder().id(8L).productLineId(1L).build();
        when(requirements.selectOne(any())).thenReturn(requirement);
        when(lines.selectList(any())).thenReturn(List.of(
            ProductLine.builder().id(2L).lineCode("other").status("ACTIVE").build()));
        binder().apply(8L, "产品线：other\n", null, "000000");
        assertThat(requirement.getProductLineId()).isEqualTo(1L);
        verify(requirements, never()).update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    @Test
    void appendixListsCodedCatalogAndSkipsUnspecified() {
        when(requirements.selectOne(any())).thenReturn(Requirement.builder()
            .id(9L).title("门禁考勤一体").content("要刷脸").build());
        when(lines.selectList(any())).thenReturn(List.of(
            ProductLine.builder().id(19L).lineCode("catalog-access").lineName("门禁产品").status("ACTIVE").build(),
            ProductLine.builder().id(1L).lineCode("unspecified").lineName("未指定产品线").status("ACTIVE").build()));
        when(products.selectList(any())).thenReturn(List.of(
            Product.builder().id(31L).productCode("ZK-X").productName("熵基门禁").productLineId(19L).build(),
            Product.builder().id(32L).productName("没有编码").productLineId(19L).build()));
        String appendix = binder().promptAppendix(9L, "000000");
        assertThat(appendix).contains("本张需求标题：门禁考勤一体");
        assertThat(appendix).contains("catalog-access 门禁产品");
        assertThat(appendix).contains("ZK-X 熵基门禁 catalog-access");
        assertThat(appendix).doesNotContain("unspecified");
        assertThat(appendix).doesNotContain("没有编码");
    }

    @Test
    void appendixKeepsCodedProductsPastTheOldCap() {
        when(requirements.selectOne(any())).thenReturn(Requirement.builder().id(10L).title("全目录").build());
        when(lines.selectList(any())).thenReturn(List.of(ProductLine.builder()
            .id(19L).lineCode("catalog-access").lineName("门禁产品").status("ACTIVE").build()));
        List<Product> rows = new ArrayList<>();
        for (int i = 0; i < 81; i++) {
            rows.add(Product.builder().id(100L + i).productCode("C" + i).productName("型号" + i)
                .productLineId(19L).build());
        }
        when(products.selectList(any())).thenReturn(rows);
        String appendix = binder().promptAppendix(10L, "000000");
        assertThat(appendix).contains("C0 型号0 catalog-access");
        assertThat(appendix).contains("C80 型号80 catalog-access");
    }

    @Test
    void completeDirectoryFindsExactModelBeyondOldCapAndBoundsPrompt() {
        when(requirements.selectOne(any())).thenReturn(Requirement.builder().id(17L)
            .rawModel("MODEL-999").content("需要MODEL-999的升级").build());
        when(lines.selectList(any())).thenReturn(List.of(ProductLine.builder()
            .id(19L).lineCode("catalog-access").status("ACTIVE").build()));
        List<Product> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) rows.add(Product.builder().id(100L + i)
            .productCode("SKU-" + i).modelCode("MODEL-" + i).productName("产品" + i)
            .productLineId(19L).build());
        when(products.selectList(any())).thenReturn(rows);
        var context = binder().open(17L, "000000");
        assertThat(context.hit().productCode()).isEqualTo("SKU-999");
        assertThat(context.appendix()).contains("SKU-999", "目录摘录已限长");
        assertThat(context.appendix().length()).isLessThan(17000);
        var query = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(products).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("product_line_id IN").doesNotContain("LIMIT");
    }

    @Test
    void duplicateModelAcrossProductsAndOutOfScopeProductCannotDetermineBinding() {
        when(requirements.selectOne(any())).thenReturn(Requirement.builder().id(18L).rawModel("MODEL-X").build());
        when(lines.selectList(any())).thenReturn(List.of(ProductLine.builder()
            .id(19L).lineCode("catalog-access").status("ACTIVE").build()));
        when(products.selectList(any())).thenReturn(List.of(
            Product.builder().id(1L).productCode("SKU-A").modelCode("MODEL-X").productLineId(19L).build(),
            Product.builder().id(2L).productCode("SKU-B").modelCode("MODEL-X").productLineId(19L).build(),
            Product.builder().id(3L).productCode("SKU-C").modelCode("MODEL-X").productLineId(999L).build()));
        var context = binder().open(18L, "000000");
        assertThat(context.hit().productCode()).isNull();
        assertThat(context.appendix()).doesNotContain("SKU-C");
    }

    @Test
    void structuredCodeWritesEvenWhenAnswerSaysUnknown() {
        Requirement requirement = Requirement.builder().id(12L).build();
        when(requirements.selectOne(any())).thenReturn(requirement);
        when(lines.selectList(any())).thenReturn(List.of(ProductLine.builder()
            .id(19L).lineCode("catalog-access").status("ACTIVE").build()));
        when(requirements.update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenReturn(1);
        binder().apply(12L, "产品线：未取得\n", new DemandCatalogBinder.CatalogHit("catalog-access", null), "000000");
        assertThat(requirement.getProductLineId()).isEqualTo(19L);
        verify(requirements).update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    @Test
    void structuredMissDoesNotFallBackToAnswerRegex() {
        Requirement requirement = Requirement.builder().id(13L).build();
        when(requirements.selectOne(any())).thenReturn(requirement);
        when(lines.selectList(any())).thenReturn(List.of());
        binder().apply(13L, "产品线：other\n", new DemandCatalogBinder.CatalogHit("missing", null), "000000");
        assertThat(requirement.getProductLineId()).isNull();
        verify(requirements, never()).update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    @Test
    void openMatchesOneCatalogCodeInDemandText() {
        when(requirements.selectOne(any())).thenReturn(Requirement.builder()
            .id(14L).title("门禁").content("请看 catalog-access 与 ZK-X").rawModel("ZK-X").build());
        when(lines.selectList(any())).thenReturn(List.of(
            ProductLine.builder().id(19L).lineCode("catalog-access").lineName("门禁产品").status("ACTIVE").build(),
            ProductLine.builder().id(20L).lineCode("catalog-video").lineName("视频").status("ACTIVE").build()));
        when(products.selectList(any())).thenReturn(List.of(
            Product.builder().id(31L).productCode("ZK-X").productName("熵基门禁").productLineId(19L).build(),
            Product.builder().id(32L).productCode("ZK-Y").productName("另一款").productLineId(20L).build()));
        DemandCatalogBinder.CatalogHit hit = binder().open(14L, "000000").hit();
        assertThat(hit.lineCode()).isEqualTo("catalog-access");
        assertThat(hit.productCode()).isEqualTo("ZK-X");
    }

    @Test
    void openLeavesLineEmptyWhenTwoCodesAppear() {
        when(requirements.selectOne(any())).thenReturn(Requirement.builder()
            .id(15L).content("catalog-access catalog-video").build());
        when(lines.selectList(any())).thenReturn(List.of(
            ProductLine.builder().id(19L).lineCode("catalog-access").status("ACTIVE").build(),
            ProductLine.builder().id(20L).lineCode("catalog-video").status("ACTIVE").build()));
        when(products.selectList(any())).thenReturn(List.of());
        assertThat(binder().open(15L, "000000").hit().lineCode()).isNull();
        assertThat(binder().open(15L, "000000").hit().productCode()).isNull();
    }

    @Test
    void zeroUpdatedRowsIsAnExplicitConflict() {
        Requirement requirement = Requirement.builder().id(16L).build();
        when(requirements.selectOne(any())).thenReturn(requirement);
        when(lines.selectList(any())).thenReturn(List.of(ProductLine.builder()
            .id(19L).lineCode("catalog-access").status("ACTIVE").build()));
        when(requirements.update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> binder().apply(16L, "产品线：catalog-access\n", null, "000000"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("绑定发生冲突");
        var captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(requirements).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("id", "product_line_id IS NULL", "product_id IS NULL");
        assertThat(captor.getValue().getSqlSet()).contains("product_line_id").doesNotContain("title", "content");
    }

    @Test
    void missingTrustedTenantNeverReadsOrWritesAnyCatalog() {
        assertThatThrownBy(() -> binder().open(42L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class).hasMessageContaining("可信租户");
        assertThatThrownBy(() -> binder().apply(42L, "产品线：catalog-access", null, " "))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class).hasMessageContaining("可信租户");
        org.mockito.Mockito.verifyNoInteractions(requirements, lines, products);
    }

    @Test
    void requirementFromAnotherTenantCannotEnterPromptOrCatalogLookup() {
        when(requirements.selectOne(any())).thenAnswer(invocation -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("tenant_id =");
            assertThat(query.getParamNameValuePairs().values()).contains("trusted-tenant", 42L);
            return null; // 该ID存在于另一租户，被条件排除。
        });
        assertThatThrownBy(() -> binder().open(42L, "trusted-tenant"))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class).hasMessageContaining("当前租户");
        org.mockito.Mockito.verifyNoInteractions(lines, products);
    }

    private DemandCatalogBinder binder() {
        return new DemandCatalogBinder(requirements, lines, products);
    }
}
