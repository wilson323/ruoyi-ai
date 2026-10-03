# AgentScope 官方能力全量启用 — 四项任务收口报告

- 日期：2026-10-03
- 执行依据：owner 2026-10-02 指令「AgentScope 官方能力全量启用、禁止禁用、禁止降级」，覆盖 ADR-0077「按需关闭」口径
- 版本基线：AgentScope Java **2.0.3**（钉死；一切 API 主张以 `~/.m2` 的 2.0.3 jar / sources.jar 与 `/Users/mac/Documents/agentscope-java` 真实源码为终审）
- 运行环境：`127.0.0.1:16039`，jar `ruoyi-admin/target/ruoyi-admin.jar`（mtime `Oct 3 01:12`，PID 58389，profile `ipd-local,dev`）
- 数据库：MySQL 8.0.46 @ `127.0.0.1:13306`，库 `ipd_dev`，运行账号 `ipd_app@127.0.0.1`

---

## 0. 总裁决

| # | 任务 | 裁决 | 关键证据 |
|---|---|---|---|
| ① | 拉起后端做真实人员旅程闭环验收 | **闭环** | 全链 9 站 `code=0`，run `2106272487481290754` 走到 `ARCHIVED` 终态 |
| ② | 查实沙箱快照 take/restore 时机 | **闭环** | 子智能体基于 2.0.3 源码实证（见 §2） |
| ③ | owner 拍板长期记忆与回退模型 | **闭环（已拍板 + 已实施 + 真实落库验证）** | LTM=引入官方 `LongTermMemory` 本地化；fallback=MiniMax 备用；`ipd_agent_memory` 落库 **4 行**（见 §3） |
| ④ | AG-UI 按官方标准 | **闭环** | 盘点 → 实施 → 测试全绿（见 §4） |

**未闭环 / 待他人处置的遗留**（详见 §6）：run 停 `VERIFYING` 驻留态属兄弟会话 Quality 域 V-2 在途工作；fallback 真实切换未在线触发；沙箱 release 竞态为官方 2.0.3 偶发。

---

## 1. 任务①：真实人员旅程闭环验收

- 身份：`ipd-market`（personId `900103`），项目 `9140005`，能力包 `market-research/v1`，动作 `C01`，模型配置 `2104885081318375426`
- 路径：创建运行 `POST /api/v1/projects/{projectId}/agent-runs` → 轮询详情 `GET /api/v1/agent-runs/{runId}` → `WAITING_APPROVAL` 时按 `pauseSeq` + `interruptId` 走 `POST /api/v1/agent-runs/{runId}/resume` 批准 → 产物定档 → `ARCHIVED`
- 结果：全链 9 站 `code=0`，终态 `ARCHIVED`；续跑循环脚本 `/tmp/journey_resume_loop.py`（读 interrupts 全批准直到终态）可复用
- 事实源：run 状态以 `ipd_agent_run.status` 库内现态为准，不以接口回显单独定论

---

## 2. 任务②：沙箱快照 take/restore 时机

结论（2.0.3 源码实证，非文档推断）：

- 快照 **take** 发生在子智能体一次工具执行回合结束、沙箱归还前，用于把该回合的文件系统变更固化；
- **restore** 发生在下一次同一隔离键的沙箱申请命中时，按快照恢复工作区，而不是重新初始化；
- 隔离键收口为四维（租户 / 项目 / 人员 / 运行），与本项目 `KernelScopeKey` 复合槽一致；
- `disableSessionPersistence()` 在 2.0.3 是**空操作**，不能靠方法名判断会话是否落盘（AGENTS.md 既有雷区，本轮复核仍成立）；
- 沙箱 release 存在官方 2.0.3 偶发竞态，非本项目代码缺陷。

---

## 3. 任务③：长期记忆（LTM）— 本轮主战场

### 3.1 owner 决策

- **LTM**：引入官方 `LongTermMemory` 接口并本地化实现（不自造第二套记忆轨）
- **fallback**：MiniMax 备用模型（`MiniMax-M2.7-highspeed`），经官方 `fallbackModel` 装配面挂载

### 3.2 实现形态

| 组件 | 角色 |
|---|---|
| `ProjectScopedLongTermMemory` | 官方 `LongTermMemory` 的本地化实现：按 `(projectId, personId)` 作用域 record / retrieve，落 `ipd_agent_memory` |
| `ProjectAgentLongTermMemoryMiddleware` | 官方 `MiddlewareBase.onAgent(agent, ctx, input, next)` 扩展点：`Flux.defer` + `doOnComplete` 实现 PostCall 语义，召回注入 + 会话记录 |
| `IpdAgentMemoryMapper` | `@InterceptorIgnore(tenantLine="true")` + `INSERT IGNORE` 幂等写入（唯一键 `uk_scope_digest(project_id, person_id, source_digest)`） |
| `AgentScopeProjectAgentKernel` | 装配点；已删除中间态的 `agentStateRef` 预取三处 |

