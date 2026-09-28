package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.GatePrecheckService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI-P2-1（2026-09-27）：Gate 评审材料 AI 预审端点。
 *
 * <p>{@code POST /api/v1/gates/{gateId}/precheck} → 结构化「已覆盖/部分/缺失 + 证据定位
 * + AI 参考清单」。权限=评审参与人：注解层复用 {@code ipd:gate-review:list}
 * （与 GateMaterialController 同源，不新增权限码），对象层参与人校验与审计
 * 全在 {@link GatePrecheckService}，Controller 不掺业务。
 *
 * <p>卡面硬约束：只读参考——不写 Gate 决策、不阻塞评审（无请求体、无状态变更）。
 *
 * <p>POST /api/v1/gates/{gateId}/arbitration-divergences → 仲裁分歧点汇总（同轮双 PM
 * 决策不一致清单 + AI 归纳参考，R240 落地）。同为只读参考：响应固定回带
 * {@code blocking=false / decisionWritten=false} 自证旗标，不代写仲裁决策；
 * 权限与对象级参与人校验同预审口径。
 */
@RestController
@RequestMapping("/api/v1/gates/{gateId}")
@RequiredArgsConstructor
public class GatePrecheckController {

    private final GatePrecheckService service;
    private final IpdPermission permission;

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_GATE_REVIEW, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/precheck")
    public ApiV1Response<Map<String, Object>> precheck(@PathVariable Long gateId) {
        IpdActor actor = permission.requireInternal();
        return ApiV1Response.ok(service.precheck(gateId, actor));
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_GATE_REVIEW, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/arbitration-divergences")
    public ApiV1Response<Map<String, Object>> arbitrationDivergences(@PathVariable Long gateId) {
        IpdActor actor = permission.requireInternal();
        return ApiV1Response.ok(service.arbitrationDivergences(gateId, actor));
    }
}
