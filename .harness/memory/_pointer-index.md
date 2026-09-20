# Pointer Index — 反脆弱指针驱动飞轮索引

> **维护**：R132 落地，2026-09-20
> **基数**：17 根指针（#119-#135）
> **驱动**：`scripts/pointer-trigger.sh` 元脚本扫描

## 索引表

| pointer_id | 标题 | 类型 | 严重度 | 触发器 | 对应脚本/hook | 自证能红 |
|---|---|---|---|---|---|---|
| #119 | pipe-trap-red | 反思层（根因） | 🔴 | `bash X.sh \| tail` | `scripts/check-pipe-trap.sh` | ✅ |
| #120 | additional-location-mandatory | 反思层（根因） | 🔴 | 后端启动未带 additional-location | `scripts/check-backend-config-location.sh`（H-1） | ✅ |
| #121 | sandbox-bg-process | 反思层（根因） | 🔴 | `is_background=false` | `scripts/check-backend-pid-survive.sh`（H-2） | ✅ |
| #122 | collision-r13-hard | 反思层（根因） | 🟡 | 同分钟多 commit 撞 hash | `scripts/check-commit-hash-uniqueness.sh`（H-3） | ✅ |
| #123 | five-must-verify-red | 反思层（根因） | 🟡 | 五必现查违反 | `scripts/check-commit-hash-filled.sh`（H-4/5/6） | ✅ |
| #124 | pipe-trap-evolve | 机制层（演化） | 🟢 | pre-commit hook 拦截 | `.git/hooks/pre-commit` | ✅ |
| #125 | additional-location-evolve | 机制层（演化） | 🟢 | 启后端 bash 函数强校验 | `bin/start-ipd-backend.sh` | ✅ |
| #126 | sandbox-evolve | 机制层（演化） | 🟢 | `is_background=true` SOP | 3 次 ps -p 存活确认 | ✅ |
| #127 | collision-evolve | 机制层（演化） | 🟢 | 三 hash 声明机制 | `scripts/check-r13-hard-3hash.sh` | ✅ |
| #128 | five-verify-evolve | 机制层（演化） | 🟢 | 硬 hook 拦截凭记忆 | 三硬 hook + Skill | ✅ |
| #129 | 三源对账批判 | 反思层 | 🔴 | 三源漂移 | `verify.sh --cross-audit-report` | ✅ |
| #130 | 反思链元根因 | 反思层 | 🔴 | 反思链深度 > 3 | `loop.sh --reflect-depth-audit` | ✅ |
| #131 | log.md 同步回填 | 文档层 | 🟡 | log.md 段号撞号 | `.claude/hooks/wt-close-pre-check.sh`（H-11） | ✅ |
| #132 | 跨仓 cd 强校验 | 机制层 | 🔴 | cwd 漂移 | `.claude/hooks/pre-cd-cross-repo-check.sh`（H-12） | ✅ |
| #133 | BCP 飞轮齿位 | 飞轮层 | 🟢 | 飞轮扫描 | `.harness/evolve/flywheel.sh`（H-13） | ✅ |
| #134 | 自证能红缺失假绿 | 验证层 | 🔴 | 验证脚本无反证 | `gate.sh --red-self-test` | ✅ |
| #135 | R 报告行数表述差 | 表述层 | 🟡 | 数字漂移 > 5% | `docs-lint.sh numeric-claim-audit` | ✅ |

## 按类型聚合

| 类型 | 数量 | pointer_id |
|---|---|---|
| 反思层 | 7 | #119 #120 #121 #122 #123 #129 #130 |
| 文档层 | 1 | #131 |
| 机制层 | 6 | #124 #125 #126 #127 #128 #132 |
| 飞轮层 | 1 | #133 |
| 验证层 | 1 | #134 |
| 表述层 | 1 | #135 |

## 按严重度聚合

| 严重度 | 数量 | pointer_id |
|---|---|---|
| 🔴 | 7 | #119 #120 #121 #129 #130 #132 #134 |
| 🟡 | 4 | #122 #123 #131 #135 |
| 🟢 | 6 | #124 #125 #126 #127 #128 #133 |

## 飞轮调度使用

```bash
# 扫所有 17 根指针 → 命中触发对应 BCP
bash scripts/pointer-trigger.sh

# 按类型过滤（仅反思层）
bash scripts/pointer-trigger.sh --filter-type 反思层

# 按严重度过滤（仅 🔴）
bash scripts/pointer-trigger.sh --filter-severity 🔴
```
