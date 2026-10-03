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
| `ProjectAgentLongTermMemoryMiddleware` | 官方 `MiddlewareBase.onAgent(agent, ctx, input, next)` 扩展点。**本报告落盘时为本线版本（`doOnComplete` 无条件 record）；02:10–02:13 被兄弟会话演进为「仅根 agent 正常完成才 record」，详见 §8** |
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
| run 7 / run 8 停 `VERIFYING` 驻留态不判终态 | **根因已更正**：不是「兄弟漏接端点」，而是「端点已由兄弟在途补齐、但运行包是 01:12 旧包未加载」。待重新打包即可推进 | 兄弟会话 Quality 域 V-2；证据与处置见 §8 |
| run 8 即使复检也不会转 `SUCCEEDED` | 已确证 | 产物正文首行是 `**背景核对 + 待澄清**`（粗体非标题），`doc.heading.structure` BLOCK 规则 FAIL（`doc.placeholder.content` PASS，占位标记 0 处）→ 复检幂等保持 `VERIFYING`，属正确行为而非缺陷 |
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

---

## 8. 并发写入事实登记与结论更正（02:05–02:15 实测）

本节记录一次真实的兄弟会话并发写入，以及它如何推翻本报告中先前的一个结论。**结论必须绑当下证据，快照会被并发写入推翻。**

### 8.1 被推翻的结论

先前判定：「`reverify` 零调用点，兄弟会话 Quality 域 V-2 漏接 controller 端点，`VERIFYING` 是死路」。

该判定在 01:47 的 grep 快照上成立（当时 `controller/` 目录 `reverify` 命中 0），但**在 02:05 已不成立**。

### 8.2 三层证据（同一时刻对比）

| 位置 | `reverify` 端点 | 证据 |
|---|---|---|
| 工作树源码 | **有 1 处**（未提交） | `ProjectAgentController.java:186-192`，注释「本人驻留产物复检，复用原运行及权限，不创建第二发送轨」 |
| HEAD `67cd2b50`（兄弟 01:41） | **无** | `git show HEAD:...Controller.java \| grep -c reverify` = 0 |
| 运行中 jar（01:12 第八包） | **无** | fat jar 内嵌 `ProjectAgentController.class` 字节中查无 `reverify` 字样 |

→ 真正根因是**「源码已写、进程仍是旧包」**（AGENTS.md 既有雷区：源码已提交但进程是旧包时不能写成已生效），不是设计缺口。

前端侧早已对齐（兄弟提交 `8c08585`，01:42，「运行状态机接入 VERIFYING 校验中状态」）：

```ts
// ruoyi-ipd-web/apps/web-antd/src/api/ipd/project-agent.ts:147
export function reverifyAgentRun(runId: string): Promise<AgentRunReceipt> {
  return ipdPost<AgentRunReceipt>(`/agent-runs/${seg(runId)}/reverify`, {});
}
```

前端测试还断言了 URL 与返回 `{runId, status}` —— 因此补端点**不会**触发 API 契约孤儿棘轮门禁（R212）。

### 8.3 本线的误操作与还原

基于 01:47 的过期快照，本线向 controller 插入了一个 `reverify` 端点，造成**重复 mapping**（两个 `@PostMapping("/agent-runs/{runId}/reverify")` + 两个同名方法），Spring 启动必报 `Ambiguous mapping`。

- 发现方式：SearchReplace 返回的 diff 上下文里出现了非本次插入的 `/** 本人驻留产物复检… */`
- 处置：**立即完整还原本线插入的 18 行**，保留兄弟原版；还原后 `grep -c 'PostMapping("/agent-runs/{runId}/reverify")'` = 1
- 结果：工作树未被污染，未产生任何残留

> **可复用规约**：在多会话共享工作树上，**编辑前的 grep 快照会在分钟级失效**。对 `M` 状态（兄弟在途）文件做任何插入前，必须在同一条命令里重新 grep 目标符号确认不存在；SearchReplace 返回的 diff 上下文若出现非本次插入的同类代码，立即判定为并发冲突并还原。

### 8.4 兄弟会话对本线 LTM middleware 的演进（在途，未提交）

`ProjectAgentLongTermMemoryMiddleware.java` 是本线本轮创建的 untracked 文件，02:10 与 02:13 被兄弟会话连续改写两次（5223 → 5486 字节）：

