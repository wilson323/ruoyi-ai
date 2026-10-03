# 移交说明 · 后台记忆根因修复 + AgentScope 能力面核查

**移交方**：会话 `ruoyi-ai-b0`
**接手方**：本项目任一 `ruoyi-*` 会话（建议 `ruoyi-ai-ae` 或 `ruoyi-ai-49`）
**时间**：2026-10-03 17:30
**本文档是自包含的**——接手方不需要读移交方的对话记录，所有事实都带出处。

---

## 0. 先读这段：一句话现状

从一次真实运行失败 `2106378468009717761` 出发，定位到 4 个缺陷并全部修复，真实服务验证通过；
另做 AgentScope 能力面核查，发现联网能力缺一个环境变量、挖出一个被静默删除的安全控制并已补上防护。
**剩余 5 项待办见第 5 节，其中 2 项需要 owner 拍板。**

---

## 1. 当前运行态（现查，勿沿用任何历史值）

```
后端 PID      73941
包路径        /Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/backups/
              ruoyi-admin.codex-webgovernance-20261003-053db9d75a7e.jar
SHA-256       053db9d75a7e766a5752ebe06675c20a8f1b0392e324fdbfaaf36571e4ff445b
回滚包        ruoyi-admin.codex-final-20261003-e51bb0dccbfe.jar
后端 git HEAD 366d1960
前端 git HEAD bfe605e
前端 vite     127.0.0.1:15666
数据库        127.0.0.1:13306 / ipd_dev（凭据在 .codex/ipd-dev/config/application-ipd-local.yml）
```

**这个包已包含移交方全部代码改动。** 但工作树里可能还有其它会话的未提交改动，接手前请重新现查。

### 验证基线（2026-10-03 10:04 实测）

```bash
cd /Users/mac/Documents/ruoyi-ai
mvn -o test -pl ruoyi-modules/ruoyi-ipd
# → Tests run: 4270, Failures: 0, Errors: 0, Skipped: 26, BUILD SUCCESS
```

> 注意：`-am` 参数会让 `ruoyi-common-core` 因 surefire 分组配置报错，**不要用 `-am`**，直接 `-pl ruoyi-modules/ruoyi-ipd`。

---

## 2. 移交方改过的文件（接手方请勿覆盖）

### 后端 `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/`
```
agent/kernel/ProjectScopedLongTermMemory.java          记忆流空闲期限/重试/兜底期限 + RecordOutcome 回执
agent/kernel/ProjectAgentLongTermMemoryMiddleware.java 记忆失败不再改写业务终态
agent/kernel/ProjectAgentBackgroundMemoryLifecycle.java 排空预算 10→30 秒
agent/kernel/AgentScopeProjectAgentKernel.java          错误分类走 cause 链
agent/kernel/ProjectAgentEventSink.java                新增 onMemoryReceipt
agent/kernel/ProjectAgentRuntimeAccessSink.java        新增转发
agent/kernel/ProjectAgentUsageSink.java                新增转发
agent/kernel/ProjectAgentOfficialToolGovernance.java   web_search 出站治理
agent/config/ProjectAgentModelTransportConfiguration.java 【新增未跟踪】传输层 streamIdleTimeout 5分钟→120秒
service/ProjectAgentRunHandle.java                    实现 onMemoryReceipt
service/ProjectAgentRunExecutor.java                  转发 onMemoryReceipt
agent/model/AgentEventType.java                       新增 MEMORY_RECEIPT 枚举值
```

### 前端 `/Users/mac/Documents/ruoyi-ipd-web/`
```
apps/web-antd/src/api/ipd/project-agent.ts            AgentRunEventType 加 MEMORY_RECEIPT
apps/web-antd/src/views/ipd/_shared/ai-agent/timeline-model.ts        新增 kind:'memory-note' 与 case
apps/web-antd/src/views/ipd/_shared/ai-agent/timeline-model.test.ts  新增 3 项测试
apps/web-antd/src/views/ipd/_shared/ai-agent/run-timeline.vue         KIND_LABEL 补 'memory-note'
```

