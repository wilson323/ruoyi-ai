package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R221 Task 9：双扫描器 @Scheduled 字面量锁定（防撞 09:00-09:50 已占满时段 + 防委托漂移）。
 * 范式照 R219SchedulerWiringTest：反射取 @Scheduled 断言字面量 + invoke + verify 委托。
 */
@Tag("dev")
class AiExecSchedulerWiringTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
            new MapperBuilderAssistant(new MybatisConfiguration(), "r221-exec-wiring");
        TableInfoHelper.initTableInfo(assistant, StageAction.class);
    }

    private static Method scheduledMethodOf(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Scheduled.class)) { return m; }
        }
        throw new AssertionError(clazz.getSimpleName() + " 缺少 @Scheduled 方法");
    }

    @Test
    void fallbackScannerUsesFixedDelay30s() throws Exception {
        Scheduled s = scheduledMethodOf(AiTaskFallbackScanner.class).getAnnotation(Scheduled.class);
        assertThat(s.fixedDelayString()).isEqualTo("${ipd.aiexec.fallback.interval-ms:30000}");
        assertThat(s.cron()).isEmpty();
    }

    @Test
    void proactiveSchedulerUsesCron0955() throws Exception {
        Scheduled s = scheduledMethodOf(AiProactiveScanScheduler.class).getAnnotation(Scheduled.class);
        assertThat(s.cron()).isEqualTo("0 55 9 * * ?");
    }

    @Test
    void fallbackScanDelegatesToEngineDispatchCycle() throws Exception {
        AiExecutionEngine engine = mock(AiExecutionEngine.class);
        AiTaskFallbackScanner scanner = new AiTaskFallbackScanner(engine);
        scheduledMethodOf(AiTaskFallbackScanner.class).invoke(scanner);
        verify(engine).dispatchCycle(50);
    }

    /** 引擎抛异常不吞调度线程：单轮异常 catch 住下轮继续（outbox 同款语义） */
    @Test
    void fallbackScanSwallowsEngineException() throws Exception {
        AiExecutionEngine engine = mock(AiExecutionEngine.class);
        when(engine.dispatchCycle(50)).thenThrow(new RuntimeException("boom"));
        AiTaskFallbackScanner scanner = new AiTaskFallbackScanner(engine);
        scheduledMethodOf(AiTaskFallbackScanner.class).invoke(scanner); // 不抛出即通过
        verify(engine).dispatchCycle(50);
    }

    /**
     * 主动扫描只触发 AI 档（AI_DIRECT/AI_GENERATE）7 日内到期 NOT_STARTED 动作；
     * HUMAN_GATE（C11）与目录外编码不触发。mock 合法性：instantiate 产物带
     * NOT_STARTED+dueDate 是真库正常组合。
     */
    @Test
    void proactiveScanTriggersOnlyAiModeActions() throws Exception {
        StageActionMapper mapper = mock(StageActionMapper.class);
        AiExecutionTrigger trigger = mock(AiExecutionTrigger.class);
        AiProactiveScanScheduler scheduler = new AiProactiveScanScheduler(mapper, trigger);
        scheduler.setClock(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("UTC")));

        StageAction c01 = action(1L, 100L, "C01");    // AI_GENERATE → 触发
        StageAction p08 = action(2L, 100L, "P08");    // AI_DIRECT → 触发
        StageAction c11 = action(3L, 100L, "C11");    // HUMAN_GATE → 不触发
        StageAction zzz = action(4L, 100L, "Z99");    // 目录外 → 不触发不阻断
        when(mapper.selectList(any())).thenReturn(List.of(c01, p08, c11, zzz));

        scheduledMethodOf(AiProactiveScanScheduler.class).invoke(scheduler);

        verify(trigger).triggerSchedule(eq(100L), eq("C01"), eq(1L));
        verify(trigger).triggerSchedule(eq(100L), eq("P08"), eq(2L));
        verify(trigger, never()).triggerSchedule(anyLong(), eq("C11"), anyLong());
        verify(trigger, never()).triggerSchedule(anyLong(), eq("Z99"), anyLong());
    }

    /** 单行 triggerSchedule 异常不阻断后续行（NotificationOutboxScanner 同款容错范式） */
    @Test
    void proactiveScanRowFailureDoesNotBlockNext() throws Exception {
        StageActionMapper mapper = mock(StageActionMapper.class);
        AiExecutionTrigger trigger = mock(AiExecutionTrigger.class);
        AiProactiveScanScheduler scheduler = new AiProactiveScanScheduler(mapper, trigger);
        scheduler.setClock(Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("UTC")));
        when(mapper.selectList(any())).thenReturn(List.of(action(1L, 100L, "C01"), action(2L, 100L, "P08")));
        when(trigger.triggerSchedule(anyLong(), anyString(), anyLong()))
            .thenThrow(new RuntimeException("db down"))
            .thenReturn(null);

        scheduledMethodOf(AiProactiveScanScheduler.class).invoke(scheduler);

        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(trigger, org.mockito.Mockito.times(2)).triggerSchedule(eq(100L), codes.capture(), anyLong());
        assertThat(codes.getAllValues()).containsExactly("C01", "P08");
    }

    private static StageAction action(long id, long projectId, String code) {
        StageAction a = new StageAction();
        a.setId(id);
        a.setProjectId(projectId);
        a.setActionCode(code);
        a.setStatus("NOT_STARTED");
        a.setDueDate(Date.from(Instant.parse("2026-10-01T00:00:00Z"))); // 7 日内到期
        return a;
    }
}
