# R194 §A7 前端 + 部署整合调研（2026-09-23）

> **撰写者**：ioedream-evolver（R193 §四 + §五 前端+真库+部署阻塞的"41 页排序 + 部署物补齐"整合调研）
> **归属**：IPD 治理轮 R194 §A7，docs-only，撞车 0 严守
> **触发刷新**：owner 拍板 P0-2/4/7/8/10/11/12 任一项，或前端 owner 派单节奏变化，或 DBA 窗口更新

---

## §一 任务背景 — R193 §四 + §五 调研依据

R193（`docs/ipd-系统说明/R193-生产就绪全部待办-20260923.md`）完成"到生产就绪"全部待办大盘点，遗留两大阻塞带未做优先级排序与补齐方案：

| 阻塞带 | R193 §位 | 现状要点 |
|---|---|---|
| **§四 前端生产就绪** | 49 页三证 8/49，剩 41 页；权限三套体系（meta.access / meta.authority / v-access:code）仅文档化未强制收敛 | 41 页未排序 + 三套边界未拍板 |
| **§五 真库 + 部署** | P0-12 Redis 多实例 26h；DBA 窗口 3 项（P0-4/P0-10/DB-02）；docker-compose 端口漂移 23306 vs 13306；字符集 P3-LOW；nginx / 前端 Dockerfile 缺 | 部署物缺 + 端口漂移未对齐 |

**R194 §A7 调研目标**：① 把 41 页按业务核心度 + owner 边界 + 上下游依赖排成 High/Medium/Low 三档；② 把部署物补齐方案细化到工作项；③ 整合 R189 P0-12 Redis 调研结论到本盘可派单级。

---

## §二 前端 49 页三证 8/49 现状

**已落 8 张（兄弟会话累计）**：页 31 / 33 / 35 / 36 等，详见 `docs/ipd-系统说明/验收/e2e-p2b-incentive-20260923/`；外加 R179-P0 已落的 P0-10.1 登录 + P0-10.2 强制改密 + projects / requirements 三件套。

**剩 41 张**：按前端仓路由表 `apps/web-antd/src/router/routes/modules/ipd.ts` 八个业务域分布：

| 业务域 | 待三证页数 | 业务核心度 | 上下游关键依赖 |
|---|---|---|---|
| 工作台（workbench） | 3（detail/my-initiated/overdue）| M | summary / overdue API |
| 项目域（projects） | 9（create + detail×8：overview/flow/gates/changes/kpi/documents/audit/circle）| **H** | P0-2 KPI / P3 阶段验收 / P1-6 评审要素 |
| 需求域（requirements） | 2（create + requirement-detail）| **H** | 需求池实体 |
| 产品域（products） | 3（products/edit/workspace）| M | PRODUCT 模块 |
| 招标域（bids） | 4（bids/create/respond/select）| **H** | BidScanService R25 已实装 |
| 激励域（kpi） | 8（kpi/project-score/raw-records/shared/contribution/bonus-pool/allowance/negative-feedback）| **H** | P0-2 KPI 公式 + R25 实装 |
| 协作域（collaboration） | 5（handover/cert-templates/deletion×2/change/ai-docs）| M | HANDOVER_CANCEL 删 / 附件鉴权 P1-4 |
| 管理域（admin） | 9（audit/logs + admin×8：gate-elements/identity-sync/org/sop-template/business-config/config/ai-models/handover/gate-detail）| L | 运维 + 业务配置（v-access:code）|

---

## §三 前端 41 页优先级排序（High/Medium/Low 三档）

按 **业务核心度 × 上下游依赖 × 工作量 × 撞车风险** 综合排序，结果如下（owner 派单建议列在最后）：

### 3.1 High 档（23 页，必跑）

