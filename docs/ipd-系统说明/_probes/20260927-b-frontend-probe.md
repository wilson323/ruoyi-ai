# 探针报告：ruoyi-ipd-web 前端「工作流」代码盘点

- **任务 ID**：20260927-b-frontend-probe
- **工作区根**：`/Users/mac/Documents/ruoyi-ipd-web`
- **HEAD（commit short）**：`10c2c7b`（full `10c2c7b9842d21a7586ccd637a61a83ed1c79136`）
- **HEAD message**：`chore(ipd-fe): 给 web-antd 装 @vue/typescript-plugin 并在 tsconfig 注册`
- **盘点模式**：只读（Read/Glob/Grep/Git）+ 不跑 pnpm/vitest/build
- **生成时间**：2026-09-27

> **三类与任务描述不符的关键事实，列在每节首段；行号 / commit hash / URL 均现查，文件未读实现细节（除有特定需求）**。

---

## 0. 总览偏差（任务描述 vs 实际）

| 任务描述 | 实际 | 偏差说明 |
|---|---|---|
| `views/workflow/` 共 51 文件 | **56 文件** | 任务描述 maxdepth 漏计 `components/actions/` (2 文件) 与 `leave/api/` (2 文件) 与 4 个 .tsx data 文件 |
| `views/aiflow/components/` 下所有 NodeXxx.vue | **目录不存在** | 整个 NodeShell 体系位于 `packages/workflow-designer/components/nodes/`，与 aiflow 视图同仓不同路径 |
| `views/aiflow/runtime.ts` SSE 适配 | **文件不存在** | aiflow 只有顶层 6 个文件：README.md / data.ts / index.vue / edit.vue / run.vue / workflow-modal.vue |
| `api/workflow/spel/index.ts` | **实际 `spel/index.tsx`** | 5 子目录中 spel 唯独是 .tsx（26 行 5 端点全在此） |
| `api/ipd/` 47 个 .ts 文件 | **实际 95 个 .ts 文件**（含 *.test.ts） | 工作流相关子集不是按任务描述划定，本报告用业务域关键字判定 |
| `api/ipd/review.ts` | **文件不存在** | review 域拆成 `gate-review.ts` + `post-launch-review.ts` 两个文件 |
| `views/workflow/register.ts` 21 行 | **22 行**（含最后空行） | 内容一致：单 key 映射 `/workflow/leaveEdit/index → LeaveDescription` |

---

## 1. A 类：通用工作流前端

### 1.1 `views/workflow/` 目录清单（56 文件 / 7389 行）

| 子域 | 文件数 | 累计行 | 关键文件（行数） |
|---|---|---|---|
| `category/` | 3 | 359 | `index.vue` (153) · `category-modal.vue` (137) · `data.ts` (69) |
| `components/` (顶层 19 + actions/ 2) | 21 | 2916 | `actions/flow-actions.vue` (420) · `user-select-modal.vue` (378) · `approval-modal.vue` (227) · `approval-panel.vue` (228) · `flow-interfere-modal.vue` (171) · `apply-modal.vue` (166) · `approval-rejection-modal.vue` (146) · `approval-timeline-item.vue` (129) · `approval-card.vue` (97) · `copy-component.vue` (98) · `helper.tsx` (60) · `flow-designer.vue` (54) · `flow-info-modal.vue` (50) · `flow-preview.vue` (46) · `hook.ts` (43) · `approval-details.vue` (40) · `index.ts` (30) · `approval-content.vue` (26) · `approval-timeline.vue` (20) · `type.d.ts` (8) · `actions/index.ts` (1) |
| `leave/` (顶层 7 + api/ 2) | 9 | 1134 | `index.vue` (244) · `leave-form.vue` (197) · `leave-drawer.vue` (190) · `data.tsx` (189) · `api/model.d.ts` (108) · `api/index.ts` (74) · `leave-description.vue` (56) · `leaveEdit.vue` (43) · `hook.ts` (33) |
| `processDefinition/` | 7 | 881 | `index.vue` (378) · `process-definition-modal.vue` (137) · `data.tsx` (131) · `category-tree.vue` (115) · `process-definition-deploy-modal.vue` (73) · `constant.ts` (36) · `design.vue` (11) |
| `processInstance/` | 4 | 613 | `index.vue` (241) · `instance-variable-modal.vue` (214) · `data.tsx` (91) · `instance-invalid-modal.vue` (67) |
| `spel/` | 5 | 419 | `index.vue` (147) · `data.tsx` (122) · `spel-drawer.vue` (108) · `spel-previewer.vue` (27) · `common.ts` (15) |
| `task/` | 6 | 1524 | `allTaskWaiting.vue` (360) · `taskWaiting.vue` (302) · `taskFinish.vue` (299) · `taskCopyList.vue` (299) · `myDocument.vue` (257) · `constant.ts` (7) |
| `register.ts` | 1 | 22 | 业务详情组件注册表（仅 1 个映射，详见 1.5） |

### 1.2 关键文件行号 + 实证四件套