| 维度 | 本线原版（01:12 包，§3.6 落库证据所依据） | 兄弟 02:13 版 |
|---|---|---|
| 记录时机 | `doOnComplete` 无条件 record | 仅 `rootSucceeded && !suspended` 才 record |
| 暂停 / 取消 | 仍会 record | `RequireUserConfirm` / `RequireExternalExecution` / `RequestStop` / `ExceedMaxIters` → 不 record |
| 结束原因 | 不区分 | 仅 `MODEL_STOP` / `STRUCTURED_OUTPUT` 视为正常 |
| 子智能体 | 不区分 | `event.getSource()` 非空（子）→ 不 record |
| 召回 | 同步 `block()` | 异步 `Mono<AgentInput>` |
| 失败语义 | 内部吞错为空 | 向主运行传播 |

**保留未动的部分**：本线的核心修复 `getAgentState(ctx)`（trustedScope 复合槽活引用）与 javadoc「执行身份（2026-10-03 实测修正）」节被完整保留 —— §3.3 的三槽结论仍然有效。

**对已验收证据的影响**：§3.6 的 4 行落库是在**原版语义**下取得的，证据本身不受影响（既成事实、库内可查）；但**新版语义下同样输入未必再触发 record**（run 8 的产物是澄清提问并停 `VERIFYING`，新版会因暂停/非正常结束而跳过记录）。因此 §3.6 不可作为新版行为的验收依据，须在打包新版后重新取一次运行证据。

### 8.5 打包决策：让路，未打包

02:15 实测到兄弟会话仍在活跃工作，本线**主动放弃打包**：

- PID 35669（启动 16 秒）：兄弟的 `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dgroups= test`（空 `groups` = 绕过 `@Tag` 过滤跑全量）
- middleware 文件 3 分钟内第二次变动（mtime 02:13）

理由：此时打包会 ①与兄弟构建交叉重写 `target/` 制造假红（AGENTS.md「假红陷阱」）②装入正在编辑的中间态源码 ③02:09 曾实测到中间态编译错 `GenerateReason.TOOL_RETURN_DIRECT` 找不到符号 —— javap 终审 AgentScope 2.0.3 的 `GenerateReason` 只有 11 个常量（`MODEL_STOP / TOOL_CALLS / STRUCTURED_OUTPUT / TOOL_SUSPENDED / REASONING_STOP_REQUESTED / ACTING_STOP_REQUESTED / PERMISSION_ASKING / MIDDLEWARE_STOP_REQUESTED / ALL_TOOLS_DENIED / INTERRUPTED / MAX_ITERATIONS`），**从来没有 `TOOL_RETURN_DIRECT`**；02:13 后该错误引用已消失，`test-compile` 恢复 `BUILD SUCCESS`。

### 8.6 本线在让路前完成的验证

- `mvn -o -pl ruoyi-modules/ruoyi-ipd test-compile` → `BUILD SUCCESS`（02:13）
- LTM 回归 `ProjectScopedLongTermMemoryTest + ProjectAgentLongTermMemoryMiddlewareIT + AgentScopeProjectAgentKernelTest + ProjectAgentTemporaryStateStoreTest` → **36/36 全绿**（7 + 1 + 15 + 13；`ProjectScopedLongTermMemoryTest` 由兄弟从 8 扩到 15）
- ⚠ 该轮 36/36 与 02:13 那次改写时间重叠，**须在文件稳定后重跑一次才算数**

### 8.7 待办（打包窗口打开后）

1. ~~确认兄弟 mvn 结束 + middleware 文件 mtime 稳定~~ → **已完成**（见 §9）
2. ~~重跑 LTM 回归~~ → **72/72 全绿**（见 §9.1）
3. ~~install → rm 旧 jar → package → 字节比对~~ → **第九包 02:25 成功**（见 §9.2）
4. ~~重启 + 验证 reverify 端点~~ → **端点生效 code=0**（见 §9.3）
5. ~~新版语义下重取 LTM 运行证据~~ → **run 9 落库 4 行**（见 §9.4）
6. ~~run 7 / run 8 复检预期~~ → **已验证，幂等保持 VERIFYING**（见 §9.3）

---

## 9. 第九包收口：reverify 上线 + 新版 LTM 语义运行验证（02:19–02:32 实测）

### 9.1 接手一个阻塞编译的笔误（R25 三步法）

02:19 尝试 `install` 失败，定位为兄弟会话新建的 untracked 文件留下一个语法错：

```
ProjectAgentSkillBundle.java:[32,116] 需要')'
```