| 序 | 页面 | 卡 | 依赖 P0 | 工作量 | 撞车风险 |
|---|---|---|---|---|---|
| H1 | project/detail/overview | P0-10.x | 无 | 0.5h | 低 |
| H2 | project/detail/flow | P0-10.x | 无 | 0.5h | 低 |
| H3 | project/detail/gates | P0-10.x | P1-6 评审要素 | 1h | **中**（依赖 P1-6 owner inreview）|
| H4 | project/detail/changes | P0-10.x | 无 | 1h | 低 |
| H5 | project/detail/kpi | P0-10.x | **P0-2** KPI 公式 | 2h | **高**（依赖业务 owner 派单）|
| H6 | project/detail/documents | P0-10.x | 无 | 1h | 低 |
| H7 | project/detail/audit | P0-10.x | 无 | 1h | 低 |
| H8 | project/detail/circle | P0-10.x | 无 | 1h | 低 |
| H9 | projects/create | P0-10.x | 无 | 1h | 低 |
| H10 | requirements/create | P0-10.x | 无 | 1h | 低 |
| H11 | requirement-detail | P0-10.x | 需求池实体 | 1h | 低 |
| H12-H15 | bids / bid-create / bid-respond / bid-select | P0-10.x | BidScanService R25 已落 | 4h | 低 |
| H16 | kpi（公式列表）| P0-10.x | **P0-2** | 2h | **高** |
| H17 | kpi/project-score | P0-10.x | **P0-2** | 1.5h | **高** |
| H18 | kpi/raw-records | P0-10.x | R25 已落 | 1h | 低 |
| H19 | kpi/shared | P0-10.x | R25 已落 | 1h | 低 |
| H20 | contribution | P0-10.x | R25 已落 | 1h | 低 |
| H21 | bonus-pool | P0-10.x | R25 已落 | 1h | 低 |
| H22 | allowance | P0-10.x | R25 §A1 AllowanceService 拍板 | 1.5h | **中**（待 owner 拍板删/留）|
| H23 | negative-feedback | P0-10.x | R25 已落 | 1h | 低 |

### 3.2 Medium 档（11 页，可穿插）

| 序 | 页面 | 工作量 | 备注 |
|---|---|---|---|
| M1 | workbench/detail | 1h | summary API 已就位 |
| M2 | workbench/my-initiated | 1h | — |
| M3 | workbench/overdue | 1h | 依赖 P3-2.3 催办 |
| M4 | products | 1h | 产品域 |
| M5 | product/edit | 1h | — |
| M6 | product/workspace | 1.5h | — |
| M7 | handover | 1h | HANDOVER_CANCEL R186 已删 |
| M8 | cert/templates | 1h | — |
| M9 | deletion/list | 1h | — |
| M10 | deletion/audit | 1h | — |
| M11 | change | 1h | — |

### 3.3 Low 档（7 页，可最后跑）

| 序 | 页面 | 工作量 | 备注 |
|---|---|---|---|
| L1 | ai-docs | 1h | AI 业务待 owner 拍板 |
| L2 | audit/logs | 1h | 运维域 |
| L3-L9 | admin × 7（gate-elements/identity-sync/org/sop-template/business-config/config/ai-models/handover/gate-detail）| 7h | 业务配置 + 运维，撞车风险极低 |

**合计估算**：High 23h + Medium 11h + Low 8h = **42h ≈ 5.5 人日**，L2 模板化 10-15 张/会话 → **3 会话可跑完**。

**owner 派单建议**：H5/H16/H17（P0-2 KPI 联调）必须由前端 owner + 业务 owner 联合派单；H22 待 owner 拍板 AllowanceService 后再启动；其余 High 档前端 owner 单方派单即可。

---

## §四 前端权限三套体系收敛（meta.access / meta.authority / v-access:code）

按 R185 §2.2 + R186 §八 文档化（`docs/ipd-系统说明/权限三套体系边界-20260923.md`），三套边界如下：

| 体系 | 性质 | 边界 | 现状 |
|---|---|---|---|
| **meta.access** | 装饰性实锤（R182 探针验证）| 仅 UI 控件显隐 | 已用，**作为单一事实源候选** |
| **meta.authority** | 旧路由元信息遗留 | 路由级权限（废弃）| 历史包袱，**收敛后下线** |
| **v-access:code** | 新指令式权限码 | 按钮 / 字段级 | 设计意图单一事实源 |

**M-Root-12**（多套闸不同步）已落档 BCP-Registry §三十，**仅文档化收口未强制收敛**。

