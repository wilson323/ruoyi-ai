# 📋 Git Worktree 全量盘点与清理报告

> 生成时间：**2026-09-20 09:40 PDT**  
> 执行人：Agency Harness（撞车 0 严守）  
> 主工作树：`/Users/mac/Documents/ruoyi-ai`  
> 主分支：`main` @ `4f51187b83ea670a002751e1ecc9379f1ce0b588` (2026-09-20 09:23:56 PDT, Claude Code)

---

## §一 盘点总览

**11 个 worktree**（1 主 + 10 兄弟），其中：
- 1 个主工作树（`/Users/mac/Documents/ruoyi-ai` @ main）
- 9 个兄弟 worktree（`/Users/mac/Documents/ruoyi-ai-wt-*`）
- 1 个 .claude 内部 worktree（`/Users/mac/Documents/ruoyi-ai/.claude/worktrees/wt-r127-be`）

**`/private/tmp/` 下未发现 `fix-*` 或 `wt-*` 隐藏 worktree 目录**（仅有 ruoyi-admin.log / .pid / .jar.backup 等运行时文件，**不在本次清理范围**）。

**同盘 `/Users/mac/Documents/` 下其他 ruoyi 相关项目**（RuoYi-Vue-Plus-base / ZKER-* / IAP-zker / ZK-IPD / StaffDeckforZK）属于独立 git 仓库，**不在本次盘点范围**。

### 11 worktree 完整数据表

| # | 绝对路径 | 分支 | ahead main | behind main | 未提交文件数 | 最后文件 mtime | 最后 commit 时间 | 最后 commit author | 最后 commit hash | 已 merge main | 类别 |
|---|---|---|---:|---:|---:|---|---|---|---|:---:|:---:|
| 1 | `/Users/mac/Documents/ruoyi-ai` | `main` | — | — | 0 | (主工作树，跳过) | 2026-09-20 09:23:56 | Claude Code | `4f51187b83ea670a002751e1ecc9379f1ce0b588` | — | 主 |
| 2 | `/Users/mac/Documents/ruoyi-ai-wt-d1b` | `fix/r125-d1b-charset-check` | **0** | 29 | **0** | 9h 前（= HEAD 时间） | 2026-09-20 00:22:23 | ioedream-pm | `25bc9e877d34cc0c5700b8e6ada33da926248602` | ✅ | **🟢** |
| 3 | `/Users/mac/Documents/ruoyi-ai-wt-d3b` | `fix/r125-d3b-chain-eval` | **0** | 28 | **0** | 9h 前 | 2026-09-20 00:22:42 | ioedream-pm | `5e77c3a8758705b7c9ac9b8cdceec9f72532bf7a` | ✅ | **🟢** |
| 4 | `/Users/mac/Documents/ruoyi-ai-wt-r119-revise` | `fix/r119-revise-r25-gates-judgment` | **0** | 30 | **0** | 9h 前 | 2026-09-20 00:05:14 | ioedream-pm | `752a8a48fc27e61100b8de5a4f9174cf602a75e8` | ✅ | **🟢** |
| 5 | `/Users/mac/Documents/ruoyi-ai-wt-r120-v2` | `fix-r120-r25-gates-exit1` | 1 | 32 | 0 | 10h 前 | 2026-09-19 23:42:25 | Claude Code | `811200476070fbf75713de2e8a29291d8d79b8bf` | ❌ | 🔴 |
| 6 | `/Users/mac/Documents/ruoyi-ai-wt-r121-eval` | `fix/r125-r121-eval` | 1 | 31 | 0 | 9h 前 | 2026-09-20 00:30:50 | Claude Code | `084b89af2492df7ecb181259a56984402e211ddd` | ❌ | �� |
| 7 | `/Users/mac/Documents/ruoyi-ai-wt-r126-audit` | `fix/r126-audit` | 1 | 28 | 1 (`?? .codex/`) | 9h 前 | 2026-09-20 00:42:26 | Claude Code | `c7833943319b5d49daf758948c0d98eb991e658f` | ❌ | 🔴 |
| 8 | `/Users/mac/Documents/ruoyi-ai-wt-r126-backend` | `fix/r126-backend-rule` | **0** | 28 | 1 (`?? docs/.../R126-后端规范基线-底座对齐-20260920.md`) | 8h 前 | 2026-09-20 00:22:42 | ioedream-pm | `5e77c3a8758705b7c9ac9b8cdceec9f72532bf7a` | ✅ | **🟡** |
| 9 | `/Users/mac/Documents/ruoyi-ai-wt-r127-blindspot` | `fix/r127-blindspot` | 1 | 28 | 0 | 8h 前 | 2026-09-20 00:54:00 | Claude Code | `80d993c1e166c56c1033ba7e22c7e8b33ea46ed0` | ❌ | 🔴 |
| 10 | `/Users/mac/Documents/ruoyi-ai-wt-r127-db` | `fix/r127-db-paiban` | 1 | 28 | 1 (`?? .codex/`) | 8h 前 | 2026-09-20 01:00:41 | Claude Code | `b7f0131a7f7039a953a4ef20efb8b98138b9bede` | ❌ | 🔴 |
| 11 | `/Users/mac/Documents/ruoyi-ai/.claude/worktrees/wt-r127-be` | `fix/r127-be-paiban` | 1 | 28 | 0 | 8h 前 | 2026-09-20 00:56:16 | Claude Code | `c6e843562d19926636d0b89ab15fa1ed37f552a4` | ❌ | 🔴 |

