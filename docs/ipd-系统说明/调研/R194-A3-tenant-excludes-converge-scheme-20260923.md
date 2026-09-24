# R194-§A3 `tenant.excludes` vs `@InterceptorIgnore` 双轨收敛方案（2026-09-23）

> **任务**：R186 §A3 双事实源发现的最小可行方案（R193 §三 R194 子任务）| **模式**：docs-only 调研，撞车 0 严守
> **作者**：evolver 子智能体 | **日期**：2026-09-23 | **工作区**：`/tmp/wt-r194-pack/`

---

## §一 任务背景

**R186 §A3 旧结论**：`tenant.excludes`（yml 白名单，**当时 31 张**）↔ `@InterceptorIgnore(tenantLine="true")`（**2 个 mapper**）= "真实双轨"，建议二选一收敛。

**R186 §八 owner 已拍板**（2026-09-23 收口，supersede §三）：**#2 = A：维持共存 + 文档化**——yml = 通用白名单单一源；`@InterceptorIgnore` = 仅 2 张全局配置表（`ai_model_configs` / `ai_doc_embeddings`）**R184-A bug 必需更强修复**（异步线程/无 LoginHelper 上下文误过滤 `tenant_id='000000'` → 数据查不到）。

**R194-§A3 工作目标**：① 把 §A3 旧结论里 "31 张" 更新到 fresh 数；② 给真实"二选一"评估——诚实呈现 A/B 两路均会 regress R184-A，**沿用 R186 §八 C 方案**才是 R194 唯一可落地方向。

---

## §二 fresh 现状（基线 2026-09-23 现查）

### 2.1 yml `tenant.excludes` 与 mapper 注解精确对账

| 维度 | 现查（2026-09-23）| R186 §A3 旧数 | 增量 |
|---|---|---|---|
| `application.yml` `tenant.excludes` 表数 | **82 张**（line 296-403）| 31 张 | +51（R10/R11/R15/R149/R152/R184-A/KPI2A 多轮补登）|
| `@InterceptorIgnore(tenantLine="true")` mapper | **2 个**（AiModelConfigMapper / AiDocEmbeddingMapper）| 2 个 | — |
| 两机制**表名重叠** | **2 张**（`ai_model_configs` / `ai_doc_embeddings` 同时在 yml 又加 mapper 注解）| 未统计 | **本次新发现** |
| 真表存在性 | yml 82 张全部 IPD 单企业私有部署表，无 tenant 语义 | — | ✅ 已 DDL 实装 |

### 2.2 R184-A 修复依据（不可绕开）

`AiModelConfigMapper.java` line 11-17 注释：

> "即便 `tenant.excludes` 已登记该表，**异步线程/无 LoginHelper 的 IPD StpLogic 上下文**仍可能在 MyBatis-Plus TenantLineInnerInterceptor 拼接 SQL 时落入 `tenant_id IS NULL` 分支并误过滤 `tenant_id='000000'` 行（实测：currentEnabled() 抛 STATE_CONFLICT → ai_doc_embeddings 0 行）。"

`AiDocEmbeddingMapper.java` line 13-16 同义重述。**R184-A 是 yml 解决不了的真实 bug**，不是装饰性双轨。

### 2.3 R186 §八 owner 拍板结论（前置事实）

> "owner 拍板 A=维持共存+文档化；yml `tenant.excludes`=通用白名单单一源；`@InterceptorIgnore`=R184-A 必需例外；收敛回 yml-only 会 regress R184-A；不做 31 表迁移（避 AGENTS.md「数据查不到」红线）"——`后端配置vs消费对账与单一事实源收敛报告-20260923.md` §8.1-#2。

**R194 任务前提**（"二选一收敛"）与 **R186 §八 owner 拍板**（"维持共存"）**直接冲突**。

---

## §三 收敛方案（3 选 1）

| 方案 | 可行性 | regress 风险 | 结论 |
|---|---|---|---|
| **A · 全走 yml，删 2 mapper 注解** | 形式可行 0.5h | ❌ **regress R184-A**（ai_model_configs / ai_doc_embeddings 异步线程误过滤「数据查不到」）| **否** |
| **B · 全走 mapper 注解，删 yml 82 张表** | 形式可行 1h | ❌ **新引入反向风险**：多租户拦截全关，业务表租户隔离失效 | **否** |
| **C · 沿用 R186 §八（维持共存 + 文档化）** ⭐ | ✅ owner 已拍板 | 极低（仅消注释 drift）| **✅ 胜出** |

**A/B 共同硬约束**：yml 是启动期 TenantLineInnerInterceptor 加载的白名单；mapper 注解是运行期类级拦截忽略——**作用阶段与语义不同**，强行二选一失去其中一层防护。

---

## §四 推荐方案 + diff stat

### 4.1 推荐 = 方案 C（沿用 R186 §八）

| 维度 | 结论 |
|---|---|
| yml `tenant.excludes` 82 张 | ✅ 通用白名单单一源（启动期生效）|
| `@InterceptorIgnore(tenantLine="true")` 2 mapper | ✅ R184-A 必需例外（运行期类级拦截忽略，异步线程兜底）|
| 二者关系 | **作用阶段互补，非平行冗余** |

