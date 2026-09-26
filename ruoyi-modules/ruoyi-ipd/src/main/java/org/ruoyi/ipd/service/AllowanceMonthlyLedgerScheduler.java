package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 月度津贴台账自动生成调度器（R219 卡④ / 看板 ef20c06a，autoScan 零生产者接线补齐 2026-09-26）。
 *
 * <p>背景：{@code AllowanceLedgerService.autoScan} 长期只 selectCount——allowance_ledgers
 * 没有任何定时生产者，账期台账全靠手工触发的单条记账，月度扫描端点形同虚设。
 *
 * <p>调度语义：每月 1 日 10:00 为上一自然月全量生成台账（幂等：(personId, projectId, month)
 * 已成账跳过；低分腿当月 FINALIZED KPI 综合分 < 60 → STOP_SCORE_BELOW_60 且实发 0；
 * NO_OUTPUT_60_DAYS 腿缺活动数据源本波未接，登记 PARTIAL）。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:50 动作逾期 / <b>每月 1 日 10:00 本 job</b>
 * （月初错峰在 09:00 离职扫描等每日任务之后，不与整点任务叠撞）。
 *
 * <p>手动兜底路径：POST /api/v1/allowance/auto-scan?period=YYYY-MM（超管，验收复测用，
 * 已编排为「先生成后计数」）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AllowanceMonthlyLedgerScheduler {

    private final AllowanceService allowanceService;

    /**
     * 每月 1 日 10:00 为上一自然月生成津贴台账（P3-3.1/3.3 月度扫账自动化）。
     */
    @Scheduled(cron = "0 0 10 1 * ?")
    public void monthlyLedgerGenerate() {
        String previousMonth = YearMonth.now().minusMonths(1).toString();
        int created = allowanceService.generateMonthlyLedgers(previousMonth);
        log.info("AllowanceMonthlyLedgerScheduler: 月度台账生成完成 month={} created={}", previousMonth, created);
    }
}
