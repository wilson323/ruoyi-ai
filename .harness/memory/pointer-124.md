# Pointer #124 — pipe-trap-evolve（R-1 演化策略）

> **类型**：机制层（演化策略型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-1 根除策略
> **严重度**：🟢
> **触发**：`scripts/check-pipe-trap.sh` 每 PR 跑
> **修复路径**：pre-commit hook 30 min + skill S1

## Why（为何需要）
R129 §三.1 R-1 根除策略 = pre-commit hook 拦截 + Skill S1 教化。本指针 = 把根因 #119 升级为可执行钩子，从"知道 pipe trap"到"hook 自动拦 pipe trap"。

## How（如何触发）
```bash
# pre-commit hook 注册
cat >> .git/hooks/pre-commit << 'HOOK_EOF'
bash scripts/check-pipe-trap.sh || exit 1
HOOK_EOF
chmod +x .git/hooks/pre-commit
```

## Link（关联）
- #119 pipe-trap-red（根因起点）
- #134 自证能红缺失假绿
- R129 §三.1 R-1

## 自证能红
故意 `bash X.sh | tail` → 跑 hook → exit ≠ 0