| 文件绝对路径 | 行数 | 关键锚点 | commit (short / date) |
|---|---|---|---|
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/workflow/register.ts` | 22 | `flowComponentsMap` 行 14；`'/workflow/leaveEdit/index'` 行 18；`FlowComponentsMapMapKey` 行 21 | `26f073c` / 2026-02-06 |
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/workflow/components/hook.ts` | 44 | `export function useWarmflowIframe()` 行 9；`postMessage({ type: theme })` 行 19 + 39 | `26f073c` / 2026-02-06 |
| `.../views/workflow/category/index.vue` | 153 | `useVbenVxeGrid` 行 70；`useVbenModal` 行 71 | `26f073c` |
| `.../views/workflow/processDefinition/index.vue` | 378 | `selectedCode = ref<...>([])` 行 44；`currentStatus = ref(1)` 行 118；`currentTableApi = computed(...)` 行 119 | `26f073c` |
| `.../views/workflow/processInstance/index.vue` | 241 | `currentType = ref('process_running')` 行 58；`typeOptions` 行 53 | `26f073c` |
| `.../views/workflow/task/taskWaiting.vue` | 302 | `taskList = ref<...>([])` 行 34；`taskTotal = ref(0)` 行 35 | `26f073c` |
| `.../views/workflow/spel/index.vue` | 147 | `useVbenVxeGrid` 行 59；`useVbenDrawer` 行 63 | `26f073c` |
| `.../views/workflow/components/actions/flow-actions.vue` | 420 | 任务描述未提及；可能为后续加入的动作面板 | （合入 components/ 提交） |

### 1.3 `api/workflow/` HTTP 适配层

5 子目录，9 个 .ts/.tsx + 4 个 model.d.ts。**spel 子目录唯独是 `.tsx`**。

| 子目录 | 行数 | 路由前缀（grep 字面量） | commit |
|---|---|---|---|
| `category/index.ts` + `model.d.ts` | 63 + 97 | `/workflow/category`、`/workflow/category/categoryTree` | `26f073c` |
| `definition/index.ts` + `model.d.ts` | 155 + 19 | `/workflow/definition` | `26f073c` |
| `instance/index.ts` + `model.d.ts` | 139 + 54 | `/workflow/instance`、`/workflow/instance/invalid`、`/workflow/instance/pageByFinish`、`/workflow/instance/pageByRunning` | `26f073c` |
| `task/index.ts` + `model.d.ts` | 172 + 116 | `/workflow/task`、`/workflow/task/backProcess`、`/workflow/task/completeTask` | `26f073c` |
| `spel/index.tsx` + `model.d.ts` | 26 + 10 | `/workflow/spel`（list/info/add/update/delete 共 5 端点） | `26f073c` |

**端点字面量抓取说明**：用 `grep -hoE "['\"\`][/][^'\"\`]+['\"\`]"` 仅能拿到硬编码路径；模板字符串 `${id}` 形式会被遗漏（如 `/workflow/definition/${id}`）。字面端点约 53+；模板路径未计入。

**整层最近 5 次变更**（`git log -5 -- api/workflow/`）：`26f073c v3.0.0 init` → `70a3bbf` → `801a89d` → `b5436a7` → `9a9bda4`，全部 2026-02 之前；HEAD = `10c2c7b` 自此 7 个月未触 workflow 适配层。

### 1.4 workflow-designer 包（独立于 views/workflow/，同仓不同路径）

`apps/web-antd/src/packages/workflow-designer/` —— standalone workflow designer + 节点组件库，与 `views/aiflow/` 的 edit.vue / run.vue 配套使用。

| 类别 | 文件 | 行数 | commit |
|---|---|---|---|
| 主壳 | `StandaloneWorkflowDesigner.vue` | 680 | `825450f` / 2026-07-30 |
| 运行时详情 | `components/RunDetail.vue` | 545 | `9c98360` / 2026-09-11 |
| 节点组件壳 | `components/nodes/NodeShell.vue` | 105 | `825450f` / 2026-07-30 |
| 节点真实现（最大） | `components/nodes/SwitcherNode.vue` | **271** | `825450f` / 2026-07-30 |
| 节点自实现（轻量） | `components/nodes/GoogleNode.vue` (47) · `StartNode.vue` (30) · `AnswerNode.vue` (24) · `TestNode.vue` (24) · `EndNode.vue` (21) | 见 §3 | `825450f` / 2026-07-30 |
| 节点转发壳 | `components/nodes/{Dalle3Node,FaqExtractorNode,TongyiwanxNode}.vue` | **12 each** | `7b1c891` / **2025-09-29** |
| 边组件 | `edges/CustomEdge.vue` (60) · `CustomEdge2.vue` (48) · `SpecialEdge.vue` (20) | — | — |
| 属性面板（最大） | `properties/SwitcherNodeProperty.vue` | **860** | — |
| 其它属性 | `properties/GoogleNodeProperty.vue` (143) · `StartNodeProperty.vue` (131) · `GenericNodeProperty.vue` (66) · `AnswerNodeProperty.vue` (59) · `TestNodeProperty.vue` (33) · `EndNodeProperty.vue` (32) | — | — |
| 其它 | `CommonNodeHeader.vue` (53) · `RuntimeNodes.vue` (90) · `WfVariableSelector.vue` (160) · `SvgIcon.vue` (12) · `panels/RightPanel.vue` (67) | — | — |

### 1.5 workflow iframe + postMessage 桥接

唯一锚点：`views/workflow/components/hook.ts:9` 导出 `useWarmflowIframe()`（44 行）。

实现要点（行号）：
- 行 10：`const iframeRef = useTemplateRef<HTMLIFrameElement>('iframeRef')`
- 行 13–20：`iframeLoadEvent()` 通过 `postMessage({ type: theme })` 推送主题
- 行 17：`await new Promise((resolve) => setTimeout(resolve, 500))` —— 注释说明"拿不到内部 vue 的 mount 状态"
- 行 22–27：`onMounted` 注册 `load` 监听
- 行 29–31：`onBeforeUnmount` 被注释（保留代码 + 注释）
- 行 34–40：`watch(isDark, ...)` 切换主题推送

### 1.6 `views/workflow/register.ts` 注册映射

22 行内只有 **1 个 key 映射**：

```ts
'/workflow/leaveEdit/index': markRaw(LeaveDescription)
```