---

## §二 🟢 可清理清单（已执行清理）

> **关键发现**：`ruoyi-ai-wt-d3b` 与 `ruoyi-ai-wt-r126-backend` 共享同一 HEAD `5e77c3a8` —— 说明 r126-backend 的分支是 d3b 工作的延伸（cherry-pick / rebase），二者的实质内容在 main 上都有等价 commit。

### 2.1 `/Users/mac/Documents/ruoyi-ai-wt-d1b`
- **分支**：`fix/r125-d1b-charset-check`
- **ahead=0 证据**：`git rev-list --count main..fix/r125-d1b-charset-check = 0`
- **已合并证据**：`git branch --merged main` 输出含此分支
- **最后 mtime**：2026-09-20 00:22:23 PDT（**= HEAD commit 时间戳**，无任何后续编辑痕迹）
- **未提交文件**：0
- **结论**：ahead=0 + 已合并 + 无未提交 + 无持续编辑 = **历史完成工作，安全清理**

### 2.2 `/Users/mac/Documents/ruoyi-ai-wt-d3b`
- **分支**：`fix/r125-d3b-chain-eval`
- **ahead=0 证据**：`git rev-list --count main..fix/r125-d3b-chain-eval = 0`
- **已合并证据**：`git branch --merged main` 输出含此分支
- **最后 mtime**：2026-09-20 00:22:42 PDT（= HEAD commit 时间戳）
- **未提交文件**：0
- **结论**：ahead=0 + 已合并 + 无未提交 = **历史完成工作，安全清理**

### 2.3 `/Users/mac/Documents/ruoyi-ai-wt-r119-revise`
- **分支**：`fix/r119-revise-r25-gates-judgment`
- **ahead=0 证据**：`git rev-list --count main..fix/r119-revise-r25-gates-judgment = 0`
- **已合并证据**：`git branch --merged main` 输出含此分支
- **最后 mtime**：2026-09-20 00:04:20 PDT（≈ HEAD commit 时间戳）
- **未提交文件**：0
- **结论**：ahead=0 + 已合并 + 无未提交 = **历史完成工作，安全清理**

---

## §三 🟡 备份后清理清单（仅备份，**未执行清理**）

### 3.1 `/Users/mac/Documents/ruoyi-ai-wt-r126-backend`
- **分支**：`fix/r126-backend-rule`
- **ahead=0 + 已合并**：是（与 d3b 共享同一 commit hash `5e77c3a8`）
- **未提交文件**：**1 个未跟踪文件** —— `?? docs/ipd-系统说明/R126-后端规范基线-底座对齐-20260920.md`
- **风险评估**：兄弟可能正在写 R126-后端规范基线报告但还没 `git add`，撞车风险未排除
- **行动**：**仅备份分支元数据（hash + 分支名）**，**不执行 `git worktree remove`**，留给 owner 手动决策

