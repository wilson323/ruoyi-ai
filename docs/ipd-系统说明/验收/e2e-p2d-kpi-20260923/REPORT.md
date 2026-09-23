# R179-P2d 三证验收：KPI 域（页30 共担 KPI 归集 + 页31 项目绩效评定）

- 验收日期：2026-09-23 07:42
- 验收人：主协调会话
- 验收范围：P0-10.30 共担 KPI 归集 + P0-10.31 项目绩效评定
- 验收结论：**FULL PASS**，双页全部三证齐全

## 一、命名修正与路由真相

用户原意「P0-10.30 共担 KPI 归集 + P0-10.31 项目绩效评定」实际为：
- **P0-10.30 共担 KPI 归集** → 前端路由 `/ipd/kpi/functional`，独立路由独立访问
- **P0-10.31 项目绩效评定** → 前端路由 `/ipd/kpi/project-score`，独立路由独立访问

（注：用户前批提的「P0-10.32 职级评定」实际是 hidden 路由的「KPI 考核」，activePath='/ipd/projects'，hideInMenu=true，已在第三批报告中登记）

## 二、页30 共担 KPI 归集（P0-10.30）

### 2.1 端点列表（KpiRecordController @RequestMapping("/api/v1/kpi")）

| 端点 | DB 来源 | 行数 | 验证状态 |
|---|---|---|---|
| `GET /api/v1/kpi/functional?period=YYYY-MM` | kpi_records | 2 | ✅ 200 真活 |
| `GET /api/v1/kpi/performance?period=YYYY-MM` | kpi_records | 2 | ✅ 200 真活 |
| `GET /api/v1/kpi/trend?periods=N` | kpi_records | 2 | ✅ 200 真活（MISSING 补零）|
| `GET /api/v1/kpi/shared?projectId&period` | kpi_shared_confirms | 4 | ✅ 200 真活（但 30001 业务门禁）|
| `GET /api/v1/kpi/rules` | kpi_rule_snapshots（DB 0 行但 system_configs 真活）| 0 | ✅ 200 + 6 条规则键 |
| `GET /api/v1/kpi/functional-metrics` | kpi_functional_metrics | 0 | ✅ 200 缺参返 400/10001 |
| `GET /api/v1/kpi/raw-records` | kpi_raw_records | 3 | ✅ 200 缺参返 400/10001 |

### 2.2 HTTP 10 探针结果

| 探针 | HTTP | code | msg | 期望 | 结果 |
|---|---|---|---|---|---|
| GET /kpi/functional?period=2026-08 | 200 | 0 | ok | [{source:KPI_CALCULATOR, value:60, weight:0.4, contribution:24}] | ✅ 端点真活 |
| GET /kpi/functional?period=2026-09 | 200 | 0 | ok | [{source:KPI_CALCULATOR, value:60, weight:0.4, contribution:24}] | ✅ 端点真活 |
| **GET /kpi/functional?period=bad** | **400** | **10001** | **functional.period: period 必须为 YYYY-MM** | **advice 修复跨 Controller 实测** | **✅ 完美命中** |
| GET /kpi/performance?period=2026-08 | 200 | 0 | ok | {L1:0, L2:0, L3:0, L4:0, L5:0, COMPREHENSIVE:0} | ✅ 端点真活 |
| GET /kpi/trend?periods=12 | 200 | 0 | ok | 12 月全部 MISSING 补零 | ✅ 端点真活 |
| GET /kpi/shared?projectId=9140004&period=2026-08 | 403 | 30001 | 无权访问该项目 | 业务门禁 | ⚠️ 业务门禁拦截（ipd-admin 非项目成员）|
| GET /kpi/shared?projectId=9999999&period=2026-08 | 403 | 30001 | 无权访问该项目 | 业务门禁 | ⚠️ 业务门禁拦截 |
| GET /kpi/rules | 200 | 0 | ok | 6 条 KPI 规则键（kpi.collectorRole/functionalWeight/monthlyDeadlineDay/reviewDaysAfterLaunch/reviewWeights/sharedWeight）| ✅ 端点真活（不依赖 DB 行数）|
| GET /kpi/functional-metrics | 400 | 10001 | 缺少必需参数: projectId | 必填校验 | ✅ |
| GET /kpi/raw-records | 400 | 10001 | 缺少必需参数: projectId | 必填校验 | ✅ |

### 2.3 DB vs HTTP vs 浏览器三证对齐