含义：flowRenderer 在业务详情路由匹配到 `/workflow/leaveEdit/index` 时挂载 `LeaveDescription`（来自 `views/workflow/leave/leave-description.vue`，56 行）。**未见 IPD 业务详情注册项**——B 类业务详情走 `views/ipd/...` 自己的视图，不通过此注册表。

### 1.7 三方对齐矩阵「前端视角」结论（任务要求）

- `views/aiflow/components/` 目录不存在 → **aiflow 视图层零转发壳**
- 真实转发壳在 `packages/workflow-designer/components/nodes/`：**3 个**（Dalle3Node / FaqExtractorNode / TongyiwanxNode，各 12 行，命中 `import NodeShell` 各 1 次）
- workflow 视图侧 7 子域不存在 NodeShell 转发壳模式（views/workflow/ 与 workflow-designer 解耦）
- 详见 §3 清单

---

## 2. B 类：IPD 业务工作流前端

### 2.1 Gate 评审要素 / 详情（admin 域）

| 文件绝对路径 | 行数 | 角色 | commit |
|---|---|---|---|
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/gate-detail/index.vue` | **795** | 主评审详情页 | `1b058bf` / 2026-09-25 |
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/gate-detail/button-policy.ts` | 138 | 按钮策略 | `bdad89f` / 2026-09-22 |
| `.../gate-detail/index.test.ts` | 451 | 测试 | — |
| `.../gate-detail/button-policy.test.ts` | 120 | 按钮策略测试 | — |
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/gate-elements/index.vue` | **682** | 评审要素列表 | `22390ad` / 2026-09-22 |
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/gate-elements/button-policy.ts` | 145 | 要素按钮策略 | `707a2c5` / 2026-09-22 |
| `.../gate-elements/index.test.ts` | 143 | 测试 | — |
| `.../gate-elements/button-policy.test.ts` | 140 | 按钮策略测试 | — |

### 2.2 AI 卡片（_shared/ai-cards/）

`apps/web-antd/src/views/ipd/_shared/ai-cards/` 共 **10 文件**：

| 文件 | 行数 | 性质 | commit |
|---|---|---|---|
| `card-registry.ts` | 105 | 注册表骨架（type → 渲染组件）；导出 `CARD_REGISTRY` / `getCardType()` / `listCardTypes()` / `isCardType()` | `2bfc88d` / 2026-09-27 |
| `types.ts` | 149 | 类型定义（`AiCardType` `AiCardScene` `CardDynString` 等） | — |
| `demand-draft-card.vue` | 153 | ✅ 真实现（shape=`draft`） | `e52763d` / 2026-09-27 |
| `gate-conclusion-card.vue` | 153 | ✅ 真实现（shape=`decision`） | `e52763d` / 2026-09-27 |
| `gate-precheck-card.vue` | 159 | ✅ 真实现（shape=`checklist`） | `e52763d` / 2026-09-27 |
| `project-charter-card.vue` | 161 | ✅ 真实现（shape=`compare`） | `e52763d` / 2026-09-27 |
| `blind-sign-render.test.ts` | 126 | **未跟踪**（兄弟会话新加） | UNTRACKED |
| `card-components.test.ts` | 248 | 测试 | — |
| `card-registry.test.ts` | 168 | 测试 | — |
| `catalog-reconcile.test.ts` | 485 | 对账哨兵测试 | — |

**4 个 .vue 均为真实现**：每个 153–161 行，使用 `Alert, Button, Tag` 等 antdv 组件，类型 import 自 `./types`，无 `import NodeShell`（NodeShell 体系是 workflow-designer 专属）。

**card-registry.ts 关键注册条目**（读全文）：
- `demand.draft` → `DemandDraftCard` (shape=`draft`, scene=`demand.create.from-requirement`)
- `gate.conclusion` → `GateConclusionCard` (shape=`decision`, scene=`gate.conclusion-draft`)
- `gate.precheck` → `GatePrecheckCard` (shape=`checklist`, scene=`gate.precheck-checklist`)
- `project.charter` → `ProjectCharterCard` (shape=`compare`, scene=`project.create.suggest`)

### 2.3 阶段确认 / Gate 评审视图（review/）

`apps/web-antd/src/views/ipd/review/` 5 文件：

| 文件 | 行数 | 角色 | commit |
|---|---|---|---|
| `index.vue` | 660 | 评审列表主页面 | `d3f8419` / 2026-09-25 |
| `gate-panel.vue` | **1255** | Gate 评审面板（最大单文件） | `af2506b` / 2026-09-27 |
| `gate-panel-complete-render.test.ts` | 206 | 完整渲染测试 | — |
| `gate-review.test.ts` | 305 | Gate 评审测试 | — |
| `review.test.ts` | 124 | 综述测试 | — |

### 2.4 项目域（project/detail/）

`apps/web-antd/src/views/ipd/project/detail/` 16 文件，含主 index + 8 页签 vue + 7 测试：

| 文件 | 行数 | 角色 | commit |
|---|---|---|---|
| `index.vue` | 186 | 项目详情主壳 | — |
| `overview.vue` | **1141** | 项目概览页签（最大） | — |
| `changes.vue` | **927** | 变更单页签 | `9e85864` / 2026-09-24 |
| `flow.vue` | 399 | 流转页签 | — |
| `documents.vue` | 483 | 文档页签 | — |
| `kpi.vue` | 347 | KPI 页签 | — |
| `circle.vue` | 335 | 圈层页签 | — |
| `gates.vue` | 164 | Gate 页签 | `bbe0b8b` / 2026-09-27 |
| `incentive.vue` | 162 | 激励页签 | — |
| `audit.vue` | 137 | 审计页签 | — |
| 6 个 .test.ts 文件 | 共 1077 | 配套测试 | — |

