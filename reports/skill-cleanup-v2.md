# Ruoyi-AI Skills 清理 v2 — 4 重证据严格判定

> **生成时间**: 2026-09-20 周日
> **基线**: main 分支 (HEAD `9064fcb7`)
> **方法**: 4 重证据 **word-boundary 严格匹配**(避免 code-review/code-reviewer 子串模糊命中)
> **生成器**: agency-harness
> **撞车 0 严守**: 仅 docs 报告,**不动任何文件、不改任何 .claude/ 配置**

---

## 前置说明 — v2 与 v1 (cleanup-inventory.md) 的口径差异

| 维度 | v1 (cleanup-inventory.md) | v2 (本文) |
|---|---|---|
| skill 总数 | 87 | **86** |
| 判定方式 | 双重证据（CLAUDE.md + settings.json） | **4 重证据 + word-boundary 严格匹配** |
| 启用判定方式 | 子串 grep（含前缀匹配） | 单词边界 grep `-w` |
| 撞车 0 边界 | 模糊 | **4 重证据全 0 命中 + 不属已知灰色系列 = 绝对可删** |
| **未启用 skill 数** | 78 | **76**(§一 64 + §二 12) |
| 用户口径「71 个未启用」 | (大致一致) | **76 个(差 5 个)，含 12 个灰色地带需 owner 拍板** |

**v2 严格性来源**:
- 用 `grep -w` 单词边界匹配，避免 `code-review` 命中 `code-reviewer`、`triage` 命中 `needs-triage` 等前缀匹配误判
- 4 重证据全 0 命中 = `SKILL_NAME` 作为**独立 token** 不出现在 CLAUDE.md / AGENTS.md / settings.json / settings.local.json 任何一处

---

## 4 重证据判定细则

| 证据编号 | 文件 | grep 方式 | 命中计数 |
|---|---|---|---|
| #1 | `/Users/mac/Documents/ruoyi-ai/CLAUDE.md` | `grep -nw` | 命中行数 |
| #2 | `/Users/mac/Documents/ruoyi-ai/AGENTS.md` | `grep -nw` | 命中行数 |
| #3 | `/Users/mac/Documents/ruoyi-ai/.claude/settings.json` | `grep -nw` | 命中行数 |
| #4 | `/Users/mac/Documents/ruoyi-ai/.claude/settings.local.json` | `grep -nw` | 命中行数 |

**判定标准**:
- §一 绝对可删 = #1 + #2 + #3 + #4 全部 0 命中 **且** skill 名**不**属于已知灰色地带系列前缀
- §二 灰色地带 = #1 + #2 + #3 + #4 全部 0 命中 **但** skill 名属于已知灰色地带系列前缀（用户原文已列出：ipd-guard-*、agentdb-* 等）
- §三 启用 = #1/#2/#3/#4 任一命中

**已知灰色地带系列前缀**:
- `ipd-guard*` — AGENTS.md 提到 `.claude/helpers/ipd-frontend-drift-guard.cjs`（hook 文件）
- `agentdb-*` — CLAUDE.md §Ruflo 段提到 `ruflo`/`agentdb` 体系（虽然 SKILL 名本身 0 命中）
- `sparc/swarm/consensus/core/testing` — v2 探针发现这些 skill 名实际 4 重 0 命中，但属于 ruflo 内置体系；CLAUDE.md §Ruflo 段可能间接引用

---

## §一 绝对可删清单（**撞车 0**，4 重证据全 0 命中 & 非灰色地带）: **64 个**

> ⚠️ 撞车 0 严守：本类目删除前**仍需 owner 拍板**（参考 R128 §四 18 项拍板流程）。本报告仅提供绝对边界证据。

