# R78:D2 P3-1 KPI 结构 owner 翻 done 决策包(2026-09-19,Loop 35)

**一句话结论**:R78 fresh 拉看板 API 验证 D2 P3-1 KPI 结构子卡 4/4 done(`56d97bb0` / `8ea011fe` / `b435964b` / `2a4f6413`),汇总卡 `f71ba244` 状态 `todo` + 标题已注记 `[子卡已全 done 待 owner 翻]`。**主协调撞车 0 + b1e8e713 红线 + docs only 严守:owner 一行 PUT 翻 done**。

**触发**:owner 指令「持续推进 1.2」= R75 subagent C 派单矩阵 #2 = D2 P3-1 KPI 结构。

**撞号透明**:R78 与 R45-R77 平行,撞号不冲突。

**撞车 0 + 单会话能力边界下撞车 0**:仅 docs/ 改动(本决策包 1 个新文件 + log.md / 镜像 append)/ 不擅自翻 status(b1e8e713 红线)/ 不擅自 commit 兄弟会话改动。

---

## 一、R13 五必现查复测(2026-09-19,Loop 35,fresh 验证)

| 项 | 实测值 | 备注 |
|---|---|---|
| HEAD | `23c8ec81` | R77 主协调,本决策包前序 |
| 工作区 | 干净 | `git status --short` 返空 |
| log.md | 6846 行 | R77 后 |
| 看板镜像 | 2706 行 | R77 后 |
| 端口 | 后端 16039 / DB 13306 / 看板 62250 / 前端 vite 15666 | R50 已记录,本轮无变更 |
| **看板 fresh 验证** | **P3-1 汇总卡 `f71ba244` status=todo + 标题注记 `[子卡已全 done 待 owner 翻]` + 4 子卡 4/4 done** | R13 五必现查 + Fresh 验证铁律(memory `770073a2`) |

---

## 二、P3-1 子卡 fresh 验证结果

### 2.1 汇总卡详情(fresh GET)

| 字段 | 值 |
|---|---|
| **ID** | `f71ba244` |
| **标题** | `[子卡已全 done 待 owner 翻] [P3-1] [汇总] KPI 结构` |
| **状态** | `todo` |
| **优先级** | 汇总 |
| **依赖** | — |
| **责任泳道** | 阶段负责人汇总(不作为执行认领卡) |
| **验收要点** | 功能 60%+共担 40%;组长归集;次月 5 日截止 |
| **验证命令/步骤** | 所有关联细卡验收且阶段业务链通过后汇总;不得凭单测全绿关闭 |

### 2.2 子卡清单(4/4 done)

| # | ID | 标题 | 状态 |
|---|---|---|---|
| 1 | `2a4f6413` | [P3-1.1] [U1 高] 功能 KPI 指标来源与计算 | ✅ done |
| 2 | `b435964b` | [P3-1.2] [U1 高] 共担 KPI 归集、样本与 40% 权重 | ✅ done |
| 3 | `56d97bb0` | [P3-1.2-BACKEND] [U1 高] 共担 KPI 双组长确认读端点:GET /api/v1/kpi/shared | ✅ done |
| 4 | `8ea011fe` | [P3-1.3] [U1 高] KPI 截止日、催办与导入分段 | ✅ done |

### 2.3 Fresh 验证结论

- ✅ 4 子卡 4/4 done(无 inprogress / todo)
- ✅ 汇总卡 title 已加注记 `[子卡已全 done 待 owner 翻]`(符合 b1e8e713 红线:title 注记而非 status 翻 done)
- ✅ 验收要点覆盖:功能 KPI + 共担 KPI + 后端读端点 + 截止催办(功能 60% + 共担 40% + 组长归集 + 次月 5 日截止)
- ✅ 卡面无失真,撞号透明 + 单会话能力边界严守

---

## 三、owner 拍板清单(撞车 0 + b1e8e713 不擅自翻 status)

### 3.1 待 owner 操作(1 行 PUT)

| # | 操作 | 端点 | Payload |
|---|---|---|---|
| 1 | **P3-1 汇总卡 `f71ba244` status `todo` → `done`** | `PUT http://127.0.0.1:62250/api/tasks/{f71ba244}` | `{"status": "done"}` |

### 3.2 操作前后卡面状态(预测)

**操作前**:`f71ba244` status=todo,title=`[子卡已全 done 待 owner 翻] [P3-1] [汇总] KPI 结构`

**操作后**:`f71ba244` status=done,title 建议移除 `[子卡已全 done 待 owner 翻]` 注记(避免冗余)→ `[P3-1] [汇总] KPI 结构`

### 3.3 PUT 后必须独立 GET 回读核验(memory `ca6d55aa` + memory `770073a2`)

```bash
curl -s "http://127.0.0.1:62250/api/tasks/{f71ba244}" | python3 -c "
import json, sys
t = json.load(sys.stdin).get('data', {})
print(f\"id={t.get('id','')[:8]} status={t.get('status','?')} title={t.get('title','')}\")
"
```

**验证条件**:status=done ✅ + title 注记已移除 ✅ → owner 翻 done 完成闭环。

---

## 四、解锁下游业务(派单矩阵更新)

### 4.1 P3-1 翻 done 解锁下游

| # | 卡号 | 主题 | 解锁关系 |
|---|---|---|---|
| 1 | D3 P3-3 | 月度津贴(★★★★★,子卡 3/3 done) | P3-1 翻 done → P3-3 派单 #3 升为可 owner 翻 |
| 2 | D4 P3-4 | 奖金池核算(★★★★★,子卡 8/8 done) | P3-1 翻 done → P3-4 派单 #4 升为可 owner 翻 |
| 3 | B1 P-DATA-gap-1 | bonus_allocations 真活 HTTP 验收 | P3-1 KPI 业务核心 → P3-4 奖金核算 → bonus_allocations 真活验收 |

### 4.2 R78 派单矩阵增量

| # | 卡号 | 主题 | 派单 worktree 命名 | 阻塞关系 |
|---|---|---|---|---|
| 1 | D2 P3-1 | KPI 结构 owner 翻 done | 无 — owner PUT 翻 done | owner 1 行 PUT(无 worktree) |
| 2 | D3 P3-3 | 月度津贴 owner 翻 done | 无 — owner PUT 翻 done | 等 P3-1 翻 done 后 |
| 3 | D4 P3-4 | 奖金池核算 owner 翻 done | 无 — owner PUT 翻 done | 等 P3-1 翻 done 后 |

---

## 五、撞号透明 + 撞车 0 + 单会话能力边界下撞车 0 严守声明

- ✅ 仅写决策包到 `docs/ipd-系统说明/R78-D2-P3-1-KPI结构-owner翻done决策包-20260919.md`,主仓其他文件未动
- ✅ 不擅自翻 status(b1e8e713 红线 + 卡面已注记但 status 未翻)
- ✅ 不擅自 commit 兄弟会话改动 / 不擅自注册 launchd / kill PID / mvn 重启
- ✅ 不擅自 push 跨仓
- ✅ Fresh 验证(memory `770073a2`):看板 API 实测 4 子卡 done + 汇总卡 todo + 标题注记
- ✅ 不擅自推算总账数字(R13 五必现查 + Fresh 验证铁律)

---

*作者:主协调会话,2026-09-19。基线:本决策包 `23c8ec81` 前序 `23c8ec81` 兄弟 `97bd709c`。撞车 0 + 撞号透明 + 单会话能力边界 + docs only + b1e8e713 红线严守。*