package org.ruoyi.ipd.common;

/**
 * IPD 资源存在性统一约定（R179-P1 治理轮，2026-09-23）。
 *
 * <p><b>病根</b>：此前 controller 直接 {@code return ApiV1Response.ok(service.getById(id))}，
 * 当 id 不存在时 entity 为 null —— 包 VO 即 NPE→500 INTERNAL_ERROR；裸返回则 200 + null data，
 * 前端拿到 null 字段后 NPE。两种形态均违反「资源不存在 = 404 NOT_FOUND」契约
 * （ApiV1ErrorCode.NOT_FOUND.getHttpStatus() = 404 早已正确映射，缺的是 controller 端的显式抛出）。
 *
 * <p><b>治理</b>：所有 {@code @GetMapping("/{id}")} 详情端点必须用本工具显式拒绝 null，
 * 不允许把 null 透传给 VO.from() 或 ApiV1Response.ok()。门禁脚本
 * {@code scripts/check-resource-not-found-pattern.sh} 静态扫描裸 {@code getById} 返回。
 *
 * <p><b>使用</b>：
 * <pre>{@code
 *   Product p = IpdResources.requireOrNotFound(productService.getById(id), id, "产品");
 *   return ApiV1Response.ok(ProductVO.from(p));
 * }</pre>
 *
 * @see ApiV1ErrorCode#NOT_FOUND
 * @see IpdBusinessException
 */
public final class IpdResources {

    private IpdResources() {
        // utility class
    }

    /**
     * 资源存在性断言：entity 为 null 即抛 {@link ApiV1ErrorCode#NOT_FOUND}（HTTP 404），
     * 由 {@code IpdServiceExceptionAdvice} 统一转为 ApiV1Response 包络 + 404。
     *
     * @param entity       service 层查询结果（可能为 null）
     * @param id           请求的资源 id（用于错误文案，便于排查）
     * @param resourceName 资源中文名（如「产品」「项目」），用于错误文案
     * @param <T>          实体类型
     * @return 非 null 的 entity（保证调用方可直接 .getXxx()）
     * @throws IpdBusinessException 当 entity 为 null 时抛 NOT_FOUND（50001，HTTP 404）
     */
    public static <T> T requireOrNotFound(T entity, Object id, String resourceName) {
        if (entity == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND,
                resourceName + "不存在: " + id);
        }
        return entity;
    }
}