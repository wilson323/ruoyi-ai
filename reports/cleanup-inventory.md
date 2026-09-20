# Ruoyi-AI 仓库精确可清理清单

> **生成时间**:2026-09-20 周日
> **基线**:main 分支 (HEAD `9064fcb7`)
> **方法**:双重证据 (CLAUDE.md 提及 + settings.json enabledPlugins) × 撞车 0 严守
> **生成器**:agency-harness

---

## 双重证据判定规则

按 user 给定规则:
- **类 1/2/3**:"未在 CLAUDE.md 提及 + 未在 settings.json 启用"= 双重证据未启用
  - `CLAUDE.md「自动化栈」`段(§ 130-176)明列启用的 skills / agents / hooks
  - `CLAUDE.md「Ruflo 多智能体协同底座」`段(§ 261-275)明列 ruflo 启用
  - `CLAUDE.md「Wiki 知识库」`段(§ 233-244)明列 karpathy-llm-wiki 启用
  - `CLAUDE.md「Hooks」`段(§ 165-182)明列 git-guardrails-claude-code 来源
  - `settings.json` **无 `enabledPlugins` 字段**,`.claude/settings.local.json` 只有 `enabledMcpjsonServers: [vibe_kanban]`
- **类 4**:报告 mtime > 7 天前 + 标题含「梳理/盘点/分诊/待办」等中间产物 + R128/R138 主报告收录 + 不在 SSOT 引用 = 全部满足才算 superseded

---

## 类 1:未启用 skill

`/Users/mac/Documents/ruoyi-ai/.claude/skills/` 共 **87 个目录**,CLAUDE.md 明列启用 **9 个**(项目自定义 4 + ruflo 内置 3 + Wiki 1 + Hook 来源 1),其余 **78 个未启用**。

**已启用 9 个(不动)**:`ai-module-add`、`gen-test`、`api-contract`、`db-migration`、`sparc-methodology`、`swarm-orchestration`、`v3-swarm-coordination`、`karpathy-llm-wiki`、`git-guardrails-claude-code`

