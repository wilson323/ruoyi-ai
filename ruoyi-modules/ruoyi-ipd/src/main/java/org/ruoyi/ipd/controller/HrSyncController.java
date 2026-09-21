package org.ruoyi.ipd.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.hr.HrSyncJob;
import org.ruoyi.ipd.hr.RealHrSyncAdapter;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.AuditLogService;
import org.ruoyi.ipd.service.HrSyncService;
import org.ruoyi.ipd.service.PersonService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * HR 同步事件接入端点（P2-2.2；AC-AUTH-06/AC-HAND-01）+ HR 真源同步端点（R149-v1）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /api/v1/hr-sync/mark-resigned} HR 系统上报离职 → 联动冻结 + 撤销会话 + 企微解绑 + 通知</li>
 *   <li>{@code POST /api/v1/hr-sync/escalate-stale-resignations} 管理员手动触发 15 日倒计时升级</li>
 *   <li>{@code GET /api/v1/hr-sync/pending-handovers} 列出当前所有 FROZEN_PENDING_HANDOVER 人员</li>
 *   <li>{@code POST /api/v1/hr-sync/sync-now} 管理员手动触发 HR 真源全量同步（R149-v1 D4）</li>
 *   <li>{@code POST /api/v1/hr-sync/sync-one} 管理员按工号触发单人同步</li>
 *   <li>{@code GET /api/v1/hr-sync/last-run} 上一次同步结果（看板 / 管理员视图）</li>
 * </ul>
 *
 * <p>权限：mark-resigned / escalate / sync-now / sync-one / last-run 限 SUPER_ADMIN；
 * pending-handovers 限 GROUP_LEADER + SUPER_ADMIN。
 */
@RestController
@RequestMapping("/api/v1/hr-sync")
@RequiredArgsConstructor
public class HrSyncController {

    private final HrSyncService hrSyncService;
    private final IpdPermission permission;
    private final AuditLogService auditLogService;
    private final HrSyncJob hrSyncJob;
    private final RealHrSyncAdapter realHrSyncAdapter;

    public record MarkResignedRequest(@NotNull Long personId, @NotBlank String reason) { }

    public record ResignView(boolean idempotent, long pendingProjects, String message,
                             boolean wecomUnbound, boolean sessionsRevoked, int notificationsSent) {
        public static ResignView from(PersonService.ResignResult r) {
            return new ResignView(r.idempotent(), r.pendingProjects(), r.message(),
                r.wecomUnbound(), r.sessionsRevoked(), r.notificationsSent());
        }
    }

    public record PendingHandoverView(Long personId, String name, String employeeNo, Long groupId,
                                      String frozenSince, long activeProjects, long ageDays,
                                      boolean escalate) {
        public static PendingHandoverView from(HrSyncService.PendingHandover p) {
            return new PendingHandoverView(p.personId(), p.name(), p.employeeNo(), p.groupId(),
                p.frozenSince() == null ? null : p.frozenSince().toString(),
                p.activeProjects(), p.ageDays(), p.escalate());
        }
    }

    public record EscalateResponse(int escalated, int thresholdDays) { }

    /** R149-v1 D4：sync-one 入参（按工号单人同步）。 */
    public record SyncOneRequest(@NotBlank String employeeNo) { }

    /** R149-v1 D4：sync-now/sync-one 出参。 */
    public record SyncStatsResponse(int personsFetched, int personsUpserted, int personsSkipped,
                                    int failures, int orgsFetched, int orgsUpserted, long costMs) {
        public static SyncStatsResponse from(RealHrSyncAdapter.SyncStats s) {
            return new SyncStatsResponse(s.personsFetched(), s.personsUpserted(),
                s.personsSkipped(), s.failures(), s.orgsFetched(), s.orgsUpserted(), s.costMs());
        }
    }

    /** R149-v1 D4：last-run 出参（nullable 各字段）。 */
    public record LastRunResponse(long startedAt, long finishedAt, long costMs,
                                  String triggerBy, boolean ok, SyncStatsResponse stats,
                                  String errorMessage) {
        public static LastRunResponse from(HrSyncJob.LastRun r) {
            if (r == null) return null;
            return new LastRunResponse(r.startedAt(), r.finishedAt(), r.costMs(),
                r.triggerBy(), r.isOk(),
                r.stats() == null ? null : SyncStatsResponse.from(r.stats()),
                r.errorMessage());
        }
    }