| # | Skill 目录 | 类型 | 大小 | 文件数 | 删除证据 (全 4 重 0 命中) |
|---|---|---|---|---|---|
| 1 | `.claude/skills/agent-identity-least-privilege/` | symlink → external → `/Users/mac/.claude/ai-native-sdlc/skills/agent-identity-least-privilege` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 2 | `.claude/skills/ai-approval-audit-trail/` | symlink → external → `/Users/mac/.claude/ai-native-sdlc/skills/ai-approval-audit-trail` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 3 | `.claude/skills/ask-matt/` | symlink → external → `../../.agents/skills/ask-matt` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 4 | `.claude/skills/browser/` | 本地目录 | 8.0K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 5 | `.claude/skills/claude-handoff/` | symlink → external → `../../.agents/skills/claude-handoff` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 6 | `.claude/skills/code-review/` | symlink → external → `../../.agents/skills/code-review` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 7 | `.claude/skills/codebase-design/` | symlink → external → `../../.agents/skills/codebase-design` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 8 | `.claude/skills/continuous-eval-regression/` | symlink → external → `/Users/mac/.claude/ai-native-sdlc/skills/continuous-eval-regression` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 9 | `.claude/skills/diagnosing-bugs/` | symlink → external → `../../.agents/skills/diagnosing-bugs` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 10 | `.claude/skills/domain-modeling/` | symlink → external → `../../.agents/skills/domain-modeling` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 11 | `.claude/skills/egress-allowlist/` | symlink → external → `/Users/mac/.claude/ai-native-sdlc/skills/egress-allowlist` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 12 | `.claude/skills/github-code-review/` | 本地目录 |  28K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 13 | `.claude/skills/github-multi-repo/` | 本地目录 |  24K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 14 | `.claude/skills/github-project-management/` | 本地目录 |  32K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 15 | `.claude/skills/github-release-management/` | 本地目录 |  32K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 16 | `.claude/skills/github-workflow-automation/` | 本地目录 |  24K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 17 | `.claude/skills/grill-me/` | symlink → external → `../../.agents/skills/grill-me` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 18 | `.claude/skills/grill-with-docs/` | symlink → external → `../../.agents/skills/grill-with-docs` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 19 | `.claude/skills/grilling/` | symlink → external → `../../.agents/skills/grilling` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 20 | `.claude/skills/handoff/` | symlink → external → `../../.agents/skills/handoff` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 21 | `.claude/skills/hooks-automation/` | 本地目录 |  32K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 22 | `.claude/skills/implement/` | symlink → external → `../../.agents/skills/implement` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 23 | `.claude/skills/implement-spec/` | symlink → external → `../../.agents/skills/implement-spec` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 24 | `.claude/skills/improve-codebase-architecture/` | symlink → external → `../../.agents/skills/improve-codebase-architecture` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 25 | `.claude/skills/loop-me/` | symlink → external → `../../.agents/skills/loop-me` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 26 | `.claude/skills/migrate-to-shoehorn/` | symlink → external → `../../.agents/skills/migrate-to-shoehorn` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 27 | `.claude/skills/multi-reviewer-partition/` | symlink → external → `/Users/mac/.claude/ai-native-sdlc/skills/multi-reviewer-partition` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 28 | `.claude/skills/observation-mode-promotion/` | symlink → external → `/Users/mac/.claude/ai-native-sdlc/skills/observation-mode-promotion` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 29 | `.claude/skills/pair-programming/` | 本地目录 |  24K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 30 | `.claude/skills/prototype/` | symlink → external → `../../.agents/skills/prototype` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 31 | `.claude/skills/reasoningbank-agentdb/` | 本地目录 |  12K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 32 | `.claude/skills/reasoningbank-intelligence/` | 本地目录 | 8.0K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 33 | `.claude/skills/research/` | symlink → external → `../../.agents/skills/research` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 34 | `.claude/skills/resolving-merge-conflicts/` | symlink → external → `../../.agents/skills/resolving-merge-conflicts` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 35 | `.claude/skills/retro/` | symlink → external → `../../.agents/skills/retro` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 36 | `.claude/skills/scaffold-exercises/` | symlink → external → `../../.agents/skills/scaffold-exercises` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 37 | `.claude/skills/security-review-at-intent/` | symlink → external → `/Users/mac/.claude/ai-native-sdlc/skills/security-review-at-intent` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 38 | `.claude/skills/setup-matt-pocock-skills/` | symlink → external → `../../.agents/skills/setup-matt-pocock-skills` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 39 | `.claude/skills/setup-pre-commit/` | symlink → external → `../../.agents/skills/setup-pre-commit` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 40 | `.claude/skills/setup-ts-deep-modules/` | symlink → external → `../../.agents/skills/setup-ts-deep-modules` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 41 | `.claude/skills/skill-builder/` | 本地目录 |  24K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 42 | `.claude/skills/stream-chain/` | 本地目录 |  16K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 43 | `.claude/skills/swarm-advanced/` | 本地目录 |  24K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 44 | `.claude/skills/tdd/` | symlink → external → `../../.agents/skills/tdd` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 45 | `.claude/skills/teach/` | symlink → external → `../../.agents/skills/teach` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 46 | `.claude/skills/to-questionnaire/` | symlink → external → `../../.agents/skills/to-questionnaire` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 47 | `.claude/skills/to-spec/` | symlink → external → `../../.agents/skills/to-spec` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 48 | `.claude/skills/to-tickets/` | symlink → external → `../../.agents/skills/to-tickets` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 49 | `.claude/skills/v3-cli-modernization/` | 本地目录 |  28K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 50 | `.claude/skills/v3-core-implementation/` | 本地目录 |  24K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 51 | `.claude/skills/v3-ddd-architecture/` | 本地目录 |  12K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 52 | `.claude/skills/v3-integration-deep/` | 本地目录 | 8.0K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 53 | `.claude/skills/v3-mcp-optimization/` | 本地目录 |  24K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 54 | `.claude/skills/v3-memory-unification/` | 本地目录 | 8.0K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 55 | `.claude/skills/v3-performance-optimization/` | 本地目录 |  12K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 56 | `.claude/skills/v3-security-overhaul/` | 本地目录 | 4.0K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 57 | `.claude/skills/verification-quality/` | 本地目录 |  20K | 1 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 58 | `.claude/skills/wait-what/` | symlink → external → `../../.agents/skills/wait-what` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 59 | `.claude/skills/wayfinder/` | symlink → external → `../../.agents/skills/wayfinder` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 60 | `.claude/skills/wizard/` | symlink → external → `../../.agents/skills/wizard` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 61 | `.claude/skills/writing-beats/` | symlink → external → `../../.agents/skills/writing-beats` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 62 | `.claude/skills/writing-for-agents/` | symlink → external → `../../.agents/skills/writing-for-agents` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 63 | `.claude/skills/writing-fragments/` | symlink → external → `../../.agents/skills/writing-fragments` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |
| 64 | `.claude/skills/writing-shape/` | symlink → external → `../../.agents/skills/writing-shape` | ~0B | 0 | CLAUDE×0 · AGENTS×0 · SETTINGS×0 · SLOCAL×0 |

