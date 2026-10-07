package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 阶段动作每日提醒调度器（R219 卡④ / 看板 ef20c06a，ACTION_OVERDUE 零发布方接线补齐 2026-09-26；
 * ⑤刀 2026-10-06 并入 ACTION_DUE_SOON 到期前预警）。
 *
 * <p>背景：{@code NotificationService.Types.ACTION_OVERDUE} 长期只有声明零发布方——
 * 真库 notifications 无任何 ACTION_OVERDUE 行，dueDate 已过的开放动作无人提醒。
 *
 * <p>调度语义：每日 09:50 在<b>同一个调度方法</b>内依次跑两条扫描——
 * ① 逾期提醒（ACTION_OVERDUE）：dueDate 已过且仍处开放态（NOT_STARTED/IN_PROGRESS/DELAYED，LIMIT 500）；
 * ② 到期前预警（ACTION_DUE_SOON）：dueDate 恰为 {@code gate.due-soon.days-before}（默认 3）天后的开放态动作。
 * 两者都按 ownerRole 解析接收人（MARKET_PM/RD_PM/BOTH→在职成员；GROUP_LEADER→主组组长）后
 * 走 publishDailyAfterCommit 发 ACTION 类通知（dedupKey 含自然日，同日重扫幂等不重发，次日再提醒，AC-IPD-12）。
 *
 * <p><b>为什么并成一个 @Scheduled 而不是另起 09:52</b>：错峰表每时刻只容一个 job 是硬约束；
 * 但更实际的是 {@code R219SchedulerWiringTest.scheduledMethodOf()} 用 {@code getDeclaredMethods()}
 * 取「第一个」{@code @Scheduled}——同一类挂第二个 @Scheduled 会让它锁到顺序不确定的方法上，
 * 那套测试就必须跟着改。为一个同源、同收件人、幂等键相近的提醒动作去动测试不划算，故并入本方法。
 * 预警扫描另有 try 包裹，炸了也不影响逾期扫描。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:45 邀标过期 / <b>09:50 本 job</b> /
 * 每月 1 日 10:00 津贴台账生成。
 *
 * <p>手动兜底路径：POST /api/v1/stage-actions/overdue-scan（超管，验收复测用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StageActionOverdueScheduler {

    /** ⑤刀：到期前预警的默认提前天数（配置键 {@code gate.due-soon.days-before}）。 */
    static final int DEFAULT_DUE_SOON_DAYS = 3;

    private final StageActionService stageActionService;

    /** 字段注入（非 final，@RequiredArgsConstructor 不参与，既有测试的 1 参 new 不受影响）。 */
    @Value("${gate.due-soon.days-before:3}")
    private int dueSoonDaysBefore = DEFAULT_DUE_SOON_DAYS;

    /**
     * 每日 09:50 执行动作逾期提醒（ACTION_OVERDUE）+ 到期前预警（ACTION_DUE_SOON）两条扫描。
     */
    @Scheduled(cron = "0 50 9 * * ?")
    public void dailyOverdueScan() {
        int notified = stageActionService.notifyOverdueActions();
        log.info("StageActionOverdueScheduler: ACTION_OVERDUE 逾期动作提醒完成 notified={}", notified);
        int dueSoon = 0;
        try {
            dueSoon = stageActionService.notifyDueSoonActions(dueSoonDaysBefore);
        } catch (Exception ex) {
            // 预警链是副链，炸了也不能拖垮逾期提醒（逾期部分的结果已先落日志）
            log.warn("[⑤刀] ACTION_DUE_SOON 到期前预警扫描异常（不影响逾期提醒）: {}", ex.toString());
        }
        log.info("StageActionOverdueScheduler: ACTION_DUE_SOON 到期前预警完成 daysBefore={} notified={}",
            dueSoonDaysBefore, dueSoon);
    }
}