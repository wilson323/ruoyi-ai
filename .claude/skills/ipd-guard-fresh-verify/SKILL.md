---
name: ipd-guard-fresh-verify
description: 看板（Vibe Kanban @ 127.0.0.1:62250）任何 PUT 写入后强制 fresh GET 回读核验，禁止用推算总账数字、禁止用单卡 GET 代替 LIST 取基文、禁止把 422 当作失败。封装"PUT 200 ≠ 落库成功"的反复踩坑。
---

# ipd-guard-fresh-verify

看板写入的 fresh 验证铁律。

## 何时使用

- 任何 `PUT /api/tasks/<id>` 操作（翻卡、注记、改描述、加 marker）
- 任何 `POST /api/tasks` 新建卡
- 任何批量改卡（多卡连写）
- 收尾报总账数字（卡数、状态分布、marker 计数）

## 三条铁律

### 铁律 1：PUT 后必须独立 GET 回读

PUT 返回 200 **不等于**真的落库。已实测的失败模式：

- PUT 成功但 desc 字段未更新
- PUT 成功但 marker 数组被整体替换为空
- PUT 成功但 status 字段类型错误（"in_progress" 写成 "inprogress"）
- PUT 返回 200 但服务侧抛了 422 后**已部分生效**

**强制做法**：

```bash
# 1. PUT 后立刻独立 GET 一次（不用缓存、不用之前 GET 的结果）
curl -s "http://127.0.0.1:62250/api/tasks/<task_id>" \
  -H "Authorization: Bearer <token>" \
  | jq '.description | length, .updated_at, .status'

# 2. 若该卡是汇总卡 / 包含卡，还要 LIST 同父项目所有卡复核计数
curl -s "http://127.0.0.1:62250/api/projects/<project_id>/tasks" \
  | jq '[.[] | select(.status=="done")] | length'
```

**禁止**用 PUT 前的 GET 内容做 diff 来判定落库——PUT 是整体替换语义，diff 工具看不出来。

### 铁律 2：取基文用 LIST 端点，不用单卡 GET

单卡 GET 在某些路径下可能返回空描述（实测），导致 manage.py / 外部脚本基于空 desc 做 PUT 把已有描述清空。

**强制做法**：

```bash
# 取基文永远用 LIST 端点（返回所有任务，含完整 desc）
curl -s "http://127.0.0.1:62250/api/projects/<project_id>/tasks" \
  | jq '.[] | select(.id=="<task_id>")'

# 单卡 GET 只用于"我刚 PUT 完，要回读验证这一刻"——不能拿来做 PUT 前的基文
```

### 铁律 3：总账数字必须 fresh 拉 API 验证

任何在汇报里写"已完成 X 张"、"待审核 Y 张"、"P0 阻塞 Z 项"之前：

```bash
# fresh 拉一次，按真实 status 计数
curl -s "http://127.0.0.1:62250/api/projects/<project_id>/tasks" \
  | jq 'group_by(.status) | map({status: .[0].status, count: length})'
```

**禁止**用"之前是多少 + 这次翻了几张 = 现在多少"推算。多会话共工时别人可能也在翻，推算出来的数字一定不准。

## 状态字段合法值

合法 status：`todo` / `in_progress` / `review` / `done` / `cancelled`。

**`blocked` 不是合法 status**。如果试图 PUT 一个 `blocked` 状态，看板会静默忽略或返回 422，**已观测到 PUT 仍"成功"返回 200 但状态未变更**的情况。

要表达"被阻塞"，**不要**改 status，而是改描述里加 marker 或文本说明。

## 必做检查清单

每次看板写入交付前自检：

- [ ] 写入后独立 GET 回读一次，确认 desc_len / status / marker 都符合预期
- [ ] 取基文用的是 LIST 端点，不是单卡 GET
- [ ] 总账数字是 fresh 拉的，不是推算的
- [ ] 没有用 `blocked` 作为 status
- [ ] 写入的项目是 `ruoyi-ai`（01dcf15c-86bb-4c7b-957c-8fe44bddd10d），不是 ZKER-staff

## 失败归因

| 现象 | 真因 | 归因分类 | 修法 |
|---|---|---|---|
| PUT 200 但 desc 没变 | LIST 取基文用了单卡 GET，旧 desc 为空 | 知识错（违反铁律 2） | 改用 LIST 端点 |
| PUT 200 但 status 没变 | 用了 blocked / blocked-by 等非法 status | 知识错 | 改用合法 status + 描述加 marker |
| 总账数字与其他会话汇报对不上 | 推算未 fresh 拉 | 知识错（违反铁律 3） | 重拉 API 重新计数 |
| PUT 422 但刷新页面看状态已变 | 422 后已部分生效 | 检查错（422 不一定回滚） | 先 GET 当前态，再决定是否补 PUT |

## 禁止清单

- ❌ PUT 后用单卡 GET 复核对内容做判定
- ❌ 用 PUT 前缓存的内容做 diff
- ❌ 推算总账数字（"之前 50 + 这次 3 = 现在 53"）
- ❌ 把任何 `blocked` / `wontfix` / `pending` 当作合法 status
- ❌ 跨项目操作（把 ZKER-staff 卡当 ruoyi-ai 卡改）

## 版本指纹

- 验证时看板版本：Vibe Kanban @ 127.0.0.1:62250（HTTP API mode）
- 验证时项目 UUID：01dcf15c-86bb-4c7b-957c-8fe44bddd10d（ruoyi-ai）
- 最近验证：2026-09-11（首版蒸馏）
- 过期触发：看板 API 版本升级 / status 枚举扩展