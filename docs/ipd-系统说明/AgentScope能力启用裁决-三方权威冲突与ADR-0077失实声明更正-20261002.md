> ## ⚠️ 效力声明（2026-10-02 21:39 补记，必须先读）
>
> **本文件 §1.1–§1.3、§1.7 步骤 1–2 的核心前提已失效。**
>
> 21:39 实测：**全仓主源树 AgentScope `disable*()` 调用 = 0**。§1.1 记述的「7 个 disable」
> 是 **14:36 的历史快照**，兄弟会话已在 14:58 / 15:09 / 21:04 分批移除（`log.md:13500-13520`
> 记录了第五会话与第六会话之间的一轮拉锯）。**主协调者当时未重采基线、持续播报过期快照，
> 属观测纪律失误**，在此显式更正。
>
> 剩余 4 处 `.disable` 命中经逐条核实**与 AgentScope 能力无关**：
> `GateElementController.java:65`（业务 `gateElementService.disable(id)`）、
> `AiCopilotService.java:185/370/553`（字符串字面量 `"config.disabled"`）。
>
> **仍然有效的章节**：§1.4（镜像版本陷阱）、§1.5（owner 裁决）、§1.6（官方三开关的字节码实证——
> 那是对官方 API 的分析，与本仓是否调用无关）、§1.7 步骤 3–4（门禁升级）、§1.8（自我反思）、
> §2、§3 全部、§4–§六。
>
> **已失效的章节**：§1.1、§1.2、§1.3（前提不成立）、§1.7 步骤 1–2（无 disable 可配置）。
>
> **配套规范**：[多会话并发工程·证据与自证工作规范](多会话并发工程-证据与自证工作规范-20261002.md)
> ——本轮 6 次真实失误的提炼：javap 实证纪律 / 时效三元组 / 变异自证 / 校验器优先怀疑 / assert-first 编辑 / 并发让路。

---

# AgentScope 能力启用裁决：三方权威冲突 + ADR-0077 失实声明更正

- 类型：**docs-only 裁决稿**，零 Java 变更，零配置变更
- 观测时刻：2026-10-02 14:37–14:44 PDT · `git HEAD=2db6d4ef` · 工作树脏（含 7 个 disable 违规）
- 证据分级：**[实证]** = 本轮直接读字节 / 跑命令；**[推断]** = 由字节推导，未运行时验证
- 适用范围：本文件**不裁决业务该走哪条路**，只裁决「**谁有权决定能力开关**」，并更正 ADR-0077 与工作树的 8 条不一致
- 上游裁决来源：owner 本轮指令「官方能力全量启用、禁止禁用、禁止降级」+「严格基于官方的 AgentScope 融合本项目」+ 14:53 裁决「**可以是可配置但是必须要全部完整默认可用**」
- **修订记录**：15:09 owner 追问后撤回 §1.7 步骤 3 原门禁升级方案（过度设计），替换方案与五条反思见 **§1.8**；15:5x 完成 TranscriptStore 回退与 PoC 装配复核，**基线快照的 PoC P0 结论予以撤回**，见 **§3.5**
- 文档状态：裁决已定；**§1.7 步骤 1–2 已由兄弟会话在代码层完成**（21:39 实测 disable 归零）；
  本会话已实施步骤 3 的相关修复（`FailClosedAgentStateStore` 方案 D、`KernelScopeKey` 注释纠错）与 t3（plan_exit HITL 3/3 绿）

---

## 一、三方权威冲突（已于 §1.5 裁决；§1.1–1.4 为裁决依据留档）

同一问题（子智能体 / 动态子智能体 / 动态技能是否关闭）当前有**三份互相矛盾的依据**：

| # | 来源 | 原文口径 | 作用域 | 时点 |
|---|---|---|---|---|
| A | **owner 指令**（本轮 + ADR-0077 §1） | 官方能力全量启用、**禁止禁用、禁止降级** | 全局 | 2026-10-02 最新 |
| B | **ADR-0077** §2 规则 3 | 「不得以 disable 开关关闭整类能力」；§3 把 disable 开关行定级为「与最新全量启用目标冲突，**待迁移及验收**」；§6「砍掉的应该是与官方重叠的自研底层，**不是与官方重叠的官方能力**」 | 全局（判定规则四条，验收级） | 2026-10-02 |
| C | **docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md §2 规则3** | 「不打开动态技能、子智能体或 plan mode，不用 `plans/PLAN.md` 或 workspace `MEMORY.md` 当业务事实源」 | **单刀边界** | 早于 A/B |

### 1.1 冲突事实

**[实证]** 2026-10-02 14:36:19–14:36:54，并发写入者在**三个装配点**全量加回了 7 个 disable：

| 文件 | 行 | 调用 |
|---|---|---|
| `chat/kernel/ChatOfficialCapabilities.java` | 64 | `.disableSubagents().disableDynamicSubagents().disableDynamicSkills()` |
| `ipd/agent/kernel/AgentScopeProjectAgentKernel.java` | 296-298 | `.disableSubagents()` / `.disableDynamicSubagents()` / `.disableDynamicSkills()` |
| `service/coding/impl/CodingServiceImpl.java` | 129-131 | 同上三项 |

**[实证]** `CodingServiceImpl.java:128` 的注释原文：

```
// 业务红线合规关闭（按 AGENTS.md / ADR-0077）：不打开子智能体、不打开动态子智能体、不打开动态技能。
```

### 1.2 三处引用错误（这是本节的核心问题）

| 引用 | 是否成立 | 依据 |
|---|---|---|
| 引用 `docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md §2 规则3` | **作用域误用** | 该行完整语境是「产线知识库 MCP 用 AgentScope 替换本仓分叉接入……**这一刀**不把 AgentScope 从 2.0.3 升到 2.0.4，不引入 AgentScope Service」。它是**一次替换刀的范围边界**，不是全局能力策略。把它当作全局禁开依据是作用域提升。 |
| 引用 `ADR-0077` | **引用方向相反** | ADR-0077 §2 规则 3 明文「禁止禁用、禁止降级」；§3 将 disable 开关定级为「与最新全量启用目标冲突，待迁移及验收」；§6 把「官方不上」列为**被否决方向**。ADR-0077 是**禁止这段代码的文件**，被当成了这段代码的依据。 |
| 称其为「业务红线」 | **定性不成立** | 该组三开关对应的是**能力可用性**，不是业务红线。按 owner 口径「能力可用性与具体操作授权分开验收」，权限/审批/Gate 才是业务闸门；子智能体是否可装配属于能力面。 |

### 1.3 字节码实证：官方 2.0.3 默认值（决定性证据）

**[实证]** 从本仓锁定的真实 JAR 反汇编构造器 `HarnessAgent$Builder.<init>`（`javap -p -c`，读 `putfield` 前的 `iconst_*`）：

```
JAR=~/.m2/repository/io/agentscope/agentscope-harness/2.0.3/agentscope-harness-2.0.3.jar
```

| 字段 | 默认值 | 官方 2.0.3 出厂状态 |
|---|---|---|
| `disableCompaction` | `false` | **开启** |
| `disableToolResultEviction` | `false` | **开启** |
| `disableFilesystemTools` | `false` | **开启** |
| `disableShellTool` | `false` | **开启** |
| `disableMemoryTools` | `false` | **开启** |
| `disableMemoryHooks` | `false` | **开启** |
| `disableTranscript` | `false` | **开启** |
| `disableSessionPersistence` | `false` | **开启** |
| `disableWorkspaceContext` | `false` | **开启** |
| `disableAtPathExpansion` | `false` | **开启** |
| **`disableSubagents`** | **`false`** | **开启** |
| **`disableDynamicSubagents`** | **`false`** | **开启** |
| **`disableDynamicSkills`** | **`false`** | **开启** |
| `disableDefaultWorkspaceSkills` | `false` | **开启** |
| `disableToolsConfig` | `false` | **开启** |
| `agentTracingLogEnabled` | `true` | **开启** |
| `skillManageToolEnabled` | `false` | **关闭**——须显式开启 |
| `skillCuratorEnabled` | `false` | **关闭**——须显式开启 |
| `planModeEnabled` | `false` | **关闭**——须显式开启 |
| `planModeAllowShell` | `false` | **关闭**——须显式开启 |
| `checkRunning` | `true` | — |
| `leafSubagent` | `false` | — |

**结论（推翻 §1.2 的「合规关闭」定性）**：

官方 2.0.3 **出厂即开启**子智能体、动态子智能体、动态技能。那 7 个 `disable` 不是「关掉不该开的」，而是**把官方默认开启的能力逐个掐掉**——即 owner 明令禁止的**功能降级**。`CodingServiceImpl.java:128` 注释中「业务红线合规关闭」一语，在字节码层面与事实相反。

同时这张表给出了门禁的设计基准（详见 §3.1）：

- **16 个 `disable*` 全部默认 `false`**，故 anti-disable 门禁（「零 disable」）等价于「保持官方出厂态」，判据成立。
- **`skillManageTool` / `skillCurator` / `planMode` 默认 `false`**，它们**必须显式开启**，扫 `disable*()` **扫不到**。这是「全量启用」的真实漏网区。

### 1.4 版本前提：镜像 ≠ 锁定版（已实证 2 例 + 污染实例 1 例）

**[实证]** `/Users/mac/Documents/agentscope-java` 实际 revision = **`2.0.4-SNAPSHOT`**（`pom.xml:32`），git HEAD `e9721285`。本仓锁定 **`2.0.3`**。镜像**领先一个开发周期**，且领先点恰好落在本仓最关心的扩展点面上。

**例 1（破坏性差异，影响本仓扩展点写法）**：

`javap` 2.0.3 JAR 的 `io.agentscope.core.middleware.MiddlewareBase` 实测**仅 6 个方法**：

```
order() / onAgent(...) / onReasoning(...) / onActing(...) / onModelCall(...) / onSystemPrompt(...)
```

镜像 `MiddlewareBase` 另有 `enum ExtensionPoint`（:77）、`default Set<ExtensionPoint> activePoints()`（:107）、`default void onAgentStateReady(...)`（:240）——**这三项在 2.0.3 中不存在**。

> **后果**：任何按镜像写的 `implements MiddlewareBase` + `@Override activePoints()`，在锁定的 2.0.3 下**编译失败**。本仓的扩展点白名单（ADR-0077 §2 规则 2）必须按 **2.0.3 的 6 个 hook** 写，不能按镜像。

**例 2（API 表面漂移）**：`disableWebTools` / `webHttpClient` / `failoverListener` 在镜像 `HarnessAgent.java:1259/1262/1469` 存在；**2.0.3 JAR 的 `HarnessAgent$Builder` 上三个都不存在**（实测 `javap` 零命中）。

**污染实例（本轮真实发生，非假设）**：本轮审计子智能体 `capability-matrix` 在报告中把 `disableWebTools()` 列为「2.0.3 官方能力 · 关闭开关 · `HarnessAgent.java:2738`」。主协调者用 `javap` 复核后确认——**该方法在 2.0.3 JAR 中根本不存在**。该条目读自镜像源码却被标注为 2.0.3，属**镜像污染**。

> **规则固化**：AgentScope 的一切 API 面与默认值主张，**必须以 `javap` 对 `~/.m2/repository/io/agentscope/*/2.0.3/*.jar` 的实测为唯一终审证据**。`/Users/mac/Documents/agentscope-java` 仅可作**设计意图参考**，任何从镜像抄来的方法名/签名/默认值，在引用前必须回查 JAR。本轮已出现 1 次子智能体级别的事实污染，说明该风险是真实的、可复现的。

### 1.5 【已裁决】owner 2026-10-02 14:53 拍板

> **owner 原文：「可以是可配置但是必须要全部完整默认可用。」**

**裁决效力**：本节由 owner 拍板，**取代** §1.4 的建议稿，**取代** `docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md §2 规则3` 作为全局能力策略的依据。

**裁决内容（三层，不可拆分）**：

| 层 | 裁决 | 判定标准 |
|---|---|---|
| **L1 默认态** | 官方能力**默认全部完整可用** | 任一能力在**未配置**时必须为开启态。判定：查 §1.3 字节码默认值 + 配置类字段初始值，二者均须为「开」 |
| **L2 可配置** | 允许按能力粒度配置关闭 | 每个能力一个独立开关，可单独关闭、可灰度、可回滚 |
| **L3 硬编码禁止** | **禁止**在装配点硬编码 `.disable*()` | 门禁 `check-agentscope-capabilities-open.py` 必须由「零 disable」升级为「**disable 只能存在于受配置保护的分支内，且该配置默认 true**」 |

**L1 的关键实现约束——「缺省即开」**：

