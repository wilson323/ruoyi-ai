---
source: file:///Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiExecutionEngine.java
collected: 2026-09-27
published: 2026-09-27
topic: ipd-source
---

# AI 执行引擎与节点智能体执行器（源码级摘要）

源文件群：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/`
契约文档：`docs/ipd-系统说明/R236-生命周期节点智能体接线设计-20260927.md`

## 1. AiActionExecutor SPI 契约

文件：`service/aiexec/AiActionExecutor.java`（35 行）

```java
public interface AiActionExecutor {
    Set<String> supportedActionCodes();          // 引擎据此建 code→executor 路由表
    AiExecResult execute(AiAgentTask task, AiExecContext ctx);  // 不抛业务异常
    default boolean supportsSchedule() { return true; }  // SCHEDULE 自动派发过滤
    static AiExecResult terminalNoOp(StageAction action) { ... }  // 终态守卫 DONE/NA → no-op
}
```

- `supportedActionCodes()`：声明本执行器认领的动作码集合，引擎构造时 flatMap 建路由表
- `execute(task, ctx)`：执行单任务，返回结构化结果；异常由引擎捕获进退避/DEAD
- `supportsSchedule()`：默认 true；DEEP 填表族（C08/K01-K04）覆写为 false，扫描器据此二次过滤
- `terminalNoOp(action)`：静态方法，DONE/NA 动作重复触发返回 ok("no-op")，防改写历史完成日

## 2. AiExecContext / AiExecResult 结构

### AiExecContext（`service/aiexec/AiExecContext.java`，13 行）

```java
public record AiExecContext(IpdActor systemActor, Clock clock) {}
```

- `systemActor`：AI_SYSTEM_PERSON_ID=0，身份 `IpdActor(0L, "system", "SYSTEM", null)`
- `clock`：可注入时钟，退避/审计时间戳确定性（测试用 fixed clock）

### AiExecResult（`service/aiexec/AiExecResult.java`，21 行）

```java
public record AiExecResult(boolean ok, String summary, Long aiDocId, String errorMsg) {
    public static AiExecResult ok(String summary) { ... }
    public static AiExecResult ok(String summary, Long aiDocId) { ... }
    public static AiExecResult fail(String errorMsg) { ... }
}
```

- ok=true → 引擎翻 SUCCEEDED，summary 落 `result_summary`，aiDocId 可选
- ok=false → 引擎走退避/DEAD，errorMsg 落 `error_msg`

## 3. AiExecutionEngine（outbox 派发引擎）

文件：`service/AiExecutionEngine.java`（243 行）

### 关键常量与方法行号索引

| 成员 | 行号 | 职责 |
|---|---|---|
| `SYSTEM_ACTOR` | L39 | `IpdActor(0L, "system", "SYSTEM", null)` |
| `MAX_ATTEMPTS` | L40 | 3（≥3 次 DEAD 转人工） |
| `BACKOFF_SECONDS` | L41 | `{30, 120, 600}`（30s → 2m → 10m） |
| `executorByCode` | L44 | 构造时 flatMap 所有执行器的 supportedActionCodes → Map<String, Executor> |
| `wiredActionCodes()` | L77 | 返回已接线路由键集（Set.copyOf），外围按此过滤 |
| `scheduleWiredActionCodes()` | L84 | 可自动派发集 = AI档 ∧ 已接线 ∧ supportsSchedule ∧ 在 ActionCatalog 内 |
| `dispatchAsync()` | L100 | 异步提交派发轮（单线程池 daemon） |
| `dispatchCycle(limit)` | L111 | 扫 PENDING + 到期 FAILED → 逐个 claim + runOne（排除 CHAT 行） |
| `claim(task)` | L134 | 条件 UPDATE 抢占：PENDING/FAILED → RUNNING（乐观守卫，多实例安全） |
| `runOne(taskId)` | L148 | selectById → 路由 executor → execute → finalizeTask（self 代理保事务） |
| `finalizeTask(t, result)` | L172 | @Transactional：成功翻 SUCCEEDED；失败 attempt+1，<3 FAILED+退避，≥3 DEAD |
| `notifyIfDead(t)` | L218 | DEAD 转人工通知（事务外、尽力而为；triggeredBy=null 跳过推送） |

### 退避重试机制

```
attempt 1 失败 → FAILED + nextRetryAt = now + 30s
attempt 2 失败 → FAILED + nextRetryAt = now + 120s
attempt 3 失败 → DEAD（不再重试）+ 通知触发人
```

### 审计留痕

每次 finalizeTask 写审计：`action=AI_EXEC`（成功）或 `AI_EXEC_FAILED`（失败），entityType=`ai_agent_task`，afterData 含 aiAssisted/aiModel/aiRole/status/summary/error/aiDocId。

## 4. 六个执行器（R236 接线完成后磁盘实际状态，2026-09-27）

### 4.1 LightDirectExecutor（51 行）

文件：`service/aiexec/LightDirectExecutor.java`

- **当前认领码**：14 码 `Set.of("P08","P09","P10","D02","D03","D07","D08","D09","D10","V01","V04","V05","L05","LC06")`
- **行为**：`recordFields(id, now, ...)` → `transit(DONE)`，operator="0" 系统身份
- **是否调 LLM**：否（零 LLM，纯确定性）
- **supportsSchedule**：true（默认）
- **终态**：DONE
- **不包含 V11**：动态深度码（SOLUTION 模板下为 DEEP），归 AgentEvidenceExecutor（契约 §7 B3）

### 4.2 DeepDirectExecutor（247 行）

文件：`service/aiexec/DeepDirectExecutor.java`

- **当前认领码**：4 码 `Set.of("C08","D11","V02","L08")`
- **行为**：resolvePayload（人确认载荷）→ JSON 解析 fields → 按 `def.valueFields()` 数据驱动选字段 → recordFields → buildMarkdown → OSS upload → addDeliverable → transit(DONE)
- **是否调 LLM**：否（确定性归集）
- **supportsSchedule**：**false**（无载荷 SCHEDULE 必 fail）
- **终态**：DONE
- **信任收紧**：仅 PASSIVE ∧ triggeredBy≠null 才回捞 CHAT 行载荷；SCHEDULE/EVENT 连查都不查
- **字段映射**（契约 §7.1）：`FAR,FRR` → farValue+frrValue（**成对强制**）；`CERT_NO,CERT_DATE` → certNo+certPassedAt（token 名与字段名不同源）；`LAUNCH_DATE` → actualDoneAt（StageAction 无 launchDate 列、无专属守卫）；`BASELINE`（C08）不落数值只落完成日
- **日期解析复用成熟件**：`org.ruoyi.common.core.utils.DateUtils.parseDate(Object)`，不自写格式分支

### 4.3 GenerateExecutor（142 行）

文件：`service/aiexec/GenerateExecutor.java`

- **当前认领码**：24 码（全 AI_GENERATE）
- **行为**：ActionCatalog.byCode → `docTypeOf(code)` 定 docType → `NodeAgentResolver.systemPromptOf(code)` 取智能体指令 → `aiGenerationService.generate(actor, req)` → transit(**IN_PROGRESS**) → `notifyPm(def.ownerRole())`
- **是否调 LLM**：**是**（经 AiGenerationService.generate 7 道治理）
- **supportsSchedule**：true（默认）
- **终态**：IN_PROGRESS（草稿待人审，绝不代签 DONE）
- **通知**：publishDaily 日级 dedup 防重试刷屏；角色取目录 `ownerRole`（24 码实测 MARKET_PM 14 + RD_PM 10，无 BOTH）
- **R236 消除三处硬编码**（契约 §7 B6）：prompt（原市场营销文案）、docType（原写死 `MARKET_RESEARCH`）、通知角色（原写死 `MARKET_PM`）
- **降级链**：未绑定智能体时，docType 命中 `PromptType` 值域则作为 `promptType` 下发复用既有 8 套 `PromptTemplates`；绑定时 promptType 留 null 避免指令双重叠加

### 4.4 GatePrepExecutor（170 行）

文件：`service/aiexec/GatePrepExecutor.java`

- **当前认领码**：5 码 `Set.of("C11","P13","D05","L07","LC02")`
- **行为**：查 Gate(PENDING) → enabledElements → uploadMd(材料+纪要) → 非否决要素 judge(PASS) → submit → notifyDualPm
- **是否调 LLM**：否（确定性备料 md 模板）
- **supportsSchedule**：true（默认）
- **终态**：取决于 submit 结果——成功则 ok，被人判门槛拒绝则 fail（退避重试）
- **红线**：`isVeto(el)` 命中即 `continue`——否决项绝不代判

### 4.5 KpiSharedReconcileExecutor（163 行）

文件：`service/aiexec/KpiSharedReconcileExecutor.java`

- **当前认领码**：`Set.of("K01", "K02", "K03", "K04")`
- **行为**：reconcileService.reconcile → buildLedger(md) → OSS upload → addDeliverable → notifyLeaders
- **是否调 LLM**：否（确定性复算对账）
- **supportsSchedule**：**false**（台账只由组长 PASSIVE 触发）
- **终态**：不 transit、不代签、不改任何业务表（只读对账 + 交付物留痕）
- **特殊**：不复用 terminalNoOp（K 动作 DONE 后再对账仍合法）
- **归属**：兄弟车道 R232-W14（R236 本轮未碰其文件与码集）

### 4.6 AgentEvidenceExecutor（161 行，R236 新增）

文件：`service/aiexec/AgentEvidenceExecutor.java`

- **当前认领码**：18 码 `Set.of("C07","C09","C10","P02","P12","V03","V09","V10","V11","V12","L02","L06","LC01","LC03","LC04","LC05","LC07","LC09")`（AI_DIRECT ∧ DEEP ∧ 无 valueFields，含动态深度码 V11）
- **行为**：terminalNoOp → `NodeAgentResolver.systemPromptOf(code)` → 绑定则 `generate()` 出证据正文 → **先 OSS 上传再落库状态** → recordFields(now) → addDeliverable → transit(DONE)
- **是否调 LLM**：**是**（经 AiGenerationService.generate 7 道治理）
- **supportsSchedule**：**false**（安全红线，契约 §7 B4）——LLM 产物即 DONE 门禁证据、无独立人审环节，放开调度等于 AI 代签完成；只接受自然人 PASSIVE 触发
- **终态**：DONE + ≥1 交付物（BR-IPD-03）
- **降级不伪造**：未绑定智能体 → `fallbackEvidence()` 只归集已知事实且 md 内显式声明「不含任何 AI 生成结论」+ WARN；**已绑定却生成失败绝不静默降级**，一律 `fail` 走退避重试、≥3 次 DEAD 转人工
- **顺序约束**：先上传后落库，避免 OSS 失败时留下「有完成日却无交付物」的半成品

### 4.7 NodeAgentResolver（80 行，R236 新增支撑件）

文件：`service/ai/NodeAgentResolver.java`

- **职责**：按命名约定 `IPD-<动作码>` 从 `agent_info` 取该节点工作指令（system_prompt）。约定即映射——无映射表、无 system_configs 键、不新增 IAgentService 方法
- **解析入口**：复用既有 `IAgentService.queryEnabledOptions()`（status=0），**精确等值**匹配 agentName
- **禁用** `queryList(AgentBo)`：它对 agentName 用 like，`IPD-C1` 会命中 `IPD-C11`
- **不自建缓存**：PERF-02「配置变更立即生效、不允许 TTL 窗口」
- **降级**：查询抛异常 / 无匹配行 / prompt 空白 → 返回 null（由调用方走显式声明的确定性降级），不抛进 outbox 执行链

## 5. AiGenerationService 7 道治理

文件：`service/AiGenerationService.java`（343 行），核心方法 `generate()` L105-L206

| # | 治理道 | 行号 | 机制 |
|---|---|---|---|
| 1 | SSRF 前置 | L112-120 | `ssrfBlockReason(host)` + allowlist 豁免（SEC-REV-04/R213） |
| 2 | 月度预算预检 | L122-131 | `budgetTokens`（AC-AI-08），已用+预估 > 预算 → BUDGET_EXCEEDED |
| 3 | 并发限流 | L133-139 | `Semaphore(3)` 满载 2s 拿不到 → RATE_LIMITED |
| 4 | RAG 注入 | L142-153 | `docEmbeddingService.retrieveContext` → composePrompt（AI-STRAT-1） |
| 5 | 瞬时失败重试 | L158-166 | TIMEOUT/UNREACHABLE/HTTP_5xx 同请求内补一次（AI-P1-1） |
| 6 | 文档落库 | L176-178 | `documentService.createGenerated`（versionNo=1, status=GENERATED 待审核） |
| 7 | 审计 | L179-195 | AI_GENERATE 成功审计（含 token 计量 AC-AI-09）/ AI_GENERATE_FAILED 失败审计 |

## 6. AiGateway 与 AiChatClient

### AiGateway（`service/ai/AiGateway.java`，319 行）

- IPD 生成主链**已是 langchain4j**：直接 import `dev.langchain4j.model.openai.OpenAiChatModel`
- 三个公开方法：`chat(cfg, prompt, maxTokens, temperature)` / `embed(cfg, texts)` / `stream(cfg, prompt, maxTokens, temperature, handler)`
- SSRF 前置复用 `legacyClient.ssrfCheck()`
- 错误码白名单映射：`mapFailure(Throwable, latencyMs)` → AUTH_FAILED / HTTP_n / TIMEOUT / UNREACHABLE / EMPTY_RESPONSE / UNSUPPORTED_PROTOCOL

### AiChatClient（`service/ai/AiChatClient.java`，340 行）

- **类级 `@Deprecated`**（L41）
- 注释原文（L44-48）：「生成主链已迁 AiGateway（Langchain4j）…新代码禁止直接注入本类做生成调用」
- 保留原因：① ssrfCheck 供 AiGateway 复用；② 单测拦截桩构造器仍在用

## 7. ByteArrayMultipartFile（69 行）

文件：`service/aiexec/ByteArrayMultipartFile.java`

内存产物 → MultipartFile 最小适配器（C08/GatePrep/Kpi 归集 md 上传 OSS 用）。仅实现 `ISysOssService#upload` 链路实际触碰的方法集。

