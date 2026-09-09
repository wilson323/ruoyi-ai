package org.ruoyi.ipd.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.AuditLogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IpdAuditAspect 行为等价测试（R22 AOP P1 批验收：设计文档 §4「Controller 行为不变」的切面侧铁证）。
 * <p>覆盖：adminOnly 门禁先行 / 成功返回后同步 append（SpEL 解析 entityId/reason+operator 三元组）/
 * 业务异常不落审计 / SpEL 坏表达式按空降级不阻断业务返回。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class IpdAuditAspectTest {

    @Mock private AuditLogService auditLogService;
    @Mock private IpdPermission permission;
    @Mock private ProceedingJoinPoint pjp;
    @Mock private MethodSignature signature;

    private IpdAuditAspect aspect;

    /** SpEL 上下文样例参数（record 组件访问器） */
    record SampleReq(Long id, String month) { }

    static class Sample {
        @IpdAudit(action = "SAMPLE_ACT", entityType = IpdEntityType.PROJECTS,
                  entityId = "#req.id", reason = "'月 ' + #req.month", adminOnly = true)
        public String ok(SampleReq req) { return "done"; }

        @IpdAudit(action = "SAMPLE_ACT", entityType = IpdEntityType.PROJECTS,
                  entityId = "#badExpression!!!", adminOnly = true)
        public String badSpel(SampleReq req) { return "done"; }

        public String noAnno(SampleReq req) { return "done"; }
    }

    @BeforeEach
    void setUp() {
        aspect = new IpdAuditAspect(auditLogService, permission);
    }

    private IpdAudit anno(String method) throws Exception {
        return Sample.class.getMethod(method, SampleReq.class).getAnnotation(IpdAudit.class);
    }

    private void stubJoinPoint(String method, SampleReq req, Object result) throws Throwable {
        when(signature.getMethod()).thenReturn(Sample.class.getMethod(method, SampleReq.class));
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{req});
        when(pjp.proceed()).thenReturn(result);
    }

    @Test
    @DisplayName("adminOnly：requireAdmin 先行，成功返回后 append 收齐 operator 三元组 + SpEL 字段")
    void adminOnlyAppendsWithActorTripleAndSpel() throws Throwable {
        IpdActor actor = new IpdActor(9L, "张三", "SUPER_ADMIN", 2L);
        when(permission.requireAdmin()).thenReturn(actor);
        SampleReq req = new SampleReq(77L, "2026-08");
        stubJoinPoint("ok", req, "done");

        Object out = aspect.around(pjp, anno("ok"));

        assertThat(out).isEqualTo("done");
        verify(permission).requireAdmin();
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        AuditLog row = cap.getValue();
        assertThat(row.getAction()).isEqualTo("SAMPLE_ACT");
        assertThat(row.getEntityType()).isEqualTo(IpdEntityType.PROJECTS);
        assertThat(row.getEntityId()).isEqualTo(77L);
        assertThat(row.getReason()).isEqualTo("月 2026-08");
        assertThat(row.getOperatorId()).isEqualTo(9L);
        assertThat(row.getOperatorName()).isEqualTo("张三");
        assertThat(row.getOperatorRole()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("业务异常：proceed 抛出不落审计且异常原样传播（与手写 append 语义一致）")
    void businessExceptionSkipsAppend() throws Throwable {
        when(permission.requireAdmin()).thenReturn(new IpdActor(1L, "a", "r", null));
        // proceed 抛异常后 appendAfterReturn 不执行 → getSignature/getMethod/getArgs 均不会被调
        //（stub 了会挂 Mockito UnnecessaryStubbing，严格模式）
        when(pjp.proceed()).thenThrow(new IllegalStateException("biz fail"));

        // around 声明 throws Throwable，lambda 上下文不能抛受检异常 → try-catch 手动捕获
        Throwable[] held = new Throwable[1];
        try {
            aspect.around(pjp, anno("ok"));
        } catch (Throwable t) {
            held[0] = t;
        }
        assertThat(held[0]).isInstanceOf(IllegalStateException.class);
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("SpEL 坏表达式：entityId 按空降级，业务返回不被审计表达问题阻断")
    void badSpelDegradesToEmptyButStillAppends() throws Throwable {
        when(permission.requireAdmin()).thenReturn(new IpdActor(3L, "b", "r", null));
        SampleReq req = new SampleReq(5L, "x");
        stubJoinPoint("badSpel", req, "done");

        Object out = aspect.around(pjp, anno("badSpel"));

        assertThat(out).isEqualTo("done");
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertThat(cap.getValue().getEntityId()).isNull();
        assertThat(cap.getValue().getAction()).isEqualTo("SAMPLE_ACT");
    }
}