- 配置类字段初始值必须为 `true`，**不得**依赖 Spring 的「键缺失 → 绑定到字段声明类型」行为来间接表达。
- 读取处必须写成 `capabilities.isXxxEnabled()` 单一入口，**禁止**出现「配置里没有这个键 → 走关闭分支」的反向缺省。
- 反例（禁止）：`if (!props.containsKey("x")) { builder.disableX(); }`
- 正例（要求）：`if (!props.isXxxEnabled()) { builder.disableX(); }`，其中 `isXxxEnabled()` 默认返回 `true`。

### 1.6 三个开关的实际作用与裁决对照（2.0.3 字节码实证）

主协调者已定位三个开关在 `HarnessAgent$Builder.build()` 中的**全部消费点**（`javap -p -c` 实证）：

**`disableSubagents()` —— 整块跳过子智能体装配**
```
1574: getfield  disableSubagents
1577: ifne      1786        <- 禁用则跳过整块
1581: getfield  model
1584: ifnull    1786
```
等价：`if (!leafSubagent && !disableSubagents && model != null)` 才装配。**3 个装配点全禁** → 模型不能 spawn/派活给下级智能体。

**`disableDynamicSubagents()` —— 只在外层块内生效，当前为冗余**
```
1589: ifnull  1695          <- filesystem 为 null 走静态分支
1593: getfield disableDynamicSubagents
1596: ifne   1695
```
外层 `disableSubagents` 已短路，**此行在当前代码中一行未起作用**。

**`disableDynamicSkills()` —— 技能动态加载冻结**
```
2974: getfield disableDynamicSkills
2977: ifeq    3001          <- 未禁用走正常路径；禁用则改用 frozen(...) + skillFilter
3083: getfield disableDynamicSkills
3086: ifeq    3098
3093: iconst_0
3094: ReActAgent$Builder.dynamicSkillsEnabled(false)
```
**不影响**技能管理——`skillManageToolEnabled`(2394)、`skillCuratorEnabled`(2739)、`planModeEnabled`(2178) 在字节码上是**三个独立分支**，互不干扰。

**净效果**：7 行 / 9 次调用 / 3 种开关 = **全仓 3 个装配点的智能体一律不能自主派生下级智能体，技能不能按调用动态加载**。

**为何不能用装配层开关（对照裁决 L3）**：本仓**已具备授权层**——`ToolPolicyEngine`、`KernelToolGovernance`、`IpdRolePermissionCatalog`，且 `AgentScopeProjectAgentKernel` 已挂 `permissionContext(...)`。子智能体的授权边界、多租户键继承、成本上限**全部属于授权面**，应由授权层按「主体 + 键 + 动作」分辨放行/拒绝，而非由装配层一刀切删掉合法路径与非法路径。

`★ 自我否定证据`：`AgentScopeProjectAgentKernel` 同时挂了 `skillFilter`（`FrozenProjectAgentSkills` 配套）**和** `disableDynamicSkills`——**已有精确管控手段，又叠加一刀切 disable**。该行不换回任何安全收益。

### 1.7 建议实施清单（待写窗口释放后执行，本轮未动 Java）

#### 步骤 1：新增能力配置类（单一入口，缺省即开）

拟新增 `org.ruoyi.chat.kernel.OfficialCapabilityConfig`（`@ConfigurationProperties("agentscope.capabilities")`），字段与默认值**逐项对应 §1.3 字节码实测的官方出厂态**：

```java
// 全部默认 true —— 对齐 2.0.3 出厂态，缺键不得关闭任何能力
private boolean subagents          = true;   // disableSubagents         默认 false
private boolean dynamicSubagents   = true;   // disableDynamicSubagents  默认 false
private boolean dynamicSkills      = true;   // disableDynamicSkills     默认 false
private boolean skillCurator       = true;   // 2.0.3 默认 false -> 本项目须显式开启
private boolean skillManageTool    = true;   // 同上
private boolean planMode           = true;   // 同上
```

对应 `application.yml`（**注释风格照 CLAUDE.md「引用键名不引用行号」**）：

```yaml
agentscope:
  capabilities:
    subagents: true
    dynamic-subagents: true
    dynamic-skills: true
    skill-curator: true
    skill-manage-tool: true
    plan-mode: true
```

#### 步骤 2：三个装配点改为条件装配

```java
// ChatOfficialCapabilities.configure(...) 与两个内核装配段共用
if (!caps.isDynamicSubagentsEnabled()) { builder.disableDynamicSubagents(); }
```

**约束**：三个装配点必须调用**同一份**装配逻辑，不得各自复制条件判断（这正是「全局一致性」的落点）。

#### 步骤 3：~~门禁升级~~ —— **已撤回并替换**（详见 §1.8 反思）

原提案为「由『零 disable』升级为『disable 必须被配置键保护 + 默认值必须 true + 四档一致』」。
owner 15:09 追问「结合门禁深度思考，你确定合理吗」后复核，**该提案撤回**。理由见 §1.8 五条反思。

替换方案：**每条规则只守它真能守住的东西**。

| 保障层 | 手段 | 守得住吗 |
|---|---|---|
| **静态（门禁）** | ① 配置类字段默认值必须为 `true`<br>② `.disable*()` 出现在**配置类之外**的文件 → 红 | ✅ 纯文本可判，无控制流需求 |
| **运行期（启动自检）** | 启动时打印**生效能力清单** + 写审计留痕 + 断言默认为全开 | ✅ 这才是能真正兑现 owner 裁决的机制 |
| **档位一致性** | 改为**差异登记制**：谁与谁不同、为什么，必须在册 | ✅ 避免「零差异即红」引发门禁腐化 |

> **门禁只保证「代码默认值是 true」，不保证「实际运行态全开」**——后者由运行期自检与审计留痕承担。此点为 §1.8 反思 2 的直接结论，**不得再对门禁能力过度承诺**。

#### 步骤 4：验证

- `python3 scripts/check-agentscope-capabilities-open.py` → EXIT=0
- 同脚本 `--self-test` → EXIT=0（含「配置默认 false」「无保护 disable」「档位不齐」三类新负例）
- `mvn -o -pl ruoyi-modules/ruoyi-chat test` 全绿 + `ruoyi-ipd` 单测全绿
- `bash .claude/skills/agentscope-harness/scripts/verify.sh`（含 `--self-red` EXIT=0）

#### 风险与未决

- **并发写入者仍在活跃**（最新观测 14:49:12 同时改写 3 个装配点）。步骤 2 直接命中所争文件，**执行前必须确认其已停笔**，否则必然互相覆盖。
- 步骤 1 的配置类放 `ruoyi-chat` 模块还是 `ruoyi-common`，取决于 IPD 侧是否反向依赖；**本轮未决**，需在实施时按模块依赖方向定。


> **建议：采纳 A/B，令 C 降级为单刀历史记录。**
> 1. 全局能力策略唯一权威 = owner 指令 + ADR-0077 §2 规则 3/§6；能力开关**默认开启**，关闭任一类需**单独 ADR** 记明理由（标准只能是「官方无等价能力」或「安全边界经 owner 签字」，不能是「自研已在」）。
> 2. `docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md §2 规则3` 增补作用域限定词，明确其为**产线 MCP 替换刀**的边界条款，不构成全局能力策略；引用时必须连同该限定。
> 3. 现有 7 个 disable 由**写入方**自行移除并更正注释，或由 owner 另开 ADR 批准保留。本轮因并发让路，**主协调者不代为删除**。
> 4. 门禁 `check-agentscope-capabilities-open.py` 保持「零 disable」硬红线，**不因本次争议加白名单**。若裁决为保留，须走第 1 条的单独 ADR。

---

## 1.8 自我反思记录：一次被 owner 拦下的过度设计

> 触发：owner 15:09 问「结合门禁深度思考反思你确定合理吗」。主协调者复核后**撤回 §1.7 步骤 3 原案**。
> 本节保留，因为它记录的是**方法论层面的教训**，比结论本身更值得留下。

### 反思 1：门禁升级是 §3.2 逃逸的递归版本

**[实证]** `scripts/check-agentscope-capabilities-open.py` 本体是**逐行正则 + `code.count('\n')` 定位行号**，无 AST、无作用域、无控制流：

```python
return [(code.count('\n', 0, m.start()) + 1, m.group().strip())
        for pattern in patterns for m in re.finditer(pattern, code)]
```

而原提案的规则「`disable` 必须被配置键保护」本质要求判断：**该调用是否位于 `if (!caps.isXxxEnabled()) {}` 内部**。

- 正则**做不到**；
- 即便上 JavaParser 做 AST，也只能到**语法层**，判不了「这个 `if` 的条件真的来自配置对象」。

> **这正是 §3.2 的同一个错误**：我修一次盲区，就造一个更复杂的盲区。**每增加一条需要语义分析的规则，就增加一个可被针对性绕过的面。**「设计一个更聪明的门禁」与「设计一个不够聪明的门禁」是同一种自欺。

### 反思 2：「可配置」与「门禁保证默认完整」互斥

owner 裁决是「**默认**可用」，不是「永远可用」。一旦做成配置项，运行时状态取决于 `application.yml` + 环境变量覆盖，而**门禁是静态的，看不见部署时实际传了什么**。

> 门禁最多能保证「**代码默认值**是 true」，**保证不了「实际运行态是全开的**」。原方案把门禁说成能落实 owner 裁决，属**过度承诺**，已撤回。

### 反思 3：配置化在安全语义上比硬编码更危险

| 维度 | 硬编码 `disable` | 配置项 |
|---|---|---|
| 调用点可见性 | **一眼看见** | `if (!caps.isSubagentsEnabled())` 读起来人畜无害 |
| 影响面半径 | 一个文件 | 一个配置文件，一处改动**全仓生效** |
| 是否进 review / diff | 必然 | 可能只改了 yml |
| 事后翻回 | 必然 | **可能永远不翻** |

安全相关能力的 kill switch 典型失效模式：线上事故 → 有人翻开关 → 事情过去无人翻回。

> **kill switch 必须有声**：启动日志、审计留痕、告警，而非静默配置。原方案只看了「可回滚」，漏看「可观测性」与「作用域半径」。

### 反思 4：「四档不一致即红」可能过严

四个装配点服务不同目的（chat / IPD 项目智能体 / coding 助手 / PoC）。**「全局一致性」≠「四档完全相同」**，而是「同一份装配逻辑 + 差异显式声明在册」。

强制零差异 → 门禁噪声 → 有人加豁免 → 门禁腐化。这是静态门禁最常见的死法。已改为**差异登记制**。

### 反思 5：又一次「没验证就断言」的边缘

「默认全开」前需逐能力确认运行时前置依赖。本轮**只查了一个**：

- **[实证]** 本仓**已**对 Docker 沙箱硬依赖——`ChatOfficialCapabilities.java:54` 直接 `throw new IllegalArgumentException("Sandbox image is required")`。故开启 subagent **不引入新的基础设施依赖**，此条安全。
- **其余能力未查。** 本会话已两次栽在「没验证就断言」上（§3.4 的 `disableWebTools`、`asyncToolTimeout`），**实施前必须逐能力补完前置依赖检查**。

### 反思 6（对前一条反思的反思）：「每条规则只守它真能守住的」也不是免死金牌

替换方案里的「`.disable*()` 出现在配置类之外 → 红」仍有一个**已知逃逸面**：把 `disable` 调用**藏进配置类本身**（配置类里持有一个 `Builder` 引用并调用）即可绕过。**规则强度与绕过成本之间没有免费午餐。**

> 因此替换方案**不宣称门禁已充分**，只宣称**它守的每一段都是它真能守的**。真正的兜底是**反思 3 的运行期自检与审计留痕**——它观察真实状态，不依赖任何静态推断。**静态门禁负责快速失败，运行期断言负责真相。**

## 二、ADR-0077 与工作树的 8 条不一致更正

ADR-0077 记录的处置**已大部分在代码中落地，但 ADR 未回写**——即**文档落后于字节**，非代码违规。逐条更正如下（均 [实证]）：

