package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.LandedScenario;
import org.ruoyi.ipd.dto.CreateLandedScenarioReq;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.LandedScenarioService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 落地场景登记 Controller（A4 落地场景登记，R149 batch2a）。
 *
 * <p>3 端点：
 * <ul>
 *   <li>{@code POST /api/v1/scenarios/landed} — PM 录入单个落地场景，权限 {@code ipd:scenario:landed:create}</li>
 *   <li>{@code POST /api/v1/scenarios/landed/import} — PM 批量导入（JSON 数组），权限 {@code ipd:scenario:landed:create}</li>
 *   <li>{@code GET  /api/v1/scenarios/landed} — 按项目（必填）+ 月份（可空）查询，权限 {@code ipd:scenario:landed:query}</li>
 * </ul>
 *
 * <p>用户拍板简化：仅做登记界面 + 导入入口，不做双认定验证。
 */
@RestController
@RequestMapping("/api/v1/scenarios/landed")
@RequiredArgsConstructor
@Validated
public class LandedScenarioController {

    private final IpdPermission ipdPermission;
    private final LandedScenarioService landedScenarioService;

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SCENARIO_LANDED_CREATE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping
    public ApiV1Response<LandedScenario> create(@Valid @RequestBody CreateLandedScenarioReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        LandedScenario draft = new LandedScenario()
            .setProjectId(req.projectId())
            .setScenarioCode(req.scenarioCode())
            .setScenarioName(req.scenarioName())
            .setLandedDate(req.landedDate())
            .setLandedAmount(req.landedAmount())
            .setRemark(req.remark());
        return ApiV1Response.ok(landedScenarioService.record(draft, actor));
    }

    /**
     * 批量导入（接收 JSON 数组）。上限 {@link LandedScenarioService#BATCH_IMPORT_MAX} 条。
     *
     * @return 成功导入条数
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SCENARIO_LANDED_CREATE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/import")
    public ApiV1Response<Integer> importBatch(@Valid @RequestBody List<CreateLandedScenarioReq> reqs) {
        IpdActor actor = ipdPermission.requireInternal();
        List<LandedScenario> items = reqs.stream().map(req -> new LandedScenario()
            .setProjectId(req.projectId())
            .setScenarioCode(req.scenarioCode())
            .setScenarioName(req.scenarioName())
            .setLandedDate(req.landedDate())
            .setLandedAmount(req.landedAmount())
            .setRemark(req.remark())
        ).toList();
        int saved = landedScenarioService.importBatch(items, actor);
        return ApiV1Response.ok(saved);
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SCENARIO_LANDED_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<List<LandedScenario>> list(
        @RequestParam Long projectId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period
    ) {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(landedScenarioService.list(projectId, period));
    }
}
