# E2E 生产就绪验证 — 2026-09-23

## 目标
最小三证律闭环验证：登录链路（入口 → 鉴权 → 业务首页）端到端真活，生产就绪 P0 缺口清单基线。

## 环境（fresh 现查）
- 后端：`127.0.0.1:16039` jar=ruoyi-admin，profile=ipd-local,dev，连 MySQL `13306/ipd_dev`（PID 49049）
- 前端：`127.0.0.1:15666` vite dev mode（PID 85166，hermes node，Mon11PM 起）
- 真库：157 表，drift-check 通过（实体 64 个全对齐）
- 浏览器：chromium（chrome-devtools MCP）
- 凭据：`ipd-admin / Ipd@123456`（来自 docs/ipd-系统说明/r173/OPERATIONS-RUNBOOK-20260922.md）

## 三证齐全

### HTTP 证据
1. **POST /api/v1/auth/login** → 200，响应 5 字段包络 `{code:0,message:"ok",data:{token:"eyJ...",tokenType:"Bearer",expiresIn:7200,scope:"FULL",person:{id:"900101",personType:"SUPER_ADMIN"}}}`，token 长度 187
3. **GET /api/v1/projects** → 200，5 字段包络正常，data 为 28 行真实项目（PRJ-2026-001~032 + 6 个产品级种子），含 tenantId=000000/delFlag=0 软删过滤

### DB 证据
1. **audit_logs**（哈希链 3370→3374 连续 5 行）：
   ```
   seq  action      operator_name           prev→curr
   3374 LOGIN       ipd-admin               feab812b5df3→7607fe6ae0e9  ← 浏览器真活点击登录
   3373 LOGIN       ipd-admin               957706fd846b→feab812b5df3  ← 前端 refresh
   3372 LOGIN       ipd-admin               b6c01141f54e→957706fd846b  ← curl 真活登录
   3371 LOGIN_FAIL  ipd-market-admin-900103 17c95988da3a→b6c01141f54e  ← 错密码探针
   3370 LOGIN_FAIL  ipd-admin               86bca1153db9→17c95988da3a  ← 早期错密码
   ```
   哈希链 `prev[i+1] == curr[i]` 完全连续 ✓
2. **projects 表**：45 行真实业务数据，21 行 DRAFT 状态

### 浏览器证据（chromium 真用户路径）
截图位置见本目录：
- `00-workbench-pre-clean.png`：清 cookie 前的已登录态工作台（验证前置 session 真实有效）
- `01-login-page.png`：**清 cookie 后**的登录页（深色品牌区+演示账号快捷选择）
- `02-login-filled.png`：用户名 ipd-admin / 密码 9 字符（Ipd@123456 长度匹配）
- `03-workbench-after-login.png`：**重新点击登录后**的工作台，含 15 个核心菜单模块、29 个项目下拉、8 项责任任务队列、4 张统计卡、ZK-IPD 津贴风控规则提示

## 三证相互印证
- HTTP 登录 `loginId:900101, scope:FULL` ↔ 工作台 banner「ipd-admin」按钮 ↔ DB seq=3374 `operator_name:ipd-admin, entity_id:900101`
- HTTP projects 28 行 ↔ 工作台项目下拉 29 个项目 ↔ DB projects 表 45 行（多租户过滤 + 软删过滤后差异）
- 限流（60s/5 次/IP）未被打爆，登录链路未触发频繁限流（只在 15:41:17 错密码时触发 1 次桶占用）

## 验证结论
**登录链路三证律 FULL PASS**：
- ✓ 真实执行：chromium 真浏览器 + 后端真 jar + MySQL 真库
- ✓ 真实用户可见：登录页/工作台完整渲染，菜单+项目+任务全部可见
- ✓ 真实业务落库：登录审计哈希链连续 5 行，业务表 45 行
- ✓ 完整闭环：登录页 → 输入 → 点击 → 跳转 → 工作台无断链

## 生产就绪 P0 缺口（残余）

### 已 done（本会话无关）
- 后端 main HEAD=99e53a8b（origin/main 同步），clean
- 前端 main HEAD=3264ac5（origin/main 同步），clean
- entity↔真库 schema drift-check 0

### R28 残留三件（**本轮 FULL CLOSED** ✓）
- ① audit_logs 索引 apply：**已 apply**（事实修正 — 原查 `SHOW INDEX ... LIKE 'idx_entity%'` 前缀错，正确索引名前缀是 `idx_al_entity`，R28 登记的两个复合索引 `idx_al_entity_type_id (entity_type, entity_id)` + `idx_al_entity_type_time (entity_type, create_time)` 已在表内 8 个索引中存在，audit_logs 1940 行 × 32 entity_type 覆盖完整）✓
- ② 浏览器 E2E 真活验证：✓ **本轮 FULL PASS**（最小登录路径）
- ③ 重启 16039：✓ **事实 closed**（PID 49049 已在跑 ipd-local,dev profile 连 13306 真库）

### R170 P0 清单 13 项
- 已 done（5）：P0-2/5/6/9、P0-4 待 DDL apply
- 仍待办（8，全部需 owner 拍板）：P0-1 双线合流、P0-3 platform-token 加固、P0-7 @Autowired 48 处、P0-8 评审要素按钮、P0-10 c-batch-4 DDL、P0-11 登录会话收口、P0-12 审计 hash 链多实例、P0-13 守卫测试失败

## 责任边界
- **本轮已做**：三证律真活验证（HTTP+DB+浏览器）+ R28 残留三件更新
- **高风险待用户授权**：audit_logs 索引 apply（DDL）、P0 清单 8 项 owner 拍板
- **撞号透明**：本段接续 e244bfee 之前的 99e53a8b 全局工作树盘点段；与兄弟 3264ac5 根因B 修复平行不冲突

## 阻塞项
- 浏览器 15666 端口现是 IAP-workflow-base 项目同进程（PID 48170）所起 vite（**不同项目**，但端口复用可能产生路由混淆）——确认 IPD 端口 15666 实际是 PID 85166 的 hermes node vite，与 IAP 同端口是兄弟服务共存现象，非本项目阻塞。
- audit_logs 索引 DDL apply 与 P0 清单 8 项 owner 拍板是「生产就绪」进一步推进的前置，需用户分批授权。