# IPD 项目运维手册 runbook（P2-4）

- **编写日期**：2026-09-22
- **编写人**：R173 AI 自主推进
- **适用版本**：R172 接管后（main HEAD = 74f4e84e，r172-takeover-20260922 = 269353bc）
- **目标读者**：值守运维 / 二线工程师 / Owner

## 一、本机四件套启停

| 组件 | 端口 | PID 文件 | 启停命令 | 健康检查 |
|------|------|----------|----------|----------|
| MySQL | 13306 | `/tmp/mysqld.pid` | `mysqld --defaults-file=.../my.cnf &` | `mysql ... -e "SELECT @@port"` |
| Redis | 6379 | `/tmp/redis.pid` | `/opt/homebrew/bin/redis-server --daemonize no --port 6379 --bind 127.0.0.1 --dir /tmp` | `redis-cli -p 6379 PING` |
| 后端 (Spring Boot) | 16039 | - | 见 §1.3 | `curl http://127.0.0.1:16039/actuator/health` |
| 前端 (Vite dev) | 15666 | - | 见 §1.4 | `curl http://127.0.0.1:15666/` HTTP 200 |

### 1.1 MySQL 13306 启动

```bash
# 已存在则跳过
lsof -i :13306 -P 2>&1 | head -3
# 启命令（参数随本机 my.cnf 而定）
mysqld --defaults-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql.cnf &
```

### 1.2 Redis 6379 启动

```bash
# 检查
redis-cli -p 6379 PING
# 启
nohup /opt/homebrew/bin/redis-server --daemonize no --port 6379 --bind 127.0.0.1 --dir /tmp &
```

### 1.3 后端 16039 启动（三防律铁律）

```bash
cd /Users/mac/Documents/ruoyi-ai

# 打包（拿 fat jar）
mvn -o -pl ruoyi-admin -am package -DskipTests -Dmaven.jar.forceCreation=true

# 启（双 profile + 显式 ipd-local 配置）
export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"
nohup $JAVA_HOME/bin/java \
    -jar ruoyi-admin/target/ruoyi-admin.jar \
    --spring.profiles.active=ipd-local,dev \
    --spring.config.additional-location=optional:classpath:./,optional:file:./.codex/ipd-dev/config/ \
    > server-16039.log 2>&1 &

# 等启动（~25s）
for i in $(seq 1 30); do
    curl -s -m 1 -o /dev/null -w "%{http_code}" http://127.0.0.1:16039/actuator/health 2>/dev/null | grep -q 401 && echo "ready ($i s)" && break
    sleep 1
done
```

**三防律铁律**：
1. `-Dmaven.jar.forceCreation=true` 避免 maven 跳过 jar 重建
2. 双 profile `ipd-local,dev`（不能单 dev，会 Unknown database 'ruoyi-ai'）
3. 显式 `--spring.config.additional-location` 指向 ipd-local 数据源配置

### 1.4 前端 15666 启动（活 pty 铁律）

**绝对不能用 nohup / `&` 后台化**——vite 前台读 stdin，nohup 缺活 pty → event loop 锁死。

```bash
# 正确起法（活 pty，必须用工具的原生后台机制）
cd /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd
exec /Users/mac/.hermes/node/bin/node /Users/mac/Documents/ruoyi-ipd-web/node_modules/vite/bin/vite.js --port 15666 --host 127.0.0.1
# 配合 Bash is_background=true + GetTerminalOutput
```

**症状指纹**：
- 终端出现 `VITE v7.2.7 ready`（进程活着）
- `lsof -i :15666` 显示 LISTEN
- 但 `curl` 5s/10s HTTP 000 超时 → 100% 是 nohup 卡死

**修复**：杀掉进程 + 用活 pty 重起。

## 二、登录与会话

### 2.1 登录凭证

| 角色 | username | password | 权限 |
|------|----------|----------|------|
| 超管 | `ipd-admin` | `Ipd@123456` | 全功能 |

演示账号按钮一键登录（前端登录页 UI 已有）。

### 2.2 登录 API

```bash
curl -X POST http://127.0.0.1:16039/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"ipd-admin","password":"Ipd@123456"}'
```

返回 `{code:0, data:{token:"eyJ..."}}`。

### 2.3 鉴权拦截自动恢复

前端 axios 拦截器会检测 401 → 自动 refresh token → 重试。日志里如出现 `reqid=1207 [401] reqid=1208-1210 [200]` 序列属正常。

## 三、关键业务端点（真活清单）

### 3.1 KPI 三件套（核心）

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:16039/api/v1/auth/login -H "Content-Type: application/json" -d '{"username":"ipd-admin","password":"Ipd@123456"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:16039/api/v1/kpi/functional-metrics/codes
curl -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:16039/api/v1/kpi/functional?period=2026-09"
curl -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:16039/api/v1/kpi/performance?period=2026-09"
curl -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:16039/api/v1/kpi/trend?periods=12"
```

期望：所有 4 个端点返回 200 + code:0 + data 非空。

### 3.2 业务端点矩阵

| 端点 | 状态 | 备注 |
|------|------|------|
| GET /api/v1/demands | 200 | 需求列表 |
| GET /api/v1/product-groups | 200 | 产品空间 |
| GET /api/v1/notifications | 200 | 通知公告 |
| GET /api/v1/workbench/summary | 200 | 工作台摘要 |
| GET /api/v1/projects | 200 | 项目列表 |
| GET /api/v1/products | 200 | 产品列表 |
| GET /api/v1/bid-invitations | 200 | 招募列表 |
| GET /api/v1/requirement-changes | 200 | 变更列表 |
| GET /api/v1/projects/{id}/gate-checklist | 200 | 阶段确认 |
| GET /api/v1/audit-logs/scope | 200 | 全流程轨迹 |
| GET /api/v1/report/project-summary | 200 | 报表分析 |
| GET /api/v1/handovers/inbox | 200 | 项目移交 |
| GET /api/v1/pm-directory | 200 | 人员同步 |
| GET /api/v1/system-configs | 200 | 超级管理 |

## 四、监控指标（健康检查模板）

### 4.1 进程存活

```bash
lsof -iTCP -sTCP:LISTEN -P 2>&1 | grep -E "16039|15666|6379|13306"
# 期望 4 行（每件套一行）
```

### 4.2 后端健康

```bash
curl -s -m 3 http://127.0.0.1:16039/actuator/health
# 期望：401 + JSON envelope（需要 basic auth 是预期）
```

### 4.3 前端健康

```bash
curl -s -m 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:15666/
# 期望：200
```

### 4.4 DB 健康

```bash
mysql --defaults-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf \
    -e "SELECT @@port, DATABASE();"
