package org.ruoyi.common.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R28.5 防线 4：ApplicationConfig 异步装配的结构断言。
 *
 * <p>设计意图：如果有人把 {@code implements AsyncConfigurer} 加回 ApplicationConfig，
 * 这里立刻报红，不必等到生产环境第一次 {@code @Async} 派发才炸
 * （届时抛 {@code IllegalStateException: Only one AsyncConfigurer may exist}，
 * 与 Spring Boot 默认的 applicationTaskExecutorAsyncConfigurer 冲突）。
 *
 * <p><b>本类 2026-10-03 的形态变更，改动者请注意：</b>
 * 原实现是「无 JUnit 注解的 public class + static main()」，靠
 * {@code scripts/run-async-smoke-test.sh} 用 javac + java 直接跑；那个脚本
 * <b>全仓零调用</b>（不在 CI、不在任何门禁），等于防线 4 从未真正生效。
 * 起因是 ruoyi-common-core 当时刻意不引入测试框架，于是有人为「让 surefire 跑到它」
 * 加了 {@code @Tag("dev")}——该注解对没有 @Test 方法的类本就无效，surefire 不会执行它，
 * 代价却是整个 36 模块反应堆在本模块 {@code testCompile} 处被掐断。
 * 现改为标准 JUnit 5 测试，并给本模块补上 junit-jupiter（test 作用域）：
 * 父 pom 的 surefire 统一打开 {@code groups/excludedGroups}，缺 JUnit 引擎的模块
 * 连一个测试类都没有也会失败——所以那个依赖是必需的，不是可选项。
 * 原脚本已随之退役删除，防线 4 现在由每次 {@code mvn test} 负责执行。
 *
 * <p>规约：docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md
 *
 * @see ApplicationConfig
 */
@Tag("dev")
@DisplayName("R28.5 防线 4：ApplicationConfig 禁止 implements AsyncConfigurer")
class ApplicationConfigSmokeTest {

    private static final String TARGET = "org.ruoyi.common.core.config.ApplicationConfig";

    @Test
    @DisplayName("禁止 implements AsyncConfigurer（与 Spring Boot 默认 Bean 冲突）")
    void doesNotImplementAsyncConfigurer() throws Exception {
        Class<?> cls = Class.forName(TARGET);
        assertFalse(AsyncConfigurer.class.isAssignableFrom(cls),
            "ApplicationConfig 禁止 implements AsyncConfigurer（与 Spring Boot 默认 Bean 冲突，"
                + "运行期首次 @Async 派发会抛 IllegalStateException: Only one AsyncConfigurer may exist）。"
                + "实际类：" + cls.getName());
    }

    @Test
    @DisplayName("必须标 @EnableAsync，否则 @Async 不会派发")
    void hasEnableAsyncAnnotation() throws Exception {
        Class<?> cls = Class.forName(TARGET);
        assertNotNull(cls.getAnnotation(EnableAsync.class),
            "ApplicationConfig 必须标 @EnableAsync，否则 @Async 永远不派发");
    }

    @Test
    @DisplayName("getAsyncExecutor() 必须 @Bean 暴露（容器按类型找 Executor）")
    void exposesTaskExecutorBean() throws Exception {
        Method m = ApplicationConfig.class.getDeclaredMethod("getAsyncExecutor");
        assertNotNull(m.getAnnotation(Bean.class),
            "getAsyncExecutor() 必须标 @Bean，容器才能把它识别为 taskExecutor");
    }

    @Test
    @DisplayName("getAsyncUncaughtExceptionHandler() 必须 @Bean 暴露")
    void exposesAsyncUncaughtExceptionHandlerBean() throws Exception {
        Method m = ApplicationConfig.class.getDeclaredMethod("getAsyncUncaughtExceptionHandler");
        assertNotNull(m.getAnnotation(Bean.class),
            "getAsyncUncaughtExceptionHandler() 必须标 @Bean，容器才能把它识别为异常处理器");
    }

    @Test
    @DisplayName("getAsyncExecutor() 返回类型必须是 java.util.concurrent.Executor")
    void getAsyncExecutorReturnsExecutorType() throws Exception {
        Method m = ApplicationConfig.class.getDeclaredMethod("getAsyncExecutor");
        assertTrue(Executor.class.isAssignableFrom(m.getReturnType()),
            "getAsyncExecutor() 返回类型必须是 java.util.concurrent.Executor，实际="
                + m.getReturnType().getName());
    }
}
