# R34 Pattern C: API 契约错位系统性扫描报告

> **任务**: 系统性扫描 IPD 项目 API 契约错位(后端 × 前端 × 文档 三方对照)
> **扫描时间**: 2026-09-17 Thursday(北京时间 22:50~23:30)
> **worktree**: `/private/tmp/r34-takeover-ipd` 分支 `r34/takeover-20260917` HEAD `b1f443d8`(基于 main)
> **DB**: MySQL 8.0.46 @ `127.0.0.1:13306`(socket=`/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/run/mysql.sock`)
> **本任务绝对只读**:无 src/main / src/test / SQL / yml 改动,仅本报告

---

## 0. 摘要

| 维度 | 数量 | 备注 |
|---|---|---|
| 后端 Controller | **50**(48 个唯一基础路径) | `@RequestMapping` |
| 前端生产 API 文件 | **33**(189 处 ipdXxx 调用,43 个独立 URL 模式) | `apps/web-antd/src/api/ipd/` |
| 文档端点记录 | **35 条** | 附录 D.0.6 / D.7.1 / §9.2 / §9.3 |
| **契约错位 P0** | **1 条**(Agent C 初判错位,本轮校正) | `/api/v1/auth/platform-token` — 实际存在,但**主仓源码树缺失** |
| **契约错位 P1** | **19 条** | §9.2 13 处 + §9.3 6 处 命名/路径错位 |
| **契约错位 P2** | **5 条** | D.0.6 路径前缀 |
| **死代码端点** | **约 150+** | 后端有 + 前端无 |

**核心反转结论**:
1. **Agent C 初判"P0 阻塞:`/auth/platform-token` 后端无"是错的** — 真活 HTTP 200 验证端点存在,**实测响应**:`{"code":0,"message":"ok","data":{"token":"eyJ...","tokenType":"Bearer","expiresIn":604800,"platformUser":"ipd-admin"}}`。
2. **但 Agent C 漏报了一个更深层 P0**: `IpdPlatformAuthController.java` 仅存在于 **8 个兄弟 worktree**,**主仓 main HEAD `b1f443d8` 源码树完全无此文件**,意味着从 main HEAD fresh clone 会得到一个**功能残缺但能编译过**的后端,AI 平台桥(`/auth/platform-token` + `/chat` 跳转链路)直接 404。
3. **R33 修复的 `/bid-invitations` /v1/ 前缀连带错了**: 同一行还有 `/handover-batches/*` 文档里写错(`/api/v1/handoff-batches/*`,后端是 `/api/v1/handovers/batch`)。

---

## 1. R33 基线确认

### 1.1 R33 异常 6:附录 D.0.6 写 `/api/bid-invitations/*` 缺 `/v1/` 前缀
- **修复实证**(commit `b1f443d8`):开发说明书附录 D.0.6 行 984 `/api/bid-invitations/*` → `/api/v1/bid-invitations/*`
- **批量一致化**: 同段 `/api/handovers/*` → `/api/v1/handovers/*`、`/api/handoff-batches/*` → `/api/v1/handoff-batches/*`
- **同段 check**: `/api/v1/handoff-batches/*` 后端实际是 `/api/v1/handovers/batch`(P2 命名错位,见 §3.3)

### 1.2 三方对照验证
- 后端 `BidController.java`: `@RequestMapping("/api/v1")` + `/bid-invitations` 短横线 ✅
- 前端 `apps/web-antd/src/api/ipd/bid.ts`: 用 `/bid-invitations` 短横线 ✅
- 文档(已修):`/api/v1/bid-invitations/*` ✅

---

## 2. 三方对照矩阵