### 3.3 触发链断裂的根因与修复（本轮核心技术成果）

**现象**：IT 复现 `inserts=0`；线上 run 完成后 `ipd_agent_memory` 0 行。

**诊断（三槽对比法）**：在 middleware 内同时取三个 stateCache 槽并打印 `context.size()`：

```
refSlot    userId=1234567  sessionId=1234567                          context=0   ← 预取的 (runId,runId) 槽，空
ctxSlot    userId=p9:u7    sessionId=aipd_project_agent:s1234567      context=2   ← 真正执行身份（trustedScope 复合槽）
noargSlot  userId=null     sessionId=1234567                          context=0   ← 官方无参槽，空
```

**根因**：`ReActAgent.getAgentState(userId, sessionId)` 是 stateCache 按精确 slotKey 缓存的**活引用**；执行链写入的是 trustedScope 复合槽 `KernelScopeKey.of("9","7",AGENT_ID,"1234567")`，而 kernel 预取的是 `(runId, runId)` 槽 → 预取到空槽 → record 拿到 `context=0` 直接 return。

**必须记住的区分**（此前踩坑点）：

> `TemporaryStateStore` **落库层**身份（把 `(null, runId)` / `(runId, runId)` 都映射到 trustedScope 落库键）**≠** `stateCache` **执行层**身份（精确 slotKey 匹配）。落库层结论不可迁移到 stateCache 层使用。

**修复**：

- middleware 改为持有 `agent` + `RuntimeContext`，在 `doOnComplete` 时用
  `agent instanceof ReActAgent react ? react.getAgentState(ctx) : agent.getAgentState()` 取**活引用**；
- kernel 侧删除 `agentStateRef` 预取三处（声明、喂参、set 段）；
- javap 佐证：`ReActAgent` 有 `getAgentState()` / `getAgentState(RuntimeContext)` / `getAgentState(String,String)`；`Agent` 接口只有无参 default；`AgentBase` 无 ctx 版 → **middleware 取执行态必须 cast `ReActAgent`**。

### 3.4 hook 死路 → middleware v2

自研 hook 路径在 2.0.3 无法覆盖 PostCall 全路径；改为官方 `MiddlewareBase.onAgent` 扩展点后全路径触发。符合 owner「只经官方扩展点定制」的裁决（ADR-0077）。

### 3.5 落库层两处阻断（真实运行环境才暴露）

**阻断 1 — 实体字段缺失**

```
ReflectionException: There is no getter for property named 'tenantId' in 'class org.ruoyi.ipd.domain.IpdAgentMemory'
```

成因：`@Insert` 手写 SQL 的 `#{tenantId}` / `#{delFlag}` / `#{remark}` 按实体 getter 反射取值，而 `BaseEntity`（ruoyi-common-mybatis）只有 `searchValue / createDept / createBy / createTime / updateBy / updateTime / params`，**没有这三个字段**。
修复：实体补 `tenantId`（恒 null，租户按 `project_id`）/ `delFlag` / `remark` 三字段；`ProjectScopedLongTermMemory` 构造处显式 `.delFlag("0")`（库列 `NOT NULL DEFAULT '0'`，不给值会撞 Error 1048）。

**阻断 2 — DB 逐表授权缺失**

```
INSERT command denied to user 'ipd_app'@'localhost' for table 'ipd_agent_memory'
```

成因：本机 dev 库运行账号 `ipd_app@127.0.0.1` 是**逐表 CRUD 授权**形态（172 条 `GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.<table>`），新建表时漏了 `ipd_agent_memory`。
修复：root 探针执行 `GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.ipd_agent_memory TO 'ipd_app'@'127.0.0.1'; FLUSH PRIVILEGES;`，并把该语句补进 DDL 文件 `docs/script/sql/update/20261002-ipd-agent-memory.sql` 尾部（含踩坑注释）。

> **可复用规约**：本仓无 Flyway/Liquibase，`docs/script/sql/update/**` 靠人工 apply；**新建表必须同时补 `ipd_app@127.0.0.1` 的逐表 GRANT**，否则表现为「代码全绿、真跑 INSERT denied」。

### 3.6 最终验收证据

日志 `/tmp/ipd-backend-8.log`：

```
[ipd-memory] record triggered run=2106296461246337026 messages=24/26/28/30/32   ← run 7：GRANT 前，触发正常但落库失败已降级
[ipd-memory] record triggered run=2106300308379406337 messages=2                ← run 8
[ipd-memory] run=2106300308379406337 project=9140005 person=900103 extracted=4 saved=4
```

库内 `ipd_agent_memory`（4 行，全部 `run_id=2106300308379406337`、`project_id=9140005`、`person_id=900103`、`status=0` 候选态）：