    /**
     * HR 系统上报离职 → PersonService.resign 完整联动链路（AC-AUTH-06）。
     * <p>限 SUPER_ADMIN（HR 系统直推，超管收口）；幂等：重复上报返 idempotent=true。
     */
    @PostMapping("/mark-resigned")
    public ApiV1Response<ResignView> markResigned(@Valid @RequestBody MarkResignedRequest req) {
        IpdActor operator = permission.requireAdmin();
        PersonService.ResignResult result = hrSyncService.markResignedByHr(
            req.personId(), req.reason(), operator);
        audit(operator, "hr_mark_resigned", req.personId(), req.reason());
        return ApiV1Response.ok(ResignView.from(result));
    }

    /**
     * 管理员手动触发 15 日倒计时升级（AC-HAND-01 末段）。生产由 cron 每日 09:00 触发。
     */
    @PostMapping("/escalate-stale-resignations")
    public ApiV1Response<EscalateResponse> escalateStaleResignations(
            @RequestParam(value = "thresholdDays", required = false, defaultValue = "15") int thresholdDays) {
        IpdActor operator = permission.requireAdmin();
        int escalated = hrSyncService.escalateStaleResignations(thresholdDays, operator);
        return ApiV1Response.ok(new EscalateResponse(escalated, thresholdDays));
    }

    /**
     * 列出当前所有 FROZEN_PENDING_HANDOVER 人员（前端待移交收件箱 + 管理员视图共用）。
     */
    @GetMapping("/pending-handovers")
    public ApiV1Response<List<PendingHandoverView>> listPendingHandovers(
            @RequestParam(value = "thresholdDays", required = false, defaultValue = "15") int thresholdDays) {
        permission.requireLeaderOrAdmin();
        return ApiV1Response.ok(hrSyncService.listPendingHandoverEscalations(thresholdDays).stream()
            .map(PendingHandoverView::from).toList());
    }

    private void audit(IpdActor actor, String action, Long entityId, String reason) {
        if (actor == null) {
            return;
        }
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id())
            .operatorName(actor.name())
            .operatorRole(actor.role())
            .action(action)
            .entityType("persons")
            .entityId(entityId)
            .reason(reason)
            .build());
    }

    /**
     * R149-v1 D4：管理员手动触发 HR 真源全量同步（异步执行，秒级响应返回 last-run 占位）。
     *
     * <p>权限：SUPER_ADMIN。生产由 cron 每日 02:00 自动跑；本端点是 admin 应急入口（HR 紧急
     * 增删人后立刻触发，避免等到次日凌晨 02:00）。
     */
    @PostMapping("/sync-now")
    public ApiV1Response<SyncStatsResponse> syncNow() {
        IpdActor operator = permission.requireAdmin();
        String triggerBy = "MANUAL:" + operator.id();
        HrSyncJob.LastRun lr = hrSyncJob.runOnce(triggerBy);
        audit(operator, "hr_sync_now", null, triggerBy);
        if (!lr.isOk()) {
            return ApiV1Response.ok(new SyncStatsResponse(0, 0, 0, 1, 0, 0, lr.costMs()));
        }
        return ApiV1Response.ok(SyncStatsResponse.from(lr.stats()));
    }

    /**
     * R149-v1 D4：管理员按工号触发单人同步（HR 紧急给某人开账号 / 关账号用）。
     */
    @PostMapping("/sync-one")
    public ApiV1Response<SyncStatsResponse> syncOne(@Valid @RequestBody SyncOneRequest req) {
        IpdActor operator = permission.requireAdmin();
        String triggerBy = "MANUAL_ONE:" + operator.id() + ":" + req.employeeNo();
        RealHrSyncAdapter.SyncStats stats = realHrSyncAdapter.syncOne(req.employeeNo(), triggerBy);
        audit(operator, "hr_sync_one", null, triggerBy);
        return ApiV1Response.ok(SyncStatsResponse.from(stats));
    }

    /**
     * R149-v1 D4：上一次 HR 同步结果（看板 / 管理员视图）。
     */
    @GetMapping("/last-run")
    public ApiV1Response<LastRunResponse> lastRun() {
        permission.requireAdmin();
        return ApiV1Response.ok(LastRunResponse.from(hrSyncJob.lastResult()));
    }
}
