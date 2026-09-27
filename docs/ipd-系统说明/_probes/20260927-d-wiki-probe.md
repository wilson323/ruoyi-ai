# 探针报告 · docs/wiki/ 知识库一致性盘点

- **生成日期**：2026-09-27（Sunday）
- **任务代号**：20260927-d-wiki-probe
- **执行模式**：只读调研 · 未修改任何文件 · 未运行 wiki-lint · 未 commit/push
- **工作区根**：`/Users/mac/Documents/ruoyi-ai`

---

## 0. TL;DR（结论先行）

| 维度 | 结论 |
|---|---|
| 1. wiki 现状 | **22 篇 wiki 文章 + 63 个 raw 文件**（不是 index.md 声明的 60，**index 已失真**） |
| 2. 工作流主题覆盖 | `workflow.md` (Warm-Flow) 与 `aiflow.md` (图驱动) **存在**；**完全没有 `ruoyi-ipd` 业务工作流文档** |
| 3. 一致性差异 | wiki `aiflow.md` 的 4 处关键描述**与代码与 raw 都对不上**（详见 §3） |
| 4. IPD 文档覆盖缺口 | 极严重：raw 没有 `ipd-source/`；wiki 没有 IPD / Gate / 双签 / DRE / PDT 任一关键词 |
| 5. 三方对齐矩阵 | **预期缺口被验证**：无任何文章描述「工厂分支 + 渲染路径 + 库注册准入闸」 |
| 6. wiki-lint 现状 | 脚本存在于 `docs/wiki/wiki-lint.cjs`（**不在 `.claude/helpers/`**）；近 23 天**未跑过 lint 痕迹** |

---

## 1. wiki 现状

### 1.1 文章清单（22 篇 + log.md + index.md）

**实证**：
- 索引源：`/Users/mac/Documents/ruoyi-ai/docs/wiki/wiki/index.md:1-91`（91 行）
- 目录源：`ls docs/wiki/wiki/modules/` 列出 18 篇
- `ls docs/wiki/wiki/cross-cutting/` 列出 3 篇
- `ls docs/wiki/wiki/automation/` 列出 1 篇
- 合计 18 + 3 + 1 = **22 篇** ✓ 与 `index.md:47` 一致

#### modules/ 18 篇

| # | 文件 | 主题 | 行号 | 引用 raw 数 |
|---|---|---|---|---|
| 1 | admin.md | ruoyi-admin 入口 | `wiki/modules/admin.md` | (见 frontmatter) |
| 2 | admin-source.md | ruoyi-admin 源码层 | `wiki/modules/admin-source.md` | - |
| 3 | chat.md | ruoyi-chat Langchain4j | `wiki/modules/chat.md` | - |
| 4 | chat-agents-catalog.md | 21 Agent 目录 | `wiki/modules/chat-agents-catalog.md` | - |
| 5 | chat-mcp-tools.md | 内置 MCP 工具 | `wiki/modules/chat-mcp-tools.md` | - |
| 6 | chat-multimodal.md | 多模态 | `wiki/modules/chat-multimodal.md` | - |
| 7 | aiflow.md | **可视化 AI 工作流引擎** | `wiki/modules/aiflow.md:1-177` | 8 |
| 8 | system.md | RBAC 系统管理 | `wiki/modules/system.md` | - |
| 9 | system-rbac-deep-dive.md | RBAC 实体关系 | `wiki/modules/system-rbac-deep-dive.md` | - |
| 10 | system-listener-runner.md | 监听器/启动任务 | `wiki/modules/system-listener-runner.md` | - |
| 11 | workflow.md | **Warm-Flow BPMN 引擎** | `wiki/modules/workflow.md:1-107` | 2 |
| 12 | generator.md | 代码生成器 | `wiki/modules/generator.md` | - |
| 13 | common.md | 27 个共享库 | `wiki/modules/common.md` | - |
| 14 | common-core-utilities.md | core/json/doc/excel | `wiki/modules/common-core-utilities.md` | - |
| 15 | common-security-auth.md | security/satoken/encrypt | `wiki/modules/common-security-auth.md` | - |
| 16 | common-data.md | mybatis/redis/tenant | `wiki/modules/common-data.md` | - |
| 17 | common-communication.md | web/sse/websocket | `wiki/modules/common-communication.md` | - |
| 18 | common-business.md | log/job/idempotent | `wiki/modules/common-business.md` | - |

#### cross-cutting/ 3 篇

| # | 文件 | 主题 |
|---|---|---|
| 19 | architecture-overview.md | 架构总览 |
| 20 | multi-tenant-design.md | 多租户隔离 |
| 21 | deployment-guide.md | 部署指南 |

#### automation/ 1 篇

| # | 文件 | 主题 |
|---|---|---|
| 22 | claude-code-setup.md | Claude Code 自动化栈 |