## 8. ActionCatalog 69 码目录

文件：`seed/ActionCatalog.java`（239 行）

- `ALL`：69 个 `ActionDef` record 实例（List.of 不可变）
- `ActionDef` 字段：code / name / stage / ownerRole / depth / blocking / applicable / valueFields / bioFeature / gate / execMode
- execMode 三档：`AI_DIRECT`（40 码）/ `AI_GENERATE`（24 码）/ `HUMAN_GATE`（5 码）
- `byCode(code)`：Z 系别名归一 + 查目录，未知码抛 IllegalArgumentException
- `B_LEVEL_BLOCKING_CODES`：B 级项目阻断集 10 项

## 9. StageActionService.validateCompletion（L281-301）

```java
private void validateCompletion(StageAction a, ActionDef def, boolean deep) {
    if (deep) {
        // BR-IPD-03：深管动作完成前必须 ≥1 未删交付物
        Long cnt = deliverableMapper.selectCount(...);
        if (cnt == null || cnt == 0) throw ServiceException;
    } else if (a.getActualDoneAt() == null) {
        // BR-IPD-05：轻管动作完成必须登记实际完成日期
        throw ServiceException;
    }
    // valueFields 附加校验：FAR/FRR、CERT_NO/CERT_DATE
}
```

## 引用来源

- 契约文档：`docs/ipd-系统说明/R236-生命周期节点智能体接线设计-20260927.md`
- 治理报告：`docs/ipd-系统说明/工作流系统性梳理-20260927.md`
- 源码目录：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/aiexec/`
