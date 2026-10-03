package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IProductService 接口（paiban-05 接口化，实现见 {@link ProductService}）。
 */
public interface IProductService {

    /** Round 8 / R8-P0-10：批量导入单次最大行数 */
    Product create(Product product, Long operatorId);

    /** * P1-1.2：编辑产品基础字段（名称/编码/型号/组）；已绑项目时禁止改来源。 */
    /** * R8X-CONT-1 P0-2：加 actor.groupId == product.groupId 横向越权防护（SUPER_ADMIN 豁免）。 */
    /** * */
    /** * @param productId    产品 ID */
    /** * @param patch        白名单变更 */
    /** * @param operatorId   操作人 ID */
    /** * @param actorGroupId 操作人所属产品组 */
    /** * @param actorRole    操作人角色 */
    /** * @return 更新后实体 */
    Product update(
        Long productId,
        Product patch,
        Long operatorId,
        Long actorGroupId,
        String actorRole
    );

    /** * P1-1.2 / AC-PROD-06：超管批量导入在售型号；按 modelCode 幂等（已存在则更新名称）。 */
    /** * Round 8 / R8-P0-7：从「循环每行 selectOne」改造为「一次性 selectList in(...) → Map.get」。 */
    /** * 100 行导入 IO 从 100 SELECT 降到 1 SELECT。 */
    /** * */
    /** * @param items      导入行（受 Controller @Size(max=500) 约束） */
    /** * @param operatorId 超管 */
    /** * @return 逐行结果（ok/error） */
    List<Map<String, Object>> batchImportOnSale(List<Product> items, Long operatorId);

    /** * 状态切换 ON_SALE|IN_RD|INACTIVE|ACTIVE（删除走两级审核引擎）。 */
    /** * R8X-CONT-1 P0-2：加 actor.groupId == product.groupId 横向越权防护（SUPER_ADMIN 豁免）。 */
    void changeStatus(
        Long productId,
        String status,
        Long operatorId,
        Long actorGroupId,
        String actorRole
    );

    /** * 把项目挂到产品上。一个产品可以有多个项目，一个项目只属于一个产品。 */
    /** * <p>products.project_id 只保留第一个项目。后续项目只写 projects.product_id。 */
    /** * <p>项目已属于其他产品时拒绝。已挂在本产品上时幂等返回，不重复审计。 */
    /** * <p>GUEST_OTHER 占位不可绑定；软删项目/产品拒绝。 */
    /** * <p>R8X-CONT-1 P0-2：加 actor.groupId == product.groupId 横向越权防护（SUPER_ADMIN 豁免，403 FORBIDDEN）。 */
    /** * <p>首个项目指针用条件 UPDATE {@code id=? AND project_id IS NULL}；指针已有时不再改写。 */
    /** * project 端用条件 UPDATE {@code id=? AND product_id IS NULL}；项目端 affected=0 即拒绝。 */
    /** * DB：{@code uk_products_project(project_id)} 只保证指针列不重复。projects.product_id 允许多个项目指向同一产品。 */
    /** * */
    /** * @param productId    产品 ID */
    /** * @param projectId    目标项目 ID */
    /** * @param operatorId   操作人 ID */
    /** * @param actorGroupId 操作人所属产品组 */
    /** * @param actorRole    操作人角色 */
    void bindProject(
        Long productId,
        Long projectId,
        Long operatorId,
        Long actorGroupId,
        String actorRole
    );

    /** * 解除一个项目与产品的归属。只清这个项目的 product_id。 */
    /** * 若产品上的首个项目指针就是它，一并清空指针；其他项目不受影响。 */
    /** * */
    /** * @param productId    产品 ID */
    /** * @param projectId    目标项目 ID */
    /** * @param operatorId   操作人 ID */
    /** * @param actorGroupId 操作人所属产品组 */
    /** * @param actorRole    操作人角色 */
    void unbindProject(
        Long productId,
        Long projectId,
        Long operatorId,
        Long actorGroupId,
        String actorRole
    );

    /** * 按主键读取未删除产品。projectId 只是首个项目指针。 */
    Product getById(Long id);

    /** * 按关键字列出未删除产品。一个产品可对应多个项目。 */
    List<Product> list(String keyword);

}