### 2.5 状态机前端（_shared/ipd-state-machines.ts）

**286 行**（任务描述 285，差 1 trailing newline），单一权威源，覆盖 **9 个状态机**——任务描述「6 个」是基于文件头部注释过时（注释行 5–11 列了 6 个，实际 export 9 个）。

| 状态机 | 类型定义行号 | 状态数 | 迁移图导出 |
|---|---|---|---|
| `PROJECT_STATUS_MACHINE` | 行 76 | 5（DRAFT/TEAMING/ACTIVE/SUSPENDED/ARCHIVED） | 行 79–85 |
| `DEMAND_STATUS_MACHINE` | 行 99 | 8（SUBMITTED/ACCEPTED/EVALUATING/SCHEDULED/PROCESSING/IN_DEV/CLOSED/ARCHIVED） | 行 102–111 |
| `BONUS_STATUS_MACHINE` | 行 120 | 3（DRAFT/CONFIRMED/DISTRIBUTED） | 行 123 |
| `DELETION_STATUS_MACHINE` | 行 135 | 6（PENDING/WITHDRAWN/LEADER_APPROVED/REJECTED/PURGED/ARCHIVED） | 行 138–145 |
| `GATE_STATUS_MACHINE` | 行 155 | 4（PENDING/IN_PROGRESS/PASSED/FAILED） | 行 158–163 |
| `CHANGE_STATUS_MACHINE` | 行 173 | 4（DRAFT/PENDING_SIGN/APPROVED/REJECTED） | 行 176–181 |
| `BID_STATUS_MACHINE` | 行 191 | 4（OPEN/SELECTED/EXPIRED/CLOSED） | 行 194–199 |
| `BID_RESPONSE_STATUS_MACHINE` | 行 209 | 4（PENDING/ACCEPTED/REJECTED/WITHDRAWN） | 行 212–217 |
| `ACTION_STATUS_MACHINE` | 行 228 | 5（NOT_STARTED/IN_PROGRESS/DONE/DELAYED/NA） | 行 231–237 |

**通用工具**：
- `findNode` (行 243)、`isState` (行 251)、`stateLabel` (行 259)、`stateTone` (行 269)、`nextStates` (行 279)
- 类型守卫 + 标签 + tone 三件套（降级 fallback 默认值：「待补充」/「default」）

**commit**：`a285eec` / 2026-09-07（独立 commit，未被兄弟会话在途修改）。

### 2.6 需求流转（demand/）

`apps/web-antd/src/views/ipd/demand/` 4 文件：

| 文件 | 行数 | 角色 | commit |
|---|---|---|---|
| `index.vue` | 944 | 需求列表/主页 | `1dc0bb5` / 2026-09-26 |
| `index.test.ts` | 692 | 测试 | — |
| `detail/index.vue` | — | 详情页 | — |
| `detail/index.test.ts` | — | 详情测试 | — |

### 2.7 其它 _shared（任务相关）

| 文件 | 行数 | 角色 | commit |
|---|---|---|---|
| `ai-assistant.vue` | 759 | AI 助手面板（**M 状态**，兄弟会话在途） | `3097bf0` / 2026-09-26 |
| `ai-assistant.test.ts` | 733 | **未跟踪**（兄弟会话新加） | UNTRACKED |
| `ai-suggest.vue` | 未读 | AI 建议面板 | — |
| `ai-suggest.test.ts` | 未读 | 测试 | — |
| `backend-pending.vue` | 未读 | 后端挂起态页 | — |
| `no-access.vue` | 未读 | 无权限页 | — |
| `three-state.vue` | 未读 | 三态页 | — |

---

## 3. 渲染壳 vs 真实现清单

### 3.1 `packages/workflow-designer/components/nodes/*.vue`（10 文件）

判定规则：≤30 行 + `import NodeShell` 命中 ≥1 → **转发壳**；否则按行数 + 自实现特征判定。

| 文件 | 行数 | `import NodeShell` 命中 | 判定 |
|---|---|---|---|
| `NodeShell.vue` | 105 | 0（本身） | **基础壳**（被其它 3 个文件 import） |
| `Dalle3Node.vue` | **12** | 1 | ✅ **转发壳** |
| `FaqExtractorNode.vue` | **12** | 1 | ✅ **转发壳** |
| `TongyiwanxNode.vue` | **12** | 1 | ✅ **转发壳** |
| `EndNode.vue` | 21 | 0 | 自实现（无 NodeShell，但行数接近壳边界） |
| `AnswerNode.vue` | 24 | 0 | 自实现 |
| `TestNode.vue` | 24 | 0 | 自实现 |
| `StartNode.vue` | 30 | 0 | 自实现（边界 = 任务描述阈值） |
| `GoogleNode.vue` | 47 | 0 | 自实现 |
| `SwitcherNode.vue` | **271** | 0 | ✅ **真实现**（最大单节点文件） |

**任务记忆 `af6390ff-...` 验证**：12 行转发壳（3 个） vs 20-271 行真实现（7 个）——精确吻合。三方对齐矩阵「前端视角」分支命中：转发壳数量 = **3**，真实现数量 = **7**（含 NodeShell 自身）。

### 3.2 `views/ipd/_shared/ai-cards/*.vue`（4 文件）

**全部为真实现**，无 NodeShell 转发壳：

