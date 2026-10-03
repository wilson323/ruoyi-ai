# Issue tracker: GitHub

本页是历史 GitHub 操作参考，不是 IPD 事项入口。IPD 的执行顺序由总画布裁定，事项登记使用本地看板及 `docs/ipd-系统说明/开发计划-看板镜像.md`。以下外部写操作仅在当前用户明确要求时适用，不因 Skill 提到 publish 自动执行。

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments by `jq` and also fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` with appropriate `--label` and `--state` filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`

Infer the repo from `git remote -v`; `gh` does this automatically when run inside a clone.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests; `/triage` reads this flag.)_

When set to `yes`, PRs run through the same labels and states as issues, using the `gh pr` equivalents:

- **Read a PR**: `gh pr view <number> --comments` and `gh pr diff <number>` for the diff.
- **List external PRs for triage**: `gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments` then keep only `authorAssociation` of `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, or `NONE` (drop `OWNER`/`MEMBER`/`COLLABORATOR`).
- **Comment / label / close**: `gh pr comment`, `gh pr edit --add-label`/`--remove-label`, `gh pr close`.

GitHub shares one number space across issues and PRs, so a bare `#42` may be either: resolve with `gh pr view 42` and fall back to `gh issue view 42`.

## When a skill says "publish to the issue tracker"

不要自动创建 GitHub issue。先按 IPD 现行本地事项机制交付；用户明确要求发布时再使用下列参考。

## 本仓库特定说明

- **origin**: `wilson323/ruoyi-ai`（项目实际仓库）
- **upstream**: `ageerle/ruoyi-ai`（fork 源）
- **常用命令**:
 - 创建 issue: `gh issue create --title "..." --body "..." --label "needs-triage"`
 - 列待 triage: `gh issue list --label "needs-triage" --state open`
 - 列需 info: `gh issue list --label "needs-info" --state open`
 - 列待 agent: `gh issue list --label "ready-for-agent" --state open`
- **本仓库特有标签命名**:
 - 业务标签：`bug`、`enhancement`、`documentation`、`p0/p1/p2/p3/p4`（阶段）
 - 领域标签：`chat` / `aiflow` / `system` / `common` / `infra` / `ipd`（模块）
 - 紧急度：`urgent` / `low-priority`
- **本仓库特有工作流**:
 - 本仓库正在从 RuoYi-AI 二开为 IPD 产品经理管理系统（详见 `README-IPD-OVERRIDE.md`）
 - 改造工作见 `docs/ipd-系统说明/`（drift audit + 改造检查清单 + 类型映射 + 命名约定）
 - 任何 IPD 业务相关 issue 必须打 `ipd` 标签
- **PR 规范**:
 - 二开 PR 标题格式：`[ipd][P0] 描述`（如 `[ipd][P1] 新建项目 controller`）
 - 至少 1 个 reviewer 同意才能 merge
 - 涉及 `docs/开发说明/` 现有文件的 PR 需要 Gavin 单独批准（避免污染上游文档）

---

**最后更新**：2026-09-04（setup-matt-pocock-skills 自动生成）