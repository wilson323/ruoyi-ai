# Pointer #135 — R 报告行数表述差

> **类型**：表述层
> **创建时间**：2026-09-20
> **基线**：R131 落地后（R130 §六 D1 + R121/R122 行数差实证）
> **严重度**：🟡
> **触发**：`scripts/docs-lint.sh numeric-claim-audit` 扫 R 报告数字行
> **修复路径**：docs-lint 加 numeric-claim 子规则，禁止"约/大概/TBD"占位数字

## Why（为何需要）
R130 §六 D1 实证：R 报告常出现"R120 ~ 25 行"实际 28 行、"约 50 个脚本"实际 47 等表述差。SSOT 数字与表述差 > 5% 即污染决策（基于错数字派单）。数字必须现查 + 引用 ≤ 24h 的实测快照，禁止凭印象写。

## How（如何触发）
```bash
bash scripts/docs-lint.sh numeric-claim-audit \
  --docs "docs/ipd-系统说明/R131-*.md" \
  --tolerance-pct 5
```

## Link（关联）
- #123 five-must-verify-red（五必现查数字现查）
- #131 log.md 同步回填（同根：表述 vs 实际漂移）
- R130 §六 D1

## 自证能红
故意在 R 报告写"约 100 行"实际 80 行 → 跑 → 应报"数字偏差 25% > 5%" → exit ≠ 0