| # | ADR-0077 位置 | ADR 声明 | 工作树实际 | 核验命令/证据 |
|---|---|---|---|---|
| 1 | §3 表「disable 开关」行 | 「filesystem/shell/memory×2/transcript/subagents×2/dynamicskills/defaultskills/skillsEnabled(false) 全套官方开关」 | **已失效**。ADR 该行描述的是**已删除的旧实现**，未随代码更新 | `grep -cE '\.disable[A-Za-z]+\(\)'` 于 14:37 时全仓 main = 0；14:36:54 后被并发写入者重新引入 7 处 |
| 2 | §3 表「modelExecutionConfig / toolExecutionConfig」行 | 「**未挂载** = 真缺口，30 分钟长任务裸奔」 | **已修复**，ADR 未回写 | `CodingServiceImpl.java:129-130` 已挂 `.modelExecutionConfig(ExecutionConfig.MODEL_DEFAULTS).toolExecutionConfig(ExecutionConfig.TOOL_DEFAULTS)` |
| 3 | §3 表「compaction 显式固化」行 | 「未显式声明」 | **已修复**，ADR 未回写 | `CodingServiceImpl.java:133` 已 `.compaction(CompactionConfig.builder().build())`，注释与 IPD 内核 `:321-322` 一致 |
| 4 | §4 证据 4 | 「`CodingController` 返回 `"Legacy coding endpoints are disabled; use /coding/harness"`」 | **假，且方向已反转**。现为 `requireLegacyEnabled()` → 抛 `410 GONE`；`/coding/harness` 已删，现存 `/coding/**` 全是默认关闭的 legacy 口 | `CodingController.java`（`org/ruoyi/controller/coding/`） |
| 5 | §4.2 保留清单 8 类 | 「删 17 包前**必须迁出**（建议迁至 `org.ruoyi.chat.kernel.*`）」 | **未执行**。8 类全部仍在 `org.ruoyi.service.coding.harness.*` 原包 | `HarnessPermissionMode` / `HarnessToolEffect` → `harness/model/`；`PolicyDecision` / `ToolCapability` / `ToolDescriptor` / `ToolInvocation` / `ToolPolicyEngine` / `ToolPolicyEvaluation` → `harness/tool/`。**跨域反向 import**：IPD/chat 治理层依赖 coding 域包 |
| 6 | §7 验收门禁 | 「`grep -rl "org.ruoyi.service.coding.harness" --include="*.java" ruoyi-modules/**/src/main` **归零**」 | **门禁当前 FAIL** | 14:44 实测 = **24 个文件**仍 import 该包 |
| 7 | §3 标题 | 「**第三**装配点 CodingServiceImpl」 | **已过时**。主源树现为 **4 个** | `AgentScopeChatKernel.java` / `PocKernelSupport.java` / `CodingServiceImpl.java` / `AgentScopeProjectAgentKernel.java` |
| 8 | §4.1 两项域外消费者 | ShortDrama 引 `harness.loop.model.*`、CustomApi 引 `harness.modelruntime.HarnessModelPolicy` | **该 17 包已摘除**（`harness/` 现仅 `model`+`tool`），两项依赖应已随之消解 —— **本轮未逐条验证，留待复核** | `ruoyi-chat/.../service/coding/harness/` 下仅 2 子目录 |

### 2.1 ADR-0077 中**仍然成立**的声明（不得因上述更正而废止）

- §2 四条判定规则（官方基线 / 扩展点白名单 / 全量启用禁禁用禁降级 / 双轨红线）——**第 3 条与 §6 正被违反，见第一节**。
- §1 两次 owner 拍板的历史依据。
- §5 结论更新关系表（ADR-0075 前提已变、六问 ADR 问 2 已落地、问 6 已过时）。
- §7 后两条验收项：`mvn -o -pl ruoyi-modules/ruoyi-chat test` 全绿 + `verify.sh`（含 `--self-red` EXIT=0）+ langchain4j 棘轮维持 0。
- ADR 自身为 docs-only（`git log -- docs/.../ADR-0077*` 无输出，文件未入库）——**[推断]**。

---

## 三、门禁状态（供 §1 裁决引用）

| 门禁 | 时刻 | 结果 | 判读 |
|---|---|---|---|
| `scripts/check-agentscope-capabilities-open.py` | 14:42 | **EXIT=1**，`consumers=4 violations=9` | **真红，非假绿**。9 = 3 文件 × 3 调用。正确识别全部 7 个 disable 行 |
| 同上 `--self-test` | 14:44 | **EXIT=0** | 自证能红有效 |
| 逻辑质量 | — | `code_only()` 先剥注释与字符串；`patterns = (r'\.\s*disable\w*\s*\(', …)`；`--self-test` 覆盖「注释内 disable 不算」「字符串内 disable 不算」「`Other.builder().disabled(false)` 不算」 | **不是恒真**。剥离逻辑正确，未见注释字面量误报 |

### 3.1 该门禁的结构性盲区（ADR-0077 与 owner 指令均未覆盖）

**[实证]** 结合 §1.3 字节码默认值，2.0.3 的 `HarnessAgent$Builder` 能力面天然分为两类：

| 类别 | 成员 | anti-disable 门禁能否覆盖 |
|---|---|---|
| **A 类：官方默认开启**（`disable*` 全为 `false`） | compaction / toolResultEviction / filesystemTools / shellTool / memoryTools / memoryHooks / transcript / sessionPersistence / workspaceContext / atPathExpansion / **subagents** / **dynamicSubagents** / **dynamicSkills** / defaultWorkspaceSkills / toolsConfig / agentTracingLog | **能**——保持不调 `disable*()` 即为开启，扫得到回归 |
| **B 类：官方默认关闭，必须显式开启** | `skillManageToolEnabled` / `skillCuratorEnabled` / `planModeEnabled` / `planModeAllowShell`，以及只能经显式声明装配的 `subagent()` / `teamsMode()` / `messageBus()` / `taskRepository()` / `artifactDeliveryTarget()` / `modelResolver()` / `mcpServerRegistrationListener()` / `toolsConfig(...)` / `permissionContext(...)` / `distributedStore()` / `asyncToolRegistry()` | **不能**——没开与开了在 anti-disable 门禁下**同样全绿** |

因此：

- 扫 `disable*()` 只能证明「**A 类没被关**」，**不能证明「B 类已开启」**。
- 一个从未调用 `enableSkillCurator()` / `subagent()` / `permissionContext()` 的装配点，anti-disable 门禁**照样全绿**——这正是「能力未接入」与「能力已启用」在静态层无法区分的根因。
- 门禁末尾自述 `static anti-disable contract only` 是诚实的，但**验收链若只跑这一条，会把「全绿」误读为「能力完整」**。

**补强方向（不在本轮范围，留待裁决后实施）**：

1. **B 类存在性断言**——对 `enableSkillCurator` / `enableSkillManageTool` / `enablePlanMode` / `subagent` / `teamsMode` / `permissionContext` / `messageBus` / `toolsConfig` 等逐项断言「装配点是否声明了它」，缺失即红。
2. **四装配点档位一致性比对**——同一能力在不同装配点档位不齐即红（当前 chat / IPD / coding / PoC 四点已实测不齐）。
3. **禁用注释不得引用未校验的依据**——`disable` 附近注释若援引 ADR/规范编号，须回查该文件确有支持条款，否则红。**本轮 §1.2 的三处引用错误正是此盲区的产物。**

### 3.2 门禁逃逸实证（主协调者亲自复现，非转述）

`check-agentscope-capabilities-open.py:22-23` 有一道**准入门槛**，比两条正则本身更致命：

```python
def violations(source):
    code = code_only(source)
    if not re.search(r'\bHarnessAgent\b', code):   # :22-23
        return []                                 # 整个文件直接放行
```

它只在**同一文件内**同时出现「`HarnessAgent` 字面量」+「直调 `.disableX()`」时才说话。`--self-test` 的 11 组正反控**全部构造在同一文件内**（`:32-42`），**无一覆盖这道门槛的逃逸面**。

**[实证] 主协调者用影子夹具复现（14:47，HEAD `2db6d4ef`）**：

| 情形 | 构造 | 门禁实测 |
|---|---|---|
| A（现状） | `disableSubagents()` 留在含 `HarnessAgent` 字面量的文件 | **EXIT=1** `consumers=2 violations=2` |
| **B1** | 同一批 disable 挪进**零个 `HarnessAgent` 字面量**的类，用 `getMethod("disableSubagents").invoke(b)` 反射调用 | **EXIT=0** `consumers=1 violations=0` —— **全绿** |
| **B2** | 零字面量类，`import static ...HarnessAgent.Builder.disableSubagents;` 后按短名调用 | **EXIT=0** `violations=0` —— **全绿** |

> **风险量级**：`ChatOfficialCapabilities.configure()` 是被 **3 个装配点复用**的共享 helper（`AgentScopeChatKernel` / `CodingServiceImpl` / 及 IPD 侧调用面）。**把 `builder.disableSubagents()` 从该类挪进一个名字里没有 `HarnessAgent` 的新类，距离现状只有一层重构**，届时 3 个生产装配点同时失去子智能体，而门禁从 9 violations **直接变 0 全绿**。
>
> 结论：当前门禁不是「假绿」，但它是**单文件自洽检测**而非「装配点能力检测」——**它保护的是写法，不是能力**。§1.2 的三处错误引用与 §1.3 的 7 个 disable，都是在门禁**全绿的历史区间**内被写入的（14:36 前全仓 disable=0，门禁当时绿）。

**修正建议**：删掉 `:22-23` 的准入门槛，改为**全仓**扫描任意 `.disable\w*(` 报警，`HarnessAgent` 字面量只用于**标注严重级**而非准入；并补一条「含 `getMethod(`/`getDeclaredMethod(` 且入参为 `"disable…"` 拼接者判红（反射即视为绕过静态门禁）」。

### 3.3 能力第三类：靠 `build()` 内 null 回退自动装配（扫 `disable*()` 原理上抓不到）

**[已实证 · 2.0.3 JAR 字节码]** 除 §3.1 的 A/B 两类外，2.0.3 存在**第三类**能力：没有 `disable*()` 开关，能力因「**你没传它，SDK 自己造了一个**」而开启。只扫 builder 调用点的门禁**原理上无法覆盖整类**。

主协调者从 `javap -p -c` 的 `build()` 字节码（1424 条指令）中定位到两处 `putfield messageBus`，其一为：

```
776: getfield      messageBus
780: ifnonnull     804        <- 显式设置过则保留
783: aload         10         <- 局部变量 10 = AbstractFilesystem
785: ifnull        804        <- 未设 filesystem 则跳过
789: new           WorkspaceMessageBus
795: ldc           ".agentscope/bus"
801: putfield      messageBus <- SDK 自建并回填
```

**等价源码（2.0.3 实证）**：

```java
if (messageBus == null && filesystem != null) {
    messageBus = new WorkspaceMessageBus(filesystem, ".agentscope/bus");
}
```

**对本仓的直接后果**：本仓**从未调用** `.messageBus(...)`（实测全仓 0 命中），但 **`ChatOfficialCapabilities.java:65` 与 `AgentScopeProjectAgentKernel.java:348` 都调用了 `.filesystem(...)`**。按上述回退，**这两个装配点的 `messageBus` 在 `build()` 结束时非 null**。

| 能力 | 2.0.3 回退条件 | 本仓实况 | 结论 |
|---|---|---|---|
| `MessageBus` | `messageBus==null && filesystem!=null` -> 自建 | chat / IPD 设了 `filesystem` | **自动存在** |
| `AsyncToolRegistry` | 同构分支（+804 起紧邻） | 同上 | **自动存在** |
| `TranscriptStore` | **`transcriptStore==null` -> 自建**，见下方 §3.5 | 四装配点均显式传入 | **已实证安全** |
| `WaitAsyncResultsTool` | 由上行**级联**注册 | 同上 | 默认进 toolkit |

> 转录回退的**完整两分支**结构见 §3.5。四装配点（含 PoC）均显式传入 `ChatSafeTranscriptStore`，**已实证不落默认路径**。

### 3.4 复核推翻记录：审计结论被 2.0.3 字节码推翻（2 例）

本轮共出现 **2 次「读镜像源码 -> 当作 2.0.3 事实」的污染**，均由主协调者用 `javap` 复核后推翻。写在此处，因为它们证明 §1.4 的规则**有实际命中率**。

| # | 污染方 | 其结论 | 复核结果 | 危害方向 |
|---|---|---|---|---|
| 1 | `capability-matrix` | 「`disableWebTools()` 是 2.0.3 官方关闭开关，`:2738`」 | **该方法在 2.0.3 JAR 的 Builder 上不存在** | 虚构不存在的可关闭项，误导门禁设计 |
| 2 | `ipd-kernel` | 「本仓未调 `.messageBus(...)`，故 `.asyncToolTimeout(30s)` 是**空操作**，30 秒从未生效」 | **错**。2.0.3 `build()` 存在 `messageBus==null && filesystem!=null` 回退；本仓两装配点均设 `filesystem`，故 30 秒**确实生效** | **反向危害更大**：若采信，会把一个正确生效的配置当成无效并「清理掉」 |

**污染根因（两条同源）**：`/Users/mac/Documents/agentscope-java` 实际为 **`2.0.4-SNAPSHOT`**（`pom.xml:30` `<revision>2.0.4-SNAPSHOT</revision>`，`git describe` = `v2.0.2-217-ge9721285`）。`ipd-kernel` 报告抬头自述「对照基线：官方 **v2.0.3** 源码字节」——**该自述与字节不符**。

