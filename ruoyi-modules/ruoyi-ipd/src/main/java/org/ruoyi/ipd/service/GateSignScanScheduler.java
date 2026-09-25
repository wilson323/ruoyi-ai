package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Gate 签署期限提醒 / 超时弃权扫描调度器（R215-GAP-B4，2026-09-25 批次 2 接线）。
 *
 * <p>背景：{@code scanRemind}（AC-GATE-09 期限前 1 天提醒）与 {@code scanTimeout}
 * （AC-GATE-08 超期未签自动弃权流转）长期只有超管手动 HTTP 入口
 * （POST /api/v1/gates/sign/scan-remind、/scan-timeout），全模块无 @Scheduled 覆盖——
 * 无人触发即功能空转（孤儿盘点定性 GAP-B4 成员）。
 *
 * <p>幂等取证（细节见 docs/ipd-系统说明/验收/R215-增量收口-20260924/gap-b4-schedulers.md）：
 * <ul>
 *   <li>scanRemind：通知走 {@code NotificationService.publishDaily}（dedupKey 含自然日
 *       yyyyMMdd），同日重扫不重发；提醒窗口 {@code 0 < untilDue ≤ 24h} 天然逐日轮转。</li>
 *   <li>scanTimeout：选择条件仅 status=PENDING，settleTimeout 落终态后不再命中；
 *       知会走 {@code publish}（dedupKey=gate:GATE_ABSTAINED:gateId:receiverId 永久去重），
 *       双保险无重发路径。</li>
 * </ul>
 *
 * <p>调度语义：每日 09:20 期限提醒、09:25 超时弃权折算（两窗口互斥，先后无耦合）。
 * 系统身份审计落名沿用 RequirementChangeService.SYSTEM_ACTOR 先例（id=0/system/SYSTEM）。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:00 离职 / 09:05 移交 / 09:10 共担KPI /
 * 09:15 评分待办 / <b>09:20、09:25 本 job</b> / 09:30 遗留逾期 / 09:35 P0 升级链。
 * 手动兜底路径保留：两 HTTP 端点仍供超管补跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GateSignScanScheduler {

    /** 定时任务系统身份（审计落名；服务侧无角色校验，仅 operator 字段落审计行）。 */
    static final IpdActor SYSTEM_ACTOR = new IpdActor(0L, "system", "SYSTEM", null);

    private final GateReviewService gateReviewService;

    /** 可注入时钟（仿 KpiSharedDeadlineScheduler；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private Clock clock = Clock.systemDefaultZone();

    public void setClock(Clock clock) {
        this.clock = (clock == null) ? Clock.systemDefaultZone() : clock;
    }

    /**
     * 每日 09:20 签署期限前 1 天提醒（AC-GATE-09；publishDaily 同日去重）。
     */
    @Scheduled(cron = "0 20 9 * * ?")
    public void dailySignRemindScan() {
        int reminded = gateReviewService.scanRemind(SYSTEM_ACTOR);
        log.info("GateSignScanScheduler remind: scanDate={} reminded={}", LocalDate.now(clock), reminded);
    }

    /**
     * 每日 09:25 签署超期弃权折算（AC-GATE-08；状态机落终态 + publish 永久去重，重跑静默）。
     */
    @Scheduled(cron = "0 25 9 * * ?")
    public void dailySignTimeoutScan() {
        int handled = gateReviewService.scanTimeout(SYSTEM_ACTOR);
        log.info("GateSignScanScheduler timeout: scanDate={} abstainHandled={}", LocalDate.now(clock), handled);
    }
}
