# AgentScope 能力启用裁决：三方权威冲突 + ADR-0077 失实声明更正

- 类型：**docs-only 裁决稿**，零 Java 变更，零配置变更
- 观测时刻：2026-10-02 14:37–14:44 PDT · `git HEAD=2db6d4ef` · 工作树脏（含 7 个 disable 违规）
- 证据分级：**[实证]** = 本轮直接读字节 / 跑命令；**[推断]** = 由字节推导，未运行时验证
- 适用范围：本文件**不裁决业务该走哪条路**，只裁决「**谁有权决定能力开关**」，并更正 ADR-0077 与工作树的 8 条不一致
- 上游裁决来源：owner 本轮指令「官方能力全量启用、禁止禁用、禁止降级」+「严格基于官方的 AgentScope 融合本项目」+ 14:53 裁决「**可以是可配置但是必须要全部完整默认可用**」
- **修订记录**：15:09 owner 追问后撤回 §1.7 步骤 3 原门禁升级方案（过度设计），替换方案与五条反思见 **§1.8**；15:5x 完成 TranscriptStore 回退与 PoC 装配复核，**基线快照的 PoC P0 结论予以撤回**，见 **§3.5**
- 文档状态：裁决已定，**实施未启动**（并发写入者占用写窗口，见 §4）

---

## 一、三方权威冲突（已于 §1.5 裁决；§1.1–1.4 为裁决依据留档）

同一问题（子智能体 / 动态子智能体 / 动态技能是否关闭）当前有**三份互相矛盾的依据**：

| # | 来源 | 原文口径 | 作用域 | 时点 |
|---|---|---|---|---|
| A | **owner 指令**（本轮 + ADR-0077 §1） | 官方能力全量启用、**禁止禁用、禁止降级** | 全局 | 2026-10-02 最新 |
| B | **ADR-0077** §2 规则 3 | 「不得以 disable 开关关闭整类能力」；§3 把 disable 开关行定级为「与最新全量启用目标冲突，**待迁移及验收**」；§6「砍掉的应该是与官方重叠的自研底层，**不是与官方重叠的官方能力**」 | 全局（判定规则四条，验收级） | 2026-10-02 |
| C | **AGENTS.md:77** | 「不打开动态技能、子智能体或 plan mode，不用 `plans/PLAN.md` 或 workspace `MEMORY.md` 当业务事实源」 | **单刀边界** | 早于 A/B |

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
| 引用 `AGENTS.md:77` | **作用域误用** | 该行完整语境是「产线知识库 MCP 用 AgentScope 替换本仓分叉接入……**这一刀**不把 AgentScope 从 2.0.3 升到 2.0.4，不引入 AgentScope Service」。它是**一次替换刀的范围边界**，不是全局能力策略。把它当作全局禁开依据是作用域提升。 |
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

**裁决效力**：本节由 owner 拍板，**取代** §1.4 的建议稿，**取代** `AGENTS.md:77` 作为全局能力策略的依据。

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
> 2. `AGENTS.md:77` 增补作用域限定词，明确其为**产线 MCP 替换刀**的边界条款，不构成全局能力策略；引用时必须连同该限定。
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
  4. IPD 内核四维隔离与权限扩展点冲突的完整结论：`ipd-kernel` 报告本轮**只收到截断稿**，其 G1/G4/G5/G14/G16-G22 等条目**未经主协调者复核**，不得直接作为实施依据。
  5. **`ProjectAgentOfficialCollaboration` 系死代码**——实测全仓仅 3 处自身声明，**零生产调用方**（`kernel/ProjectAgentOfficialCollaboration.java:26/27`、`config/ProjectAgentOfficialCollaborationRedis.java:21`）。mtime 13:47 / 14:02，**早于 14:36 并发窗口**，并非该窗口新建。即：官方 team/messageBus/asyncToolRegistry 协作能力**本轮确认仍未接入**。
- 门禁规则草案（`capability-matrix` 产出）本轮**仅采纳主协调者亲自复现的 B1/B2 两例**；其 8 条正则盲区与 `scope-key-statements.py` 的 8 条盲区**未逐条复现**，标注为**待复核**。

---

## 六、附：官方 7 件套口径纠正

**[待复核 · 来源：`official-api`]** 官方「7 件套 building blocks」指 **`agentscope-core` 的核心概念面**（`io.agentscope.core.*`：agent / message / model / tool / memory / state / skill 等），**不是** `agentscope-harness/` 下的模块。`agentscope-harness` 是 `ReActAgent` 的**薄包装层**，独立于 7 件套。

本仓既有审计文档 `docs/ipd-系统说明/AgentScope-building-blocks-7件套审计-20261002.md:19` 用的正是这个口径（标为「L1 官方核心 / 7 件套本体」），与 `agentscope-harness` 并列为 L3 —— **该口径正确，无需更正**。此处登记仅为避免后续执行者把两者混为一谈。

---

- marker: agentscope-capability-authority-arbitration-20261002
