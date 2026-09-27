---
topic: modules/workflow
title: ruoyi-workflow — Warm-Flow BPMN 引擎
updated: 2026-09-04
raw:
  - raw/workflow-source/warm-flow-controller.md
  - raw/workflow-source/workflow-service.md
---

# ruoyi-workflow — Warm-Flow BPMN 引擎

`ruoyi-workflow` 是基于 **Warm-Flow**（国产 BPMN 引擎）的传统审批工作流模块。**78 个 Java 文件**，跟 `ruoyi-aiflow` 的图驱动 AI 工作流不同——这是面向审批场景的 BPMN 2.0 流程引擎。

参见：[claude-md.md § Module Layout — workflow / aiflow](../raw/project-skeleton/claude-md.md)、[claude-md.md § workflow / generator](../raw/project-skeleton/claude-md.md)。

## 与 aiflow 的边界

| 维度 | ruoyi-workflow | ruoyi-aiflow |
|---|---|---|
| 引擎 | Warm-Flow（第三方） | 自研图驱动 |
| 适用 | 传统审批（请假 / 报销 / 多级签批） | AI 处理管道（模型调用 → RAG → 邮件） |
| 节点 | BPMN 元素（userTask / serviceTask / gateway） | 自定义节点（model / email / web_search） |
| 状态 | BPMN 标准状态机 | 自定义 WfState / WfNodeState |

**两者并存不冲突**：业务审批用 Warm-Flow，AI 编排用 aiflow。

## 关键 Controller

```
FlwDefinitionController       流程定义 CRUD（部署 BPMN XML）
FlwInstanceController         流程实例（启动、暂停、终止）
FlwTaskController             用户任务（签收、完成、转办、委派）
FlwCategoryController         流程分类
FlwSpelController             SPEL 表达式（流程条件）
TestLeaveController           示例：请假流程
```

参见：[warm-flow-controller](../raw/workflow-source/warm-flow-controller)。

## 关键 Service

| Service | 作用 |
|---|---|
| `FlwDefinitionService` | 流程定义（部署 BPMN XML 到数据库） |
| `FlwInstanceService` | 流程实例（启动、暂停、终止） |
| `FlwTaskService` | 用户任务（审批相关） |
| `FlwSpelService` | SPEL 表达式求值（用于流程条件） |

参见：[workflow-service](../raw/workflow-source/workflow-service)。

## Warm-Flow 集成

Warm-Flow 依赖（参见 [pom-xml.md § warm-flow](../raw/project-skeleton/pom-xml.md)）：

```xml
<dependency>
    <groupId>org.dromara.warm</groupId>
    <artifactId>warm-flow-mybatis-plus-sb3-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.dromara.warm</groupId>
    <artifactId>warm-flow-plugin-ui-sb-web</artifactId>
</dependency>
```

配置（参见 [application-yml.md § warm-flow](../raw/project-skeleton/application-yml.md)）：

```yaml
warm-flow:
  enabled: true            # 总开关
  ui: true                 # 设计器 UI
  top-text-show: true      # 流程图顶部文字
  node-tooltip: true       # 节点悬浮提示
  token-name: Authorization,clientid  # token 名称（Sa-Token + 客户端）
```

参见：[application-yml.md § warm-flow](../raw/project-skeleton/application-yml.md)。

## 流程设计器

`warm-flow-plugin-ui-sb-web` 提供 Web 端流程设计器 UI（拖拽节点 → 画 BPMN → 保存 XML）。

## 包结构

```
org.ruoyi.workflow/
├── controller/             # 6 个（Flw* + TestLeave）
├── service/                # 业务 service
├── domain/                 # 实体（BPMN 相关）
├── mapper/                 # MyBatis-Plus mapper
├── handler/                # 自定义 BPMN 节点处理器
├── listener/               # 流程事件监听（启动 / 完成 / 异常）
├── rule/                   # 流程规则（条件、网关判断）
├── config/                 # Warm-Flow 配置
└── common/                 # 公共
```

## 接入业务系统

传统审批流接入示例：