| 文件 | 行数 | 判定 | 关键 import |
|---|---|---|---|
| `demand-draft-card.vue` | 153 | ✅ 真实现 | `Alert, Button` · `CardDynString, DemandDraftCardData` |
| `gate-conclusion-card.vue` | 153 | ✅ 真实现 | `Alert, Button, Tag` · `CardDynString, GateConclusionCardData` |
| `gate-precheck-card.vue` | 159 | ✅ 真实现 | `Alert, Button` · `CardDynString, GatePrecheckCardData` |
| `project-charter-card.vue` | 161 | ✅ 真实现 | `Alert, Button` · `CardDynString, ProjectCharterCardData` |

### 3.3 转发壳 vs 真实现总账

| 路径 | 转发壳数 | 真实现数 | 判定来源 |
|---|---|---|---|
| `views/workflow/` | 0 | 56 | 全为视图页/drawer/modal，无 NodeShell 模式 |
| `views/aiflow/` | 0 | 6（4 vue + 2 ts） | 整目录不含 nodes/ 子目录 |
| `packages/workflow-designer/components/nodes/` | **3** | **7** | `grep -c "import NodeShell"` 实测 |
| `views/ipd/_shared/ai-cards/` | 0 | 4 | 全部真实现 |
| **合计（前端所有路径）** | **3** | **73** | — |

---

## 4. API 适配层清单

### 4.1 `api/workflow/`（5 子目录）

详见 §1.3。所有 commit = `26f073c`（v3.0.0 init），HEAD 后 7 个月未触。

### 4.2 `api/ipd/` 与工作流相关（实际 95 文件，划定工作流子集）

判定依据：业务域关键字 + 任务描述列出的文件名清单。

| 文件 | 工作流相关性 | 关键 export（行号） | commit |
|---|---|---|---|
| `gate.ts` | ★ | grep 无 export 输出（**待二次核对**：可能空壳或被专用文件拆分） | — |
| `gate-element.ts` | ★ | `IpdGateCode` 行 19；`IpdGateElementStatus` 行 23；`createGateElement/updateGateElement/disableGateElement` 行 130–140 | — |
| `gate-element-result.ts` | ★ | `GateElementResult` 行 19；`submitGateReview` 行 123；`listGateLegacyItems` 行 95 | — |
| `gate-precheck.ts` | ★ | `GatePrecheckItemStatus` 行 17；`runGatePrecheck` 行 93 | — |
| `gate-review.ts` | ★ | `GateDecision` 行 21；`listProjectGates` 行 99；`getGateReview` 行 104；`signGate` 行 109+ | — |
| `gate-material.ts` | ★ | `GateMaterialItem` 行 13；`getGateMaterialStatus` 行 31 | — |
| `stage-action.ts` | ★ | `StageActionDepth/Status` 行 26–28；`AlgoType` 行 30；`transitStageAction` 行 162；`recordStageActionFields` 行 173；`addStageActionDeliverable` 行 185；`aiExecuteStageAction` 行 204；`fetchAiAgentTask` 行 236 | — |
| `change.ts` | ★ | `ChangeType` 行 17；`parseCoefficientChangeRequest` 行 86；`parseLaunchDateChangeRequest` 行 114；`RequirementChangeStatus` 行 243；`RequirementChangeCreateInput` 行 265+ | — |
| `demand.ts` | ★ | `DemandStatus` 行 23；`IpdDemand` 行 33；`fetchDemands` 行 56；`fetchDemandDetail` 行 61；`triageDemand` 行 66；`linkDemandProject` 行 74+ | — |
| `deletion.ts` | ★ | `DeletionEntityType` 行 12；`DELETION_ENTITY_TYPES` 行 14；`submitDeletionRequest` 行 53；`leaderDecideDeletion` 行 61；`adminDecideDeletion` 行 65；`listMyDeletionRequests` 行 77；`listDeletionReviewQueue` 行 92；`purgeDeletionRequest` 行 100 | — |
| `review.ts` | — | **不存在** | — |
| `ai-copilot.ts` | ★ | `CopilotTurn` 行 25；`chatCopilot` 行 57；`createSseFrameParser` 行 112（SSE 解析）；`parseStreamDone` 行 179 | — |
| `ai-document.ts` (359) | ★ | `AiDocument` 行 25；`AI_DOCUMENT_PROMPT_TYPES` 行 51；`AiDocumentPromptType` 行 62；`normalizeDateTime` 行 116；`toTimeText` 行 125；`parseAiDocument` 行 143 | **`478cef4`** / 2026-09-24 (**M**) |
| `ai-document.test.ts` (343) | ★ | 测试 | `478cef4` (**M**) |
| `ai-suggest.ts` | ★ | `AiSuggestScene` 行 17；`AiSuggestInput` 行 26；`aiSuggest` 行 48 | — |
| `portal.ts` (272) | ★ | `PortalProduct` 行 20；`PortalDemandTrace` 行 60；`fetchPortalProducts` 行 232；`submitPortalDemand` 行 237；`fetchPortalDemandByCode` 行 246；`PORTAL_CODE_PATTERN` 行 87 | **`8de452f`** / 2026-09-10 (**M**) |
| `portal.test.ts` (308) | ★ | 测试 | `f26271b` / 2026-09-08 (**M**) |
| `project.ts` | ★ | `ProjectLevel/Stage/Status` 行 21–30；`Project` 行 37；`ProjectCreateBody` 行 67；`parseTargetMarkets` 行 173；`listProjects` 行 295 | — |
| `recovery.ts` | ★ | `RecoveryWarningStatus` 行 16；`checkRecovery90d` 行 37；`listRecoveryWarnings` 行 50 | — |
| `handover.ts` | ★ | `HandoverRole` 行 21；`HandoverView` 行 24；`getHandoverInbox` 行 58；`initiateHandover` 行 63；`batchHandover` 行 75；`acceptHandover` 行 87；`cancelHandover` 行 109；`getPmDirectory` 行 117 | — |
| `kpi.ts` | ★ | `KpiSourceItem` 行 39；`KpiPerformanceSummary` 行 47；`getFunctionalKpi` 行 76；`getPerformanceKpi` 行 81；`getKpiTrend` 行 86；`KPI_RAW_TYPES` 行 95；`RawKpiRecord` 行 108；`RawKpiRecordCreateReq` 行 121；`RawKpiRecordQuery` 行 130 | — |
| `scenarios.ts` | ★ | `LandedScenario` 行 19；`listLandedScenarios` 行 63；`createLandedScenario` 行 68；`importLandedScenarios` 行 73+ | — |
| `business-config.ts` | ★ | `BusinessConfigScope` 行 18；`BusinessConfig` 行 20+ | — |
| `sop-template.ts` | ★ | grep 无 export 输出（**待二次核对**） | — |
| `bonus.ts`、`coefficient-change-requests`、`launch-date-change-requests`、`bid*`、`contribution*`、`switching-acceptance`、`post-launch-reviews`、`receipt-ledger*`、`report`、`p0-escalation`、`compliance`、`audit*`、`cert-template*`、`code-texts`、`product*`、`notification*`、`person*`、`hr-sync`、`negative-feedback*`、`role-permission`、`system-config`、`auth*`、`admin-permanent-delete`、`workbench`、`http`、`product-group`、`product-workspace`、`project-circle`、`project-live`、`project-score`、`ai-model-config`、`allowance*`、`auth-live` | 弱 / ★ | 余 70+ 文件，部分与工作流弱相关（如 bonus-pool / audit / auth 等） | — |