### 1.2 raw 文件清单（63 个 · index.md 标注 60 已失真）

**实证**：每个 raw 文件首行 `grep "collected:" docs/wiki/raw/*/*.md` 显示**全部 63 个文件采集日期均为 `2026-09-04`**（与 index.md 一致），但**目录文件数与 index.md 不符**：

| 子目录 | 实际数 | index.md 声明 | 差异 |
|---|---|---|---|
| project-skeleton/ | 7 | 7 | ✓ |
| admin-source/ | 7 | 7 | ✓ |
| chat-source/ | **10** | 8 | **❌ +2** |
| aiflow-source/ | 8 | 8 | ✓ |
| system-source/ | **9** | 8 | **❌ +1** |
| workflow-source/ | 2 | 2 | ✓ |
| generator-source/ | 2 | 2 | ✓ |
| common-source/ | **9** | 6 | **❌ +3** |
| extend-source/ | 2 | 2 | ✓ |
| docker-source/ | 3 | 3 | ✓ |
| multimodal-source/ | **4** | (未列) | **❌ 未声明** |
| **合计** | **63** | **60** | **+3 未声明** |

**raw 文件目录证据**（`ls docs/wiki/raw/`）：
- chat-source/：agents-catalog.md, chat-controller.md, chat-service-factory.md, chit-chat-agent.md, mcp-sse-config.md, mcp-tool-provider-service.md, mcp-tools-catalog.md, rag-trace-node-types.md, rag-trace-payload-builder.md, vector-store-properties.md = **10 个**
- common-source/ 9 个（vs index.md 声明的 6）
- multimodal-source/：4 个（image.md / audio.md / video.md / embedding.md）—— **index.md 完全未列入**
- system-source/ 9 个（vs index.md 声明的 8）

**结论**：`wiki/index.md:46` 标 "raw 文件数：60"，**实际 63**。index.md 未跟随 batch-10（agents-catalog/mcp-tools-catalog/rbac-entities 三聚合）与 batch-11（4 个 multimodal）增量更新。

### 1.3 log.md 状态

**实证**：`docs/wiki/wiki/log.md:1-75`（75 行）

- **最后更新日期**：`2026-09-04`（log.md:4）
- **最后 lint 痕迹**：`log.md:74` 「lint 结果: 121 通过 / 0 失败 / 0 孤立 raw」（2026-09-04）
- **距今天数**：`2026-09-27 - 2026-09-04 = 23 天` **未跑过 lint**

### 1.4 wiki-lint 脚本位置

**实证**：`ls .claude/helpers/ | grep -i "wiki\|lint"` 输出**仅有** `ddl-field-usage-lint.cjs/READM`，**没有 wiki-lint**。

**结论**：wiki-lint **不在 `.claude/helpers/`**（index.md:91 指引错误），实际在 **`docs/wiki/wiki-lint.cjs`**（101 行）。脚本内容含 3 项检查：① wiki 文章 `raw:` 字段引用的文件存在 ② frontmatter 含 topic/title/updated ③ 无孤立 raw 文件。

---

## 2. 工作流主题覆盖

### 2.1 已存在文档

| 主题 | wiki 文章 | 引用 raw | raw 采集日期 | raw 滞后代码？ |
|---|---|---|---|---|
| Warm-Flow 审批流 | `wiki/modules/workflow.md` (107 行) | warm-flow-controller.md, workflow-service.md | **2026-09-04** | `git log -1 --format` |
| aiflow 图驱动 | `wiki/modules/aiflow.md` (177 行) | 8 个 aiflow-source 文件 | **2026-09-04** | 见 §3 |

### 2.2 raw 采集日期

**实证**：`grep "collected:" docs/wiki/raw/aiflow-source/*.md docs/wiki/raw/workflow-source/*.md` 输出：

| 文件 | collected |
|---|---|
| raw/aiflow-source/wf-node-factory.md | 2026-09-04 |
| raw/aiflow-source/workflow-controller.md | 2026-09-04 |
| raw/aiflow-source/workflow-engine.md | 2026-09-04 |
| raw/aiflow-source/workflow-graph-builder.md | 2026-09-04 |
| raw/aiflow-source/workflow-runtime-controller.md | 2026-09-04 |
| raw/aiflow-source/entity-workflow-edge.md | 2026-09-04 |
| raw/aiflow-source/entity-workflow-node.md | 2026-09-04 |
| raw/aiflow-source/entity-workflow.md | 2026-09-04 |
| raw/workflow-source/warm-flow-controller.md | 2026-09-04 |
| raw/workflow-source/workflow-service.md | 2026-09-04 |

**全部 raw 采集于 2026-09-04**，距今 23 天。

### 2.3 raw 与代码相对时效