> **规则命中证据**：`javap` 对锁定 JAR 的实测**不是可选的严谨动作，而是能直接改变实施方向的必要动作**。上表第 2 条若被采信，本轮就会产出「删掉 `asyncToolTimeout` 因为它是空操作」的错误改动。

## 3.5 【已完成】TranscriptStore 回退分支 + PoC 装配实况复核

> 复核时刻 15:5x · HEAD `2db6d4ef` · 本节为主协调者亲验，**取代** §5 原「最高优先待复核项」。

### 3.5.1 TranscriptStore 回退分支（2.0.3 字节码全文实证）

`build()` 偏移 992-1078 完整还原：

```java
if (!disableTranscript) {
    TranscriptStore store = this.transcriptStore;
    if (store == null) {                                    // 1006: ifnonnull 1056
        if (workspaceManager.getFilesystem() != null) {      // 1014: ifnull 1034
            store = new ObjectStoreTranscriptStore(workspaceManager.getFilesystem());   // 1017-1026
        } else {
            store = new FilesystemTranscriptStore(                            // 1034-1051
                workspaceManager.getWorkspace().resolve(".agentscope/transcripts"));
        }
    }
    inner.middleware(new TranscriptMiddleware(workspaceManager, store, transcriptTenant));  // 1060-1075
}
```

**关键结构修正（此前未掌握）**：回退**不是单一路径，而是两分支**。

| 分支 | 条件 | 落点 | 风险 |
|---|---|---|---|
| A | `getFilesystem() != null` | `ObjectStoreTranscriptStore(filesystem)` | 沙箱内，随沙箱生命周期 |
| B | `getFilesystem() == null` | `FilesystemTranscriptStore(workspace/.agentscope/transcripts)` | ⚠️ **宿主磁盘明文落盘** |

**对本仓的结论**：本仓四个装配点**全部**经 `ChatOfficialCapabilities.configure(...)` 传入 `ChatSafeTranscriptStore`（该类专门做 transcript 脱敏），**走不到任何回退分支**。

> **残余风险（须登记）**：B 分支距本仓只有**一次 `.filesystem(...)` 移除**之遥。任何人若为「本地跑快点」去掉沙箱配置，transcript 会**静默改落宿主磁盘明文**且**无任何告警**。建议把「四装配点必须传 transcriptStore 且必须设 filesystem」列为**成对不变量**登记，避免单独删其一。

### 3.5.2 PoC 装配实况：基线快照的 P0 结论**已失效，予以撤回**

`AgentScope归位-基线快照-20261002.json` 记 `poc_in_main_tree.severity = "P0-待裁决"`，理由为「`reachable_from: PocSseController（main 源树，HTTP 可达）`、`four_dim_isolation: false`、依赖需预建的独立库」。

**[实证] 三项指控现已全部不成立**：

| 基线指控 | 现状 | 证据 |
|---|---|---|
| 无四维隔离 | **已接入** | `PocKernelSupport.java:57` `KernelScopeKey.of(projectId, userId, agentId, sessionId)`；`PocSseController.java:56` 同 |
| HTTP 可达且无鉴权 | **已鉴权，fail-closed** | `PocSseController.java:78` `LoginHelper.getUserId()`；`:79` catch `NotLoginException`；`:30-31` 注释明载「userId 恒从登录会话推导，请求参数自报已移除，没有『默认 U1』式降级」 |
| agent 缓存键不含用户 | **已按四维键缓存** | `PocKernelSupport.java:59` `AGENTS.computeIfAbsent(scope.slotId(), ...)` —— 键为 `slotId` 而非 `agentId` |
| transcript 安全性未验证 | **已实证安全** | `PocKernelSupport.java:80` 共用 `ChatOfficialCapabilities.configure` → `ChatSafeTranscriptStore` |
| 无双重保险 | **已有** | `PocKernelSupport.java:58` `if (userId == null \|\| userId.isBlank()) throw` |

> **撤回结论**：基线快照中 `P0-待裁决` 一条**不再成立**，应标为已消解。该快照的 `generated_at`/`git_head` 已过期（见 §2 与 §4），**其全部计数类结论均不得再作为当前状态引用**。

### 3.5.3 PoC 残留的**真实**问题（替代原 P0）

**[实证]** `PocSseController.java:47` `@RequestParam(value = "projectId", defaultValue = "P1") String projectId` —— **`projectId` 仍由请求方自报**，代码注释 `:32` 自认「残留自报参数（projectId/agentId/sessionId）仅供 PoC 接线验证，cutover 时按 W1 裁决收口」。

**风险**：登录用户可自选任意 `projectId`，从而构造出**指向他人项目**的四维隔离键（`userId` 真实但 `projectId` 伪造）。transcript 虽安全，但**工作区、沙箱、状态键都会落到该项目命名空间下**。这与 IPD 主线的「动作写入须校验项目成员资格」(`e2aad11b`) 是**两套标准**。

> 定级：**中**。属 PoC 专用路由（代码注释 `:30` 称其受同族开关控制、不流入正式面），但**该开关的实际默认值本轮未验证**——若默认为开，则该面在生产可达。

**另两项（非安全，登记备查）**：
1. `PocKernelSupport.java:71` `Files.createTempDirectory` 每个 agent 一次，agent 被缓存故复用，**目录永不清理** → 磁盘缓慢增长。
2. `PocKernelSupport.java:116` JDBC `useSSL=false&allowPublicKeyRetrieval=true`，凭证读自 `.codex/ipd-dev/config/mysql-app.cnf`（本地明文文件）。仅连 `127.0.0.1:13306`，风险有限，但**该文件是否在分发的仓内、是否含真实口令，须 owner 确认**。

## 3.6 PoC 归属终审：它不是「未上线功能」，是**放错源树的测试夹具**

> 复核时刻 15:56 · HEAD `2db6d4ef`

### 3.6.1 五条证据，指向同一个结论

| # | 证据 | 命令/位置 | 含义 |
|---|---|---|---|
| 1 | 生产代码仅 **242 行 / 2 文件** | `PocSseController` 96 + `PocKernelSupport` 146 | 体量是夹具级，不是产品级 |
| 2 | **无任何生产消费者** | 主源树内仅这两个文件互相引用；前端 `ts/vue/js/json` 搜 `poc/kernel` → **零命中**（命中的只是验收 JSON 里的路径字符串） | 不是产品功能 |
| 3 | **唯一消费者是 8 个测试类** | `PocSseControllerIdentityTest`(8 处) / `AgentScopeKernelConcurrencyPocIT`(4) / `AgentScopeChatKernelResilienceIT`(4) / `PocModelCredentialConsumerTest`(2) / `PocSseApplication`(3) 等 | 夹具 |
| 4 | **唯一运行方式是测试 classpath 手动启动** | `PocSseApplication.java:13` 用法 `java -cp <test classpath> org.ruoyi.chat.poc.kernel.PocSseApplication` | 不经生产启动流程 |
| 5 | **它自己就写明了** | `PocSseApplication.java:10` 「[PoC G4] SSE 样例最小启动器(**测试域，不进生产装配**)」 | 自述已定性 |

### 3.6.2 路由可达性（回应 §3.5.3 的悬置问题）

**[实证] PoC 路由默认关闭，且全仓无任何配置点启用它**：

```java
// PocSseController.java:39
@ConditionalOnProperty(name = "chat.kernel.poc.enabled", havingValue = "true", matchIfMissing = false)
```

- `matchIfMissing = false` → **键缺失即不注册**；
- 全仓 `*.yml` / `*.yaml` 搜 `poc` → **0 个相关配置键**（仅命中 neo4j compose 的 `procedures` 字样）。

> **结论**：§3.5.3 记的「`projectId` 自报越权（中）」**在默认配置下不可达**。风险仅在有人**显式**设置 `chat.kernel.poc.enabled=true` 时才成立。定级维持**中**，但加前置条件：**该开关默认关且未被配置**。

### 3.6.3 留在主源树的代价（为何该迁）

一个 242 行的测试夹具住在主源树，实际付出：

1. **进生产 jar**，随每次构建分发；
2. **带一个 HTTP 端点** `/poc/kernel/chat/stream`（虽默认关，但需要人记得它在那儿）；
3. **带一条明文凭证读取路径**——`PocKernelSupport.java:114` 读 `.codex/ipd-dev/config/mysql-app.cnf`，`:116` 硬编码 `jdbc:mysql://127.0.0.1:13306/ipd_poc?useSSL=false&allowPublicKeyRetrieval=true`；
4. **依赖一个需预建的独立库** `ipd_poc.agentscope_sessions`（`:43` 注释称「表已按源码 DDL 预建」）；
5. **让门禁多背一个装配点**——§3.1 的「四装配点档位一致性」要把它算进去，而它与另外三个本就不同源。

### 3.6.4 建议：整体迁入 test 源树（技术零摩擦）

**可行性证据**：8 个消费者**全部**已在 test 树；`PocSseApplication` 已在 test 树且**同包名** `org.ruoyi.chat.poc.kernel`；其 `@ComponentScan(basePackageClasses = PocSseController.class)` 迁后照常扫到。

```
ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/   ← 迁出
ruoyi-modules/ruoyi-chat/src/test/java/org/ruoyi/chat/poc/kernel/   ← 迁入（与 PocSseApplication 同目录）
```

迁后收益：生产 jar 少 242 行 + 少一个端点 + 少一条凭证路径 + 少一个独立库依赖；门禁的装配点从 4 降到 3，一致性比对更干净。

> **本轮未执行**：`PocKernelSupport.java` 当前为 `MM`（暂存区+工作区双改），属并发写入者在途文件。迁移需先取得写窗口。
> **迁移前须 owner 确认**：`chat.kernel.poc.enabled` 是否有人在本地/联调环境依赖；`.codex/ipd-dev/config/mysql-app.cnf` 是否为分发仓内文件、是否含真实口令。

### 3.6.5 上述两项前置确认：主协调者自查结论（无需 owner 回答，20:40 完成）

#### (1) `.codex/ipd-dev/config/mysql-app.cnf` —— **不在分发面，无泄漏**

| 检查项 | 命令 | 结果 |
|---|---|---|
| 是否被 git 跟踪 | `git ls-files --error-unmatch` | **未跟踪**（`did not match any file(s) known to git`） |
| 是否被 ignore | `git check-ignore -v` | **`.gitignore:83` → `.codex/`** |
| 全历史全分支是否曾提交 | `git log --all --diff-filter=A -- .codex` | **从未** |
| `.codex` 目录跟踪文件数 | `git ls-files .codex \| wc -l` | **0** |
| 磁盘权限 | `ls -la` | `-rw-------`（**0600**，仅属主可读，正确） |
| 内容 | 脱敏查看 | `[client]` + `user` / `password` / `protocol` / `host` / `port`，**含真实值** |

**结论**：**分发仓内不存在该文件，也不存在任何含其内容的 commit。** 风险不在「泄漏」，而在**代码与本地环境的耦合**——`PocKernelSupport.java:114` 硬依赖这条本地路径，fresh clone 后该功能必然抛 `IllegalStateException("repo root with .codex/ipd-dev/config/mysql-app.cnf not found")`（`:133`）。这是**可移植性缺陷，不是安全缺陷**。

补充：主源树**无任何硬编码口令字面量**（`grep 'password\s*=\s*"|setPassword("' ruoyi-modules/*/src/main` → 0 命中）。

#### (2) `chat.kernel.poc.enabled` —— **一次性验证开关，已闭环，无人依赖**

| 检查项 | 结果 |
|---|---|
| yml / properties | **0 命中** |
| `scripts/start.sh` / `.vscode/` / `.github/` | **0 命中** |
| 测试代码设置点 | **0**（仅 `PocSseApplication.java:14` 的**用法注释**） |
| git 历史中出现该键的 commit | 6 个，**全部是文档/验收证据入库**，非配置启用 |

**唯一一次实际启用**（`log.md:13123`）：2026-09-29 为补 D9 真链 HTTP 证据，以命令行参数 `--chat.kernel.poc.enabled=true` 重启 16039（kill 旧 PID 23115 → `mvn -o package` → 新 PID 30760），owner 当轮指令为「都做，你来重启」。证据归档 `验收/D轮-生产就绪独立验证-20260929/d9-poc-truechain-20260929.json`，看板镜像 `:5958` 记「**原 PENDING_VALIDATION 闭环**」。

**结论**：这是**一次已完成的验证动作**，不是常驻依赖。**无人依赖该开关**（除复现该次验证外）。

#### (3) 附带发现：本次「撤回 P0」与项目历史一致

