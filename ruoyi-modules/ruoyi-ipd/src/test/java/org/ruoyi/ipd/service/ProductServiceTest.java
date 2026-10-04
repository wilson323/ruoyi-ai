package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产品服务单测：三路来源、首个项目指针、启停
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private IAuditLogService auditLogService;

    private ProductService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
    }

    @BeforeEach
    void setUp() {
        service = new ProductService(productMapper, projectMapper, auditLogService);
    }

    private Product product(String source, String modelCode, Long projectId) {
        Product p = new Product();
        p.setProductName("人脸门禁 Pro");
        p.setSource(source);
        p.setModelCode(modelCode);
        p.setProjectId(projectId);
        p.setGroupId(1L);
        return p;
    }

    @Test
    @DisplayName("来源非法拒绝（白名单外）")
    void invalidSource() {
        assertThatThrownBy(() -> service.create(product("HACK", null, null), 1L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("来源非法");
    }

    @Test
    @DisplayName("ADMIN_IMPORT 必须 modelCode")
    void adminImportNeedsModel() {
        assertThatThrownBy(() -> service.create(product("ADMIN_IMPORT", null, null), 1L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("modelCode");
    }

    @Test
    @DisplayName("GUEST_OTHER 占位不可关联项目")
    void guestOtherCannotBindProject() {
        assertThatThrownBy(() -> service.create(product("GUEST_OTHER", null, 9L), 1L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("其他");
    }

    @Test
    @DisplayName("首个项目指针已被其他产品占用 → 拒绝")
    void oneToOneTaken() {
        when(productMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        assertThatThrownBy(() -> service.create(product("PM_NEW", null, 9L), 1L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("首个项目");
    }

    @Test
    @DisplayName("PM_NEW 正常创建 + 默认 IN_RD（在研，AC-PROD-07）+ 审计")
    void createOk() {
        // projectId=null 不触发占用检查，无需 stub selectCount（Mockito strict）
        // 108be858 起 PM_NEW 默认状态由 ACTIVE 改为 IN_RD（AC-PROD-07：PM 新增→在研）
        Product created = service.create(product("PM_NEW", null, null), 1L);
        assertThat(created.getStatus()).isEqualTo(Product.ST_IN_RD);
        verify(auditLogService).append(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bindProject：项目存在且未占用 → 两端回填关联")
    void bindProjectOk() {
        Product p = product("PM_NEW", null, null);
        p.setId(3L);
        p.setDelFlag("0");
        p.setGroupId(1L);
        when(productMapper.selectById(3L)).thenReturn(p);
        Project project = new Project();
        project.setId(9L);
        project.setDelFlag("0");
        project.setMainGroupId(1L); // R-NEW A-2：补 mainGroupId=actor.groupId，跳同组守卫（与 Product.groupId=1L 一致）
        when(projectMapper.selectById(9L)).thenReturn(project);
        // P1-1.1：bindProject 改为条件 UPDATE（LambdaUpdateWrapper）+ 自洽终态
        doAnswer(inv -> { p.setProjectId(9L); return 1; })
            .when(productMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        doAnswer(inv -> { project.setProductId(3L); return 1; })
            .when(projectMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        service.bindProject(3L, 9L, 1L, 1L, "MARKET_PM");
        assertThat(p.getProjectId()).isEqualTo(9L);
        assertThat(project.getProductId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("启停：非法状态拒绝，合法切换 + 审计")
    void changeStatus() {
        Product p = product("PM_NEW", null, null);
        p.setId(3L);
        p.setStatus("ACTIVE");
        p.setDelFlag("0");
        when(productMapper.selectById(3L)).thenReturn(p);

        assertThatThrownBy(() -> service.changeStatus(3L, "DELETED", 1L, 1L, "MARKET_PM"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("状态非法");
        service.changeStatus(3L, "INACTIVE", 1L, 1L, "MARKET_PM");
        assertThat(p.getStatus()).isEqualTo("INACTIVE");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"update", "status", "bind", "unbind"})
    void retiredReadonlyProductRejectsEveryOrdinaryWriteEvenForAdministrator(String operation) {
        Product retired = product("PM_NEW", null, 9L);
        retired.setId(3L); retired.setGroupId(1L); retired.setDelFlag("0");
        retired.setRetirementLocked("1");
        when(productMapper.selectById(3L)).thenReturn(retired);
        Runnable write = switch (operation) {
            case "update" -> () -> service.update(3L, new Product().setProductName("新名称"), 1L, 1L, "SUPER_ADMIN");
            case "status" -> () -> service.changeStatus(3L, Product.ST_ON_SALE, 1L, 1L, "SUPER_ADMIN");
            case "bind" -> () -> service.bindProject(3L, 10L, 1L, 1L, "SUPER_ADMIN");
            default -> () -> service.unbindProject(3L, 9L, 1L, 1L, "SUPER_ADMIN");
        };
        assertThatThrownBy(write::run).isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class)
            .hasMessageContaining("已退市并只读");
        org.mockito.Mockito.verify(productMapper, org.mockito.Mockito.never()).updateById(any(Product.class));
        org.mockito.Mockito.verify(productMapper, org.mockito.Mockito.never()).update(any(), any());
        org.mockito.Mockito.verifyNoInteractions(projectMapper, auditLogService);
        assertThat(retired.getProductName()).isEqualTo("人脸门禁 Pro");
        assertThat(retired.getProjectId()).isEqualTo(9L);
    }

    @Test
    void retirementThatBecameEffectiveAfterInitialReadIsDetectedBeforeMutation() {
        Product stale = product("PM_NEW", null, null);
        stale.setId(3L); stale.setGroupId(1L); stale.setDelFlag("0"); stale.setRetirementLocked("0");
        when(productMapper.selectById(3L)).thenReturn(stale);
        when(productMapper.isRetirementLockedForUpdate(3L)).thenReturn(true);
        assertThatThrownBy(() -> service.update(3L, new Product().setProductName("不能写入"), 1L, 1L, "MARKET_PM"))
            .hasMessageContaining("已退市并只读");
        assertThat(stale.getProductName()).isEqualTo("人脸门禁 Pro");
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
        org.mockito.Mockito.verify(productMapper, org.mockito.Mockito.never()).updateById(any(Product.class));
    }

    @Test
    void batchImportReportsReadonlyRowWithoutReactivatingIt() {
        Product retired = product("ADMIN_IMPORT", "retired-model", null);
        retired.setId(3L); retired.setDelFlag("0"); retired.setStatus(Product.ST_INACTIVE);
        retired.setRetirementLocked("1");
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(retired));
        var report = service.batchImportOnSale(List.of(product("ADMIN_IMPORT", "retired-model", null)), 1L);
        assertThat(report).singleElement().satisfies(row -> {
            assertThat(row.get("ok")).isEqualTo(false);
            assertThat(row.get("error").toString()).contains("已退市并只读");
        });
        assertThat(retired.getStatus()).isEqualTo(Product.ST_INACTIVE);
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
        org.mockito.Mockito.verify(productMapper, org.mockito.Mockito.never()).updateById(any(Product.class));
    }

    @Test
    void retiredHistoricalProductRemainsReadableWithoutWriteLock() {
        Product retired = product("PM_NEW", null, 9L);
        retired.setId(3L); retired.setRetirementLocked("1");
        when(productMapper.selectById(3L)).thenReturn(retired);
        assertThat(service.getById(3L)).isSameAs(retired);
        org.mockito.Mockito.verify(productMapper, org.mockito.Mockito.never()).isRetirementLockedForUpdate(anyLong());
    }

}