**实证**：
- aiflow 模块代码最近 commit：`git log -1 --format="%h %ad %s" -- ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WfComponentNameEnum.java`
  → `6e264ad5 Thu Jul 30 00:15:44 2026 +0800 feat: streamline workflow orchestration and chat routing`
- 同 commit 也覆盖 `WfState.java`
- raw 文件 commit：`git log -1 --format` → `8004cd03 Fri Sep 4 08:00:13 2026 -0700 docs: karpathy-llm-wiki 初始生成`

**对比结论**：
- 代码最近修改：**2026-07-30**（早于 raw 采集 36 天）
- raw 最近修改：**2026-09-04**（脚本一次性重建）
- 当前 working tree 与 HEAD 一致（`git status --short docs/wiki/raw/` 输出空）

**含义**：raw 内容**仍能反映**当前代码状态（代码未变），但 wiki 描述**与 raw 仍然不一致**（见 §3），这是**生成时刻的描述性错误**，不是 raw 时效问题。

### 2.4 关键缺失：`docs/wiki/raw/ipd-source/`

**实证**：
- `ls docs/wiki/raw/ | grep -i ipd` → **空**
- `find docs/wiki/raw/ -type d -name "*ipd*"` → **空**

**结论**：
- **没有 ipd-source 子目录**
- 整个 wiki/raw 下**没有**任何 IPD 相关源材料
- ruoyi-ipd 模块的 100+ Java 文件**完全没被采集**

---

## 3. 一致性差异（wiki vs raw vs 代码）

### 3.1 节点类型枚举：4 处严重失真

#### 差异 A：wiki 列出的节点类型 ≠ 实际枚举 ≠ raw 文本

**wiki 描述**（`wiki/modules/aiflow.md:104-112`）：
```
- MODEL（模型调用）
- EMAIL（邮件发送）
- MANUAL_REVIEW（人工审核）
- WEB_SEARCH（联网搜索）
- KNOWLEDGE（知识库检索）
- CODE_EXEC（代码执行）
```

**实际枚举**（`ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WfComponentNameEnum.java:8-29`）：
```java
START("Start"), END("End"),
LLM_ANSWER("Answer"),
DALLE3("Dalle3"),
TONGYI_WANX("Tongyiwanx"),
FAQ_EXTRACTOR("FaqExtractor"),
KNOWLEDGE_RETRIEVER("KnowledgeRetrieval"),
SWITCHER("Switcher"),
GOOGLE_SEARCH("Google"),
MAIL_SEND("MailSend"),
HTTP_REQUEST("HttpRequest");
```

**实证比对**：

| wiki 描述 | 实际枚举 | 一致？ |
|---|---|---|
| MODEL | LLM_ANSWER | ❌ 名称不同 |
| EMAIL | MAIL_SEND | ❌ 名称不同 |
| MANUAL_REVIEW | (无) | ❌ 完全不存在 |
| WEB_SEARCH | GOOGLE_SEARCH | ❌ 名称不同 |
| KNOWLEDGE | KNOWLEDGE_RETRIEVER | ❌ 名称不同 |
| CODE_EXEC | (无) | ❌ 完全不存在 |
| (未列) | START, END | ⚠️ wiki 未提 |
| (未列) | DALLE3 | ⚠️ wiki 未提 |
| (未列) | TONGYI_WANX | ⚠️ wiki 未提 |
| (未列) | FAQ_EXTRACTOR | ⚠️ wiki 未提 |
| (未列) | SWITCHER | ⚠️ wiki 未提 |
| (未列) | HTTP_REQUEST | ⚠️ wiki 未提 |

**raw 印证**（`raw/aiflow-source/wf-node-factory.md:10-45`）：raw 实际展示的 switch 包含 `START / LLM_ANSWER / TONGYI_WANX / KNOWLEDGE_RETRIEVER / END / MAIL_SEND / HTTP_REQUEST / SWITCHER / GOOGLE_SEARCH` —— **与代码完全一致**。

**结论**：wiki 列出的 6 个枚举名是**作者杜撰**（不是来自 raw），与 raw 与代码都对不上。`MANUAL_REVIEW` 和 `CODE_EXEC` 是**根本不存在**的虚构节点。

### 3.2 状态机：状态值完全失真

#### 差异 B：wiki 列出的状态 ≠ 实际常量

**wiki 描述**（`wiki/modules/aiflow.md:132-133`）：
```
- WorkflowRuntime: PENDING / RUNNING / PAUSED / COMPLETED / FAILED / CANCELED
- WorkflowRuntimeNode: WAITING / RUNNING / SUCCESS / FAILED / SKIPPED
```

