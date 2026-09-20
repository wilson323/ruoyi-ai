# Pointer #123 — five-must-verify-red（R-5 根因）

> **类型**：反思层（根因型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-5 实证
> **严重度**：🟡
> **触发**：五必现查（hash/端口字段/段号/看板回读/跨仓 cd 凭记忆写）违反
> **修复路径**：H-4/H-5/H-6 新脚本（0.5 hr×3）+ Skill `ipd-guard-five-must-verify/`

## Why（为何需要）
R121/R122 报告 commit hash 行用 `TBD` 占位 = 五必现查缺 hash 现查。R129 §三.1 R-5 列为 2/16 极弱。Skill 已存在，缺硬 hook 拦截凭记忆引用。

## How（如何触发）
```bash
bash scripts/check-commit-hash-filled.sh
bash scripts/check-segment-number-uniqueness.sh
bash scripts/check-cd-absolute-path.sh
```

## Link（关联）
- #128 five-verify-evolve（演化策略）
- #131 log.md 同步回填
- #132 跨仓 cd 强校验
- R129 §三.1 R-5

## 自证能红
故意写 TBD 占位 → 跑 check-commit-hash-filled.sh → exit ≠ 0