### 4.3 api/ipd 主要工作流 URL 前缀（grep 字面量，去重）

抓取方式：`grep -hoE "['\"\`][/][^'\"\`]+['\"\`]" api/ipd/*.ts | sort -u`，仅含硬编码字面量：

```
/ai-agent-tasks                       /ai-copilot/chat
/ai-documents                         /ai-documents/generate
/ai-models                            /ai/suggest
/coefficient-change-requests          /compliance/data-deletion-request
/compliance/data-retention-rules      /deletion-requests
/deletion-requests/archive            /deletion-requests/my-requests
/deletion-requests/review-queue       /demands
/gate-elements                        /gate-elements/manage
/handovers                            /handovers/batch
/handovers/inbox                      /handovers/monthly-attribution
/handovers/super-admin                /kpi/functional
/kpi/performance                      /kpi/raw-records
/kpi/rules                            /kpi/shared
/kpi/trend                            /launch-date-change-requests
/notifications                        /p0/escalation-chain
/p0/escalation-chain/check            /person-sync/jobs
/pm-directory                         /post-launch-reviews
/post-launch-reviews/pending          /product-groups
/products                             /products/batch-import
/project-score-tasks/my               /project-scores
/projects                             /projects/legacy-import
/projects/legacy-import/batch         /receipt-ledgers
/recovery/check-90d                   /recovery/warnings
/report/export/allowance              /report/export/bonus
/report/export/project                /report/project-summary
/requirement-changes                  /requirement-changes/open
/role-permissions                     /role-permissions/effective
/scenarios/landed                     /scenarios/landed/import
/sop-templates                        /sop-templates/current
/stage-actions                        /switching-acceptance
/system-configs                       /workbench/my-initiated
/workbench/my-pending-approvals       /workbench/summary
/workbench/tasks                      /admin/permanent-delete/audit
/audit-logs/export/scope              /audit-logs/rebuild-chain
/audit-logs/scope                     /audit-logs/verify
/auth/login                           /auth/wecom/qr-login
/bonus-pool/auto-compute              /bonus-pool/coefficient/preview
/bonus-pool/compute                   /bonus-pool/list
/bonus-pool/page                      /bid-invitations
/bid-invitations/p231-create          /bid-responses
/cert-templates                       /cert-templates/country-counts
/cert-templates/resolve               /hr-sync/pending-handovers
/negative-feedbacks                   /notifications/read-all
/notifications/unread-count          /person-sync/jobs/abnormal
/person-sync/jobs/retry-all           /role-permissions/reload
```

模板字符串路径（部分）：
- `/admin/permanent-delete/${encodeURIComponent(entityType)}/${encodeURIComponent(id)}`
- `/ai-documents/${documentId}/diff`、`${documentId}/history`、`${documentId}/revise`、`${documentId}/versions`...
- `/api/v1${path}`（注意：`api/v1` 前缀注入点）

### 4.4 兄弟会话涉及 API 文件的提交基线

| 文件 | HEAD 时 commit | 当前是否 M |
|---|---|---|
| `api/ipd/portal.ts` | `8de452f` / 2026-09-10 | ✅ M |
| `api/ipd/ai-document.ts` | `478cef4` / 2026-09-24 | ✅ M |
| `api/ipd/portal.test.ts` | `f26271b` / 2026-09-08 | ✅ M |
| `api/ipd/ai-document.test.ts` | `478cef4` / 2026-09-24 | ✅ M |

> ⚠️ **任务记忆 `ae7c4d81-...` 触发**：portal.ts / ai-document.ts 在兄弟会话中可能已采纳派单文档§3.1 端点契约做字段双兼容。本盘点只读 M 状态文件存在 + 行数，**不验证**双兼容具体内容；详见 §6。

---

## 5. 兄弟会话在途编辑边界

`git status --short` 输出（已现查，HEAD = `10c2c7b`）：