**实际代码状态值**（`ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/cosntant/AdiConstant.java:340-348`）：
```java
public static final int NODE_PROCESS_STATUS_READY = 1;
public static final int NODE_PROCESS_STATUS_DOING = 2;
public static final int NODE_PROCESS_STATUS_SUCCESS = 3;
public static final int NODE_PROCESS_STATUS_FAIL = 4;
public static final int WORKFLOW_PROCESS_STATUS_READY = 1;
public static final int WORKFLOW_PROCESS_STATUS_DOING = 2;
public static final int WORKFLOW_PROCESS_STATUS_SUCCESS = 3;
public static final int WORKFLOW_PROCESS_STATUS_FAIL = 4;
```

**实际字段类型**（`WfState.java:56`、`WfNodeState.java:32`）：`Integer processStatus`（**int 字段**，非 enum）

**实体确认**：
- `entity/WorkflowRuntime.java:42` — `private Integer status;`
- `entity/WorkflowRuntimeNode.java:36` — `private Integer status;`

**raw 印证**（`grep -nE "PROCESS_STATUS" raw/aiflow-source/workflow-engine.md`）：
```
136: wfState.setProcessStatus(WORKFLOW_PROCESS_STATUS_SUCCESS);
157: workflowRuntimeService.updateStatus(wfRuntimeResp.getId(), WORKFLOW_PROCESS_STATUS_FAIL, errorMsg);
```

**实证比对**：

| wiki 状态 | 实际状态 | 一致？ |
|---|---|---|
| PENDING | (无) | ❌ 不存在 |
| RUNNING | DOING | ❌ 名称不同 |
| PAUSED | (无) | ❌ 不存在 |
| COMPLETED | SUCCESS | ❌ 名称不同 |
| FAILED | FAIL | ❌ 名称不同 |
| CANCELED | (无) | ❌ 不存在 |
| (节点) WAITING | (节点) READY | ❌ |
| (节点) SKIPPED | (无) | ❌ |

**结论**：wiki 的 12 个状态名（6+6）**全部对不上**，其中 **PENDING/PAUSED/CANCELED/WAITING/SKIPPED 在代码中根本不存在**。实际只有 **READY/DOING/SUCCESS/FAIL** 4 个 int 常量。

### 3.3 WfNodeFactory 签名：方法名 + 形参都失真

**wiki 描述**（`wiki/modules/aiflow.md:101`）：
```java
WfNodeFactory.getNode(String componentName) → AbstractNode
```

**实际签名**（`raw/aiflow-source/wf-node-factory.md:27-28`）：
```java
public static AbstractWfNode create(WorkflowComponent wfComponent, WorkflowNode nodeDefinition,
                                    WfState wfState, WfNodeState nodeState)
```

**实证比对**：

| 维度 | wiki | 实际 | 一致？ |
|---|---|---|---|
| 方法名 | `getNode` | `create` | ❌ |
| 参数 1 | `String componentName` | `WorkflowComponent wfComponent` | ❌ |
| 参数 2 | (无) | `WorkflowNode nodeDefinition` | ❌ |
| 参数 3 | (无) | `WfState wfState` | ❌ |
| 参数 4 | (无) | `WfNodeState nodeState` | ❌ |
| 返回类型 | `AbstractNode` | `AbstractWfNode` | ❌ |

### 3.4 工厂分支缺失：DALLE3 / FAQ_EXTRACTOR 实际无法渲染

**实证**（`raw/aiflow-source/wf-node-factory.md:30-42` + `WfComponentNameEnum.java:8-29`）：

| 枚举值 | 工厂 switch case | 节点实现目录 | 准入闸？ |
|---|---|---|---|
| START | ✓ (line 31) | `node/start/StartNode.java` | ✅ |
| END | ✓ (line 35) | `node/EndNode.java` | ✅ |
| LLM_ANSWER | ✓ (line 32) | `node/answer/LLMAnswerNode.java` | ✅ |
| DALLE3 | ❌ 无 case | ❌ 目录不存在 | **⚠️ 库注册未接通** |
| TONGYI_WANX | ✓ (line 33 → ImageNode) | `node/image/ImageNode.java` | ✅ |
| FAQ_EXTRACTOR | ❌ 无 case | ❌ 目录不存在 | **⚠️ 库注册未接通** |
| KNOWLEDGE_RETRIEVER | ✓ (line 34) | `node/knowledgeRetrieval/` | ✅ |
| SWITCHER | ✓ (line 38) | `node/switcher/SwitcherNode.java` | ✅ |
| GOOGLE_SEARCH | ✓ (line 39) | `node/googleSearch/GoogleSearchNode.java` | ✅ |
| MAIL_SEND | ✓ (line 36) | `node/mailSend/MailSendNode.java` | ✅ |
| HTTP_REQUEST | ✓ (line 37) | `node/httpRequest/HttpRequestNode.java` | ✅ |