`log.md:13392` 载有一段**早前会话的实证纠正**——某兄弟报告称「main 源树 HTTP 可达零 disable 不经四维隔离」并定级 P0，该轮已将其纠正为：

> 「`PocSseController.java:39` 有 `@ConditionalOnProperty(..., matchIfMissing=false)`，且 `ruoyi-admin/src/main/resources/` 全量 yml **无该键**（grep 零命中）→ **默认不装配、HTTP 不可达**；`:56` 明确 `KernelScopeKey.of(...)` + `:53-54` userId 恒从 LoginHelper 推导（fail-closed）→ **是经四维隔离的**。……**属休眠态加固项（P1/P2），非活跃 P0**。」

> 即：基线快照 `AgentScope归位-基线快照-20261002.json` 的 `P0-待裁决` 定级，**在本轮之前就已被项目历史否定过一次**。本轮 §3.5.2 的撤回与之一致，非孤证。**该快照的定级类结论应整体作废。**

## 3.7 【文档纠错】`KernelScopeKey` 对官方 Redis 键结构的描述与字节码不符

> 复核时刻 20:5x · 主协调者 `javap` 实证 · 对象：`io.agentscope.extensions.redis.state.RedisAgentStateStore`（2.0.3）

### 3.7.1 官方键结构的真实构造（字节码还原）

常量池 `BootstrapMethods` 取出的拼接配方 + 调用点还原：

```java
// 静态私有方法
private static String slotId(String user, String session) {
    if (session == null || session.isBlank()) throw new IllegalArgumentException("sessionId must not be blank");
    return normalizeUser(user) + "/" + session;          // 配方 "/"  (BS#13)
}
private String getStateKey(String slot, String stateKey) {
    return keyPrefix + slot + ":" + stateKey;            // 配方 ":"  (BS#15)
}
private static String normalizeUser(String user) {
    return (user == null || user.isBlank()) ? "__anon__" : user;   // 常量 ANON_USER
}
```

`saveVersioned` / `exists` / `delete` 等全部路径的实测调用链：
```
2:  invokestatic  slotId(user, session)      →  slot
11: invokevirtual getStateKey(slot, stateKey) →  key
```

**真实键结构**：

```
{keyPrefix}{normalizeUser(user)}/{sessionId}:{stateKey}
```

本仓代入 `KernelScopeKey` 的复合键后，实际形如：

```
agentscope:session:pP1:u900103/aemp-a1:sS1:agent_state
              └── 复合 userId ──┘ └── 复合 sessionId ──┘ └ stateKey
```

常量：`DEFAULT_KEY_PREFIX="agentscope:session:"`、`ANON_USER="__anon__"`、`HASH_SUFFIX=":_hash"`、`LIST_SUFFIX=":list"`、`KEYS_SUFFIX=":_keys"`。

### 3.7.2 本仓注释的三处错误

`KernelScopeKey.java:23-27` 现有表述：

> 官方 `RedisAgentStateStore` 的 Redis key 结构是 `{prefix}{sessionId}:{stateKey}`——**其 key 本身不含 userId**，userId 维度由调用方传入的 sessionId 参数承载。故四维隔离的落点**取决于调用方传进去的复合 sessionId 字符串**……一旦某个调用点绕过 `of` 直接传原始 sessionId，user/project/agent 三维会静默丢失。

| # | 注释断言 | 字节码事实 | 判定 |
|---|---|---|---|
| 1 | 键为 `{prefix}{sessionId}:{stateKey}` | `{prefix}{user}/{sessionId}:{stateKey}` | ❌ **少一段** |
| 2 | 「其 key 本身不含 userId」 | `normalizeUser(user)` 是**独立前缀段**，位于 `/` 之前 | ❌ **反了** |
| 3 | 「绕过 `of` 传原始 sessionId → user/project/agent **三维**静默丢失」 | 绕过只影响 **project**（在 user 段内）与 **agent**（在 session 段内）；**user 维由 `user` 参数独立承载，不会丢** | ❌ **多算一维** |

### 3.7.3 结论修正

- **隔离强度比注释描述的更强，不是更弱。** 四个维度（project / user 走 `user` 段，agent / session 走 `sessionId` 段）**全部进入 Redis 键**，不存在「user 维靠 sessionId 捎带」这种间接承载。
- 该注释建立在一条**未经实证的官方键结构假设**上，而这条假设与字节码相反。修正方向：**先 `javap` 读键构造，再据此判断隔离是否成立**——否则隔离论证会建立在一个错误前提上，且**错误方向是偏乐观**（注释让人以为 user 维更脆弱）。
- **注释中仍然成立的部分**：① 官方空 userId 降级到 `__anon__`（`normalizeUser` 字节码确认，且与本仓 `ANONYMOUS_USER_SEGMENT` 常量同值，属有意对齐）；② 复合键必须经 `KernelScopeKey.of` 构造、业务代码禁止手工拼键（防复合键注入伪造别桶）；③ `slotId()`（本仓方法）不参与落库键——**这条也是错的**，见下。

> **附带纠错**：`KernelScopeKey.java:61-64` 称 `Scope#slotId()`「**不是**任何 store 的落库键……本方法不参与持久化键构成」。按 §3.7.1，**官方内部有一个同名语义的 `RedisAgentStateStore.slotId(user, session)`，且它正是落库键的第一段拼接来源**。本仓 `Scope#slotId()`（复合 userId + ":" + 复合 sessionId）与之**同名不同物**——本仓的用 `:` 分隔且含四个维度，官方的用 `/` 分隔且只有 user + session 两维。**同名易混淆，建议重命名本仓方法（如 `turnGateKey()`）**，其真实用途仅 turn gate 串行化。
>
> **本轮未执行重命名**——`KernelScopeKey` 非并发在途文件，但改动会波及 `AgentScopeChatKernel` / `PocSseController` / `ProjectAgent*` 多处调用点，属需要独立窗口的改动。

## 3.8 记忆（Memory）实现：官方最佳实践符合度判定

> 复核时刻 21:0x · 主协调者亲验 · 官方 API 全部 `javap` 2.0.3 实证
> 官方文档源：`https://java.agentscope.io/v2/zh/integration/memory/{index,overview}`（owner 20:55 指定）

### 3.8.1 官方记忆是**两层正交**，不是一层

**[实证] 2.0.3 JAR 实证**：

**第一层 · 会话内记忆** `io.agentscope.core.memory.Memory`（SPI）
```java
public interface Memory {
    void saveTo(AgentStateStore, String user, String session);   // 官方把记忆持久化委托给状态存储
    void loadFrom(AgentStateStore, String user, String session);
    void addMessage(Msg);  List<Msg> getMessages();  void deleteMessage(int);  void clear();
}
```
实现三个：`StateBackedMemory(AgentState)` / `InMemoryMemory()` / `AgentStateMemoryView(Supplier<AgentState>)`

**第二层 · 长期记忆** `io.agentscope.core.memory.LongTermMemory`（跨会话，SPI）
```java
public interface LongTermMemory {
    Mono<Void>  record(List<Msg>);   // 记录
    Mono<String> retrieve(Msg);      // 检索
}
```
挂载点在 **`ReActAgent.Builder`**：`longTermMemory(...)` / `longTermMemoryMode(LongTermMemoryMode)` / `longTermMemoryAsyncRecord(boolean)`；
`LongTermMemoryMode` = `AGENT_CONTROL` / `STATIC_CONTROL` / `BOTH`；
配套 `LongTermMemoryTools`（`recordToMemory` / `retrieveFromMemory` / `wrap`）与 `StaticLongTermMemoryHook(LongTermMemory, Memory[, boolean])`。
官方后端三选一：**Mem0 / 百炼(Bailian) / ReMe**。

> **分层陷阱（本节核心）**：官方文档以「记忆」为题，但代码里是两层**正交**能力——`harness.agent.memory.MemoryConfig` 管「**一次运行内**上下文如何压缩/沉淀」，`core.memory.LongTermMemory` 管「**跨运行**记住什么」。只看文档的「记忆」二字，容易把「已接 `MemoryConfig`」误读为「已用 AgentScope 记忆」，**长期记忆那一层会完全不被看见**。

### 3.8.2 本仓符合度逐项判定

| 官方能力 | 本仓实现 | 符合度 |
|---|---|---|
| harness `MemoryConfig`（flush + consolidation） | `ChatOfficialCapabilities.java:71-75` 显式配置两个 prompt | ✅ **已用** |
| `Memory` SPI 的 `saveTo/loadFrom`（记忆持久化委托 `AgentStateStore`） | 全仓 **零引用** | ❌ **未接** |
| `LongTermMemory`（跨会话长期记忆） | 全仓 **零挂载** | ❌ **未接** |
| `LongTermMemoryMode` / `LongTermMemoryTools` / `StaticLongTermMemoryHook` | **零引用** | ❌ 未接 |
| Mem0 / 百炼 / ReMe | **pom 零命中** | ❌ 未接 |
| 记忆相关 yml 配置键 | **零命中** | ❌ 无运行时配置面 |

**本仓记忆配置的实际内容**（`ChatOfficialCapabilities.java:72-75`，逐字）：
```
flushPrompt:        "Extract sourced reusable preferences and observations. Never store secrets
                     or raw internal reasoning. Memory and plans are working notes, not permission
                     or business approval. User identity is the authenticated chat user."
consolidationPrompt: "Consolidate sourced working notes without secrets or raw internal reasoning.
                     Memory does not approve skills, business operations or permissions.
                     Keep within %d tokens and %d characters."
```
→ 正面满足 ADR-0077 §2 规则 3 与 `docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md §2 规则3` 对「记忆不得自动成为业务权威」的要求。

### 3.8.3 `OfficialMemoryCompletionMiddleware` 判定：**协作，非双轨**

**[实证]** 28 行，`MiddlewareBase`，`order() = Integer.MAX_VALUE`（SDK 2.0.3 降序排列 → 最外层）。逻辑：在 agent 事件流结束后 `concatWith` 一个 `Mono`，调 `MemoryBackgroundTasks.awaitQuiescence(5, SECONDS)`；未静默则抛 `IllegalStateException`。

**它解的问题**：harness 的记忆 flush/consolidate 是**后台异步**（`MemoryBackgroundTasks`），而沙箱会被释放——两者竞态会导致 flush 落盘不完整。

**判定**：经**官方 `MiddlewareBase` 扩展点**实现的生命周期护栏，针对的是官方记忆的异步语义，**不重复实现任何官方能力**，**不构成双轨**。方向正确。

### 3.8.4 接入约束（若日后决定接长期记忆）

**[实证]** `HarnessAgent.Builder` **没有** `longTermMemory` 透传方法（`javap` 全量方法表确认）。官方路径只在 `ReActAgent.Builder` 上。唯一接法：

```java
HarnessAgent.builder().fromAgent(
    ReActAgent.builder().longTermMemory(m).longTermMemoryMode(LongTermMemoryMode.BOTH).build());
```

### 3.8.5 定级与待决策

**不是 bug，是能力缺口。** 业务真相在 MySQL、记忆定位为「working notes」，不接长期记忆不影响现有业务正确性。

但按 owner「全量启用、禁止功能降级」口径，**官方长期记忆能力在本仓完全空白**应登记为**待决策项**：

> **待决策**：IPD 的产品经理 / 数字员工是否需要「跨会话记住用户偏好」？
> - 若**需要** → 须选定后端（Mem0 需按 metadata 做多租户过滤，与本仓四维键天然契合；百炼为云托管；ReMe 偏工作区级轨迹摘要），并解决 §3.8.4 的 `fromAgent` 装配路径。
> - 若**不需要** → 应在文档显式登记「长期记忆为**有意不启用**」及其理由，**避免下一个执行者把它当成漏接的缺陷重���发现**。

**本轮未做**：`ChatOfficialCapabilities` 非当前并发在途文件，但改动会影响三装配点档位一致性，需与 §1.7 实施窗口合并。

## 3.9 【重要】`FailClosedAgentStateStore` 在当前装配下**不生效**（inert）

> 复核时刻 21:0x · 主协调者亲验 · 全链 `javap` 字节码闭环

### 3.9.1 官方冲突策略的默认解析（字节码）

`ReActAgent` 私有构造器，offset 216-234：
```
216: getfield      Builder.conflictPolicy
221: ifnull        231
224: getfield      Builder.conflictPolicy      ← 显式设置则用之
228: goto          234
231: getstatic     ConflictPolicy.OVERWRITE    ← 未设置则 OVERWRITE
234: putfield      this.conflictPolicy
```

**本仓 `ConflictPolicy` 全仓零命中**（`grep -rn "ConflictPolicy" ruoyi-modules/*/src ruoyi-common/*/src` → 0），
且 **`HarnessAgent.Builder` 无 `conflictPolicy` 透传方法**（`javap` 全量方法表确认；`ReActAgent.Builder` 有该 setter，但 harness 未暴露）。

