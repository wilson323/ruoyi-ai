package org.ruoyi.common.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.reflect.Method;

/**
 * R28.5 防线 4 配套静态测试：验证 ApplicationConfig 不实现 AsyncConfigurer。
 *
 * <p>不依赖 JUnit/spring-boot-starter-test（ruoyi-common-core 不引入测试框架，
 * 加依赖会改已跟踪 pom.xml 触发兄弟会话冲突风险）。
 * 用 JDK 反射 + main 方法做断言，通过 {@code scripts/run_async_smoke_test.sh} 调用。
 *
 * <p>设计意图：每次启动期如果有人误把 {@code implements AsyncConfigurer} 加回来，
 * 跑这个 smoke 立刻失败（不必等到生产第一次 @Async 调用才暴露 IllegalStateException）。
 *
 * <p>规约：docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md
 *
 * @see ApplicationConfig
 */
public class ApplicationConfigSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("▶ R28.5 ApplicationConfig smoke test (静态反射)");
        testDoesNotImplementAsyncConfigurer();
        testHasEnableAsyncAnnotation();
        testExposesTaskExecutorBean();
        testExposesAsyncUncaughtExceptionHandlerBean();
        testGetAsyncExecutorReturnsExecutorType();
        System.out.println("----------------------------------------");
        System.out.println("PASSED=" + passed + " FAILED=" + failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    /** 防线 4 核心：禁止 implements AsyncConfigurer（与 Spring Boot 默认 applicationTaskExecutorAsyncConfigurer 冲突） */
    static void testDoesNotImplementAsyncConfigurer() throws Exception {
        Class<?> cls = Class.forName("org.ruoyi.common.core.config.ApplicationConfig");
        boolean impl = AsyncConfigurer.class.isAssignableFrom(cls);
        check(!impl,
            "ApplicationConfig 禁止 implements AsyncConfigurer（与 Spring Boot 默认 Bean 冲突，"
                + "运行期首次 @Async 派发会抛 IllegalStateException: Only one AsyncConfigurer may exist）");
    }

    /** 必须标 @EnableAsync 才会激活 @Async 派发 */
    static void testHasEnableAsyncAnnotation() throws Exception {
        Class<?> cls = Class.forName("org.ruoyi.common.core.config.ApplicationConfig");
        EnableAsync ea = cls.getAnnotation(EnableAsync.class);
        check(ea != null, "ApplicationConfig 必须标 @EnableAsync");
    }

    /** getAsyncExecutor() 必须 @Bean 暴露（Spring Boot 默认 AsyncConfigurer 按类型找 Executor） */
    static void testExposesTaskExecutorBean() throws Exception {
        Method m = ApplicationConfig.class.getDeclaredMethod("getAsyncExecutor");
        Bean bean = m.getAnnotation(Bean.class);
        check(bean != null, "getAsyncExecutor() 必须标 @Bean（容器才能识别为 taskExecutor）");
    }

    /** getAsyncUncaughtExceptionHandler() 必须 @Bean 暴露 */
    static void testExposesAsyncUncaughtExceptionHandlerBean() throws Exception {
        Method m = ApplicationConfig.class.getDeclaredMethod("getAsyncUncaughtExceptionHandler");
        Bean bean = m.getAnnotation(Bean.class);
        check(bean != null, "getAsyncUncaughtExceptionHandler() 必须标 @Bean（容器才能识别异常处理器）");
    }

    /** getAsyncExecutor() 返回类型必须是 java.util.concurrent.Executor（接口契约） */
    static void testGetAsyncExecutorReturnsExecutorType() throws Exception {
        Method m = ApplicationConfig.class.getDeclaredMethod("getAsyncExecutor");
        check(java.util.concurrent.Executor.class.isAssignableFrom(m.getReturnType()),
            "getAsyncExecutor() 返回类型必须是 java.util.concurrent.Executor（实际=" + m.getReturnType().getName() + "）");
    }

    static void check(boolean cond, String msg) {
        if (cond) {
            passed++;
            System.out.println("  ✅ " + msg);
        } else {
            failed++;
            System.out.println("  ❌ " + msg);
        }
    }
}