package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 产品服务（BR-PROD-01 三路来源；一个产品可以有多个项目，一个项目只属于一个产品）
 * 来源规则：
 *  ADMIN_IMPORT 超管导入在售型号 → modelCode 必填
 *  PM_NEW       PM 新增         → 常规
 *  GUEST_OTHER  游客「其他」占位 → 仅占位，不可关联项目
 *
 * Round 8 / R8-P0-7：batchImportOnSale 预取优化（一次性 selectList in (...) → Map.get）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService implements IProductService {

    private static final Set<String> SOURCES = Set.of(Product.SRC_ADMIN_IMPORT, Product.SRC_PM_NEW, Product.SRC_GUEST_OTHER);

    /** Round 8 / R8-P0-10：批量导入单次最大行数 */
    public static final int MAX_BATCH_SIZE = 500;

    private final ProductMapper productMapper;
    private final ProjectMapper projectMapper;
    private final IAuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductRetirementService retirementService;

    public void setProductRetirementService(ProductRetirementService retirementService) {
        this.retirementService = retirementService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Product create(Product product, Long operatorId) {
        if (product.getSource() == null || !SOURCES.contains(product.getSource())) {
            throw new ServiceException("产品来源非法: " + product.getSource() + "（允许 ADMIN_IMPORT|PM_NEW|GUEST_OTHER）");
        }
        if (Product.SRC_ADMIN_IMPORT.equals(product.getSource()) && isBlank(product.getModelCode())) {
            throw new ServiceException("超管导入在售型号必须填写 modelCode");
        }
        if (product.getProjectId() != null) {
            if (Product.SRC_GUEST_OTHER.equals(product.getSource())) {
                throw new ServiceException("游客「其他」占位产品不可关联项目");
            }
            checkProjectNotTaken(product.getProjectId());
        }
        if (isBlank(product.getStatus())) {
            // AC-PROD-06/07：超管导入→在售；PM 新增→在研
            if (Product.SRC_ADMIN_IMPORT.equals(product.getSource())) {
                product.setStatus(Product.ST_ON_SALE);
            } else if (Product.SRC_PM_NEW.equals(product.getSource())) {
                product.setStatus(Product.ST_IN_RD);
            } else {
                product.setStatus(Product.ST_IN_RD);
            }
        }
        if (product.getId() == null) {
            product.setCreateTime(new Date());
        }
        productMapper.insert(product);
        audit(product.getId(), product.getProductName(), operatorId, "PRODUCT_CREATE");
        return product;
    }

    /**
     * P1-1.2：编辑产品基础字段（名称/编码/型号/组）；已绑项目时禁止改来源。
     * R8X-CONT-1 P0-2：加 actor.groupId == product.groupId 横向越权防护（SUPER_ADMIN 豁免）。
     *
     * @param productId    产品 ID
     * @param patch        白名单变更
     * @param operatorId   操作人 ID
     * @param actorGroupId 操作人所属产品组
     * @param actorRole    操作人角色
     * @return 更新后实体
     */
    @Transactional(rollbackFor = Exception.class)
    public Product update(Long productId, Product patch, Long operatorId,
                          Long actorGroupId, String actorRole) {
        Product product = require(productId);
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            product.getGroupId());
        requireWritable(product);
        if (patch.getProductName() != null && !patch.getProductName().isBlank()) {
            product.setProductName(patch.getProductName().trim());
        }
        if (patch.getProductCode() != null) {
            product.setProductCode(patch.getProductCode().isBlank() ? null : patch.getProductCode().trim());
        }
        if (patch.getModelCode() != null) {
            if (Product.SRC_ADMIN_IMPORT.equals(product.getSource()) && patch.getModelCode().isBlank()) {
                throw new ServiceException("超管导入在售型号必须填写 modelCode");
            }
            product.setModelCode(patch.getModelCode().isBlank() ? null : patch.getModelCode().trim());
        }
        if (patch.getGroupId() != null) {
            product.setGroupId(patch.getGroupId());
        }
        if (patch.getSource() != null && !patch.getSource().equals(product.getSource())) {
            throw new ServiceException("产品来源创建后不可变更");
        }
        productMapper.updateById(product);
        audit(productId, product.getProductName(), operatorId, "PRODUCT_UPDATE");
        return product;
    }

    /**
     * P1-1.2 / AC-PROD-06：超管批量导入在售型号；按 modelCode 幂等（已存在则更新名称）。
     * Round 8 / R8-P0-7：从「循环每行 selectOne」改造为「一次性 selectList in(...) → Map.get」。
     * 100 行导入 IO 从 100 SELECT 降到 1 SELECT。
     *
     * @param items      导入行（受 Controller @Size(max=500) 约束）
     * @param operatorId 超管
     * @return 逐行结果（ok/error）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> batchImportOnSale(List<Product> items, Long operatorId) {
        if (items == null || items.isEmpty()) {
            throw new ServiceException("导入列表不能为空");
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new ServiceException("单次导入最多 " + MAX_BATCH_SIZE + " 行（实际 " + items.size() + " 行）");
        }
        // R8-P0-7：一次性预取所有已存在的 modelCode → Map<modelCode, Product>
        Set<String> models = new HashSet<>();
        for (Product item : items) {
            if (item != null && !isBlank(item.getModelCode())) {
                models.add(item.getModelCode().trim());
            }
        }
        Map<String, Product> existingMap = models.isEmpty()
            ? Map.of()
            : productMapper.selectList(new LambdaQueryWrapper<Product>()
                .in(Product::getModelCode, models)
                .eq(Product::getSource, Product.SRC_ADMIN_IMPORT))
                .stream()
                .collect(Collectors.toMap(Product::getModelCode, p -> p, (a, b) -> a));

        List<Map<String, Object>> report = new ArrayList<>();
        int row = 0;
        for (Product item : items) {
            row++;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("row", row);
            try {
                if (item == null || isBlank(item.getProductName())) {
                    throw new ServiceException("产品名称必填");
                }
                if (isBlank(item.getModelCode())) {
                    throw new ServiceException("超管导入在售型号必须填写 modelCode");
                }
                String model = item.getModelCode().trim();
                Product existing = existingMap.get(model);
                if (existing != null) {
                    requireWritable(existing);
                    existing.setProductName(item.getProductName().trim());
                    if (item.getProductCode() != null && !item.getProductCode().isBlank()) {
                        existing.setProductCode(item.getProductCode().trim());
                    }
                    if (item.getGroupId() != null) {
                        existing.setGroupId(item.getGroupId());
                    }
                    existing.setStatus(Product.ST_ON_SALE);
                    productMapper.updateById(existing);
                    audit(existing.getId(), existing.getProductName(), operatorId, "PRODUCT_IMPORT_UPSERT");
                    line.put("status", "UPSERT");
                    line.put("id", existing.getId());
                } else {
                    Product created = Product.builder()
                        .productName(item.getProductName().trim())
                        .productCode(item.getProductCode())
                        .modelCode(model)
                        .source(Product.SRC_ADMIN_IMPORT)
                        .groupId(item.getGroupId())
                        .status(Product.ST_ON_SALE)
                        .tenantId("000000")
                        .delFlag("0")
                        .build();
                    create(created, operatorId);
                    line.put("status", "CREATED");
                    line.put("id", created.getId());
                }
                line.put("ok", true);
            } catch (ServiceException | IpdBusinessException ex) {
                line.put("ok", false);
                line.put("error", ex.getMessage());
            }
            report.add(line);
        }
        return report;
    }

    /**
     * 状态切换 ON_SALE|IN_RD|INACTIVE|ACTIVE（删除走两级审核引擎）。
     * R8X-CONT-1 P0-2：加 actor.groupId == product.groupId 横向越权防护（SUPER_ADMIN 豁免）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long productId, String status, Long operatorId,
                             Long actorGroupId, String actorRole) {
        if (!Product.STATUSES.contains(status)) {
            throw new ServiceException("产品状态非法: " + status + "（允许 ON_SALE|IN_RD|INACTIVE|ACTIVE）");
        }
        Product product = require(productId);
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            product.getGroupId());
        requireWritable(product);
        product.setStatus(status);
        productMapper.updateById(product);
        audit(productId, product.getProductName(), operatorId, "PRODUCT_STATUS_" + status);
    }

    /**
     * 把项目挂到产品上。一个产品可以有多个项目，一个项目只属于一个产品。
     * <p>products.project_id 只保留第一个项目，不再挡住后续项目。
     * 后续项目只写 projects.product_id。
     * <p>项目已属于其他产品时拒绝。已挂在本产品上时幂等返回，不重复审计。
     *
     * @param productId    产品 ID
     * @param projectId    目标项目 ID
     * @param operatorId   操作人 ID
     * @param actorGroupId 操作人所属产品组
     * @param actorRole    操作人角色
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindProject(Long productId, Long projectId, Long operatorId,
                            Long actorGroupId, String actorRole) {
        Product product = require(productId);
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            product.getGroupId());
        requireWritable(product);
        if (retirementService != null && (retirementService.isOrderStopped(productId) || retirementService.isProductionStopped(productId))) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该产品订货或生产已截止，不能绑定新项目");
        }
        if (Product.SRC_GUEST_OTHER.equals(product.getSource())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "游客「其他」占位产品不可关联项目");
        }
        Project project = requireProject(projectId);
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            project.getMainGroupId());
        if (productId.equals(project.getProductId())) {
            return;
        }
        if (project.getProductId() != null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "该项目已属于其他产品");
        }

        try {
            if (product.getProjectId() == null) {
                int rows1 = productMapper.update(null, new LambdaUpdateWrapper<Product>()
                    .eq(Product::getId, productId)
                    .isNull(Product::getProjectId)
                    .set(Product::getProjectId, projectId));
                if (rows1 == 1) {
                    product.setProjectId(projectId);
                } else {
                    Product again = productMapper.selectById(productId);
                    if (again == null || again.getProjectId() == null) {
                        throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "绑定冲突，请重试");
                    }
                    product.setProjectId(again.getProjectId());
                }
            }
            int rows2 = projectMapper.update(null, new LambdaUpdateWrapper<Project>()
                .eq(Project::getId, projectId)
                .isNull(Project::getProductId)
                .set(Project::getProductId, productId));
            if (rows2 == 0) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                    "该项目已被其他产品绑定");
            }
        } catch (DuplicateKeyException dup) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "绑定冲突：项目已被占用");
        }
        Product reread = productMapper.selectById(productId);
        Project rereadP = projectMapper.selectById(projectId);
        if (reread == null || rereadP == null
            || reread.getProjectId() == null
            || !productId.equals(rereadP.getProductId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "绑定终态校验失败");
        }
        audit(productId, product.getProductName(), operatorId, "PRODUCT_BIND_PROJECT");
    }

    /**
     * 解除一个项目与产品的归属。只清这个项目的 product_id。
     * 若产品上的首个项目指针就是它，一并清空指针；其他项目不受影响。
     *
     * @param productId    产品 ID
     * @param projectId    目标项目 ID
     * @param operatorId   操作人 ID
     * @param actorGroupId 操作人所属产品组
     * @param actorRole    操作人角色
     */
    @Transactional(rollbackFor = Exception.class)
    public void unbindProject(Long productId, Long projectId, Long operatorId,
                              Long actorGroupId, String actorRole) {
        Product product = require(productId);
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            product.getGroupId());
        requireWritable(product);
        Project project = projectMapper.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())
            || !productId.equals(project.getProductId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产品未绑定该项目");
        }
        IpdIdorGuard.assertSameGroupIpd(new IpdActor(operatorId, null, actorRole, actorGroupId),
            project.getMainGroupId());
        boolean pointer = projectId.equals(product.getProjectId());
        if (pointer) {
            int rows1 = productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .eq(Product::getId, productId)
                .eq(Product::getProjectId, projectId)
                .set(Product::getProjectId, null));
            if (rows1 == 0) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                    "解绑冲突：产品端条件更新受影响行 0");
            }
            product.setProjectId(null);
        }
        int rows2 = projectMapper.update(null, new LambdaUpdateWrapper<Project>()
            .eq(Project::getId, projectId)
            .eq(Project::getProductId, productId)
            .set(Project::getProductId, null));
        if (rows2 == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "解绑冲突：项目端条件更新受影响行 0");
        }
        project.setProductId(null);
        Product reread = productMapper.selectById(productId);
        Project rereadP = projectMapper.selectById(projectId);
        if (reread == null || rereadP == null || rereadP.getProductId() != null
            || (pointer && reread.getProjectId() != null)) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "解绑终态校验失败");
        }
        audit(productId, product.getProductName(), operatorId, "PRODUCT_UNBIND_PROJECT");
    }

    public Product getById(Long id) {
        return productMapper.selectById(id);
    }

    public List<Product> list(String keyword) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<Product>().eq(Product::getDelFlag, "0");
        if (keyword != null && !keyword.isBlank()) {
            qw.like(Product::getProductName, keyword);
        }
        return productMapper.selectList(qw.orderByDesc(Product::getId));
    }

    /** Readonly state is a business result, never set or cleared by an ordinary product operation. */
    private void requireWritable(Product product) {
        if ("1".equals(product.getRetirementLocked())
            || productMapper.isRetirementLockedForUpdate(product.getId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "该产品已退市并只读，不能修改或调整关联");
        }
    }

    private Product require(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null || "1".equals(product.getDelFlag())) {
            throw new ServiceException("产品不存在: " + id);
        }
        return product;
    }

    /**
     * 加载未软删项目，用于挂到产品上。
     *
     * @param id 项目主键
     * @return 存活项目
     */
    private Project requireProject(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException("项目不存在: " + id);
        }
        return project;
    }

    private void checkProjectNotTaken(Long projectId) {
        Long taken = productMapper.selectCount(new LambdaQueryWrapper<Product>()
            .eq(Product::getProjectId, projectId).eq(Product::getDelFlag, "0"));
        if (taken != null && taken > 0) {
            throw new ServiceException("该项目已是其他产品的首个项目");
        }
    }

    private void audit(Long id, String name, Long operatorId, String action) {
        auditLogService.append(operatorId, action, "products", id, name);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}