> **结论：经 `HarnessAgent.builder()` 装配的三个生产装配点，生效策略恒为 `ConflictPolicy.OVERWRITE`，且当前 API 面无法更改。**

### 3.9.2 三档策略分别调用哪个 store 方法

`private long persistAgentStateCas(String user, String session, String key, AgentState, long, int)`，offset 157-168：

```
157: getstatic     ReActAgent$3.$SwitchMap$...ConflictPolicy:[I
164: invokevirtual ConflictPolicy.ordinal()
168: tableswitch { 1: 196, 2: 323, 3: 337, default: 546 }
```

| 档 | 分支内实际调用 | 语义 |
|---|---|---|
| **1 = OVERWRITE** | **`AgentStateStore.save(user, session, key, State)`**（offset 283） | **无条件覆盖，不做 CAS** |
| 2 = FAIL | `getVersioned(...)` → `saveIfVersion(...)`（347 / 472） | 版本相等才写，冲突抛 `ConcurrentSessionModificationException` |
| 3 = APPEND_MERGE | `getVersioned(...)` → `saveIfVersion(...)` | 重载基线后追加合并重试 |
| default | `new IllegalStateException(String.valueOf(conflictPolicy))`（546-565） | 非法档位硬失败 |

### 3.9.3 交叉结论：包装器只守了用不到的那条路

`FailClosedAgentStateStore.java:21-27` 的唯一行为是：
```java
public long saveIfVersion(..., long expected) {
    long version = delegate.saveIfVersion(...);
    if (delegate.supportsVersioning() && version == UNVERSIONED) {
        throw new IllegalStateException("native chat state CAS conflict");
    }
    return version;
}
```
而 `save(user, session, key, State)`（`:15-17`）与 `save(..., List)`（`:18-20`）是**零校验直通**。

| 事实 | 来源 |
|---|---|
| 生效策略 = OVERWRITE | §3.9.1 字节码 + 本仓零设置 + harness 无透传 |
| OVERWRITE 走 `save()`，**不走** `saveIfVersion()` | §3.9.2 offset 283 |
| 本仓 `save()` 是直通 | `FailClosedAgentStateStore.java:15-20` |

> **闭环结论**：`FailClosedAgentStateStore` 的 fail-closed 断言挂在 `saveIfVersion` 上，而当前装配下**该方法根本不会被调用**。这层防护在生产路径上 **inert（不生效）**，其类注释宣称的「阻止 Harness 2.0.3 未暴露的默认覆盖恢复」**未达成**。

### 3.9.4 定级与影响

| 项 | 判定 |
|---|---|
| 缺陷类型 | **防护性代码未生效**（不是功能缺失，是「以为有、实则无」） |
| 实际行为 | 同一 `(user, session)` 上并发运行 → 状态**静默互相覆盖**（last-writer-wins），无异常、无日志 |
| 现有缓解 | 本仓有 turn gate（`TURN_GATE.acquire(scope.slotId())`）做 turn 级串行化，**可能**已覆盖主要竞态窗口——**本轮未验证其覆盖度** |
| 定级 | **中**（并发正确性，非安全泄漏）。但**认知危害大于实际危害**：后续维护者会因这层包装而误判并发安全已受保护 |

### 3.9.5 【已闭环】turn gate 覆盖度核查：三条线**三套机制，无一能覆盖 CAS 竞态**

主协调者 21:0x 亲验，结论推翻了 §3.9.4 里「turn gate 可能已覆盖主要竞态窗口」的乐观假设。

| 装配点 | 串行化机制 | 作用域 | 锁粒度 | 能否覆盖同 session 的状态 CAS 竞态 |
|---|---|---|---|---|
| **chat** | 官方 `LocalSessionTurnGate`（`AgentScopeChatKernel.java:78`） | **仅进程内** | `scope.slotId()` = 四维 | ❌ **多副本部署不成立** |
| **IPD** | 自研 `ProjectAgentRunOwnership`（Redisson `RFencedLock`） | **跨节点** ✅ | **`runId`** | ❌ **锁键维度不对** |
| **coding** | **无**（`CodingServiceImpl` 零 Gate/Lock/Semaphore 命中） | — | — | ❌ **无任何保护** |

**逐条依据**：

1. **chat 线** — `LocalSessionTurnGate` 实证为纯进程内实现：
   ```java
   public final class LocalSessionTurnGate implements SessionTurnGate {
     private final ConcurrentHashMap<String, Semaphore> gates;   // ← 纯 JVM
   }
   ```
   2.0.3 JAR 中 `SessionTurnGate` **只有这一个实现类**（`unzip -l` 确认：无 Redis/Distributed 版）。
   → 单副本下有效；**多副本部署时同一 `slotId` 的两个请求会落在不同 JVM，锁不互斥**。

2. **IPD 线** — `ProjectAgentRunOwnership.java:24`：
   ```java
   RFencedLock lock = redisson.getFencedLock("ipd:project-agent:owner:" + runId);
   ```
   锁键是 **`runId`**，而 `runId` 是**每次运行**的标识。**同一 `(user, session)` 上的两次并发运行 = 两个不同 runId = 两把不同的锁 = 零互斥。**
   该锁保护的是「**运行所有权**」（哪个执行者有权跑这次运行），**不是状态存储的 CAS**——两者是不同维度。

3. **coding 线** — `CodingServiceImpl` 全文零 `Gate` / `Lock` / `acquire` / `synchronized` / `Semaphore` 命中；且其 state store 是 `InMemoryAgentStateStore`（进程内）。

> **闭环结论**：`FailClosedAgentStateStore` 想防的那个竞态，**三条线都没有被 turn gate 防住**。
> → **§3.9.5 的方案 A（只删断言、改注释）不安全**——那等于删掉一层本来就无效的假防护，但**不补任何真防护**。
> → **方案 D（在 store 侧做真 CAS）成为唯一正确解**：它与锁粒度无关，与副本数无关，只依赖 `getVersioned` / `saveIfVersion` 的正确实现。

### 3.9.6 顺带记录：三线三套串行化机制本身是「全局一致性」的反例

| 维度 | chat | IPD | coding |
|---|---|---|---|
| 状态存储 | Redis（官方）+ FailClosed 包装 | Redis | **InMemory** |
| 冲突策略 | 官方默认 OVERWRITE | 同 | 同 |
| 串行化 | 官方 in-JVM Semaphore | 自研 Redisson FencedLock（按 runId） | **无** |

三者在**同一件事（防并发）**上用了三种不同机制、覆盖三种不同边界。**这与 §3.8 的记忆 prompt 分叉、§3.7 的键构造误判同属一类：本仓四装配点各自演化，已无统一合同。** 建议纳入 §1.7「四装配点档位一致性」的登记范畴。

### 3.9.7 修复选项（结论已定，待拍板，本轮未动 Java）

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. 诚实降级** | 删除 `FailClosedAgentStateStore` 的 CAS 断言（或整类），改注释写明「当前 OVERWRITE 策略下不生效，依赖 turn gate 串行化」 | 最小改动，消除认知危害 |
| **B. 改用 ReActAgent 直装** | 放弃 `HarnessAgent.builder()`，改走 `HarnessAgent.builder().fromAgent(ReActAgent.builder().conflictPolicy(FAIL)…)` | 失去 harness 装配面，**与「官方 HarnessAgent 是唯一装配面」裁决冲突** |
| **C. 补透传** | 向官方提 issue 要 `HarnessAgent.Builder.conflictPolicy` 透传 | 依赖上游，本仓无法自解 |
| **D. 在 store 侧做真防护** | `save(...)` 内自行做 `getVersioned` 比对后再写 | 把 CAS 语义下沉到包装器，**能真正生效**，但与官方 store 实现耦合 |

> **结论：选 D**（turn gate 覆盖度已核查为不足，见 §3.9.5）。A 方案被否——它不补防护。
> **实施前提**：`FailClosedAgentStateStore.save(...)` 内改做「先 `getVersioned` 取基线版本 → 比较 → 再写」，使 OVERWRITE 路径也具备 CAS 语义。**改动局限于本仓包装器，不触碰官方类，不破坏 HarnessAgent 单一装配面。**
> **风险**：本仓包装器将不再只是「CAS 断言」，而是**真正实现乐观锁**——须补并发单测（两线程写同 key，断言一方抛错）。

### 3.9.8 附：官方 Redis store **无 TTL**

**[实证]** `RedisAgentStateStore.Builder` 全部方法仅 `keyPrefix` / 四种 client 注入 / `clientAdapter` / `build`，**无 TTL 项**；Lua 脚本无 `EXPIRE`。→ 状态键在 Redis 中**永不过期**，需依赖调用方显式 `delete`。

## 3.10 记忆落盘路径：三条线各不相同，且**真正的落点在沙箱内**

> 复核时刻 21:1x · 主协调者亲验

### 3.10.1 官方记忆写入走 `filesystem` 抽象（[实证] `ms-api` 字节码 + 主协调者复核）

官方 `WorkspaceManager.appendUtf8WorkspaceRelative` / `writeUtf8WorkspaceRelative` 的分支结构：

```java
if (this.filesystem == null) { writeLocalFile(rel, content); return; }   // 宿主磁盘分支
this.filesystem.uploadFiles(rc, ...);                                      // filesystem 分支
```

**即：官方记忆的落点由 `filesystem` 抽象决定。** 而本仓三个装配点**全部**经 `ChatOfficialCapabilities` 设了 `DockerFilesystemSpec`。

> **推论（本轮未完全闭环，见 3.10.4）**：记忆落在 **Docker 沙箱内**，而**不是**传入的宿主 workspace 目录。

记忆的实际文件形态（`WorkspaceConstants` / `MemoryConsolidator` 常量实证）：

| 路径 | 内容 |
|---|---|
| `MEMORY.md` | 策展后的长期记忆（官方定义：**跨天、跨会话**知识真源） |
| `memory/YYYY-MM-DD.md` | 当日流水账，**append-only**，格式 `\n## Memory Flush — %s\n%s\n` |
| `memory/.consolidation_state` | consolidation 水位（`WATERMARK_KEY="watermark"`，`MAX_CAS_RETRIES=5`） |
| `memory/archive*.md` | 归档 |

### 3.10.2 三条线传入的 workspace 路径**各不相同**

| 装配点 | workspace 来源 | 是否稳定 | 是否有 session 维 |
|---|---|---|---|
| **chat** | `AgentScopeChatKernel.java:93` `@Value("${chat.kernel.agentscope.workspace-root:${java.io.tmpdir}/agentscope-workspace}")`，`:328` `createWorkspace(workspaceRoot, projectId, userId, agentId)` | ✅ 固定根 | ❌ **无 session 维** |
| **IPD** | `AgentScopeProjectAgentKernel.java:339` `ProjectAgentWorkspace.prepare(workspaceRoot, projectId, userId, agentId)`；`ProjectAgentWorkspace.java:53-55` 三级 resolve | ✅ 固定根 | ❌ **无 session 维** |
| **coding** | `CodingServiceImpl.java:125` **`Files.createTempDirectory("coding-native-runtime-")`** | ❌ **每次运行全新目录** | ❌ 无 |

**chat / IPD 两条线的 workspace 按 `(projectId, userId, agentId)` 三级分桶**（`ProjectAgentWorkspace.java:53-55`，含符号链接 fail-closed 校验：`:37-51` 逐级 `toRealPath()` 比对，拒绝 symlink 逃逸）。**无 session 维 → 同一用户同一智能体的不同会话共享同一工作区。**

**coding 线每次运行新建临时目录，且从不清理**——该目录既是工作区又是 `LocalSnapshotSpec` 的基准路径（`ChatOfficialCapabilities` 内部 `workspace.resolve(".sandbox-snapshots")`），**运行结束即成为孤儿目录**。

### 3.10.3 由此得出的三条结论

1. **coding 线的记忆必然不跨运行**——workspace 每次全新，且 `CodingServiceImpl:138` 还额外用了 `InMemoryAgentStateStore`（进程重启即丢）。
2. **chat / IPD 线的记忆在宿主侧路径是稳定的**（`/tmp/agentscope-workspace/{projectId}/{userId}/{agentId}`），但**由于落点在沙箱内，宿主路径稳定 ≠ 记忆存活**。
3. **无 session 维**意味着工作区（及其中的一切文件）在同用户同智能体的多个会话间**共享**。这对「跨会话记忆」是**特性**，但对「会话隔离」是**边界放宽**——需确认 `IsolationScope.SESSION` 的沙箱层是否补上了这层隔离。

### 3.10.4 【未闭环】沙箱快照的 take / restore 时机未查实