### 移交方的授权边界（务必继承）
- **未获授权**：Git commit / push / 建分支 / 合并 / 发布 / DDL / 数据删除 / 全局配置修改
- **已获授权**：出站模型调用、本地工程数据库写入、运行包重新加载、恢复已核实故障的 Docker
- 移交方全程**未做任何 git 操作**，也**未 commit**

---

## 3. 已闭环的事项（不要重复做，但可复用其证据）

### 3.1 根因：后台记忆失败把整轮改判 FAILED，且文案与事实相反

**runId 2106378468009717761** 逐秒实证：

| 时刻 | 事实 |
|---|---|
| 21:39:02 | 主模型调用 `9d811210` COMPLETE |
| 21:39:02 | 答案「运行验收连接正常。」**已 TEXT_DELTA 推送并落库**（seq 11-19） |
| 21:39:02 | 收尾并发两个后台记忆模型调用 `5c972682`(10s完成) / `93ae5117`(30s挂死CANCELLED) |
| 21:39:32 | 整轮改判 **FAILED / STREAM_ERROR**，文案「模型输出中断，请稍后重试」 |

对照组：SUCCEEDED 运行 2106345905601953794 同样并发两调用、各 9-10 秒全完成。
**结论：挂起是偶发传输停顿，不是记忆代码的确定性缺陷。**

### 3.2 SDK 层面的真正根因（这是最容易漏掉的一层）

javap 实测 `agentscope-core-2.0.3`：
```
DEFAULT_STREAM_IDLE_TIMEOUT = DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5)
```
**挂死的流在传输层要静默满 5 分钟才反应。** 移交方之前所有的上层修复都是在兜底。

**公开扩展点已落地**：`HttpTransportFactory.setDefault(...)` 是公开静态方法，
`OpenAIChatModel.Builder.resolveTransport()` 在未显式指定传输层时正是走 `getDefault()`。
启动时装一次对全部 OpenAI 兼容端点（MiniMax/DeepSeek/GLM/Kimi）生效。

- 装配类：`ProjectAgentModelTransportConfiguration`（`STREAM_IDLE_TIMEOUT = 120s`，owner 拍板）
- 启动日志实证：`operation=MODEL_TRANSPORT status=CONFIGURED streamIdleTimeout=PT2M default=PT5M`
- ⚠️ **`JdkHttpTransport` 的 `(HttpTransportConfig)` 构造器是包级私有**，必须走
  `JdkHttpTransport.builder().config(...).build()`；且 `JdkHttpTransport` 无公开 `getConfig()`
  （只有 `OkHttpTransport` 有），断言只能针对自己构造的 config 对象，**不得用反射读回**

### 3.3 四处修复 + 自证能红

| # | 缺陷 | 修复 |
|---|---|---|
| 1 | 记忆失败经 `concatWith` 逃逸进主流，改写业务终态 | 改写 `MEMORY_RECEIPT` 持久回执；回执本身写不进才抛 |
| 2 | `error()` 只看顶层 `instanceof TimeoutException`，包装后误报 STREAM_ERROR | `classifyStreamFailure()` 走 cause 链，日志增打 `rootErrorType` |
| 3 | 无空闲期限，传输挂起空转满 30 秒才暴露 | 5s→**10s** 空闲期限 + 有限重试 **1 次** + 兜底 **45s** |
| 4 | 排空预算 10s 与健康调用实测上限 10s 完全重合 | `QUIESCE_BUDGET_SECONDS` 10→**30s** |

**预算语义务必理解**（移交方为此返工 3 次）：
- **空闲期限抓「死流」**，10 秒内暴露，挂死发现速度只由它决定
- **兜底总期限只抓「活着但异常慢」**，理论上界 20.5s ≪ 45s，健康区间永不被触及
- 曾按「总次数」理解 `Retry.fixedDelay(n,·)` 导致算错（n 是**重试次数**，总调用 n+1），常量已改名 `EXTRACT_RETRIES`
- 教训铁律：**设参数必须有对应实测**。已有行为测试 `slowButAliveStreamIsNotAbandoned` 钉住「慢但活着≠死了」

