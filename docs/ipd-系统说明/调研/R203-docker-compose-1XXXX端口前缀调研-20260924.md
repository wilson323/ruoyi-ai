# R203 docker-compose 端口 1XXXX 前缀调研与三套拆分计划（2026-09-24）

> **撰写者**：主协调会话（按 R202 阶段 1 docs-only 收口 + R201 §A7 #11 docker 端口拍板 B 全仓推 1XXXX + 撞车 0 严守 docs-only 调研）

> **目的**：调研当前 docker-compose / Dockerfile / .env 现状，落档三套独立 compose 拆分计划 + 端口前缀约定 `1XXXX`（dev=13306/staging=15306/prod=17306）+ 前端 `VITE_API_BASE_URL` 显式指向 16039

> **撞车 0 严守**：本盘仅 docs/ipd-系统说明/ 落档调研计划，**不动 yml/Dockerfile/CI/.env**（实装阶段错峰 + 单 worktree）

---

## 一、现状盘点（基线 2026-09-24 现查）

### 1.1 后端 Dockerfile（5 个现查）

| Dockerfile | 路径 | 状态 |
|---|---|---|
| 根 | `./Dockerfile`（1406B）| ✅ R19/R21 已实装 |
| ruoyi-admin | `./ruoyi-admin/Dockerfile`（2243B）| ✅ R19/R21 已实装 |
| monitor-admin | `./ruoyi-extend/ruoyi-monitor-admin/Dockerfile`（604B）| ✅ 已有 |
| snailjob-server | `./ruoyi-extend/ruoyi-snailjob-server/Dockerfile`（621B）| ✅ 已有 |
| sandbox | `./scripts/sandbox/Dockerfile`（1482B）| ✅ 已有 |

### 1.2 docker-compose 文件（**❌ 缺，无任何 yml 文件**）

- 现查：`ls docker-compose*.yml docker-compose.*.yml` → **无匹配**（zsh no matches found）
- 仓库**未提交** docker-compose.yml / docker-compose-dev.yml / docker-compose-staging.yml / docker-compose-prod.yml
- R194 §A7 §5.3 调研结论：「dev 用 13306 真库，docker-compose 写 23306 未对齐」——但实际无 yml 文件可改

### 1.3 CI 流水线（5 个相关 yml 现查）

| CI yml | 用途 |
|---|---|
| `.github/workflows/a11y-ci.yml` | a11y 验证 |
| `.github/workflows/braud01-audit-grant.yml` | 审计链 grant |
| `.github/workflows/check-prod-secrets-inlined.yml` | 生产密钥检查 |
| `.github/workflows/docs-link-check.yml` | 文档链接检查 |
| `.github/workflows/gitleaks.yml` | git 密钥扫描 |

**无 `docker-publish.yml` / `compose-deploy.yml`** —— **缺 CI 流水线推镜像 + 起 compose**

### 1.4 前端仓 vite env（跨仓 `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/.env*`）

| env 文件 | 大小 | 修改时间 |
|---|---|---|
| `.env` | 598B | 2026-09-05 |
| `.env.analyze` | 102B | 2026-09-05 |
| `.env.development` | 1907B | 2026-09-11 |
| `.env.local` | 496B | 2026-09-07 |
| `.env.production` | 1835B | 2026-09-11 |

**前端仓 .env 已**建（不在本仓暂加），需 DevOps + 前端 owner 联推 1XXXX 端口对齐。

### 1.5 当前应用端口（真库）

| 端口 | 服务 | 现查 |
|---|---|---|
| 16039 | 后端应用 | `127.0.0.1:16039`（R179 现查）|
| 13306 | MySQL ipd_dev 真库 | `127.0.0.1:13306`（R179 现查）|

---

## 二、问题诊断（端口漂移与缺失）

| # | 问题 | 风险 | 证据 |
|---|---|---|---|
| 1 | docker-compose yml **完全缺失** | **高**（无容器化部署能力）| §1.2 无匹配 |
| 2 | dev=13306 真库 vs 历史 compose 写 23306 漂移 | 中（持续漂移）| R194 §A7 §5.3 |
| 3 | 前端 `VITE_API_BASE_URL` 未显式指向 16039 | 中（混用 compose 内部网络）| R194 §A7 §5.3 |
| 4 | 无 `docker-publish.yml` / `compose-deploy.yml` | 高（无 CI 推镜像）| §1.3 缺失 |
| 5 | 无 staging/prod 三套独立 compose | 高（环境配置混用）| §1.2 缺失 |
| 6 | 无 nginx 配置（前端 Dockerfile §5.4）| 中（前端容器化卡）| R194 §A7 §5.4 |

---

