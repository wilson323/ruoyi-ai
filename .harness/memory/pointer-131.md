# Pointer #131 — log.md 同步回填

> **类型**：文档层
> **创建时间**：2026-09-20
> **基线**：R131 落地后（R131 §三.3.3 + R128 §二-注意事项 #6）
> **严重度**：🟡
> **触发**：`.claude/hooks/wt-close-pre-check.sh`（H-11 候选）扫 log.md 最新段号
> **修复路径**：wt 关闭 pre-check hook 强制回填 log.md 段号

## Why（为何需要）
R128 §二-注意事项 #6 实证：R121/R122 同会话开新报告未查 log.md 最新 R 编号 → 段号撞号。log.md 是 SSOT 段号源，wt 关闭前必须强制回填当下段号 + commit hash，否则下个 R 报告从撞号起步。

## How（如何触发）
```bash
# pre-check hook（wt 关闭前）
bash .claude/hooks/wt-close-pre-check.sh \
  --r-id R132 --segment-id 六 --commit "$(git rev-parse HEAD)"
```

## Link（关联）
- #122 collision-r13-hard（同根：撞号）
- #123 five-must-verify-red（五必现查段号）
- R131 §三.3.3 log 同步机制

## 自证能红
故意传空段号 → 跑 hook → exit ≠ 0
