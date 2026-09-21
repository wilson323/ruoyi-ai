package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.IpdBusinessConfig;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.BusinessConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务参数管理 API /api/v1/business-config（R149 batch2b A5）。
 *
 * <p>面向前端「审批人配置」「流程阈值配置」等 GROUP/PROJECT 维度业务参数的可视化管理：
 * <ul>
 *   <li>GET /api/v1/business-config?scope=&scopeId=&configKey= 按 scope 维度查/列</li>
 *   <li>POST /api/v1/business-config 增改（仅超管/组长）</li>
 *   <li>DELETE /api/v1/business-config 软删（仅超管/组长）</li>
 * </ul>
 *
 * <p>与既有 system-configs 解耦：本 controller 仅管 ipd_business_config（业务规则参数），
 * 不动 system_configs（系统参数）。两者通过 {@code config_key} 前缀区分。
 *
 * <p>权限：读 = 内部全员；写 = 组长/超管（requireLeaderOrAdmin）。
 */
@RestController
@RequestMapping("/api/v1/business-config")
@RequiredArgsConstructor
@Slf4j
public class BusinessConfigController {

    private final BusinessConfigService businessConfigService;
    private final IpdPermission ipdPermission;

    /**
     * 查询业务参数（按 scope 维度）。
     *
     * <p>支持三种用法：
     * <ul>
     *   <li>只传 {@code scope=GLOBAL} → 列举所有 GLOBAL 配置（兼容旧 SystemConfigController 风格）</li>
     *   <li>只传 {@code scope=GROUP|PROJECT} → 列举该 scope 维度所有配置（按 scope_id 分组）</li>
     *   <li>传 {@code scope+scopeId+configKey} → 精确单点查</li>
     * </ul>
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_BUSINESS_CONFIG_READ, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<Map<String, Object>> query(@RequestParam(required = false) String scope,
                                                    @RequestParam(required = false) String scopeId,
                                                    @RequestParam(required = false) String configKey) {
        ipdPermission.requireInternal();
        // 单点精确读：scope + configKey 都给 → 走缓存读（高频热路径）
        if (configKey != null && !configKey.isBlank() && scope != null && !scope.isBlank()) {
            String value = businessConfigService.getConfig(scope, scopeId, configKey, "");
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("scope", scope);
            resp.put("scopeId", scopeId);
            resp.put("configKey", configKey);
            resp.put("configValue", value);
            return ApiV1Response.ok(resp);
        }
        // 否则按维度列表
        List<IpdBusinessConfig> rows = businessConfigService.listByScope(scope, scopeId, configKey);
        return ApiV1Response.ok(Map.of(
            "scope", scope == null ? "" : scope,
            "scopeId", scopeId == null ? "" : scopeId,
            "count", rows.size(),
            "rows", rows));
    }

    /**
     * 增 / 改 业务参数。仅组长/超管（requireLeaderOrAdmin）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_BUSINESS_CONFIG_WRITE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping
    public ApiV1Response<Map<String, Object>> upsert(@RequestBody @Valid UpsertReq req) {
        IpdActor actor = ipdPermission.requireLeaderOrAdmin();
        Long id = businessConfigService.upsert(
            req.scope(),
            req.scopeId(),
            req.configKey(),
            req.configValue(),
            req.valueType(),
            req.enabled(),
            req.description(),
            actor.id());
        return ApiV1Response.ok(Map.of("id", id,
            "scope", req.scope(),
            "scopeId", req.scopeId() == null ? "" : req.scopeId(),
            "configKey", req.configKey(),
            "invalidated", "true"));
    }

    /**
     * 软删业务参数。仅组长/超管。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_BUSINESS_CONFIG_WRITE, type = IpdAuthSession.LOGIN_TYPE)
    @DeleteMapping
    public ApiV1Response<Map<String, Object>> delete(@RequestParam @NotBlank String scope,
                                                     @RequestParam(required = false) String scopeId,
                                                     @RequestParam @NotBlank String configKey) {
        IpdActor actor = ipdPermission.requireLeaderOrAdmin();
        boolean deleted = businessConfigService.softDelete(scope, scopeId, configKey, actor.id());
        if (!deleted) {
            throw new ServiceException("未找到匹配配置 scope=" + scope + " scopeId=" + scopeId + " configKey=" + configKey,
                ApiV1ErrorCode.NOT_FOUND.getCode());
        }
        return ApiV1Response.ok(Map.of(
            "scope", scope,
            "scopeId", scopeId == null ? "" : scopeId,
            "configKey", configKey,
            "deleted", true));
    }

    /**
     * upsert 请求体。scope=GROUP|PROJECT 时 scopeId 必填（service 二次校验）。
     */
    public record UpsertReq(
            @NotBlank @Size(max = 32) String scope,
            @Size(max = 64) String scopeId,
            @NotBlank @Size(max = 128) String configKey,
            @Size(max = 4000) String configValue,
            @Size(max = 16) String valueType,
            Integer enabled,
            @Size(max = 500) String description) {}
}
