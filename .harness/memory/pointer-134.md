# Pointer #134 — 自证能红缺失假绿

> **类型**：验证层
> **创建时间**：2026-09-20
> **基线**：R131 落地后（R131 §二.2.2 类型 4 + R121 §二.2 实证）
> **严重度**：🔴
> **触发**：`gate.sh --red-self-test` 反证每脚本都能 exit ≠ 0
> **修复路径**：`scripts/check-gate-self-red.sh` 已落（R120）+ `gate.sh --red-self-test` 加 wrapper

## Why（为何需要）
R131 §二.2.2 类型 4 实证：16 脚本中 0/16 = 0% 有"故意触发→exit ≠ 0"自证。R121 §二.2 E2E HTTP=000 全 FAIL 但报"绿"，正是自证能红缺失的假绿典型。任何验证脚本必须能反证"故意破坏 → exit ≠ 0"，否则就是空壳绿。

## How（如何触发）
```bash
bash scripts/check-gate-self-red.sh    # 每脚本都故意触发一次失败注入
bash .harness/evolve/gate.sh --red-self-test   # 5 维门控自证
```

## Link（关联）
- #119 pipe-trap-red（同根：pipe trap 也是自证缺失）
- #129 三源对账批判（自证缺失 = 假绿源头）
- R131 §二.2.2 类型 4

## 自证能红
故意把 check-*.sh 改成 `exit 0` 跑 → 应报"假绿" → exit ≠ 0
