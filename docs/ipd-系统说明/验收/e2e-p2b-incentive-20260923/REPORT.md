# R179-P2 业务流真活验证 · 第二批（激励域：页33 津贴台账 + 页36 负反馈执行）

时间：2026-09-23 02:2x～02:4x · 会话：R179-P2b · 承接第一批（e2e-p2-business-flow-20260923，页31/35）

## 一、页33 津贴台账 `/ipd/incentive/allowance`（IpdAllowance）

三证：

| 证 | 内容 |
|---|---|
| 前端路由 | vue-router 实锤 `/ipd/incentive/allowance`，页面默认月份 2026-09 自动查询 |
| HTTP | `GET /api/v1/allowance/ledger?period=2026-09` → 200 code=0 n=1；`?period=2026-08` → 200 n=6；`GET /allowance/pending-stop?period=2026-09` → 200 n=0；`?period=2026-08` → 200 n=0 |
| DB | allowance_ledgers 共 7 行：2026-09 恰 1 行（2035405490451600833，900101@9140002，S，2100/2100，cap_applied=0）；2026-08 恰 6 行（id 2058038583361618616 / 2003869530705900493 / 2051034931163710772 / 2023114784044887947 / 9150301 / 9150302），HTTP 返回的 id/人员/项目/评级/金额/封顶与 DB **逐字段一致** |
| 浏览器 | 2026-09 渲染 1 行（900101/9140002/S/¥2,100.00/正常发放）+ 2026-08 渲染 6 行（两批生成时间 2026-09-07 21:55 ×4、2026-09-08 00:08 ×2 与 DB create_time 吻合）+ 待停发空态 |

截图：p2b-page33-allowance-2026-09.png、p2b-page33-allowance-2026-08.png

判定：**FULL PASS**。页面真实请求参数名是 `period`（网络面板抓包实锤），非源码签名里 grep 截断可见的形态；R179-P1 矩阵中该端点 ALIVE(400) 的成因是无参时走 `MissingServletRequestParameterException` handler 正确返回 400/10001「缺少必需参数」。

## 二、页36 负反馈执行 `/ipd/incentive/negative-feedback`（IpdNegativeFeedback）

三证：

| 证 | 内容 |
|---|---|
| 前端路由 | vue-router 实锤 `/ipd/incentive/negative-feedback`；页面「项目编号必填」与 Controller `@RequestParam Long projectId` 必填契约一致 |
| HTTP | `GET /api/v1/negative-feedbacks/by-project/9140001` → 200 n=1（LIFTED）；`GET /negative-feedbacks?projectId=9140002` → 200 n=1（DRAFT）；`GET /negative-feedbacks/by-severity/MEDIUM` → 200 n=2 |
| DB | negative_feedbacks 共 2 行：2097873827152293889（9140001，MISSED_MARKET_WINDOW，BOTH，9110003，STOP_ALLOWANCE，bonusDisqualify=1，tierDelta=-0.5，2026-09，LIFTED「R29蜂群审计-解除」）；2097880293535936513（9140002，REWORK_EXCEEDED，MARKET_PM，STOP+HALVE_ALLOWANCE，DRAFT）。HTTP 返回逐字段一致 |
| 浏览器 | 空态提示「请输入项目编号后查询」→ 填 9140001 查询 → 渲染 1 行：错过市场窗口 / 9110003（停发）/ —（—）/ 2026-09 / 取消资格 / **已解除**，与 DB LIFTED 态完全一致 |

截图：p2b-page36-nf-9140001.png

判定：**FULL PASS**。

## 三、发现的问题（登记不修，避免与兄弟会话撞号 + 不重启 16039）

### 问题1：方法级参数校验失败被兜底成 500/90001（advice 第 9 类漏网）

- 复现：`GET /api/v1/allowance/ledger?period=bad` → **HTTP 500 code=90001「系统内部错误」**（应为 400/10001 参数错误）。
- 铁证：`logs/sys-error.log` 2026-09-23 02:36:29 `[IPD] unexpected exception` 堆栈：`jakarta.validation.ConstraintViolationException: listLedger.period: period 必须 为 YYYY-MM`（经 MethodValidationInterceptor AOP 路径）——兄弟会话 02:36 也踩过同一坑（有堆栈无修复）。
- 根因：IpdServiceExceptionAdvice 已治理 8 类异常，但 `@RequestParam @NotBlank @Pattern` 走方法级校验抛 `ConstraintViolationException`（ruoyi 框架 AOP 代理路径，非 Spring 6.1 原生 HandlerMethodValidationException），无专属 handler → 落 `Exception.class` 兜底 500。
- 影响面：AllowanceLedgerController 3 个端点 + 全部用方法级校验注解的端点；前端无法区分「用户传错参」vs「系统故障」，误导告警。
- 修复建议：advice 补 `@ExceptionHandler(ConstraintViolationException.class)` → 400 + PARAM_INVALID + 首条 violation message（与 handleValidation 同构）。

### 问题2：运行中 JVM 日志冻结（环境异常，待查）

- 现象：PID 79717（本仓 ruoyi-admin.jar，Sep 22 20:56 启动）正常响应请求（09:34-09:4x 实测 200/401/500 均正常），但 `logs/sys-info.log`、`sys-error.log`、`sys-console.log`、`server-16039.log` 的 mtime 全部**冻结在 2026-09-23 02:39:42**（websocket 心跳日志停更 = 全量停更，非级别过滤）。
- 影响：依赖日志的审计链路（traceId 排查、ERROR 告警）已盲 7 小时+。
- 待查方向：logback 异步 appender 队列满静默丢弃 / 磁盘句柄异常 / 兄弟会话是否知情。

## 四、方法论沉淀（新增 2 条）

1. **IPD 页内 fetch 探针取 token**：IPD 会话 token 在 `sessionStorage['ruoyi-ipd.session'].accessToken`（不是 localStorage 的 `ruoyi-ipd-web-*-core-access`——那是框架管理端 JWT，对 /api/v1 无效）。
2. **抓真实请求参数名**：源码 grep 变量名可能被截断/与直觉不符（本例 `period` 非 `month`），浏览器 Network 面板（list_network_requests）抓页面已成功的请求 URL 是最快实锤。

## 五、P1-2 三证进度

累计 **8/49**（页31/35 + 本批 2 页 + 前批 4 页计数口径见 R179-P2 总台账）。

## 六、遗留

- 问题1/问题2 待 owner 拍板是否修复/排查（问题1 修复需重启 16039，影响兄弟会话在途验证）。
- 兄弟会话 02:11 起在途的资源 404 契约治理（IpdResources.requireOrNotFound）与本批零文件交集，继续避让。