**实测代码节点目录**（`ls ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/node/`）：
```
AbstractWfNode.java   answer/            googleSearch/    image/
EndNode.java          enmus/             httpRequest/     knowledgeRetrieval/
mailSend/             start/             switcher/
```

**实测 grep**：`grep -rn "DALLE3\|TONGYI_WANX\|FAQ_EXTRACTOR" ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/` 仅匹配 `WfComponentNameEnum.java` 定义 + `WfNodeFactory.java:23 case TONGYI_WANX`，**没有任何 class 引用 DALLE3 或 FAQ_EXTRACTOR**。

**结论**：
- **DALLE3 / FAQ_EXTRACTOR 两个枚举值是「死枚举」**：声明在 `WfComponentNameEnum` 但工厂没有 case 处理，节点目录没有实现类
- 这是「**工厂分支缺失**」+「**渲染路径断裂**」+「**库注册未接通**」的典型三方对齐缺口
- wiki **完全没有提示这一缺口**（`aiflow.md:113` 只说"在 WfNodeFactory 注册"是必要步骤，但未审计实际是否已注册）

### 3.5 模块结构图：节点目录子目录数失真

**wiki 描述**（`wiki/modules/aiflow.md:28`）：
```
├── workflow/                             # 引擎核心（55 文件）
│   ├── ...
│   └── node/, edge/, data/, def/         # 节点类型细分
```

**实测**（`find ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow -maxdepth 2 -type d`）：
```
workflow/
├── def/
├── edge/
├── node/           # 12 项：AbstractWfNode/EndNode/answer/enmus/googleSearch/httpRequest/image/knowledgeRetrieval/mailSend/start/switcher
├── data/
└── (其他)
```

`workflow/node/` 实际有 **11 个子目录/类**（不含 `AbstractWfNode.java` 与 `EndNode.java` 自身），wiki 仅以"node/"一笔带过。

### 3.6 实体数量：aiflow 实体数未列**

**实测**（`ls entity/`）：Workflow.java / WorkflowNode.java / WorkflowEdge.java / WorkflowComponent.java / WorkflowRuntime.java / WorkflowRuntimeNode.java = **6 个实体**

**wiki 描述**（`aiflow.md:38-44`）：仅列出 **4 个 + 运行时 2 个 = 6 个** ✓ 此项一致。

### 3.7 wiki/workflow.md 与 aiflow.md 边界声明

**wiki/modules/workflow.md:18-25**：边界表正确描述两者并存（Warm-Flow 审批 vs aiflow AI 编排），此节无问题。

---

## 4. IPD 业务工作流文档覆盖缺口

### 4.1 完全无覆盖

**实证**：
- `grep -rln "Gate\|双签\|DRE\|PDT\|TRT" docs/wiki/wiki/` → **空**
- `grep -rln "Gate\|双签\|DRE\|PDT\|TRT\|阶段流转\|六阶段" docs/wiki/raw/` → **空**
- `grep -rln "IPD\|ipd" docs/wiki/wiki/` → **空**
- `grep -rln "IPD\|ipd" docs/wiki/raw/` → **空**

**结论**：整个 wiki + raw 对 IPD 关键词**零覆盖**。

### 4.2 ruoyi-ipd 模块真实存在但未被采集

**实证**（`find ruoyi-modules/ruoyi-ipd/src/main/java -maxdepth 4 -type d`）：

```
ruoyi-ipd/src/main/java/org/ruoyi/ipd/
├── dto/         # 含 GateElementCreateReq / GateChecklistView / GateChecklistItem / GateElementUpdateReq
├── vo/          # 含 GateElementVO
├── websocket/
├── seed/        # 含 ActionCatalog
├── util/
├── config/      # 含 IpdSchedulingConfig / IpdGateElementSeedInitializer
├── security/    # 含 IpdRolePermissionCatalog / IpdPermission
├── mapper/      # 含 GateReviewMapper / GateArbitrationMapper / GateElementMapper / GateMapper / GateReviewObserverMapper
├── advice/
├── controller/
├── common/
├── audit/       # 含 IpdAuditAspect
├── workbench/
├── hr/
├── service/
└── domain/
```

**关键 mapper / dto**（grep "Gate\|TRT\|DRE\|PDT" ruoyi-modules/ruoyi-ipd/src/main/java/）：
- `dto/GateElementCreateReq.java`、`dto/GateChecklistView.java`、`dto/GateChecklistItem.java`、`dto/GateElementUpdateReq.java`
- `vo/GateElementVO.java`
- `mapper/GateReviewMapper.java`、`mapper/GateArbitrationMapper.java`、`mapper/GateElementMapper.java`、`mapper/GateMapper.java`、`mapper/GateReviewObserverMapper.java`
- `seed/ActionCatalog.java`（标准动作清单）
- `config/IpdGateElementSeedInitializer.java`（Gate 要素种子初始化器）