# 期望：13306 + NULL（未指定库）
```

### 4.5 Redis 健康

```bash
redis-cli -p 6379 PING
# 期望：PONG
```

## 五、常见事故与处置

### 5.1 后端 Unknown database 'ruoyi-ai'

**症状**：启动时报 `Unknown database 'ruoyi-ai'`
**根因**：单 dev profile，连 3306/ruoyi-ai 不存在
**处置**：必须用双 profile `ipd-local,dev` + 显式 config 路径（§1.3 铁律）

### 5.2 后端 Unable to connect Redis 127.0.0.1:6379

**症状**：启动时报 `Unable to connect Redis`
**根因**：Redis 未启
**处置**：按 §1.2 起 Redis

### 5.3 前端 vite HTTP 000 超时

**症状**：`curl` 5s/10s 超时 + 终端有 `VITE ready` + lsof 显示 LISTEN
**根因**：nohup 后台化缺活 pty
**处置**：按 §1.4 用活 pty 重起

### 5.4 登录 401 + 多次重试

**症状**：日志出现 `reqid=X [401] reqid=X+1~X+3 [200]` 序列
**根因**：token 临过期，axios 拦截器自动 refresh
**处置**：无需处置，属正常

### 5.5 DDL apply 后 业务 500

**症状**：写操作 HTTP 500
**根因**：RENAME 表后未迁移表级 GRANT（mysql.tables_priv 仍挂旧名）
**处置**：参考 `docs/script/sql/update/2026-09-21-ipd-rename-3-tables-plural.sql` 第 2/3 段（GRANT 新名 + REVOKE 旧名）

## 六、回滚 SOP

### 6.1 代码回滚

```bash
# 本地未提交
git checkout -- <files>

# 已提交未推
git revert <hash>

# 已推 origin（需 owner 拍板）
git revert <hash> && git push
```

### 6.2 DDL 回滚（参考 RENAME 脚本反向）

```sql
RENAME TABLE ipd_dev.requirement_pools    TO ipd_dev.requirement_pool;
RENAME TABLE ipd_dev.receipt_ledgers      TO ipd_dev.receipt_ledger;
RENAME TABLE ipd_dev.switching_acceptances TO ipd_dev.switching_acceptance;
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.requirement_pool     TO 'ipd_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.receipt_ledger       TO 'ipd_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.switching_acceptance TO 'ipd_app'@'127.0.0.1';
REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.requirement_pools     FROM 'ipd_app'@'127.0.0.1';
REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.receipt_ledgers       FROM 'ipd_app'@'127.0.0.1';
REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.switching_acceptances FROM 'ipd_app'@'127.0.0.1';
FLUSH PRIVILEGES;
```

### 6.3 后端启动回滚

```bash
# 杀当前进程
pkill -9 -f "ruoyi-admin.jar"

# 启上一版本
java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=ipd-local,dev ...
```

## 七、关键路径速查

| 类别 | 路径 |
|------|------|
| 后端启动配置 | `/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/application-ipd-local.yml` |
| 后端数据源 | `jdbc:mysql://127.0.0.1:13306/ipd_dev`（user=ipd_app） |
| 后端 fat jar | `ruoyi-admin/target/ruoyi-admin.jar` |
| 后端模块 jar | `ruoyi-modules/ruoyi-ipd/target/ruoyi-ipd-3.1.0.jar` |
| 前端 vite 配置 | `apps/web-antd/vite.config.mts` |
| 前端 Dockerfile | `apps/web-antd/Dockerfile` |
| 前端 nginx 配置 | `apps/web-antd/nginx.conf` |
| 后端 Dockerfile | `docs/docker/ruoyi-ai/Dockerfile` |
| SSOT 镜像 | `docs/ipd-系统说明/开发计划-看板镜像.md` |
| 真库 ipd_dev | 127.0.0.1:13306（凭证见 application-ipd-local.yml） |

## 八、值班联系

| 角色 | 联系方式 | 拍板范围 |
|------|----------|----------|
| Owner | （Slack/钉钉） | 部署策略 / DDL apply / 凭据轮换 |
| AI 主协调 | 当前会话 | 可逆代码改动 / 单测补齐 / docs 落档 |
| 二线 | - | 启停服务 / 健康检查 / 监控 |

## 九、runbook 更新纪律

每次会话收尾如有以下变更必须更新本 runbook：
- 端口变更
- 启动参数变更
- 新增/下线业务端点
- DDL 脚本归档
- 部署物变更
- 事故复盘（新增 §五 处置章节）

---

**runbook 版本**：v1.0 (R173 自主首版)
**下次更新**：新增业务端点 / 部署物变更 / 重大事故复盘后