等待 100 秒后文件 mtime 仍停在 02:20、错误依旧 → 判定**不是写入中间态，而是真实笔误**（第 31–33 行 `if` 条件缺一个右括号：行末的 `)` 只闭合了 `anyMatch(`，`if(` 本身未闭合）。

- **处置结论（R25 ①）**：零歧义笔误，**修改后入库**——仅补 1 个 `)`，不改任何判定语义
- 修后 `test-compile` 恢复 `BUILD SUCCESS`（`Arrays` 已 import，无连带问题）
- 本接手事实已登记 `docs/ipd-系统说明/log.md`（R25 ②，marker `sibling-ninth-package-reverify-20261003`）。**诚实更正**：本行初稿写在登记动作之前，02:47 复查 `grep -c "SkillBundle\|58551\|332586907" log.md` = **0** 才发现声明未兜现，已于 02:47 真实补写入（+14 行）

> 该笔误阻塞的是**整个 ruoyi-ipd 模块编译**，因此也阻塞了 reverify 端点上线；不修则无法打包。

### 9.2 打包前验证与第九包

- 打包前回归 **72/72 全绿**：`RunServiceLifecycleTest 11`（含 reverify 三态）+ `TemporaryStateStoreTest 7` + `LongTermMemoryMiddlewareIT 1` + `ScopedLongTermMemoryTest 15` + `KernelTest 13` + `FallbackModelAssemblyTest 14` + `ControllerTest 11`
- `install` ruoyi-ipd → m2 jar 02:25（3,704,913 字节）；`rm` 旧 fat jar 后 `package` → **第九包 02:25，332,586,907 字节**
- 内嵌类字节比对 5/5 OK：`ProjectAgentController.class` 含 `reverify`、`ProjectAgentLongTermMemoryMiddleware.class` 同时含 `rootSucceeded`（新版语义）与 `getAgentState`（本线 ctx 槽核心修复）、`ProjectAgentSkillBundle.class` 存在、`IpdAgentMemory.class` 含 `tenantId`
- 重启：旧进程 58389（第八包）已退 → 新进程 **PID 58551**，`Started RuoYiAIApplication in 17.607 seconds`，health 401 正常，**无 `Ambiguous mapping`**（反证 §8.3 的重复端点已完整还原、未进包）

### 9.3 reverify 端点上线验证

| 调用 | 旧包（01:12） | 第九包（02:25） |
|---|---|---|
| `POST /api/v1/agent-runs/2106300308379406337/reverify` | 无此端点 | `code=0`，`status=VERIFYING` |
| `POST /api/v1/agent-runs/2106296461246337026/reverify` | 无此端点 | `code=0`，`status=VERIFYING` |

两个 run 复检后**幂等保持 `VERIFYING`**（`finished_at` 仍 NULL）——符合预期：产物确实缺标题，BLOCK 缺口未消失。至此 `VERIFYING` 不再是死路（复检 + cancel 两条 HTTP 出路均生效）。

### 9.4 run 9：新版 LTM 语义下的运行证据（替代 §3.6 作为新版验收依据）

run `2106315966815264770`（project 9140005 / person 900103 / C01 / market-research@v1），输入明确要求「不提问直接产出 + Markdown 标题分节」：

```
[02:31:06] status=WAITING_APPROVAL pauseSeq=79   ← 暂停轮次：新版语义下未 record（符合设计）
    resume 1 -> code=0
[02:31:55] status=SUCCEEDED

[ipd-memory] record triggered run=2106315966815264770 messages=11
[ipd-memory] run=2106315966815264770 project=9140005 person=900103 extracted=4 saved=4
```

**新增 4 行**（`ipd_agent_memory` 总计 8 → **12 行**）：

| kind | content（截断） |
|---|---|
| PREFERENCE | 用户偏好直接输出结论，不要向其提问、不要等待其确认 |
| PREFERENCE | 用户偏好报告用 Markdown 标题分节，明确指定 ## 关键判断、## 市场空间、## 依据与不确定性 三节结构 |
| OBSERVATION | 本轮对话未产生任何新的项目事实或客户验证材料，工作区与长期记忆中仍无访谈纪要、走访记录、意向书等一手证据 |
| FACT | 本轮调研范围「园区智慧通行一码通」与现行项目定位「熵基互联 + 智能锁联动」相关但不完全等同，范围对齐尚未完成 |

**两项语义结论**：

