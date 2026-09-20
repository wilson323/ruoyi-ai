# Pointer #127 — collision-evolve（R-4 演化策略）

> **类型**：机制层（演化策略型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-4 根除策略
> **严重度**：🟢
> **触发**：`scripts/check-r13-hard-3hash.sh`（H-3 升级，30 min）
> **修复路径**：三 hash 声明机制（commit / pre-merge / post-merge）+ hook 拦截

## Why（为何需要）
R129 §三.1 R-4 根除策略 = 升级 hook `check-r13-hard-3hash.sh`。撞号透明登记 + 三 hash 声明 = commit hash / pre-merge hash / post-merge hash 三者必须都填。

## How（如何触发）
```bash
bash scripts/check-r13-hard-3hash.sh
# 故意只填 2 个 hash → 跑 → 应报"缺第 3 hash" → exit ≠ 0
```

## Link（关联）
- #122 collision-r13-hard（根因起点）
- #131 log.md 同步回填
- R129 §三.1 R-4

## 自证能红
故意只填 2 hash → 跑 → exit ≠ 0
