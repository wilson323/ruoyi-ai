# Pointer #126 — sandbox-evolve（R-3 演化策略）

> **类型**：机制层（演化策略型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-3 根除策略
> **严重度**：🟢
> **触发**：`is_background=true` 必填 agent SOP + skill S3
> **修复路径**：H-2 新脚本 + 3 次 ps -p 存活确认（1 hr）

## Why（为何需要）
R129 §三.1 R-3 根除策略 = `is_background=true` 必填 SOP。Agent 调用任何起后台进程的 bash 必须 `is_background=true`，且跑完立刻 `ps -p $PID` 验证。

## How（如何触发）
```bash
# SOP：起后端
is_background=true java -jar target/x.jar &
PID=$!
sleep 30 && ps -p $PID >/dev/null || { echo "DEAD"; exit 1; }
sleep 60 && ps -p $PID >/dev/null || exit 1
sleep 120 && ps -p $PID >/dev/null || exit 1
```

## Link（关联）
- #121 sandbox-bg-process（根因起点）
- R129 §三.1 R-3

## 自证能红
故意 is_background=false → 跑 → 30s 后 PID 死 → exit ≠ 0
