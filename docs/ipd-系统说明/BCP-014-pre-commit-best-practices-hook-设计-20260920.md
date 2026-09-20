# BCP-014-pre-commit-best-practices-hook-设计-20260920

> **BCP 编号**：BCP-014（飞轮首批 BCP 第 14 项 — 最佳实践系统性梳理）
> **R 段**：R141
> **A 智能体独占**（agency-harness）
> **撞车 0 让路**：✅ .claude/hooks/ **docs 设计** 白名单（**未实装 hook 实质**）
> **拍板位**：**C 类 14d owner 必拍 #1 / #4 / #6**（BP-013/014/015 三件套设计文档）

---

## §一 设计意图

将 R141 落地的 5 个最佳实践门禁脚本（check-best-practices-coverage + 4 专项）接入 pre-commit hook、GitHub Action CI、跨仓三仓共享三个层级的自动化机制。

**撞车 0 让路严守**：本设计文档**仅落档 docs 设计**，**不实装**任何 hook 实质 / CI workflow / 跨仓同步代码。所有实质实装需 owner 拍板。

---

## §二 BP-013 pre-commit H5 hook 实质实装（撞车 0 边界外）

### 2.1 设计草案（待 owner 拍板 #1）

**意图**：在 `pre-commit` hook 中触发 `scripts/check-best-practices-coverage.sh`，作为「提交前最后一道最佳实践门禁」。

```bash
# .claude/hooks/pre-commit-best-practices-check.sh（草案）
#!/usr/bin/env bash
# 最佳实践应用覆盖度 pre-commit 检查（BP-013 — owner-blocked #1）
#
# 触发时机：pre-commit
# 检查内容：BP-001~015 覆盖度 ≥ 80% PASS
# 自证能红：HOOK_BP_FAIL_SEED=1 → exit 1
#
# 来源：R141（BCP-014）
# 撞车 0 让路：docs-only 设计，**未实装 hook 实质**（等 owner 拍板 #1）
# 同 BCP-010 H5 矩阵设计：https://docs/ipd-系统说明/BCP-010-H5-pre-commit-smoke-test-设计-20260920.md

set -o pipefail

REPO="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0

# 1. 自证能红
if [[ "${HOOK_BP_FAIL_SEED:-0}" == "1" ]]; then
  echo "[BP-HOOK] HOOK_BP_FAIL_SEED=1 → 故意注入 hook 失败"
  exit 1
fi

# 2. 触发主门禁
if ! bash "$REPO/scripts/check-best-practices-coverage.sh"; then
  echo "[BP-HOOK] 主门禁 FAIL，请修复后重试"
  exit 1
fi

exit 0
```

### 2.2 owner 拍板请求（撞车 0 边界外）

详见 `BCP-014-frontend-code-review-适配设计-20260920.md` §八.1 BP-013 owner 拍板 #1。

**撞车 0 让路**：本设计文档**不实装** `.claude/hooks/pre-commit-best-practices-check.sh` 实质文件，仅落档设计。

### 2.3 同 BCP-010 H5 矩阵的关系

- BCP-010 H5：pre-commit smoke test（覆盖域：build smoke）
- **BCP-014 BP-013**：pre-commit best-practices-check（覆盖域：最佳实践应用）

**撞号预防**：两份设计文档互不撞号（A 智能体独占 R141 段号，Q 智能体独占 R138 §三.3.18）。

---

## §三 BP-014 GitHub Action best-practices.yml（撞车 0 边界外）

### 3.1 设计草案（待 owner 拍板 #4）

**意图**：在 `.github/workflows/` 下创建 `best-practices-check.yml`，PR 触发时自动跑 5 个最佳实践门禁脚本。