```
M apps/web-antd/src/api/ipd/ai-document.test.ts
M apps/web-antd/src/api/ipd/ai-document.ts
M apps/web-antd/src/api/ipd/portal.test.ts
M apps/web-antd/src/api/ipd/portal.ts
M apps/web-antd/src/router/routes/modules/ipd.ts
M apps/web-antd/src/views/ipd/_shared/ai-assistant.vue
M apps/web-antd/src/views/ipd/change/change.test.ts
M apps/web-antd/src/views/ipd/deletion/my-requests/index.test.ts
M apps/web-antd/src/views/ipd/deletion/my-requests/index.vue
M apps/web-antd/src/views/ipd/deletion/review/index.test.ts
M apps/web-antd/src/views/ipd/deletion/review/index.vue
M apps/web-antd/src/views/ipd/kpi/index.vue
M apps/web-antd/src/views/ipd/kpi/kpi.test.ts
M apps/web-antd/src/views/ipd/portal/status/index.test.ts
M apps/web-antd/src/views/ipd/portal/status/index.vue
?? apps/web-antd/src/views/ipd/_shared/ai-assistant.test.ts
?? apps/web-antd/src/views/ipd/_shared/ai-cards/blind-sign-render.test.ts
```

### 5.1 按工作流相关性分组

| 分组 | M 文件 | 影响盘点？ |
|---|---|---|
| **Gate/Stage 评审** | `views/ipd/portal/status/index.vue` (+test) · `views/ipd/kpi/index.vue` (+test) · `views/ipd/deletion/{my-requests,review}/index.vue` (+test) · `views/ipd/change/change.test.ts` | 是——`portal/status/index.vue` 可能修改了状态机驱动 UI；`deletion/*` 涉及 deletion 状态机的展示；`change/change.test.ts` 涉及 `change.ts` API 测试 |
| **AI 助手** | `views/ipd/_shared/ai-assistant.vue` (M, 759 行) · `views/ipd/_shared/ai-assistant.test.ts` (??, 733 行) | 是——可能修改 ai-assistant 与 ai-cards 间的渲染路径 |
| **AI 卡片** | `views/ipd/_shared/ai-cards/blind-sign-render.test.ts` (??, 126 行) | 是——盲签渲染此前未在 ai-cards 4 个 vue 找到，是新增测试 |
| **路由** | `router/routes/modules/ipd.ts` | 是——可能新增/删除 IPD 路由 |
| **API 端点** | `api/ipd/portal.ts` (272) · `api/ipd/ai-document.ts` (359) · 对应 .test.ts | 是——**任务记忆 ae7c4d81 在此生效，portal.ts 可能已采纳派单文档§3.1 双兼容** |

### 5.2 对工作流盘点的影响

1. **不影响**（已稳定）：
   - `views/workflow/*`（七子域 + 56 文件）——HEAD 后 7 个月未触
   - `packages/workflow-designer/*`（含 NodeShell 体系）——最新独立 commit `825450f` (2026-07-30) / `9c98360` (2026-09-11)
   - `api/workflow/*`（5 子目录 9 文件）——HEAD 后未触
   - `views/ipd/_shared/ipd-state-machines.ts`（9 状态机）——独立 commit `a285eec` (2026-09-07)

2. **影响但已现查**（不读 diff）：
   - `ai-assistant.vue` 759 行（基线 commit `3097bf0` 2026-09-26）
   - `ai-document.ts` 359 行（基线 `478cef4` 2026-09-24）
   - `portal.ts` 272 行（基线 `8de452f` 2026-09-10）
   - `blind-sign-render.test.ts` 126 行（**全新文件**，无 commit hash）

3. **未跟踪文件 `??` 状态**：
   - `views/ipd/_shared/ai-assistant.test.ts` —— 733 行
   - `views/ipd/_shared/ai-cards/blind-sign-render.test.ts` —— 126 行
   - 两者均无 commit hash；HEAD 基线不可比对

### 5.3 推荐的下一步顺序（探针不替决策，仅供后续会话参考）

1. **优先**：等兄弟会话 commit 完毕后，把 M 文件 diff + 新跟踪文件纳入盘点
2. **次优**：核对 `blind-sign-render.test.ts` 的盲签渲染测试是否覆盖 `gate-conclusion-card.vue` / `project-charter-card.vue` 内的「双签」路径
3. **再核**：核对 `portal.ts` 是否采纳派单文档§3.1 端点契约（任务记忆 ae7c4d81）
4. **最后**：核对 `router/routes/modules/ipd.ts` 是否新增/删除/改名 IPD 业务路由

---

## 6. 限制与未验证项

### 6.1 未验证项

| 项 | 原因 | 状态 |
|---|---|---|
| **任务记忆 `ae7c4d81-...` 在 `portal.ts` 中是否真实生效** | M 状态文件 + 任务明确要求"谨慎"——本盘点只读基线 commit (`8de452f`) 与当前行数 (272)，**未读 diff** | **来源未知** |
| **任务记忆 `ae7c4d81-...` 在 `ai-document.ts` 中是否真实生效** | M 状态文件 | **来源未知** |
| **`ai-assistant.vue` 在 M 后的功能变化** | M 状态文件 759 行；只读基线 commit (`3097bf0`) 与当前行数 | **来源未知** |
| **`blind-sign-render.test.ts` 126 行内容** | 全新未跟踪文件；只读 wc 行数 | **来源未知** |
| **`ai-assistant.test.ts` 733 行内容** | 全新未跟踪文件 | **来源未知** |
| **`router/routes/modules/ipd.ts` 路由变化** | M 状态 | **来源未知** |
| **`api/ipd/gate.ts` 是否真为"空壳"** | grep 无 export 输出，但需读全文确认（与 `gate-element.ts` / `gate-review.ts` 等专用文件可能是同一份逻辑拆分） | **待二次核对** |
| **`api/ipd/sop-template.ts` 是否真为"空壳"** | 同上 | **待二次核对** |
| **`views/workflow/components/actions/flow-actions.vue`（420 行）角色** | 任务描述未提及，本盘点未读全文 | **来源未知** |
| **`views/workflow/components/helper.tsx`（60 行）角色** | 任务描述未提及 | **来源未知** |
| **`views/aiflow/workflow-modal.vue`（138 行）角色** | 任务描述未提及 | **来源未知** |
| **`views/aiflow/edit.vue`（269）/ `run.vue`（299）/ `index.vue`（189）是否真使用 StandaloneWorkflowDesigner** | 只列行数，未读实现 | **来源未知** |
| **`views/ipd/demand/detail/index.vue` 内容** | 文件存在但本盘点未读全文 | **来源未知** |
| **`views/ipd/_shared/ai-suggest.vue` / `backend-pending.vue` / `no-access.vue` / `three-state.vue` 等** | — | **来源未知** |