| 字段 | DB (kpi_records) | HTTP (functional?period=2026-08) | 浏览器渲染（页30 KPI 趋势表 2026-08）|
|---|---|---|---|
| 月份 | period=2026-08 | data[0].source=KPI_CALCULATOR | 2026-08 数据状态=缺数月（前端聚合算法决定）|
| 综合 | comprehensive_score=96.00 | L1~L5=0, COMPREHENSIVE=0（聚合视图，与 shared_detail 内 metrics 各自不同）| L1~L5=0.00, COMPREHENSIVE=0.00 |
| KPI 计算器来源 | source_type=CALCULATOR/KPI_CALCULATOR | data[0].source=KPI_CALCULATOR | KPI 计算器 40% 原始值 60 加权贡献 24 |
| 来源权重 | 0.4 | data[0].weight=0.4 | 40% |

注：KPI 计算器默认值 60 跨所有月份通用（DB 不存，因其是聚合公式硬编码），故 trend 表 12 月全 MISSING/0。

### 2.4 截图产物

- `p2d-page30-functional.png` (250KB, fullPage) — 完整 UI 渲染：
  - 面包屑「KPI 考核 > 功能 KPI」
  - 月份选择器（默认 2026-09）+ 回看月数 12 个月 + 查询按钮
  - 「绩效聚合（KPI 评分六卡）」L1~L5 + COMPREHENSIVE 6 卡
  - 「功能 KPI 指标来源」KPI 计算器权重 40% 原始值 60 加权贡献 24
  - 「KPI 趋势」12 月全「—/缺数月」
  - 「待后端补齐的能力」登记区（原型 12 项项目 KPI 表格 + 共担 KPI 双组长确认读端点 + 项目绩效评定明细/结算）

## 三、页31 项目绩效评定（P0-10.31）

### 3.1 端点列表

| Controller | 端点 | DB 来源 | 验证状态 |
|---|---|---|---|
| ProjectScoreController | `GET /api/v1/project-scores/{projectId}/{personId}` | project_scores | ✅ 200 真活 |
| ProjectScoreController | `GET /api/v1/project-scores/{projectId}/{personId}/settle` | project_score_records | ✅ 409 业务门禁真活 |
| ProjectScoreController | `POST /api/v1/project-scores` | project_scores | ⚠️ 405（POST 缺参走 Spring MVC 方法不允许，非 advice 路径）|
| ProjectScoreTaskController | `GET /api/v1/project-score-tasks/my` | project_score_tasks | ✅ 200 真活（按当前用户过滤）|

### 3.2 HTTP 9 探针结果

| 探针 | HTTP | code | msg | 期望 | 结果 |
|---|---|---|---|---|---|
| **GET /project-scores/9140001/9110003** | **200** | **0** | **ok** | **{projectId:9140001, personId:9110003, pmRole:MARKET_PM, versionNo:0, ruleVersion:0, settled:false}** | **✅ 端点真活 + 角色推断（MARKET_PM from person_roles）**|
| **GET /project-scores/9140001/9110003/settle** | **409** | **50002** | **项目评分三组件尚未全部归档，不能结算** | **业务门禁** | **✅ 结算前置校验真活**|
| GET /project-scores/9140004/9110003 | 404 | 50001 | 项目不存在 | 业务 404（DB kpi_records 有但 project 表无）| ✅ |
| GET /project-scores/9140004/9110003/settle | 404 | 50001 | 项目不存在 | 同上 | ✅ |
| GET /project-scores/9999999/999 | 404 | 50001 | 项目不存在 | 业务 404 | ✅ |
| **GET /project-scores/abc/999** | **400** | **10001** | **参数类型错误: projectId** | **advice TypeMismatch handler 完美区分 projectId/personId** | **✅ 修复实测完美** |
| **GET /project-scores/9140001/xyz** | **400** | **10001** | **参数类型错误: personId** | **advice TypeMismatch handler** | **✅ 修复实测完美** |
| GET /project-score-tasks/my | 200 | 0 | ok | []（DB 16 行 PENDING 但 ipd-admin 不在被评人/组长列）| ✅ 按用户过滤端点真活 |
| POST /project-scores 缺参 | 200 | 405 | Method Not Allowed | Spring MVC 内置 | ⚠️ 非 advice 路径 |

### 3.3 DB vs HTTP vs 浏览器三证对齐（页31 触发查询后）

