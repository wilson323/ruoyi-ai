package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.audit.AuditRollbackCounter;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计回滚率只读查询端点（P5 测量件，2026-10-03）。
 *
 * <p>存在意义：让「业务回滚但审计已落库」这个频率<b>可读</b>。
 * 读到 0 或接近 0 就是 P5 审计时序改造的否证依据（方案 §2.5 判据）。
 *
 * <p><b>与 {@link AuditLogController} 分开的原因</b>：本端点是本轮新建的测量能力，
 * 而 {@code AuditLogController} 正被另一路并行修改（验链锚表判据响应），
 * 同文件混改会撞车。两者路径前缀不同（{@code /api/v1/audit-logs} vs
 * {@code /api/v1/audit-rollback-counter}），无路由冲突。
 *
 * <p><b>鉴权</b>：双层——{@code @SaCheckPermission} 走 Sa-Token 权限码
 * {@link IpdPermissionCode#OPERATION_AUDIT_LOG_VERIFY}（与验链同级，因为这本质是验链前的
 * 取证动作），方法体内再 {@code requireAdmin()} 与既有审计只读端点保持一致。
 * 任何一层不通过都会拒绝，<b>不是裸接口</b>。
 *
 * <p><b>只读</b>：只暴露 GET，<b>刻意不提供清零端点</b>——清零只走
 * {@link AuditRollbackCounter#clear()}（代码内可达，无 HTTP 面），
 * 避免测量件变成业务请求可触发的写接口。
 */
@RestController
@RequestMapping("/api/v1/audit-rollback-counter")
@RequiredArgsConstructor
public class AuditRollbackCounterController {

    private final AuditRollbackCounter counter;
    private final IpdPermission ipdPermission;

    /**
     * 读取回滚率统计。
     *
     * <p>响应里 {@code enabled=false} 时，所有计数为 0 是<b>因为没开开关</b>，
     * <b>不代表真实回滚率为 0</b>——读数时必须先看这一位。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AUDIT_LOG_VERIFY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<AuditRollbackCounter.AuditRollbackSnapshot> snapshot() {
        ipdPermission.requireAdmin();
        return ApiV1Response.ok(counter.snapshot());
    }
}
