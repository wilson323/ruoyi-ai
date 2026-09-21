# 🗂️ Worktree 清理备份清单

> 生成时间：2026-09-20 09:39 PDT  
> 执行人：Agency Harness  
> 触发任务：系统性梳理 git worktree（撞车 0 严守）  
> 操作：**`git worktree remove`（不动分支、不 amend/rebase、不 push）**

---

## §1 清理对象（🟢 可清理 = 3 个）

| # | 路径 | 分支 | HEAD hash | 最后 commit 时间 | 最后 commit 标题 | 最后 commit author | ahead=0 证据 | merged 证据 | 最后文件 mtime | 清理原因 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `/Users/mac/Documents/ruoyi-ai-wt-d1b` | `fix/r125-d1b-charset-check` | `25bc9e877d34cc0c5700b8e6ada33da926248602` | 2026-09-20 00:22:23 PDT | scripts+docs(R122-D1B): 新增 check-charset-consistency.sh 真活可跑字符集巡检 | ioedream-pm | `git rev-list --count main..fix/r125-d1b-charset-check = 0` | `git branch --merged main` 含此分支 | 9h 前（= HEAD commit 时间，无后续编辑） | ahead=0 + 已合并 + 无未提交 + 无持续编辑痕迹 = 历史完成工作 |
| 2 | `/Users/mac/Documents/ruoyi-ai-wt-d3b` | `fix/r125-d3b-chain-eval` | `5e77c3a8758705b7c9ac9b8cdceec9f72532bf7a` | 2026-09-20 00:22:42 PDT | docs(ipd): R122 D3-B chain root 评估报告 (基于真库 fresh 健康快照) | ioedream-pm | `git rev-list --count main..fix/r125-d3b-chain-eval = 0` | `git branch --merged main` 含此分支 | 9h 前 | ahead=0 + 已合并 + 无未提交 = 历史完成工作 |
| 3 | `/Users/mac/Documents/ruoyi-ai-wt-r119-revise` | `fix/r119-revise-r25-gates-judgment` | `752a8a48fc27e61100b8de5a4f9174cf602a75e8` | 2026-09-20 00:05:14 PDT | docs(ipd): R119 报告 §R25 机制失效清单 R125 精确化修订 | ioedream-pm | `git rev-list --count main..fix/r119-revise-r25-gates-judgment = 0` | `git branch --merged main` 含此分支 | 9h 前 | ahead=0 + 已合并 + 无未提交 = 历史完成工作 |

## §2 待清理观察（🟡 备份但暂不清理 = 1 个）

| # | 路径 | 分支 | HEAD hash | 最后 mtime | 原因 |
|---|---|---|---|---|---|
| 1 | `/Users/mac/Documents/ruoyi-ai-wt-r126-backend` | `fix/r126-backend-rule` | `5e77c3a8758705b7c9ac9b8cdceec9f72532bf7a` | 8h 前 | ahead=0 + 已合并，但存在 1 个未跟踪文件 `docs/ipd-系统说明/R126-后端规范基线-底座对齐-20260920.md` —— 兄弟可能写了忘了 `git add`，撞车风险未排除，**仅备份不清理** |

## §3 回滚指引（如需恢复 worktree 视图）

如果发现兄弟 agent 还需要这些 worktree，可基于分支 HEAD 直接重建（分支未被删除）：

```bash
git worktree add <path> <branch>
# 例：
git worktree add /Users/mac/Documents/ruoyi-ai-wt-d1b fix/r125-d1b-charset-check
git worktree add /Users/mac/Documents/ruoyi-ai-wt-d3b fix/r125-d3b-chain-eval
git worktree add /Users/mac/Documents/ruoyi-ai-wt-r119-revise fix/r119-revise-r25-gates-judgment
```

## §4 红线声明

- ✅ 仅删除 worktree 视图，**分支 named ref 全部保留**
- ✅ 未 amend / rebase / push 任何 commit
- ✅ 未触碰主工作树任何 Java/Vue/SQL/yml 文件
- ✅ 未启动/停止任何进程，未占用任何端口
