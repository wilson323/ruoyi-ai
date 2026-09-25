# R215-GAP-B1 收口证据：kpi/shared/deadline-scan 调度接线（2026-09-25）

## 背景
并行盘点（orphan-triage-47-plus-a1a8-audit.md §3.1）定性：`scanMonthlyDeadlines` / `scanDueSoon` 长期只有超管手动 HTTP 入口，全模块无 @Scheduled 覆盖 → 月度截止催办功能空转（P3-1.3 催办链缺口）。

## 实施
- 新增 `KpiSharedDeadlineScheduler`（service 包）：每日 09:10 扫上一自然月周期，双扫描 dueSoon（截止前 1 天 FYI）+ overdue（第 1/3 天催办升级）；可注入 Clock（仿 HandoverOverdueScanner）。幂等靠 publishDaily dedupKey 含自然日，同日重扫不重发；周期未到期时扫描静默零副作用。
- `IpdSchedulingConfig` 错峰表登记 09:10 档（09:00 离职/09:05 移交/09:10 本 job/09:15 评分）。@EnableScheduling 已随 OPS-04 合入主树 → 本 job 部署即生效。
- 手动兜底端点保留（补扫任意历史周期）。

## 验证
- `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=KpiSharedDeadlineSchedulerTest test`（错峰单模块，无 -am/clean）：**Tests run: 3, Failures: 0, Errors: 0** BUILD SUCCESS（2026-09-25 03:25 实测）。
- 用例：①周期推导（2026-09-20→2026-08 双扫描委托）②跨年边界（2026-01-10→2025-12）③setClock(null) 回退。@Tag("dev") 已挂（Surefire groups 假绿陷阱规避）。
- runtime 生效验证依赖下次后端重启 + 真实时钟到 09:10，登记于此不提前销账；重启后看日志 `KpiSharedDeadlineScheduler: period=` 即确认接线。

## 遗留
B2（stage-actions/instantiate 触发方收口）需与业务确认触发时机；B3（negative-feedbacks 裸 setter）属安全治理需 owner 定下线/加守卫；B4（gates scan 三件套调度）随各卡面推进。