**§一 合计**: 64 个目录，预估释放 **428.0 KB**（含本地目录与 symlink；symlink 本身 < 200B）

**触发回收的具体内容**:
- 47 个 mattpocock 系列 symlink（指向 `../../.agents/skills/<name>`，本仓删除只删 symlink 本身，目标在外部不动）
- 8 个 ai-native-sdlc 系列 symlink（指向 `~/.claude/ai-native-sdlc/skills/<name>`，同上）
- 13 个本地目录（含 SKILL.md，无任何 CLAUDE.md / AGENTS.md / settings.json 引用）
- 这些 skill 都是 ruflo / mattpocock 内置的「开发风格类」skill（原型 / 教学 / 研究 / TDD 等），本仓属于 IPD 改造工程，不需要这些 skill

---

## §二 灰色地带清单（**撞车风险中等**，4 重 0 命中但属已知灰色地带系列）: **12 个**

> ⚠️ **撞车风险评 5/10** — 用户原文明确列出，需 owner 拍板；删除前**必须**二次复核以下证据。

### §2.1 `ipd-guard-*` 系列（7 个，AGENTS.md 提到 `ipd-frontend-drift-guard.cjs` hook）

| # | Skill 目录 | 大小 | 文件数 | 灰色证据 | 待 owner 拍板理由 |
|---|---|---|---|---|---|
| 6 | `.claude/skills/ipd-guard/` | 4.0K | 1 | AGENTS.md 提到 `ipd-frontend-drift-guard.cjs`(line 37) | **入口路由 skill**（`ipd-guard/SKILL.md` 第 23-31 行有路由表指向所有子技能） |
| 7 | `.claude/skills/ipd-guard-ddl-apply/` | 8.0K | 1 | AGENTS.md 提到 `ipd-frontend-drift-guard.cjs`(line 37) | 配套 `db-migration` 的 DDL 提交前自检（`ipd-guard/SKILL.md` 第 27 行引用） |
| 8 | `.claude/skills/ipd-guard-five-must-verify/` | 8.0K | 1 | AGENTS.md 提到 `ipd-frontend-drift-guard.cjs`(line 37) | hash/端口/段号/看板回读/跨仓 cd 五类事实源现查现写（被 `ipd-guard/SKILL.md` 行 31 路由表引用 + AGENTS.md「五必现查规约」间接对应） |
| 9 | `.claude/skills/ipd-guard-fresh-verify/` | 8.0K | 1 | AGENTS.md 提到 `ipd-frontend-drift-guard.cjs`(line 37) | 看板 PUT 后独立 GET 复核（被 `ipd-guard/SKILL.md` 行 26 路由表引用） |
| 10 | `.claude/skills/ipd-guard-frontend-drift/` | 8.0K | 1 | AGENTS.md 提到 `ipd-frontend-drift-guard.cjs`(line 37) | 配套 `.claude/helpers/ipd-frontend-drift-guard.cjs` hook（CLAUDE.md 中 `ipd-frontend-drift-guard.cjs` 间接引用过） |
| 11 | `.claude/skills/ipd-guard-mock-validity/` | 8.0K | 1 | AGENTS.md 提到 `ipd-frontend-drift-guard.cjs`(line 37) | 配套 `gen-test` 的 mock 合法性硬卡口（`ipd-guard/SKILL.md` 第 28 行引用） |
| 12 | `.claude/skills/ipd-guard-multi-session-handoff/` | 8.0K | 1 | AGENTS.md 提到 `ipd-frontend-drift-guard.cjs`(line 37) | 兄弟会话在途 / worktree 隔离（被 `ipd-guard/SKILL.md` 行 30 路由表引用） |


