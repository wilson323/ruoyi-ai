# Pointer #122 — collision-r13-hard（R-4 根因）

> **类型**：反思层（根因型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-4 实证
> **严重度**：🟡
> **触发**：同分钟多 commit 撞 hash
> **修复路径**：`scripts/check-commit-hash-uniqueness.sh`（H-3）+ 三 hash 声明机制

## Why（为何需要）
R129 §三.1 R-4 实证：R121/R122 同 commit `3df19c34` 撞号。撞号 = SSOT 漂移之源。已基本机制化（撞号透明登记 + 三 hash 声明），需升级 hook `check-r13-hard-3hash.sh`。

## How（如何触发）
```bash
bash scripts/check-commit-hash-uniqueness.sh
# 故意 1 分钟内 commit 两次 → 重跑 → 第二个 commit 应被拦 → exit ≠ 0
```

## Link（关联）
- #127 collision-evolve（演化策略）
- #131 log.md 同步回填（同根：撞号）
- R129 §三.1 R-4

## 自证能红
故意快速连 commit 撞 hash → 跑 → exit ≠ 0