**自证能红记录**：
- 整链测试 `memoryExtractionFailureDoesNotFlipBusinessTerminalState` 在旧行为下失败信息为
  `业务终态必须是成功而非失败，errors=[STREAM_ERROR]` —— 原故障的精确复现
- `classifyStreamFailure` 还原旧判据后 2 条超时包装分类测试确定性失败
- `webSearchBlockReason` 闸门拆除后测试立即失败

### 3.4 web_search 出站治理（移交方本轮新做）

**背景**：文档三处声称 `ToolsConfig` 拒绝 web 工具并列为**安全风险 R1 的「已覆盖」缓解措施**，
但该 deny 已于 commit `b757fa7a`（commit message 仅 `"test"`，无任何说明）删除，
当前 `AgentScopeProjectAgentKernel:435` 是 `new ToolsConfig()` 空对象。**依据此条做过的安全判断全部失效。**

**风险形态与 web_fetch 不同**（别照搬）：`web_fetch` 由调用方给 URL，威胁是 SSRF；
`web_search` 目标端点在 SDK 内写死为 `https://api.tavily.com/search`，**没有 SSRF 面**。
真实风险是：①`query` 完全由模型生成，受提示词注入影响可能夹带凭据发往第三方；
②检索结果（标题/URL/摘要）是外部可控内容，会流回模型上下文构成二次注入。

**已做**：新增 `webSearchBlockReason`（长度上限 500 + 凭据词表硬拦，词表沿用
`ProjectScopedLongTermMemory`），在 `checkPermissions` 与 `callAsync` **两处**各拦一次
（官方只读工具可能被 EXPLORE/ACCEPT_EDITS 预先放行）。

**未做（诚实登记）**：风险②「检索结果内容不可信回流」属内容可信度分级问题，
需独立设计决策，代码注释与文档均写明**未解决**。

**注意**：该治理**尚未被真实触发过**（`web_search` 仍缺 `TAVILY_API_KEY`），
现状是「已实现 + 自证能红」，**不是「已实跑验证」**。

### 3.5 记忆分工（owner 已拍板，见文档）

已落档 `AgentScope归位-transcript-plan-skill-memory-20261002.md`：
- **官方文件记忆**（`memory/*.md`）= 单次运行内的会话工作上下文，随沙箱快照归档
- **自研 `ipd_agent_memory` 表** = 跨运行跨会话的个人工作笔记，按 `project_id`+`person_id`
  隔离、强制非权威标注、IPD 权限/审批/Gate 一律不查
- **两者都保留不合并，明确不建同步代码**

### 3.6 文档更正（三处基于已删除保护的安全判断）

已按实测事实更正、加删除线保留原句：
- `AgentScope归位-subagent-team-20261002.md:455` 与 `:625`（R1 缓解措施）
- `项目智能体能力提升研究-20260930.md:54`
- `项目智能体全局优化计划-对照Agent设计原理-20260930.md:50`
- `AgentScope归位-sandbox-workspace-20261002.md:324`

### 3.7 纠正两条审计误判（避免接手方被误导）

1. **「根智能体拿不到 spawn 工具，子智能体链路在业务上不可达」——不成立。**
   实测：浏览器真实就绪接口显示 `agent_spawn`/`agent_list`/`agent_send`/`task_list`/
   `task_output`/`task_cancel`/`wait_async_results` **七个全部可用**；
   SDK `HarnessAgentBuilderSupport:211-217` **无条件**加入内置 `general-purpose` 子智能体条目；
   `ProjectAgentChildLineageRegistry` 血缘治理**生产已接线**（内核 448/460/462/576，
   SDK `DefaultAgentManager:222` 确实回调项目绑定）。**不要按「缺工具」去改。**
2. **「官方文件记忆里有真实业务内容」——未证实。** 审计称沙箱快照中 2 个 `MEMORY.md`
   含真实内容，移交方本地查见 0 个，**不采信**。