| # | 绝对路径 | 删除证据 1 | 删除证据 2 | 安全理由 |
|---|---|---|---|---|
| 1 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/_templates/` | CLAUDE.md 未提及(grep `\btemplates\b` 在 §自动化栈段无命中) | settings.json 无 enabledPlugins | 仅 skill-builder 模板,无调用入口 |
| 2 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/agent-identity-least-privilege/` | CLAUDE.md 未提及 | settings.json 未启用 | ruflo 内置 agent 身份 skill,无调用证据 |
| 3 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/agentdb-advanced/` | CLAUDE.md 未提及 | settings.json 未启用 | agentdb 高级特性,无调用入口 |
| 4 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/agentdb-learning/` | CLAUDE.md 未提及 | settings.json 未启用 | agentdb 学习,无调用入口 |
| 5 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/agentdb-memory-patterns/` | CLAUDE.md 未提及 | settings.json 未启用 | agentdb 记忆模式,无调用入口 |
| 6 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/agentdb-optimization/` | CLAUDE.md 未提及 | settings.json 未启用 | agentdb 优化,无调用入口 |
| 7 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/agentdb-vector-search/` | CLAUDE.md 未提及 | settings.json 未启用 | agentdb 向量搜索,无调用入口 |
| 8 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ai-approval-audit-trail/` | CLAUDE.md 未提及 | settings.json 未启用 | ai 审批审计,无调用入口 |
| 9 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ask-matt/` | CLAUDE.md 未提及 | settings.json 未启用 | matt pocock 路由器,CLAUDE.md §Agent skills 提「proceed silently」原则懒加载,但未明列该 skill |
| 10 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/browser/` | CLAUDE.md 未提及 | settings.json 未启用 | 浏览器自动化,无调用入口 |
| 11 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/claude-handoff/` | CLAUDE.md 未提及 | settings.json 未启用 | claude 切换,无调用入口 |
| 12 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/code-review/` | CLAUDE.md 未提及(matt pocock 系列,被 `code-reviewer` agent 替代) | settings.json 未启用 | 项目已有 `code-reviewer` agent(CLAUDE.md §Subagents)替代此功能 |
| 13 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/codebase-design/` | CLAUDE.md 未提及 | settings.json 未启用 | 代码库设计,无调用入口 |
| 14 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/continuous-eval-regression/` | CLAUDE.md 未提及 | settings.json 未启用 | 持续评估回归,无调用入口 |
| 15 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/diagnosing-bugs/` | CLAUDE.md 未提及 | settings.json 未启用 | bug 诊断,无调用入口 |
| 16 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/domain-modeling/` | CLAUDE.md 未提及 | settings.json 未启用 | 领域建模,无调用入口 |
| 17 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/egress-allowlist/` | CLAUDE.md 未提及 | settings.json 未启用 | 出站白名单,无调用入口 |
| 18 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/grill-me/` | CLAUDE.md 未提及 | settings.json 未启用 | grill 系列,无调用入口 |
| 19 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/grill-with-docs/` | CLAUDE.md 未提及 | settings.json 未启用 | grill 系列,无调用入口 |
| 20 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/grilling/` | CLAUDE.md 未提及 | settings.json 未启用 | grill 系列,无调用入口 |
| 21 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/handoff/` | CLAUDE.md 未提及 | settings.json 未启用 | 切换,无调用入口 |
| 22 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/hooks-automation/` | CLAUDE.md 未提及 | settings.json 未启用 | hooks 自动化,CLAUDE.md §Hooks 段已用 `.claude/helpers/*.cjs` + `.claude/hooks/*.sh` 替代 |
| 23 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/implement/` | CLAUDE.md 未提及 | settings.json 未启用 | 实施 skill,无调用入口 |
| 24 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/implement-spec/` | CLAUDE.md 未提及 | settings.json 未启用 | 实施 spec,无调用入口 |
| 25 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/improve-codebase-architecture/` | CLAUDE.md 未提及 | settings.json 未启用 | 改进架构,无调用入口 |
| 26 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ipd-guard/` | CLAUDE.md 未提及 | settings.json 未启用 | IPD 防御 skill 入口,与 `.claude/helpers/*.cjs` 协同,但 CLAUDE.md §自动化栈未明列 |
| 27 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ipd-guard-ddl-apply/` | CLAUDE.md 未提及 | settings.json 未启用 | DDL apply 防御,无调用入口(⚠️ 与 db-migration skill 协同,删除前需 owner 拍板) |
| 28 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ipd-guard-five-must-verify/` | CLAUDE.md 未提及 | settings.json 未启用 | 五必现查防御,无调用入口(⚠️ 唯一 SSOT 引用见 log.md R129/R130 等,删除前需 owner 拍板) |
| 29 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ipd-guard-fresh-verify/` | CLAUDE.md 未提及 | settings.json 未启用 | fresh 验证,无调用入口 |
| 30 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ipd-guard-frontend-drift/` | CLAUDE.md 未提及 | settings.json 未启用 | 前端漂移防御,无调用入口(⚠️ 与 `.claude/helpers/ipd-frontend-drift-guard.cjs` hook 协同,删除前需 owner 拍板) |
| 31 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ipd-guard-mock-validity/` | CLAUDE.md 未提及 | settings.json 未启用 | mock 合法性,无调用入口(⚠️ 与 `.claude/helpers/ddl-field-usage-lint.cjs` 协同,删除前需 owner 拍板) |
| 32 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/ipd-guard-multi-session-handoff/` | CLAUDE.md 未提及 | settings.json 未启用 | 多会话切换防御,无调用入口 |
| 33 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/loop-me/` | CLAUDE.md 未提及 | settings.json 未启用 | loop skill,无调用入口 |
| 34 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/migrate-to-shoehorn/` | CLAUDE.md 未提及 | settings.json 未启用 | shoehorn 迁移,无调用入口 |
| 35 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/multi-reviewer-partition/` | CLAUDE.md 未提及 | settings.json 未启用 | 多审查器分区,无调用入口 |
| 36 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/observation-mode-promotion/` | CLAUDE.md 未提及 | settings.json 未启用 | 观察模式,无调用入口 |
| 37 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/pair-programming/` | CLAUDE.md 未提及 | settings.json 未启用 | 配对编程,无调用入口 |
| 38 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/prototype/` | CLAUDE.md 未提及 | settings.json 未启用 | 原型,无调用入口 |
| 39 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/reasoningbank-agentdb/` | CLAUDE.md 未提及 | settings.json 未启用 | reasoningbank,无调用入口 |
| 40 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/reasoningbank-intelligence/` | CLAUDE.md 未提及 | settings.json 未启用 | reasoningbank,无调用入口 |
| 41 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/research/` | CLAUDE.md 未提及 | settings.json 未启用 | 研究 skill,无调用入口 |
| 42 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/resolving-merge-conflicts/` | CLAUDE.md 未提及 | settings.json 未启用 | 合并冲突解决,无调用入口 |
| 43 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/retro/` | CLAUDE.md 未提及 | settings.json 未启用 | retro skill,无调用入口 |
| 44 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/scaffold-exercises/` | CLAUDE.md 未提及 | settings.json 未启用 | scaffold,无调用入口 |
| 45 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/security-review-at-intent/` | CLAUDE.md 未提及 | settings.json 未启用 | 安全审查意图,无调用入口(⚠️ 与 `security-reviewer` agent 协同,删除前需 owner 拍板) |
| 46 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/setup-matt-pocock-skills/` | CLAUDE.md 未提及 | settings.json 未启用 | matt pocock 配置,无调用入口 |
| 47 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/setup-pre-commit/` | CLAUDE.md 未提及 | settings.json 未启用 | pre-commit 配置,无调用入口 |
| 48 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/setup-ts-deep-modules/` | CLAUDE.md 未提及 | settings.json 未启用 | ts deep modules,无调用入口 |
| 49 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/skill-builder/` | CLAUDE.md 未提及 | settings.json 未启用 | skill 创建器,无调用入口 |
| 50 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/stream-chain/` | CLAUDE.md 未提及 | settings.json 未启用 | stream-chain,无调用入口 |
| 51 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/swarm-advanced/` | CLAUDE.md 未提及(CLAUDE.md §Skills 段提到 `swarm-orchestration` 与 `v3-swarm-coordination`,但**未提及** `swarm-advanced`) | settings.json 未启用 | swarm 高级,CLAUDE.md 明列的是另外两个 swarm 系列 |
| 52 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/tdd/` | CLAUDE.md 未提及 | settings.json 未启用 | TDD,无调用入口 |
| 53 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/teach/` | CLAUDE.md 未提及 | settings.json 未启用 | 教学,无调用入口 |
| 54 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/to-questionnaire/` | CLAUDE.md 未提及 | settings.json 未启用 | 问卷,无调用入口 |
| 55 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/to-spec/` | CLAUDE.md 未提及 | settings.json 未启用 | spec 化,无调用入口 |
| 56 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/to-tickets/` | CLAUDE.md 未提及 | settings.json 未启用 | tickets 化,无调用入口 |
| 57 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/triage/` | CLAUDE.md 未提及 | settings.json 未启用 | triage,无调用入口 |
| 58 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-cli-modernization/` | CLAUDE.md 未提及 | settings.json 未启用 | v3 cli,无调用入口 |
| 59 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-core-implementation/` | CLAUDE.md 未提及 | settings.json 未启用 | v3 core,无调用入口 |
| 60 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-ddd-architecture/` | CLAUDE.md 未提及 | settings.json 未启用 | v3 ddd,无调用入口 |
| 61 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-integration-deep/` | CLAUDE.md 未提及 | settings.json 未启用 | v3 integration,无调用入口 |
| 62 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-mcp-optimization/` | CLAUDE.md 未提及 | settings.json 未启用 | v3 mcp,无调用入口 |
| 63 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-memory-unification/` | CLAUDE.md 未提及 | settings.json 未启用 | v3 memory,无调用入口 |
| 64 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-performance-optimization/` | CLAUDE.md 未提及 | settings.json 未启用 | v3 perf,无调用入口 |
| 65 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-security-overhaul/` | CLAUDE.md 未提及 | settings.json 未启用 | v3 security,无调用入口 |
| 66 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/verification-quality/` | CLAUDE.md 未提及 | settings.json 未启用 | 验证质量,无调用入口 |
| 67 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/wait-what/` | CLAUDE.md 未提及 | settings.json 未启用 | wait-what,无调用入口 |
| 68 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/wayfinder/` | CLAUDE.md 未提及 | settings.json 未启用 | wayfinder,无调用入口 |
| 69 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/wizard/` | CLAUDE.md 未提及 | settings.json 未启用 | wizard,无调用入口 |
| 70 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/writing-beats/` | CLAUDE.md 未提及 | settings.json 未启用 | writing,无调用入口 |
| 71 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/writing-for-agents/` | CLAUDE.md 未提及 | settings.json 未启用 | writing,无调用入口 |
| 72 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/writing-fragments/` | CLAUDE.md 未提及 | settings.json 未启用 | writing,无调用入口 |
| 73 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/writing-shape/` | CLAUDE.md 未提及 | settings.json 未启用 | writing,无调用入口 |
| 74 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/github-code-review/` | CLAUDE.md 未提及 | settings.json 未启用 | github code review,无调用入口 |
| 75 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/github-multi-repo/` | CLAUDE.md 未提及 | settings.json 未启用 | github 多仓,无调用入口 |
| 76 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/github-project-management/` | CLAUDE.md 未提及 | settings.json 未启用 | github 项目管理,无调用入口 |
| 77 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/github-release-management/` | CLAUDE.md 未提及 | settings.json 未启用 | github 发布,无调用入口 |
| 78 | `/Users/mac/Documents/ruoyi-ai/.claude/skills/github-workflow-automation/` | CLAUDE.md 未提及 | settings.json 未启用 | github workflow,无调用入口 |

**类 1 合计:78 个目录,预估磁盘释放 ~570KB**(扣除 9 个启用 skill 后,704K - 134K = 570K)

**撞车风险边界标注**:
- ⚠️ 7 个 `ipd-guard-*` 与 `.claude/helpers/*.cjs` hooks 协同,**删除前需 owner 拍板**
- ⚠️ `security-review-at-intent` 与 `security-reviewer` agent 功能重叠,**删除前需 owner 拍板**
- 其余 70 个属 ruflo/mattpocock 内置,无项目代码引用,可删

---

## 类 2:未启用 agent

`/Users/mac/Documents/ruoyi-ai/.claude/agents/` 共 **10 项**(4 个 `.md` + 6 个子目录)。

CLAUDE.md §Subagents 段(§ 147-163)明列启用 **4 个项目自定义 subagent**(`.md`):`code-reviewer`、`langchain4j-agent-reviewer`、`security-reviewer`、`performance-analyzer`。其余 **6 个子目录未启用**。

| # | 绝对路径 | 删除证据 1 | 删除证据 2 | 安全理由 |
|---|---|---|---|---|
| 1 | `/Users/mac/Documents/ruoyi-ai/.claude/agents/browser/` | CLAUDE.md §Subagents 未列 | settings.json 未启用 | 浏览器 subagent,8K,无 CLAUDE.md 引用 |
| 2 | `/Users/mac/Documents/ruoyi-ai/.claude/agents/consensus/` | CLAUDE.md §Subagents 未列 | settings.json 未启用 | 共识 subagent,116K,无 CLAUDE.md 引用 |
| 3 | `/Users/mac/Documents/ruoyi-ai/.claude/agents/core/` | CLAUDE.md §Subagents 未列 | settings.json 未启用 | core subagent,16K,无 CLAUDE.md 引用 |
| 4 | `/Users/mac/Documents/ruoyi-ai/.claude/agents/sparc/` | CLAUDE.md §Subagents 未列(skill 名 `sparc-methodology` 是 skill,非 agent) | settings.json 未启用 | SPARC subagent,76K,CLAUDE.md §Skills 提了 `sparc-methodology` skill,但 §Subagents 未列此 agent |
| 5 | `/Users/mac/Documents/ruoyi-ai/.claude/agents/swarm/` | CLAUDE.md §Subagents 未列(skill 名 `swarm-orchestration` / `v3-swarm-coordination` 是 skill,非 agent) | settings.json 未启用 | swarm subagent,92K,CLAUDE.md §Skills 提了两个 swarm skill,但 §Subagents 未列此 agent |
| 6 | `/Users/mac/Documents/ruoyi-ai/.claude/agents/testing/` | CLAUDE.md §Subagents 未列 | settings.json 未启用 | testing subagent,20K,无 CLAUDE.md 引用 |

**类 2 合计:6 个目录,预估磁盘释放 ~328KB**(356K - 28K 项目自定义 4 个 = 328K)

**撞车风险边界标注**:
- ⚠️ `.claude/agents/swarm/` 与 `.claude/agents/sparc/` 是 ruflo/claude-flow 体系的标准 subagent,**删除前需 owner 拍板**(`settings.json` §claudeFlow.swarm 已启用 swarm 拓扑,但 subagent 目录本身未明列)
- 其余 4 个(browser/consensus/core/testing)无项目代码引用,可删

---

## 类 3:未启用的 command

`/Users/mac/Documents/ruoyi-ai/.claude/commands/` 共 **16 项**(3 个 `.md` + 13 个子目录)。

CLAUDE.md §自动化栈段**未明列任何 command**,仅 §「Ruflo 多智能体协同底座」段提到 `npx claude-flow` shell 命令。`settings.json` 无 enabledPlugins。

按 user query 给定:**未启用的 command 是 `claude-flow-help/memory/swarm` 这 3 个 .md**(其余 13 个子目录是 ruflo/claude-flow 体系,**§Ruflo 段已间接启用**,保留)。

| # | 绝对路径 | 删除证据 1 | 删除证据 2 | 安全理由 |
|---|---|---|---|---|
| 1 | `/Users/mac/Documents/ruoyi-ai/.claude/commands/claude-flow-help.md` | CLAUDE.md §自动化栈段未列该文件(§Ruflo 段提了 `npx claude-flow swarm init` 与 `npx claude-flow hooks route`,**未提及**该 .md) | settings.json 无 enabledPlugins | 4.0K,claude-flow 命令手册,本仓 Ruflo 调用走 `npx claude-flow` 不走此 slash command |
| 2 | `/Users/mac/Documents/ruoyi-ai/.claude/commands/claude-flow-memory.md` | CLAUDE.md §自动化栈段未列该文件 | settings.json 无 enabledPlugins | 4.0K,claude-flow 记忆系统手册,本仓用 `.remember/` 与 `.codex/ipd-dev/`,不走此 |
| 3 | `/Users/mac/Documents/ruoyi-ai/.claude/commands/claude-flow-swarm.md` | CLAUDE.md §自动化栈段未列该文件 | settings.json 无 enabledPlugins | 8.0K,claude-flow swarm 协调手册,本仓 `settings.json` §claudeFlow.swarm 配置已直接启用 swarm 拓扑,不走此 slash command |

**类 3 合计:3 个 .md 文件,预估磁盘释放 ~16KB**

**撞车风险边界标注**:
- 这 3 个 .md 是 ruflo/claude-flow 体系自带的命令手册,**删除前最好先与 owner 确认** — 但本仓**实际使用** `npx claude-flow` 调用而非 slash command,所以是可删候选

---

## 类 4:被 superseded 的历史 R 报告

`/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/` 共 **128 个 R*.md**。

按 user 给定 4 个判定标准:
1. mtime > 7 天前 → R119-R139 全部 Sep 18-20(**全部不满足**)
2. 标题含「梳理/盘点/分诊/待办」等中间产物且无独立交付 → 多数有独立交付(**多数不满足**)
3. R128/R138 主报告收录 → R119-R127 被 R128 §六 列(R128 完整清单明列覆盖);R132-R137 间接被 R138 后续提及
4. 不在 SSOT 引用 → R119-R137 全部被 `log.md` / `BCP-Registry.md` / `BCP-Closure-Log.md` 引用(**全部不满足**)

按"严格 4 条全部满足才算 superseded",**没有任何 R 报告完全满足**。但按"R128 §六 文档清单明列覆盖 + 标题治理类中间产物"两条关键证据,以下 6 份**为候选 superseded**(实际删除前必须 owner 拍板,因为全部仍被 SSOT 引用):

| # | 绝对路径 | 删除证据 1 | 删除证据 2 | 安全理由 | superseded by |
|---|---|---|---|---|---|
| 1 | `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R119-系统性根因反思-R25门禁常态化-R119补缺-20260919.md` | R128 §六 文档清单明列覆盖(§六 行 213:`R119 \| 系统性根因反思 + 16 脚本矩阵`) | 标题含"系统性根因反思"(中间产物);mtime Sep 20 00:22 (<7 天);**log.md 行 8274-8293 引用**(冲突!) | ⚠️ 删除前需 owner 拍板 | R128 |
| 2 | `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R120-修R25-4失效脚本加exit1分支-20260919.md` | R128 §六 文档清单明列覆盖(行 214:`R120 \| 修 R25 4 失效脚本`) | 标题"修脚本"(有独立交付物);mtime Sep 19 23:51;**log.md 行 8335 引用** | ⚠️ 删除前需 owner 拍板 | R128 |
| 3 | `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R121-真活E2E-拍板包-20260919.md` | R128 §六 文档清单明列覆盖 + R128 §四 #1 拍板项整合 | 标题"拍板包"(中间产物);mtime Sep 19 23:16;**log.md 行 8295-8306 详细引用拍板过程** | ⚠️ 删除前需 owner 拍板 — 拍板包是 owner 决策的事实源 | R128 |
| 4 | `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R122-治理轮剩余-D1+D2+D3-拍板包-20260919.md` | R128 §六 文档清单明列覆盖 | 标题"拍板包"(中间产物);mtime Sep 19 23:18;**log.md 行 8308-8325 详细引用拍板过程** | ⚠️ 删除前需 owner 拍板 — 拍板包是 owner 决策的事实源 | R128 |
| 5 | `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R123-cron+pre-commit-hook常态化-R119-5脚本-20260919.md` | R128 §六 文档清单明列覆盖(行 217) | 标题"常态化"(中间产物);mtime Sep 19 23:24;**log.md 行 8327 引用 commit `3df19c34`** | ⚠️ 删除前需 owner 拍板 — commit 事实源 | R128 |
| 6 | `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R126-前端规范基线-底座对齐-20260920.md` | R128 §六 文档清单明列覆盖 + R128 §三 前端规范表整合 | 标题"规范基线"(有独立交付);mtime Sep 20 00:37;**log.md 行 8339-8343 引用** | ⚠️ 删除前需 owner 拍板 — 是前端规范的事实源 | R128 |

**类 4 合计:6 份 R 报告,预估磁盘释放 ~80KB**

**撞车风险边界标注**:
- ⚠️ **R125 / R127 主树无文件**(R125 治理轮轮名,R125 产出分散在 `D3-chain-root评估报告-20260920.md` / `A1-治本-Controller白名单-20260919.md` / `A3-治本-Mock全局过滤-20260919.md` / `A4-治本-参数审计接口-20260919.md` / `A4-治本-参数漂移门禁-20260919.md` / `字符集一致性-20260920.md` 等;R127 仅在 `.claude/worktrees/wt-r127-be/` 内存在,主树从未提交)
- ⚠️ **R124 主树无文件**(撞号跳过,从未存在)
- ⚠️ 上述 6 份**全部被 log.md / BCP-Registry 引用**,严格按 user "不在 SSOT 引用" 标准,**不应删除**。列为候选仅因 R128 §六 明列覆盖

**关于 R132-R137(飞轮自举子报告)**:
- 这些是 R138 主报告之前的飞轮推进子报告
- R138 没有像 R128 §六 那样明列覆盖 R132-R137
- R132-R137 全部仍被 BCP-Registry 引用(`5 钻撞根因覆盖率` 累积过程证据)
- 严格按 user 4 条标准,**R132-R137 不应被标为 superseded**
- **不列入本类 4**

**关于 R132-r-report-line-claims-dry-run-20260920.md**:
- 这是一个工具的 dry-run 输出,**无独立交付**(纯扫描报告)
- 标题含"dry-run"(中间产物特征)
- mtime Sep 20 02:49 (<7 天)
- 不在 BCP-Registry 引用,但 log.md 行 8400 引用过
- **候选边界** — 删除前需 owner 拍板

---

## 不动清单复核

按 user query "撞车 0 严守绝对不动清单",以下文件**全部确认不动**:

### docs/开发说明/**(产品圣经 G-04)
- `/Users/mac/Documents/ruoyi-ai/docs/开发说明/spec/` (CLAUDE.md §IPD 改造必读 行 222-228)
- `/Users/mac/Documents/ruoyi-ai/docs/开发说明/zk-ipd-override.md`
- `/Users/mac/Documents/ruoyi-ai/docs/开发说明/开发说明书.md`

### docs/ipd-系统说明/ SSOT 登记本与主报告
- `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/log.md` (9519 行 SSOT 总账)
- `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/BCP-Registry.md` (1251 行 BCP 登记)
- `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/BCP-Closure-Log.md` (1776 行 BCP 闭环登记)
- `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/开发计划-看板镜像.md` (3587 行看板镜像)
- `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R128-完整清单-剩余待办+注意事项+开发规范+拍板项+最佳实践-20260920.md` (主报告 #1)
- `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R138-4智能体并行穿透3BCP闭环+§十三§十四SOP复盘+A撞号避让-20260920.md` (主报告 #2)

### R 报告中未列入删除候选的
- **R129-R139**:飞轮自举连续推进的子报告(R129 → R139),每份都基于前一份继续推进,**全部保留**(撞车 0 严守 + 后续工作流依赖)
- **R130-R137 全部保留**(飞轮自举的连续推进证据)

### .claude/settings*.json / hooks / 治理骨架
- `/Users/mac/Documents/ruoyi-ai/.claude/settings.json` (运行时强制)
- `/Users/mac/Documents/ruoyi-ai/.claude/settings.local.json` (运行时强制)
- `/Users/mac/Documents/ruoyi-ai/.claude/settings.json.bak` / `.wave19b.bak` / `.proposed` (历史配置,不动)
- `/Users/mac/Documents/ruoyi-ai/.claude/proven-config.json` (ruflo 框架配置)
- `/Users/mac/Documents/ruoyi-ai/.claude/hooks/` (所有 `.sh` hook,运行时强制)
- `/Users/mac/Documents/ruoyi-ai/.claude/helpers/` (所有 `.cjs` helper,运行时强制)
- `/Users/mac/Documents/ruoyi-ai/.claude/worktrees/` (git worktree,不清理)
- `/Users/mac/Documents/ruoyi-ai/.claude/instructions/` (CLAUDE.md 上下文指令)
- `/Users/mac/Documents/ruoyi-ai/.harness/` (治理骨架)
- `/Users/mac/Documents/ruoyi-ai/.agents/` (治理骨架)
- `/Users/mac/Documents/ruoyi-ai/.git/hooks/` (pre-commit 链)
- `/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/**` (gitignored 真库凭证)

### CLAUDE.md 启用的 9 个 skill(项目层不动)
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/ai-module-add/`
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/gen-test/`
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/api-contract/`
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/db-migration/`
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/sparc-methodology/`
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/swarm-orchestration/`
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/v3-swarm-coordination/`
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/karpathy-llm-wiki/`
- `/Users/mac/Documents/ruoyi-ai/.claude/skills/git-guardrails-claude-code/`

### CLAUDE.md 启用的 4 个 subagent(项目层不动)
- `/Users/mac/Documents/ruoyi-ai/.claude/agents/code-reviewer.md`
- `/Users/mac/Documents/ruoyi-ai/.claude/agents/langchain4j-agent-reviewer.md`
- `/Users/mac/Documents/ruoyi-ai/.claude/agents/security-reviewer.md`
- `/Users/mac/Documents/ruoyi-ai/.claude/agents/performance-analyzer.md`

### owner 拍板项相关文件(R128 §四 18 项 + P0-P2 28 项)
按 docs/ipd-系统说明/R128-完整清单-...20260920.md §一 P0 必修(6 项)、§三 P1 应修(11 项)、§四 owner 拍板项清单(18 项)。**全部保留**(撞车 0 严守)。

### docs/wiki/**(按 AGENTS.md "改 docs/wiki/** 后跑 wiki-lint.cjs")
- `/Users/mac/Documents/ruoyi-ai/docs/wiki/**`(可能 owner 看板用,撞车 0 严守不动)

---

## 汇总统计

| 类别 | 候选条数 | 预估磁盘释放 | 撞车风险评分(0-10) |
|---|---|---|---|
| **类 1 未启用 skill** | 78 | ~570 KB | 3 ⚠️ |
| **类 2 未启用 agent** | 6 | ~328 KB | 3 ⚠️ |
| **类 3 未启用的 command** | 3 | ~16 KB | 2 |
| **类 4 被 superseded R 报告** | 6 | ~80 KB | 7 ⚠️⚠️ |
| **合计** | **93** | **~994 KB (~1 MB)** | **4(整体平均,类 4 单项 7)** |

### 撞车风险评分说明

- **类 1 (3/10)**:删除未启用 skill 后,CLAUDE.md §自动化栈段明的 4 个项目 skill 完全保留;ruflo 体系启用命令也保留(ruflo 内置 skill 3 个 + karpathy-llm-wiki + git-guardrails-claude-code)。⚠️ **唯一例外**:7 个 `ipd-guard-*` 与 `.claude/helpers/*.cjs` 协同的 hook 配套,删除前需 owner 拍板(这是软边界,实际删除可分批渐进)。
- **类 2 (3/10)**:删除未启用 subagent 子目录后,CLAUDE.md §Subagents 明列的 4 个项目 subagent 完全保留。⚠️ **唯一例外**:`.claude/agents/swarm/` 与 `.claude/agents/sparc/` 是 ruflo/claude-flow 体系标准 subagent,删除前需 owner 拍板。
- **类 3 (2/10)**:删除 3 个 `.md` 不影响任何运行链路 — Ruflo 调用走 `npx claude-flow` shell 命令,不走 slash command。
- **类 4 (7/10)**:⚠️ **最高风险**。所有 6 份候选**全部被 log.md / BCP-Registry.md / BCP-Closure-Log.md / 开发计划-看板镜像.md 引用**(commit 事实源 + 拍板包细节)。严格按 user "不在 SSOT 引用" 标准,**全部不应删除**。R128 §六 列出的覆盖不等于"可在 SSOT 删除原文"。建议:保留 R119-R139 全部,**撞车 0 让路下不删任何 R 报告**。

### 撞车 0 严守最终建议

1. **类 1 / 类 2 / 类 3 合计 87 项,撞车风险低**,可由 owner 一次性拍板后清理(预估 ~914 KB)。
2. **类 4 6 项,撞车风险高**,建议**全部保留**。理由:
   - 全部仍被 SSOT 引用,删除会破坏"commit 事实源 + 拍板包细节"的可追溯性
   - R128 §六 是"文档清单"(列出文档),不是"覆盖证据"(可删除原文档)
   - 删除前必须 owner 拍板,且应在拍板项表中立项(R128 §四 #17 拍板顺序要求)
3. **整体撞车风险评分 = 4/10**(类 4 拉高均值)。**实际清理建议**:**先做类 1 + 类 2 + 类 3,撞车风险 3/10;类 4 全部保留**。

---

## 报告状态

**生成时间**:2026-09-20 周日
**撞车 0 严守边界**:本报告本身仅落档 `reports/cleanup-inventory.md`(新文件,不动 docs/),不修改任何 skills/agents/commands/R 报告
**下一步**:等 owner 拍板清理范围(建议优先类 1 + 类 2 + 类 3;类 4 全保留)
**撞车 0 让路下默认行为**:不动任何文件,等 owner 拍板清单(P0-P2 28 项 + R128 §四 18 项)落地后分批渐进清理
