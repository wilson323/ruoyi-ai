# WP3.1 批次1：A10 工作台 2 端点 + A13 需求变更/解绑（样板批次）

- 执行：R215 增量收口轮主会话（单写者）
- 日期：2026-09-24 晚
- 看板卡：A10 `0f35d16e-0561-4df0-a40d-22418816b693`、A13 `eace4648-fab3-40d6-abc7-e9896fcdc9f7`

## 接线内容（前端 5 文件 + 测试 3 文件）

| 文件 | 改动 | 说明 |
|---|---|---|
| `src/api/ipd/workbench.ts` | +34 | MyInitiatedTaskView 接口 + fetchMyInitiated / fetchMyPendingApprovals |
| `src/views/ipd/workbench/index.vue` | +68/-5 | 「我发起的」tab 真数据（不再复用 stage_action 队列）；治理区「待我审批」卡；onMounted 并行拉取静默降级 |
| `src/api/ipd/change.ts` | +9 | listOpenRequirementChanges（/requirement-changes/open） |
| `src/views/ipd/project/detail/changes.vue` | +29/-2 | Tab3 顶部 P2-6.2 门禁 Alert（未闭环变更单清单） |
| `src/api/ipd/product.ts` | +11 | unbindProductProject（与 bind 对称显式解绑，P1-1.1） |
| `src/api/ipd/workbench.test.ts` | +56 | A10 两端点契约测试（URL/查询串/载荷/500 抛错） |
| `src/api/ipd/change.test.ts` | +51 | A13 open 端点契约测试（数组解析/空态/非数组兜底/坏行抛错） |
| `src/api/ipd/product-bind.test.ts` | +67 新建 | bind/unbind 对称契约测试（URL 编码/POST 方法/500） |

## 契约门禁证据（check-api-contract-fe-be.mjs）

`contract-verify-batch1.json`（本目录）：

- orphan_endpoints：**87 → 84**（三端点消亡：GET /workbench/my-initiated、GET /workbench/my-pending-approvals、GET /requirement-changes/open）
- POST /products/{id}/unbind-project：A13 卡 #62 微项，同批消亡（84 已含）
- orphan_paths：0（无新引入前端孤儿路径）
- field_mismatches：0；path_var_name_diffs（info）：15
- pass：**True**

## 验证（实测 2026-09-24 21:42）

- vue-tsc（check:type 等价直跑）：**exit 0 全绿**。注：pnpm run 包装进程在本机卡死（已知坑），
  直跑 `apps/web-antd && ../../node_modules/.bin/vue-tsc --noEmit --skipLibCheck`。
- vitest（同因直跑 `./node_modules/.bin/vitest run --config vitest.ipd.config.mts`）：
  **3 文件 34 测试全绿**（workbench 15 + change 14 + product-bind 5），996ms。
- 首轮 2 failed 复盘：①workbench 导出面锁定哨兵捕获新增导出（有意变更，锁定清单更新）；
  ②product-bind 空串断言错——实测发现 http.ts 丢弃空值参数，即 bind 的「空串解绑」注释路径
  实际断链（后端 @RequestParam 必填→400），已修正注释并指向 unbindProductProject。

## 顺手修复（非本批范围但阻塞 check:type）

- `src/api/core/user.ts` IpdPerson 缺 `permissionCodes?: string[]`（af6d3ea 接通权限码时
  后端 PersonView 已实发，前端 /auth/me 消费侧类型未同步）→ 补齐，vue-tsc 清零。
  与 `src/api/ipd/auth.ts` 的 IpdPerson 定义保持一致。

## UI 入口注记（A13 unbind）

bind 与 unbind 当前均无产品列表 UI 交互入口（list/index.vue L146 仅只读展示「绑定项目」列）。
本批完成 API 层对称接线 + 契约测试；UI 入口建议 bind/unbind 一起产品化（避免单边按钮），
已在 A13 卡注记，不阻塞卡面闭环。

## HTTP+DB 真活（2026-09-24 21:50，ti-a13-open-live.txt + 浏览器三证）

- A10：`GET /workbench/my-initiated`（ipd-admin，data=[] 空态合理）；
  `GET /workbench/my-pending-approvals` 返回真数据 ≥2 条（删除申请 ADMIN_REVIEW）。
- A13：造数 `POST /requirement-changes`（requirement 2103330885699985410 / project 9140001，
  before/afterSnapshot 四维中文 key「范围/成本/时限/质量」必填——英文 key 会被 400 拒绝，
  已实测确认服务端 REQUIRED_DIMENSION_KEYS）→ 变更单 id=2103346457456201730（DRAFT，留库待后续拒签闭环）；
  `GET /requirement-changes/open?projectId=9140001` → code 0 rows=1（id/status 回读一致）；
  无 projectId → HTTP 400「缺少必需参数: projectId」（负例）。
- DB 回读：`ipd_dev.requirement_changes` 落行 2103346457456201730（SCOPE/DRAFT/create_by=900101）。

## 浏览器三证（Browser 子代理，2026-09-24 21:5x）

1. `batch1-workbench-initiated.png`：「我发起的」tab 选中（aria-selected=true），0 项空态文案正常；
2. `batch1-workbench-pending-approvals.png`：「待我审批」治理卡 **7 项真实待审**（products#3 等）；
3. `batch1-changes-open-alert.png`：项目 9140001 需求变更 Tab 页顶 warning Alert
   「本项目有 1 张未闭环需求变更单（P2-6.2：阶段推进门禁会拦截）#2103346457456201730（草稿）」，
   文案与实现一致；路由纠偏记录：详情路由为复数 `/ipd/projects/9140001/changes`。

## 途中发现（后端契约事实，未改后端）

- WorkbenchService taskType 常量**名为长形式值为短形式**（`TASK_TYPE_DELETION_REQUEST = "DELETION"`，
  同类 COEFFICIENT/LAUNCH_DATE），javadoc 写的长形式是误导；前端映射已按实测短形式对齐
  （index.vue MY_INITIATED_SOURCE_TEXT + workbench.ts 注释 + 测试 fixture）。
- `appendPlaceholderCards` 存在 status="COUNT" 占位卡路径（按 count 展开），前端 title fallback 可兼容。