1. 兄弟收紧后的新版**未破坏记忆链**：暂停轮次不 record、resume 后正常完成（`MODEL_STOP` + 根 agent）才 record 一次 —— 正是其设计意图（避免暂停/子智能体/失败时重复抽取）
2. **对照实验坐实 verifier 判定正确**：

| | run 8 | run 9 |
|---|---|---|
| 产物首行 | `**背景核对 + 待澄清**`（粗体，无标题） | `## 关键判断`（Markdown 标题） |
| `doc.heading.structure` | FAIL → BLOCK | PASS |
| 终态 | `VERIFYING`（幂等驻留） | **`SUCCEEDED`**（`finished_at` 17:31:49） |

→ `VERIFYING` 不是缺陷，而是产物质量的真实反映；§6 中「run 8 即使复检也不会转 SUCCEEDED」的判断已被实测证实。

### 9.5 02:42–02:47 现查复核（回答「为什么卡住了」的当前态）

本节全部为 02:42–02:47 重新现查的 A 级证据，不沿用早前快照。

**① 库级对照实验（坐实驻留根因，不再依赖产物首行目测）**

```sql
SELECT run_id, content REGEXP '(^|\n)#{1,6}[ \t]+[^ ]' AS has_heading, CHAR_LENGTH(content)
  FROM ipd_agent_artifact_version WHERE run_id IN (…run7, run8, run9);
-- run7 2106296461246337026 → has_heading=0, 177 字
-- run8 2106300308379406337 → has_heading=0, 758 字
-- run9 2106315966815264770 → has_heading=1, 1240 字
```

三个运行的唯一变量就是产物是否含 Markdown 标题，`doc.heading.structure`（BLOCK）因此是 run7/8 停 `VERIFYING` 的确定根因。run7/8 `finished_at` 仍为 NULL、`status=VERIFYING`；reverify 重放仍 `code=0, status=VERIFYING`（traceId `e20378c1…`，02:42:29）。

**② verifier 路径已被主协调会话移位**：现态为 `agent/service/ProjectAgentArtifactVerifier.java`（不再是 `agent/kernel/`）；规则未变（HEADING `(?m)^#{1,6}[\t ]+\S`、PLACEHOLDER 命中 ≥2 才 FAIL、两条均 BLOCK，`ACTION_RULES = Map.of()` 仍空）。本报告 §2/§6 引用旧路径处按本节为准。

**③ 新发现：孤儿进程 56119（半关状态，连接池泄漏）**

| 现查项 | 结果 |
|---|---|
| `ps` | PID 56119，lstart `Sat Oct 3 00:18:50`，etime `02:21:43` |
| `lsof -iTCP:16039 -sTCP:LISTEN` | **仅 58551** 持有；56119 无任何 LISTEN 端口 |
| 56119 stdout `/private/tmp/ipd-backend-3rd.log` | mtime **00:33:12**（停更 ≈2 小时），尾部为 `LaunchedClassLoader.loadClass` 异常栈 |
| 56119 到 13306 的连接 | ≥**7** 条 ESTABLISHED |

成因：fat jar 被本线 `rm` 重建后，旧进程的 `LaunchedClassLoader` 无法再加载类，web 容器已关但 JVM 未退。**影响判定**：它无 HTTP 端口、且 00:33 后已无法加载新类，因此不可能创建运行或改写 run 状态——本报告的 `[ipd-memory]` 证据归属不受污染；但它白占 ≥7 条 MySQL 连接并持有共享日志 FD（`logs/sys-info.log` 等）。因 `AGENTS.md`「不杀其他端口进程」约束，**本线未擅自 kill**，留主协调会话/用户裁决。

**④ 第九包 ≠ 当前工作树**：02:47 `git status --porcelain | wc -l` = **27**（HEAD 仍 366d1960），主协调会话在 02:25 后继续改 agent 域（技能评审/发布链：`ProjectAgentSkillPublisher` / `ProjectAgentSkillReviewService` / `AgentSkillReviewReq` 等 untracked，及 `ProjectAgentController` / `RunService` / `ModelAssembler` 等 M）。要验收其新链路需第十包；本线**不抢打包窗口**。

**⑤ 当前真正待拍的设计缺口（非缺陷）**：run7/8 产物已定稿不可改 → 复检恒返回 `VERIFYING` → 实际出路只剩 `cancel`（转 `CANCELLED` 失败终态）。「产物结构缺口能否经人工修订或重新生成后复检转 `SUCCEEDED`」需 owner 拍板，属 Quality 域 V-2/V-3 范围，不在本线四项任务内。
