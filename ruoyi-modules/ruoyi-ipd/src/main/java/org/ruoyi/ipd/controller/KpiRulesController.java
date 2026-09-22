package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.KpiRulesService;
import org.ruoyi.ipd.vo.KpiRuleView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * KPI 规则读端点（R108 / paiban-02 方案 B，零 DB 变更）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /api/v1/kpi/rules} — 当前生效 KPI 规则清单，返回
 *       {@code [{ruleKey, ruleValue}]}；不新建 kpi_rules 表，复用现有 KPI 表数据源。</li>
 * </ul>
 *
 * <p>权限：{@code ipd:kpi:query}（MARKET_PM / RD_PM / GROUP_LEADER / SUPER_ADMIN 可见），
 * 读端点同码 + requireInternal 兜底，与 KpiRecordController / SharedKpiController 一致。
 */
@RestController
@RequestMapping("/api/v1/kpi")
@RequiredArgsConstructor
@Validated
public class KpiRulesController {

    private final IpdPermission permission;
    private final KpiRulesService rulesService;

    /** R108：当前生效 KPI 规则清单（快照优先 → system_configs kpi.* 回退，空源返回空列表）。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_KPI_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/rules")
    public ApiV1Response<List<KpiRuleView>> rules() {
        permission.requireInternal();
        return ApiV1Response.ok(rulesService.listActiveRules());
    }
}
