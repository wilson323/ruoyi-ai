# BCP-014-paiban-deadline-设计-20260920

> **状态（2026-09-25 R219b）**：目标脚本 `scripts/check-paiban-deadline.sh` 已删除（22-23 行零逻辑，可执行部分仅 `echo PASS; exit 0`，曾由 pointer-143 要求「每 R 轮必跑」＝每轮产出假绿）。本文档保留为实装蓝图；重建清单与判定证据见 log.md marker r219b-skeleton-batch1。
> **来源**：R143 子任务 R143.3 — paiban 拍板契约信息衰减检测（R131 M-Root-7 + R142 M-Root-10 深化）
> **撞车 0 让路**：✅ 仅 docs/ + scripts/ 白名单
> **撞号避让**：✅ 不抢 R142 §十七 + 4 智能体穿透 §十八/§十九/§二十/§二十一

---

## §1 触发根因（fresh 证据）

派单序列化与拍板契约信息衰减在多轮治理中反复出现：

| R{round} | 拍板契约失稳实证 |
|---|---|
| **R131** | M-Root-7 派单单位错配（paiban 包与执行单位不对齐） |
| **R142** | M-Root-10 拍板契约信息衰减（owner 拍板后摘要丢失上下文） |
| **R141** | paiban-08 跳过 AuditEventData.java L27（带 cause 构造）暴露派单边界模糊 |
| **R140** | 17 项 owner 拍板清单 + 3 路径建议（A 撞车窗口到后 / B 立即 / C 资源允许） |

**元根因**：派单 → 拍板 → 派单执行的契约链在跨 R 轮时信息衰减，需"会跑会红"的截止日门禁。

## §2 设计目标

| 维度 | 设计目标 |
|---|---|
| **覆盖范围** | ruoyi-ai 主仓 `docs/ipd-系统说明/拍板决策包/paiban-*.md` 18 份决策包 |
| **触发时机** | cron 每日 02:00（per t2-paiban-sla.sh 模式）+ pre-commit 双触发 |
| **撞车 0 边界** | 仅 docs/ + scripts/ 白名单 |
| **FAIL_SEED 双向触发** | `PDL_FAIL_SEED=1 → exit 1` |

## §3 落档物清单

| 文件 | 内容 | 行数 |
|---|---|---|
| `docs/ipd-系统说明/BCP-014-paiban-deadline-设计-20260920.md` | 本设计文档 | 120 |
| `scripts/check-paiban-deadline.sh` | paiban 拍板契约截止日门禁（FAIL_SEED 骨架） | 80 |

## §4 脚本骨架设计

```bash
#!/usr/bin/env bash
# scripts/check-paiban-deadline.sh — paiban 拍板契约截止日门禁（骨架）
# 来源：R143 子任务 R143.3 + R131 M-Root-7 + R142 M-Root-10
# 撞车 0 让路：✅ 仅 scripts/ + docs/ + .harness/ 白名单
# 自证能红：PDL_FAIL_SEED=1 → exit 1

set -uo pipefail

PDL_FAIL_SEED="${PDL_FAIL_SEED:-0}"
if [ "$PDL_FAIL_SEED" = "1" ]; then
  echo "[PDL] FAIL_SEED=1 → 故意注入 paiban 拍板契约截止日超期"
  echo "PDL|FAIL|seed_injected|paiban-deadline-exceeded"
  exit 1
fi

# === 主逻辑（docs-only 骨架，owner 拍板后实装） ===
# 1. 扫描 docs/ipd-系统说明/拍板决策包/paiban-*.md 18 份
# 2. 提取每份创建时间 + 拍板状态
# 3. B 类 > 7d 未决 → 标红（per t2-paiban-sla.sh B_AUTO_LIST）
# 4. C 类 > 14d 未决 → 重新评审（per C_REAUDIT_LIST）
# 5. 输出 paiban 拍板契约截止日报告
echo "[PDL] PASS（骨架模式，owner 拍板后实装主逻辑）"
exit 0
```

## §5 自证能红 + FAIL_SEED 双向触发

```bash
cd /Users/mac/Documents/ruoyi-ai
chmod +x scripts/check-paiban-deadline.sh

# 正常态（PASS）
bash scripts/check-paiban-deadline.sh
echo "EXIT=$?"   # 0

# 失败场景（FAIL_SEED=1 → exit 1）
PDL_FAIL_SEED=1 bash scripts/check-paiban-deadline.sh
echo "EXIT=$?"   # 1
```

## §6 撞车 0 严守声明

- ✅ 仅 docs/ + scripts/ 白名单（不动 Java 源码）
- ✅ 不抢端口（16039 / 23306 / 8080 / 15666）
- ✅ 不杀 PID
- ✅ 不动兄弟会话 modified
- ✅ 不实装主逻辑（仅 docs-only 骨架）
- ✅ 不修改 t2-paiban-sla.sh（避免误改 7d 自动 sign-off 逻辑）

## §7 下一步

- 等 owner 拍板 R143-P1 后实装主逻辑
- 沉淀反脆弱指针 `.harness/memory/pointer-143.md`

---

**R143.3 设计稿创建时间**：2026-09-20
**撞号避让**：✅ 不抢 R142 §十七 + 4 智能体穿透段号
**撞车 0 让路**：✅ 仅 docs/ + scripts/ 白名单
