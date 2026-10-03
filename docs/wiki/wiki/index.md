# Knowledge Base Index

RuoYi-AI 项目知识库索引。基于 karpathy-llm-wiki 工作流生成：源材料在 `raw/`，编译后的结构化文章在 `wiki/` 下。

## 文章清单（按主题分组）

### modules/ — 各功能模块详解

| 文章 | 主题 | Updated |
|---|---|---|
| [modules/admin.md](modules/admin.md) | ruoyi-admin — Spring Boot 主应用入口 | 2026-09-04 |
| [modules/admin-source.md](modules/admin-source.md) | ruoyi-admin 源码层（启动 / Controller / Config） | 2026-09-04 |
| [modules/chat.md](modules/chat.md) | ruoyi-chat — AI 核心模块（AgentScope 内核 + AiGateway） | 2026-10-02 |
| [modules/chat-multimodal.md](modules/chat-multimodal.md) | ruoyi-chat — 多模态（视频 / 音频 / 图像 / Embedding） | 2026-09-04 |
| [modules/ipd-workflow.md](modules/ipd-workflow.md) | ruoyi-ipd — IPD 业务工作流（六阶段 / Gate 评审 / 阶段动作） | 2026-09-27 |
| [modules/ipd-node-agents.md](modules/ipd-node-agents.md) | ruoyi-ipd — 生命周期节点智能体（69 码执行栈 / 6 执行器 / 信任边界） | 2026-09-27 |
| [modules/system.md](modules/system.md) | ruoyi-system — RBAC 与系统管理 | 2026-09-04 |
| [modules/system-rbac-deep-dive.md](modules/system-rbac-deep-dive.md) | ruoyi-system — RBAC 实体关系 + 权限注解 + 数据权限 | 2026-09-04 |
| [modules/system-listener-runner.md](modules/system-listener-runner.md) | ruoyi-system — 事件监听器与启动任务 | 2026-09-04 |
| [modules/generator.md](modules/generator.md) | ruoyi-generator — 代码生成器 | 2026-09-04 |
| [modules/common.md](modules/common.md) | ruoyi-common — 24 个共享库（+1 BOM） | 2026-09-04 |
| [modules/common-core-utilities.md](modules/common-core-utilities.md) | common — 核心工具层（core / json / doc / excel） | 2026-09-04 |
| [modules/common-security-auth.md](modules/common-security-auth.md) | common — 安全认证层（security / satoken / encrypt / sensitive） | 2026-09-04 |
| [modules/common-data.md](modules/common-data.md) | common — 数据层（mybatis / redis / tenant / trace） | 2026-09-04 |
| [modules/common-communication.md](modules/common-communication.md) | common — 通信层（web / sse / websocket / oss / mail / sms / social） | 2026-09-04 |
| [modules/common-business.md](modules/common-business.md) | common — 业务能力层（log / job / ratelimiter / idempotent / translation） | 2026-09-04 |

### cross-cutting/ — 跨模块主题

| 文章 | 主题 | Updated |
|---|---|---|
| [cross-cutting/architecture-overview.md](cross-cutting/architecture-overview.md) | 系统架构总览 + 模块拓扑 + AI 内核 | 2026-10-02 |
| [cross-cutting/multi-tenant-design.md](cross-cutting/multi-tenant-design.md) | 多租户隔离设计（机制 / 边界 / checklist） | 2026-09-04 |
| [cross-cutting/deployment-guide.md](cross-cutting/deployment-guide.md) | 部署指南（3 种方式 + 配套服务 + 故障排查） | 2026-09-04 |

### automation/ — Claude Code 自动化栈

| 文章 | 主题 | Updated |
|---|---|---|
| [automation/claude-code-setup.md](automation/claude-code-setup.md) | Claude Code 自动化栈使用手册 | 2026-09-04 |

## 统计

- **raw 文件数**：48
- **快照漂移（2026-10-03 实测）**：48 份中与活文件一致 29 / 已漂移 16 / 源文件已删 1（`system-source/entity-cms-content.md`，对应实体已下线）/ 指向目录无法字节比对 2（`chat-source/agents-catalog.md`、`system-source/rbac-entities.md`）。引用 raw 快照结论前先核对活文件，漂移快照里的数字不可直接采信
- **wiki 文章数**：20
- **raw 总大小**：~430 KB
- **wiki 总大小**：~210 KB
- **覆盖模块**：admin / chat / system / generator / common / extend / ipd
- **覆盖主题**：架构 / 多租户 / 部署 / 自动化
- **git 跟踪**：commit 8004cd03（+ 新增待提交）
- **CI 集成**：.github/workflows/wiki-lint.yml（自动验证）

## 主题目录（raw 源材料）

| 目录 | 文件数 | 主题 |
|---|---|---|
| `raw/project-skeleton/` | 2 | 项目骨架（application-dev/prod yml） |
| `raw/admin-source/` | 7 | ruoyi-admin 源码（启动 / controller / config / logback） |
| `raw/chat-source/` | 7 | ruoyi-chat 核心（controller / factory / RAG trace / vector store） |
| `raw/system-source/` | 9 | ruoyi-system 核心（controller / listener / runner / entity） |
| `raw/ipd-source/` | 3 | ruoyi-ipd 业务工作流（Gate 评审 / 阶段动作 / AI 执行引擎与节点智能体） |
| `raw/multimodal-source/` | 4 | 多模态（视频 / 音频 / 图像 / Embedding） |
| `raw/generator-source/` | 2 | ruoyi-generator 核心（controller / service） |
| `raw/common-source/` | 9 | ruoyi-common 关键库（security / mybatis / web / satoken / chat） |
| `raw/extend-source/` | 2 | ruoyi-extend（monitor-admin / snailjob-server） |
| `raw/docker-source/` | 3 | docker-compose 配置 + Dockerfile |

## 操作日志

见 [log.md](log.md)。

## 使用说明

每篇文章顶部有 YAML frontmatter：

```yaml
topic: modules/chat
title: ruoyi-chat — AI 核心模块
raw:
  - raw/chat-source/chat-controller.md
  - raw/chat-source/vector-store-properties.md
  ...
```

`raw:` 字段列出该文章引用的事实来源。所有数据点都能追溯到 `raw/` 里的 verbatim 原文。

**新增文章**：参考 `.agents/skills/karpathy-llm-wiki/SKILL.md` 的 article-template。

**验证完整性**：跑 `node .claude/helpers/wiki-lint.cjs`（已写入 wiki 根目录）。