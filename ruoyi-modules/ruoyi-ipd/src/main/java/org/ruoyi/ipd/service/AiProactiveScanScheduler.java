package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * R221 AI 主动执行扫描器（spec §3.1 ACTIVE 定时档）：每日 09:55 扫 7 日内到期、
 * NOT_STARTED、execMode ∈ {AI_DIRECT, AI_GENERATE} <b>且已有接线执行器</b>
 * （{@link AiExecutionEngine#wiredActionCodes()} 单一事实源，接线一批放开一批）的阶段动作，逐个
 * {@link AiExecutionTrigger#triggerSchedule} 建 SCHEDULE 任务（dedup 守卫天然防重复建任务）。
 *
 * <p>HUMAN_GATE 档（如 C11）不在此主动触发——备料由到期日 PASSIVE/后续批次接管（spec §4.2）。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:50 动作逾期提醒 / <b>09:55 本 job</b> /
 * 每月 1 日 10:00 津贴台账。单行异常 try/catch 不阻断（NotificationOutboxScanner 范式）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiProactiveScanScheduler {

    private static final Duration HORIZON = Duration.ofDays(7);

    private final StageActionMapper stageActionMapper;
    private final AiExecutionTrigger trigger;
    private final AiExecutionEngine engine;

    /** 可注入时钟（仿 P0EscalationScanScheduler；生产零影响）。 */
    private Clock clock = Clock.systemDefaultZone();

    public void setClock(Clock clock) {
        this.clock = (clock == null) ? Clock.systemDefaultZone() : clock;
    }

    @Scheduled(cron = "0 55 9 * * ?")
    public void dailyProactiveScan() {
        Instant now = clock.instant();
        List<StageAction> due = stageActionMapper.selectList(new LambdaQueryWrapper<StageAction>()
            .eq(StageAction::getStatus, "NOT_STARTED")
            .between(StageAction::getDueDate, Date.from(now), Date.from(now.plus(HORIZON))));
        int dispatched = 0;
        for (StageAction a : due) {
            try {
                String code = ActionCatalog.resolveCode(a.getActionCode());
                ActionDef def = ActionCatalog.byCode(code);
                if (!"AI_DIRECT".equals(def.execMode()) && !"AI_GENERATE".equals(def.execMode())) {
                    continue; // HUMAN_GATE 不主动触发（spec §4.2）
                }
                if (!engine.wiredActionCodes().contains(code)) {
                    continue; // 复审问题7：未接线码建了必死（路由 miss → FAILED→DEAD），接线一批放开一批
                }
                trigger.triggerSchedule(a.getProjectId(), code, a.getId());
                dispatched++;
            } catch (Exception e) {
                // 目录外编码/单行 DB 异常：记录并继续下一行，不阻断本轮
                log.warn("[R221] 主动扫描单行失败 actionId={} code={}: {}",
                    a.getId(), a.getActionCode(), e.getMessage());
            }
        }
        log.info("AiProactiveScanScheduler: 7日内到期候选 {} 行，触发 SCHEDULE 任务 {} 行", due.size(), dispatched);
    }
}