---

## §四 🔴 不能动清单

### 4.1 `/Users/mac/Documents/ruoyi-ai-wt-r120-v2`
- **分支**：`fix-r120-r25-gates-exit1`
- **理由**：ahead=1（branch 独有的 R25-4 失效脚本修复未合并进 main）

### 4.2 `/Users/mac/Documents/ruoyi-ai-wt-r121-eval`
- **分支**：`fix/r125-r121-eval`
- **理由**：ahead=1（R121-A 撞车 0 评估报告未合并）

### 4.3 `/Users/mac/Documents/ruoyi-ai-wt-r126-audit`
- **分支**：`fix/r126-audit`
- **理由**：ahead=1（R126 规范合规审视报告未合并）+ 1 个未跟踪 `.codex/` 目录

### 4.4 `/Users/mac/Documents/ruoyi-ai-wt-r127-blindspot`
- **分支**：`fix/r127-blindspot`
- **理由**：ahead=1（R127 治理盲区反思报告未合并）

### 4.5 `/Users/mac/Documents/ruoyi-ai-wt-r127-db`
- **分支**：`fix/r127-db-paiban`
- **理由**：ahead=1（R127 DB 整改拍板包未合并）+ 1 个未跟踪 `.codex/` 目录

### 4.6 `/Users/mac/Documents/ruoyi-ai/.claude/worktrees/wt-r127-be`
- **分支**：`fix/r127-be-paiban`
- **理由**：ahead=1（R127 后端整改拍板包未合并）—— 位于 `.claude/` 内部 worktree，可能是 .claude 工具自身维护，需特别谨慎

---

## §五 清理执行记录

### 5.1 执行明细（实际清理 = 3 个 🟢，跳过 1 个 🟡，不动 6 个 🔴）

```
$ git worktree remove /Users/mac/Documents/ruoyi-ai-wt-d1b      → exit 0 ✅
$ git worktree remove /Users/mac/Documents/ruoyi-ai-wt-d3b      → exit 0 ✅
$ git worktree remove /Users/mac/Documents/ruoyi-ai-wt-r119-revise → exit 0 ✅
$ git worktree prune                                              → exit 0 ✅
```

### 5.2 BEFORE → AFTER 对比

**BEFORE（11 个）**：
```
worktree /Users/mac/Documents/ruoyi-ai                              branch main
worktree /Users/mac/Documents/ruoyi-ai-wt-d1b                      branch fix/r125-d1b-charset-check
worktree /Users/mac/Documents/ruoyi-ai-wt-d3b                      branch fix/r125-d3b-chain-eval
worktree /Users/mac/Documents/ruoyi-ai-wt-r119-revise              branch fix/r119-revise-r25-gates-judgment
worktree /Users/mac/Documents/ruoyi-ai-wt-r120-v2                  branch fix-r120-r25-gates-exit1
worktree /Users/mac/Documents/ruoyi-ai-wt-r121-eval                branch fix/r125-r121-eval
worktree /Users/mac/Documents/ruoyi-ai-wt-r126-audit               branch fix/r126-audit
worktree /Users/mac/Documents/ruoyi-ai-wt-r126-backend             branch fix/r126-backend-rule
worktree /Users/mac/Documents/ruoyi-ai-wt-r127-blindspot           branch fix/r127-blindspot
worktree /Users/mac/Documents/ruoyi-ai-wt-r127-db                  branch fix/r127-db-paiban
worktree /Users/mac/Documents/ruoyi-ai/.claude/worktrees/wt-r127-be  branch fix/r127-be-paiban
```

