package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdRolePermissionCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R215-E2E-C（看板卡 f4445a05）：GET /api/v1/demands/{id} 详情端点契约。
 * <p>
 * 缺口背景：DemandController 此前仅 list / triage / link-project 三端点，前端需求详情 404。
 * 覆盖矩阵：
 * - 正例：存在 → 返回与 list 同套 14 字段（含产品名/双PM名解析）；
 * - 一致性：detail 与 list 对同一条需求输出逐字段相等（toDemandMap 单一真源防漂移）；
 * - 负例：不存在 → NOT_FOUND（复用 requireDemand 既有错误码，不自造）；
 * - 越权：注解与 list 同款（OPERATION_PRODUCT_GROUP + ipd 登录态）；
 *   未知角色（GUEST）在目录 fail-closed 不持码 → Sa-Token 注解层拒绝。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DemandDetailEndpointTest {

    private static final long DEMAND_ID = 2103330885699985410L;

    @Mock private RequirementMapper requirementMapper;
    @Mock private ProductMapper productMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private PersonMapper personMapper;

    private DemandController controller;

    @BeforeEach
    void setUp() {
        controller = new DemandController(requirementMapper, productMapper, projectMapper, personMapper);
    }

    private static Requirement demand() {
        Requirement r = new Requirement();
        r.setId(DEMAND_ID);
        r.setProductId(7001L);
        r.setProjectId(9140001L);
        r.setSource("PORTAL_GUEST");
        r.setSubmitterName("张三");
        r.setCustomerName("某某公司");
        r.setTitle("希望支持批量导出");
        r.setStatus("SUBMITTED");
        r.setMarketPmId(900103L);
        r.setRdPmId(900104L);
        r.setCreateTime(new Date(1700000000000L));
        return r;
    }

    private void stubNameTables() {
        Product product = new Product();
        product.setId(7001L);
        product.setProductName("ZK-X100");
        when(productMapper.selectBatchIds(anyList())).thenReturn(List.of(product));
        Person marketPm = new Person();
        marketPm.setId(900103L);
        marketPm.setName("市场PM李");
        Person rdPm = new Person();
        rdPm.setId(900104L);
        rdPm.setName("研发PM王");
        when(personMapper.selectBatchIds(anyList())).thenReturn(List.of(marketPm, rdPm));
    }

    @Test
    @DisplayName("E2E-C.1 需求存在 → 详情返回与 list 同套 14 字段（产品名/双PM名已解析）")
    void detail_exists_returnsFullVoAlignedWithList() {
        when(requirementMapper.selectById(DEMAND_ID)).thenReturn(demand());
        stubNameTables();

        Map<String, Object> data = controller.detail(DEMAND_ID).getData();

        assertThat(data).containsOnlyKeys(
            "id", "productId", "productName", "projectId", "source", "submitterName",
            "customerName", "title", "status", "marketPmId", "marketPmName",
            "rdPmId", "rdPmName", "createdAt");
        assertThat(data)
            .containsEntry("id", DEMAND_ID)
            .containsEntry("productId", 7001L)
            .containsEntry("productName", "ZK-X100")
            .containsEntry("projectId", 9140001L)
            .containsEntry("source", "PORTAL_GUEST")
            .containsEntry("submitterName", "张三")
            .containsEntry("customerName", "某某公司")
            .containsEntry("title", "希望支持批量导出")
            .containsEntry("status", "SUBMITTED")
            .containsEntry("marketPmId", 900103L)
            .containsEntry("marketPmName", "市场PM李")
            .containsEntry("rdPmId", 900104L)
            .containsEntry("rdPmName", "研发PM王")
            .containsEntry("createdAt", new Date(1700000000000L));
    }

    @Test
    @DisplayName("E2E-C.2 detail 与 list 对同一条需求逐字段相等（两端点字段集防漂移）")
    @SuppressWarnings("unchecked")
    void detail_voEqualsListRow() {
        when(requirementMapper.selectById(DEMAND_ID)).thenReturn(demand());
        when(requirementMapper.selectList(any())).thenReturn(List.of(demand()));
        stubNameTables();

        Map<String, Object> detail = controller.detail(DEMAND_ID).getData();
        List<Map<String, Object>> listRows =
            (List<Map<String, Object>>) controller.list(null, null).getData().get("demands");

        assertThat(listRows).hasSize(1);
        assertThat(detail).isEqualTo(listRows.get(0));
    }

    @Test
    @DisplayName("E2E-C.3 需求不存在 → NOT_FOUND（复用 requireDemand 既有错误码）且不查名称表")
    void detail_missing_notFound() {
        when(requirementMapper.selectById(DEMAND_ID)).thenReturn(null);

        assertThatThrownBy(() -> controller.detail(DEMAND_ID))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND))
            .hasMessageContaining("需求不存在");
        verify(productMapper, never()).selectBatchIds(anyList());
        verify(personMapper, never()).selectBatchIds(anyList());
    }

    @Test
    @DisplayName("E2E-C.4 权限契约：detail 注解与 list 同款；未知角色 fail-closed 不持码（越权拒绝）")
    void detail_permissionAlignedWithList() throws NoSuchMethodException {
        SaCheckPermission detailAnn = DemandController.class
            .getDeclaredMethod("detail", Long.class).getAnnotation(SaCheckPermission.class);
        SaCheckPermission listAnn = DemandController.class
            .getDeclaredMethod("list", Long.class, String.class).getAnnotation(SaCheckPermission.class);

        assertThat(detailAnn).as("detail 端点必须挂 @SaCheckPermission").isNotNull();
        assertThat(listAnn).as("list 端点注解（对齐基准）").isNotNull();
        assertThat(detailAnn.value()).containsExactly(IpdPermissionCode.OPERATION_PRODUCT_GROUP);
        assertThat(detailAnn.value()).isEqualTo(listAnn.value());
        assertThat(detailAnn.type()).isEqualTo(IpdAuthSession.LOGIN_TYPE);
        assertThat(detailAnn.type()).isEqualTo(listAnn.type());

        assertThat(IpdRolePermissionCatalog.has("MARKET_PM", IpdPermissionCode.OPERATION_PRODUCT_GROUP))
            .as("内部角色持读码（正常可读）").isTrue();
        assertThat(IpdRolePermissionCatalog.has("GUEST", IpdPermissionCode.OPERATION_PRODUCT_GROUP))
            .as("未知角色 fail-closed 不持码 → 注解层拒绝（越权）").isFalse();
    }
}