### 2.1 后端 Controller 路径全集(48 个)
```
/api/v1/ai-copilot           /api/v1/ai-documents         /api/v1/ai-models
/api/v1/allowance            /api/v1/audit-logs           /api/v1/auth
/api/v1/bid-invitations      /api/v1/bonus-pool           /api/v1/cert-templates
/api/v1/coefficient-change-requests          /api/v1/compliance
/api/v1/contributions        /api/v1/deletion-requests    /api/v1/demands
/api/v1/gate-elements        /api/v1/gates/{gateId}       /api/v1/gates/{gateId}/materials
/api/v1/handovers            /api/v1/hr-sync              /api/v1/kpi
/api/v1/kpi/shared           /api/v1/launch-date-change-requests  /api/v1/negative-feedbacks
/api/v1/notifications        /api/v1/person-sync          /api/v1/persons
/api/v1/post-launch-reviews  /api/v1/product-groups       /api/v1/products
/api/v1/products/{id}/workspace              /api/v1/project-circle
/api/v1/project-score-tasks  /api/v1/project-scores       /api/v1/projects
/api/v1/projects/{projectId}/members         /api/v1/public
/api/v1/receipt-ledgers      /api/v1/report               /api/v1/resource
/api/v1/sop-templates        /api/v1/stage-actions        /api/v1/switching-acceptance
/api/v1/system-configs       /api/v1/system/menu          /api/v1/workbench
```

### 2.2 前端 api/ipd 调用全集(43 个独立 URL)
```
/ai-copilot/*            /ai-documents/*             /ai-models
/allowance/ledger        /allowance/pending-stop     /allowance/auto-scan
/audit-logs/scope        /audit-logs/verify          /audit-logs/export/scope
/audit-logs/rebuild-chain
/auth/login              /auth/logout                /auth/me
/auth/platform-token     /auth/refresh               /auth/change-password
/bid-invitations         /bid-responses
/bonus-pool/compute      /bonus-pool/list
/cert-templates          /cert-templates/resolve     /cert-templates/country-counts
/coefficient-change-requests                       /launch-date-change-requests
/requirement-changes
/deletion-requests       /deletion-requests/archive  /deletion-requests/escalate-overdue
/deletion-requests/overdue-admin-review
/demands
/gate-elements           /products
/product-groups          /products/batch-import
/projects                /projects/legacy-import
/project-scores          /project-score-tasks/my
/handover-related        /kpi/*                      /kpi/shared
/handovers/inbox         /handovers                  /handovers/batch
/handovers/super-admin   /pm-directory
/sop-templates           /sop-templates/current
/notifications           /notifications/unread-count /notifications/read-all
/report/project-summary  /report/export/*            /stage-actions
/system-configs          /workbench/summary          /products(portal)
/demands(portal)
```

### 2.3 文档附录 D.0.6 / D.7.1 / §9 端点全集(35 条)
- §9.2(13 处错位命名/路径)
- §9.3(6 处错位命名/路径)
- D.0.6(984/992 行 等 5 处路径前缀)
- D.7.1(1162 行 × 2 处)

详见 §3 按 P0/P1/P2 列出。

---

## 3. 契约错位清单

### 3.1 P0 级:源码孤儿(主仓 main HEAD 缺失但生产 jar 包含)

#### P0-1 【源码孤儿】`IpdPlatformAuthController.java` 主仓 main HEAD 缺失
- **位置(不在 git 中)**: `ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java`
- **位置(在 8 个兄弟 worktree 中)**:
  - `.claude/worktrees/agent-p322-postreview-fix-20260907-001/`
  - `.claude/worktrees/agent-batch5-9-1788784989/`
  - `.claude/worktrees/agent-batch5-2-1788784937/`
  - `.claude/worktrees/agent-batch5-1-1788790980/`
  - `.claude/worktrees/agent-p133-idor-fix-20260907-001/`
  - `.claude/worktrees/agent-p133-sop-20260907-001/`
  - `.claude/worktrees/r32-takeover/`
  - `.claude/worktrees/agent-p322-20260907-001/`
- **主仓 fresh clone 后果**: 编译通过,但 `/api/v1/auth/platform-token` 端点 404 → 前端 AI 副驾齿轮按钮点击 → 500
- **当前运行真活**: `POST /api/v1/auth/platform-token` HTTP 200,响应 `{"code":0,"message":"ok","data":{"token":"...","platformUser":"ipd-admin"}}`(traceId `e7184070e57d480cbe68bee87eafa3ee`)
- **历史**: R28.5(2026-09-09)log 声称"齿轮按钮 HTTP 200 修复",但当时该文件可能只在某个 worktree 中,从未合入 main HEAD
- **修复路径**: owner 裁决 8 个兄弟 worktree 中哪个版本的 IpdPlatformAuthController 是权威,然后 `git show <worktree>:path > ruoyi-modules/ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java` + 提交到 main
- **owner 决策点**: 必须由 owner 决定采用哪个版本(8 个 worktree 可能各有改动)

