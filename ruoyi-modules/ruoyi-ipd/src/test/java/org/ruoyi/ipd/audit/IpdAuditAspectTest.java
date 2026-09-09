package org.ruoyi.ipd.audit;

import cn.dev33.satoken.exception.NotLoginException;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.service.AuditLogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-1 @IpdAudit 声明式审计切面单元测试（首个挂载域：SwitchingAcceptance）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P2-1 @IpdAudit 审计切面")
class IpdAuditAspectTest {

    @Mock
    private AuditLogService auditLogService;
    @Mock
    private IpdAuthSession session;
    @Mock
    private ProceedingJoinPoint pjp;
    @Mock
    private MethodSignature signature;

    private IpdAuditAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new IpdAuditAspect(auditLogService, session);
    }

    /** 供 SpEL/参数名解析用的样板方法（month + req.id 形态与首批挂载端点一致）。 */
    @SuppressWarnings("unused")
    public static Object sampleMethod(String month, SampleReq req) {
        return "OK";
    }

    public record SampleReq(Long id, String reason) {
    }

    /** 直接构造注解（注解实例无法 new，用动态代理）。 */
    private IpdAudit sampleAnnotationDirect() {
        return dynamicAnnotation("SWITCHING_LOCK", "switching_acceptance", "#req.id", "#month + ' | ' + #req.reason");
    }

    static IpdAudit dynamicAnnotation(String action, String entityType, String entityIdExpr, String reasonExpr) {
        return (IpdAudit) java.lang.reflect.Proxy.newProxyInstance(
            IpdAudit.class.getClassLoader(), new Class<?>[]{IpdAudit.class},
            (proxy, m, args) -> switch (m.getName()) {
                case "action" -> action;
                case "entityType" -> entityType;
                case "entityIdExpr" -> entityIdExpr;
                case "reasonExpr" -> reasonExpr;
                case "annotationType" -> IpdAudit.class;
                default -> m.getDefaultValue();
            });
    }

    private Person actor() {
        Person p = new Person();
        p.setId(7L);
        p.setName("甲");
        p.setPersonType("SUPER_ADMIN");
        p.setGroupId(3L);
        return p;
    }

    private void stubInvocation(String month, SampleReq req) throws NoSuchMethodException {
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{month, req});
        when(signature.getMethod())
            .thenReturn(IpdAuditAspectTest.class.getMethod("sampleMethod", String.class, SampleReq.class));
    }

    @Test
    @DisplayName("成功路径：落一条审计，actor 从会话推导，SpEL 提取 entityId/reason")
    void successWritesAuditWithSpel() throws Throwable {
        IpdAudit ann = dynamicAnnotation("SWITCHING_LOCK", "switching_acceptance", "#req.id", "#month + ' | ' + #req.reason");
        stubInvocation("2026-09", new SampleReq(42L, "解除月度锁定"));
        when(pjp.proceed()).thenReturn("OK");
        when(session.currentPerson()).thenReturn(actor());

        Object result = aspect.auditAround(pjp, ann);

        assertThat(result).isEqualTo("OK");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("SWITCHING_LOCK");
        assertThat(log.getEntityType()).isEqualTo("switching_acceptance");
        assertThat(log.getEntityId()).isEqualTo(42L);
        assertThat(log.getReason()).isEqualTo("2026-09 | 解除月度锁定");
        assertThat(log.getOperatorId()).isEqualTo(7L);
        assertThat(log.getOperatorName()).isEqualTo("甲");
        assertThat(log.getOperatorRole()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("业务异常：不落审计且异常透传（与手工版「成功才审」口径一致）")
    void businessExceptionSkipsAuditAndPropagates() throws Throwable {
        IpdAudit ann = dynamicAnnotation("SWITCHING_RUN", "switching_acceptance", "", "#month");
        stubInvocation("2026-09", null);
        when(pjp.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.auditAround(pjp, ann)).isInstanceOf(IllegalStateException.class);
        verify(auditLogService, never()).append(any());
    }

    @Test
    @DisplayName("无登录上下文：跳过审计不抛（WARN 留痕）")
    void notLoginSkipsAuditQuietly() throws Throwable {
        IpdAudit ann = dynamicAnnotation("SWITCHING_RUN", "switching_acceptance", "", "#month");
        stubInvocation("2026-09", null);
        when(pjp.proceed()).thenReturn("OK");
        NotLoginException notLogin = org.mockito.Mockito.mock(NotLoginException.class);
        when(session.currentPerson()).thenThrow(notLogin);

        Object result = aspect.auditAround(pjp, ann);

        assertThat(result).isEqualTo("OK");
        verify(auditLogService, never()).append(any());
    }

    @Test
    @DisplayName("entityIdExpr 为空或缺省：entityId=null，不抛 SpEL 异常")
    void blankExprYieldsNull() throws Throwable {
        IpdAudit ann = dynamicAnnotation("SWITCHING_RUN", "switching_acceptance", "", "");
        stubInvocation("2026-09", null);
        when(pjp.proceed()).thenReturn("OK");
        when(session.currentPerson()).thenReturn(actor());

        aspect.auditAround(pjp, ann);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        assertThat(captor.getValue().getEntityId()).isNull();
        assertThat(captor.getValue().getReason()).isNull();
    }
}
