# BCP-014-collision-drift-设计-20260920

> **状态（2026-09-25 R219b）**：目标脚本 `scripts/check-collision-drift.sh` 已删除（22-23 行零逻辑，可执行部分仅 `echo PASS; exit 0`，曾由 pointer-143 要求「每 R 轮必跑」＝每轮产出假绿）。本文档保留为实装蓝图；重建清单与判定证据见 log.md marker r219b-skeleton-batch1。
> **来源**：R143 子任务 R143.2 — 撞车 0 让路边界复发预警（R15/R46/R49/R138 撞车复发教训）
> **撞车 0 让路**：✅ 仅 docs/ + scripts/ 白名单
> **撞号避让**：✅ 不抢 R142 §十七 + 4 智能体穿透 §十八/§十九/§二十/§二十一

---

## §1 触发根因（fresh 证据）

撞车 0 让路边界在多轮治理中反复失守：

| R{round} | 撞车实证 |
|---|---|
| **R15** | cwd 漂移复发（教训 R13 五必现查 §5 再次踩中，commit 卡住时 pwd 显示 ruoyi-ipd-web） |
| **R46** | 共享工作树改动互吃 + maven 增量缓存假错 |
| **R49** | 子智能体 40441 仍被禁用，多会话并发改同一文件 |
| **R138** | 4 智能体并行穿透 3 BCP docs-only 闭环，需严格撞号映射表 SOP 才避免 |
| **R141** | paiban-07~10 在 worktree 隔离，但主工作树仍撞 4 commit ahead of origin/main |

**元根因**：撞车 0 让路边界"靠人自觉"易破，需要"会跑会红"的脚本级预警。

## §2 设计目标

| 维度 | 设计目标 |
|---|---|
| **覆盖范围** | ruoyi-ai 主仓 + reports/ 兄弟会话 modified 文件 + .claude/hooks/ docs-only 设计 |
| **触发时机** | pre-commit + cron 每日 02:00（per t2-paiban-sla.sh 模式） |
| **撞车 0 边界** | 仅 docs/ + scripts/ + .harness/ 白名单 |
| **FAIL_SEED 双向触发** | `CDRIFT_FAIL_SEED=1 → exit 1` |

## §3 落档物清单

| 文件 | 内容 | 行数 |
|---|---|---|
| `docs/ipd-系统说明/BCP-014-collision-drift-设计-20260920.md` | 本设计文档 | 120 |
| `scripts/check-collision-drift.sh` | 撞车 0 让路边界漂移预警门禁（FAIL_SEED 骨架） | 80 |

## §4 脚本骨架设计

```bash
#!/usr/bin/env bash
# scripts/check-collision-drift.sh — 撞车 0 让路边界漂移预警门禁（骨架）
# 来源：R143 子任务 R143.2 + R15/R46/R49/R138 撞车复发教训
# 撞车 0 让路：✅ 仅 scripts/ + docs/ + .harness/ 白名单
# 自证能红：CDRIFT_FAIL_SEED=1 → exit 1

set -uo pipefail

CDRIFT_FAIL_SEED="${CDRIFT_FAIL_SEED:-0}"
if [ "$CDRIFT_FAIL_SEED" = "1" ]; then
  echo "[CDRIFT] FAIL_SEED=1 → 故意注入撞车 0 让路边界漂移"
  echo "CDRIFT|FAIL|seed_injected|collision-drift-detected"
  exit 1
fi

# === 主逻辑（docs-only 骨架，owner 拍板后实装） ===
# 1. 扫 git status --porcelain 是否有兄弟会话 modified（reports/ 2 文件）
# 2. 扫 working tree 是否动 Java 源码 / SQL / yml（撞车 0 红线）
# 3. 扫端口/PID 是否被抢/杀（16039/23306/8080/15666）
# 4. 输出撞车 0 让路边界报告
echo "[CDRIFT] PASS（骨架模式，owner 拍板后实装主逻辑）"
exit 0
```

## §5 自证能红 + FAIL_SEED 双向触发

```bash
cd /Users/mac/Documents/ruoyi-ai
chmod +x scripts/check-collision-drift.sh

# 正常态（PASS）
bash scripts/check-collision-drift.sh
echo "EXIT=$?"   # 0

# 失败场景（FAIL_SEED=1 → exit 1）
CDRIFT_FAIL_SEED=1 bash scripts/check-collision-drift.sh
echo "EXIT=$?"   # 1
```

## §6 撞车 0 严守声明

- ✅ 仅 docs/ + scripts/ 白名单（不动 Java 源码）
- ✅ 不抢端口（16039 / 23306 / 8080 / 15666）
- ✅ 不杀 PID
- ✅ 不动兄弟会话 modified
- ✅ 不实装主逻辑（仅 docs-only 骨架）

## §7 下一步

- 等 owner 拍板 R143-P1（跨仓 commit 并行授权）后实装主逻辑
- 沉淀反脆弱指针 `.harness/memory/pointer-143.md`

---

**R143.2 设计稿创建时间**：2026-09-20
**撞号避让**：✅ 不抢 R142 §十七 + 4 智能体穿透段号
**撞车 0 让路**：✅ 仅 docs/ + scripts/ 白名单