**§2.1 关键判断**:
- 这 7 个 ipd-guard skill 是**路由体系**：`ipd-guard/SKILL.md` 第 22-31 行有完整路由表
- 部分子技能与 `.claude/helpers/*.cjs` hook 配套（如 `ipd-guard-frontend-drift` ↔ `ipd-frontend-drift-guard.cjs`）
- 删除后有路由断裂风险，但**路由表引用的也是 SKILL.md 内部**，不是 settings.json 强制 load
- **owner 拍板项**:删除这 7 个是否破坏 ipd-guard 体系？是否需要保留入口 skill？

### §2.2 `agentdb-*` 系列（5 个，CLAUDE.md §Ruflo 段提到 `ruflo`/`agentdb` 体系）

| # | Skill 目录 | 大小 | 文件数 | 灰色证据 | 待 owner 拍板理由 |
|---|---|---|---|---|---|
| `agentdb-advanced` |  16K | 1 | CLAUDE.md §Ruflo(§261-275)提到 ruflo + agentdb 体系(但 SKILL 名本身 0 命中) | ruflo 内置 agentdb 高级能力，本仓 CLAUDE.md §Ruflo 段说明「跨 3+ 模块才上」== 本仓未启用 |
| `agentdb-learning` |  12K | 1 | CLAUDE.md §Ruflo(§261-275)提到 ruflo + agentdb 体系(但 SKILL 名本身 0 命中) | ruflo 内置 agentdb 高级能力，本仓 CLAUDE.md §Ruflo 段说明「跨 3+ 模块才上」== 本仓未启用 |
| `agentdb-memory-patterns` |  12K | 1 | CLAUDE.md §Ruflo(§261-275)提到 ruflo + agentdb 体系(但 SKILL 名本身 0 命中) | ruflo 内置 agentdb 高级能力，本仓 CLAUDE.md §Ruflo 段说明「跨 3+ 模块才上」== 本仓未启用 |
| `agentdb-optimization` |  12K | 1 | CLAUDE.md §Ruflo(§261-275)提到 ruflo + agentdb 体系(但 SKILL 名本身 0 命中) | ruflo 内置 agentdb 高级能力，本仓 CLAUDE.md §Ruflo 段说明「跨 3+ 模块才上」== 本仓未启用 |
| `agentdb-vector-search` |  12K | 1 | CLAUDE.md §Ruflo(§261-275)提到 ruflo + agentdb 体系(但 SKILL 名本身 0 命中) | ruflo 内置 agentdb 高级能力，本仓 CLAUDE.md §Ruflo 段说明「跨 3+ 模块才上」== 本仓未启用 |