**收敛建议**：① **meta.access 作 v-access:code 上层代理**（路由 meta 中加 `accessCodes: []`，v-access 指令读这个）；② **meta.authority 标记 `@deprecated`**（R185+ 后续轮次下线）；③ **新增 `check-permission-single-source.sh` 门禁**（evolver 自主，docs-only，M-Root-4 范畴）。

**owner 派板点**：前端 owner 确认收敛顺序（先收 meta.authority 再统一到 v-access:code），不阻塞前端三证推进。

---

## §五 部署物补齐

### 5.1 后端 Dockerfile（✅ 已 done）

`ruoyi-admin/Dockerfile` R19/R21 已实装，无需重做。

### 5.2 前端 Dockerfile（❌ 缺）

建议骨架（前端 owner + DevOps 联合实装）：

```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --registry=https://registry.npmmirror.com
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

工作量：**4h**（含本地构建测试）。

### 5.3 docker-compose 端口漂移（23306 vs 13306）

**问题**：dev 用 13306 真库，docker-compose 写 23306 未对齐。

**修复**：`docker-compose.yml` 把 `MYSQL_PORT` 改回 `13306`（dev / staging / prod 三套各自独立 compose 文件，统一端口前缀约定 `1XXXX`）；前端 `VITE_API_BASE_URL` 显式指向 `http://127.0.0.1:16039`（不走 compose 内部网络）。

工作量：**2h**（改 3 个 compose 文件 + 1 个 vite env）。

### 5.4 nginx 配置（❌ 缺）

建议骨架（前端仓 + DevOps 联合）：

```nginx
server {
  listen 80;
  server_name _;
  root /usr/share/nginx/html;
  index index.html;
  location / {
    try_files $uri $uri/ /index.html;
  }
  location /api/ {
    proxy_pass http://backend:16039/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
  }
}
```

工作量：**2h**（含 gzip / 缓存策略 + 与后端联调）。

### 5.5 CI 流水线（❌ 缺）

**推荐骨架**（基于 `gitee go` 或 `GitHub Actions`）：

| 阶段 | 动作 | 时长 |
|---|---|---|
| 1. checkout | 拉代码 | 10s |
| 2. 后端编译 | `mvn -o package -DskipTests` | 3-5min |
| 3. 前端构建 | `npm ci && npm run build` | 1-2min |
| 4. 测试 | `mvn -o test` | 2-3min |
| 5. 镜像打 tag | `docker build -t ipd:$COMMIT .` | 1min |
| 6. 推送 | `docker push`（内部 registry）| 1min |

工作量：**8h**（含 4 个 pipeline：main / release / hotfix / feature）。

---

## §六 真库 DDL apply 阻塞（DBA 窗口 3 项）

| # | 项 | 阻塞 | 推进 | 拍板 |
|---|---|---|---|---|
| **P0-4** | 单数表 RENAME DDL | commit `fc322a46` 已落，未 apply | DBA 维护窗口执行 `ALTER TABLE` | DBA 单方 |
| **P0-10** | c-batch-4 DDL | 业务 owner 决策 apply 时机 | 业务 owner + DBA 联合 | 业务 owner |
| **DB-02** | 批量创建 16 域 | 看板 inreview，DBA 排期 | DBA 窗口批量执行 | DBA 单方 |

**P0-4 + P0-10 + DB-02 合并窗口**：建议合并为一次 DBA 维护窗口（2-3h），降低多次切库风险。

**撞车 0 承诺**：本仓不主动跑 SQL，DBA 维护窗口由 DBA 单方执行，本仓仅落档记录。

---

## §七 Redis 多实例切流 P0-12 + 字符集 P3-LOW

### 7.1 P0-12 Redis 多实例切流（基于 R189 调研）

| 子任务 | 人时 | 风险 |
|---|---|---|
| HrTokenClient 切 Redis（key = `ipd:hr:token`）| 4h | 低（接口已隔离）|
| BusinessConfigServiceImpl pub/sub（channel = `ipd:business-config:invalidate`）| 8h | 中（SCOPE_CACHE 模糊匹配边界）|
| application.yml 新增 `spring.data.redis` + `redisson.config` | 2h | 低 |
| Testcontainers + 多实例 IT | 8h | 中 |
| 联调 + 看板同步 | 4h | 低 |
| **合计** | **26h ≈ 3.5 人日** | — |