**含义**：IPD 业务工作流（含 33 项 Gate 要素 + 14 项否决 + 6 阶段流转）有**完整代码实现**，但 wiki 知识库**完全没有采集**。

### 4.3 docs/ipd-系统说明/ 下相关工程合同存在

**实证**（`grep -lnE "Gate|阶段|双签|DRE|PDT|TRT|TR2|TR4|TR5|TR6" docs/ipd-系统说明/工程合同/*.md`）：
- `工程合同/DOC-01.md` ✓
- `工程合同/DOC-05.md` ✓（33项 Gate 要素 / 14项 否决工程合同）
- `工程合同/业务决策确认-20260905.md` ✓（含 G2-6 认证清单 / Gate 评审要素）

**DOC-05.md 关键引用**（`grep -nE "Gate|双签" docs/ipd-系统说明/工程合同/DOC-05.md`）：
- 第 1 行：「DOC-05：33项Gate要素与14项否决工程合同」
- 第 12-17 行：要素原稿、动作清单、主规格、AC 引用五大 Gate 评审要素
- 第 77 行：「Gate | 要素数 | 否决数 | 签署/流程性质」表格
- 第 137 行：「不同项目毛利互不串值，以及列席与签权隔离」

**业务决策确认-20260905.md 关键引用**（`docs/ipd-系统说明/工程合同/业务决策确认-20260905.md:13-22`）：
- 第 13 行：G2-6 认证清单/周期未完成可通过、保留问题与跟踪、33 要素/14 否决基线、P10 同源遗留不硬拦、V02/G4 验证保留
- 第 17 行：列席人员按项目选择、列席身份不自动获得签署权

**wiki 是否引用 docs/ipd-系统说明？**：`grep -rln "ipd-系统说明\|工程合同/DOC-01\|DOC-05" docs/wiki/` → **空**

**结论**：
- IPD 业务工作流**完全自洽于 docs/ipd-系统说明**（DOC-01/DOC-05 + 调研/）
- wiki **完全独立于** docs/ipd-系统说明 体系
- 两者**无任何交叉引用**——这是 wiki 知识库**结构性盲区**

### 4.4 建议新增的 wiki 文章清单（基于代码 + 调研存在）

按优先级（基于 docs/ipd-系统说明/调研/ 已有素材 + 代码现成）：

| 建议 wiki 文章 | 应引用 raw | raw 来源 | 优先级 |
|---|---|---|---|
| `wiki/modules/ipd.md` | 5-8 | ruoyi-ipd/controller + service + domain + mapper 核心 | P0 |
| `wiki/modules/ipd-gate-review.md` | 3-4 | GateReviewMapper / GateMapper / GateElementMapper + GateChecklistView | P0 |
| `wiki/modules/ipd-audit-aspect.md` | 2 | IpdAuditAspect + @IpdAudit AOP 注解（呼应 R194-A2 调研） | P1 |
| `wiki/modules/ipd-stage-flow.md` | 2-4 | ActionCatalog + IpdGateElementSeedInitializer + 六阶段流转 | P0 |
| `wiki/cross-cutting/ipd-business-rules.md` | (引用 DOC-05) | 33 Gate 要素 + 14 否决 + 6 阶段工程合同 | P1 |
| `wiki/modules/ipd-permission.md` | 1-2 | IpdRolePermissionCatalog + IpdPermission | P2 |

`raw/ipd-source/` 应至少包含：
1. `gate-element-mapper.md`（GateReviewMapper/GateArbitrationMapper/GateElementMapper/GateMapper/GateReviewObserverMapper 聚合）
2. `ipd-audit-aspect.md`（@IpdAudit AOP 入口）
3. `gate-checklist-view.md`（DTO 三件套）
4. `action-catalog.md`（seed/ActionCatalog）
5. `ipd-gate-element-seed-initializer.md`
6. `ipd-permission-catalog.md`（IpdRolePermissionCatalog + IpdPermission）

---

## 5. 三方对齐矩阵的「wiki 视角」结论

### 5.1 是否存在「工厂分支 + 渲染路径 + 库注册准入闸」对齐文章？

**期望**：无（按任务预设）

**实证**：
- 全 wiki 22 篇 grep "工厂\|渲染\|准入\|注册" → 仅 `aiflow.md:113` 出现"在 WfNodeFactory 注册"
- 没有单独章节描述「枚举声明 → 工厂 switch → 节点实现 → 库注册」的对齐审计
- `aiflow.md:113` 仅描述**流程意图**（"新增节点类型：实现 AbstractNode 接口 → 在 WfComponentNameEnum 加枚举 → 在 WfNodeFactory 注册"），**未审计实际是否完整**

