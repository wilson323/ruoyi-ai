# QA-06 部署演练 Checklist（2026-09-19，文档级实质推进）

- **看板卡**：QA-06（部署安全与恢复演练验收；主线 PASS 见 `QA-06-业务部署安全与恢复演练-20260905.md`，2 发现项 + 3 显式未验）
- **本轮性质**：部署演练 checklist 文档级推进（真实多环境演练不在本轮授权内，登记为 owner/后续轮执行）
- **素材来源（现查）**：根 `docker-compose.yml`（P0-6 生产编排模板，2026-09-09）、`docs/nginx/ipd.conf`（本轮新增）、前端仓 `apps/web-antd/Dockerfile` + `nginx.conf`（只读参考）、`生产部署Runbook-20260909.md`

## 1. 部署前置（owner 确认项）

- [ ] **域名与证书**：`docs/nginx/ipd.conf` 中 `server_name`/`ssl_certificate` 为占位，owner 拍板后替换（对应 QA-06 §6 显式未验"HTTPS/TLS"的部署侧闭环）
- [ ] **ENV_VAR 注入**：`.env` 填 `IPD_DB_URL` / `IPD_DB_USER` / `IPD_DB_PASSWORD` / `IPD_JWT_SECRET_KEY`（R96 已把 `application-prod.yml` 28 处字面量换成 `${VAR:}`，凭证一律走 .env，gitignore）
- [ ] **泄露候选轮换**：R96 登记的 snail-token/gitee/maxkey 明文 secret 待 owner 轮换后方可上线
- [ ] **demo.enabled=false** 确认（prod 默认关，勿被环境变量覆盖回 true）

## 2. 栈启动演练步骤

```bash
# 1) 后端镜像（含 R97+ seed 修复）
docker build -t ipd-backend:latest .
# 2) 前端镜像（ruoyi-ipd-web 仓，--build-arg VITE_GLOB_API_URL 指向实际后端地址）
docker build -t ipd-frontend:latest -f apps/web-antd/Dockerfile .
# 3) 起栈（backend 16039 + redis 6379 + frontend 80:5666）
docker compose up -d
# 4) 健康检查（compose healthcheck 同路径，start_period 90s）
curl -fs http://127.0.0.1:16039/actuator/health
# 5) 前端容器反代自检
curl -fs http://127.0.0.1:80/api/v1/auth/login -X POST -H 'Content-Type: application/json' -d '{"username":"x","password":"y"}'
#    预期 HTTP 400 code=10001（证明前端 nginx → 后端链路通，且错误包络未被网关改写）
```

## 3. 宿主层 nginx（可选边缘部署形态）

- [ ] `cp docs/nginx/ipd.conf /etc/nginx/conf.d/ipd.conf && nginx -t && nginx -s reload`
- [ ] compose 端口改 `127.0.0.1:5666:5666`（避免宿主 80 与前端容器 80 冲突——R96 已修 DB 侧 23306→13306，前端侧此形态下同理收敛到 loopback）
- [ ] SSE 冒烟：`curl -N http://<host>/api/v1/resource/sse`（应即时收到事件帧不攒包；`proxy_buffering off` 生效判据）
- [ ] 本机无 nginx，`ipd.conf` 语法未经 `nginx -t` 实测——首次部署时以 `nginx -t` 为准，红了按报错修（配置中 map 段若与主配置重名需删除）

## 4. 回滚步骤

1. `docker compose down`（保留 volumes：redis-data / backend-logs）
2. 镜像回退：`docker tag ipd-backend:<prev> ipd-backend:latest && docker compose up -d`
3. DB 回滚参照 QA-06 §1-2 恢复演练口径（mysqldump `--single-transaction` → `ipd_restore` 验证 → 切换），**主库只读恢复到影子库先行**

## 5. 与 QA-06 §6 三项显式未验的对应

| 显式未验项 | 本 checklist 对应动作 |
|---|---|
| HTTPS/TLS | §1 域名证书项 + `ipd.conf` 443 注释模板（owner 启用后补测） |
| SEC-03 / OPS-03 依赖产物 | 不在本 checklist 范围（等 SEC-03 报告归仓） |
| 审计链 v2 行重算 | 不在本 checklist 范围（结构性不可复现，B1+B3 兜底口径不变） |

## 6. 判定

- 文档级实质推进完成：部署演练从"无 checklist"到"可执行步骤清单"，且与 R96 ENV_VAR 收尾、本轮 nginx 配置形成闭环。
- 真实多环境演练（生产/预发 + 真实域名）**不在本轮授权内**，登记 owner 后续执行；QA-06 卡状态不翻。
