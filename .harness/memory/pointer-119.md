# Pointer #119 — pipe-trap-red（R-1 根因）

> **类型**：反思层（根因型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-1 实证
> **严重度**：🔴
> **触发**：`bash X.sh | tail; echo $?` 测的是 tail EXIT = 0 → 假绿
> **修复路径**：`scripts/check-pipe-trap.sh` + pre-commit hook

## Why（为何需要）
R129 §三.1 R-1 实证：16/16 脚本"间覆盖"pipe trap 假绿问题。`bash check-X.sh | tail; echo $?` 输出 tail 的 EXIT（0）而非脚本 EXIT（1）。该根因反复穿透 3 层 = 治标不治本。

## How（如何触发）
```bash
bash scripts/check-pipe-trap.sh
# 注入 `bash check-foo.sh | tail` → 应报"pipe trap 风险" → exit ≠ 0
```

## Link（关联）
- #134 自证能红缺失假绿（同根）
- #124 pipe-trap-evolve（演化策略）
- R129 §三.1 R-1

## 自证能红
故意 `bash check-foo.sh | tail` → 跑 check-pipe-trap.sh → exit ≠ 0