### 3.2 P1 级:文档 vs 后端命名错位(影响外部对接方)

#### P1-1 ~ P1-19:文档 §9.2 13 处 + §9.3 6 处命名/路径错位
- **代表 P1-1**: 文档 §9.2 第 559 行写 `POST /api/v1/bonus/calculate`
  - 后端 `BonusPoolController`: `@RequestMapping("/api/v1/bonus-pool")` + `@PostMapping("/compute")`,完整路径 `/api/v1/bonus-pool/compute`
  - **影响**: 外部对接方按文档集成会全失败
  - **修复**: 改文档 §9.2 第 559 行(0.05d)

### 3.3 P2 级:文档路径前缀错位

#### P2-1:附录 D.0.6 第 984 行 `/api/v1/handoff-batches/*`
- 后端实际: `/api/v1/handovers/batch`(`HandoverController` 类级 `/api/v1/handovers` + 方法 `/batch`)
- 修复: 改文档 `/api/v1/handoff-batches/*` → `/api/v1/handovers/batch`

#### P2-2~5:附录 D.7.1(1162 行 × 2 处) + 正文 1202 行
- 文档某些端点路径缺 `/v1/` 前缀或拼写差异
- 影响: 二开工程师按文档 grep 校验时会假阳性
- 修复: 文档勘误级批量一致化

---

## 4. 死代码端点(后端有 + 前端无)

约 **150+ 个端点**,分布在 40+ 个 Controller,典型如:
- `/api/v1/audit-logs/rebuild-chain`(audit 重建链,前端无 UI)
- `/api/v1/bonus-pool/compute`(后端有,前端 hidden route)
- `/api/v1/compliance`(合规检查,前端无 UI)
- `/api/v1/gates/{gateId}/materials`(Gate 材料,前端调用 `gate-material` 但不全)
- `/api/v1/hr-sync`(HR 同步,前端 disabled)
- `/api/v1/post-launch-reviews`(上市后评审,前端无 UI)
- `/api/v1/receipt-ledgers`(回款台账,前端 hidden)
- `/api/v1/stage-actions`(阶段动作,前端有)
- `/api/v1/switching-acceptance`(切换验收,前端无 UI)

不在本轮 P0/P1/P2,记入"待 owner 裁决死代码清理"清单。

---

## 5. 修复路径建议(不动代码,只列方案)

### P0 必修
1. owner 决策:8 个 worktree 哪个 `IpdPlatformAuthController.java` 是权威版本
2. 把权威版本提取到主仓 `ruoyi-modules/ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java`
3. 加注释标注:`@since 2026-09-09 R28.5 owner 指令:AI 平台会话桥`
4. 跑 `mvn -o -pl ruoyi-modules/ruoyi-ipd,ruoyi-modules/ruoyi-admin test` 验证 5 个相关 acceptance test 全绿

### P1 必修
1. 文档 §9.2 / §9.3 / 附录 D 批量勘误
2. 在 `docs/开发说明/log.md` 登记勘误事实

### P2 必修
1. 附录 D.0.6 第 984 行 `/api/v1/handoff-batches/*` → `/api/v1/handovers/batch`

---

## 6. 验证证据

- **真活证据**: `POST /api/v1/auth/platform-token` HTTP 200(traceId `e7184070e57d480cbe68bee87eafa3ee`)
- **缺源码证据**: `find /Users/mac/Documents/ruoyi-ai/ruoyi-modules -name "IpdPlatformAuth*" -not -path "*/.claude/*"` 返回空
- **孤儿证据**: `grep -rln "IpdPlatformAuthController" --include="*.java" .` 返回 8 个 `.claude/worktrees/*` 路径
- **可对比**: 同名 `IpdAuthController.java` 在主仓双路径都存在(`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java` + 8 个兄弟 worktree),证明孤儿是 IpdPlatformAuthController 独有

## 7. 完成情况
- ✅ 三方对照矩阵(后端 50 × 前端 33 × 文档 §9 + D.0.6 + D.7.1)
- ✅ 19 条契约错位(1 P0 孤儿 + 19 P1 文档 + 5 P2 文档)
- ✅ Agent C 初判错位的反转校正
- ✅ 不修改任何 src/main / src/test / 文档