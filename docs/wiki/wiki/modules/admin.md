---
topic: modules/admin
title: ruoyi-admin — Spring Boot 主应用入口
updated: 2026-09-04
raw:
  - raw/project-skeleton/application-dev-yml.md
  - raw/project-skeleton/application-prod-yml.md
---

# ruoyi-admin — Spring Boot 主应用入口

`ruoyi-admin` 是 RuoYi-AI 项目的 Spring Boot 主应用模块，承担应用启动、REST 入口聚合、全局配置三大职责。整个项目虽然有 5 个功能模块，但**只有 `ruoyi-admin` 是可独立部署的应用**，其余模块都是依赖库（参见 `pom.xml` 的 `<packaging>pom</packaging>` 是父 POM，子模块也都是 jar 库依赖）。

## 项目元信息

- **groupId / artifactId / version**：`org.ruoyi / ruoyi-ai / ${revision}`，`revision` 由 `flatten-maven-plugin` 解析为 `3.1.0`
- **Spring Boot**：`3.5.8`，**Java**：`17`
- **构建产物**：父 POM 聚合 4 个子模块（`ruoyi-admin`、`ruoyi-common`、`ruoyi-extend`、`ruoyi-modules`），最终打成单个可执行 jar 由 `ruoyi-admin` 提供

## 主应用入口

[org.ruoyi.RuoYiAIApplication](../../../../ruoyi-admin/src/main/java/org/ruoyi/RuoYiAIApplication.java) 是 Spring Boot 启动类，当前直接运行 Spring，**不会自动终止占用端口的进程**。历史自动清理代码不代表当前实现；发生端口冲突须核对实际服务归属，本机 IPD 使用配置端口 16039。

```java
@SpringBootApplication
public class RuoYiAIApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RuoYiAIApplication.class);
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
    }
}
```

## 默认端口与 HTTP 配置

- **HTTP 端口**：`6039`（`server.port`，`application.yml`）
- **Servlet 容器**：Undertow（Spring Boot 默认 Tomcat 在 `ruoyi-admin` 切换为 Undertow，因为 AI 场景下 Undertow 的 NIO 更适合大量 SSE 长连接）
- **Undertow 关键参数**：
  - `max-http-post-size: -1`（无限 POST 大小，配合 AI 大上下文）
  - `buffer-size: 512`
  - `threads.io: 8`、`threads.io worker: 256`
- **Spring 异步线程池前缀**：`async-`，由 Spring 自管（3.5 起不需要自配 `AsyncConfig`）

## 多 Profile 配置

`application.yml` 通过 `${SPRING_PROFILES_ACTIVE:dev}` 切换，3 个 profile 文件：

| 文件 | 适用环境 | 关键差异 |
|---|---|---|
| `application.yml` | 基线 | `demo.enabled: true` 默认演示模式；公共配置 |
| `application-dev.yml` | 开发 | dev profile 启用 `@Tag("dev")` Surefire 过滤；详细调试配置 |
| `application-prod.yml` | 生产 | `demo.enabled: false`；日志级别 WARN；性能优化参数 |

Maven profile 与 Spring profile 一一对应：`mvn spring-boot:run -Pprod` 等价于 `-Dspring-boot.run.profiles=prod`。

## 关键开关与边界

| 开关 | 默认值 | 位置 | 用途 |
|---|---|---|---|
| `demo.enabled` | `true` | `application.yml` | 演示模式：拦截所有写操作（POST/PATCH/DELETE），返回「演示模式，不允许操作」 |
| `demo.excludes` | `/login`, `/logout`, `/chat/send` 等 | `application.yml:296-308` | 写操作白名单（聊天、登录、系统接口） |
| `tenant.enable` | `true` | `application.yml` | 多租户过滤 |
| `tenant.excludes` | `sys_menu`, `sys_tenant` 等 14 张表 | `application.yml:148-161` | 不参与租户过滤的共享表 |
| `vector-store.type` | `weaviate` | `application.yml` | 向量库选择（weaviate / milvus / qdrant） |
| `websocket.enabled` | `false` | `application.yml` | WebSocket 开关（默认关，用 SSE） |
| `sse.enabled` | `true` | `application.yml` | SSE 默认开，路径 `/resource/sse` |
| `trace.enabled` | `true` | `application.yml` | 链路追踪（异步线程写 trace_run/trace_node） |

## Springdoc OpenAPI 分组

`application.yml:227-239` 定义了 6 个 OpenAPI 分组，对应不同的 package 扫描路径：

1. 演示模块 — `org.ruoyi.demo`
2. 通用模块 — `org.ruoyi.web`
3. 系统模块 — `org.ruoyi.system`
4. 代码生成模块 — `org.ruoyi.generator`
5. 工作流模块 — `org.ruoyi.workflow`
6. MCP 模块 — `org.ruoyi.mcp`

Swagger UI 在 `/swagger-ui.html`。

## Coding Harness（嵌入式 agent runtime）

`application.yml:24-32` 有个不寻常的配置段——**嵌入式 AI agent 运行时**：

```yaml
coding:
  harness:
    budget:
      max-iterations: 200
      max-tool-calls: 600
    tools:
      execute-process:
        enabled: true
```

这是项目内置的 agent runtime（commit history 显示 `feat: add coding harness runtime and CMS module`），agent 在每个 chat 会话中可调用，预算 200 轮、600 次工具调用。`execute-process.enabled=true` 允许 agent shell 出本进程——**安全敏感点**，需要在生产环境评估。

> **⚠️ 过时标注（2026-10-03 追加）**：上文逐字引用的 `coding.harness.tools.execute-process.enabled` 配置项**已不存在**——它随 ADR-0077 于 2026-10-02 删除（自建 coding harness 链与 `/coding/harness` controller 下线，改用官方 `HarnessAgent.builder()` 装配）。该键现不存在于任何 yml；本节 YAML 与「安全敏感点」评述均为**历史快照**，不得作为现役配置或安全评估依据。现役唯一相关键为 `coding.harness.workspace.shared-root`（挂在幸存的 `CodingWorkspaceService` 上）。依据：`docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md` 及根 CLAUDE.md「Coding harness」条。

## 测试约定

Maven Surefire 配 `<groups>${profiles.active}</groups>`，意味着：
- 默认 `dev` profile 只跑 `@Tag("dev")` 测试
- 新测试类必须显式 `@Tag("dev")` 才会被 `mvn test` 执行
- `@Tag("exclude")` 显式排除（性能 / 手动测试）

## 模块依赖图（admin）

`ruoyi-admin` 通过 `pom.xml` 的 `<dependency>` 引入：

- `ruoyi-system`（RBAC）
- `ruoyi-generator`（代码生成）
- `ruoyi-chat`（AI 核心）

3 个功能模块全部聚合到 admin，最终由 admin 单一进程启动（`ruoyi-workflow` / `ruoyi-aiflow` 已于 2026-10-02 下线）。`ruoyi-common` 里的 27 个工具模块（`ruoyi-common-bom`）由子模块逐个引用。

## 排除路径（security.excludes）

`security.excludes` 是 Sa-Token 的认证白名单，包括静态资源、API docs 等。