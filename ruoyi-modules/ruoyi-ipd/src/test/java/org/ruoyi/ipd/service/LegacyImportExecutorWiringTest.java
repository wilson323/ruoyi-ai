package org.ruoyi.ipd.service;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.mapper.*;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LegacyImportExecutorWiringTest {
    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ProjectService.class, () -> mock(ProjectService.class));
        context.registerBean(ProjectMapper.class, () -> mock(ProjectMapper.class));
        context.registerBean(StageActionMapper.class, () -> mock(StageActionMapper.class));
        context.registerBean(LegacyImportMapper.class, () -> mock(LegacyImportMapper.class));
        context.registerBean(IAuditLogService.class, () -> mock(IAuditLogService.class));
        return context;
    }
    private void executor(AnnotationConfigApplicationContext context, String name, Executor value) {
        context.registerBean(name, Executor.class, () -> value, definition -> definition.setPrimary(true));
    }
    @Test void explicitQualifierSelectsMainExecutorAmongMultiplePrimaryBeans() {
        try (var context = context()) {
            Executor main = Runnable::run;
            executor(context, "mainExecutor", main);
            executor(context, "applicationTaskExecutor", command -> { throw new AssertionError("wrong executor"); });
            executor(context, "scheduledExecutorService", command -> { throw new AssertionError("wrong executor"); });
            context.registerBean(LegacyImportService.class);
            context.refresh();
            assertSame(main, ReflectionTestUtils.getField(context.getBean(LegacyImportService.class), "executor"));
        }
    }
    @Test void unqualifiedConstructorFailsWithTheSameMultiplePrimaryBeans() {
        try (var context = context()) {
            executor(context, "mainExecutor", Runnable::run);
            executor(context, "applicationTaskExecutor", Runnable::run);
            context.registerBean(UnqualifiedConsumer.class);
            assertThrows(BeanCreationException.class, context::refresh);
        }
    }
    @Test void missingNamedExecutorCannotSilentlyChooseAnotherPrimary() {
        try (var context = context()) {
            executor(context, "applicationTaskExecutor", Runnable::run);
            context.registerBean(LegacyImportService.class);
            assertThrows(BeanCreationException.class, context::refresh);
        }
    }
    static final class UnqualifiedConsumer {
        UnqualifiedConsumer(Executor executor) { }
    }
}
