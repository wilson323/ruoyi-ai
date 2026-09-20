# BCP-014-cross-session-isolation-设计-20260920

> **来源**：R143 子任务 R143.1 — 跨会话身份隔离盲区根除（R142 M-Root-9 深化）
> **撞车 0 让路**：✅ 仅 docs/ + scripts/ 白名单
> **撞号避让**：✅ 不抢 R142 §十七 + 4 智能体穿透 §十八/§十九/§二十/§二十一

---

## §1 触发根因（fresh 证据）

R142 M-Root-9 跨会话身份隔离盲区在多次会话并发场景下反复出现：

| R{round} | 实证教训 |
|---|---|
| **R36-b** | 多会话并发扫描输出路径冲突，后写者覆盖先写者（兄弟会话公开指控） |
| **R46** | 共享工作树改动互吃 + maven 增量缓存假错 |
| **R138** | 4 智能体并行穿透 3 BCP docs-only 闭环时撞号预防映射表严守才解决 |
| **R141** | paiban-07~10 在 worktree 隔离，但主工作树仍撞 4 commit ahead of origin/main |

**元根因**：跨会话共享资源（git working tree / .harness/memory/ / docs/）无强校验门禁，靠"会话自觉 + 让路声明"易破。

## §2 设计目标

| 维度 | 设计目标 |
|---|---|
| **覆盖范围** | ruoyi-ai 主仓 + 4 智能体穿透报告并发场景 |
| **触发时机** | pre-commit + pre-push（双触发） |
| **撞车 0 边界** | 仅 docs/ + scripts/ + .harness/ 白名单 |
| **FAIL_SEED 双向触发** | `CSI_FAIL_SEED=1 → exit 1` |

## §3 落档物清单

| 文件 | 内容 | 行数 |
|---|---|---|
| `docs/ipd-系统说明/BCP-014-cross-session-isolation-设计-20260920.md` | 本设计文档 | 120 |
| `scripts/check-cross-session-isolation.sh` | 跨会话身份隔离门禁（FAIL_SEED 骨架） | 80 |

## §4 脚本骨架设计

```bash
#!/usr/bin/env bash
# scripts/check-cross-session-isolation.sh — 跨会话身份隔离门禁（骨架）
# 来源：R143 子任务 R143.1 + R142 M-Root-9
# 撞车 0 让路：✅ 仅 scripts/ + docs/ + .harness/ 白名单
# 自证能红：CSI_FAIL_SEED=1 → exit 1

set -uo pipefail

CSI_FAIL_SEED="${CSI_FAIL_SEED:-0}"
if [ "$CSI_FAIL_SEED" = "1" ]; then
  echo "[CSI] FAIL_SEED=1 → 故意注入跨会话身份隔离失败"
  echo "CSI|FAIL|seed_injected|cross-session-isolation-breach"
  exit 1
fi

# === 主逻辑（docs-only 骨架，owner 拍板后实装） ===
# 1. 检查 .git/index.lock 是否存在（兄弟会话 commit 中）
# 2. 检查 .harness/memory/ 是否被 2+ 个会话并发写
# 3. 检查 BCP-Registry.md / BCP-Closure-Log.md 是否被外部改动（mtime 5min 内）
# 4. 输出跨会话身份隔离报告
echo "[CSI] PASS（骨架模式，owner 拍板后实装主逻辑）"
exit 0
```

## §5 自证能红 + FAIL_SEED 双向触发

```bash
cd /Users/mac/Documents/ruoyi-ai
chmod +x scripts/check-cross-session-isolation.sh

# 正常态（PASS）
bash scripts/check-cross-session-isolation.sh
echo "EXIT=$?"   # 0

# 失败场景（FAIL_SEED=1 → exit 1）
CSI_FAIL_SEED=1 bash scripts/check-cross-session-isolation.sh
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

**R143.1 设计稿创建时间**：2026-09-20
**撞号避让**：✅ 不抢 R142 §十七 + 4 智能体穿透段号
**撞车 0 让路**：✅ 仅 docs/ + scripts/ 白名单
