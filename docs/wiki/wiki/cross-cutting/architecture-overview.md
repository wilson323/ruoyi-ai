---
topic: cross-cutting/architecture-overview
title: 系统架构总览
updated: 2026-09-04
raw:
  - raw/admin-source/ruoyi-ai-application.md
  - raw/chat-source/chat-controller.md
  - raw/system-source/sys-user-controller.md
---

# 系统架构总览

RuoYi-AI 是一个**企业级 AI 助手平台**，后端基于 Spring Boot 3.5.8 + Java 17 + AgentScope（项目智能体内核），前端分离（独立仓库）。（2026-10-02：LangChain4j/Langgraph4j 已全量替换为 AgentScope；ruoyi-aiflow / ruoyi-workflow 两模块已下线，IPD 领域状态机为唯一编排事实源）

## 顶层架构

```
┌────────────────────────────────────────────────────────┐
│                      用户 / 浏览器                       │
│      ┌────────────────┐  ┌────────────────┐              │
│      │  ruoyi-web     │  │  ruoyi-admin   │              │
│      │  用户端 (Vue3) │  │ 管理后台 (Vue3) │              │
│      └────────┬───────┘  └────────┬───────┘              │
└───────────────┼──────────────────┼─────────────────────┘
                │ HTTPS / SSE / WebSocket
                ▼
┌────────────────────────────────────────────────────────┐
│           Spring Boot 3.5.8 (ruoyi-admin, port 6039)    │
│  ┌──────────────────────────────────────────────────┐  │
│  │  ruoyi-modules/                                   │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────┐ │  │
│  │  │ ruoyi-chat   │ │ ruoyi-ipd    │ │ ruoyi-   │ │  │
│  │  │  AgentScope  │ │ IPD 业务核心 │ │ system   │ │  │
│  │  │  AI 核心     │ │ 69动作/Gate │ │ RBAC     │ │  │
│  │  └──────────────┘ └──────────────┘ └──────────┘ │  │
│  │  ┌──────────────┐                                │  │
│  │  │ ruoyi-       │                                │  │
│  │  │ generator    │                                │  │
│  │  │ 代码生成     │                                │  │
│  │  └──────────────┘                                │  │
│  └──────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────┐  │
│  │  ruoyi-common/ (27 子模块)                       │  │
│  │  共享：security/mybatis/web/chat/redis/sse/...   │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
                │ JDBC / Redis / HTTP / gRPC
                ▼
┌────────────────────────────────────────────────────────┐
│  数据 / 中间件                                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │ MySQL    │ │ Redis    │ │ 向量库   │ │ MinIO    │  │
│  │ 13306    │ │ 26379    │ │ (任选一) │ │ 29000    │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
└────────────────────────────────────────────────────────┘

外部 AI 模型（通过 application.yml 模型管理配置）：
- DeepSeek / Zhipu / OpenAI / MIMO / Bailian
- Dify / Coze / FastGPT / RAGFlow 平台集成
```

参见：[modules/admin.md](../modules/admin.md)、[modules/chat.md](../modules/chat.md)。

## 模块拓扑

```
                 ┌─────────────────────┐
                 │   ruoyi-admin       │
                 │   Spring Boot 主    │
                 └──────────┬──────────┘
                            │ 依赖（jar）
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ ruoyi-system │    │  ruoyi-chat  │    │  ruoyi-ipd   │
│   RBAC       │    │  AI 核心     │    │  IPD 业务    │
└──────────────┘    └──────────────┘    └──────────────┘
                            │
                ┌───────────┴───────────┐
                ▼                       ▼
        ┌──────────────┐        ┌──────────────┐
        │  ruoyi-      │        │  ruoyi-      │
        │  generator   │        │  common/*    │
        │  代码生成    │        │  27 共享库   │
        └──────────────┘        └──────────────┘
```

**关键点**：`ruoyi-extend/`（monitor-admin + snailjob-server）是**独立 Spring Boot 应用**，**不**被 admin 依赖——它们独立部署。

参见：[modules/admin.md](../modules/admin.md)。

## AI 编排

原项目曾并存两套工作流引擎（`ruoyi-workflow` Warm-Flow BPMN / `ruoyi-aiflow` 自研图驱动），**均已于 2026-10-02 整模块下线**（零消费查证后退役，下线脚本见 `docs/script/sql/update/2026-10-02-workflow-modules-offline.sql`）。当前唯一编排事实源是 `ruoyi-ipd` 的自研领域状态机（六阶段 + 5 Gate + 69 标准动作），详见 [modules/ipd-workflow.md](../modules/ipd-workflow.md)。

## AI 核心技术栈

- **AgentScope**：`2.0.3`（项目智能体内核，HarnessAgent 装配面）
- **向量库**：Weaviate（默认）/ Milvus / Qdrant，可通过 `vector-store.type` 切换
- **多模型**：通过「模型管理」后台配置，支持 DeepSeek / Zhipu / OpenAI 等
- **MCP 协议**：自定义 MCP 服务接入（modelcontextprotocol SDK + SSE 传输）

参见：[modules/chat.md](../modules/chat.md)。

## 通信协议

- **HTTP / HTTPS**：REST API（Spring MVC）
- **SSE**：默认流式响应通道（`/resource/sse`），适合 LLM streaming 输出
- **WebSocket**：可选（默认关），用于需要双向通信场景
- **MySQL TCP**：数据库连接（HikariCP 连接池）

## 数据持久层

- **MyBatis-Plus** + 多租户拦截器 + 数据权限拦截器 + 分页插件
- **Redis**：缓存、分布式锁（Redisson）
- **向量库**：Weaviate / Milvus / Qdrant（RAG 与 embedding）
- **MinIO**：对象存储（OSS / 头像 / 文件上传）

参见：[modules/common.md § mybatis / redis / oss](../modules/common.md)。

## 安全 / 鉴权

- **Sa-Token + JWT** 双层认证
- **API RSA 加解密**（可选，`api-decrypt.enabled`）
- **多租户过滤**（自动）
- **数据权限范围**（`@SaCheckDataScope`）
- **XSS 过滤**（全局）

详见 [cross-cutting/multi-tenant-design.md](../cross-cutting/multi-tenant-design.md)。