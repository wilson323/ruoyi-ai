# BCP-014-five-bores-stagnation-设计-20260920

> **来源**：R143 子任务 R143.4 — 5 钻撞根因覆盖率停滞预警（R131/R141 5 钻 39/80 = 48.75% 停滞）
> **撞车 0 让路**：✅ 仅 docs/ + scripts/ 白名单
> **撞号避让**：✅ 不抢 R142 §十七 + 4 智能体穿透 §十八/§十九/§二十/§二十一

---

## §1 触发根因（fresh 证据）

5 钻撞根因覆盖率长期停滞：

| 阶段 | 5 钻覆盖率 | 增速 |
|---|---|---|
| **R131 末** | 32/80（40%） | — |
| **R138 末** | 36/80（45%） | +4/80 = 5% |
| **R141 末** | 38/80（47.5%） | +2/80 = 2.5% |
| **R142 末** | 39/80（48.75%） | +1/80 = 1.25% |
| **R143 预估** | 40-42/80（50-52.5%） | +1-3/80（待实证） |

**元根因**：钻数增长依赖每 R 轮手动识别根因类别 + 撞根因映射，缺乏"会跑会红"的覆盖率停滞预警。

## §2 设计目标

| 维度 | 设计目标 |
|---|---|
| **覆盖范围** | ruoyi-ai 主仓 BCP-Registry §三 5 钻撞根因覆盖率度量表 |
| **触发时机** | pre-commit + cron 每周一次（per ruoyi-ai-reconcile-weekly 模式） |
| **撞车 0 边界** | 仅 docs/ + scripts/ 白名单 |
| **FAIL_SEED 双向触发** | `FBS_FAIL_SEED=1 → exit 1` |

## §3 落档物清单

| 文件 | 内容 | 行数 |
|---|---|---|
| `docs/ipd-系统说明/BCP-014-five-bores-stagnation-设计-20260920.md` | 本设计文档 | 120 |
| `scripts/check-five-bores-stagnation.sh` | 5 钻覆盖率停滞预警门禁（FAIL_SEED 骨架） | 80 |

## §4 脚本骨架设计

```bash
#!/usr/bin/env bash
# scripts/check-five-bores-stagnation.sh — 5 钻覆盖率停滞预警门禁（骨架）
# 来源：R143 子任务 R143.4 + R131/R141 5 钻 39/80 仍 48.75% 停滞
# 撞车 0 让路：✅ 仅 scripts/ + docs/ + .harness/ 白名单
# 自证能红：FBS_FAIL_SEED=1 → exit 1

set -uo pipefail

FBS_FAIL_SEED="${FBS_FAIL_SEED:-0}"
if [ "$FBS_FAIL_SEED" = "1" ]; then
  echo "[FBS] FAIL_SEED=1 → 故意注入 5 钻覆盖率停滞"
  echo "FBS|FAIL|seed_injected|five-bores-stagnation-detected"
  exit 1
fi

# === 主逻辑（docs-only 骨架，owner 拍板后实装） ===
# 1. 扫描 BCP-Registry §三 5 钻撞根因覆盖率度量表
# 2. 提取最近 3 R 轮的覆盖率数字
# 3. 若增速 < 1/80 → 预警停滞
# 4. 输出 5 钻覆盖率停滞预警报告
echo "[FBS] PASS（骨架模式，owner 拍板后实装主逻辑）"
exit 0
```

## §5 自证能红 + FAIL_SEED 双向触发

```bash
cd /Users/mac/Documents/ruoyi-ai
chmod +x scripts/check-five-bores-stagnation.sh

# 正常态（PASS）
bash scripts/check-five-bores-stagnation.sh
echo "EXIT=$?"   # 0

# 失败场景（FAIL_SEED=1 → exit 1）
FBS_FAIL_SEED=1 bash scripts/check-five-bores-stagnation.sh
echo "EXIT=$?"   # 1
```

## §6 撞车 0 严守声明

- ✅ 仅 docs/ + scripts/ 白名单（不动 Java 源码）
- ✅ 不抢端口（16039 / 23306 / 8080 / 15666）
- ✅ 不杀 PID
- ✅ 不动兄弟会话 modified
- ✅ 不实装主逻辑（仅 docs-only 骨架）

## §7 下一步

- 等 owner 拍板 R143-P1 后实装主逻辑
- 沉淀反脆弱指针 `.harness/memory/pointer-143.md`

---

**R143.4 设计稿创建时间**：2026-09-20
**撞号避让**：✅ 不抢 R142 §十七 + 4 智能体穿透段号
**撞车 0 让路**：✅ 仅 docs/ + scripts/ 白名单