1. 业务模块（`ruoyi-system` 的请假 service）注入 `FlwInstanceService`
2. 调用 `startProcess(businessKey, variables)` 启动流程
3. 监听 `FlwProcessEvent`（listener 模块）处理业务状态变更
4. 在 BPMN XML 里画 userTask / serviceTask 节点

**注意**：BPMN 流程定义（XML）存在 `flw_definition` 表，由 Warm-Flow 管理；不要自己改表结构。

---

## AI 工作流（ruoyi-aiflow）死枚举与历史损坏登记

> 本节归属模块：`ruoyi-aiflow`（自研图驱动 AI 编排）。以下问题均出自该模块；因 wiki 文档按用户原指令统一收纳在 `workflow.md`，实际排查请定位到 `ruoyi-aiflow` 代码路径。

### 死枚举清单

枚举声明在 `WfComponentNameEnum`，但 `WfNodeFactory` switch 分支、`t_workflow_component` 库行、前端 `NodeShell` name-switch 三方均无对应实现的项，归类为「死枚举」。

| name | enum 声明 | factory switch | 库行 is_enable=1 AND is_deleted=0 | 前端 NodeShell | 状态 |
|---|---|---|---|---|---|
| DALLE3 | 是 | 否（default → null） | 否（2026-09-27 fresh 直读 ipd_dev） | 否（forwarding 壳） | 🔴 死枚举 |
| FAQ_EXTRACTOR | 是 | 否（default → null） | 否（2026-09-27 fresh 直读 ipd_dev） | 否（forwarding 壳） | 🔴 死枚举 |

证据：fresh 直读 MySQL 13306/ipd_dev（cnf `.codex/ipd-dev/config/mysql-client.cnf` + socket `.codex/ipd-dev/run/mysql.sock`）：

```
SELECT name, uuid, is_enable, is_deleted FROM t_workflow_component
  WHERE name IN ('DALLE3','FAQ_EXTRACTOR') ORDER BY name;
-- 结果 0 行
```

全表 9 行 / 启用 9 / 软删 0；启用清单：Start / End / Answer / Switcher / Tongyiwanx / MailSend / KnowledgeRetrieval / HttpRequest / Google。三方对齐无缺口。

清理建议（owner 拍板后动）：

1. 枚举侧：从 `WfComponentNameEnum` 删除 `DALLE3` / `FAQ_EXTRACTOR` 两条。
2. 库侧：保留空（已是现状，无写入副作用）。
3. 前端：保留空（已是现状，无渲染副作用）。

### GBK 乱码史

`WorkflowEngine.java`（`ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowEngine.java`）历史上残留多处 UTF-8/GBK 互转损坏（典型形态：UTF-8 文件被以 GBK 解读后再次以 UTF-8 保存，产生 `e5 88 86 ef bf bd 3f` 这种 `分<U+FFFD>?` 字节序列）。

| 修复日期 | 位置 | 损坏形态 | 修复结果 |
|---|---|---|---|
| 2026-09-27 | L141 | `errorMsg = "并行节点中不能包含条件分<U+FFFD>?";` | `errorMsg = "并行节点中不能包含条件分支";` |
| 2026-09-27 | L188 | `//并行节点...发送输出结<U+FFBD>?` | 注释上提至 L174 并修正为 `输出结果`；同步清理 13 行注释死代码（原 L164-176） |
| 2026-09-27 | L190 | `//langgraph4j state...只存储元数<U+FFBD>?` | `//...只存储元数据` |

未清理的残留（按"最小变更"原则保留待后续清理）：

| 位置 | 损坏形态 | 处置 |
|---|---|---|
| L239 | `* @param startNode  开始节点定<U+FFBD>?` | 不动；仅 javadoc，不影响运行时 |
| L240 | `* @return 正确的用户输入列<U+FFBD>?` | 不动；仅 javadoc，不影响运行时 |

教训：写入 UTF-8 中文注释时，若 IDE / 终端编码未锁 UTF-8，会触发 GBK ↔ UTF-8 互转损坏。建议 `.editorconfig` + IDE 编码设置固定为 UTF-8。

参见：[aiflow.md](../raw/aiflow-source/workflow-engine.md)（同模块入口）。