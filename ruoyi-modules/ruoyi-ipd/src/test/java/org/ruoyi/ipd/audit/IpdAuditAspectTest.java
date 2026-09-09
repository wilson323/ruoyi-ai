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
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.AuditLogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IpdAuditAspect 双线合并版测试（2026-09-09）：
 * <ul>
 *   <li>R22 框架用例（本地线）：adminOnly 门禁先行 / entityId+reason 属性名 / SpEL 坏表达式降级。</li>
 *   <li>P2-1 声明式用例（origin 线）：entityIdExpr/reasonExpr 别名 / 会话取操作人 /
 *       无登录静默跳过 / 空表达式 null。</li>
 *   <li>合并语义：审计落库失败旁路容错（P2轮三复审 P1 裁决）由主代码 catch 保证，用例不重复断言。</li>
 * </ul>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IpdAuditAspect 审计切面（R22+P2轮三双线合并）")
class IpdAuditAspectTest {

    @Mock private AuditLogService auditLogService;
    @Mock private IpdPermission permission;
    @Mock private IpdAuthSession session;
    @Mock private ProceedingJoinPoint pjp;
    @Mock private MethodSignature signature;

    private IpdAuditAspect aspect;

    /* ===================== R22 框架样例（真注解 + entityId/reason/adminOnly 属性名） ===================== */

    record SampleReq(Long id, String month) { }

    static class Sample {
        @IpdAudit(action = "SAMPLE_ACT", entityType = IpdEntityType.PROJECTS,
                  entityId = "#req.id", reason = "'月 ' + #req.month", adminOnly = true)
        public String ok(SampleReq req) { return "done"; }

        @IpdAudit(action = "SAMPLE_ACT", entityType = IpdEntityType.PROJECTS,
                  entityId = "#badExpression!!!", adminOnly = true)
        public String badSpel(SampleReq req) { return "done"; }
    }

    /* ===================== P2轮三样板（动态注解 + entityIdExpr/reasonExpr 别名；SwitchReq.id/reason 与首批挂载端点形态一致） ===================== */

    public record SwitchReq(Long id, String reason) {
    }

    @SuppressWarnings("unused")
    public static Object sampleMethod(String month, SwitchReq req) {
        return "OK";
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

    private Person person() {
        Person p = new Person();
        p.setId(7L);
        p.setName("甲");
        p.setPersonType("SUPER_ADMIN");
        p.setGroupId(3L);
        return p;
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

    private void stubInvocation(String month, SwitchReq req) throws NoSuchMethodException {
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{month, req});
        when(signature.getMethod())
            .thenReturn(IpdAuditAspectTest.class.getMethod("sampleMethod", String.class, SwitchReq.class));
    }

    /* ===================== R22 用例（adminOnly + entityId/reason 属性名，兼容构造器） ===================== */

    @Test
    @DisplayName("R22 adminOnly：requireAdmin 先行，append 收齐 operator 三元组 + entityId/reason SpEL")
    void adminOnlyAppendsWithActorTripleAndSpel() throws Throwable {
        aspect = new IpdAuditAspect(auditLogService, permission);
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
    @DisplayName("R22 业务异常：proceed 抛出不落审计且异常原样传播")
    void businessExceptionSkipsAppend() throws Throwable {
        aspect = new IpdAuditAspect(auditLogService, permission);
        when(permission.requireAdmin()).thenReturn(new IpdActor(1L, "a", "r", null));
        when(pjp.proceed()).thenThrow(new IllegalStateException("biz fail"));

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
    @DisplayName("R22 SpEL 坏表达式：entityId 按空降级，业务返回不被审计表达问题阻断")
    void badSpelDegradesToEmptyButStillAppends() throws Throwable {
        aspect = new IpdAuditAspect(auditLogService, permission);
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

    /* ===================== P2轮三用例（session 通道 + entityIdExpr/reasonExpr 别名） ===================== */

    @BeforeEach
    void setUp() {
        aspect = new IpdAuditAspect(auditLogService, session);
    }

    @Test
    @DisplayName("P2轮三 成功路径：会话取操作人，entityIdExpr/reasonExpr 别名 SpEL 提取")
    void successWritesAuditWithSpel() throws Throwable {
        IpdAudit ann = dynamicAnnotation("SWITCHING_LOCK", "switching_acceptance", "#req.id", "#month + ' | ' + #req.reason");
        stubInvocation("2026-09", new SwitchReq(42L, "解除月度锁定"));
        when(pjp.proceed()).thenReturn("OK");
        when(session.currentPerson()).thenReturn(person());

        Object result = aspect.auditAround(pjp, ann);

        assertThat(result).isEqualTo("OK");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getAction()).isEqualTo("SWITCHING_LOCK");
        assertThat(row.getEntityType()).isEqualTo("switching_acceptance");
        assertThat(row.getEntityId()).isEqualTo(42L);
        assertThat(row.getReason()).isEqualTo("2026-09 | 解除月度锁定");
        assertThat(row.getOperatorId()).isEqualTo(7L);
        assertThat(row.getOperatorName()).isEqualTo("甲");
        assertThat(row.getOperatorRole()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("P2轮三 业务异常：不落审计且异常透传")
    void businessExceptionSkipsAuditAndPropagates() throws Throwable {
        IpdAudit ann = dynamicAnnotation("SWITCHING_RUN", "switching_acceptance", "", "#month");
        stubInvocation("2026-09", null);
        when(pjp.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.auditAround(pjp, ann)).isInstanceOf(IllegalStateException.class);
        verify(auditLogService, never()).append(any());
    }

    @Test
    @DisplayName("P2轮三 无登录上下文：跳过审计不抛（WARN 留痕）")
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
    @DisplayName("P2轮三 空表达式：entityId=null，不抛 SpEL 异常")
    void blankExprYieldsNull() throws Throwable {
        IpdAudit ann = dynamicAnnotation("SWITCHING_RUN", "switching_acceptance", "", "");
        stubInvocation("2026-09", null);
        when(pjp.proceed()).thenReturn("OK");
        when(session.currentPerson()).thenReturn(person());

        aspect.auditAround(pjp, ann);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        assertThat(captor.getValue().getEntityId()).isNull();
        assertThat(captor.getValue().getReason()).isNull();
    }
}