### 4.2 diff stat（方案 C 全量改动）

| 类型 | 改动 | 行数 |
|---|---|---|
| ❌ Java（ruoyi-modules/ruoyi-ipd/src/main/**）| **不动** | 0 |
| ❌ yml `application.yml` | **不动**（保留 82 张 excludes）| 0 |
| ❌ SQL / 真库 / pom.xml / 看板卡 status | **不动** | 0 |
| ✅ docs（本报告）| 新建 | ~110 行 |

---

## §五 撞车 0 风险评估

### 5.1 兄弟会话 in-flight 撞车排查

| 兄弟 worktree | 在盘 | 重叠？ |
|---|---|---|
| `wt-r194-pack`（本会话）| docs-only | — |
| `wt-R184-A`（已落库 `e19857ea`）| AiModelConfigMapper / AiDocEmbeddingMapper 已加注解 | ⚠️ 已闭合，**仅读不碰** |
| `wt-R126-audit` / `wt-R157-*` / `wt-P47` / `wt-R120/R121/R127-*` | 审计链 / entity / UI / SM / fix | 无 |

**结论**：`tenant.excludes` 与 2 mapper 注解相关文件 **0 个未提交改动**，方案 C 无需碰任何已落库代码。

### 5.2 撞车 0 严守声明

- ✅ 仅 `/tmp/wt-r194-pack/docs/ipd-系统说明/调研/` 落档 1 个新文件
- ✅ 未改 `ruoyi-modules/ruoyi-ipd/src/main/**` 任何 Java
- ✅ 未改 `application.yml` / `pom.xml`
- ✅ 未跑真库 / 未启后端 / 未抢端口
- ✅ 未改看板卡 status / 未 `git add` / `commit` / `push`

---

## §六 实施步骤

方案 C = **0 实装 + 1 文档化收口**；本会话仅 `mkdir -p` + `cat > R194-A3-*.md << EOF`，无 Java/yml/SQL/commit 任何操作。

**6.x 红线（不做项）**：

| 红线 | 不做项 |
|---|---|
| ❌ | 任何 `src/main/**` Java 改动（含 mapper 注解 / yml excludes）|
| ❌ | `application.yml` 任何修改（保留 82 张 excludes）|
| ❌ | `mvn spring-boot:run` 启后端 / 真库 SQL / Flyway / DDL / 看板卡 status / `git add` / `commit` / `push` |

**6.y 验证清单**：行数 ≤150 ✅ / 7 段结构完整（§一~§七）✅ / 数字精确（82 张 / 2 mapper / 2 张重叠表）✅ / R186 §八 owner 拍板事实显式引用 ✅ / 撞车 0 兑现 ✅。

---

## §七 owner 拍板点 + 下次刷新触发

### 7.1 owner 拍板点（核心 = 是否重启 R186 §八已闭合议题）

| # | 拍板项 | 建议 |
|---|---|---|
| 1 | **坚持 R194 任务前提**（"二选一收敛"）还是**接受 R186 §八**（C=维持共存）| ✅ **接受 R186 §八**（R184-A 是 yml 解决不了的真实 bug，二选一必然 regress）|
| 2 | 修订 `AiModelConfigMapper.java` line 17 注释消 drift（"31 张 excludes 表对齐本注解"→"R184-A 必需例外，非 31 张表迁移依据"）| ✅ 采纳（R195+ 1h 双修）|
| 3 | "yml 通用白名单 + mapper 注解 R184-A 必需例外"模式写进 AGENTS.md 多租户章节 | ✅ 采纳（R195+ 0.5h，防后人再误判为双轨）|
| 4 | 启动 §3-A（删 2 注解）或 §3-B（80 mapper 加注解）实装 | ❌ **不启动**（regress R184-A 红线）|

### 7.2 下次刷新触发

| 触发 | 动作 |
|---|---|
| owner 拍板 #2 | R195+ 改 AiModelConfigMapper line 17 注释（1h）|
| owner 拍板 #3 | R195+ AGENTS.md 多租户章节补丁（0.5h）|
| 新增 IPD 单企业业务表 | 强制 yml `tenant.excludes` 补登（不动 mapper 注解）|
| 新增 异步线程/无 LoginHelper 访问的全局配置表 | mapper 加 `@InterceptorIgnore` + yml excludes 双登记（R184-A 模式）|
| 兄弟会话在 `tenant.excludes` / `tenantLine` 字段重叠 | 拉通对齐（owner 拍板）|

### 7.3 长期维护约束

> `tenant.excludes` 与 `@InterceptorIgnore(tenantLine="true")` 是作用阶段互补的双层防护——前者启动期白名单，后者运行期类级拦截忽略；二者**不是平行双轨**，不可强行二选一。依据：R184-A（`AiModelConfigMapper.java` L11-17 / `AiDocEmbeddingMapper.java` L13-16）+ R186 §八 owner 拍板 A（2026-09-23）。

**撞车 0 兑现**：本报告 = 仅落档 1 个新文件 / 0 Java 改动 / 0 yml 改动 / 0 SQL / 0 PID / 0 端口 / 0 看板卡 status / 0 git commit。