**AFTER（8 个）**：
```
worktree /Users/mac/Documents/ruoyi-ai                              branch main
worktree /Users/mac/Documents/ruoyi-ai-wt-r120-v2                  branch fix-r120-r25-gates-exit1
worktree /Users/mac/Documents/ruoyi-ai-wt-r121-eval                branch fix/r125-r121-eval
worktree /Users/mac/Documents/ruoyi-ai-wt-r126-audit               branch fix/r126-audit
worktree /Users/mac/Documents/ruoyi-ai-wt-r126-backend             branch fix/r126-backend-rule
worktree /Users/mac/Documents/ruoyi-ai-wt-r127-blindspot           branch fix/r127-blindspot
worktree /Users/mac/Documents/ruoyi-ai-wt-r127-db                  branch fix/r127-db-paiban
worktree /Users/mac/Documents/ruoyi-ai/.claude/worktrees/wt-r127-be  branch fix/r127-be-paiban
```

**减少 3 个 worktree 视图，分支全部保留**。

### 5.3 分支保留验证（`git branch` 仍有）
- ✅ `fix/r125-d1b-charset-check`     （被清理 worktree 但分支还在）
- ✅ `fix/r125-d3b-chain-eval`        （被清理 worktree 但分支还在）
- ✅ `fix/r119-revise-r25-gates-judgment`（被清理 worktree 但分支还在）

### 5.4 主工作树状态验证
```
$ git status --porcelain
?? reports/worktree-cleanup-backup.md     ← 任务产物（非 Java/Vue/SQL/yml）
```

**主工作树 Java/Vue/SQL/yml 文件零改动**。

### 5.5 .git/worktrees/ 内部 metadata 验证
- BEFORE：11 个 metadata（d1b / d3b / r119-revise / r120-v2 / r121-eval / r126-audit / r126-backend / r127-blindspot / r127-db / wt-r127-be + main）
- AFTER：7 个 metadata（d1b / d3b / r119-revise 已通过 `git worktree prune` 清除）

### 5.6 物理目录验证
- ✅ `/Users/mac/Documents/ruoyi-ai-wt-d1b` 已不存在
- ✅ `/Users/mac/Documents/ruoyi-ai-wt-d3b` 已不存在
- ✅ `/Users/mac/Documents/ruoyi-ai-wt-r119-revise` 已不存在
- ✅ 其余 7 个 worktree 物理目录完整保留

---

## §六 撞车 0 严守声明

| 红线项 | 是否触碰 | 证据 |
|---|:---:|---|
| 不杀进程（不动 PID） | ✅ 未触碰 | 未执行 `kill` / `pkill`，未读取 `/private/tmp/ruoyi-admin.pid` |
| 不抢端口 | ✅ 未触碰 | 未启动任何服务，未监听任何端口 |
| 不动兄弟会话 modified 文件 | ✅ 未触碰 | 兄弟 worktree 的 `??` 未跟踪文件（r126-audit/r127-db 的 `.codex/`、r126-backend 的 `R126-后端规范基线-底座对齐-20260920.md`）原样保留 |
| 不 push 任何 commit | ✅ 未触碰 | 未执行 `git push`，未触碰任何 remote ref |
| 不 amend / rebase 任何已存在 commit | ✅ 未触碰 | 未对任何分支执行 `git rebase` / `git commit --amend`，所有 10 个分支的 HEAD hash 与盘点时一致 |
| 不删任何分支 | ✅ 未触碰 | `git branch` 输出仍含 10 个本地分支，3 个被清理的 worktree 对应分支全部保留 |
| 主工作树绝对不动 Java/Vue/SQL/yml | ✅ 未触碰 | `git status` 输出仅 `?? reports/worktree-cleanup-backup.md` —— 本次任务产物，非源代码 |

---

## §七 衍生产物

- 📄 `/Users/mac/Documents/ruoyi-ai/reports/worktree-cleanup-backup.md` —— 🟢 3 个分支的备份元数据 + 回滚指引
- 📄 `/Users/mac/Documents/ruoyi-ai/reports/worktree-inventory.md` —— 本报告

---

## §八 统计

- 🟢 **可清理数**：3
- 🟡 **备份清理数**（仅备份未清理）：1
- �� **保留数**：6
- **实际执行清理数**：**3**（= 🟢 + 0，🟡 因有未跟踪文件风险未排除故未清理）
- **剩余 worktree 总数**：8（1 主 + 7 兄弟）
- **保留分支总数**：10（与清理前一致）
