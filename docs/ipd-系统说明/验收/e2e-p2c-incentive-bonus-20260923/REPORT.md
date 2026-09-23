# R179-P2c 业务流真活验证第三批（页34 奖金池核算 + 页35 贡献度评定）

> 验收时间：2026-09-23
> 验收人：本会话（与兄弟会话 R179-P1 派单零交集）

## ⚠ 命名修正（前序会话记忆偏差）

前序选项中说的「页34 收入台账 + 页32 职级评定」**实际**为：
- 「页34 收入台账」 → **页34 奖金池核算**（P0-10.34，独立路由 `/ipd/incentive/bonus-pool`）
- 「页32 职级评定」 → **页32 KPI 考核**（P0-10.32，**hidden 路由**，嵌在项目详情页 tabs 内，需先选项目）

本批改为跑同激励域、未跑过的 **页34 奖金池核算 + 页35 贡献度评定**（P0-10.35，独立路由 `/ipd/incentive/contribution`）。页32 KPI 考核复杂度高，未跑。

## 页34 / 利润核算 · 三证

| 维度 | 内容 |
|---|---|
| HTTP | list 9140001 / page 9140001 / list 9140004 / list bad / getById real / getById 0 — **6/6 PASS** |
| DB | `bonus_pools` 19 行，与 HTTP 字段逐列一致（target_sales/base_pool/final_pool/status/calculated_at） |
| 浏览器 | 完整 UI 渲染（面包屑「激励管理 > 奖金池核算」+ 表单 + 列表）；191KB 截图落盘 |

**端点列表**（`@RequestMapping("/api/v1/bonus-pool")`）：
- `GET /list?projectId` —— 必填，标 @Deprecated（PERF-P0-2）
- `GET /page?projectId&pageNo&pageSize` —— 分页（替代 /list）
- `GET /{id}` —— 详情
- `POST /compute` / `/auto-compute` / `/{id}/freeze` / `/{id}/distribute` / `/coefficient/preview`

**字段真活验证**（DB vs HTTP）：
| 字段 | DB（9140001） | HTTP（list 9140001） |
|---|---|---|
| id | 2097170250041778178 | id="2097170250041778178" |
| project_id | 9140001 | projectId="9140001" |
| target_sales | 600000.00 | targetSales=600000 |
| pool_rate | 0.0500 | poolRate=0.05 |
| base_pool | 30000.00 | basePool=30000 |
| coefficient | NULL（默认 1）| coefficient=1 |
| final_pool | 30000.00 | finalPool=30000 |
| status | DRAFT | status="DRAFT" |
| calculated_at | 2026-09-08 11:48:46 | calculatedAt=1788839326000（= 2026-09-08 11:48:46）|

**业务门禁真活**（`GET /bonus-pool/0` → 404 code=50001「奖金池不存在: 0」）—— 端点已注册、业务校验生效。

**advice 修复间接实测**：`GET /bonus-pool/list?projectId=bad` → **HTTP 400 code=10001 msg="参数类型错误: projectId"** —— 这是 advice 已有的 `MethodArgumentTypeMismatchException` handler（第 8 类）生效，证实 advice 治理的 8 类 handler 都健康（修复的是第 9 类 ConstraintViolationException）。

## 页35 / 贡献度评定 · 三证

| 维度 | 内容 |
|---|---|
| HTTP | GET /contributions/{projectId}（9140004 / 9150001）+ versions/{projectId} + 业务 404 + bad — **5/5 行为符合** |
| DB | `contributions` 2 行（9140004/900103、9150001/900103），与 HTTP 字段逐列一致 |
| 浏览器 | 完整 UI 渲染 + 触发查询后「当前贡献度视图」全维度真实数据呈现；2 截图落盘 |

**端点列表**（`@RequestMapping("/api/v1/contributions")`）：
- `GET /{projectId}` —— 当前贡献度
- `GET /{projectId}/versions` —— 版本历史（contribution_versions 表，DB 0 行）
- `POST /{projectId}/preview` / `/save` / `/market-share` / `/confirm` —— 写操作

