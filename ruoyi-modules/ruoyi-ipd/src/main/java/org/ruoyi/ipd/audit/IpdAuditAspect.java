package org.ruoyi.ipd.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import cn.dev33.satoken.exception.NotLoginException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * IPD 审计切面（审计 AOP 改造设计-20260909 §3，R22 落地）。
 * <p>语义：方法<b>成功返回后</b>同步落一条审计；业务异常时不落（与手写 append 位于
 * return 前的既有语义一致）。落库通道红线：直接调 {@link AuditLogService#append}
 * （REQUIRES_NEW + 锚行锁），<b>不做</b> publishEvent 异步（设计 §3.3）。
 * <p>权限：{@code adminOnly=true} 时切面在 proceed 前 {@code requireAdmin()}——
 * 门禁语义与原「方法体内先取 actor」一致（拒绝在业务执行前抛出）。
 * <p>SpEL 降级：entityId/reason 解析失败按空串落审计并 WARN，<b>不</b>让审计失败阻断业务返回。
 * <p>操作人四通道（2026-09-09 双线合并后优先级）：adminOnly→requireAdmin()（门禁前置）＞
 * operator SpEL ＞ systemOperatorId ＞ IpdAuthSession.currentPerson()（P2轮三通道；
 * 无登录上下文时 WARN 跳过整条审计——受保护写端点不应出现，疑似鉴权链异常）。
 * <p>旁路容错（P2轮三蜂群复审 P1 裁决，2026-09-09 双线合并采纳）：业务成功后审计落库失败
 * （含 requireJson/锚行锁异常）只记 ERROR 不抛——把成功响应变 500 会误导非幂等端点重试双写；
 * ERROR 日志含异常栈即为监控告警信号，数据正确性问题通过日志告警显性化，不再通过传播异常。
 */
@Aspect
@Component
public class IpdAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(IpdAuditAspect.class);

    private final AuditLogService auditLogService;
    private final IpdPermission ipdPermission;
    private final IpdAuthSession ipdAuthSession;

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    /** 兼容旧签名（R22 时代无 session 通道）；生产装配走三参构造器。 */
    public IpdAuditAspect(AuditLogService auditLogService, IpdPermission ipdPermission) {
        this(auditLogService, ipdPermission, null);
    }

    /** 兼容旧签名（P2轮三时代无 permission/operator 通道）。 */
    public IpdAuditAspect(AuditLogService auditLogService, IpdAuthSession ipdAuthSession) {
        this(auditLogService, null, ipdAuthSession);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IpdAuditAspect(AuditLogService auditLogService, IpdPermission ipdPermission, IpdAuthSession ipdAuthSession) {
        this.auditLogService = auditLogService;
        this.ipdPermission = ipdPermission;
        this.ipdAuthSession = ipdAuthSession;
    }

    @Around("@annotation(ipdAudit)")
    public Object auditAround(ProceedingJoinPoint pjp, IpdAudit ipdAudit) throws Throwable {
        IpdActor actor = null;
        if (ipdAudit.adminOnly()) {
            actor = ipdPermission.requireAdmin();
        } else if (!ipdAudit.operator().isBlank()) {
            actor = evalOperator(pjp, ipdAudit.operator());
        }
        Object result = pjp.proceed();
        // P2轮三通道（proceed 后取操作人，与业务异常传播不交叉）：受保护写端点从会话取真实操作人。
        if (actor == null && ipdAuthSession != null) {
            try {
                Person person = ipdAuthSession.currentPerson();
                actor = new IpdActor(person.getId(), person.getName(), person.getPersonType(), person.getGroupId());
            } catch (NotLoginException e) {
                log.warn("[IpdAudit] {} {} 跳过：无登录上下文（写端点不应出现，疑似鉴权链异常）",
                    ipdAudit.entityType(), ipdAudit.action());
                return result;
            }
        }
        appendAfterReturn(pjp, ipdAudit, actor, result);
        return result;
    }

    /** R22 旧名委托（测试直调兼容）。 */
    public Object around(ProceedingJoinPoint pjp, IpdAudit ipdAudit) throws Throwable {
        return auditAround(pjp, ipdAudit);
    }

    /** 仅在业务成功返回后调用；SpEL 上下文含 #result 与全部具名参数。 */
    private void appendAfterReturn(ProceedingJoinPoint pjp, IpdAudit ann,
                                   IpdActor actor, Object result) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            Map<String, Object> vars = bindArgs(method, pjp.getArgs());
            vars.put("result", result);

            AuditLog.AuditLogBuilder builder = AuditLog.builder()
                .action(ann.action())
                .entityType(ann.entityType())
                .createTime(new Date());
            if (actor != null) {
                builder.operatorId(actor.id()).operatorName(actor.name()).operatorRole(actor.role());
            } else if (ann.systemOperatorId() != 0L) {
                builder.operatorId(ann.systemOperatorId());
            }
            // 双属性名兼容：entityId/reason（R22）优先，为空再看 entityIdExpr/reasonExpr（P2轮三）
            String entityIdSpel = !ann.entityId().isBlank() ? ann.entityId() : ann.entityIdExpr();
            String reasonSpel = !ann.reason().isBlank() ? ann.reason() : ann.reasonExpr();
            Long entityId = evalLong(vars, entityIdSpel, "entityId", method.getName());
            if (entityId != null) {
                builder.entityId(entityId);
            }
            String reason = evalString(vars, reasonSpel, "reason", method.getName());
            if (reason != null && !reason.isBlank()) {
                builder.reason(reason);
            }
            auditLogService.append(builder.build());
        } catch (RuntimeException e) {
            // 旁路容错（P2轮三复审 P1 裁决）：业务已成功后审计落库失败不抛——防非幂等端点
            // 被误导重试双写；ERROR 含异常栈即监控告警信号（requireJson/锚行锁类数据正确性
            // 问题经日志告警显性化，不靠传播异常）。
            log.error("[IpdAudit] {} {} 审计落库失败（业务响应不受影响）",
                ann.entityType(), ann.action(), e);
        }
    }

    private Map<String, Object> bindArgs(Method method, Object[] args) {
        Map<String, Object> vars = new HashMap<>();
        String[] names = paramNameDiscoverer.getParameterNames(method);
        for (int i = 0; i < args.length; i++) {
            vars.put("p" + i, args[i]);
            if (names != null && i < names.length) {
                vars.put(names[i], args[i]);
            }
        }
        return vars;
    }

    private IpdActor evalOperator(ProceedingJoinPoint pjp, String spel) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Map<String, Object> vars = bindArgs(signature.getMethod(), pjp.getArgs());
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariables(vars);
            Expression expr = parser.parseExpression(spel, new TemplateParserContext());
            return expr.getValue(ctx, IpdActor.class);
        } catch (RuntimeException e) {
            log.warn("[ipd-audit] operator SpEL 解析失败（按无操作人降级）：method={} spel={} err={}",
                pjp.getSignature().toShortString(), spel, e.getMessage());
            return null;
        }
    }

    private Long evalLong(Map<String, Object> vars, String spel, String field, String method) {
        Object v = eval(vars, spel, field, method);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            log.warn("[ipd-audit] {} SpEL 结果非 Long（按空降级）：method={} value={}", field, method, v);
            return null;
        }
    }

    private String evalString(Map<String, Object> vars, String spel, String field, String method) {
        Object v = eval(vars, spel, field, method);
        return v == null ? null : String.valueOf(v);
    }

    private Object eval(Map<String, Object> vars, String spel, String field, String method) {
        if (spel == null || spel.isBlank()) {
            return null;
        }
        // 威胁模型：spel 仅来自 @IpdAudit 注解字面量（编译期常量，与 Spring @PreAuthorize/@Cacheable
        // 同一信任边界），不接任何运行时用户输入；StandardEvaluationContext 不构成注入面。
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariables(vars);
            // 非 template 模式：整串即表达式（#result.data.id / '字面量' 拼接均可用）
            Expression expr = parser.parseExpression(spel);
            return expr.getValue(ctx);
        } catch (RuntimeException e) {
            log.warn("[ipd-audit] {} SpEL 解析失败（按空降级）：method={} spel={} err={}",
                field, method, spel, e.getMessage());
            return null;
        }
    }
}