| kind | content（截断） |
|---|---|
| PREFERENCE | 回复时将关键判断放在最前面 |
| PREFERENCE | 保持简洁 |
| FACT | 项目使用 C01 的七步流程作为调研方法论 |
| OBSERVATION | 用户当前关注园区智慧通行一码通场景的市场空间 |

内容全部来自 run 8 的真实输入，**抽取有效、非空壳**。run 7 的 `messages` 从 24 递增到 32 证明 trustedScope 槽活引用随对话增长 —— 执行身份修复完全生效。

### 3.7 回归

- `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=... test`：**29/29 全绿**（KernelTest 13 + StoreTest 7 + LTM Test 8 + IT 1）
- 实体补字段后 LTM 相关：**9/9 绿**
- 均按「错峰 + 单模块 + 不带 `-am` 不带 `clean`」执行，避免并发假红

---

## 4. 任务④：AG-UI 对齐官方标准

- 盘点 → 实施 → 测试全绿；
- 前端不新开第二条 run/文档 API，仍走 `ProjectAgentController` 单轨；
- 意图仍是既有 `STEP` 的 `kind=INTENT`，未新增事件枚举；未调用 `enablePlanMode()`，未写 `plans/PLAN.md`。

---

## 5. 打包与验证的工程雷区（本轮实测，可复用）

1. **`mvn ... | tail; echo EXIT=$?` 拿到的是 `tail` 的退出码（假 0）**。必须 `> log 2>&1; echo EXIT=$?` 后读日志。
2. **`maven-jar-plugin` up-to-date 跳过**：admin 模块 classes 无变化时 jar 阶段跳过重建，`repackage` 也产不出新 fat jar，表现为「install 成功但 jar mtime 不变、内嵌类还是旧的」。修法：`rm` 旧 jar 强制重建。
3. **fat jar 内嵌类必须字节比对**：用 `python zipfile.ZipFile(io.BytesIO(z.read('BOOT-INF/lib/ruoyi-ipd-3.1.0.jar')))` 取出内嵌类与 `~/.m2` 同类比对，确认新代码真的进了包。
4. **IPD token 有效期 7200 秒**（`expiresIn=7200`）：机器睡眠或跨小时后验证脚本会集体拿到 `code=20001 未认证或凭证失效`，表现为「run 卡在 WAITING_APPROVAL 不动」——**先查 token 再怀疑代码**。凭据从 `.codex/ipd-dev/config/auth-market-current.json` 静默换取，不打印、不入库。
5. **record 触发时机早于 `VERIFYING`**：LTM 验收不必等 run 终态，`doOnComplete` 在 agent 流结束即触发。

---

## 6. 限制、风险与遗留

| 项 | 状态 | 归属 |
|---|---|---|
| run 7 / run 8 停 `VERIFYING` 驻留态不判终态 | 未闭环 | **兄弟会话 Quality 域 V-2 在途工作**（`AgentRunStatus.VERIFYING` + `ProjectAgentArtifactVerifier.java` + `RunHandle/RunService/RunExecutor` 修改），在 01:12 打包前落盘被编入第八包；其全量测试亦在失败（`ProjectAgentModelCatalog` bean 缺失、`SandboxReaper` cron、`but was: VERIFYING`）。按 OPS-09/R25 纪律未擅动、未回滚 |
| fallback（MiniMax 备用）真实切换 | 已装配、7 测试绿，**未在线触发验证** | 本线；需主模型故障注入才能验证 |
| 沙箱 release 竞态 | 偶发 | 官方 2.0.3 |
| 记忆 `status` 仍为候选态 `0` | 设计如此 | 晋升须 owner 拍板，不随运行自动晋升 |

---

## 7. 本轮变更文件清单

主代码：

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentLongTermMemoryMiddleware.java`（改持 `agent`+`ctx`，javadoc 增「执行身份（2026-10-03 实测修正）」节记录三槽教训）
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java`（删 `agentStateRef` 三处）
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/IpdAgentMemory.java`（补 `tenantId` / `delFlag` / `remark`）
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectScopedLongTermMemory.java`（`.delFlag("0")`）

测试：

- `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/kernel/ProjectAgentLongTermMemoryMiddlewareIT.java`（补 `KernelModelRequest` / `Map` import；自建同构 `Sink`，不再引用其他测试类的 private 内部类）

SQL：

- `docs/script/sql/update/20261002-ipd-agent-memory.sql`（尾部追加 `ipd_app@127.0.0.1` 逐表 GRANT + 踩坑注释）

数据库实际变更（已 apply，测试数据按 R214 政策留库）：

- `ipd_agent_memory` 建表 + 4 行 run 8 记忆数据
- `GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.ipd_agent_memory TO 'ipd_app'@'127.0.0.1'`

> 未执行任何 git 提交（用户未明确要求）。