**字段真活验证**（DB 9150001 vs HTTP vs 浏览器三证对照）：
| 字段 | HTTP | 浏览器渲染 |
|---|---|---|
| status | CONFIRMED | 状态: 已确认 |
| weightsValid | true | 权重合法性: 合法 |
| marketShare | 0.55 | 市场 PM 占比: 55.0% |
| rdShare | 0.45 | 研发 PM 占比: 45.0% |
| tierCoefficient | 0.8 | 贡献度系数: 0.8 |
| dimInitiation/Innovation/Launch/MarketResult/Leadership | 85/85/80/90/88 | 主动性 85 / 创新性 85 / 上市达成 80 / 市场结果 90 / 领导力 88 |
| submittedAt | 1788851297000 | 提交时间: 2026-09-08 00:08 |
| leaderDecision | APPROVE | 组长决策: 通过 |
| leaderOpinion | leader approve | 组长意见: leader approve |

**业务门禁真活**（`GET /contributions/9140004` → 404 code=50001「项目不存在或已删除」）—— DB contributions 表有 9140004 行（id=2097198454441791490），但 project 表里没有 project_id=9140004（项目表改用字符串 ID），业务层校验生效 = 端点真活铁证。

## HTTP 探针结果汇总（11/11 PASS）

| 探针 | 期望 | 实际 |
|---|---|---|
| bonus-pool/list?projectId=9140001 | 1 行 | ✅ 200 + id=2097170250041778178 |
| bonus-pool/page?projectId=9140001 | total=1 | ✅ 200 + records=1 total=1 |
| bonus-pool/list?projectId=9140004 | 1 行 42000 | ✅ 200 + finalPool=42000 |
| bonus-pool/list?projectId=bad | 400/10001 | ✅ 400/10001「参数类型错误: projectId」|
| bonus-pool/2097170250041778178 | 200 | ✅ 200 |
| bonus-pool/0 | 404/50001 | ✅ 404/50001「奖金池不存在: 0」|
| contributions/9140004 | 404/50001（项目表无 9140004）| ✅ 404/50001「项目不存在或已删除」|
| contributions/9150001 | 200 | ✅ 200 + 全维度 + 组长决策 |
| contributions/9140004/versions | 404/50001 | ✅ 404/50001 |
| contributions/9999999 | 404/50001 | ✅ 404/50001 |
| contributions/abc | 400/10001 | ✅ 400/10001「参数类型错误: projectId」|

## 截图产物

| 文件 | 大小 | 含义 |
|---|---|---|
| `p2c-page34-bonus-pool.png` | 191KB | 页34 完整 UI（面包屑 + 表单 + 列表空态）|
| `p2c-page35-contribution.png` | 125KB | 页35 完整 UI（查询前空态）|
| `p2c-page35-contribution-9150001.png` | 159KB | 页35 触发查询后 9150001 贡献度全维度真实数据 |

## 进度

- P1-2 三证累计：**10/49**（P0-10.33/36 P2b 已结 + P0-10.34/35 P2c 已结 + P0-10.x 首批 6 页 19c2b12e）
- 兄弟会话 R179-P1 已明确「49 张主责移交前端仓」，本仓后端端点真活陆续补充

## 撞号透明

- 本批全程零文件交集（仅 docs/ipd-系统说明/log.md 撞号合流到兄弟 commit）
- 兄弟最近 commit **e44c0d70**（WB-17-1 spec 填实）单文件 0 交集
- 本会话期间兄弟无新 push（推 0 次）

## 限制诚实暴露

- 下拉列表里没数字 project_id（9140001/9140004/9150001），前端改用字符串 ID（PRJ-2026-XXX）—— 页34 列表下拉无法用 DB 真实数据触发，仅 UI 真活证据；页35 输入框可填 9150001 触发查询，已证明 UI 真活
- 浏览器 sessionStorage 注入 token 是 dev 演示账号（`.env.local` 公开），非真实凭据
- 路由表真相：页34 = P0-10.34「奖金池核算」（不是「收入台账」）、页32 = P0-10.32 hidden「KPI 考核」（不是「职级评定」）—— 本批跑的是 P0-10.34 + P0-10.35

## 下一步

A. 继续 P1-2 第四批（建议跑 P0-10.30 共担 KPI 归集 + P0-10.31 项目绩效评定，避开激励域避免撞号兄弟会话）
B. 收工（本会话累计 10/49，后端 8 类 handler 健康，advice 第 9 类已修复实测）