### 6.2 已知限制

1. **行号精度**：Vue/TSX 文件使用 `<script setup>` 语法时无 `defineComponent`/export default 锚点；本盘点的「关键 export 行号」列仅基于 `grep "^export"`，对 Vue SFC 仅识别 `<script setup>` 内的顶层 export，不识别 `<template>` 中的 ref/function。
2. **URL 前缀漏抓**：`grep "['\"\`][/][^'\"\`]+['\"\`]"` 不抓模板字符串 `${id}` 路径；端点数低估。
3. **commit hash 精度**：用 `git log -1 --format=%h` 仅得短 hash；冲突时需用 `%H` 全 hash 二次核对（已对头部 4 文件确认 short = full 前缀）。
4. **状态机数量偏差**：`_shared/ipd-state-machines.ts` 文件头部注释列 6 个状态机，实际 export 9 个——注释与实现不符，**前端视图文档需更新**。
5. **任务描述偏差**：51 vs 56 文件、47 vs 95 .ts 文件、`spel/index.tsx` vs `ts`、`aiflow/components/` 不存在、`runtime.ts` 不存在、`review.ts` 不存在、`register.ts` 21 vs 22 行——7+1 处偏差已逐一登记并以实测为准。

### 6.3 不做的事（强制约束遵守）

- ❌ 未修改任何文件
- ❌ 未跑 pnpm / vitest / build
- ❌ 未 commit / push
- ❌ 未读 M 文件 diff 内容
- ❌ 未读未跟踪文件（`??`）内容

---

## 附录 A：所有实证文件清单（按 commit 倒序）

| commit (short) | date | path | 行数 |
|---|---|---|---|
| `10c2c7b` | 2026-09-27 | HEAD（chore @vue/typescript-plugin） | — |
| `2bfc88d` | 2026-09-27 | `views/ipd/_shared/ai-cards/card-registry.ts` | 105 |
| `af2506b` | 2026-09-27 | `views/ipd/review/gate-panel.vue` | 1255 |
| `bbe0b8b` | 2026-09-27 | `views/ipd/project/detail/gates.vue` | 164 |
| `e52763d` | 2026-09-27 | `views/ipd/_shared/ai-cards/{demand-draft,gate-conclusion,gate-precheck,project-charter}-card.vue` | 153/153/159/161 |
| `1dc0bb5` | 2026-09-26 | `views/ipd/demand/index.vue` | 944 |
| `3097bf0` | 2026-09-26 | `views/ipd/_shared/ai-assistant.vue` | 759 (**M**) |
| `d3f8419` | 2026-09-25 | `views/ipd/review/index.vue` | 660 |
| `1b058bf` | 2026-09-25 | `views/ipd/admin/gate-detail/index.vue` | 795 |
| `9e85864` | 2026-09-24 | `views/ipd/project/detail/changes.vue` | 927 |
| `478cef4` | 2026-09-24 | `api/ipd/{ai-document,ai-document.test}.ts` | 359 / 343 (**M**) |
| `bdad89f` | 2026-09-22 | `views/ipd/admin/gate-detail/button-policy.ts` | 138 |
| `22390ad` | 2026-09-22 | `views/ipd/admin/gate-elements/index.vue` | 682 |
| `707a2c5` | 2026-09-22 | `views/ipd/admin/gate-elements/button-policy.ts` | 145 |
| `9c98360` | 2026-09-11 | `packages/workflow-designer/components/RunDetail.vue` | 545 |
| `8de452f` | 2026-09-10 | `api/ipd/portal.ts` | 272 (**M**) |
| `f26271b` | 2026-09-08 | `api/ipd/portal.test.ts` | 308 (**M**) |
| `a285eec` | 2026-09-07 | `views/ipd/_shared/ipd-state-machines.ts` | 286 |
| `825450f` | 2026-07-30 | `packages/workflow-designer/{StandaloneWorkflowDesigner,components/nodes/NodeShell,components/nodes/SwitcherNode}.vue` | 680 / 105 / 271 |
| `26f073c` | 2026-02-06 | `views/workflow/register.ts`、`views/workflow/components/hook.ts`、`api/workflow/{category,definition,instance,task}/index.ts`、`api/workflow/spel/index.tsx` | 22 / 44 / 63+155+139+172+26 |
| `7b1c891` | 2025-09-29 | `packages/workflow-designer/components/nodes/{Dalle3Node,FaqExtractorNode,TongyiwanxNode}.vue` | 12 / 12 / 12 |
| UNTRACKED | — | `views/ipd/_shared/ai-assistant.test.ts`、`views/ipd/_shared/ai-cards/blind-sign-render.test.ts` | 733 / 126 |

---

**END OF PROBE**
