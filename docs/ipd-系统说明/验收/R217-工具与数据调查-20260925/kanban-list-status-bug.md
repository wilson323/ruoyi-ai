# R215-KANBAN-BUG 调查记录：vibe-kanban LIST status 查询参数过滤失效

- **卡 ID**: df893d81-6fe1-490b-9a6c-0b59aef218d6
- **调查日期**: 2026-09-25
- **调查人**: IPD 工具/数据治理专员
- **项目**: ruoyi-ai (01dcf15c-86bb-4c7b-957c-8fe44bddd10d)
- **结论**: ② 第三方工具上游 bug（非本仓代码缺陷）

---

## 1. 复现实测

### 1.1 环境定位

```
$ lsof -ti:62250 | xargs ps -p
  PID  PPID COMMAND
26741 26706 /Applications/Docker.app/Contents/MacOS/com.docker.backend services
```

端口 62250 由 Docker 转发：
- **nginx 容器** `aip-vk-web` (nginx:1.28-alpine) → 127.0.0.1:62250→80
- **后端容器** `ruoyi-ai-vibe-kanban` (ruoyi-ai/vibe-kanban-local:0.0.168) → /opt/vibe-kanban (Rust ELF aarch64, 128MB, stripped)
- nginx 转发至 board:9000

### 1.2 status 参数过滤测试

```bash
PID=01dcf15c-86bb-4c7b-957c-8fe44bddd10d
```

| 请求 | 返回总数 | status 分布 |
|------|---------|-------------|
| `GET /api/tasks?project_id=$PID` (无 status) | **529** | done:423 cancelled:59 todo:29 inprogress:15 inreview:3 |
| `GET /api/tasks?project_id=$PID&status=todo` | **529** | 同上（完全一致） |
| `GET /api/tasks?project_id=$PID&status=done` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&status=inprogress` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&status=inreview` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&status=cancelled` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&statuses=todo` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&state=todo` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&filter_status=todo` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&task_status=todo` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&status__eq=todo` | **529** | 同上 |
| `GET /api/tasks?project_id=$PID&is_done=false` | **529** | 同上 |

**结论：所有 status 类参数均被服务端忽略，恒返回全量 529 条。**

### 1.3 updated_at 不 bump 验证

```
done tasks: updated_at==created_at: 423, updated_at!=created_at: 0
```

所有 423 条 done 状态卡的 `updated_at` 与 `created_at` 完全相同，证实 PATCH/PUT 状态变更不触发 updated_at 更新。

### 1.4 端点元信息

```
$ curl -s -X OPTIONS "http://127.0.0.1:62250/api/tasks" -i
HTTP/1.1 405 Method Not Allowed
allow: GET,HEAD,POST
```

- 无 OpenAPI schema（`/api/openapi.json` 返回 SPA HTML）
- 无参数自描述能力

---

## 2. 工具溯源

| 维度 | 结论 |
|------|------|
| 工具名 | Vibe Kanban (BloopAI/vibe-kanban) |
| 本仓版本 | 0.0.168（Docker image: ruoyi-ai/vibe-kanban-local:0.0.168） |
| 上游最新 | v0.1.45-20260919085201（2026-09-19） |
| 语言 | Rust (axum web framework) |
| 本仓代码 | **无服务端源码**。仅有 wrapper 脚本：`docs/ipd-系统说明/vibe-kanban/manage.py`、`batch-sync-commits.py`、`audit_evidence.py`、`nginx.conf` |
| 上游架构变更 | v0.1.x 已将 task_attempts 重构为 workspaces+sessions（PR #1569），/api/tasks 端点可能已迁移 |

### 本仓 wrapper 的既有绕过

`manage.py` 第 238 行：
```python
tasks = api(f'/api/tasks?project_id={project["id"]}') if project else []
```
**从未传递 status 参数**，所有过滤在 Python 侧完成。说明团队早已知晓此限制。

---

## 3. 定性结论

### ② 第三方工具的上游 bug（设计缺失）

- `/api/tasks` 端点在 v0.0.168 中**仅接受 `project_id` 一个有效查询参数**
- 无 status/filter 参数支持，不是"参数名写错"，是**从未实现**
- updated_at 不 bump 是同一工具的第二个缺陷（状态变更未触发时间戳更新）
- 本仓无服务端源码，**不可修**
- 上游 v0.1.45 已大幅重构，该端点可能已变更或废弃

---

## 4. 建议

### 4.1 本仓侧绕过方案（已生效，无需额外改动）

所有对账/同步脚本统一模式：
```
LIST 全量 → 本地 Python/jq 按 status 过滤
```
`manage.py`、`batch-sync-commits.py`、`audit_evidence.py` 均已采用此模式。

### 4.2 对账脚本注意事项

- **禁止**在 URL 中追加 `&status=xxx` 并期望服务端过滤（会产生"已过滤"假象）
- 如需统计各状态数量，必须全量拉取后 `collections.Counter(t['status'] for t in tasks)`
- updated_at 不可作为"最近变更"排序依据（恒等于 created_at）

### 4.3 上游跟进建议

- 上游仓库：https://github.com/BloopAI/vibe-kanban
- 可提 Issue：`GET /api/tasks ignores status query parameter (v0.0.168)`
- 但鉴于上游已重构至 v0.1.45（workspaces/sessions 模型），该 Issue 可能已自然消解
- **建议**：评估是否升级本仓 Docker image 至 v0.1.x（需验证 API 兼容性 + manage.py 适配）

### 4.4 卡面处置建议

本卡定性为**工具缺陷登记**，调查完成即可翻 done。
- 不需要改本仓代码
- 不需要改第三方工具
- 产出物 = 本调查记录

---

## 5. 原始证据存档

复现命令（可直接重跑）：
```bash
PID=01dcf15c-86bb-4c7b-957c-8fe44bddd10d
for st in "" "status=todo" "status=done" "statuses=todo" "state=todo"; do
  sep=$([ -z "$st" ] && echo "" || echo "&")
  n=$(curl -s "http://127.0.0.1:62250/api/tasks?project_id=$PID$sep$st" | python3 -c "import json,sys;d=json.load(sys.stdin);t=d.get('data',d);print(len(t) if isinstance(t,list) else len(t.get('tasks',[])))")
  echo "${st:-<none>} -> $n"
done
```

预期输出（全部 529）：
```
<none> -> 529
status=todo -> 529
status=done -> 529
statuses=todo -> 529
state=todo -> 529
```
