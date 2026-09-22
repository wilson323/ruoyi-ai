package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
