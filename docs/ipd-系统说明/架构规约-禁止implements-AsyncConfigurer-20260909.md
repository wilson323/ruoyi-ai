# 架构规约：禁止 implements AsyncConfigurer（2026-09-09 立）

> 适用范围：本仓 `ruoyi-ai` 所有 Java 模块（`ruoyi-common` / `ruoyi-modules` / `ruoyi-extend` / `ruoyi-admin`）。
> 规约来源：R28.5 治理轮根因复盘（traceId `b65ece346803458cb636692a55f23c6e`）。

## 一、禁止行为

**任何业务模块的 `@Configuration` / `@AutoConfiguration` 类都不允许 `implements AsyncConfigurer`**。

```java
// ❌ 严禁
@AutoConfiguration
@EnableAsync
public class FooConfig implements AsyncConfigurer {  // ← 这里
    @Override
    public Executor getAsyncExecutor() { ... }
}

// ✅ 正确写法
@AutoConfiguration
@EnableAsync
public class FooConfig {
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() { ... }
}
```

## 二、为什么禁止（根因复盘）

### 现象

Spring Boot 2.7+ / 3.x 自带 `TaskExecutionAutoConfiguration`，其中包含一个 `@Bean @ConditionalOnMissingBean` 的 `applicationTaskExecutorAsyncConfigurer`：

```java
// Spring Boot 框架代码（org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration）
@Bean(name = "applicationTaskExecutor")
@ConditionalOnMissingBean(name = "applicationTaskExecutor")
public Executor applicationTaskExecutor(...) { ... }

// 在 Spring Boot 内部某处（TaskExecutorAutoConfigurationAdapter / 实际是 TaskExecutionProperties 配置链路）
// 还会向容器注册 applicationTaskExecutorAsyncConfigurer（实现 AsyncConfigurer 接口）
```

### 冲突链路

1. 我们自己写了 `@AutoConfiguration public class ApplicationConfig implements AsyncConfigurer`——Spring 把它识别为第 1 个 `AsyncConfigurer` Bean。
2. Spring Boot 的 `applicationTaskExecutorAsyncConfigurer` 标注了 `@ConditionalOnMissingBean(AsyncConfigurer.class)`——**理论上应该看到我们的就跳过自己**。
3. 但因为 `@ConditionalOnMissingBean` 是基于 BeanDefinition 的"声明时检查"，而我们的 `ApplicationConfig` 通过 `@AutoConfiguration` 自动配置时序晚于 Spring Boot 默认配置，`applicationTaskExecutorAsyncConfigurer` 已经先注册完了。
4. 容器里实际有 2 个 `AsyncConfigurer` Bean：我们的 + Spring Boot 默认的。
5. **Spring Boot 启动时没炸**——`AbstractAsyncConfiguration.setConfigurers(ObjectProvider)` 是懒注入，调用 `provider.get()` 时才解析。
6. **第一次跑 `@Async` 方法**（如登录触发的 `recordLogininfor`）：异步拦截器 `AsyncExecutionInterceptor.invoke` → `AsyncExecutionAspectSupport.determineAsyncExecutor` → 触发懒解析 → 查到 2 个 `AsyncConfigurer` → 抛 `IllegalStateException: Only one AsyncConfigurer may exist`。
7. **异常被全局 `IpdServiceExceptionAdvice` 兜底**——返回 `code:90001 系统内部错误`，前端表现为 HTTP 500（实际 traceId `b65ece346803458cb636692a55f23c6e`）。

### 为什么 9/7 的"修复"无效

R28 治理轮前兄弟会话曾尝试修复（`docs/ipd-系统说明/验收/platform-token-500修复-AsyncConfigurer-20260907.md`），但后续被 reset / 还原 / 兄弟会话覆盖。当前 HEAD `103d7b5e` 的 `ApplicationConfig.java:36` 仍是 `public class ApplicationConfig implements AsyncConfigurer`——证明根治必须**配合架构规约 + lint 门禁**才能防复发。

## 三、正确做法（异步机制保留）

异步能力本身不丢，只是换成"暴露普通 Bean 让 Spring Boot 默认 AsyncConfigurer 接管"的写法：

```java
@Slf4j
@AutoConfiguration
@EnableAspectJAutoProxy
@EnableAsync(proxyTargetClass = true)
public class ApplicationConfig {

    /**
     * 全局默认 @Async 线程池。Spring Boot 默认 AsyncConfigurer 按类型自动注入。
     */
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(512);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }

    /**
     * @Async 未捕获异常统一处理器。Spring Boot 默认 AsyncConfigurer 按类型自动注入。
     */
    @Bean(name = "asyncUncaughtExceptionHandler")
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(
            "[AsyncUncaught] method={} declaringClass={} params={}",
            method.getName(), method.getDeclaringClass().getSimpleName(),
            Arrays.toString(params), ex
        );
    }
}
```

- 容器里只剩 Spring Boot 默认那 1 个 `AsyncConfigurer`。
- 默认 `AsyncConfigurer` 按 `Executor` / `AsyncUncaughtExceptionHandler` 类型自动找到我们的 Bean 并使用。
- 线程池行为完全不变（core/max/queue/prefix 都一样）。
- 业务代码里的 `@Async` / `@Async("命名线程池")` 不需要任何修改。

## 四、@Async 落点分级（次级规约）

不是所有 `@Async` 都该用。本仓推荐：

| 场景 | 是否 @Async | 理由 |
|---|---|---|
| 事件监听器 `@Async + @EventListener`（登录/操作日志） | **否** | 写一行日志 < 5ms，事件链同步发布+同步消费语义最自然；异步会让异常链路断裂 |
| 远程 HTTP 同步调用链 | **否** | 同步串行最易调试，超时本来就该熔断 |
| 调用外部大模型 / 长任务 | **是** | 用 `@Async("knowledgeParseExecutor")` 等命名线程池，限流+超时可控 |
| 异步任务带 Future 返回 | **是** | 调用方 .get() 显式等待 |

## 五、门禁（防复发）

- `scripts/check_async_configurer_duplication.sh` —— 扫所有 `*.java`（排除 `.codex/` `.claude/worktrees/` `target/`），命中 `implements AsyncConfigurer` 或 `extends AsyncConfigurer` 时 exit 1。
- CI 接入位置：`.github/workflows/` 下新增 `ipd-async-configurer.yml`（待补；本轮交付脚本）。
- 任何被门禁拦住的 PR 一律打回，禁止 `@SuppressWarnings("AsyncConfigurer")` 类绕过。

## 六、回滚预案

如果未来 Spring Boot 框架修了 `applicationTaskExecutorAsyncConfigurer` 的注册时序问题（让 `@ConditionalOnMissingBean` 真正生效），可以重新评估放开本规约。但**当下必须保持禁止**——根因未在框架层修复前，业务代码不要赌时序。

## 七、相关链接

- 验收文档：`docs/ipd-系统说明/验收/platform-token-500修复-AsyncConfigurer-20260907.md`（9/7 兄弟会话已尝试修复，但未根治）
- log.md 登记：`docs/ipd-系统说明/log.md` § 2026-09-09 R28.5
- 记忆卡片：`common_pitfalls_experience` —— IPD 平台换票 500 根因复盘