```yaml
# .github/workflows/best-practices-check.yml（草案）
name: best-practices-check
on:
  pull_request:
    branches: [main]
    paths:
      - 'ruoyi-*/**/*.java'
      - '../ruoyi-ipd-web/apps/web-antd/**/*.{ts,tsx,vue}'

jobs:
  check:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4

      - name: 主门禁（最佳实践应用覆盖度）
        run: bash scripts/check-best-practices-coverage.sh

      - name: 命名规范
        run: bash scripts/check-naming-convention.sh

      - name: 注释与代码一致
        run: bash scripts/check-doc-code-sync.sh

      - name: 内存泄漏模式
        run: bash scripts/check-memory-leak-pattern.sh

      - name: 可访问性 a11y
        run: bash scripts/check-a11y-basics.sh

      # 自证能红 + FAIL_SEED 双向触发验证（仅在调试模式下）
      - name: 自证能红 PASS 验证（仅 README 触发）
        if: contains(github.event.pull_request.body, '[bp-fail-seed-test]')
        run: |
          BP_FAIL_SEED=1 bash scripts/check-best-practices-coverage.sh
          # 期望 EXIT=1
```

### 3.2 owner 拍板请求（撞车 0 边界外）

详见 `BCP-014-frontend-code-review-适配设计-20260920.md` §八.2 BP-014 owner 拍板 #4。

**撞车 0 让路**：本设计文档**不实装** `.github/workflows/best-practices-check.yml` 实质文件，仅落档设计。

---

## §四 BP-015 跨仓 pre-commit 三仓共享（撞车 0 边界外）

### 4.1 设计草案（待 owner 拍板 #6）

**意图**：将 `scripts/check-best-practices-coverage.sh` + 4 专项脚本接入 ruoyi-ai + ruoyi-ipd-web + ZK-IPD 三仓的 pre-commit hook。

```bash
# 跨仓共享方案（草案）

# 主仓 ruoyi-ai/scripts/ 是脚本单一事实源（SSOT）
# /Users/mac/Documents/ruoyi-ai/scripts/check-best-practices-coverage.sh
# /Users/mac/Documents/ruoyi-ai/scripts/check-naming-convention.sh
# /Users/mac/Documents/ruoyi-ai/scripts/check-doc-code-sync.sh
# /Users/mac/Documents/ruoyi-ai/scripts/check-memory-leak-pattern.sh
# /Users/mac/Documents/ruoyi-ai/scripts/check-a11y-basics.sh

# 三仓 pre-commit hook 引用同一脚本（草案）
# ruoyi-ai/.claude/hooks/pre-commit → exec bash /Users/mac/Documents/ruoyi-ai/scripts/check-best-practices-coverage.sh
# ruoyi-ipd-web/.claude/hooks/pre-commit → exec bash /Users/mac/Documents/ruoyi-ai/scripts/check-best-practices-coverage.sh
# ZK-IPD/.claude/hooks/pre-commit → exec bash /Users/mac/Documents/ruoyi-ai/scripts/check-best-practices-coverage.sh
```

### 4.2 撞车 0 边界外问题

跨仓 commit 操作触发的最大破坏风险（参考 BCP-009 跨仓最大破坏 4 类场景）：
- A. 兄弟会话 in-flight commit 被 reset 孤儿化
- B. 三仓撞号同步失败导致扇出回滚
- C. 跨仓脚本权限不一致
- E. 撞号预防映射表跨仓失效

**撞车 0 让路**：本设计文档**不实装**跨仓同步逻辑，仅落档设计 + owner 拍板请求。

### 4.3 owner 拍板请求（撞车 0 边界外）

详见 `BCP-014-frontend-code-review-适配设计-20260920.md` §八.3 BP-015 owner 拍板 #6。

---

## §五 自证能红 + FAIL_SEED 双向触发（已落地部分）

| 脚本 | FAIL_SEED 环境变量 | 触发行为 | 状态 |
|---|---|---|---|
| `scripts/check-best-practices-coverage.sh` | `BP_FAIL_SEED=1` | exit 1 | ✅ R141 已落地 |
| `scripts/check-naming-convention.sh` | `NAMING_FAIL_SEED=1` | exit 1 | ✅ R141 已落地 |
| `scripts/check-doc-code-sync.sh` | `DOCSYNC_FAIL_SEED=1` | exit 1 | ✅ R141 已落地 |
| `scripts/check-memory-leak-pattern.sh` | `LEAK_FAIL_SEED=1` | exit 1 | ✅ R141 已落地 |
| `scripts/check-a11y-basics.sh` | `A11Y_FAIL_SEED=1` | exit 1 | ✅ R141 已落地 |
| `BCP-014 BP-013 pre-commit hook` | `HOOK_BP_FAIL_SEED=1` | exit 1 | ⏳ docs-only 设计，**未实装** |

