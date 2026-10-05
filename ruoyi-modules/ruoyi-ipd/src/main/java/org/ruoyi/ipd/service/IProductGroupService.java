package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.domain.ProductGroup;

/**
 * IProductGroupService 接口（paiban-05 接口化，实现见 {@link ProductGroupService}）。
 */
public interface IProductGroupService {

    /** 列出所有非删除产品组（按名称升序） */
    List<ProductGroup> listAll();

    /** 按 id 取详情（含删除态） */
    ProductGroup getById(Long id);

    /** 按 id 取详情（含删除态） */
    ProductGroup create(ProductGroup group, Long operatorId);

    /** 按 id 取详情（含删除态） */
    ProductGroup updateLeader(Long groupId, Long newLeaderPersonId, Long operatorId);

    /** * 禁止直删旁路（P0-6.2 / G-02）：产品组须走 DeletionRequest 审核。 */
    void remove(Long id, Long operatorId);

}
