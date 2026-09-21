package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.RecoveryWarning;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.RecoveryWarningService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 90 日回款预警 Controller（C1，R149 batch2a）。
 *
 * <p>2 端点：
 * <ul>
 *   <li>{@code POST /api/v1/recovery/check-90d} — 扫描所有上市后未满 90 日的项目，
 *       回款比例低于阈值（默认 0.25）的写入 recovery_warnings，
 *       权限 {@code ipd:recovery:check-90d}（GROUP_LEADER / SUPER_ADMIN）</li>
 *   <li>{@code GET  /api/v1/recovery/warnings} — 按项目（可空）查询预警列表，
 *       权限 {@code ipd:recovery:warnings:query}（内部四角色）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/recovery")
@RequiredArgsConstructor
@Validated
public class RecoveryWarningController {

    private final IpdPermission ipdPermission;
    private final RecoveryWarningService recoveryWarningService;

    /**
     * 触发 90 日回款预警扫描。
     *
     * @param scanDate 扫描当日（YYYY-MM-DD，可空；空时取系统当前日期）
     * @return 新增预警条数
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_RECOVERY_CHECK_90D, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/check-90d")
    public ApiV1Response<Integer> check90d(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate scanDate
    ) {
        // 兕底与注解同严：注解限组长/超管，service 不再放宽
        ipdPermission.requireLeaderOrAdmin();
        int saved = recoveryWarningService.checkAndGenerate(scanDate);
        return ApiV1Response.ok(saved);
    }

    /**
     * 列出预警（按 warning_date DESC）。
     *
     * @param projectId 项目ID（可空；为空时返回全部）
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_RECOVERY_WARNINGS_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/warnings")
    public ApiV1Response<List<RecoveryWarning>> list(
        @RequestParam(required = false) Long projectId
    ) {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(recoveryWarningService.list(projectId));
    }
}
