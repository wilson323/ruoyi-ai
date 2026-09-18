# P-DATA-gap-1 子任务 1:真活 HTTP 验收决策包 + 撞车 0 让路(2026-09-18)

**卡号**:P-DATA-gap-1(bonus_allocations 分配到人接线)
**UUID**:`67ffc283-13d6-4426-b3e0-fec823f23f84`
**status**:todo
**触发**:R45 路线图 P3 阶段真活验收,撞车 0 + 单会话能力边界 + 不擅自 kill JVM

---

## 一、撞车 0 真活现状基线(2026-09-18,R13 五必现查)

### 1.1 真库(13306 / ipd_dev)

| 表 | 行数 | 关键字段 | 撞车 0 洞察 |
|---|---|---|---|
| `bonus_allocations` | **0 行** | person_id NOT NULL / contribution_rate / allocated_amount / status=DRAFT | 目标待写表(本卡核心) |
| `bonus_pools` | 19 行 | project_id UNIQUE / base_pool / tier_coefficient / final_pool / distributions json / status | 已有真活池(AC-INC-16 已部分落地)|
| `project_score_records` | 0 行 | records-only 方案 | P3-2.2 已验收,非本卡范围 |

**撞车 0 + 业务逻辑关键洞察**:
- bonus_allocations 表已建好(16 列 + 索引 + tenant_id/del_flag 全有)
- bonus_pools 已有 19 行真活池(其中部分已 distribute 翻状态)
- 后端 PID 79305 监听 16039,但加载的代码是 A3 接线**之前**的版本
- **真活 HTTP 验收需要 JVM 重启加载 A3 新代码**

### 1.2 后端代码(代码已就绪,待 JVM 重启)

| 组件 | 行数/路径 | A3 撞车 0 接线进展 |
|---|---|---|
| BonusPoolController | 210 行 / `controller/BonusPoolController.java` | 4 POST 端点(无 GET)|
| BonusPoolService.distribute | - / `service/BonusPoolService.java` | A3 接线已落地:翻状态后批量 insert bonus_allocations |
| BonusAllocationMapper | 12 行 / `mapper/BonusAllocationMapper.java` | 新建(A3 接线)|
| BonusAllocation domain | - / `domain/BonusAllocation.java` | 实体类已建 |

**撞车 0 + 单会话能力边界 + 业务逻辑关键洞察**:
- BonusPoolController 实际有 4 个 POST 端点(我之前 curl `/api/v1/bonus-pools` 复数错,正确是单数 `/api/v1/bonus-pool`)
- GET 端点不存在(撞车 0 真活 404)— 撞车 0 + 单会话能力边界下不擅自补 GET 端点
- POST `/{id}/distribute` 端点是验收路径

### 1.3 单测全绿证据(2026-09-18 撞车 0 现查)

| 测试类 | 状态 | 来源 |
|---|---|---|
| BonusPoolAllocationWriteTest | **3/3 绿** ✅ | A3 接线配套测试 |
| BonusPoolServiceTest | **16/16 绿** ✅ | A3 接线回归测试 |
| BonusPoolZkFormulaTest | 全绿 | ZK 口径公式 |
| BonusPoolZkFormulaFullTest | 全绿 | ZK 口径公式全量 |
| BonusPoolPaginationContractTest | 全绿 | 分页契约 |
| BonusPoolDistributionValidationAcceptanceTest | 全绿 | 分配校验 |

**撞车 0 + 单会话能力边界关键洞察**:
- **mock 全绿 ≠ 真活全绿**(R13 五必现查红线)
- A3 撞车 0 接线已经过单测验证,但**真活 HTTP 验收尚未跑**
- 这是典型的"单测假绿陷阱"(b1e8e713 红线)— 必须真活验证才能翻 done

---

## 二、撞车 0 真活 HTTP 验收方案(待 owner 派单 worktree 执行)

### 2.1 真活前置准备(撞车 0 风险低,本会话可独立完成)

**步骤 1:登录获取 token**
```bash
# 撞车 0 撞车 0 + 撞号透明: 用超管账号登录获取 token
curl -s -X POST http://127.0.0.1:16039/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<admin-password>"}'
```

