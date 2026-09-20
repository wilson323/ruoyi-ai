# Pointer-143 — 跨会话异常根因反思 + 根除最佳实践触发器

> **触发条件**：每 R 轮必跑 R143 4 脚本 + 撞号自检
> **撞车 0 让路**：✅ 仅 .harness/memory/ + scripts/ + docs/ 白名单
> **撞号避让**：✅ 4 脚本骨架 docs-only 设计，不实装主逻辑（等 owner 拍板）

---

## §1 触发链

```bash
# 每 R 轮收口必跑
cd /Users/mac/Documents/ruoyi-ai

# 1. 跨会话身份隔离
CSI_FAIL_SEED=1 bash scripts/check-cross-session-isolation.sh; echo "CSI EXIT=$?"

# 2. 撞车 0 让路边界
CDRIFT_FAIL_SEED=1 bash scripts/check-collision-drift.sh; echo "CDRIFT EXIT=$?"

# 3. paiban 拍板契约
PDL_FAIL_SEED=1 bash scripts/check-paiban-deadline.sh; echo "PDL EXIT=$?"

# 4. 5 钻覆盖率
FBS_FAIL_SEED=1 bash scripts/check-five-bores-stagnation.sh; echo "FBS EXIT=$?"
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

- R144：4 脚本主逻辑实装 + 跨仓穿透 + 三源对账
- R145+：持续应用保障（每 R 轮必跑本指针触发链）
