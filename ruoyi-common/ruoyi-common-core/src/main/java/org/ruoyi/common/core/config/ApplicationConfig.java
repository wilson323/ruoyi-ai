package org.ruoyi.common.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * 程序注解配置（R28.5 PERF-P0-3 @Async 异常黑洞修复 + AsyncConfigurer 多 Bean 冲突根治）。
 *
 * <p>注册 {@link AsyncUncaughtExceptionHandler} 统一处理 {@code @Async} 方法
 * 抛出但调用方已无法接收的异常——之前默认仅 stderr 打印，无业务告警，
 * 审计 / AI 解析失败可能被静默。修复后：
 * <ul>
 *   <li>异常进 SLF4J ERROR 日志（带 method + params 上下文）</li>
 *   <li>集成 TraceIdFilter MDC，traceId 串联可追踪</li>
 *   <li>统一走 log.error 触发 ELK / Loki 聚合告警</li>
 * </ul>
 *
 * <p><b>R28.5 根治（2026-09-09）</b>：解除本类与 {@code org.springframework.scheduling.annotation.AsyncConfigurer} 的实现关系
  * （详见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md）。
 * 原因：Spring Boot 自带 {@code applicationTaskExecutorAsyncConfigurer}
 * （{@code @ConditionalOnMissingBean}），与我们自定义的 AsyncConfigurer 同时注册，
 * 启动期未炸（懒解析），首次跑 {@code @Async} 方法时 Spring 抛
 * {@code Only one AsyncConfigurer may exist}（{@code AbstractAsyncConfiguration.setConfigurers}），
 * 异常被全局 {@code IpdServiceExceptionAdvice} 兜底成 HTTP 500 + code:90001。
 * 修复方式：仅暴露 {@code @Bean("taskExecutor")} 与 {@code @Bean asyncUncaughtExceptionHandler}，
 * 让 Spring Boot 默认 AsyncConfigurer 单独接管，按类型自动注入我们的 taskExecutor，
 * 线程池行为不变。
 *
 * <p>若未来需要写审计日志（IPD audit_logs.async_failure），只需在本 bean 内
 * 注入 AuditLogService 后调用即可，无需散落到各 @Async 方法。
 *
 * @author Lion Li (PERF-P0-3 修复)
 * @author R28.5 会话（AsyncConfigurer 多 Bean 冲突根治）
 */
@Slf4j
@AutoConfiguration
@EnableAspectJAutoProxy
@EnableAsync(proxyTargetClass = true)
public class ApplicationConfig {

    /**
     * 全局默认 @Async 线程池（沿用 Spring Boot 默认配置，避免影响现有业务）。
     * <p>R28.5：不再作为 {@code AsyncConfigurer.getAsyncExecutor()} 返回，而是
     * 普通 {@code @Bean(name="taskExecutor")}，Spring Boot 默认 AsyncConfigurer
     * 按类型自动找到该 Executor 并接管异步任务派发。
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
     * @Async 未捕获异常统一处理器（PERF-P0-3 核心修复）。
     * <p>return-type-void 方法的异常会走这里；Future 类型的方法异常由调用方 .get() 触发。
     * <p>R28.5：不再作为 {@code AsyncConfigurer.getAsyncUncaughtExceptionHandler()} 返回，
     * 而是普通 {@code @Bean}，Spring Boot 默认 AsyncConfigurer 自动按类型注入。
     */
    @Bean(name = "asyncUncaughtExceptionHandler")
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(
            "[AsyncUncaught] method={} declaringClass={} params={}",
            method.getName(),
            method.getDeclaringClass().getSimpleName(),
            Arrays.toString(params),
            ex
        );
    }
}