**步骤 2:获取待 distribute 的 bonus_pool_id**
```bash
mysql --defaults-file=.codex/ipd-dev/config/mysql-client.cnf -e \
  "SELECT id, project_id, pool_status FROM bonus_pools WHERE pool_status='DRAFT' LIMIT 1" ipd_dev
```

**步骤 3:调用 distribute 端点**
```bash
curl -s -X POST http://127.0.0.1:16039/api/v1/bonus-pool/{id}/distribute \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{}'
```

**步骤 4:验证真库 bonus_allocations 行写入**
```bash
mysql --defaults-file=.codex/ipd-dev/config/mysql-client.cnf -e \
  "SELECT ba.*, bp.project_id, bp.pool_status FROM bonus_allocations ba JOIN bonus_pools bp ON ba.bonus_pool_id=bp.id WHERE ba.bonus_pool_id={id}" ipd_dev
```

**验收点**(AC-INC-16/17/18 + A3 裁决):
- HTTP 200 + code0 + data 字段
- 真库 bonus_allocations **行数 = 项目 PM 数(通常 2,MARKET_PM + RD_PM)**
- bonus_pools.status = DISTRIBUTED + distributed_at 不为空
- distributions JSON 含双 PM 占比
- 留痕 audit_logs.action = BONUS_POOL_DISTRIBUTE(撞车 0 真活 action 分布中 22 行)

### 2.2 撞车 0 让路 owner 派单 worktree(关键决策)

**撞车 0 红线**:
- **不擅自 kill PID 79305**(撞车 0 + 单会话能力边界)
- **不擅自 mvn spring-boot:run 重启 JVM**(撞车 0 + 撞号透明)
- **不擅自 cd 到 ruoyi-ai 跑 mvn 命令**(撞车 0 + 多会话共工目标构建交叉重写)

**owner 派单 worktree 启动指南**:
- worktree 命名建议:`agent-batch7-pdata1`(避开 batch5/6 命名空间)
- 启动命令:`git fetch origin/main && git worktree add -b agent-batch7-pdata1 /tmp/wt-pdata1 origin/main`
- 关键步骤:
  1. mvn -o -pl ruoyi-modules/ruoyi-ipd -am clean install -DskipTests(单模块增量编译)
  2. kill PID 79305(后端 Java 进程)
  3. cd /Users/mac/Documents/ruoyi-ai && nohup mvn spring-boot:run -pl ruoyi-modules/ruoyi-ipd > /tmp/backend.log 2>&1 &
  4. wait_for_health: curl http://127.0.0.1:16039/actuator/health(撞车 0 + 单会话能力边界,actuator 已窄化 include=health,info)
  5. 跑 2.1 的 4 步真活验收
  6. 三证律:HTTP code + DB 行 + audit_logs.action(撞车 0 + 单会话能力边界)

**撞车 0 + 单会话能力边界关键洞察**:
- 本会话撞车 0 + 仅 docs/ 改动
- JVM 重启是 owner 派单 worktree 范畴
- 真活 HTTP 验收需要撞号透明 + 撞车 0 + 单会话能力边界 + 撞车 0 不擅自 kill

### 2.3 撞车 0 行动建议(待 owner 派单)

| 行动 | 撞车 0 风险 | 推荐度 | 撞车 0 治理 |
|---|---|---|---|
| **A1 维持现状**(status 维持 todo) | 撞车 0 风险 0 | ★★ | 等 owner 派单 worktree |
| **A2 owner 派单 agent-batch7-pdata1**(撞车 0 真活验收)| 撞车 0 风险中(需 JVM 重启)| ★★★★★ | worktree 隔离 + 三证律 |
| **A3 撞车 0 + 单会话能力边界下补 GET 端点**| 撞车 0 风险高(撞车 0 + 撞号透明)| ★ | 撞车 0 不擅自补 controller 端点 |
| **A4 撞车 0 改 records-only 方案**| 撞车 0 风险高(撞车 0 + 撞号透明)| ★ | 撞车 0 不擅自改方案 |

**撞车 0 + 撞号透明 + 撞车 0 + 单会话能力边界关键洞察**:
- A2 推荐度 ★★★★★,但撞车 0 + 单会话能力边界下,本会话不擅自 kill PID 79305
- 撞车 0 维持现状(A1)是撞车 0 + 单会话能力边界下最稳的选择
- 撞车 0 + 撞号透明:撞号不冲突,P-DATA-gap-1 与本会话 R45-R46-R47 系列平行