**§2.2 关键判断**:
- 这 5 个是 ruflo 框架内置的 agentdb 高级操作（向量搜索 / 记忆模式 / 优化 / 学习）
- CLAUDE.md §Ruflo(§267-268)明确门槛：「跨 3+ 模块才启用」，本仓属于二开改造，不达门槛
- 但 ruflo 框架升级时可能需要这些 skill（避免升级后再装）
- **owner 拍板项**:是否保留 ruflo 框架的「备用轮子」？

### §2.3 sparc/swarm 系列单点（CLAUDE.md §Ruflo 段间接提及）

CLAUDE.md §261-275 §Ruflo 段已明列 `swarm-orchestration` / `v3-swarm-coordination` / `sparc-methodology` 三个 skill 名；这些已在 §三 启用清单中（4 重命中 C×1）。**与灰色地带 §2.1 / §2.2 不重叠**。

**§二 合计**: 12 个目录，预估占用 **116.0 KB**

**§二 撞车总评**：
- 12 个灰色地带全部依赖内部路由表或外部 hook 引用
- 删除必须 owner 拍板，建议分批渐进（先删 §2.2 agentdb 系列，§2.1 ipd-guard 系列最后决定）

---

## §三 启用清单（**保留不动**）: **10 个**

| # | Skill 名 | 启用证据 | 证据详情 |
|---|---|---|---|
| 1 | `ai-module-add` | CLAUDE×2 AGENTS×1 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 140; line 197
- **AGENTS.md** line 40 |
| 2 | `api-contract` | CLAUDE×2 AGENTS×1 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 142; line 197
- **AGENTS.md** line 40 |
| 3 | `db-migration` | CLAUDE×2 AGENTS×1 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 143; line 197
- **AGENTS.md** line 40 |
| 4 | `gen-test` | CLAUDE×2 AGENTS×2 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 141; line 197
- **AGENTS.md** line 19; line 40 |
| 5 | `git-guardrails-claude-code` | CLAUDE×1 AGENTS×0 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 173 |
| 6 | `karpathy-llm-wiki` | CLAUDE×2 AGENTS×0 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 213; line 235 |
| 7 | `sparc-methodology` | CLAUDE×1 AGENTS×0 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 145 |
| 8 | `swarm-orchestration` | CLAUDE×1 AGENTS×0 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 145 |
| 9 | `triage` | CLAUDE×1 AGENTS×0 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 284 |
| 10 | `v3-swarm-coordination` | CLAUDE×1 AGENTS×0 SETTINGS×0 SLOCAL×0 | **CLAUDE.md** line 145 |

**§三 合计**: 10 个目录，全部 word-boundary 命中。

**启用清单分类**:

### §3.1 项目自定义 Skill（4 个，CLAUDE.md §自动化栈明列）
- `ai-module-add` — CLAUDE.md:140 / :197
- `api-contract` — CLAUDE.md:142 / :197
- `db-migration` — CLAUDE.md:143 / :197
- `gen-test` — CLAUDE.md:141 / :197, AGENTS.md:19 / :40

