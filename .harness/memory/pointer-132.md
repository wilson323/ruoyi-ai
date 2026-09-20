# Pointer #132 — 跨仓 cd 强校验

> **类型**：机制层
> **创建时间**：2026-09-20
> **基线**：R131 落地后（R130 §六 D4 + R128 §二-注意事项 #4）
> **严重度**：🔴
> **触发**：`.claude/hooks/pre-cd-cross-repo-check.sh`（H-12 候选）cwd 漂移检测
> **修复路径**：pre-cd hook 强校验 cwd 绝对路径 vs 目标仓

## Why（为何需要）
R128 §二-注意事项 #4 实证：`cd ruoyi-ai && git log` 在 sandbox cwd 漂移时实际进错仓，操作另一仓的文件还以为在本仓。跨仓操作必须用绝对路径 + cwd 现查 + 目标仓 commit hash 校验，禁止相对路径 cd。

## How（如何触发）
```bash
# pre-cd hook（每次 cd 跨仓前）
bash .claude/hooks/pre-cd-cross-repo-check.sh \
  --target /Users/mac/Documents/ruoyi-ai \
  --expect-remote origin/main
```

## Link（关联）
- #123 five-must-verify-red（五必现查跨仓 cd）
- #134 自证能红缺失假绿（cwd 漂移 = 假绿）
- R130 §六 D4

## 自证能红
故意 cd 到其他仓再回退 cwd → 跑 hook → exit ≠ 0