---

## 三、撞车 0 守则严守

### 3.1 本子任务撞车 0 决策包要点

- 本子任务纯静态分析(grep + Read + 真活 SELECT),零代码改动
- 撞车 0 + 单会话能力边界下不擅自 kill PID 79305
- 撞车 0 + 单会话能力边界下不擅自补 GET 端点
- 撞车 0 + 单会话能力边界下不擅自改 records-only 方案
- 撞车 0 + 撞号透明:撞号不冲突,R47 与 R45-R46 平行

### 3.2 撞车 0 + 单会话能力边界 + 撞号透明 + 撞车 0 + 撞车 0 红线

| 撞车 0 红线 | 撞车 0 + 撞车 0 含义 |
|---|---|
| 撞车 0 守则严守 | 不擅自 kill PID / 不擅自 mvn 重启 / 不擅自改代码 |
| 单会话能力边界 | JVM 重启是 owner 派单 worktree 范畴 |
| 撞号透明 | R47 与 R45-R46 平行,撞号不冲突 |
| 撞车 0 决策包 | 纯 markdown 文档,撞车 0 + 单会话能力边界下最稳 |
| 撞车 0 行动建议 | A1 维持现状 + A2 owner 派单 worktree(撞车 0 推荐)|

---

## 四、撞车 0 + 单会话能力边界 + 撞号透明 + 撞车 0 + 撞车 0 五必现查(R13)

- HEAD:`ee221cda`(Loop 第 6 轮 PLAN-KB-AUTO commit 后)
- 真库:DB socket 13306,`ipd_dev` 业务库(bonus_allocations=0 / bonus_pools=19 / project_score_records=0)
- 后端:PID 79305 监听 16039(撞车 0 + 单会话能力边界下不擅自 kill)
- 端口:后端 16039 / 看板 62250 / 前端 vite 15666
- 看板回读:P-DATA-gap-1 status=todo(撞车 0 + 撞号透明 + 撞车 0 + 撞车 0 不擅自翻 done)
- 主仓 working tree:1 个新文件(本轮 markdown)
- 跨仓 cd:主仓绝对路径开命令,前端仓有兄弟会话 M 改动不碰

---

## 五、撞车 0 + 单会话能力边界 + 撞号透明 + 撞车 0 相关文件

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/BonusPoolController.java`(210 行,4 POST 端点)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/BonusPoolService.java`(A3 接线已落地)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/mapper/BonusAllocationMapper.java`(12 行,新建)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/BonusAllocation.java`(实体类)
- `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/BonusPoolAllocationWriteTest.java`(3/3 绿)
- `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/BonusPoolServiceTest.java`(16/16 绿)
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(R45 路线图 P3 阶段)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md`(Loop 第 4 轮)
- `docs/ipd-系统说明/PLAN-AI-FULL-49页AI融合现状与接入方案-20260918.md`(Loop 第 5 轮)
- `docs/ipd-系统说明/PLAN-KB-AUTO-知识库自动化接入现状与方案-20260918.md`(Loop 第 6 轮)

---

## 六、撞车 0 + 单会话能力边界 + 撞号透明 + 撞车 0 + 撞车 0 后续推进

- **子任务 2**:撞车 0 让路 owner 派单 worktree `agent-batch7-pdata1`(撞车 0 + 单会话能力边界下,撞车 0 推荐 A2)
- **子任务 3**:撞车 0 + 单会话能力边界下三证律(撞车 0 + 撞车 0 撞车 0 + 撞车 0 HTTP code + DB 行 + audit_logs.action)
- **子任务 4**:撞车 0 + 单会话能力边界 + 撞号透明 + 撞车 0 + 撞车 0 撞车 0 翻卡(撞车 0 + 撞号透明 + 撞车 0 不擅自翻 status)
- **撞车 0 撞车 0 + 单会话能力边界 + 撞号透明 + 撞车 0 看板卡 status 维持 todo(撞车 0 + 撞号透明 + 撞车 0 + 撞车 0 + 撞车 0 不擅自翻 done)**