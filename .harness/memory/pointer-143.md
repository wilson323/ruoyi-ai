# Pointer-143 — 跨会话异常根因反思 + 根除最佳实践触发器

> **触发条件**：~~每 R 轮必跑 R143 4 脚本~~ **已失效（2026-09-25 R219b）**：4 脚本为 `echo PASS; exit 0` 零逻辑骨架，每轮必跑等于每轮产出 4 条“治理检查已过”假绿，已删除（单 commit 可 revert，待实装清单迁至 log.md R219b 段）
> **撞车 0 让路**：✅ 仅 .harness/memory/ + scripts/ + docs/ 白名单
> **撞号避让**：✅ 4 脚本骨架 docs-only 设计，不实装主逻辑（等 owner 拍板）

---

## §1 触发链（已停用，勿跑）

```bash
# 2026-09-25 R219b：下列 4 脚本已删除（零逻辑恒 PASS），本段仅作历史留存
# 跑它会直接 `No such file or directory`；如需重建按 log.md 「r219b-skeleton-batch1」段的待实装清单
# CSI_FAIL_SEED=1   bash scripts/check-cross-session-isolation.sh
# CDRIFT_FAIL_SEED=1 bash scripts/check-collision-drift.sh
# PDL_FAIL_SEED=1   bash scripts/check-paiban-deadline.sh
# FBS_FAIL_SEED=1   bash scripts/check-five-bores-stagnation.sh
```

## §2 撞号预防映射表

| 段号 | 占用者 | R143 决策 |
|---|---|---|
| §十七 | R142 | 不抢 |
| §十八~§二十一 | R141 4 智能体穿透 | 不抢 |
| **§二十二** | R143 | 独占 |

## §3 撞车 0 让路 8 红线（严守）

1. ✅ 仅 docs/ipd-系统说明/ + scripts/ + .harness/memory/ + .claude/hooks/(docs 设计) 白名单
2. ✅ 不动 Java 源码
3. ✅ 不动 SQL / DDL / Flyway
4. ✅ 不抢端口
5. ✅ 不杀 PID
6. ✅ 不动兄弟会话 modified
7. ✅ Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&`
8. ✅ A 智能体独占段号（§二十二 + §三.3.26 + §四 R143 度量）

## §4 R143 启动触发条件

1. 用户明确指令"系统性梳理分析全局项目全部会话记录..."
2. 不抢 R142 §十七 + 4 智能体穿透 §十八/§十九/§二十/§二十一
3. 仅 docs/ + scripts/ + .harness/ 白名单
4. 等用户审阅设计稿 + 拍板 → implementation

## §5 后续 R 轮必跑

- ~~R144：4 脚本主逻辑实装~~ **未执行（挂起 5 天）→ 2026-09-25 R219b 已删除骨架本体**，实装需求降为 owner 待拍项，清单见 log.md marker r219b-skeleton-batch1
- R145+：持续应用保障（本指针 §1 触发链已停用，仅 §3 撞车红线仍有效）