---

## 4. 关键实测数据（可复用，不必重测）

### 4.1 后台模型调用耗时分布
```
健康调用 9 次：3/3/4/5/5/5/9/10/10 秒
换包后 3 次：3/9/14、5/7/11、3/8/10 秒
```
**最长 14 秒**（经 inputTokens 三重佐证确认那次是**记忆抽取本身**，非官方压缩调用）
→ 空闲期限必须 > 14s，10s 是合理下限。

### 4.2 工具真实使用分布（311 次 TOOL_CALL）
```
project_knowledge_search 160（最高频）  list_files 31      FastGPT 外部 MCP 26
read_file 16          todo_write 15      memory_search 15  load_skill_through_path 9
execute 6（沙箱内 Shell）                 plan_* 13        write_file 3   memory_save 3
web_search / edit_file / agent_spawn 原为 0 次
```
注：`web_fetch` 已在移交方后续验证中破 0（`TOOL_RESULT state=SUCCESS`）。

### 4.3 上下文压缩永远不触发（重要）
```
压缩触发线 FALLBACK_TRIGGER_TOKENS = 160000（javap 实测）
实测最大上下文 63640、平均 11076、maxIters=8
```
**按当前业务量级压缩永远不会触发**，因此「已实现」不能算「已验收」。

### 4.4 真实运行验证记录
| runId | 说明 |
|---|---|
| 2106378468009717761 | 原故障（已修复） |
| 2106391297689497602 等 8 次 | 记忆回执链路验证，全部 SUCCEEDED，每次都有回执 |
| 2106410414047776770 | `web_fetch` 首次真实调用成功 |
| 2106417336746528769 | 委派测试——模型**未选择**委派，0 工具调用 |
| 2106434621112623106 | 新包（web_search 治理）上验证，全链贯通 SUCCEEDED |

---

## 5. 剩余待办（移交核心）

### 【A】子智能体真实委派实跑 —— 成本低，建议先做

**现状**：spawn 工具在根智能体上可用，内置 `general-purpose` 条目存在，血缘治理生产已接线。
**实测 runId 2106417336746528769 明确要求模型委派，模型仍直接回答，0 工具调用。**

**根因判断**：内置 `general-purpose` 描述是「**与主智能体能力相同**」，
模型没有任何理由委派。真正要委派，须声明**有差异化能力**的子智能体。

**接手方要做**：
1. 先只读复核上述前提（工具是否真在、是否有内置条目），确认审计误判已纠正
2. 跑一次真实委派（浏览器真实链路，见第 6 节）
3. 若仍不委派，判断是「prompt 未引导」还是「需声明专用子智能体」——
   **后者是产品设计决策，需 owner 拍板，不要自己拍**

### 【B】超长上下文压缩实跑 —— 成本高，需 owner 授权额度

**这是把压缩从「接线」变成「已验收」的唯一路径**（日常量级永不触发，见 4.3）。
需构造一次 ≥16 万 token 的对局，真实消耗模型额度。**建议先取得 owner 明确授权再动手。**

### 【C】web_search 剩余风险 —— 需 owner 拍板
风险②「检索结果内容不可信回流模型上下文」需内容可信度分级设计。
另：`TAVILY_API_KEY` 只能由 owner 提供；配密钥前建议先完成该设计，
否则等于开一个无防护的、由提示词注入可控的出站通道。

### 【D】5 项真库测试受阻
`Qa04MysqlConcurrencyTest` 5 项报 `Access denied for user 'qa04_runner'`。
账号在 MySQL 中存在，但**口令未记录于 `.codex/ipd-dev/config`**。
重置口令属**未授权 DDL**，移交方**未执行**，如实记为受阻。
需要 owner 授权或提供口令。

### 【E】业务面大范围未验收
- 全业务动作（69 项）、六阶段流程端到端验收：**未做**
- 官方完整能力验收：**未做**
- 智谱 GLM-5.3-Flash 备用模型真实切换：**未验**（库中 `is_active=0`，
  fallbackModel 装配生效、零触发）

