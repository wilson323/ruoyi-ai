# Pointer #121 — sandbox-bg-process（R-3 根因）

> **类型**：反思层（根因型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-3 实证
> **严重度**：🔴
> **触发**：`is_background=false` 起 java → 30s 后沙箱 SIGHUP 杀 → mvn BUILD SUCCESS 但后端从未启
> **修复路径**：`scripts/check-backend-pid-survive.sh`（H-2 新脚本，1 hr）+ `is_background=true` SOP

## Why（为何需要）
R129 §三.1 R-3 实证 sandbox 回收后台 java 进程，0/16 致命盲区。任何起后端/数据库的 bash 必须 `is_background=true`，且 30s/60s/120s 三次 `ps -p $PID` 确认存活。

## How（如何触发）
```bash
bash scripts/check-backend-pid-survive.sh
# 注入 is_background=false → 30s 后 PID 已死 → exit ≠ 0
```

## Link（关联）
- #126 sandbox-evolve（演化策略）
- R129 §三.1 R-3

## 自证能红
故意 is_background=false → 跑 → 30s 后 PID 不存在 → exit ≠ 0
