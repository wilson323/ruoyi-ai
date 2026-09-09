package org.ruoyi.ipd.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.security.IpdActor;
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
 * <p>SpEL 降级：entityId/reason 解析失败按空串落审计并 WARN，<b>不</b>让审计失败阻断业务返回
 * （append 自身的 requireJson/锚行锁异常仍会传播——那是数据正确性问题，不是表达式问题）。
 */
@Aspect
@Component
public class IpdAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(IpdAuditAspect.class);

    private final AuditLogService auditLogService;
    private final IpdPermission ipdPermission;

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    public IpdAuditAspect(AuditLogService auditLogService, IpdPermission ipdPermission) {
        this.auditLogService = auditLogService;
        this.ipdPermission = ipdPermission;
    }

    @Around("@annotation(ipdAudit)")
    public Object around(ProceedingJoinPoint pjp, IpdAudit ipdAudit) throws Throwable {
        IpdActor actor = null;
        if (ipdAudit.adminOnly()) {
            actor = ipdPermission.requireAdmin();
        } else if (!ipdAudit.operator().isBlank()) {
            actor = evalOperator(pjp, ipdAudit.operator());
        }
        Object result = pjp.proceed();
        appendAfterReturn(pjp, ipdAudit, actor, result);
        return result;
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
            Long entityId = evalLong(vars, ann.entityId(), "entityId", method.getName());
            if (entityId != null) {
                builder.entityId(entityId);
            }
            String reason = evalString(vars, ann.reason(), "reason", method.getName());
            if (reason != null && !reason.isBlank()) {
                builder.reason(reason);
            }
            auditLogService.append(builder.build());
        } catch (RuntimeException e) {
            // append 的数据正确性异常（requireJson/锚行缺失）必须传播——只有 SpEL 表达式
            // 环节按空串降级，已在 evalXxx 内处理；到这里说明落库通道本身出错，不能吞。
            throw e;
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
