# Pointer #129 — 三源对账批判

> **类型**：反思层
> **创建时间**：2026-09-20
> **基线**：R131 落地后（R130 §六 D5-D7 实证）
> **严重度**：🔴
> **触发**：`verify.sh --cross-audit-report` 步骤探测三源漂移
> **修复路径**：`.harness/evolve/verify.sh` + `scripts/check-mirror-vs-board.py`

## Why（为何需要）
R128 提到 `check-ssot-drift.mjs` 脚本根本不存在（指错文件类型为 `.sh`），R130 §六 D5-D7 三源对账发现镜像 vs log.md vs git branch 在多轮派单下漂移。三源对账不能只看"是否都有"，必须批判"内容是否一致 / 时序是否对得上 / 撞号是否透明"。

## How（如何触发）
```bash
bash scripts/check-mirror-vs-board.py --strict
bash .harness/evolve/verify.sh --cross-audit-report
```

## Link（关联）
- #122 collision-r13-hard（撞号透明登记）
- #131 log.md 同步回填（同根问题：log 漂移）
- R130 §六 D5-D7 三源对账原始证据

## 自证能红
故意改 docs/ipd-系统说明/R131 镜像行 vs git branch → 重跑 → exit ≠ 0
