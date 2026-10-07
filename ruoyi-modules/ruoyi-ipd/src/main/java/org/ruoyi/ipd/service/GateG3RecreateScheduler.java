package org.ruoyi.ipd.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F6-③：G3 双周开发复评的每日扫描调度器。
 *
 * <p>背景：G3 在动作目录里对应 D05「双周开发评审」（{@code ActionCatalog} 中 D05.gate()="G3"）。
 * 过去建卡只有一个手工入口（POST /api/v1/projects/{id}/gates?gateCode=），且该入口还压着一层
 * 「同项目同 gateCode 14 天内不可再建」的时间冷却——恰好把双周复评周期当成了禁止建卡窗口，
 * 结果 G3 第二轮永远建不出来。本调度器配合 {@link GateCreationService} 的在途去重修复这两点：
 * 时间维度交回这里判定（距上次 G3 满 {@code gate.g3.recreate_interval_days} 天，默认 14）。
 *
 * <p>判定口径（全部落在 {@link GateCreationService#scanDevG3Recreate()}）：
 * 项目当前阶段为 DEV 且距最近一次 G3 建卡已满复评周期（含「从未建过 G3」的情形）。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：10:10。避开 09:00~09:55 的晨间业务窗口
 * 与 10:05 审计异常扫描，也不与每月 1 日 10:00 的津贴台账生成撞时刻。
 */
@Slf4j
@Component
public class GateG3RecreateScheduler {

    private final GateCreationService gateCreationService;

    public GateG3RecreateScheduler(GateCreationService gateCreationService) {
        this.gateCreationService = gateCreationService;
    }

    /**
     * 每日 10:10 扫描 DEV 阶段项目，为距上次 G3 已满复评周期的项目补建 G3 评审。
     */
    @Scheduled(cron = "0 10 10 * * ?")
    public void dailyG3RecreateScan() {
        int created = gateCreationService.scanDevG3Recreate();
        log.info("GateG3RecreateScheduler: G3 双周复评扫描完成 created={}", created);
    }
}