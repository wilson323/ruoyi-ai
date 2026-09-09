# A4 worktree 处置矩阵 + D4 cron 验证指引（R25，2026-09-09 fresh 实查）

## A4：17 个 worktree fresh 处置矩阵（git worktree list + rev-list 实查）

### 可安全清（2 个，ahead=0 dirty=0，删 worktree 不删分支）
| worktree | 分支 | 说明 |
|---|---|---|
| /private/tmp/ipd-main-audit | merge/local-main-r15 | 审计遗留，HEAD=8d2b2069 无新 commit |
| /private/tmp/scan-overdue-tenant-fix | fix/scan-overdue-tenant-guard | HEAD=0b096e62 无新 commit |

### 必须保留（15 个，ahead>0 未合或在用）
| worktree | 分支 | ahead | dirty | 保留理由 |
|---|---|---|---|---|
| 主工作区 | main | 8 | 14 | 本轮 R25 根除 8 commit + untracked 留置 |
| wt-p0r2 | fix/p0-r2-round1-20260909 | 8 | 74 | PR 主体（含 d76a6086 接手链）；74 脏=untracked 留置类，随合并后清 |
| wt-r24 | fix/state-machine-wiring-20260909 | 8 | 1 | 兄弟 37 条接线分支，与 wt-p0r2 分裂待 owner 裁决 |
| p131-worktree | p1-3.1-bootstrap | 2 | 0 | 未合 |
| wt-draft-decision-pack | draft/d1-d4-d5-decision-pack | 3 | 0 | 未合 |
| wt-p1q3fix / wt-p1sm / wt-p2rep | fix/p1-quick3 / p1-statemachine / p2-round1-report | 2/1/1 | 0 | 未合 |
| 7 个 agent-* | agent/batch5-x、p133-x、p322-x | 1-3 | 0 | 未合 |

> 处置动作：owner 拍板后执行 `git worktree remove <path>`（分支自动保留）；本矩阵只盘点不动作。

## D4：HandoverOverdueScanner cron（错峰 09:05）验证指引

### 现查结论（2026-09-09 实查）
1. `HandoverOverdueScanner.java` 只存在于 `fix/p0-r2-round1-20260909` 分支（d76a6086 接手件），**main 尚无**。
2. 16039 进程 2026-09-09 07:43:55 启动（main 构建）——**不含 scanner**。
3. `notification_events` 今晨（08:00 后）零事件——**符合预期**（scanner 未在跑，不是 cron 失灵）。

### 合并后的验证步骤（三步）
1. 合并 fix 分支 → 重启 16039（`RuoYiAIApplication.main()`）。
2. 等当日 09:05 触发（或临时把 `@Scheduled(cron=...)` 调至 2 分钟后加速验证，验完改回）。
3. 真库取证：
   ```sql
   SELECT event_type, COUNT(*) FROM notification_events
   WHERE create_time >= CURDATE() AND create_time >= '09:00' GROUP BY event_type;
   ```
   期望：出现超期移交类事件（overdue/scan 相关 event_type）≥1 条；同步核对应用日志 `logs/sys-console.log` 无 scanner 异常栈。
4. 若零事件：先查 `@Scheduled` 是否被 `@Profile`/配置开关排除，再查 `handover_record` 是否确有超期数据（无超期数据时零事件属正常）。