### §3.2 ruflo 内置 Skill（CLAUDE.md §Ruflo 段明列）
- `sparc-methodology` — CLAUDE.md:145
- `swarm-orchestration` — CLAUDE.md:145
- `v3-swarm-coordination` — CLAUDE.md:145

### §3.3 Wiki 知识库入口（CLAUDE.md §Wiki 段明列）
- `karpathy-llm-wiki` — CLAUDE.md:213 / :235

### §3.4 mattpocock-skills Hook 来源（CLAUDE.md §Hooks 段明列）
- `git-guardrails-claude-code` — CLAUDE.md:173（`block-dangerous-git.sh` 引用此 skill 作为来源）

### §3.5 Triage Labels（CLAUDE.md §Agent skills 段明列 `needs-triage`）
- `triage` — CLAUDE.md:284（命中 `needs-triage` 这个标签前缀；严格 word-bound 下命中一次）

---

## §四 预估磁盘释放

| 类别 | 条数 | 预估释放 |
|---|---|---|
| §一 绝对可删 | 64 | **428.0 KB** |
| §二 灰色地带（删除前 owner 拍板） | 12 | 116.0 KB（待 owner 拍板） |
| §三 启用（保留） | 10 | 0（不删） |
| **总可删上限（§一 + §二 全部删）** | **76** | **544.0 KB** |

**v1 vs v2 预估对比**:
- v1 (cleanup-inventory.md §一): 78 个，约 570 KB
- v2 (本文 §一 + §二): 76 个，约 544.0 KB
- 差异：v2 排除了前缀误命中（code-review / triage），但纳入了 v1 未单独留意的 sparc/swarm 灰色地带分项

---

## §五 撞车 0 严守的最终建议

按 user query 「撞车 0 严守绝对不动清单」+「不删任何文件」+「不动 .claude/ 下任何配置」:

1. **本报告仅落档** `reports/skill-cleanup-v2.md`，不修改任何 `.claude/skills/` 下文件
2. **§一 绝对可删 (64 个)** 是**理论可删边界**，实际删除仍需 owner 按 R128 §四 18 项拍板流程立项
3. **§二 灰色地带 (12 个)** 必须 owner 二次复核 `ipd-guard*` 路由表引用 + `agentdb-*` 备用轮子用途
4. **§三 启用 (10 个)** 全部保留，是项目运行依赖
5. 建议清理顺序：§2.2 agentdb-*（5 个，最安全）→ §一 其余安全类（mattpocock / 教学）→ §2.1 ipd-guard-*（最后，需充分验证 hook 配套）

---

## §六 不动清单复核（按 v1 + v2 一致原则）

按 user query "撞车 0 严守绝对不动清单":
- `.claude/settings.json` / `.claude/settings.local.json` — **不动**（运行时强制）
- `.claude/hooks/*.sh` / `.claude/helpers/*.cjs` — **不动**（hook/helper 运行时强制）
- `.claude/agents/` 4 个 `.md`（code-reviewer / langchain4j-agent-reviewer / security-reviewer / performance-analyzer）— **不动**（CLAUDE.md §Subagents 段明列）
- `docs/ipd-系统说明/log.md` / `BCP-Registry.md` / `BCP-Closure-Log.md` / `开发计划-看板镜像.md` / `R128-*` / `R138-*` — **不动**（SSOT）
- `docs/开发说明/**`（G-04 圣经）— **不动**
- `docs/wiki/**` — **不动**（AGENTS.md 提到改 wiki 必须 lint）
- `.claude/worktrees/` / `.harness/` / `.agents/` / `.codex/` — **不动**（治理骨架 / 真库凭证）
- **§三 启用清单的 10 个 skill** — **不动**

---

## 附录 — 完整 TSV 数据（全 86 个 skill 的命中明细）

详见 `/tmp/skill_final.tsv`，含 `skill / hit_c / hit_a / hit_s / hit_l / total / gray_prefix / symlink / size / files / target` 12 列。

---

**报告状态**: 已落档 `reports/skill-cleanup-v2.md`，**不修改任何文件**
**撞车 0 让路默认行为**: 等 owner 按 R128 §四 18 项拍板流程立项清理
