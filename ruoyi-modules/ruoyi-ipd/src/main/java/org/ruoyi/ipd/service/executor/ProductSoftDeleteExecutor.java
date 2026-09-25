package org.ruoyi.ipd.service.executor;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.service.SoftDeleteExecutor;
import org.springframework.stereotype.Component;

/**
 * Product 软删除执行器（entityType=products，P0-6.2 / P1-1.1）。
 * <p>幂等：已软删不重复写。软删后释放项目侧 productId，保持双向 1:1 可重建。
 */
@Component
@RequiredArgsConstructor
public class ProductSoftDeleteExecutor implements SoftDeleteExecutor<Product> {

    public static final String ENTITY_TYPE = "products";

    private final ProductMapper productMapper;
    private final ProjectMapper projectMapper;

    @Override
    public String entityType() {
        return ENTITY_TYPE;
    }

    @Override
    public Class<Product> entityClass() {
        return Product.class;
    }

    /**
     * 软删产品并清空仍指向本产品的项目 productId。
     *
     * @param id 产品主键
     */
    @Override
    public void softDelete(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null || "1".equals(product.getDelFlag())) {
            return;
        }
        // 实体带 @TableLogic，updateById 会把逻辑删除字段从 SET 子句剔除致静默失效（R216 实测回归），
        // 与 PersonSoftDeleteExecutor 同款显式 UPDATE 保证 del_flag 真实落库。
        int rows = productMapper.update(null, new LambdaUpdateWrapper<Product>()
            .eq(Product::getId, id)
            .eq(Product::getDelFlag, "0")
            .set(Product::getDelFlag, "1"));
        if (rows != 1) {
            throw new ServiceException("产品软删除未更新唯一记录: id=" + id);
        }
        releaseProjectLink(id, product.getProjectId());
    }

    /**
     * 释放项目侧 1:1 指针（优先按产品上记录的 projectId）。
     *
     * @param productId 已软删产品 ID
     * @param projectId 产品上记录的项目 ID，可为 null
     */
    private void releaseProjectLink(Long productId, Long projectId) {
        if (projectId == null) {
            return;
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            return;
        }
        if (productId.equals(project.getProductId())) {
            project.setProductId(null);
            projectMapper.updateById(project);
        }
    }

    /**
     * 判断产品是否已软删或不存在。
     *
     * @param id 产品主键
     * @return true 表示应记 DELETE_NOOP
     */
    @Override
    public boolean isDeleted(Long id) {
        Product product = productMapper.selectById(id);
        return product == null || "1".equals(product.getDelFlag());
    }
}