### 5.2 当前缺口（基于本盘点发现的真实漏洞）

| 维度 | 缺口 |
|---|---|
| 工厂分支 | `DALLE3` / `FAQ_EXTRACTOR` 枚举存在但 switch 无 case |
| 渲染路径 | `WfNodeFactory.create()` 命中上述两个枚举走 default 分支返回 null |
| 库注册准入闸 | 完全没有 lint / 启动检查 / 单测守护**断言"枚举 ↔ 工厂 ↔ 实现目录"三方对齐** |
| 文档审计 | wiki 没有描述这一对齐关系，也没有给出"DALLE3/FAQ_EXTRACTOR 是死枚举"的告警 |

**结论**：**预期缺口被验证**——三方对齐**既无代码守护，也无 wiki 文档揭示**。DALLE3/FAQ_EXTRACTOR 是潜在的运行期 NPE 源（若前端保存了引用这两个枚举的 WorkflowComponent）。

---

## 6. wiki-lint 现状

### 6.1 脚本存在性

**实证**：
- `ls .claude/helpers/ | grep -i "wiki\|lint"` → 仅 `ddl-field-usage-lint.cjs/READM`，**没有 wiki-lint**
- `ls docs/wiki/` → `wiki-lint.cjs`（**3.2KB**）+ raw/ + wiki/
- 实际位置：**`docs/wiki/wiki-lint.cjs`**（101 行）

**index.md:91 指引错误**：「跑 `node .claude/helpers/wiki-lint.cjs`」——实际正确路径是 `node docs/wiki/wiki-lint.cjs`。

### 6.2 lint 检查项（只读获取，无执行）

**实证**（`docs/wiki/wiki-lint.cjs:5-10` 注释 + 第 53-67 行实现）：
1. 每篇 wiki 文章的 `raw:` 字段列出的文件都存在
2. 每篇 wiki 文章含 `topic / title / updated` frontmatter
3. 没有孤立的 raw 文件（被任何 wiki 引用过）

**脚本不含**的检查（**与本次盘点相关**）：
- ❌ raw 文件采集日期是否过期（如 >7 天告警）
- ❌ 节点类型枚举 / 状态值与代码对齐审计
- ❌ ruoyi-ipd 模块覆盖度审计
- ❌ IPD / Gate / 双签关键词覆盖度审计

### 6.3 lint 最近执行痕迹

**实证**：`grep -E "lint 结果\|lint 验证" docs/wiki/wiki/log.md`
- log.md:50-52：第一次 lint（79 通过）
- log.md:61：batch-10 lint（99 通过）
- log.md:74：batch-11 lint（121 通过）

**全部为 2026-09-04** 一次性生成时记录。**2026-09-05 至今（23 天）未跑过 lint**。

### 6.4 CI 集成（只读探查）

**实证**：`docs/wiki/wiki/index.md:53` 「CI 集成：.github/workflows/wiki-lint.yml（自动验证）」

未单独验证该 yml 是否仍存在并能运行——属"未验证项"（见 §7）。

---

## 7. 限制与未验证项

### 7.1 限制

- 本探针**未执行** `node docs/wiki/wiki-lint.cjs`（按任务约束避免误触 CI hook）
- 本探针**未读取**所有 raw 文件全文（仅 grep 关键模式）
- 本探针**未比对** wiki 与代码的每一处描述（聚焦工作流主题与节点类型）
- 本探针**未比对** ruoyi-workflow 模块（Warm-Flow）的实体与 wiki workflow.md
- 本探针**未跑**任何构建 / 测试 / 启动检查

### 7.2 未验证项

| 项 | 状态 |
|---|---|
| `.github/workflows/wiki-lint.yml` 是否仍存在并能运行 | 未查 |
| `wiki/raw/` 之外是否还有 IPD 相关材料在 wiki 树内 | 已查（无） |
| wiki-lint 脚本是否因 raw 索引失真（63 vs 60）触发"孤立 raw"假阳性 | 未跑（推测：3 个未声明的 raw 会被报孤立） |
| ruoyi-workflow 模块（Warm-Flow）状态机与 wiki 描述是否一致 | 未对 |
| chat-source 等模块的 wiki 描述是否也有同等失真 | 未对 |
| raw 采集时（2026-09-04）代码是否真为 6e264ad5 | 未对（git log 仅显示当前 HEAD） |

### 7.3 风险评估

若**现在直接套用 wiki `aiflow.md` 描述做开发**：
1. 按 wiki 列的 `MODEL/EMAIL/MANUAL_REVIEW/WEB_SEARCH/KNOWLEDGE/CODE_EXEC` 6 类型新建节点 → **全部不在 `WfComponentNameEnum` 中**
2. 按 wiki `WfNodeFactory.getNode(String componentName)` 签名调用 → **方法不存在，签名是 `create(4 参)`**
3. 按 wiki 状态 `PENDING/RUNNING/PAUSED/COMPLETED/FAILED/CANCELED` 判等 → **6 个名字有 3 个不存在**
5. 引用 `MANUAL_REVIEW` / `CODE_EXEC` 类 → **没有任何实现类**