---

## §六 落地路线图

| 阶段 | 产出 | 撞车 0 | 拍板位 |
|---|---|---|---|
| R141（当前） | 3 个 docs-only 设计文档（本文件 + BP-013/014/015） | ✅ docs-only | **C 14d owner 必拍** |
| 等 owner 拍板 #1 | `.claude/hooks/pre-commit-best-practices-check.sh` 实装 | ⚠️ .claude/hooks/ hook 实质 | **C 14d owner 必拍** |
| 等 owner 拍板 #4 | `.github/workflows/best-practices-check.yml` 实装 | ⚠️ CI workflow | **C 14d owner 必拍** |
| 等 owner 拍板 #6 | 跨仓 pre-commit 三仓共享实装 | ⚠️ 三仓白名单 | **C 14d owner 必拍** |

**撞车 0 让路严守**：所有实质实装均**需 owner 拍板后**，由对应派单的智能体（owner 派单）落地，**A 智能体 R141 仅落 docs 设计**。

---

## §七 撞车 0 + 撞号预防自检

### 7.1 撞车 0 让路

- ✅ 仅 `docs/ipd-系统说明/` + `scripts/` + `.claude/hooks/`（docs 设计）+ `.harness/memory/` 强推进白名单
- ✅ **不实装** `.claude/hooks/pre-commit-best-practices-check.sh` 实质
- ✅ **不实装** `.github/workflows/best-practices-check.yml` 实质
- ✅ **不实装** 三仓 pre-commit 共享
- ✅ 不动 Java 源码 / SQL / 端口 / PID / 兄弟会话 modified
- ✅ Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&`

### 7.2 撞号预防映射表

- ✅ A 智能体独占段号：BCP-Registry §六/§十六/§一 BCP-014 + BCP-Closure-Log §三.3.20/§四
- ✅ 本设计文档不写 §三.3.x / §十一 / §十二 / §十三
- ✅ 同 BCP-010 H5 互不撞号（A 智能体 R141 vs Q 智能体 R138 §三.3.18）

---

## §八 关联文档

- `BCP-014-frontend-code-review-适配设计-20260920.md`（BP-001~007 + BP-013 设计）
- `BCP-014-browser-business-testing-适配设计-20260920.md`（BP-008~012 + BP-015 设计）
- `最佳实践应用登记位-20260920.md`（BP-001~015 条目清单）
- `R141-最佳实践系统性梳理+完整充分应用到本项目开发体系-20260920.md`（治理报告）
- `scripts/check-best-practices-coverage.sh`（主门禁，**已实装**）
- `scripts/check-{naming-convention,doc-code-sync,memory-leak-pattern,a11y-basics}.sh`（4 专项，**已实装**）
- `docs/ipd-系统说明/BCP-010-H5-pre-commit-smoke-test-设计-20260920.md`（同 BCP-010 H5 矩阵设计，Q 智能体 R138）

---

## §九 Owner 拍板请求汇总

| BP | owner 拍板位 | 决策项 | 选项 |
|---|---|---|---|
| **BP-013** | **#1** 启 IPD 后端真活 E2E | 是否授权启用 `.claude/hooks/pre-commit-best-practices-check.sh` 实质？ | A 授权 / B 不授权 / C 部分授权（仅 docs-only 检查） |
| **BP-014** | **#4** DTO 后缀收口 + GitHub Action CI | 是否授权启用 `.github/workflows/best-practices-check.yml` 实质？ | A 授权 / B 不授权 / C 部分授权（仅 dry-run） |
| **BP-015** | **#6** 跨仓 commit 并行授权 | 是否授权跨仓 pre-commit 三仓共享实装？ | A 授权 / B 不授权 / C 部分授权（仅主仓 + 前端仓） |

**撞车 0 让路严守**：本设计文档**不实装**任何 owner 拍板项对应的实质代码，**等 owner 拍板后**由 owner 派单智能体落地。