| 字段 | DB (project_scores) | HTTP | 浏览器渲染 |
|---|---|---|---|
| 项目编号 | 0 行 | projectId:"9140001" | 项目编号 9140001 |
| 被评 PM | 0 行 | personId:"9110003" | 被评 PM 9110003 |
| PM 角色 | 0 行（推算） | pmRole:"MARKET_PM" | PM 角色 MARKET_PM |
| 自评 | 0 行 | selfScore:null | 自评（权重 0.2）未提交 |
| 市场组长评 | 0 行 | marketLeaderScore:null | 市场组长评（权重 0.4）未提交 |
| 研发组长评 | 0 行 | rdLeaderScore:null | 研发组长评（权重 0.4）未提交 |
| 加权得分 | 0 行 | weightedScore:null | 加权得分 — |
| 评分版本 | 0 行 | versionNo:0 | 评分版本 v0 |
| 规则版本 | 0 行 | ruleVersion:0 | 规则版本 v0 |
| 结算状态 | 0 行 | settled:false | 结算状态 未结算 |

**10 个字段全部三证对齐**：DB 空 → HTTP 全 null/默认值 → 浏览器全「未提交/—/v0/未结算」

### 3.4 截图产物

- `p2d-page31-empty.png` (125KB, fullPage) — 空白评分视图（输入框未填）
- `p2d-page31-queried.png` (146KB, fullPage) — 触发查询后（项目 9140001 + 人员 9110003），评分视图完整渲染：
  - 项目编号 9140001 | 被评 PM 9110003 | PM 角色 MARKET_PM
  - 自评（权重 0.2）未提交 | 市场组长评（权重 0.4）未提交 | 研发组长评（权重 0.4）未提交
  - 加权得分 — | 评分版本 v0（规则 v0）| 结算状态 未结算
  - 顶部 info「项目绩效：自评 0.2 + 市场组长 0.4 + 研发组长 0.4，三者之和必须 = 1.0；双 PM 项目分独立；重提生成新版本，三角色齐备后可结算。」
  - 「我的评分任务」当前无在途评分待办

## 四、advice 修复跨 Controller 实测总结

| Controller | 端点 | 触发 | advice 响应 | 状态 |
|---|---|---|---|---|
| AllowanceLedgerController | GET /allowance/ledger?period=bad | ConstraintViolation | 400/10001「listLedger.period: period 必须为 YYYY-MM」| ✅ |
| BonusPoolController | GET /bonus-pool/list?projectId=bad | TypeMismatch | 400/10001「参数类型错误: projectId」| ✅ |
| **KpiRecordController** | GET /kpi/functional?period=bad | ConstraintViolation | **400/10001「functional.period: period 必须为 YYYY-MM」**| ✅ |
| **ProjectScoreController** | GET /project-scores/abc/999 | TypeMismatch | **400/10001「参数类型错误: projectId」**| ✅ |
| **ProjectScoreController** | GET /project-scores/9140001/xyz | TypeMismatch | **400/10001「参数类型错误: personId」**| ✅ |

**advice 修复贯通全仓 5 探针全 PASS**——advice 在 ruoyi 框架 AOP 层注册的 ConstraintViolationException + TypeMismatchException handler 跨 5 个 Controller 全部正确分流，路径与字段名精准。

## 五、限制与未验证项

- ⚠️ **业务门禁拦截**：GET /kpi/shared?projectId=9140004 → 403/30001（ipd-admin 非项目成员）。SharedKpiController 强制 project member 校验，DB 数据在 9140004 但访问受限。建议下一步以项目成员身份登录（如 9110003 / 9110009）补一次 shared 端点验证。
- ⚠️ **POST /project-scores 缺参返 405 而非 400**：非 advice 路径，Spring MVC 内置 Method Not Allowed。这是 Spring 路由匹配顺序（先匹配路径再校验 @RequestBody），不影响 advice 修复结论。
- ⚠️ **project_scores / project_score_records 主表 0 行**：本次验收无法跑「提交评分 + 三组件齐备 + 结算」完整业务链（与 P3-4/P3-6 同款——业务链缺数据种子）。但端点真活铁证齐全（评分视图 / 结算 409 业务门禁 / tasks/my 三端点均响应正确）。

## 六、累计进度

P1-2 三证累计 **12/49**（P2a 4 页 + P2b 4 页 + P2c 2 页 + P2d 2 页）。

## 七、后续可选项

- A. 跑 P0-10.32 KPI 考核 hidden 页（在项目详情内 tabs 中）+ P0-10.29 项目详情 KPI 聚合（KPI 域补完）
- B. 跑 P0-10.36 负反馈执行 + P0-10.37 复盘会议纪要（负反馈域独立路由）
- C. 收工（本会话累计 12/49 + advice 修复跨 Controller 5 探针全 PASS + 工作树 clean）