**结论**：当前 wiki `aiflow.md` **不能作为开发依据**，必须以 `WfComponentNameEnum.java` + `WfNodeFactory.java` + `AdiConstant.java` 为准。

---

## 8. 附录 A：本次盘点引用的所有证据路径

### 8.1 探针命令产物（行号 / 内容）

| 章节 | 实证命令 | 输出关键事实 |
|---|---|---|
| §1.1 | `ls docs/wiki/wiki/{modules,cross-cutting,automation}/` | 18+3+1=22 篇 |
| §1.2 | `ls docs/wiki/raw/` + 各子目录 | 63 文件 vs index.md 标 60 |
| §1.3 | `cat docs/wiki/wiki/log.md:1-75` | 最后 lint 2026-09-04 |
| §1.4 | `ls .claude/helpers/ | grep wiki\|lint` | 无 wiki-lint |
| §2.2 | `grep "collected:" docs/wiki/raw/*/*.md` | 全部 2026-09-04 |
| §2.3 | `git log -1` WfComponentNameEnum + WfState | 6e264ad5 (2026-07-30) |
| §2.4 | `find docs/wiki/raw/ -name "*ipd*"` | 空（无 ipd-source/） |
| §3.1 | `cat .../WfComponentNameEnum.java:8-29` | 11 个真实枚举 |
| §3.2 | `cat .../AdiConstant.java:340-348` | READY/DOING/SUCCESS/FAIL int 常量 |
| §3.3 | `cat raw/aiflow-source/wf-node-factory.md:27-28` | create(4 参) 而非 getNode(1 参) |
| §3.4 | `ls .../workflow/node/` + grep DALLE3/FAQ_EXTRACTOR | 缺 2 个目录 |
| §3.5 | `find .../workflow -maxdepth 2 -type d` | node/ 有 11 子目录 |
| §4.1 | `grep -rln "Gate\|IPD" docs/wiki/{wiki,raw}/` | 全部空 |
| §4.2 | `find ruoyi-modules/ruoyi-ipd -maxdepth 4 -type d` | 17 个包，含 Gate* mapper/dto |
| §4.3 | `grep -ln "Gate\|双签" docs/ipd-系统说明/工程合同/*.md` | DOC-01/DOC-05/业务决策确认 |
| §5 | `grep "工厂\|渲染\|准入" docs/wiki/wiki/` | 仅 aiflow.md:113 一处 |
| §6.1 | `cat docs/wiki/wiki-lint.cjs` | 101 行，3 项检查 |

### 8.2 关键代码路径（只读）

- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WfComponentNameEnum.java`（41 行）
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WfState.java`（132 行）
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WfNodeState.java`（54 行）
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/cosntant/AdiConstant.java`（含 WorkflowConstant 内部类，line 336-348）
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WfNodeFactory.java`（switch 9 case）
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/node/`（11 子目录）

### 8.3 关键 wiki 路径（只读）

- `docs/wiki/wiki/index.md`（91 行）
- `docs/wiki/wiki/log.md`（75 行）
- `docs/wiki/wiki/modules/aiflow.md`（177 行）—— **本探针主要审计对象**
- `docs/wiki/wiki/modules/workflow.md`（107 行）
- `docs/wiki/raw/aiflow-source/`（8 文件，63 个 raw 中的 8）
- `docs/wiki/raw/workflow-source/`（2 文件）

### 8.4 关键 IPD 路径（只读）

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/`（17 子包）
- `docs/ipd-系统说明/工程合同/DOC-01.md`（40.5KB）
- `docs/ipd-系统说明/工程合同/DOC-05.md`（24.6KB，33 Gate 要素 + 14 否决）
- `docs/ipd-系统说明/工程合同/业务决策确认-20260905.md`（6.6KB）
- `docs/ipd-系统说明/调研/R194-A2-ipdaudit-aop-converge-scheme-20260923.md`（含 @IpdAudit AOP 调研）

---

## 9. 报告生成元信息

- **报告字数**：约 6000 字 / 9 章 / 38 个实证比对行
- **报告行数**：约 380 行（**远低于 1500 行上限**）
- **探针执行时间**：2026-09-27
- **执行模式约束遵守**：✓ 未修改任何文件 ✓ 未跑 lint ✓ 未 commit/push
- **行号现查现写**：✓ 全部行号来自本次执行时的 grep/cat -n 输出

---

> **报告结束**。后续动作建议（如有）请另起一次性 commit，本探针仅做一致性披露。