**pom 依赖**：**0 项新增**（`redisson-spring-boot-starter` 3.51.0 + `lock4j-redisson-spring-boot-starter` 已就位，`ruoyi-ipd/pom.xml` 已依赖 `ruoyi-common-redis`）。

**owner 拍板点**：① DBA 部署 Redis（dev/staging/prod 三套）；② evolver 启动 26h 实装（owner 在场）。

### 7.2 P3-LOW 字符集遗留

sys_user ↔ persons 字符集不一致（JOIN 报 1267 不阻塞推荐路径），**已登记不阻塞**。

**A1 推荐方案**：persons → utf8mb4_0900_ai_ci（与 sys_user 对齐），DDL 1 行：

```sql
ALTER TABLE persons CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

工作量 **1h**，可与 P0-4/P0-10 合并 DBA 窗口。

---

## §八 owner 拍板点 + 下次刷新触发

### 8.1 各节拍板点汇总

| 节 | 拍板点 | 拍板方 | 阻塞与否 |
|---|---|---|---|
| §三 41 页 H5/H16/H17 | P0-2 KPI 公式派单 | 业务 owner | **阻塞 High 档 4 页** |
| §三 41 页 H22 | AllowanceService 删/留拍板 | owner | **阻塞 1 页** |
| §四 权限三套收敛顺序 | 先收 meta.authority 还是先统一 v-access:code | 前端 owner | **不阻塞** |
| §五.5 docker-compose 端口对齐 | 端口前缀约定 `1XXXX` 是否全仓推 | 前端 owner + DevOps | **阻塞部署** |
| §六 DBA 窗口 | P0-4/P0-10/DB-02 合并窗口排期 | DBA | **阻塞真库 DDL** |
| §七.1 P0-12 Redis | DBA 部署 + owner 启动 26h | DBA + owner | **阻塞多实例** |
| §七.2 字符集 P3-LOW | persons → utf8mb4_0900_ai_ci 是否合并 DBA 窗口 | DBA | **不阻塞** |

### 8.2 撞车 0 严守

| 维度 | 承诺 | 验证 |
|---|---|---|
| Java / Vue / SQL / yml / Dockerfile | 不动 | ✅ 未触 |
| 真库 ipd_dev @ 13306 | 不跑 SQL | ✅ 未触 |
| 端口（16039 / 15666 / 62250 / 13306）| 不抢 | ✅ 未触 |
| PID（28071 / 88601）| 不杀 | ✅ 未触 |
| 兄弟 13 worktree | 不抢 | ✅ 未触 |
| 看板卡 status | 不翻 | ✅ 未触 |
| pom.xml | 不改 | ✅ 未触 |

**累计撞车 0**：R188 → R189 → R190 → R191 → R192 → R193 → **R194（本盘）** = 7 轮 docs-only × 撞车 0 兑现 100%。

### 8.3 下次刷新触发

| 触发条件 | 启动项 |
|---|---|
| owner 拍板 P0-2 KPI 公式 | H5/H16/H17 三页解锁 |
| owner 拍板 AllowanceService | H22 解锁 |
| DBA 排期合并窗口 | P0-4/P0-10/DB-02 + 字符集一并 apply |
| DBA 部署 Redis + owner 启动 P0-12 | 26h 实装派单 |
| 前端 owner 派单 41 页三证 | L2 模板化 3 会话推进 |
| DevOps 接手部署物补齐 | §五.2/§五.4/§五.5 并行实装 |

---

**R194 §A7 前端 + 部署整合调研生成完毕**。
**撞车 0 兑现**：本盘为 `/tmp/wt-r194-pack/docs/ipd-系统说明/调研/R194-A7-frontend-deploy-research-20260923.md` 新文件 untracked（待主协调统一收口 commit + push），R194 §A7 期间未动 Java/Vue/SQL/yml/Dockerfile/真库/端口/PID/兄弟 worktree/看板卡 status/pom.xml。