## 三、修复方案（端口 1XXXX 前缀 + 三套拆分）

### 3.1 端口前缀约定（撞车 0 严守）

| 环境 | MySQL 端口 | 后端应用端口 | 前端端口 | Redis 端口 |
|---|---|---|---|---|
| **dev** | 13306 | 16039 | 15666 | 16379 |
| **staging** | 15306 | 16040 | 15667 | 16380 |
| **prod** | 17306 | 16060 | 15680 | 16390 |

**前缀约定**：所有端口前缀 `1XXXX`（dev=13306/staging=15306/prod=17306 类推）

### 3.2 三套独立 compose 文件结构

```
仓库根/
├── docker-compose.yml          # 默认 = dev（向后兼容）
├── docker-compose.dev.yml      # dev 环境（13306/16039/15666/16379）
├── docker-compose.staging.yml  # staging 环境（15306/16040/15667/16380）
├── docker-compose.prod.yml     # prod 环境（17306/16060/15680/16390）
└── scripts/
    ├── docker-up-dev.sh        # 启动 dev（含健康检查）
    ├── docker-up-staging.sh
    └── docker-up-prod.sh
```

### 3.3 后端 Dockerfile 端口约定

`Dockerfile` 显式声明 `EXPOSE_PORT=16039`（dev）/ `16040`（staging）/ `16060`（prod），通过 `--build-arg ENV_PROFILE` 注入：

```dockerfile
ARG ENV_PROFILE=dev
ENV SERVER_PORT=${SERVER_PORT_BY_PROFILE}
```

### 3.4 前端 vite env 显式端口（跨仓）

`apps/web-antd/.env.development`：
```
VITE_API_BASE_URL=http://127.0.0.1:16039
VITE_PORT=15666
```

`apps/web-antd/.env.production`：
```
VITE_API_BASE_URL=http://127.0.0.1:16060
VITE_PORT=15680
```

### 3.5 CI 流水线补齐

| 新增 CI yml | 触发 | 动作 |
|---|---|---|
| `.github/workflows/docker-publish.yml` | push tag `v*` | 多 profile 构建 + 推 ghcr.io |
| `.github/workflows/compose-up-dev.yml` | push main | 启动 dev compose + 健康检查 |
| `.github/workflows/compose-up-staging.yml` | push release | staging |
| `.github/workflows/compose-up-prod.yml` | manual workflow_dispatch | prod（防误推）|

---

## 四、撞车 0 兑现

- **不动 yml/Dockerfile/CI/.env**（仅 docs 落档调研计划）
- **不动真库 / 端口 / PID**（本盘为 0 触碰）
- **18 兄弟 worktree 完整保留**
- **本盘 worktree**：基于 `origin/main = 86163e88` 单拉 `/tmp/wt-r203`，分支 `docs/r203-docker-1xxxx-research-20260924`

---

## 五、工作量与撞车风险

| 阶段 | 动作 | 工作量 | 撞车 |
|---|---|---|---|
| 实装 1 | 写 4 个 compose yml + 3 个 up shell | 4h | 0 |
| 实装 2 | 后端 Dockerfile 加 `ENV_PROFILE` build-arg | 2h | **中**（动 Dockerfile）|
| 实装 3 | 跨仓前端 `.env.{development,production}` 改 1XXXX | 1h | **低**（跨仓前端 owner）|
| 实装 4 | 4 个 CI yml 新增（docker-publish + 3 个 compose-up）| 4h | **中**（动 .github/workflows）|
| 实装 5 | 联调 + 看板同步 | 2h | 0 |
| **合计** | — | **13h ≈ 1.6 人日** | **中** |

**撞车点总计**：4 项触碰（Dockerfile / 跨仓 .env / CI yml / compose yml），各自需错峰 + 单 worktree。

---

## 六、下一步

1. **DevOps + 前端 owner 摸启动条件**：拍 R203 调研方向是否采纳（dev=13306/staging=15306/prod=17306 1XXXX 前缀 + 三套拆分）
2. **R204 docs-only 调研** 权限收敛脚本骨架 + selftest（撞车 0 严守）
3. **R205 docs-only 调研** Redis 切流飞手计划（基于 R189 调研）
4. **R206 实装启动**：evolver 启动 13h 拆分 5 阶段实装（错峰 + 单 worktree）

---

## 七、撞号透明登记

- **已 push** `8931d75f R202 飞手计划`（已 merge `86163e88`）—— R202 主题 = 11 项合并飞手计划
- **本盘** R203 = docker-compose 端口 1XXXX 调研
- 按「撞号透明协议」+ R202 阶段 1 docs-only 收口，主题可分辨，与 BCP §四十 R202 并列