**[实证已确认]** 官方快照 SPI 存在：
```java
public interface SandboxSnapshotSpec { SandboxSnapshot build(String id); }
public interface SandboxSnapshot {
    void persist(InputStream) throws Exception;
    InputStream restore()   throws Exception;
    boolean isRestorable()  throws Exception;
    String getId();  String getType();
    default boolean isPersistenceEnabled();
}
// LocalSnapshotSpec.build(id) → new LocalSandboxSnapshot(basePath, id)
```

**未查实**：`SandboxSnapshotSpec.build(...)` 的**调用方**、`persist()` / `restore()` 的**触发时机**、以及 `isPersistenceEnabled()` 在 `DockerFilesystemSpec` + `LocalSnapshotSpec` 组合下的**实际取值**。

> **因此本文件无法断言「本仓的记忆在运行结束后是否存活」。** 在这条查实前，§3.8.4 记的「consolidationPrompt 覆盖丢失跨会话语义」**到底是不是真缺陷，尚不能定论**：
> - 若沙箱快照**生效** → 记忆跨运行存活 → 丢失跨会话语义是**真缺陷**（高优先级）。
> - 若快照**不生效** → 记忆本就不跨运行 → 丢失的是一个**用不上的**能力（低优先级），但**coding 线每次新建临时目录**这一条仍是独立成立的缺陷。

**已将「沙箱快照 take/restore 时机」列为最高优先待查项**，已在 §5 登记。

## 3.11 记忆开关真相：**默认全开且官方无「关闭」入口**（推翻项目文档）

> 复核时刻 21:0x · `memory-track` 报告 + 主协调者独立复核

### 3.11.1 记忆是**默认装配**，`.memory(...)` 调不调都在

**[实证]** `HarnessAgent$Builder.<init>` offset 82-86：
```
MemoryConfig.defaults();  putfield memoryConfig
```
`memoryConfig` 字段**构造即有值**。`build()` offset 1079-1288 的装配判定只有两个否决条件：
```
1080: memoryConfig.model()            // 独立的记忆模型
1105: ifnull → 1289                   // ← 无 model → 整段跳过
1110: disableMemoryHooks? → 1289      // ← 显式关钩子 → 整段跳过
```
只要 `.model(...)` 非空**且**未调 `disableMemoryHooks()`，以下三者**无条件**装入：
`MemoryFlushMiddleware`(1150) / `MemoryConsolidator`(1203) / `MemoryMaintenanceMiddleware`(1249)。

> **推论**：本仓 7 行 `disableSubagents` 等 disable **从未包含记忆开关**；记忆一直是开的，且**开不关都一样**（除非整个 agent 不给 model）。

### 3.11.2 【文档纠错】项目文档「Memory 有意关 / 双 disable」是**假的**

`docs/ipd-系统说明/AgentScope官方化-Quality域Verifier缺口设计-20261002.md:17` 原文：

> `| Memory | ✅ 绿（有意关） | 双 disable；业务记忆在 ipd 库表，MEMORY.md 不当事实源（AGENTS.md 红线） |`

**[实证] 主源树 `disableMemory` 零命中**（`grep -rn "disableMemory" ruoyi-modules/*/src/main ruoyi-common/*/src/main` → 0）。
`disableMemoryHooks()` 仅出现在两个**测试**文件（`AgentScopeKernelConcurrencyPocIT.java:281`、`AgentScopeAuditHookTest.java:45`）。

| # | 该文档断言 | 事实 | 判定 |
|---|---|---|---|
| 1 | 「Memory 有意关」 | 记忆默认全开 | ❌ |
| 2 | 「双 disable」 | 主源树零 `disableMemory*` | ❌ |
| 3 | 「MEMORY.md 不当事实源（AGENTS.md 红线）」 | **这条对**——本仓靠 `consolidationPrompt` 约束，见 §3.8.2 | ✅ 成立 |

> **修正**：记忆的「不当事实源」是靠 **prompt 约束**实现的，**不是**靠 disable 开关。
> **这正是 §3.8.4 那个缺陷的根源**——维护者想「关掉记忆的记忆功能」，实际改的是 `consolidationPrompt`（记忆的**内容**），而记忆**机制**从未被关。于是「关记忆」这个诉求被转嫁成了「改记忆内容」——**手段与目标错配**，跨会话语义的丢失就是这个错配的副作用。

### 3.11.3 隔离作用域：三个生效装配点**全部是 SESSION**（含 coding）

**[实证]** 官方从 filesystem spec 推导 `isolationScope`（`HarnessAgent$Builder` 无 `isolationScope(...)` 公开方法）：
```
353: getstatic IsolationScope.USER          // 默认
387-413: sandboxFilesystemSpec != null → 取 sandbox.getIsolationScope()   ← 本仓走这条
```

| 装配点 | 设置点 | 生效值 |
|---|---|---|
| chat | `ChatOfficialCapabilities.java:60` `filesystem.isolationScope(IsolationScope.SESSION)` | **SESSION** |
| IPD | `ProjectAgentOfficialSandbox.java:49` 同 | **SESSION** |
| **coding** | 经 `CodingServiceImpl.java:139` → `ChatOfficialCapabilities.configure` → `:62` `builder.filesystem(filesystem)` + `:60` | **SESSION** |
| `ProjectAgentFoundationTools.java:83` | `IsolationScope.USER` | 未接线扩展点，**非生效装配点** |

> **一处智能体误报已纠正**：`memory-track` 报告称「`CodingServiceImpl` 不设 filesystem spec，取默认 `USER`」。**该结论错误**——coding 线经 `ChatOfficialCapabilities.configure`（`CodingServiceImpl.java:139`）拿到了 `DockerFilesystemSpec` 与 `IsolationScope.SESSION`。**三个生效装配点的隔离作用域一致，均为 SESSION。**

### 3.11.4 与 owner「可配置」裁决的关系

owner 裁决是「可配置，但默认必须全部完整可用」。就**记忆**而言：

| 官方提供的能力 | 能否做成「配置化关闭」 |
|---|---|
| 记忆**机制**（flush/consolidation/maintenance 三个中间件） | ✅ 可——唯一入口是硬编码调 `.disableMemoryHooks()`，可改为条件调用 |
| 记忆**内容**（flushPrompt / consolidationPrompt） | ✅ 可——已是可配置项 |
| 记忆**持久化**（`Memory.saveTo/loadFrom`） | ⚠️ 官方未在本仓装配面暴露该 SPI（见 §3.8.2），**无法配置** |

> **结论**：记忆是**可以**做到「默认全开 + 配置化关闭」的，但**必须显式实现**——官方只给了 `disableMemoryHooks()` 这一个硬开关，没有默认关闭的配置面。§1.7 步骤 1 的 `OfficialCapabilityConfig` 应当**包含 `memory` 键**（默认 `true`），关闭时调 `disableMemoryHooks()`。

## 3.12 【owner 直接提问】记忆能力完整度 + 多人/团队隔离判定

> owner 21:09 提问：①记忆能力是否完整实现 ②不同人之间记忆不同、同一团队同一项目怎么处理
> 前提（owner 明示）：「基于默认是开的，不需要关掉」——故本节**不再讨论开关**，只判定**能力完整度与隔离语义**。

### 3.12.1 问题①：记忆能力完整度

| 层 | 状态 | 依据 |
|---|---|---|
| **机制装配** | ✅ **完整** | 三个中间件自动装入、零 `disableMemory*`（§3.11.1 / §3.11.2） |
| **按人隔离** | ✅ **成立** | 见 §3.12.2 |
| **跨会话内容语义** | ⚠️ **有损** | 官方 `consolidationPrompt` 被整体替换，「MEMORY.md = 跨天跨会话知识真源」指令丢失（§3.8.4） |
| **运行间存活** | ❓ **待验** | 沙箱快照 take/restore 时机未查实（§3.10.4） |
| **官方 StateBacked 持久化** | ❌ **未接** | `Memory.saveTo/loadFrom(AgentStateStore,…)` 全仓零引用（§3.8.2） |
| **`LongTermMemory` 长期记忆** | ❌ **未接** | 全仓零挂载；三个官方后端零依赖（§3.8.2） |

> **一句话判定**：**机制完整、隔离正确、语义有损、持久性待验。**
> 「完整实现」若指「官方记忆三件套都装上了」→ **是**；若指「官方的记忆能力面被完整交付」→ **否**（缺 StateBacked 持久化与 LongTermMemory 两层）。

### 3.12.2 问题②：不同人之间 / 同一团队同一项目

#### 已实证的分区事实

| 事实 | 依据 |
|---|---|
| 工作区路径 = `root/{projectId}/{userId}/{agentId}` | `ProjectAgentWorkspace.java:53-55` 三级 `resolve` |
| **无 session 维** | 同上——同一人同一智能体的所有会话共用一个工作区 |
| 运行上下文 = 复合 userId `p{projectId}:u{userId}` + 复合 sessionId `a{agentId}:s{sessionId}` | `KernelScopeKey.java:88-90` |
| 记忆文件 = `MEMORY.md` + `memory/YYYY-MM-DD.md`，落在该工作区内 | `MemoryConsolidator` / `WorkspaceConstants` 常量 |
| 三个生效装配点隔离作用域统一为 `IsolationScope.SESSION` | §3.11.3 |

#### 判定矩阵

| 场景 | 当前行为 | 是否符合预期 |
|---|---|---|
| **不同人**（userA vs userB，同项目同智能体） | 工作区路径不同 → **各自独立 `MEMORY.md`**，天然隔离 | ✅ **正是要的** |
| **同一人，不同会话** | 工作区无 session 维 → **同一份 `MEMORY.md`** | ✅ 跨会话记忆成立 |
| **同项目、同团队、不同人** | 工作区含 `userId` → **各人记忆完全独立，不共享** | ⚠️ **记忆层无共享** |
| **同项目、同团队** | ✅ **但走的是另一条通道**——见下 | ✅ 符合设计 |

#### 项目级共享走的是「知识库」，不是「记忆」

**[实证]** `ProjectKnowledgeRetriever.retrieve(personId, projectId, docType, query)`（`:51`）三路合并检索：

```java
knowledge = knowledgeVectors.search(projectId, personId, query);   // 向量检索
text     = textSearch.search(projectId, query);                    // 全文检索
docs     = vectors.retrieve(projectId, docType, query);            // 文档检索
return merge(merge(knowledge, text), docs);
```

**按 `projectId` 检索、DB/向量库承载、会话内按需检索**——这是**团队共享事实**的通道，**与 per-user 记忆完全分离**。

> **设计判定：这套分离是正确的，不应合并。**
>
> | 通道 | 作用域 | 载体 | 权威性 | 生命周期 |
> |---|---|---|---|---|
> | **记忆**（`MEMORY.md`） | **个人**（per user+project+agent） | 沙箱工作区文件 | ⚠️ **非权威**，仅 working notes | 跨会话 |
> | **知识库**（`ProjectKnowledgeRetriever`） | **项目**（per projectId） | 向量库 + 全文 + 文档表 | ✅ 权威事实 | 长期 |
> | **业务表**（MySQL） | 业务 | 关系表 | ✅ 唯一权威 | 永久 |
>
> 这与 ADR-0077 §2 规则 3 / `docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md §2 规则3`「记忆不得自动成为业务权威」**完全自洽**：
> **共享的走知识库（权威、可审计），个人的走记忆（草稿、跨会话便利）。**
> 若把团队共享塞进 `MEMORY.md`，就等于让一个**非权威、per-user、活在工作区里**的载体承担团队知识——那才是真正的设计错误。

#### 对 owner 的直接回答

- **「不同人之间记忆不同」** → **已经是这样**，天然按 userId 隔离，无需改动。
- **「同一个团队同一个项目怎么处理」** → **走项目知识库，不走记忆**。记忆刻意保持 per-user 不共享，这是对的。若业务上确实需要「个人记忆在团队内可见」，正确做法是**增加一条记忆晋升到知识库的流程**（需 owner 拍板），而不是放开记忆的 userId 隔离。

## 3.13 【实施记录】owner 21:11「按建议完整实现」——第一批已落地

> 实施时刻 21:1x · **并发写入者仍在活跃**（21:12 实测近 20 分钟内 19 个 main java 被改，
> `AgentScopeProjectAgentKernel.java` 21:04:13 刚动），故本批**只做不在其飞行中的文件**。

### 3.13.1 本批交付（4 个文件）

