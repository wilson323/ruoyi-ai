# Pointer #128 — five-verify-evolve（R-5 演化策略）

> **类型**：机制层（演化策略型）
> **创建时间**：2026-09-20（R132 补落）
> **基线**：R129 §三.1 R-5 根除策略
> **严重度**：🟢
> **触发**：硬 hook 拦截凭记忆引用（H-4/H-5/H-6 三脚本，0.5 hr×3）
> **修复路径**：skill `ipd-guard-five-must-verify/` + 三硬 hook

## Why（为何需要）
R129 §三.1 R-5 根除策略 = 3 个新脚本 + Skill 教化。把五必现查每项都加 hook：hash 现查 / 端口现查 / 段号现查 / 看板回读 / 跨仓 cd 绝对路径。

## How（如何触发）
```bash
bash scripts/check-commit-hash-filled.sh
bash scripts/check-segment-number-uniqueness.sh
bash scripts/check-cd-absolute-path.sh
```

## Link（关联）
- #123 five-must-verify-red（根因起点）
- #131 log.md 同步回填
- #132 跨仓 cd 强校验
- R129 §三.1 R-5

## 自证能红
故意凭记忆写 commit hash → 跑 → 应报"hash 未现查" → exit ≠ 0