---

## 6. 接手方操作要点（踩过的坑）

### 6.1 换包流程
```bash
cd /Users/mac/Documents/ruoyi-ai
mvn -o clean package -DskipTests
# ★ 先验证再发布：对包内类取证，不看源码、不信 target
OUT=/tmp/jarx-verify-$$; mkdir -p "$OUT"
unzip -o -q <新包> 'BOOT-INF/lib/ruoyi-ipd-*.jar' -d "$OUT"
javap -p -cp "$OUT/BOOT-INF/lib/ruoyi-ipd-3.1.0.jar" <要验证的类>
# 换包：先确认无活动运行 → kill 旧 PID（等 30s，超时再 -9）→ nohup 启动
```
⚠️ 别写 `rm -rf /tmp/xxx` 这类命令，会被项目 Layer 3 闸门误判为危险命令而拦截。

### 6.2 浏览器真实链路（比 API 脚本可靠）
```bash
# 前端 token 会随后端换包失效，.env.development.local 已被删除、
# Vite 进程内那份是启动时烘焙的 → 只能靠浏览器演示账号快捷登录重取
```
- 登录页点「市场 PM（ipd-market）」→「登录工作台」即可（密码由 Vite 启动时烘焙）
- 打开「AI 副驾」→「项目智能体」→ 选项目 #9140005「熵基互联+智能锁联动」
- **发送消息用键盘 Enter 事件**，`evaluate_script` 里按文本找发送按钮经常点不中
- 换包后必须重新登录

### 6.3 数据库只读查询
```bash
cd /Users/mac/Documents/ruoyi-ai
python3 /tmp/ipd-q.py "select id,status,error_code from ipd_agent_run order by id desc limit 5;"
```
（该脚本已备好，不打印凭据；表名是 `ai_model_configs` **复数**、事件表列名是 `event_type` 不是 `kind`）

### 6.4 测试基线
```bash
mvn -o test -pl ruoyi-modules/ruoyi-ipd            # ✓ 正确
mvn -o test -pl ruoyi-modules/ruoyi-ipd -am        # ✗ ruoyi-common-core 会因 surefire 分组报错
```

---

## 7. 跨会话协作须知

本轮曾发生两次红基线，均由**其它会话的脏工作树**造成：
1. `GateReviewService.sign()` 的项目归属守卫（ruoyi-ai-cf）导致 25 项 Gate 签署测试红——已修
2. `AuditRollbackCounterAspect.java:79` List 赋给 Set 导致整个 ipd 模块 223 错误（ruoyi-ai-ae）——已修

**接手方务必先与 `ruoyi-ai-ae`、`ruoyi-ai-49` 对齐当前在途改动，再动手。**
本项目工作树有 200+ 处未提交改动，来自多个会话，**都不要自行 commit**。

---

## 8. 状态源（回写位置，接手方继续在此登记）

| 文件 | 用途 |
|---|---|
| `docs/ipd-系统说明/验收/知识库MCP接入-20261001/codex-full-stack-candidate-20261003.json` | `continuationCurrent` 存当前 PID/包/SHA + 全部根因证据；`supersededRuntimeIdentities` 存历史身份 |
| `docs/ipd-系统说明/验收/知识库MCP接入-20261001/codex-full-stack-implementation-20261003.md` | 实施记录与根因详述 |
| `docs/ipd-系统说明/开发计划-看板镜像.md` | R242 事项镜像（看板唯一登记处） |
| `docs/ipd-系统说明/log.md` | 每轮留痕 |
| `~/.cursor/projects/Users-mac-Documents-ruoyi-ipd-web/canvases/ipd-execution-plan.canvas.tsx` | 总画布（唯一执行顺序） |

> **回写纪律**：运行态身份是**分钟级易变事实**。更新时把旧值移入历史留档，
> **不要直接覆盖**；无法现查的字段（如冻结输入数）**置 null**，不要沿用旧值充当现状。
> `sourceAheadOfLoadedRuntime` 这类比较性布尔，比较对象变了就不能沿用。
