---
topic: modules/chat
title: ruoyi-chat — AI 核心模块（AgentScope 内核 + AiGateway）
updated: 2026-10-02
raw:
  - raw/chat-source/chat-controller.md
  - raw/chat-source/chat-service-factory.md
  - raw/chat-source/rag-trace-node-types.md
  - raw/chat-source/rag-trace-payload-builder.md
  - raw/chat-source/vector-store-properties.md
  - raw/chat-source/mcp-sse-config.md
---

# ruoyi-chat — AI 核心模块

`ruoyi-chat` 是 RuoYi-AI 的**核心 AI 模块**，承担：

- 副驾咨询与文档生成（`AiCopilotService#chatStream` → `AiGateway#stream`，SSE 流式）
- IPD 项目智能体内核（AgentScope Java，接入层在 `ruoyi-ipd` 的 `ProjectAgentController` 单轨）
- 多模型适配（DeepSeek / Zhipu / OpenAI / MIMO / Bailian）
- RAG 与向量库桥接
- 聊天会话与消息持久化
- 多模态（文本 / 图片 / 视频生成）

> 2026-10-02：LangChain4j/Langgraph4j 已全量替换为 AgentScope（含 21 个 `@Agent` 接口、`LangChain4jMcpToolProviderService`、内置 MCP 工具 6 件套的物理删除）；原 Supervisor 多 agent 编排已随内核替换退役。

模块结构：

> 2026-10-07 勘误：本模块的 Java 代码**不集中在 `org.ruoyi.chat/` 下**。除 `org.ruoyi.chat.*` 外，其余包与 `chat/` 平级，都直接挂在 `org.ruoyi/` 之下（`ChatController` 的真实路径是 `org/ruoyi/controller/chat/ChatController.java`，不在 `org.ruoyi/chat/` 下）。下面的树已按实测目录结构改写。

```
org/ruoyi/                        # 模块源码根，共 15 个顶级包
├── chat/                        # 仅 3 个子包：kernel（22 个 java）/ poc（2）/ service（0）
│   ├── kernel/                  # AgentScope 内核实现
│   ├── poc/                     # 概念验证
│   └── service/                 # 当前无 java 文件
├── controller/                  # REST 层，共 6 个子包
│   ├── chat/        # 主聊天 controller（ChatController / ChatSessionController / ChatMessageController 等 7 个）
│   ├── agent/       # Agent 注册 controller（AgentController）
│   ├── mcp/         # MCP 工具市场 controller（McpMarketController / McpToolController）
│   ├── coding/      # 编码工作区
│   ├── knowledge/   # 知识库
│   └── shortdrama/  # 短剧
├── service/         # 150 个 .java（按 agent/chat/embed/video/vector 等 14 个子包划分；含接口 29 + *ServiceImpl 37 + Facade 等）
├── factory/         # 5 个 factory（ChatServiceFactory / RerankModelFactory / VectorStoreStrategyFactory / EmbeddingModelFactory / ResourceLoaderFactory）
├── domain/          # 90 个 .java（entity 18 / dto 4 及其余 bo、vo，按 agent/chat/knowledge/mcp/shortdrama 分子包）
├── mapper/          # MyBatis-Plus mapper，20 个 .java（agent/chat/knowledge/mcp/shortdrama）
├── config/          # Spring 配置，5 个 .java（VectorStoreProperties / McpSseConfig / KnowledgeRetrievalAccessFilterProperties + agent/ mcp/ 子包）
├── agent/           # Agent 装配（config / domain / manager / tool）
├── argtrace/        # RAG 链路追踪（最近从 chat.* 移到 argtrace.*，参见 commit 9d439d1d），2 个 .java
├── observability/   # 可观测性（4 个 .java）
├── mcp/             # MCP 工具与服务（service / tools）
├── websocket/       # WebSocket 处理（chat）
├── common/          # process
├── constant/  enums/
```

## 入口 — ChatController

```java
@Controller
@RequestMapping("/chat")
public class ChatController {
    private final ChatServiceFacade chatService;

    @PostMapping("/send")
    @ResponseBody
    public SseEmitter sseChat(@RequestBody @Valid ChatRequest chatRequest) {
        return chatService.sseChat(chatRequest);
    }
}
```

`/chat/send` 是唯一的 chat 入口，**返回 `SseEmitter`**（Server-Sent Events）。SSE 而非 WebSocket 是项目默认选择（参见 `application.yml` 的 `sse.enabled: true`、`websocket.enabled: false`）——SSE 单向流、HTTP/1.1 长连接、自动重连，配合 LLM streaming 输出天然契合。

实际业务在 `ChatServiceFacade`（不是直接实现）。

参见：[chat-controller.md](../../raw/chat-source/chat-controller.md)。

## Factory 模式 — ChatServiceFactory

```java
@Component
public class ChatServiceFactory implements ApplicationContextAware {
    private final Map<String, AbstractChatService> chatServiceMap = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        Map<String, AbstractChatService> serviceMap = ctx.getBeansOfType(AbstractChatService.class);
        for (AbstractChatService service : serviceMap.values()) {
            chatServiceMap.put(service.getProviderName(), service);
        }
    }

    public AbstractChatService getOriginalService(String category) {
        AbstractChatService service = chatServiceMap.get(category);
        if (service == null) throw new IllegalArgumentException("不支持的模型类别: " + category);
        return service;
    }
}
```

**`ApplicationContextAware` + 自动收集所有 `AbstractChatService` 子类**——按 `providerName` 路由。

模型适配层（DeepSeek / Zhipu / OpenAI 等）每个实现 `AbstractChatService`，启动时自动注册到 factory，前端传 `provider` 时按名查表。

类似 factory 模式还用于：`RerankModelFactory`、`VectorStoreStrategyFactory`、`EmbeddingModelFactory`、`ResourceLoaderFactory`——都是「按类型路由」的可插拔架构。

参见：[chat-service-factory.md](../../raw/chat-source/chat-service-factory.md)。

## 向量库配置 — VectorStoreProperties + McpSseConfig

- `VectorStoreProperties` 绑定 `application.yml` 的 `vector-store.*` 段，支持 weaviate / milvus / qdrant 三选一
- `McpSseConfig` 配置 MCP server 的 SSE 传输（HTTP-based MCP 而非 stdio）

参见：[vector-store-properties.md](../../raw/chat-source/vector-store-properties.md)、[mcp-sse-config.md](../../raw/chat-source/mcp-sse-config.md)。

## RAG 链路追踪 — argtrace 包

**近期重构**：trace 相关代码从 `org.ruoyi.chat.*` 移到 `org.ruoyi.argtrace.*`（参见 commit `9d439d1d: refactor: move RAG trace classes to argtrace package`）。

- `RagTraceNodeTypes` —— 节点类型常量（retrieval / rerank / generation / tool_call 等）
- `RagTracePayloadBuilder` —— 构造 trace payload，写入 `trace_run` / `trace_node` 表

**注意**：`trace_run` 和 `trace_node` 在 `tenant.excludes` 白名单里，因为 trace writer 跑在异步线程上，不传递租户上下文——这是设计上故意放行的共享表。

## 多租户 + 多会话隔离

`ChatSessionOwnershipGuard`（service 层）确保用户只能访问自己的会话，避免越权读其他租户的会话。这是项目里**业务安全**的关键防线。