| # | 文件 | 改动 | 验证 |
|---|---|---|---|
| 1 | `chat/kernel/FailClosedAgentStateStore.java` | **方案 D 落地**：`save(...)` 内自建基线版本 → `saveIfVersion` CAS → 冲突 fail-closed。构造器补 `Objects.requireNonNull` | `javac` EXIT=0 |
| 2 | `chat/kernel/FailClosedAgentStateStoreTest.java`（新建） | 7 条用例，含**确定性竞速注入**（fake store 在「基线读出后」抢先写） | **7/7 绿** |
| 3 | `chat/kernel/KernelScopeKey.java` | 按 §3.7 字节码实证**改写键结构注释**（原注释三处断言全错）；`slotId()` 补「与官方同名不同物」警告 | `javac` EXIT=0 |
| 4 | `docs/…/AgentScope官方化-Quality域Verifier缺口设计-20261002.md` | 按 §3.11.2 更正「Memory 有意关 / 双 disable」失实陈述 | 文档 |

**方案 D 的实现要点**（`FailClosedAgentStateStore.java`）：
```java
private void writeWithOptimisticLock(String user, String session, String key, State value) {
    if (!delegate.supportsVersioning()) { delegate.save(...); return; }
    long baseline = delegate.getVersioned(user, session, key, (Class<State>) value.getClass()).version();
    if (baseline == UNVERSIONED) { delegate.save(...); return; }   // 无法建基线 → 退化
    if (delegate.saveIfVersion(user, session, key, value, baseline) == UNVERSIONED) {
        throw new IllegalStateException("native chat state CAS conflict on key=" + key
            + " expectedVersion=" + baseline);
    }
}
```
**首次写入不会被自己挡住**：`RedisAgentStateStore.getVersioned` 对不存在的键返回 `lconst_0`（版本 0），
配合 `saveIfVersion(expected=0)` 的 create-if-absent 语义，基线为 0 → 写入成功（`javap` 实证）。

### 3.13.2 自证能红（变异验证）——**且抓到过自己写的废测试**

| 轮次 | 变异 | 结果 |
|---|---|---|
| 第 1 轮 | `save()` 退回 `delegate.save(...)` | ❌ **测试仍全绿 → 判定测试无效** |
| 第 2 轮 | 同上，改写为确定性竞速用例后 | ✅ **1 条失败 → 测试有效** |

**第 1 轮失败的原因**（记入教训）：原并发断言 `succeeded + conflicted == writers` 是**恒真式**
（每个写者不是成功就是抛异常），`succeeded >= 1` 亦近乎必然成立——**该断言抓不到任何东西**。
改为「fake store 在 `getVersioned` 取基线后注入一次竞对写入」，使 CAS 失败条件**确定性可复现**。

> **本轮再次实证了 §1.8 反思 6**：静态断言的**存在**不等于它**守得住**。
> 本次若不做变异验证，就会交付一个「7/7 全绿但抓不到缺陷」的测试——
> 与本文件 §3.9 的 `FailClosedAgentStateStore` 缺陷、以及 §3.2 的门禁逃逸，属**完全同型**。

### 3.13.3 本批**未做**及原因

| 项 | 原因 |
|---|---|
| 7 个 `disable` → 配置化（§1.7 步骤 1-2） | 直接改 3 个装配点，**并发写入者正在写这三个文件** |
| `consolidationPrompt` 恢复官方跨会话语义（§3.8.4） | 需改 `ChatOfficialCapabilities`（共享热点）+ `ProjectAgentNativeProfile`；且**其必要性取决于 §3.10.4 沙箱快照链是否查实** |
| 门禁升级（§1.8 替换方案） | 需与实现同步，依赖上一项 |
| PoC 迁 test 树（§3.6.4） | `PocKernelSupport.java` 仍在脏区 |
| `Scope#slotId()` 重命名为 `turnGateKey()` | 会波及多文件调用点，需独立窗口；本批**只加警告注释**，未改签名 |

## 3.14 【已闭环】沙箱快照链查实：记忆是 **per-session**，不是跨会话

> 复核时刻 22:2x · 主协调者亲验 `javap` 字节码 · **本条闭合 §3.10.4 的最高优先待查项**

### 3.14.1 完整调用链（2.0.3 实证）

```
SandboxLifecycleMiddleware.acquireForCall(rc)  →  SandboxManager.acquire(ctx, rc)     // 调用前：恢复
SandboxLifecycleMiddleware.releaseForCall(rc)  →  SandboxManager.persistState(...)    // 调用后：持久化
```

- 全 JAR 扫描确认：**`persistState` 的唯一调用方是 `SandboxLifecycleMiddleware`**；
- `SandboxManager.carryOverPersistedSnapshotId(sandbox, id, spec)`：恢复旧状态时把**旧快照 id** 带进新沙箱（偏移 73 `spec.build(oldId)` → 78 `setSnapshot`），使 `persistState` 覆盖**同一快照槽位**；
- `SandboxSnapshot.isPersistenceEnabled()` 默认 **`true`**（`iconst_1; ireturn`），`LocalSandboxSnapshot` **未覆写**；
- 本仓 `ChatOfficialCapabilities:58` 设 `.snapshotSpec(new LocalSnapshotSpec(workspace.resolve(".sandbox-snapshots")))`，`DockerFilesystemSpec` 有对应 setter（`snapshotSpec(...)`）→ **持久化链路已接通**。

### 3.14.2 状态键：`IsolationScope` 决定跨不跨会话

`SessionSandboxStateStore.slotSessionId(SandboxIsolationKey)` 的 `tableswitch {1 to 4}`，四个拼接配方（常量池实证）：

| IsolationScope | slot sessionId 格式 | 含 sessionId？ |
|---|---|---|
| `SESSION`（**本仓**） | `sandbox/session/{sessionId}` | ✅ 含 |
| `USER` | `sandbox/user/{userId}/{sessionId}` | ✅ **仍含** |
| `AGENT` | `sandbox/agent/{agentId}` | ❌ **不含** |
| `GLOBAL` | `sandbox/global` | ❌ |

**⇒ 结论：**
- **同 session 跨调用：记忆持久** ✅（`acquire` 恢复 + `release` 落盘）
- **不同 session：记忆不共享** ❌

### 3.14.3 对 §3.8.4 / §3.10 的定级修正

| 原判定 | 修正后 |
|---|---|
| §3.8.4「`consolidationPrompt` 覆盖丢失官方跨会话语义」是**高优先级缺陷** | ❌ **降级为无实际影响**。官方默认 prompt 承诺的「cross-session knowledge」在 `IsolationScope.SESSION` 下**本就不可达**；本仓把覆盖改回官方原文也不会得到跨会话记忆。**该覆盖不是缺陷，是无效动作。** |
| §3.10「chat/IPD 工作区无 session 维 → 同人跨会话共享工作区」 | ⚠️ **表述不准确**。宿主 workspace 路径确无 session 维，但**记忆实际落在沙箱内**，而沙箱按 `sandbox/session/{复合 sessionId}` 分槽 → **实际仍按 session 隔离**。 |

### 3.14.4 若产品确需跨会话记忆：唯一可行形态及其代价

| 方案 | 隔离键 | 效果 | 代价 |
|---|---|---|---|
| 现状 `SESSION` | `sandbox/session/a{agentId}:s{sessionId}` | 记忆不跨会话 | — |
| 改 `AGENT` + 复合 agentId | `sandbox/agent/p{projectId}:u{userId}:a{AGENT_ID}` | ✅ 同人同项目跨会话共享记忆；✅ 不同人/不同项目仍隔离 | ⚠️ **会话间不再隔离工作区文件**——transcript、草稿、临时文件一并跨会话共享 |
| 改 `AGENT` + 现有常量 `AGENT_ID` | `sandbox/agent/{常量}` | **全体项目全体用户共享一个沙箱** | ❌❌ **跨租户泄漏，绝对禁止** |

> **判读**：跨会话记忆在当前隔离设计下**不是一个开关，而是一次隔离语义变更**。
> 它必须以「同 user+project 跨会话共享工作区」为前提被 owner 明确接受——因为记忆不是唯一跨会话的东西，**沙箱里的一切都会跟着跨会话**。
>
> **本轮不动**：该变更影响三个装配点的 `IsolationScope`，且与 §1.7 的能力配置化同属一类需拍板事项。**登记为待决策，不擅自实施。**

## 四、并发写入观测（让路依据，非指控）

**[实证]** 观测窗口 14:36:19–14:36:54，同一工作树内出现密集写入：

| 文件 | mtime | git 状态 |
|---|---|---|
| `chat/kernel/ChatOfficialCapabilities.java` | 14:36:19 | `??` **未纳入 git 跟踪** |
| `ipd/agent/kernel/AgentScopeProjectAgentKernel.java` | 14:36:46 | `MM` |
| `service/coding/impl/CodingServiceImpl.java` | 14:36:54 | 已跟踪，脏 |

该会话同期新建 `ipd/agent/kernel/ProjectAgentOfficialCollaboration.java`（114 行），接入官方 `LocalTeamClient` / `TeamClient` / `WorkspaceMessageBus` / `MessageBus` / `AsyncToolRegistry`，其自述注释为「官方 run 内协作装配；TeamClient 不是 IPD 产品线团队及业务审批权」——**方向与本轮 owner 指令一致**。

**[实证]** 本轮自身结论的有效期已被此观测证伪一次：14:37 的能力扫描显示 `subagent` / `teamsMode` / `messageBus` / `asyncToolRegistry` 全仓 0 调用，该结论在 14:36:46 之后即不再成立。

> **方法论登记**：本仓的审计结论必须附「观测时刻 + `git HEAD` + 近 N 分钟改动清单」三元组才可消费。此规则与 `AgentScope归位-基线快照-20261002.json` 的 `$comment` 一致，本轮为该规则的一次实证。**任何仅含「某能力 0 调用」而无时间戳的审计结论，一律视为过期。**

---

## 五、本轮未做与未验证（诚实登记）

- **未修改任何 Java**（owner 拍板让路）。7 个 disable 保持原样，门禁保持红。
- 未修改 `AGENTS.md` / `ADR-0077` 本体——两者均为并发写入热点，改动归属未确认。
- 未验证 §2 表第 8 条（ShortDrama / CustomApi 两项依赖是否已随摘链消解）。
- 未做运行期验收，无 `mvn test` / `verify.sh` 全链结果。
- 2.0.3 的 Builder **API 面**与**构造器布尔默认值**已由 `javap` 对真实 JAR 字节码**实证**（§1.3、§1.4）。**未实证项**：
  1. 各能力的**装配条件**（默认 `false` 的字段在何处被读取、满足什么条件才真正装配）——需追 `build()` 方法体。
  2. ~~`TranscriptStore` 回退分支未定位~~ → **已于 §3.5 完成字节码实证**（两分支结构），并确认四装配点均走 `ChatSafeTranscriptStore`，不落默认路径。
  3. **§3.3 末条**（`disableSessionPersistence()` / `disableDefaultWorkspaceSkills()` 在主 agent 上零消费）——若属实则为**无效防护**，会产生安全假象。
  0. ~~沙箱快照 build/persist/restore 调用方与触发时机~~ → **已于 §3.14 完整闭环**：记忆为 per-session，§3.8.4 的 prompt 覆盖判定**降级为无实际影响**。
  4. IPD 内核四维隔离与权限扩展点冲突的完整结论：`ipd-kernel` 报告本轮**只收到截断稿**，其 G1/G4/G5/G14/G16-G22 等条目**未经主协调者复核**，不得直接作为实施依据。
  5. **`ProjectAgentOfficialCollaboration` 系死代码**——实测全仓仅 3 处自身声明，**零生产调用方**（`kernel/ProjectAgentOfficialCollaboration.java:26/27`、`config/ProjectAgentOfficialCollaborationRedis.java:21`）。mtime 13:47 / 14:02，**早于 14:36 并发窗口**，并非该窗口新建。即：官方 team/messageBus/asyncToolRegistry 协作能力**本轮确认仍未接入**。
- 门禁规则草案（`capability-matrix` 产出）本轮**仅采纳主协调者亲自复现的 B1/B2 两例**；其 8 条正则盲区与 `scope-key-statements.py` 的 8 条盲区**未逐条复现**，标注为**待复核**。

---

## 六、附：官方 7 件套口径纠正

**[待复核 · 来源：`official-api`]** 官方「7 件套 building blocks」指 **`agentscope-core` 的核心概念面**（`io.agentscope.core.*`：agent / message / model / tool / memory / state / skill 等），**不是** `agentscope-harness/` 下的模块。`agentscope-harness` 是 `ReActAgent` 的**薄包装层**，独立于 7 件套。

本仓既有审计文档 `docs/ipd-系统说明/AgentScope-building-blocks-7件套审计-20261002.md:19` 用的正是这个口径（标为「L1 官方核心 / 7 件套本体」），与 `agentscope-harness` 并列为 L3 —— **该口径正确，无需更正**。此处登记仅为避免后续执行者把两者混为一谈。

---

- marker: agentscope-capability-authority-arbitration-20261002
