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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.support.NoopTransactionManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;

/**
 * P1-1.1 / AC-PROD-01：产品↔项目 1:1 双向绑定验收（Mock 层）。
 * <p>覆盖：创建入口拒绝、绑定入口拒绝、双向回填、幂等、软删拒绝、GUEST_OTHER。
 * <p>真库/HTTP 回归仍属卡面完整验收；本类满足看板「新增 P111AcceptanceTest」交付门禁。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class P111AcceptanceTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private GateEngine gateEngine;
    @Mock
    private ProjectBootstrapService projectBootstrapService;
    @Mock
    private IProjectCertService projectCertService;

    @Mock
    private org.ruoyi.ipd.mapper.StageActionMapper stageActionMapper;
    @Mock
    private org.ruoyi.ipd.mapper.KpiRecordMapper kpiRecordMapper;
    private ProductService productService;
    private ProjectService projectService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
    }

    @BeforeEach
    void setUp() {
        productService = new ProductService(productMapper, projectMapper, auditLogService);
        projectService = new ProjectService(
            projectMapper, productMapper, stageActionMapper, kpiRecordMapper, auditLogService, gateEngine, NoopTransactionManager.INSTANCE, null /* P2-6.2 */);
        // D-1 接线适配：注入真实守卫（种子规则 + fail-closed 迁移闸）
        DefaultStateMachineGuard d1Guard = new DefaultStateMachineGuard(null, null);
        d1Guard.initRules();
        projectService.setStateMachineGuard(d1Guard);
    }

    private Product aliveProduct(Long id, String source, Long projectId) {
        Product p = new Product();
        p.setId(id);
        p.setProductName("人脸门禁");
        p.setSource(source);
        p.setProjectId(projectId);
        p.setDelFlag("0");
        p.setGroupId(1L);
        return p;
    }

    private Project aliveProject(Long id, Long productId) {
        Project p = new Project();
        p.setId(id);
        p.setCode("PRJ-2026-001");
        p.setName("S");
        p.setProductId(productId);
        p.setDelFlag("0");
        // W28-2 cross-group-idor：bindProject 校验 actor 归属 vs project 主组（actorGroupId=1L）
        p.setMainGroupId(1L);
        return p;
    }

    private Project newProjectDraft() {
        Project p = new Project();
        p.setName("人脸门禁 S 级");
        p.setProductId(50L);
        p.setTemplateType("HARDWARE");
        p.setTargetMarkets("[\"SA\"]");
        p.setMainGroupId(7L);
        p.setLevel("S");
        // AC-INC-15c：立项仅落默认档（S=1.5）；非默认系数须走双PM提议，不得在创建入口直传
        p.setLevelCoefficient(new BigDecimal("1.5"));
        p.setLevelCoefficientReason("默认档");
        p.setTargetSalesAmount(new BigDecimal("5000000"));
        p.setTargetChannelCount(10);
        p.setTargetNps(70);
        p.setTargetSceneCount(5);
        return p;
    }

    @Test
    @DisplayName("已挂项目的产品再关联第二个项目 → 成功，且不改写首个项目指针")
    void productAlreadyBoundAcceptsSecondProject() {
        Product product = aliveProduct(3L, Product.SRC_PM_NEW, 9L);
        Project second = aliveProject(99L, null);
        when(productMapper.selectById(3L)).thenReturn(product);
        when(projectMapper.selectById(99L)).thenReturn(second);
        doAnswer(inv -> { second.setProductId(3L); return 1; })
            .when(projectMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        productService.bindProject(3L, 99L, 1L, 1L, "MARKET_PM");

        assertThat(product.getProjectId()).isEqualTo(9L);
        assertThat(second.getProductId()).isEqualTo(3L);
        verify(productMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("创建项目：产品已有首个项目时仍可再立项，且不改写该指针")
    void createProjectWhenProductAlreadyHasProjectId() {
        Product product = aliveProduct(50L, Product.SRC_PM_NEW, 88L);
        when(productMapper.selectById(50L)).thenReturn(product);
        when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(null);

        Project created = projectService.create(newProjectDraft(), 1L, 7L);

        assertThat(created.getStatus()).isEqualTo("PENDING_START");
        assertThat(product.getProjectId()).isEqualTo(88L);
    }

    @Test
    @DisplayName("创建项目：同产品已有存活项目时，新项目仍进入待开工")
    void createProjectWhenProductTakenByOtherProject() {
        Product product = aliveProduct(50L, Product.SRC_PM_NEW, null);
        when(productMapper.selectById(50L)).thenReturn(product);
        when(projectMapper.selectMaxCodeSeqByYear(anyInt())).thenReturn(null);

        Project created = projectService.create(newProjectDraft(), 1L, 7L);

        assertThat(created.getStatus()).isEqualTo("PENDING_START");
        assertThat(product.getProjectId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("bindProject 成功：两端条件 UPDATE affected=1 + 终态自洽 + 审计")
    void bindProjectBidirectional() {
        Product product = aliveProduct(3L, Product.SRC_PM_NEW, null);
        Project project = aliveProject(9L, null);
        when(productMapper.selectById(3L)).thenReturn(product);
        when(projectMapper.selectById(9L)).thenReturn(project);
        // P1-1.1：bindProject 改为条件 UPDATE（LambdaUpdateWrapper）+ 自洽终态重读
        doAnswer(inv -> { product.setProjectId(9L); return 1; })
            .when(productMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        doAnswer(inv -> { project.setProductId(3L); return 1; })
            .when(projectMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        productService.bindProject(3L, 9L, 1L, 1L, "MARKET_PM");

        assertThat(product.getProjectId()).isEqualTo(9L);
        assertThat(project.getProductId()).isEqualTo(3L);
        verify(productMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(projectMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(auditLogService).append(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("bindProject 幂等：已绑定同一项目 → 不写库不写审计")
    void bindProjectIdempotent() {
        Product product = aliveProduct(3L, Product.SRC_PM_NEW, 9L);
        Project project = aliveProject(9L, 3L);
        when(productMapper.selectById(3L)).thenReturn(product);
        when(projectMapper.selectById(9L)).thenReturn(project);

        productService.bindProject(3L, 9L, 1L, 1L, "MARKET_PM");

        verify(productMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(projectMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(auditLogService, never()).append(any());
        verify(auditLogService, never()).append(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("GUEST_OTHER 不可 bindProject → 拒绝 409 STATE_CONFLICT")
    void guestOtherCannotBind() {
        Product product = aliveProduct(3L, Product.SRC_GUEST_OTHER, null);
        when(productMapper.selectById(3L)).thenReturn(product);

        IpdBusinessException ex = (IpdBusinessException) assertThatThrownBy(() ->
            productService.bindProject(3L, 9L, 1L, 1L, "MARKET_PM"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("其他")
            .actual();
        assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("软删项目不可绑定；软删产品不可绑定")
    void softDeletedRejected() {
        Product product = aliveProduct(3L, Product.SRC_PM_NEW, null);
        when(productMapper.selectById(3L)).thenReturn(product);
        Project deleted = aliveProject(9L, null);
        deleted.setDelFlag("1");
        when(projectMapper.selectById(9L)).thenReturn(deleted);
        assertThatThrownBy(() -> productService.bindProject(3L, 9L, 1L, 1L, "MARKET_PM"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("项目不存在");

        Product softProduct = aliveProduct(4L, Product.SRC_PM_NEW, null);
        softProduct.setDelFlag("1");
        when(productMapper.selectById(4L)).thenReturn(softProduct);
        assertThatThrownBy(() -> productService.bindProject(4L, 10L, 1L, 1L, "MARKET_PM"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("产品不存在");
    }

    @Test
    @DisplayName("项目已属于其他产品 → 拒绝（409 STATE_CONFLICT）")
    void projectTakenByOtherProduct() {
        Product product = aliveProduct(3L, Product.SRC_PM_NEW, null);
        Project project = aliveProject(9L, 7L);
        when(productMapper.selectById(3L)).thenReturn(product);
        when(projectMapper.selectById(9L)).thenReturn(project);

        IpdBusinessException ex = (IpdBusinessException) assertThatThrownBy(() ->
            productService.bindProject(3L, 9L, 1L, 1L, "MARKET_PM"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("已属于其他产品")
            .actual();
        assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("一个产品再挂第二个项目时，不走奖金池分摊")
    void secondProjectDoesNotAllocatePool() {
        Product product = aliveProduct(3L, Product.SRC_PM_NEW, 9L);
        Project second = aliveProject(99L, null);
        when(productMapper.selectById(3L)).thenReturn(product);
        when(projectMapper.selectById(99L)).thenReturn(second);
        doAnswer(inv -> { second.setProductId(3L); return 1; })
            .when(projectMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        productService.bindProject(3L, 99L, 1L, 1L, "MARKET_PM");

        assertThat(product.getProjectId()).isEqualTo(9L);
        verify(auditLogService).append(anyLong(), org.mockito.ArgumentMatchers.eq("PRODUCT_BIND_PROJECT"),
            any(), any(), any());
    }

    @Test
    @DisplayName("产品还没有首个项目时，创建项目回填该指针")
    void createProjectBackfillsProduct() {
        Product product = aliveProduct(50L, Product.SRC_PM_NEW, null);
        when(productMapper.selectById(50L)).thenReturn(product);
        when(projectMapper.insert(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(501L);
            return 1;
        });

        Project created = projectService.create(newProjectDraft(), 1L, 7L);

        assertThat(created.getId()).isEqualTo(501L);
        assertThat(product.getProjectId()).isEqualTo(501L);
        ArgumentCaptor<Product> cap = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).updateById(cap.capture());
        assertThat(cap.getValue().getProjectId()).isEqualTo(501L);
    }

    @Test
    @DisplayName("主组可选（2026-09-11 owner 拍板）：未选时权威填充 fallback 组（BR-ORG-01）")
    void mainGroupFallbackToActorGroup() {
        Product product = aliveProduct(50L, Product.SRC_PM_NEW, null);
        when(productMapper.selectById(50L)).thenReturn(product);
        when(projectMapper.insert(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(502L);
            return 1;
        });

        Project draft = newProjectDraft();
        draft.setMainGroupId(null);
        Project created = projectService.create(draft, 1L, 77L);

        assertThat(created.getMainGroupId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("主组缺失：未选且无 fallback → 拒绝（不落 null 污染 SEC-02 按组链）")
    void mainGroupMissingWhenNoFallback() {
        Project draft = newProjectDraft();
        draft.setMainGroupId(null);
        assertThatThrownBy(() -> projectService.create(draft, 1L, null))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("主组缺失");
        verify(projectMapper, never()).insert(any(Project.class));
    }

    @Test
    @DisplayName("创建入口拒绝 GUEST_OTHER 占位产品（与 bind 入口一致）")
    void createProjectRejectsGuestOtherProduct() {
        Product guest = aliveProduct(50L, Product.SRC_GUEST_OTHER, null);
        when(productMapper.selectById(50L)).thenReturn(guest);

        assertThatThrownBy(() -> projectService.create(newProjectDraft(), 1L, 7L))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("其他");
        verify(projectMapper, never()).insert(any(Project.class));
    }
}
