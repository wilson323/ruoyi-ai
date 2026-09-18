# TaskView MCP 集成指南 — 2026-09-18

**承接**:owner 命令"安装 taskview mcp 并完整管理本项目"
**基线**:main `93971edc`(R35 合并后)
**taskview-mcp**:v1.48.3(2026-06-27 发布,维护者 giman-head)
**License**:Proprietary(专有)

## 1. 安装状态

- **npm 包**:taskview-mcp@1.48.3(已通过 npx dry-run 验证可拉可启)
- **.mcp.json**:已加 `taskview` 配置块(token 用 `${TASKVIEW_TOKEN}` env 引用,不写死)
- **Node 版本**:当前 v22.22.3,EBADENGINE warn(要求 >= 24,可跑不阻断)
- **MCP 启动**:不连真实 API 时 npx 退出 1,提示需要 `TASKVIEW_URL` + `TASKVIEW_TOKEN`

## 2. 61 个工具能力清单

| 类别 | 工具数 | 代表 |
|---|---|---|
| Goals(项目) | 5 | list_goals / create_goal / update_goal / delete_goal |
| Lists | 4 | list_lists / create_list / update_list / delete_list |
| Tasks | 9 | list_tasks / get_task / create_task / update_task / delete_task / toggle_task_assignees / get_task_history / restore_task_from_history |
| Agenda | 1 | get_agenda(今日/即将/最近完成,跨项目一次取) |
| Tags | 5 | list_tags / create_tag / update_tag / delete_tag / toggle_task_tag |
| Kanban | 4 | list_kanban_columns / create_kanban_column / update_kanban_column / delete_kanban_column |
| 协作 | 11 | list_collaborators / invite_collaborator / toggle_collaborator_roles / list_permissions |
| Task deps(图) | 3 | list_task_dependencies / add_task_dependency / delete_task_dependency |
| 通知 | 3 | list_notifications / mark_notification_read / mark_all_notifications_read |
| 组织 | 6 | list_organizations / create_organization / update_organization |
| 时间追踪 | 9 | start_timer / stop_timer / log_time / get_time_summary / get_time_report |

## 3. owner 必拍板 3 项(本指南交付前阻塞)

### 阻塞 1:TASKVIEW_TOKEN(必须)

- 用户必须提供 `tvk_xxx` 前缀的 API token(从 https://app.taskview.tech/settings/api 申请)
- **我绝不写 token 到 .mcp.json / settings.json / git / log**
- **推荐做法**:`export TASKVIEW_TOKEN=tvk_xxx` 到 shell rc,然后让 MCP 从 env 读
- **绝不做法**:把 token 提交进版本库

### 阻塞 2:TASKVIEW_URL(默认即可,或私有部署)

- 默认 `https://api.taskview.tech`(官方 SaaS)— 多数用户用这个
- 若用户有私有部署(自托管),URL 改 `https://api.example.com`
- 同样用 env:`export TASKVIEW_URL=https://api.taskview.tech`

### 阻塞 3:"完整管理本项目"含义

我推测 3 种解读,需 owner 拍板:

**A:一次性迁移** — 把本机 Vibe Kanban(http://127.0.0.1:62250)上 IPD 项目的 60+ 张卡批量迁到 taskview,本机 kanban 归档
**B:长期双轨** — 两边都更新,每次本机 commit 自动同步到 taskview(双向同步)
**C:只装工具** — MCP 配好放着,owner 手动用 taskview SaaS UI 管理项目,我只帮跑 dry-run + 验证连通性

## 4. 与本机 Vibe Kanban 的关系

- **本机 Vibe Kanban**(http://127.0.0.1:62250):目前 IPD 项目卡管理唯一来源
- **看板操作只认 ruoyi-ai 项目**(记忆 39859730):禁止 ZKER-staff 污染
- **SSOT 镜像**:`docs/ipd-系统说明/开发计划-看板镜像.md`(本机 git 仓库内)
- 切换 taskview 涉及:
  1. 导出本机 Kanban → JSON/CSV
  2. 写迁移脚本:解析本机格式 → taskview-mcp 的 60+ 工具调用(create_goal / create_list / create_task 等)
  3. 校验两边数据一致(双向回读)
  4. 看板镜像改为 taskview 镜像(SSOT 切换)

## 5. 安全规约

- **token 凭证**:`tvk_` 前缀,API 调用 HTTPS,但绝不入库/不打印/不写日志
- **私有部署**:自托管时,API token 留在内网,不会泄到外网
- **OAuth 模式**:ChatGPT 等云客户端支持 OAuth 2.1,但 stdio 模式用 tvk_ 直接
- **HTTP 模式**:多用户共享时,每个请求带调用者自己的 Bearer token,server 无状态

## 6. Node 版本提示(可选,非阻塞)

- taskview-mcp 要求 Node >= 24,当前 22.22.3 → EBADENGINE warn
- 影响:不阻断,工具能跑,但 npx 拉包时打印 warn
- 处理:可不处理 / nvm 装 Node 24 / Docker 跑(不依赖本机 Node)

## 7. 三证律(R35 治理经验)

- **DB**:taskview 是 SaaS,不涉及本机 DB
- **HTTP**:owner 拍板后,跑 taskview-mcp 一次 `list_goals` 验证连通性 + 列已有 goal
- **CLI**:`npx -y taskview-mcp@1.48.3` 在隔离终端启动(无 token 时退出码 1)

## 8. 留待 owner 拍板

1. 提供 `tvk_xxx` token(我从不写死)
2. 拍板 "完整管理本项目" = A 一次性 / B 双轨 / C 只装工具
3. (可选)是否升级 Node 24 或用 Docker 跑

owner 拍板后,我:
- 配置 env 引用
- 写迁移 / 同步脚本
- 跑真活 list_goals 验证
- 写入 log.md + 看板镜像(SSOT 切换如适用)
- commit 全部 R36 闭环