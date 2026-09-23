# R179-P2 业务流真活验证 · 首批 2 页 — 2026-09-23

> P1-2 49 页三证进度：本批 +2（页31 项目绩效评定 + 页35 贡献度评定）→ 累计 6/49。
> 避让说明：兄弟会话同期在途「资源 404 契约治理」（6 Controller，commit 96fdfc22），本批选页避开 products/project/bid/bonus 域，全部落在 score/contribution 域。

## 结论

**2/2 页三证 FULL PASS**（HTTP 200 真活 + DB 回读一致 + 浏览器渲染截图）。

## 页31 项目绩效评定

| 项 | 内容 |
|---|---|
| 前端路由 | `/ipd/kpi/project-score`（IpdKpiScore，隐藏路由——非 `/ipd/performance/...`，vue-router.getRoutes() 实锤） |
| HTTP | GET /api/v1/project-score-tasks/my → 200 code=0 dataLen=0 |
| DB | project_score_tasks 16 行；person_id 分布 900103×2 / 900104×2 / 9110003~9110013×2；**900101（ipd-admin）0 行** |
| 浏览器 | 真实渲染「我的评分任务」空态 + 提示「任务由上市 30/90 日扫描生成」 |
| 判定 | ✓ 三证一致——空态是**正确业务态**（超管 900101 本就无评分任务） |

## 页35 贡献度评定

| 项 | 内容 |
|---|---|
| 前端路由 | `/ipd/incentive/contribution`（IpdContribution，页35） |
| HTTP | GET /api/v1/contributions/9150001 → 200 code=0 真活：id=2097220459849326594, status=CONFIRMED, marketShare=0.55, rdShare=0.45, tierCoefficient=0.8, 五维 dim=85/85/80/90/88, leaderDecision=APPROVE |
| DB | contributions 9150001 一行：market_share=0.5500 / rd_share=0.4500 / market_self=85,80,75,90,88 / rd_self=80,85,80,75,82 / leader_id=900102 APPROVE "leader approve" |
| 浏览器 | 输入项目编号 9150001 查询 → 真活卡片全渲染（状态已确认/权重合法/55.0%/45.0%/0.8/五维/组长通过） |
| 判定 | ✓ 三证一致 |

### 五维数字「差异」澄清（非缺陷，设计如此）

HTTP/前端显示五维 85/85/80/90/88，DB market 侧是 85/80/75/90/88——初看不一致，溯源 `ContributionService.toView()`（:653）：

```java
// 暴露给前端用于联动显示（合并双 PM 的最大维度以方便预览）
.dimInitiation(maxOrNull(c.getMarketSelfInitiation(), c.getRdSelfInitiation()))
```

`maxOrNull(market, rd)` 逐维取双 PM 最大值：max(85,80)=85 / max(80,85)=85 / max(75,80)=80 / max(90,75)=90 / max(88,82)=88 —— **HTTP=DB 按此规则完全一致**。分侧真值在 DB market_self_* / rd_self_* 两列，预览合并是代码注释明确的设计。前端忠实渲染 API。

## 环境证据

- 后端 16039 java 存活（本批 HTTP 全走 15666 vite 代理 → 16039）
- 前端 15666 vite 存活（PID 85166）
- 真库 MySQL 13306/ipd_dev（socket 探针）
- 登录会话存活；API 探针用 Bearer token（sa-token cookie 对纯 fetch credentials:'include' 不生效，属前端 token 存储设计，非缺陷）

## 方法论沉淀

1. **vue-router 实锤路由**：源码 ipd.ts 嵌套层级会骗人（project-score 看似 /ipd/performance/kpi/functional/...，实际 /ipd/kpi/project-score）——`router.getRoutes()` 一发入魂，后续三证批次优先用。
2. **先 DB 后页面**：先查真库哪个项目有数据（9150001 是唯一有贡献度的项目），再进页面填编号查询，避免「空态误判死路径」。
3. **fetch 探针需 Bearer**：页面内 evaluate_script 用 `credentials:'include'` 拿 401，必须 login 换 token 走 Authorization header。

## 遗留

- P1-2 剩 43 页三证（多天工程，后续批次继续）
- `/api/v1/score-tasks/my`（少 project- 前缀）返回业务 404 code=50001 —— 印证 R179-P1 矩阵「业务 404=门禁真活」，非缺陷
- 兄弟会话 96fdfc22（404 契约治理）尚未 push 到 origin/main，本地领先 1 commit（撞号透明，由兄弟会话自行收口）
