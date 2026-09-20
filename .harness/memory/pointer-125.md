# Pointer #125 — additional-location-evolve（R-2 演化策略）

> **类型**：机制层（演化策略型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-2 根除策略
> **严重度**：🟢
> **触发**：启后端 bash 函数强制 --spring.config.additional-location
> **修复路径**：H-1 新脚本 + skill S2（1 hr）

## Why（为何需要）
R129 §三.1 R-2 根除策略 = 启后端 bash 函数强制参数 + skill S2。封一个 `start_ipd_backend()` 函数，强制带 additional-location，未带即 abort。

## How（如何触发）
```bash
# bin/start-ipd-backend.sh 强校验
start_ipd_backend() {
  local args="$*"
  [[ "$args" == *"--spring.config.additional-location"* ]] || {
    echo "ABORT: 必须带 --spring.config.additional-location" >&2
    return 1
  }
  java -jar $args
}
```

## Link（关联）
- #120 additional-location-mandatory（根因起点）
- R129 §三.1 R-2

## 自证能红
故意 start_ipd_backend 不带 additional-location → 跑 → exit ≠ 0
